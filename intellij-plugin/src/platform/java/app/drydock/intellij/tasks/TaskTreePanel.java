package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.SessionActivityListener;
import app.drydock.projects.SharedProject;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;

import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.treeStructure.Tree;

import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The Drydock tool window: every registered project and its tasks, live.
 *
 * <p>The tree is rebuilt from scratch on every change, but rebuilds are
 * coalesced — at most one pending — and gathered off the EDT ({@link
 * TaskTreeInputs} runs git and touches the disk). It listens to session
 * changes, activity changes (both application-wide, so agents in other
 * windows update it too) and the shared projects list.</p>
 */
public final class TaskTreePanel extends SimpleToolWindowPanel implements Disposable, UiDataProvider {

    /** The selected row, for the tool window's actions. */
    public static final DataKey<TaskNode> SELECTED_NODE = DataKey.create("drydock.task.node");

    /** Every selected row; bulk actions must not collapse this to the lead selection. */
    public static final DataKey<List<TaskNode>> SELECTED_NODES = DataKey.create("drydock.task.nodes");

    /** The main checkout of the project the selected row belongs to. */
    public static final DataKey<Path> SELECTED_PROJECT_ROOT = DataKey.create("drydock.task.projectRoot");

    /** Updates the visible tree as successful worktree removals complete. */
    static final DataKey<WorktreeRemovalUi> WORKTREE_REMOVAL_UI = DataKey.create("drydock.task.worktreeRemovalUi");

    @FunctionalInterface
    interface WorktreeRemovalUi {
        void worktreesRemoved(Set<Path> removed);
    }

    private static final Logger LOG = System.getLogger(TaskTreePanel.class.getName());

    private static final int SEARCH_DEBOUNCE_MILLIS = 150;

    private final Project project;
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final SearchTextField search = new SearchTextField(false);

    {
        // The /<project glob>/[items] scope is otherwise undiscoverable. On
        // the inner editor: hover lands there, and it never reads the
        // SearchTextField wrapper's tooltip.
        search.getTextEditor().setToolTipText("Search tasks, worktrees, and issues; "
                + "a plain search that matches a project name shows that project with all its entries. "
                + "Optional project scope first: /<name glob>/ — e.g. /dry*/jfr "
                + "searches jfr only within projects whose name matches dry*.");
    }
    private final TaskTreeInputs inputs = new TaskTreeInputs();
    private final TaskTreeRefreshRequests refreshRequests = new TaskTreeRefreshRequests();
    private final Alarm searchAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private volatile String filter = "";
    private volatile ViewFilter viewFilter = ViewFilter.ALL;
    private List<TaskNode> displayedNodes = List.of();
    private String displayedFilter = "";
    private ViewFilter displayedViewFilter = ViewFilter.ALL;
    /** Which lazy listing sections the user opened here, with the guard that keeps the
     * panel's own tree restoration from counting as such an open. */
    private final ListingExpansionTracker expansions =
            new ListingExpansionTracker(this::requestRebuild, TaskTreePanel::projectRootOf);
    /** One review materialization at a time per pull request; the flow itself refuses duplicates too. */
    private final PullRequestReview pullRequestReview = new PullRequestReview();
    /** One review materialization at a time per worktree; the flow itself refuses duplicates too. */
    private final WorktreeReview worktreeReview = new WorktreeReview();
    /** Guarded by {@code this}: subscribing after dispose() must undo itself. */
    private Runnable unsubscribeShared = () -> { };
    private volatile boolean disposed;
    /**
     * The refresh action's effects, tied to this panel's real caches and
     * rebuild scheduler: the seam the dispatch tests record against (F8).
     */
    private final RefreshTarget refreshTarget = new RefreshTarget() {
        @Override
        public void invalidateAll() {
            inputs.invalidateGitHub();
            inputs.invalidateJira();
        }

        @Override
        public void invalidateIssues(Path root) {
            inputs.invalidateIssues(root);
        }

        @Override
        public void invalidatePullRequests(Path root) {
            inputs.invalidatePullRequests(root);
        }

        @Override
        public void invalidateJiraIssues(Path root) {
            inputs.invalidateJiraIssues(root);
        }

        @Override
        public void requestListing(SectionKind kind, Path root) {
            // Refresh intent is user intent: the next gather must fetch this
            // listing and open its section, even when the row was still
            // collapsed (deferred) when it was refreshed.
            expansions.requested(kind).add(root);
        }

        @Override
        public void requestRebuild(boolean changedWorktrees) {
            TaskTreePanel.this.requestRebuild(changedWorktrees);
        }
    };
    /**
     * Canonical repository root -> its id, refreshed by every background rebuild so the EDT
     * {@code update()} of the quick review action never canonicalizes (filesystem IO). First
     * repository per root wins, as the previous stream's findFirst() did.
     */
    private volatile Map<Path, RepositoryId> repositoryIdsByRoot = Map.of();

    public TaskTreePanel(Project project) {
        super(true, true);
        this.project = project;

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new TaskTreeRenderer());
        // The first read runs git once per project; a blank "Nothing to show"
        // meanwhile looked like a broken window.
        tree.getEmptyText().setText("Loading Drydock projects…");
        setContent(ScrollPaneFactory.createScrollPane(tree));

        AnAction switchToTask = new DumbAwareAction("Switch to Task", "Raise the task's window and select its agent",
                AllIcons.Actions.Forward) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof TaskRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof TaskRow task) {
                    TaskNavigator.switchTo(task.session());
                }
            }
        };
        AnAction newTask = new DumbAwareAction("New Task…",
                "Start a task for this project or worktree", AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                TaskActions.present(e, newTaskTarget(e.getData(SELECTED_NODE), root).isPresent());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                newTaskTarget(e.getData(SELECTED_NODE), root).ifPresent(target ->
                        TaskLauncher.open(project, Optional.of(target.mainRoot()), target.fixedWorktree()));
            }
        };
        AnAction newTaskInMain = new DumbAwareAction("New Task in Main Checkout…",
                "Start an agent in this project's main checkout without creating a branch or worktree",
                AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof ProjectRow
                        && e.getData(SELECTED_PROJECT_ROOT) != null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                if (root != null && e.getData(SELECTED_NODE) instanceof ProjectRow) {
                    TaskLauncher.openInMainCheckout(project, root);
                }
            }
        };
        AnAction openInGitHub = new DumbAwareAction("Open in GitHub", "Open this issue or pull request in the browser",
                AllIcons.Vcs.Vendors.Github) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, TaskMenu.urlOf(e.getData(SELECTED_NODE)).isPresent());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TaskMenu.urlOf(e.getData(SELECTED_NODE)).ifPresent(BrowserUtil::browse);
            }
        };
        // "Review…" asks for the starting model and re-records the choice:
        // a pull request is first checked out into its own worktree, an
        // idle worktree starts the session right there.
        AnAction review = new DumbAwareAction("Review…",
                "Pick the starting model and start a review session; "
                        + "a pull request is first checked out into its own worktree",
                AllIcons.Actions.Preview) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskNode node = e.getData(SELECTED_NODE);
                TaskActions.present(e, node instanceof PullRequestRow
                        || (node instanceof IdleWorktree && e.getData(SELECTED_PROJECT_ROOT) != null));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TaskNode node = e.getData(SELECTED_NODE);
                if (node instanceof PullRequestRow pullRequest) {
                    reviewPullRequestWithDialog(pullRequest.mainRoot(), pullRequest);
                } else if (node instanceof IdleWorktree idle) {
                    Path root = e.getData(SELECTED_PROJECT_ROOT);
                    if (root != null) {
                        worktreeReview.reviewWithModelDialog(project, root, idle.worktree(), idle.branch());
                    }
                }
            }
        };
        // One-gesture review with the repository's remembered starting model
        // for the harness the review will launch; "Review…" above asks and
        // re-records it instead. For a pull request the review checks the
        // PR out into its own worktree first; an idle worktree starts the
        // session right there. The label names the model so the quick
        // action says what it will launch with, and it must track the state
        // store cheaply: it reads the in-memory repositories only, never git.
        AnAction quickReview = new DumbAwareAction("Review (default)",
                "Start a review session with the remembered starting model; "
                        + "a pull request is first checked out into its own worktree",
                AllIcons.Actions.Preview) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskNode node = e.getData(SELECTED_NODE);
                Path root = node instanceof PullRequestRow pullRequest ? pullRequest.mainRoot()
                        : e.getData(SELECTED_PROJECT_ROOT);
                TaskActions.present(e, node instanceof PullRequestRow
                        || (node instanceof IdleWorktree && root != null));
                e.getPresentation().setText(quickReviewLabel(root));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TaskNode node = e.getData(SELECTED_NODE);
                if (node instanceof PullRequestRow pullRequest) {
                    reviewPullRequest(pullRequest.mainRoot(), pullRequest);
                } else if (node instanceof IdleWorktree idle) {
                    Path root = e.getData(SELECTED_PROJECT_ROOT);
                    if (root != null) {
                        worktreeReview.review(project, root, idle.worktree(), idle.branch());
                    }
                }
            }
        };
        // Reviews in the harness's sphinx plugin: same surfaces as the two
        // actions above, but the harness's sphinx command rides the launch
        // and no Drydock review tab opens. Hidden — never greyed — when the
        // repository's default harness reports no sphinx plugin: a greyed
        // item whose reason nobody can see is noise for everyone whose
        // harness simply lacks the plugin. The launch re-checks whoever the
        // resolved harness actually is.
        AnAction reviewInSphinx = new DumbAwareAction("Review in Sphinx",
                "Start a review session whose first prompt is the harness's sphinx command; "
                        + "a pull request is first checked out into its own worktree",
                AllIcons.Actions.Preview) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskNode node = e.getData(SELECTED_NODE);
                Path root = node instanceof PullRequestRow pullRequest ? pullRequest.mainRoot()
                        : e.getData(SELECTED_PROJECT_ROOT);
                TaskActions.present(e, (node instanceof PullRequestRow
                        || (node instanceof IdleWorktree && root != null)) && sphinxAvailable(root));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TaskNode node = e.getData(SELECTED_NODE);
                if (node instanceof PullRequestRow pullRequest) {
                    reviewPullRequestInSphinx(pullRequest.mainRoot(), pullRequest);
                } else if (node instanceof IdleWorktree idle) {
                    Path root = e.getData(SELECTED_PROJECT_ROOT);
                    if (root != null) {
                        worktreeReview.reviewSphinx(project, root, idle.worktree(), idle.branch());
                    }
                }
            }
        };
        AnAction implement = new DumbAwareAction("Implement…",
                "Create a writable task and worktree for this issue", AllIcons.Actions.Execute) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IssueRow
                        || e.getData(SELECTED_NODE) instanceof JiraIssueRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IssueRow issue) {
                    TaskLauncher.implement(project, issue.mainRoot(), issue.issue());
                } else if (e.getData(SELECTED_NODE) instanceof JiraIssueRow jira) {
                    TaskLauncher.implement(project, jira.mainRoot(), jira.issue());
                }
            }
        };
        AnAction triage = new DumbAwareAction("Triage…",
                "Validate and prioritize this issue against the current source", AllIcons.Actions.Show) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IssueRow
                        || e.getData(SELECTED_NODE) instanceof JiraIssueRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IssueRow issue) {
                    TaskLauncher.triage(project, issue.mainRoot(), issue.issue());
                } else if (e.getData(SELECTED_NODE) instanceof JiraIssueRow jira) {
                    TaskLauncher.triage(project, jira.mainRoot(), jira.issue());
                }
            }
        };
        // One GitHub Settings action for every row that offers it — an
        // issue, a project, or a listings section (A10: the old duplicate
        // pair is gone).
        AnAction githubSettings = githubSettingsAction(node -> node instanceof IssueRow
                || githubSettingsNode(node));
        // The manual refresh, one shared instance for the toolbar and the
        // tree's context menu alike: it is the same "show me now" gesture
        // (A10), but what it drops depends on where it ran — global from the
        // toolbar and the project row, one listing only from a section's
        // own menu (see the static refresh(…) dispatch below).
        AnAction refresh = refreshAction(refreshTarget);
        // The tree's context menu, one action instance per entry: TaskMenu
        // lays out which of them apply to the selection, and in what order
        // (A10), so a popup holds exactly the applicable actions.
        Map<TaskMenu.Item, AnAction> taskActions = TaskActions.taskActions();
        Map<TaskMenu.Item, AnAction> menuActions = new EnumMap<>(TaskMenu.Item.class);
        menuActions.putAll(taskActions);
        menuActions.put(TaskMenu.Item.SWITCH_TO_TASK, switchToTask);
        menuActions.put(TaskMenu.Item.NEW_TASK, newTask);
        menuActions.put(TaskMenu.Item.NEW_TASK_IN_MAIN, newTaskInMain);
        menuActions.put(TaskMenu.Item.OPEN_IN_GITHUB, openInGitHub);
        menuActions.put(TaskMenu.Item.QUICK_REVIEW, quickReview);
        menuActions.put(TaskMenu.Item.REVIEW, review);
        menuActions.put(TaskMenu.Item.REVIEW_IN_SPHINX, reviewInSphinx);
        menuActions.put(TaskMenu.Item.IMPLEMENT, implement);
        menuActions.put(TaskMenu.Item.TRIAGE, triage);
        menuActions.put(TaskMenu.Item.GITHUB_SETTINGS, githubSettings);
        menuActions.put(TaskMenu.Item.OPEN_IN_JIRA, JiraTreeActions.openInJira());
        menuActions.put(TaskMenu.Item.JIRA_SETTINGS, JiraTreeActions.settings(project, root -> {
            inputs.invalidateJira();
            requestRebuild(false);
        }));
        // Group edits only ever touch the project's own group storage and
        // listings: one project's Jira caches drop, nothing else.
        Consumer<Path> afterJiraGroupChange = root -> {
            inputs.invalidateJiraIssues(root);
            requestRebuild(false);
        };
        menuActions.put(TaskMenu.Item.ADD_JIRA_GROUP, JiraTreeActions.addGroup(project, afterJiraGroupChange));
        menuActions.put(TaskMenu.Item.EDIT_JIRA_GROUP, JiraTreeActions.editGroup(project, afterJiraGroupChange));
        menuActions.put(TaskMenu.Item.DELETE_JIRA_GROUP, JiraTreeActions.deleteGroup(project, afterJiraGroupChange));
        menuActions.put(TaskMenu.Item.REFRESH, refresh);
        PopupHandler.installPopupMenu(tree, new TreeMenu(menuActions), "Drydock.TaskTree.Popup");

        // The tool window's own buttons: start a task, then what can be done
        // to the selected task or project. They stay in place, greyed when
        // they do not apply (see TaskActions.shown).
        DefaultActionGroup toolbarActions = new DefaultActionGroup();
        AnAction newTaskAction = ActionManager.getInstance().getAction("Drydock.NewTask");
        if (newTaskAction != null) {
            toolbarActions.add(newTaskAction);
        }
        toolbarActions.add(switchToTask);
        toolbarActions.addSeparator();
        // One button per action instance, in the enum's values() order —
        // the removal action sits under several menu entries but is one
        // button (TaskActions.toolbarActions).
        toolbarActions.addAll(TaskActions.toolbarActions(taskActions));
        toolbarActions.addSeparator();
        toolbarActions.add(refresh);
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("Drydock.TaskTree", toolbarActions, true);
        toolbar.setTargetComponent(this);
        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar.getComponent(), BorderLayout.NORTH);
        JPanel query = new JPanel(new BorderLayout());
        query.add(search, BorderLayout.NORTH);
        query.add(new ViewFilterRibbon(viewFilter, candidate -> {
            // The ribbon owns only the chips; the chosen view stays here,
            // and every real change (not a re-click of the selected chip)
            // narrows the tree the same way the old chip row did.
            viewFilter = candidate;
            // The listing views load every project's listing on the next
            // pass; until it lands, the previous tree would mislead, so it
            // is cleared and the busy painter carries the wait.
            if (candidate == ViewFilter.ISSUES || candidate == ViewFilter.PULL_REQUESTS) {
                ((DefaultTreeModel) tree.getModel()).setRoot(new DefaultMutableTreeNode());
                displayedNodes = List.of();
                tree.getEmptyText().setText(candidate == ViewFilter.ISSUES
                        ? "Loading issues…" : "Loading pull requests…");
                tree.setPaintBusy(true);
            }
            requestRebuild(false);
        }), BorderLayout.SOUTH);
        top.add(query, BorderLayout.SOUTH);
        setToolbar(top);

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                Object node = path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                boolean primaryButton = SwingUtilities.isLeftMouseButton(event);
                boolean acts = TaskTreeRenderer.clickActs(
                        primaryButton, onLink(path, event), event.getClickCount());
                if (node instanceof AddCurrentProject add && acts) {
                    register(add);
                } else if (node instanceof IdleWorktree idle && acts) {
                    startOn(path, idle, event);
                } else if (node instanceof StaleWorktrees stale && acts) {
                    projectRootOf(path).ifPresent(root ->
                            TaskActions.confirmAndRemove(project, root, stale.worktrees(), true,
                                    TaskTreePanel.this::worktreesRemoved));
                } else if (node instanceof TaskRow task && primaryButton && event.getClickCount() == 1
                        && onLink(path, event)) {
                    hereLink(task);
                } else if (node instanceof TaskRow task && primaryButton && event.getClickCount() == 2) {
                    activate(task);
                } else if (node instanceof IssueRow issue && primaryButton && event.getClickCount() == 2) {
                    issue.issue().url().ifPresent(BrowserUtil::browse);
                } else if (node instanceof JiraIssueRow jira && primaryButton && event.getClickCount() == 2) {
                    BrowserUtil.browse(jira.issue().url());
                } else if (node instanceof PullRequestRow pullRequest && primaryButton
                        && event.getClickCount() == 2) {
                    pullRequest.pullRequest().url().ifPresent(BrowserUtil::browse);
                } else if (node instanceof PullRequestRow pullRequest && primaryButton
                        && event.getClickCount() == 1 && onLink(path, event)) {
                    reviewPullRequest(path, pullRequest);
                }
            }
        });
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER && selected().orElse(null) instanceof TaskRow task) {
                    activate(task);
                }
            }
        });
        // Real expansion events — the user's click and the panel's own
        // restoration alike — reach the tracker, which records only the
        // user's; restoration runs inside its programmatic guard.
        tree.addTreeWillExpandListener(expansions);

        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                searchAlarm.cancelAllRequests();
                searchAlarm.addRequest(() -> {
                    filter = search.getText();
                    requestRebuild(false);
                }, SEARCH_DEBOUNCE_MILLIS);
            }
        });

        MessageBusConnection bus = ApplicationManager.getApplication().getMessageBus().connect(this);
        bus.subscribe(DrydockSessionsListener.TOPIC, (DrydockSessionsListener) () -> requestRebuild(true));
        bus.subscribe(SessionActivityListener.TOPIC, (SessionActivityListener) (id, activity) -> requestRebuild(false));
        // Where an agent is shown changes nothing in the catalog: a move or a
        // release only reaches the rows through the registry's own events.
        bus.subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
            @Override
            public void executionShown(ManagedSessionId sessionId, Project to) {
                requestRebuild(false);
            }

            @Override
            public void executionReleased(ManagedSessionId sessionId) {
                requestRebuild(false);
            }

            @Override
            public void executionStarted(ManagedSessionId sessionId) {
                requestRebuild(false);
            }

            @Override
            public void executionTerminated(ManagedSessionId sessionId, boolean stopRequested, Integer exitCode) {
                requestRebuild(false);
            }
        });
        DrydockPluginService.getInstance().initialization().thenRun(() -> {
            // Initialization can finish after the tool window is gone; a
            // listener added then would outlive the panel.
            synchronized (this) {
                if (disposed) {
                    return;
                }
                unsubscribeShared = DrydockPluginService.getInstance().sharedProjects()
                        .addListener(() -> requestRebuild(true));
            }
            requestRebuild(true);
        });
    }

    /**
     * The context-menu items whose actions this panel itself provides, as
     * opposed to {@link TaskActions}; the constructor's {@code menuActions}
     * puts cover exactly these. Pure — the seam the menu-coverage test
     * reads, so no {@link TaskMenu} item can be left without an action
     * behind it.
     */
    static Set<TaskMenu.Item> panelMenuItems() {
        return EnumSet.of(TaskMenu.Item.SWITCH_TO_TASK, TaskMenu.Item.NEW_TASK, TaskMenu.Item.NEW_TASK_IN_MAIN,
                TaskMenu.Item.OPEN_IN_GITHUB, TaskMenu.Item.OPEN_IN_JIRA, TaskMenu.Item.QUICK_REVIEW,
                TaskMenu.Item.REVIEW, TaskMenu.Item.REVIEW_IN_SPHINX,
                TaskMenu.Item.IMPLEMENT, TaskMenu.Item.TRIAGE,
                TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS,
                TaskMenu.Item.ADD_JIRA_GROUP, TaskMenu.Item.EDIT_JIRA_GROUP, TaskMenu.Item.DELETE_JIRA_GROUP,
                TaskMenu.Item.REFRESH);
    }

    /**
     * What one refresh gesture can ask of the panel: the caches it can drop
     * and the rebuild it can schedule. Production implements this over the
     * panel's real inputs, listing intent and rebuild scheduler; tests
     * record the calls to assert which scope a real action event actually
     * dispatched — no {@code gh}, no network, no rebuild (F8).
     */
    interface RefreshTarget {

        /** The global gesture: every project's GitHub and Jira listings. */
        void invalidateAll();

        /** One project's issue listing only. */
        void invalidateIssues(Path root);

        /** One project's pull-request listing only. */
        void invalidatePullRequests(Path root);

        /** One project's cached Jira group listings only. */
        void invalidateJiraIssues(Path root);

        /** Records the user's intent to see {@code root}'s listing of {@code kind}. */
        void requestListing(SectionKind kind, Path root);

        /** Schedules one rebuild; {@code changedWorktrees} re-lists worktrees. */
        void requestRebuild(boolean changedWorktrees);
    }

    /**
     * The manual Refresh action, one instance for the toolbar and the tree's
     * context menu alike: it is the same "show me now" gesture (A10), and
     * the dispatch decides its scope from where the event came from and
     * what the tree selection is (see {@link #refresh}).
     */
    static AnAction refreshAction(RefreshTarget target) {
        return new DumbAwareAction("Refresh", "Re-read projects, worktrees, and issue and pull-request listings",
                AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refresh(target, e.isFromContextMenu(), e.getData(SELECTED_NODE),
                        e.getData(SELECTED_PROJECT_ROOT));
            }
        };
    }

    /**
     * One refresh gesture, two scopes. From the toolbar — or the project
     * row's context menu, which names a whole project — it is the user
     * saying "show me now", so the cached GitHub listings (issues, pull
     * requests) are dropped too, not just the worktree scan. From a
     * listing section's own context menu it re-reads only that project's
     * listing, the per-section semantics the tree's menu had before the
     * two gestures were merged into one action; the root is also recorded
     * as listing intent, so a refresh of a still-collapsed (deferred)
     * section loads it and the pass after opens it. A section with no root
     * above it — or no listing of its own — fetches nothing rather than
     * silently broadening to a global fetch. Pure dispatch over
     * {@code target}'s effects; EDT.
     */
    static void refresh(RefreshTarget target, boolean fromContextMenu, TaskNode node, Path root) {
        if (fromContextMenu && node instanceof TaskNode.JiraGroupRow) {
            // A group's refresh re-queries its project's Jira groups and
            // records the listing intent, so a group that is visible but
            // not yet fetched actually loads instead of staying deferred.
            if (root == null) {
                return;
            }
            target.invalidateJiraIssues(root);
            target.requestListing(SectionKind.ISSUES, root);
            target.requestRebuild(false);
            return;
        }
        if (!fromContextMenu || !(node instanceof Section section)) {
            target.invalidateAll();
            target.requestRebuild(true);
            return;
        }
        switch (section.kind()) {
            case ISSUES -> {
                if (root == null) {
                    return;
                }
                target.invalidateIssues(root);
                target.invalidateJiraIssues(root);
                target.requestListing(SectionKind.ISSUES, root);
                target.requestRebuild(false);
            }
            case PULL_REQUESTS -> {
                if (root == null) {
                    return;
                }
                target.invalidatePullRequests(root);
                target.requestListing(SectionKind.PULL_REQUESTS, root);
                target.requestRebuild(false);
            }
            // Sessions/worktrees sections have no listing to scope to; the
            // menu never offers Refresh on them, and a stray dispatch must
            // not turn into a global fetch.
            default -> {
            }
        }
    }

    /**
     * The tree's context menu: {@link TaskMenu#group} over the selection at
     * popup time, so each popup holds exactly the actions that apply to
     * the selected rows, in TaskMenu's order — navigation first,
     * destructive cleanup last (A10). EDT: the tree's selection is Swing
     * state, and the actions' own updates read the same data the popup
     * sinks.
     */
    private final class TreeMenu extends ActionGroup implements DumbAware {

        private final Map<TaskMenu.Item, AnAction> actions;

        TreeMenu(Map<TaskMenu.Item, AnAction> actions) {
            this.actions = actions;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public AnAction[] getChildren(@Nullable AnActionEvent e) {
            // The platform enumerates a group's children with no event in
            // some paths; with no event no popup is being built for the
            // tree, and its menu applies to nothing.
            if (e == null) {
                return AnAction.EMPTY_ARRAY;
            }
            return TaskMenu.group(selected().orElse(null), selectedNodes(), actions,
                    selectedProjectRoot().map(JiraTreeActions::jiraEnabled).orElse(false)).getChildren(e);
        }
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
        sink.set(WORKTREE_REMOVAL_UI, this::worktreesRemoved);
        selected().ifPresent(node -> sink.set(SELECTED_NODE, node));
        List<TaskNode> selected = selectedNodes();
        if (!selected.isEmpty()) {
            sink.set(SELECTED_NODES, selected);
        }
        selectedProjectRoot().ifPresent(root -> sink.set(SELECTED_PROJECT_ROOT, root));
    }

    /** Whether {@code event} landed on the link text of the row at {@code path}. EDT. */
    private boolean onLink(TreePath path, MouseEvent event) {
        Rectangle bounds = tree.getPathBounds(path);
        if (bounds == null) {
            return false;
        }
        int row = tree.getRowForPath(path);
        Component rendered = tree.getCellRenderer().getTreeCellRendererComponent(tree,
                path.getLastPathComponent(), tree.isRowSelected(row), tree.isExpanded(row),
                tree.getModel().isLeaf(path.getLastPathComponent()), row, false);
        return rendered instanceof SimpleColoredComponent colored
                && colored.getFragmentTagAt(event.getX() - bounds.x) == TaskTreeRenderer.LINK;
    }

    /** The main checkout of the project row at or above {@code path}. */
    static Optional<Path> projectRootOf(TreePath path) {
        for (TreePath at = path; at != null; at = at.getParentPath()) {
            if (at.getLastPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof ProjectRow row) {
                return Optional.of(row.project().root());
            }
        }
        return Optional.empty();
    }

    /**
     * The worktree a new task would join from {@code node}: a group's or an
     * idle worktree's, or a task's own when it works in a linked worktree
     * (not the main checkout at {@code mainRoot}).
     */
    private static Optional<NewTaskPlan.FixedWorktree> worktreeOf(TaskNode node, Path mainRoot) {
        return switch (node) {
            case WorktreeGroup group -> Optional.of(new NewTaskPlan.FixedWorktree(group.worktree(), group.branch()));
            case IdleWorktree idle -> Optional.of(new NewTaskPlan.FixedWorktree(idle.worktree(), idle.branch()));
            case TaskRow task when task.branch().isPresent() && !task.session().workingDirectory().equals(mainRoot) ->
                    Optional.of(new NewTaskPlan.FixedWorktree(task.session().workingDirectory(), task.branch().get()));
            case null, default -> Optional.empty();
        };
    }

    /**
     * The target of the tree's single context-menu New Task action. A
     * project row targets that registered project, while a worktree row (or
     * a task inside a linked worktree) additionally fixes the dialog to that
     * worktree. A task in the main checkout still targets its registered
     * project, but keeps the normal new-task choices so the main-checkout
     * writer warning cannot be bypassed through EXISTING_WORKTREE mode.
     *
     * <p>The tool-window toolbar and global shortcut deliberately do not use
     * this seam: they continue to target the currently open project.</p>
     */
    static Optional<NewTaskTarget> newTaskTarget(TaskNode node, Path mainRoot) {
        if (mainRoot == null) {
            return Optional.empty();
        }
        return switch (node) {
            case ProjectRow ignored -> Optional.of(new NewTaskTarget(mainRoot, Optional.empty()));
            case WorktreeGroup ignored -> Optional.of(new NewTaskTarget(mainRoot, worktreeOf(node, mainRoot)));
            case IdleWorktree ignored -> Optional.of(new NewTaskTarget(mainRoot, worktreeOf(node, mainRoot)));
            case TaskRow ignored -> Optional.of(new NewTaskTarget(mainRoot, worktreeOf(node, mainRoot)));
            case IssueRow ignoredIssue -> Optional.empty();
            case JiraIssueRow ignored -> Optional.empty();
            case PullRequestRow ignoredPullRequest -> Optional.empty();
            case null, default -> Optional.empty();
        };
    }

    private static boolean githubSettingsNode(TaskNode node) {
        return node instanceof ProjectRow || node instanceof Section section
                && (section.kind() == SectionKind.ISSUES || section.kind() == SectionKind.PULL_REQUESTS);
    }

    /** Starts the review materialization for the clicked pull request. EDT. */
    private void reviewPullRequest(TreePath path, PullRequestRow pullRequest) {
        reviewPullRequest(projectRootOf(path).orElse(null), pullRequest);
    }

    private void reviewPullRequest(Path mainRoot, PullRequestRow pullRequest) {
        if (mainRoot == null) {
            return;
        }
        String repositoryName = DrydockPluginService.getInstance().sharedProjects().byRoot(mainRoot)
                .map(SharedProject::displayName)
                .orElseGet(() -> String.valueOf(mainRoot.getFileName()));
        pullRequestReview.review(project, mainRoot, repositoryName, pullRequest.pullRequest());
    }

    /** Opens the review's model dialog for the pull request. EDT. */
    private void reviewPullRequestWithDialog(Path mainRoot, PullRequestRow pullRequest) {
        if (mainRoot == null) {
            return;
        }
        String repositoryName = DrydockPluginService.getInstance().sharedProjects().byRoot(mainRoot)
                .map(SharedProject::displayName)
                .orElseGet(() -> String.valueOf(mainRoot.getFileName()));
        pullRequestReview.reviewWithModelDialog(project, mainRoot, repositoryName, pullRequest.pullRequest());
    }

    /** Starts the sphinx review materialization for the clicked pull request. EDT. */
    private void reviewPullRequestInSphinx(Path mainRoot, PullRequestRow pullRequest) {
        if (mainRoot == null) {
            return;
        }
        String repositoryName = DrydockPluginService.getInstance().sharedProjects().byRoot(mainRoot)
                .map(SharedProject::displayName)
                .orElseGet(() -> String.valueOf(mainRoot.getFileName()));
        pullRequestReview.reviewSphinx(project, mainRoot, repositoryName, pullRequest.pullRequest());
    }

    /**
     * The quick review action's label: the remembered starting model of the
     * harness the review will launch, or "default" when none was picked yet.
     * Reads in-memory state only (a precomputed canonical-root map plus the
     * state store) — never git, never the disk — because {@code update} runs
     * on the EDT; a root not yet mapped reads "default" until the next rebuild.
     */
    private String quickReviewLabel(Path mainRoot) {
        if (mainRoot == null) {
            return "Review (default)";
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        // mainRoot is already canonical (TaskTreeInputs.gather), and the root -> id map is
        // precomputed off the EDT; the settings themselves are read fresh from the in-memory store.
        RepositoryId id = repositoryIdsByRoot.get(mainRoot);
        Optional<Repository> repository = id == null ? Optional.empty()
                : plugin.stateStore().state().repositories().stream()
                        .filter(r -> r.id().equals(id))
                        .findFirst();
        Optional<AgentKind> agent = PullRequestReview.selectReviewAgent(
                SessionDialogs.defaultAgent(plugin.registry(),
                        repository.flatMap(r -> r.settings().lastUsedAgent())),
                plugin.registry()::isAvailable);
        String model = agent.flatMap(kind -> repository.flatMap(r -> r.settings().lastUsedModel(kind)))
                .orElse("default");
        return "Review (" + model + ")";
    }

    /**
     * Whether the repository's default harness reaches the sphinx plugin —
     * the same cheap EDT answer {@code quickReviewLabel} builds on: the
     * precomputed root→id map plus the in-memory state store, never git and
     * never a fresh probe ({@code AgentRegistry#sphinxReview} reads its
     * init-time snapshot). {@code null} root or an unknown repository reads
     * as unavailable — absent must not look like offered.
     */
    private boolean sphinxAvailable(Path mainRoot) {
        if (mainRoot == null) {
            return false;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        RepositoryId id = repositoryIdsByRoot.get(mainRoot);
        Optional<Repository> repository = id == null ? Optional.empty()
                : plugin.stateStore().state().repositories().stream()
                        .filter(r -> r.id().equals(id))
                        .findFirst();
        return PullRequestReview.selectReviewAgent(
                        SessionDialogs.defaultAgent(plugin.registry(),
                                repository.flatMap(r -> r.settings().lastUsedAgent())),
                        plugin.registry()::isAvailable)
                .flatMap(kind -> plugin.registry().sphinxReview(kind))
                .isPresent();
    }

    private AnAction githubSettingsAction(Predicate<TaskNode> applies) {
        return new DumbAwareAction("GitHub Settings…",
                "Configure GitHub issues for this Drydock project", AllIcons.General.Settings) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, applies.test(e.getData(SELECTED_NODE)));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                if (root == null) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().byRoot(root).ifPresent(registered -> {
                    if (new GitHubProjectSettingsDialog(project, registered).showAndGet()) {
                        inputs.invalidateGitHub();
                        requestRebuild(false);
                    }
                });
            }
        };
    }

    record NewTaskTarget(Path mainRoot, Optional<NewTaskPlan.FixedWorktree> fixedWorktree) {
    }

    private Optional<TaskNode> selected() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
            return Optional.empty();
        }
        return node.getUserObject() instanceof TaskNode taskNode ? Optional.of(taskNode) : Optional.empty();
    }

    private List<TaskNode> selectedNodes() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) {
            return List.of();
        }
        return Arrays.stream(paths)
                .map(TreePath::getLastPathComponent)
                .filter(DefaultMutableTreeNode.class::isInstance)
                .map(DefaultMutableTreeNode.class::cast)
                .map(DefaultMutableTreeNode::getUserObject)
                .filter(TaskNode.class::isInstance)
                .map(TaskNode.class::cast)
                .toList();
    }

    /** A root only when the complete selection belongs to one repository. */
    private Optional<Path> selectedProjectRoot() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null || paths.length == 0) {
            return Optional.empty();
        }
        Path root = projectRootOf(paths[0]).orElse(null);
        if (root == null) {
            return Optional.empty();
        }
        for (int i = 1; i < paths.length; i++) {
            if (!projectRootOf(paths[i]).filter(root::equals).isPresent()) {
                return Optional.empty();
            }
        }
        return Optional.of(root);
    }

    /** Schedules one rebuild; {@code changedWorktrees} re-lists worktrees instead of using the cache. */
    public void requestRebuild(boolean changedWorktrees) {
        if (disposed) {
            return;
        }
        refreshRequests.request(changedWorktrees);
        scheduleRebuild();
    }

    /** Background only: canonicalizes every recorded repository root (filesystem IO). */
    private static Map<Path, RepositoryId> repositoryIdsByRoot(DrydockPluginService plugin) {
        Map<Path, RepositoryId> byRoot = new LinkedHashMap<>();
        for (Repository repository : plugin.stateStore().state().repositories()) {
            byRoot.putIfAbsent(RepositoryCatalog.canonicalize(repository.root()), repository.id());
        }
        return Map.copyOf(byRoot);
    }

    private void scheduleRebuild() {
        Optional<TaskTreeRefreshRequests.Pass> requested = refreshRequests.beginPass();
        if (requested.isEmpty()) {
            return;
        }
        long requestedVersion = requested.get().version();
        String requestedFilter = filter;
        ViewFilter requestedViewFilter = viewFilter;
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.initialization().thenRunAsync(() -> {
            if (disposed || project.isDisposed()) {
                return;
            }
            if (requested.get().worktreesChanged()) {
                // Invalidate only after any earlier scan has finished. If a
                // removal arrives during a scan, rebuildVersion schedules a
                // follow-up pass and this flag survives until that pass.
                inputs.invalidateWorktrees();
            }
            repositoryIdsByRoot = repositoryIdsByRoot(plugin);
            TaskTreeModel.Inputs gathered = inputs.gather(project, requestedFilter, requestedViewFilter,
                    Set.copyOf(expansions.requested(SectionKind.WORKTREES)),
                    Set.copyOf(expansions.requested(SectionKind.ISSUES)),
                    Set.copyOf(expansions.requested(SectionKind.PULL_REQUESTS)));
            // Only a completed gather knows which groups still exist: a deleted
            // group, an unregistered project or a disabled connection stops being
            // remembered as open. A failed gather never gets here and forgets nothing.
            expansions.retainDefinedJiraGroups(gathered.jiraGroupsByMainRoot());
            List<TaskNode> nodes = TaskTreeModel.build(gathered);
            ApplicationManager.getApplication().invokeLater(
                    () -> finishRebuild(requestedVersion, requestedFilter, requestedViewFilter, nodes, null),
                    ModalityState.any(), project.getDisposed());
        }, plugin.executor()).exceptionally(failure -> {
            ApplicationManager.getApplication().invokeLater(
                    () -> finishRebuild(requestedVersion, requestedFilter, requestedViewFilter, List.of(), failure),
                    ModalityState.any(), project.getDisposed());
            return null;
        });
    }

    /** EDT: applies only the newest requested view, then schedules any request coalesced behind it. */
    private void finishRebuild(long requestedVersion, String requestedFilter, ViewFilter requestedViewFilter,
                               List<TaskNode> nodes, Throwable failure) {
        boolean newerPassPending = requestedVersion != refreshRequests.version();
        if (failure != null) {
            // Never silent: an empty tree with no reason given is the worst
            // outcome this panel can have.
            LOG.log(Level.WARNING, "Could not rebuild the Drydock task tree", failure);
            // A newer pass owns the busy state now; this one clears it and
            // says why only when nothing else is on screen.
            if (!newerPassPending) {
                tree.setPaintBusy(false);
                if (tree.getRowCount() == 0) {
                    tree.getEmptyText().setText("Could not load the task list: "
                            + (failure.getMessage() == null ? failure : failure.getMessage()));
                }
            }
        } else if (requestedVersion == refreshRequests.version() && !disposed && !project.isDisposed()) {
            apply(nodes, requestedFilter, requestedViewFilter);
        }
        refreshRequests.finishPass();
        if (!disposed && !project.isDisposed() && newerPassPending) {
            scheduleRebuild();
        }
    }

    /** EDT: swaps in the new rows, keeping the selected task selected. */
    private void apply(List<TaskNode> nodes, String appliedFilter, ViewFilter appliedViewFilter) {
        displayedNodes = List.copyOf(nodes);
        displayedFilter = appliedFilter;
        displayedViewFilter = appliedViewFilter;
        Optional<ManagedSessionId> selectedSession = selected()
                .filter(TaskRow.class::isInstance)
                .map(node -> ((TaskRow) node).session().id());
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode[] toSelect = new DefaultMutableTreeNode[1];
        for (TaskNode node : nodes) {
            root.add(treeNode(node, selectedSession, toSelect));
        }
        ((DefaultTreeModel) tree.getModel()).setRoot(root);
        tree.getEmptyText().setText(emptyText(appliedFilter, appliedViewFilter));
        // This pass replaced the rows; the load it announced is over, even
        // when the rows are empty (the empty text says that instead).
        tree.setPaintBusy(false);
        // Restoration, not intent: every expansion in this span (and any a
        // selection could cause) records no user request.
        expansions.runProgrammatic(() -> {
            restoreExpansions(root, new TreePath(root.getPath()), null, appliedViewFilter, expansions,
                    tree::expandPath);
            if (toSelect[0] != null) {
                tree.setSelectionPath(new TreePath(toSelect[0].getPath()));
            }
        });
    }

    /** Whether a lazy listing section expands: always in the view that is
     * that listing (the Issues view opens every Issues section, the PRs
     * view every Pull Requests section), otherwise only after the user
     * opened it here. Sections with no view of their own (worktrees) follow
     * the request alone. Pure.
     */
    static boolean expandsListing(SectionKind kind, ViewFilter view, boolean requested) {
        if (kind == SectionKind.ISSUES) {
            return view == ViewFilter.ISSUES || requested;
        }
        if (kind == SectionKind.PULL_REQUESTS) {
            return view == ViewFilter.PULL_REQUESTS || requested;
        }
        return requested;
    }

    /**
     * Whether a Jira group expands: always in the view that is its
     * section's listing (the Issues view opens every group, as it opens
     * every Issues section), otherwise only after the user opened it
     * here — a rebuild re-collapses nothing the user had open. Pure.
     */
    static boolean expandsGroup(ViewFilter view, boolean requested) {
        return view == ViewFilter.ISSUES || requested;
    }

    /**
     * The panel's restoration walk: expands, through {@code expand}, every
     * eager node and every lazy section or Jira group this view or the
     * user's requests open — each node before its children, children in
     * order. The project root of a node is its nearest project row's main
     * root, carried down the walk; a section or group with none never
     * expands, in any view. Static and panel-free (the side effect is the
     * {@code expand} callback) so the tests can walk a model tree without a
     * live project.
     */
    static void restoreExpansions(DefaultMutableTreeNode node, TreePath path, Path projectRoot,
                                  ViewFilter view, ListingExpansionTracker expansions,
                                  Consumer<TreePath> expand) {
        Object value = node.getUserObject();
        Path root = value instanceof ProjectRow row ? row.project().root() : projectRoot;
        boolean expanded = switch (value) {
            case null -> false;
            case ProjectRow ignored -> true;
            case WorktreeGroup ignored -> true;
            case StaleWorktrees ignored -> true;
            case Section section -> switch (section.kind()) {
                case SESSIONS, ACTIVE, INACTIVE -> true;
                case WORKTREES -> root != null && expandsListing(SectionKind.WORKTREES, view,
                        expansions.requested(SectionKind.WORKTREES).contains(root));
                case ISSUES -> root != null && expandsListing(SectionKind.ISSUES, view,
                        expansions.requested(SectionKind.ISSUES).contains(root));
                case PULL_REQUESTS -> root != null && expandsListing(SectionKind.PULL_REQUESTS, view,
                        expansions.requested(SectionKind.PULL_REQUESTS).contains(root));
            };
            case JiraGroupRow group -> root != null && expandsGroup(view,
                    expansions.requestedJiraGroups().contains(
                            new ListingExpansionTracker.GroupKey(root, group.group().id())));
            default -> false;
        };
        if (expanded) {
            expand.accept(path);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            restoreExpansions(child, path.pathByAddingChild(child), root, view, expansions, expand);
        }
    }

    /** EDT: hides confirmed removals before the slower authoritative rescan finishes. */
    private void worktreesRemoved(Set<Path> removed) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed || project.isDisposed() || removed.isEmpty()) {
            return;
        }
        apply(TaskTreeModel.withoutWorktrees(displayedNodes, removed), displayedFilter, displayedViewFilter);
    }

    static String emptyText(String appliedFilter, ViewFilter appliedViewFilter) {
        if (!appliedFilter.isBlank()) {
            return "Nothing in " + appliedViewFilter.label().toLowerCase(Locale.ROOT) + " matches the search.";
        }
        return appliedViewFilter.emptyText();
    }

    /**
     * The Swing node of one model node, attaching every child a folder node
     * holds (a project, a section, a worktree or Jira group) — a node whose
     * children are never attached is a leaf the tree cannot expand, however
     * expandable the model says it is. Package-private so the dispatch tests
     * can assert the mapping directly.
     */
    static DefaultMutableTreeNode treeNode(TaskNode node, Optional<ManagedSessionId> selected,
                                            DefaultMutableTreeNode[] toSelect) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        switch (node) {
            case ProjectRow project -> project.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, toSelect)));
            case Section section -> section.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, toSelect)));
            case StaleWorktrees stale -> stale.worktrees().forEach(
                    worktree -> treeNode.add(treeNode(worktree, selected, toSelect)));
            case WorktreeGroup group -> group.tasks().forEach(
                    task -> treeNode.add(treeNode(task, selected, toSelect)));
            case JiraGroupRow group -> group.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, toSelect)));
            case TaskRow task -> {
                if (selected.map(task.session().id()::equals).orElse(false)) {
                    toSelect[0] = treeNode;
                }
            }
            default -> {
            }
        }
        return treeNode;
    }

    /**
     * {@code Start ▸} on an idle worktree (F1): the menu, or with ⌥ held its
     * own window straight away. Each opens New task fixed to the worktree,
     * preset to the choice. EDT.
     */
    private void startOn(TreePath path, IdleWorktree idle, MouseEvent event) {
        Optional<Path> root = projectRootOf(path);
        NewTaskPlan.FixedWorktree fixed = new NewTaskPlan.FixedWorktree(idle.worktree(), idle.branch());
        if (event.isAltDown()) {
            TaskLauncher.open(project, root, Optional.of(fixed),
                    NewTaskPreset.start(NewTaskPlan.OpenIn.OWN_WINDOW, SessionMode.WRITES));
            return;
        }
        String repo = repoOf(path).orElse(idle.worktree().getFileName().toString());
        // Lexical, not canonical: this is the EDT. A miss only offers "Start
        // in this window", which does the same thing on the window's own worktree.
        boolean ownWorktree = Optional.ofNullable(project.getBasePath())
                .map(base -> Path.of(base).toAbsolutePath().normalize().equals(idle.worktree()))
                .orElse(false);
        StartMenu.show(new RelativePoint(event), StartMenu.items(repo, idle.branch(), ownWorktree),
                item -> TaskLauncher.open(project, root, Optional.of(fixed),
                        NewTaskPreset.start(item.openIn(), item.mode())));
    }

    /** The display name of the project row above {@code path}. */
    private static Optional<String> repoOf(TreePath path) {
        for (Object component : path.getPath()) {
            if (((DefaultMutableTreeNode) component).getUserObject() instanceof ProjectRow row) {
                return Optional.of(row.project().displayName());
            }
        }
        return Optional.empty();
    }

    /** A row's inline link (F5): show a not-shown agent here, or resume an idle one here. EDT. */
    private void hereLink(TaskRow task) {
        TaskTreeRenderer.link(task).ifPresent(link -> {
            if (task.placement() instanceof TaskPlacement.NotShown) {
                AgentConsoles.showHere(project, task.session().id());
            } else {
                AgentConsoles.resumeOrStart(project, task.session());
            }
        });
    }

    /** The row's default gesture mirrors its primary toolbar action. EDT. */
    private void activate(TaskRow task) {
        switch (TaskActions.defaultActivation(task)) {
            case SWITCH_TO_RUNNING -> TaskNavigator.switchTo(task.session());
            case RESUME_HERE -> AgentConsoles.resumeOrStart(project, task.session());
        }
    }

    private void register(AddCurrentProject add) {
        DrydockPluginService.getInstance().sharedProjects().register(add.mainRoot(), add.repoName())
                .whenComplete((registered, failure) -> {
                    if (failure != null) {
                        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project,
                                "Could not add " + add.repoName() + " to Drydock: " + failure.getMessage(),
                                "Drydock"), ModalityState.nonModal(), project.getDisposed());
                    }
                });
    }

    @Override
    public void dispose() {
        synchronized (this) {
            disposed = true;
            unsubscribeShared.run();
        }
        // A gather already running finishes against a closed service: its
        // git calls fail, are caught, and its result is dropped above.
        inputs.close();
    }
}
