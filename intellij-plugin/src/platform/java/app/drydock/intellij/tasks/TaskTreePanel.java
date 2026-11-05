package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.NewDelayedTaskDialog;
import app.drydock.delayedtasks.TaskPrerequisitePicker;
import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.intellij.delayedtasks.DelayedTaskActions;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.agent.AgentConsoleFile;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.SessionActivityListener;
import app.drydock.projects.SharedProject;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.Ghost;
import app.drydock.intellij.tasks.TaskNode.GhostSource;
import app.drydock.intellij.tasks.TaskNode.GitHubNode;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraNode;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;
import app.drydock.tasks.DelayedTaskRows;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskNode.TasksSection;
import app.drydock.tasks.TaskSnapshot;

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
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
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
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** The selected Tasks section (T20a), for its New Task action and the context menu. */
    static final DataKey<TasksSection> SELECTED_TASKS_SECTION =
            DataKey.create("drydock.task.tasksSection");

    /** Updates the visible tree as successful worktree removals complete. */
    /** The registered project Focus on Project would show alone, snapshotted on the EDT for the BGT update. */
    private static final DataKey<Focus> FOCUS_TARGET = DataKey.create("drydock.task.focusTarget");

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
    /** The last applied rows before the focus narrowed them: where a hidden session's project is looked up. */
    private List<TaskNode> displayedUnfocusedNodes = List.of();
    /** The registered project the tree shows alone, or null for every project. Written on the EDT. */
    private volatile Focus focus;
    /** The session whose agent tab the user selected while the focus hides its row; null otherwise. EDT. */
    private ManagedSessionId outsideFocus;
    private final ProjectFocusBar focusBar = new ProjectFocusBar(this::focusOnOutside, () -> setFocus(null));
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
    /** The delayed-task layer the last rebuild applied (T17a), for the fast
     * worktree-rem acknowledgement, which re-applies the displayed rows. */
    private TaskLayer displayedTasks = TaskLayer.EMPTY;
    /**
     * The task node's actions (T20a): the plugin.xml-registered instances
     * when their ids resolve, locally built otherwise -- the same fallback
     * shape the toolbar's own action lookup uses.
     */
    private final AnAction[] delayedTaskActions = taskNodeMenu();
    /**
     * The Tasks node's New Task (T20a, P5): the T18a creation form; a
     * confirmed OK saves through the production saveTask core, a cancel
     * writes nothing and starts nothing.
     */
    private final AnAction newDelayedTask = new DumbAwareAction("New Task…",
            "Save a delayed task definition for later; it starts nothing", AllIcons.General.Add) {
        @Override
        public void update(@NotNull AnActionEvent e) {
            TaskActions.present(e, e.getData(SELECTED_TASKS_SECTION) != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null) {
                newDelayedTask(e.getProject());
            }
        }
    };
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
        public void requestPullRequests(Path root) {
            // Refresh intent is user intent: the next gather must fetch this
            // listing and open its section, even when the row was still
            // collapsed (deferred) when it was refreshed.
            expansions.requested(SectionKind.PULL_REQUESTS).add(root);
        }

        @Override
        public void requestGitHubIssues(Path root) {
            expansions.requestedGitHubIssues().add(root);
        }

        @Override
        public void requestJiraGroup(Path root, String groupId) {
            expansions.requestedJiraGroups().add(new ListingExpansionTracker.GroupKey(root, groupId));
        }

        @Override
        public void requestRebuild(boolean changedWorktrees) {
            TaskTreePanel.this.requestRebuild(changedWorktrees);
        }
    };
    /**
     * Canonical repository root -> its id, refreshed by every background rebuild so the
     * {@code update()} of the review actions never canonicalizes (filesystem IO). First
     * repository per root wins, as the previous stream's findFirst() did.
     */
    private volatile Map<Path, RepositoryId> repositoryIdsByRoot = Map.of();

    public TaskTreePanel(Project project) {
        super(true, true);
        this.project = project;

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new TaskTreeRenderer());
        // A session's PR badge arrives after the gather that asked for it.
        inputs.onLateData(() -> requestRebuild(false));
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
                return ActionUpdateThread.BGT;
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
                return ActionUpdateThread.BGT;
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
                return ActionUpdateThread.BGT;
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
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TaskMenu.urlOf(e.getData(SELECTED_NODE)).ifPresent(BrowserUtil::browse);
            }
        };
        // A pull request's workbench, with no agent: the PR checked out into
        // its worktree and that worktree's window showing its Review tab,
        // where the reviewer reads the change and can ask for a review.
        AnAction openInWorkbench = new DumbAwareAction("Open in Review Workbench",
                "Check the pull request out into its own worktree and open its review workbench there",
                AllIcons.Actions.Diff) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof PullRequestRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof PullRequestRow pullRequest) {
                    openPullRequestInWorkbench(pullRequest.mainRoot(), pullRequest);
                }
            }
        };
        // "Review…" asks for the starting model and re-records the choice;
        // an idle worktree starts the session right there. A pull request is
        // reviewed from its workbench (Open in Review Workbench).
        AnAction review = new DumbAwareAction("Review…",
                "Pick the starting model and start a review session", AllIcons.Actions.Preview) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IdleWorktree
                        && e.getData(SELECTED_PROJECT_ROOT) != null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IdleWorktree idle) {
                    Path root = e.getData(SELECTED_PROJECT_ROOT);
                    if (root != null) {
                        worktreeReview.reviewWithModelDialog(project, root, idle.worktree(), idle.branch());
                    }
                }
            }
        };
        // One-gesture review with the repository's remembered starting model
        // for the harness the review will launch; "Review…" above asks and
        // re-records it instead. An idle worktree starts the session right
        // there. The label names the model so the quick
        // action says what it will launch with, and it must track the state
        // store cheaply: it reads the in-memory repositories only, never git.
        AnAction quickReview = new DumbAwareAction("Review (default)",
                "Start a review session with the remembered starting model", AllIcons.Actions.Preview) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IdleWorktree && root != null);
                e.getPresentation().setText(quickReviewLabel(root));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IdleWorktree idle) {
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
                "Start a review session whose first prompt is the harness's sphinx command",
                AllIcons.Actions.Preview) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IdleWorktree && root != null
                        && sphinxAvailable(root));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IdleWorktree idle) {
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
                return ActionUpdateThread.BGT;
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
                return ActionUpdateThread.BGT;
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
        // Shows one project alone, or every project again: the selected
        // row's project, from any row under it. Focused, the same button
        // drops the focus, whatever is selected.
        AnAction focusProject = new DumbAwareAction("Focus on Project",
                "Show only the selected row's project", AllIcons.General.Locate) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                boolean focused = focus != null;
                e.getPresentation().setText(focused ? "Show All Projects" : "Focus on Project");
                e.getPresentation().setDescription(focused ? "Show every project again"
                        : "Show only the selected row's project");
                e.getPresentation().setEnabled(focused || e.getData(FOCUS_TARGET) != null);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                setFocus(focus != null ? null : e.getData(FOCUS_TARGET));
            }
        };
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
        menuActions.put(TaskMenu.Item.OPEN_IN_WORKBENCH, openInWorkbench);
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
        menuActions.put(TaskMenu.Item.FOCUS_PROJECT, focusProject);
        PopupHandler.installPopupMenu(tree,
                new TreeMenu(menuActions, delayedTaskActions, newDelayedTask, JiraTreeActions::jiraEnabled),
                "Drydock.TaskTree.Popup");

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
        toolbarActions.add(focusProject);
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
            // narrows the tree the same way the old chip row did. No view
            // fetches anything, so none needs a busy wait.
            viewFilter = candidate;
            requestRebuild(false);
        }), BorderLayout.CENTER);
        query.add(focusBar, BorderLayout.SOUTH);
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
                } else if (node instanceof Ghost ghost && acts) {
                    projectRootOf(path).ifPresent(root -> {
                        if (ghost.source() == GhostSource.GITHUB) {
                            openGitHubSettings(root);
                        } else {
                            JiraTreeActions.openSettings(project, root, fixed -> {
                                inputs.invalidateJira();
                                requestRebuild(false);
                            });
                        }
                    });
                } else if (node instanceof TaskRow task && primaryButton && event.getClickCount() == 1
                        && onLink(path, event)) {
                    hereLink(task);
                } else if (node instanceof DelayedTaskRow delayed && primaryButton
                        && event.getClickCount() == 2) {
                    // T20a: a task node's double-click is Edit (P5): its
                    // own file in the editor, the dispatch the Edit action runs.
                    DelayedTaskActions.edit(project, delayed);
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
                    projectRootOf(path).ifPresent(root -> openPullRequestInWorkbench(root, pullRequest));
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
        // restoration alike, single or bulk — reach the tracker, which
        // records only the user's; restoration runs inside its programmatic
        // guard.
        tree.addTreeExpansionListener(expansions);
        // A vetoed toggle never happens, so the tracker never records it.
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                vetoRowClickToggle(event);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
                vetoRowClickToggle(event);
            }
        });

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

        // The agent tab the user selects in the editor area selects its row.
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                        if (event.getNewFile() instanceof AgentConsoleFile console) {
                            consoleSelected(console.sessionId());
                        }
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
        // T17a: task changes (the service's snapshot publication, observed
        // through the store's VFS events) rebuild through the same coalesced
        // scheduler -- a burst of publications is one rebuild request stream,
        // not one rebuild per event. The panel's dispose closes the inputs,
        // which unsubscribes: no listener survives the panel.
        inputs.subscribeToTasks(() -> requestRebuild(false));
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
                TaskMenu.Item.OPEN_IN_GITHUB, TaskMenu.Item.OPEN_IN_JIRA, TaskMenu.Item.OPEN_IN_WORKBENCH,
                TaskMenu.Item.QUICK_REVIEW, TaskMenu.Item.REVIEW, TaskMenu.Item.REVIEW_IN_SPHINX,
                TaskMenu.Item.IMPLEMENT, TaskMenu.Item.TRIAGE,
                TaskMenu.Item.GITHUB_SETTINGS, TaskMenu.Item.JIRA_SETTINGS,
                TaskMenu.Item.ADD_JIRA_GROUP, TaskMenu.Item.EDIT_JIRA_GROUP, TaskMenu.Item.DELETE_JIRA_GROUP,
                TaskMenu.Item.REFRESH, TaskMenu.Item.FOCUS_PROJECT);
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

        /** Records the user's intent to see {@code root}'s pull-request listing (and open its section). */
        void requestPullRequests(Path root);

        /** Records the user's intent to see {@code root}'s GitHub issue listing. */
        void requestGitHubIssues(Path root);

        /** Records the user's intent to see one Jira group's listing. */
        void requestJiraGroup(Path root, String groupId);

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
     * requests) are dropped too, not just the worktree scan. From an
     * issues node's own context menu it re-reads only that project's
     * listing(s): the GitHub node its GitHub listing, the Jira node every
     * group it shows, a Jira group its own query. The refresh records the
     * fetch intent it needs, so a refresh of a not-yet-loaded node loads
     * it instead of staying unloaded. A node with no root above it — or no
     * listing of its own — fetches nothing rather than silently broadening
     * to a global fetch. Pure dispatch over {@code target}'s effects; EDT.
     */
    static void refresh(RefreshTarget target, boolean fromContextMenu, TaskNode node, Path root) {
        if (!fromContextMenu) {
            target.invalidateAll();
            target.requestRebuild(true);
            return;
        }
        // A node with no project above it has no listing to scope to; fetch
        // nothing rather than silently broadening to a global fetch.
        if (root == null) {
            return;
        }
        if (node instanceof ProjectRow) {
            // The project row's menu entry names a whole project: the
            // toolbar's scope.
            target.invalidateAll();
            target.requestRebuild(true);
            return;
        }
        if (node instanceof TaskNode.JiraGroupRow group) {
            // A group's refresh re-queries its project's Jira groups and
            // records the group's fetch intent, so a group that is visible
            // but not yet fetched actually loads instead of staying unloaded.
            target.invalidateJiraIssues(root);
            target.requestJiraGroup(root, group.group().id());
            target.requestRebuild(false);
            return;
        }
        if (node instanceof GitHubNode) {
            target.invalidateIssues(root);
            target.requestGitHubIssues(root);
            target.requestRebuild(false);
            return;
        }
        if (node instanceof JiraNode) {
            target.invalidateJiraIssues(root);
            for (String groupId : jiraGroupIdsUnder(node)) {
                target.requestJiraGroup(root, groupId);
            }
            target.requestRebuild(false);
            return;
        }
        if (!(node instanceof Section section)) {
            // Ghosts and every non-listing row have nothing to re-fetch; a
            // stray dispatch must not turn into a global fetch.
            return;
        }
        switch (section.kind()) {
            case ISSUES -> {
                target.invalidateIssues(root);
                target.invalidateJiraIssues(root);
                // The section's GitHub node and every group it shows are
                // what "refresh" means here: fetch them, even if none of
                // them was ever opened.
                target.requestGitHubIssues(root);
                for (String groupId : jiraGroupIdsUnder(node)) {
                    target.requestJiraGroup(root, groupId);
                }
                target.requestRebuild(false);
            }
            case PULL_REQUESTS -> {
                target.invalidatePullRequests(root);
                target.requestPullRequests(root);
                target.requestRebuild(false);
            }
            // Sessions/worktrees sections have no listing to scope to; the
            // menu never offers Refresh on them, and a stray dispatch must
            // not turn into a global fetch.
            default -> {
            }
        }
    }

    /** The ids of the Jira groups displayed under {@code node} (a section or the Jira node). */
    private static List<String> jiraGroupIdsUnder(TaskNode node) {
        List<String> ids = new ArrayList<>();
        collectJiraGroupIds(node, ids);
        return ids;
    }

    private static void collectJiraGroupIds(TaskNode node, List<String> ids) {
        switch (node) {
            case JiraGroupRow group -> ids.add(group.group().id());
            case Section section -> section.children().forEach(child -> collectJiraGroupIds(child, ids));
            case JiraNode jira -> jira.children().forEach(child -> collectJiraGroupIds(child, ids));
            default -> {
            }
        }
    }

    /**
     * The tree's context menu: {@link TaskMenu#group} over the selection at
     * popup time, so each popup holds exactly the actions that apply to
     * the selected rows, in TaskMenu's order — navigation first,
     * destructive cleanup last (A10). BGT: it reads the selection from the
     * event's data, which {@link #uiDataSnapshot} captured on the EDT, never
     * from the Swing tree, so the popup never waits for a busy EDT.
     */
    static final class TreeMenu extends ActionGroup implements DumbAware {

        private final Map<TaskMenu.Item, AnAction> actions;
        private final AnAction[] delayedTaskActions;
        private final AnAction newDelayedTask;
        /** Whether a project root's Jira connection is enabled; it canonicalizes the root (filesystem IO). */
        private final Predicate<Path> jiraEnabled;

        TreeMenu(Map<TaskMenu.Item, AnAction> actions, AnAction[] delayedTaskActions, AnAction newDelayedTask,
                 Predicate<Path> jiraEnabled) {
            this.actions = actions;
            this.delayedTaskActions = delayedTaskActions;
            this.newDelayedTask = newDelayedTask;
            this.jiraEnabled = jiraEnabled;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public AnAction[] getChildren(@Nullable AnActionEvent e) {
            // The platform enumerates a group's children with no event in
            // some paths; with no event no popup is being built for the
            // tree, and its menu applies to nothing.
            if (e == null) {
                return AnAction.EMPTY_ARRAY;
            }
            // The built group's actions as they are: ActionGroup.getChildren
            // is override-only API, the platform's to call, not ours.
            // T20a: a task node carries the delayed-task layer's own
            // actions, and the Tasks node offers New Task over the T18a
            // form -- everything else keeps the session-layer menu it had.
            if (e.getData(DelayedTaskActions.SELECTED_TASK) != null) {
                return delayedTaskActions;
            }
            if (e.getData(SELECTED_TASKS_SECTION) != null) {
                return new AnAction[] { newDelayedTask };
            }
            List<TaskNode> selection = e.getData(SELECTED_NODES);
            Path root = e.getData(SELECTED_PROJECT_ROOT);
            return TaskMenu.group(e.getData(SELECTED_NODE), selection == null ? List.of() : selection, actions,
                    root != null && jiraEnabled.test(root)).getChildActionsOrStubs();
        }
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
        sink.set(WORKTREE_REMOVAL_UI, this::worktreesRemoved);
        selected().ifPresent(node -> sink.set(SELECTED_NODE, node));
        // T20a: a task node is its own selection data -- the task-node
        // actions key on it; selected() answers only session-layer rows.
        Object value = selectedValue();
        if (value instanceof DelayedTaskRow task) {
            sink.set(DelayedTaskActions.SELECTED_TASK, task);
        } else if (value instanceof TasksSection section) {
            sink.set(SELECTED_TASKS_SECTION, section);
        }
        List<TaskNode> selected = selectedNodes();
        if (!selected.isEmpty()) {
            sink.set(SELECTED_NODES, selected);
        }
        selectedProjectRoot().ifPresent(root -> sink.set(SELECTED_PROJECT_ROOT, root));
        focusTarget().ifPresent(target -> sink.set(FOCUS_TARGET, target));
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
                && (section.kind() == SectionKind.ISSUES || section.kind() == SectionKind.PULL_REQUESTS)
                || node instanceof GitHubNode
                || node instanceof Ghost ghost && ghost.source() == GhostSource.GITHUB;
    }

    /** Opens the GitHub settings for {@code root}'s project, then rebuilds. EDT. */
    private void openGitHubSettings(Path root) {
        DrydockPluginService.getInstance().sharedProjects().byRoot(root).ifPresent(registered -> {
            if (new GitHubProjectSettingsDialog(project, registered).showAndGet()) {
                inputs.invalidateGitHub();
                requestRebuild(false);
            }
        });
    }

    /** Opens the pull request's review workbench in its worktree's window. EDT. */
    private void openPullRequestInWorkbench(Path mainRoot, PullRequestRow pullRequest) {
        if (mainRoot == null) {
            return;
        }
        String repositoryName = DrydockPluginService.getInstance().sharedProjects().byRoot(mainRoot)
                .map(SharedProject::displayName)
                .orElseGet(() -> String.valueOf(mainRoot.getFileName()));
        pullRequestReview.openInWorkbench(project, mainRoot, repositoryName, pullRequest.pullRequest());
    }

    /** {@link #quickReviewLabel(Map, List, AgentRegistry, Path)} over this panel's map and the live plugin state. */
    private String quickReviewLabel(Path mainRoot) {
        if (mainRoot == null) {
            return quickReviewLabel(Map.of(), List.of(), null, null);
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        return quickReviewLabel(repositoryIdsByRoot, plugin.stateStore().state().repositories(),
                plugin.registry(), mainRoot);
    }

    /**
     * The quick review action's label: the remembered starting model of the
     * harness the review will launch, or "default" when none was picked yet.
     * Reads in-memory state only (a precomputed canonical-root map, the state
     * store's repositories, the registry's init-time answers) — never git,
     * never the disk — because {@code update} runs on every selection change;
     * a root not yet mapped reads "default" until the next rebuild. Pure; any
     * thread. {@code registry} may be {@code null} only when {@code mainRoot} is.
     */
    static String quickReviewLabel(Map<Path, RepositoryId> idsByRoot, List<Repository> repositories,
                                   AgentRegistry registry, Path mainRoot) {
        if (mainRoot == null) {
            return "Review (default)";
        }
        Optional<Repository> repository = repositoryAt(idsByRoot, repositories, mainRoot);
        String model = reviewAgent(registry, repository)
                .flatMap(kind -> repository.flatMap(r -> r.settings().lastUsedModel(kind)))
                .orElse("default");
        return "Review (" + model + ")";
    }

    /** {@link #sphinxAvailable(Map, List, AgentRegistry, Path)} over this panel's map and the live plugin state. */
    private boolean sphinxAvailable(Path mainRoot) {
        if (mainRoot == null) {
            return false;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        return sphinxAvailable(repositoryIdsByRoot, plugin.stateStore().state().repositories(),
                plugin.registry(), mainRoot);
    }

    /**
     * Whether the repository's default harness reaches the sphinx plugin —
     * the same in-memory reads {@code quickReviewLabel} builds on, never git
     * and never a fresh probe ({@code AgentRegistry#sphinxReview} reads its
     * init-time snapshot). {@code null} root or an unknown repository reads
     * as unavailable — absent must not look like offered. Pure; any thread.
     */
    static boolean sphinxAvailable(Map<Path, RepositoryId> idsByRoot, List<Repository> repositories,
                                   AgentRegistry registry, Path mainRoot) {
        if (mainRoot == null) {
            return false;
        }
        Optional<Repository> repository = repositoryAt(idsByRoot, repositories, mainRoot);
        return reviewAgent(registry, repository).flatMap(registry::sphinxReview).isPresent();
    }

    /**
     * The repository recorded for canonical {@code mainRoot}: its id from the
     * precomputed map ({@code mainRoot} is already canonical, see
     * TaskTreeInputs.gather), its settings from {@code repositories}.
     */
    private static Optional<Repository> repositoryAt(Map<Path, RepositoryId> idsByRoot,
                                                     List<Repository> repositories, Path mainRoot) {
        RepositoryId id = idsByRoot.get(mainRoot);
        return id == null ? Optional.empty()
                : repositories.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** The harness a review of {@code repository} launches: its default agent, never the terminal. */
    private static Optional<AgentKind> reviewAgent(AgentRegistry registry, Optional<Repository> repository) {
        return PullRequestReview.selectReviewAgent(
                SessionDialogs.defaultAgent(registry, repository.flatMap(r -> r.settings().lastUsedAgent())),
                registry::isAvailable);
    }

    /**
     * The registered project's display name, else the folder's -- the name
     * a saved task registers under (T19a's rule, D2). Lexical, never a
     * canonicalizing probe: the caller already canonicalized off the EDT.
     */
    private static String repositoryDisplayName(DrydockPluginService plugin, Path root) {
        return plugin.sharedProjects().byRoot(root).map(SharedProject::displayName)
                .orElseGet(() -> String.valueOf(root.getFileName()));
    }

    /** The live catalog as the picker's project-slug mapping (P2), the same
     * projection the task layer applies (T17a): read once from memory. */
    private static Function<RepositoryId, String> taskSlugs(DrydockPluginService plugin) {
        Map<RepositoryId, String> displayNames = displayNamesByRepository(plugin);
        return repository -> slugOf(displayNames.get(repository));
    }

    /**
     * The task node's actions (T20a): the plugin.xml-registered instances
     * when their ids resolve, the locally built ones otherwise -- an
     * unregistered id degrades the menu, never the tree.
     */
    private static AnAction[] taskNodeMenu() {
        ActionManager manager = ActionManager.getInstance();
        return Arrays.stream(DelayedTaskActions.menu()).map(entry -> {
            AnAction registered = manager.getAction(entry.id());
            return registered != null ? registered : entry.local();
        }).toArray(AnAction[]::new);
    }

    /**
     * The Tasks node's completion dispatch (T20a): what one ACCEPTED dialog
     * result does, and the only thing the flow ever does with one -- a save
     * through the production saveTask core. A canceled dialog answers no
     * accepted result, so NO plan is built and NOTHING is written (P5's
     * zero-write). Pure; the dialog's own behavior is the platform fixture's.
     */
    static Optional<app.drydock.tasks.NewTaskPlan> savedTaskPlan(
            Optional<NewDelayedTaskDialog.Result> accepted, Path projectRoot, String projectName) {
        return accepted.map(result -> new app.drydock.tasks.NewTaskPlan(result.title(), result.body(),
                result.after(), result.doneBy(), projectRoot, projectName));
    }

    /**
     * The Tasks node's New Task… (T20a, P5): the T18a creation form over one
     * indexed snapshot -- the CACHED watched one when the watcher has
     * published, else the service's own async scan; never a render-thread
     * read. Immediate progress: the background task's own indicator; a
     * canceled dialog writes nothing; failures surface in a dialog and the
     * task ends on every path, so no progress is stranded.
     */
    private void newDelayedTask(Project from) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Reading tasks…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DelayedTaskService service = plugin.taskService();
                    WatchedSnapshot watched = service.watchedSnapshot();
                    TaskSnapshot snapshot = watched != null ? watched.snapshot()
                            : service.snapshot().get(60, TimeUnit.SECONDS);
                    indicator.setText("Indexing tasks");
                    TaskPrerequisitePicker.Index index =
                            TaskPrerequisitePicker.Index.of(snapshot, taskSlugs(plugin));
                    Path root = Optional.ofNullable(from.getBasePath()).map(Path::of)
                            .map(RepositoryCatalog::canonicalize).orElse(null);
                    if (root == null) {
                        return;
                    }
                    String name = repositoryDisplayName(plugin, root);
                    ApplicationManager.getApplication().invokeLater(
                            () -> createTask(from, index, root, name),
                            ModalityState.nonModal(), from.getDisposed());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not open the New Task form", e);
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(from,
                            "Drydock could not open the New Task form: " + e.getMessage(), "Drydock"),
                            ModalityState.nonModal(), from.getDisposed());
                }
            }
        });
    }

    /**
     * EDT: the T18a form over one index. A confirmed OK saves through the
     * production saveTask core (the service's create seam -- no worktree, no
     * agent, P5); a canceled dialog writes nothing.
     */
    private void createTask(Project from, TaskPrerequisitePicker.Index index,
                            Path canonicalRoot, String projectName) {
        NewDelayedTaskDialog dialog = new NewDelayedTaskDialog(index);
        // A canceled dialog carries no accepted result: no plan, no write (P5).
        savedTaskPlan(dialog.showAndGet() ? Optional.of(dialog.result()) : Optional.empty(),
                canonicalRoot, projectName)
                .ifPresent(plan -> TaskLauncher.saveTask(from, plan));
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
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                if (root == null) {
                    return;
                }
                openGitHubSettings(root);
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
        Focus requestedFocus = focus;
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
                    Set.copyOf(expansions.requestedGitHubIssues()),
                    Set.copyOf(expansions.requestedJiraGroups()),
                    Set.copyOf(expansions.requested(SectionKind.PULL_REQUESTS)));
            // Only a completed gather knows which groups still exist: a deleted
            // group, an unregistered project or a disabled connection stops being
            // remembered as open. A failed gather never gets here and forgets nothing.
            expansions.retainDefinedJiraGroups(gathered.jiraGroupsByMainRoot());
            List<TaskNode> nodes = TaskTreeModel.build(gathered);
            // T17a: the task layer rides this same background pass -- the
            // cached snapshot the gather read, the T15a row derivation, and
            // the in-memory catalog for slugs; never a fresh scan, never
            // the EDT.
            TaskLayer tasks = taskLayer(nodes, inputs.watchedTasks(),
                    plugin.taskService().liveSessionIds(), displayNamesByRepository(plugin),
                    requestedFilter, Instant.now());
            // The focus narrows what this pass built; the unfocused rows ride
            // along so a session the focus hides can still be placed.
            List<TaskNode> shown = nodes;
            TaskLayer shownTasks = tasks;
            if (requestedFocus != null) {
                shown = ProjectFocus.focused(nodes, requestedFocus.root());
                Map<RepositoryId, Path> roots = repositoryRoots(plugin);
                shownTasks = ProjectFocus.focused(tasks, repository -> ProjectFocus.inFocus(repository,
                        requestedFocus.root(), roots, gathered.mainRootOf()));
            }
            List<TaskNode> applied = shown;
            TaskLayer appliedTasks = shownTasks;
            ApplicationManager.getApplication().invokeLater(
                    () -> finishRebuild(requestedVersion, requestedFilter, requestedViewFilter, applied, nodes,
                            appliedTasks, null),
                    ModalityState.any(), project.getDisposed());
        }, plugin.executor()).exceptionally(failure -> {
            ApplicationManager.getApplication().invokeLater(
                    () -> finishRebuild(requestedVersion, requestedFilter, requestedViewFilter, List.of(),
                            List.of(), TaskLayer.EMPTY, failure),
                    ModalityState.any(), project.getDisposed());
            return null;
        });
    }

    /** EDT: applies only the newest requested view, then schedules any request coalesced behind it. */
    private void finishRebuild(long requestedVersion, String requestedFilter, ViewFilter requestedViewFilter,
                               List<TaskNode> nodes, List<TaskNode> unfocused, TaskLayer tasks,
                               Throwable failure) {
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
            apply(nodes, unfocused, tasks, requestedFilter, requestedViewFilter);
        }
        refreshRequests.finishPass();
        if (!disposed && !project.isDisposed() && newerPassPending) {
            scheduleRebuild();
        }
    }

    /** EDT: swaps in the new rows, keeping the selected task selected. */
    private void apply(List<TaskNode> nodes, List<TaskNode> unfocused, TaskLayer tasks, String appliedFilter,
                       ViewFilter appliedViewFilter) {
        displayedNodes = List.copyOf(nodes);
        displayedUnfocusedNodes = List.copyOf(unfocused);
        displayedTasks = tasks;
        displayedFilter = appliedFilter;
        displayedViewFilter = appliedViewFilter;
        Optional<ManagedSessionId> selectedSession = selected()
                .filter(TaskRow.class::isInstance)
                .map(node -> ((TaskRow) node).session().id());
        Optional<TaskId> selectedTask = selectedTask();
        // T17a: what the user collapsed stays collapsed across the rebuild
        // -- keyed on the row identities the tree uses (session ids, the
        // Tasks section's own node), never on Swing paths.
        Set<String> collapsedSessions = collapsedSessionRows();
        boolean tasksSectionCollapsed = tasksSectionCollapsed();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode[] toSelect = new DefaultMutableTreeNode[1];
        for (TasksSection section : tasks.sections()) {
            root.add(treeNode(section, selectedTask, toSelect));
        }
        for (TaskNode node : nodes) {
            root.add(treeNode(node, selectedSession, selectedTask, tasks, toSelect));
        }
        // Replacing the root is not intent either: whatever expansion
        // events the model swap fires must not touch the records.
        expansions.runProgrammatic(() -> ((DefaultTreeModel) tree.getModel()).setRoot(root));
        tree.getEmptyText().setText(emptyText(appliedFilter, appliedViewFilter));
        // This pass replaced the rows; the load it announced is over, even
        // when the rows are empty (the empty text says that instead).
        tree.setPaintBusy(false);
        // Restoration, not intent: every expansion in this span (and any a
        // selection could cause) records no user request.
        expansions.runProgrammatic(() -> {
            restoreExpansions(root, new TreePath(root.getPath()), null, expansions, tree::expandPath);
            // T17a: the Tasks section and the session rows that gained task
            // children (D10) open with their content; the user's collapse of
            // them, snapshotted above, is restored instead.
            if (!tasksSectionCollapsed) {
                for (int i = 0; i < root.getChildCount(); i++) {
                    if (root.getChildAt(i) instanceof DefaultMutableTreeNode child
                            && child.getUserObject() instanceof TasksSection) {
                        tree.expandPath(new TreePath(child.getPath()));
                    }
                }
            }
            expandSessionTaskRows(root, new TreePath(root.getPath()), collapsedSessions);
            if (toSelect[0] != null) {
                tree.setSelectionPath(new TreePath(toSelect[0].getPath()));
            }
        });
        // A session the focus hid is selected once a focus change shows it;
        // one that left the tree altogether is no longer outside anything.
        if (outsideFocus != null) {
            if (sessionPath(root, outsideFocus).isPresent()) {
                selectSessionRow(outsideFocus);
                outsideFocus = null;
            } else if (hiddenProject(outsideFocus).isEmpty()) {
                outsideFocus = null;
            }
        }
        updateFocusBar();
    }

    /**
     * Whether an issues container node (the GitHub node, the Jira node, a
     * Jira group) expands: only after the user opened it here — a rebuild
     * re-collapses nothing the user had open, and no view opens any of
     * them for free (issues are strictly lazy). Pure.
     */
    static boolean expandsIssuesNode(boolean requested) {
        return requested;
    }

    /**
     * The panel's restoration walk: expands, through {@code expand}, every
     * eager node and every lazy section or Jira group the user's requests
     * open — each node before its children, children in
     * order. The project root of a node is its nearest project row's main
     * root, carried down the walk; a section or group with none never
     * expands, in any view. Static and panel-free (the side effect is the
     * {@code expand} callback) so the tests can walk a model tree without a
     * live project.
     */
    static void restoreExpansions(DefaultMutableTreeNode node, TreePath path, Path projectRoot,
                                  ListingExpansionTracker expansions, Consumer<TreePath> expand) {
        Object value = node.getUserObject();
        Path root = value instanceof ProjectRow row ? row.project().root() : projectRoot;
        boolean expanded = switch (value) {
            case null -> false;
            case ProjectRow ignored -> true;
            case WorktreeGroup ignored -> true;
            case StaleWorktrees ignored -> true;
            case Section section -> switch (section.kind()) {
                case SESSIONS, ACTIVE, INACTIVE -> true;
                case WORKTREES -> root != null
                        && expansions.requested(SectionKind.WORKTREES).contains(root);
                case ISSUES -> root != null
                        && expansions.requestedIssueSections().contains(root);
                case PULL_REQUESTS -> root != null
                        && expansions.requested(SectionKind.PULL_REQUESTS).contains(root);
            };
            case GitHubNode ignored -> root != null
                    && expandsIssuesNode(expansions.requestedGitHubIssues().contains(root));
            case JiraNode ignored -> root != null
                    && expandsIssuesNode(expansions.requestedJiraNodes().contains(root));
            case JiraGroupRow group -> root != null && expandsIssuesNode(
                    expansions.requestedJiraGroups().contains(
                            new ListingExpansionTracker.GroupKey(root, group.group().id())));
            default -> false;
        };
        if (expanded) {
            expand.accept(path);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            restoreExpansions(child, path.pathByAddingChild(child), root, expansions, expand);
        }
    }

    /** EDT: hides confirmed removals before the slower authoritative rescan finishes. */
    private void worktreesRemoved(Set<Path> removed) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed || project.isDisposed() || removed.isEmpty()) {
            return;
        }
        apply(TaskTreeModel.withoutWorktrees(displayedNodes, removed),
                TaskTreeModel.withoutWorktrees(displayedUnfocusedNodes, removed), displayedTasks,
                displayedFilter, displayedViewFilter);
    }

    static String emptyText(String appliedFilter, ViewFilter appliedViewFilter) {
        if (!appliedFilter.isBlank()) {
            return "Nothing in " + appliedViewFilter.label().toLowerCase(Locale.ROOT) + " matches the search.";
        }
        return appliedViewFilter.emptyText();
    }

    /**
     * The Swing node of one model node, attaching every child a folder node
     * holds (a project, a section, a worktree, an issues container or a Jira
     * group) — a node whose children are never attached is a leaf the tree
     * cannot expand, however expandable the model says it is. Package-private
     * so the dispatch tests can assert the mapping directly.
     */
    static DefaultMutableTreeNode treeNode(TaskNode node, Optional<ManagedSessionId> selected,
                                           Optional<TaskId> selectedTask, TaskLayer tasks,
                                           DefaultMutableTreeNode[] toSelect) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        switch (node) {
            case ProjectRow project -> project.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, selectedTask, tasks, toSelect)));
            case Section section -> section.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, selectedTask, tasks, toSelect)));
            case StaleWorktrees stale -> stale.worktrees().forEach(
                    worktree -> treeNode.add(treeNode(worktree, selected, selectedTask, tasks, toSelect)));
            case WorktreeGroup group -> group.tasks().forEach(
                    task -> treeNode.add(treeNode(task, selected, selectedTask, tasks, toSelect)));
            case JiraGroupRow group -> group.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, selectedTask, tasks, toSelect)));
            case GitHubNode github -> github.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, selectedTask, tasks, toSelect)));
            case JiraNode jira -> jira.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, selectedTask, tasks, toSelect)));
            case TaskRow task -> {
                if (selected.map(task.session().id()::equals).orElse(false)) {
                    toSelect[0] = treeNode;
                }
                // T17a: the task children the layer attaches below this
                // session row (D10) -- built through the same path, so the
                // row and its children restore as one.
                for (app.drydock.tasks.TaskNode.DelayedTaskRow child : tasks.attachedBySession()
                        .getOrDefault(task.session().id().toString(), List.of())) {
                    treeNode.add(treeNode(child, selectedTask, toSelect));
                }
            }
            default -> {
            }
        }
        return treeNode;
    }

    /** One task-layer section's tree node (T17a): its children, built by the same path. */
    private static DefaultMutableTreeNode treeNode(TasksSection section,
                                                   Optional<TaskId> selectedTask, DefaultMutableTreeNode[] toSelect) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(section);
        // The layer only ever puts task rows in its sections; other kinds
        // (a session wrapper) are not produced by this panel.
        section.children().forEach(child -> {
            if (child instanceof app.drydock.tasks.TaskNode.DelayedTaskRow task) {
                treeNode.add(treeNode(task, selectedTask, toSelect));
            }
        });
        return treeNode;
    }

    /** One delayed task's tree node (T17a): a leaf, kept selected by task id. */
    private static DefaultMutableTreeNode treeNode(app.drydock.tasks.TaskNode.DelayedTaskRow task,
                                                   Optional<TaskId> selectedTask, DefaultMutableTreeNode[] toSelect) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(task);
        if (selectedTask.map(task.id()::equals).orElse(false)) {
            toSelect[0] = treeNode;
        }
        return treeNode;
    }

    /**
     * The selected delayed-task row's id (T17a), for the rebuild's
     * selection keep; a selected session row answers empty (the session
     * id keeps that selection).
     */
    private Optional<TaskId> selectedTask() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
            return Optional.empty();
        }
        return node.getUserObject() instanceof app.drydock.tasks.TaskNode.DelayedTaskRow task
                ? Optional.of(task.id()) : Optional.empty();
    }

    /** The lead selected row's raw value, whichever layer built it (T20a). */
    private Object selectedValue() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
            return null;
        }
        return node.getUserObject();
    }

    /** The session rows whose children the user collapsed in the current tree (T17a), keyed by session id. */
    private Set<String> collapsedSessionRows() {
        Set<String> collapsed = new HashSet<>();
        if (tree.getModel().getRoot() instanceof DefaultMutableTreeNode root) {
            collectCollapsedSessionRows(root, new TreePath(root.getPath()), collapsed);
        }
        return collapsed;
    }

    private void collectCollapsedSessionRows(DefaultMutableTreeNode node, TreePath path, Set<String> out) {
        // A collapsed session row is the only state a leaf never had: a row
        // without children reads expanded, so it records nothing and opens
        // with the children it may gain.
        if (node.getChildCount() > 0 && tree.isCollapsed(path)
                && node.getUserObject() instanceof TaskRow task) {
            out.add(task.session().id().toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            collectCollapsedSessionRows(child, path.pathByAddingChild(child), out);
        }
    }

    /**
     * Task-related parents (the Tasks section, a session row, a task row)
     * toggle only from the expand handle: a click on the row itself selects,
     * activates or edits it, and never folds its children.
     */
    private void vetoRowClickToggle(TreeExpansionEvent event) throws ExpandVetoException {
        if (!(EventQueue.getCurrentEvent() instanceof MouseEvent mouse) || mouse.getComponent() != tree) {
            return;
        }
        TreePath path = event.getPath();
        Object node = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(node instanceof TasksSection || node instanceof TaskRow || node instanceof DelayedTaskRow)) {
            return;
        }
        Rectangle bounds = tree.getPathBounds(path);
        // The handle sits left of the row's bounds; a press there is the handle's toggle.
        if (bounds != null && mouse.getX() >= bounds.x
                && path.equals(tree.getPathForLocation(mouse.getX(), mouse.getY()))) {
            throw new ExpandVetoException(event, "a row click does not toggle a task row");
        }
    }

    /** Whether the user collapsed the Tasks section in the current tree (T17a). */
    private boolean tasksSectionCollapsed() {
        if (tree.getModel().getRoot() instanceof DefaultMutableTreeNode root && root.getChildCount() > 0
                && root.getChildAt(0) instanceof DefaultMutableTreeNode first
                && first.getUserObject() instanceof TasksSection) {
            return tree.isCollapsed(new TreePath(first.getPath()));
        }
        return false;
    }

    /** Opens every session row that carries task children, except the ones the user collapsed (T17a). */
    private void expandSessionTaskRows(DefaultMutableTreeNode node, TreePath path, Set<String> collapsedSessions) {
        if (node.getUserObject() instanceof TaskRow task && node.getChildCount() > 0
                && !collapsedSessions.contains(task.session().id().toString())) {
            tree.expandPath(path);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            expandSessionTaskRows(child, path.pathByAddingChild(child), collapsedSessions);
        }
    }

    /**
     * The delayed-task layer of one rebuild (T17a): the section rows for
     * the tasks no session row claims, and the children each session row
     * gains. Pure data; empty when the service has published no snapshot
     * yet (the session-only input).
     */
    record TaskLayer(List<TasksSection> sections,
                     Map<String, List<app.drydock.tasks.TaskNode.DelayedTaskRow>> attachedBySession) {

        static final TaskLayer EMPTY = new TaskLayer(List.of(), Map.of());

        TaskLayer {
            sections = List.copyOf(sections);
            attachedBySession = Map.copyOf(attachedBySession);
        }
    }

    /**
     * The delayed-task layer of one rebuild (T17a): a PURE derivation over
     * the T15a rows -- the snapshot is the service's cached publication,
     * the session rows are what the existing model built, the clock is
     * injected; no file, catalog or clock is read here. Every task whose
     * stored sessions name a session row this tree shows becomes that
     * row's child (D10); the others form the Tasks section (D9). The
     * session rows themselves pass through unchanged.
     */
    static TaskLayer taskLayer(List<TaskNode> nodes, Optional<WatchedSnapshot> tasks, Set<String> liveSessionIds,
                               Map<RepositoryId, String> displayNames, String filter, Instant now) {
        if (tasks.isEmpty()) {
            return TaskLayer.EMPTY;
        }
        Map<String, TaskRow> shown = sessionRowsOf(nodes);
        Function<RepositoryId, String> slugs = repository -> slugOf(displayNames.get(repository));
        TaskTreeModel.ProjectScope scope = TaskTreeModel.parseFilter(filter);
        String itemFilter = scope.scoped() ? scope.itemFilter() : filter;
        List<app.drydock.tasks.TaskNode.DelayedTaskRow> rows =
                DelayedTaskRows.rows(tasks.get(), liveSessionIds, slugs, now);
        Map<String, List<app.drydock.tasks.TaskNode.DelayedTaskRow>> attached = new LinkedHashMap<>();
        List<app.drydock.tasks.TaskNode.DelayedTaskRow> unattached = new ArrayList<>();
        for (app.drydock.tasks.TaskNode.DelayedTaskRow row : DelayedTaskRows.sorted(rows)) {
            DelayedTaskRows.attachedSession(row, shown.keySet()).ifPresentOrElse(
                    session -> attached.computeIfAbsent(session, any -> new ArrayList<>()).add(row),
                    () -> unattached.add(row));
        }
        Map<String, List<app.drydock.tasks.TaskNode.DelayedTaskRow>> bySession = new LinkedHashMap<>();
        for (Map.Entry<String, List<app.drydock.tasks.TaskNode.DelayedTaskRow>> entry : attached.entrySet()) {
            TaskRow session = shown.get(entry.getKey());
            // The same rule the session model applies to a matching project
            // name: a row that itself matches is the answer, its children
            // stay; otherwise the item filter narrows them.
            String childFilter = scope.scoped() ? itemFilter
                    : !filter.isEmpty() && contains(session.session().displayName(), filter) ? "" : filter;
            List<app.drydock.tasks.TaskNode.DelayedTaskRow> children =
                    DelayedTaskRows.active(entry.getValue()).stream()
                            .filter(row -> DelayedTaskRows.matches(childFilter, row))
                            .toList();
            if (!children.isEmpty()) {
                bySession.put(entry.getKey(), children);
            }
        }
        // Terminal tasks stay hidden behind the filter ribbon (D9): the
        // section holds the active ones, a scope drops other projects'
        // rows, the search narrows by title or id.
        List<app.drydock.tasks.TaskNode.DelayedTaskRow> sectionRows =
                DelayedTaskRows.active(unattached).stream()
                        .filter(row -> inScope(row, scope, displayNames))
                        .filter(row -> DelayedTaskRows.matches(itemFilter, row))
                        .toList();
        List<TasksSection> sections = sectionRows.isEmpty() ? List.of()
                : List.of(new TasksSection("Tasks",
                        sectionRows.stream().map(row -> (app.drydock.tasks.TaskNode) row).toList()));
        return new TaskLayer(sections, bySession);
    }

    /**
     * Every session row in the built tree, by session id string (T17a):
     * the tree's own row identity, which D10's membership uses and the
     * rebuild's restore keys on. Pure.
     */
    static Map<String, TaskRow> sessionRowsOf(List<TaskNode> nodes) {
        Map<String, TaskRow> rows = new LinkedHashMap<>();
        sessionRowsOf(nodes, rows);
        return rows;
    }

    private static void sessionRowsOf(List<TaskNode> nodes, Map<String, TaskRow> rows) {
        for (TaskNode node : nodes) {
            switch (node) {
                case ProjectRow project -> sessionRowsOf(project.children(), rows);
                case Section section -> sessionRowsOf(section.children(), rows);
                case WorktreeGroup group -> group.tasks().forEach(
                        task -> rows.putIfAbsent(task.session().id().toString(), task));
                case TaskRow task -> rows.putIfAbsent(task.session().id().toString(), task);
                default -> {
                }
            }
        }
    }

    /** Whether the row's project survives the search's project scope (T17a). Pure. */
    private static boolean inScope(app.drydock.tasks.TaskNode.DelayedTaskRow row,
                                   TaskTreeModel.ProjectScope scope, Map<RepositoryId, String> displayNames) {
        if (!scope.scoped()) {
            return true;
        }
        String name = displayNames.get(row.repository());
        return name != null && scope.matchesProject(name);
    }

    /**
     * The live repository catalog as the task layer sees it (T17a): each
     * repository's display name, from the in-memory state, once per
     * background pass. No I/O.
     */
    static Map<RepositoryId, String> displayNamesByRepository(DrydockPluginService plugin) {
        Map<RepositoryId, String> names = new LinkedHashMap<>();
        for (Repository repository : plugin.stateStore().state().repositories()) {
            names.putIfAbsent(repository.id(), repository.displayName());
        }
        return names;
    }

    /**
     * The display-name slug (P2, T17a): the same projection the task
     * service and the goto-declaration handler apply (both private);
     * lowercase letters, digits and inner hyphens, capped at 60. Pure.
     */
    static String slugOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder(61);
        for (int i = 0; i < name.length() && out.length() < 60; i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == '-') {
            out.setLength(length - 1);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** Case-insensitive containment, the tree's existing search rule. */
    private static boolean contains(String text, String filter) {
        return text.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
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

    /**
     * Selects and scrolls to the row of {@code sessionId}, if the tree shows
     * one. Runs inside the tracker's programmatic guard: selecting a row
     * expands its collapsed ancestors, which is not the user's request to
     * open them. EDT.
     */
    private void selectSessionRow(ManagedSessionId sessionId) {
        if (disposed) {
            return;
        }
        sessionPath((DefaultMutableTreeNode) tree.getModel().getRoot(), sessionId).ifPresent(path -> {
            if (!path.equals(tree.getSelectionPath())) {
                expansions.runProgrammatic(() -> {
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                });
            }
        });
    }

    /** A registered project the tree can show alone: its main root and the name the focus bar shows. */
    private record Focus(Path root, String name) {
    }

    /** EDT: the project the selected rows belong to, when it is a registered project's row the tree shows. */
    private Optional<Focus> focusTarget() {
        return selectedProjectRoot().flatMap(root -> displayedNodes.stream()
                .filter(ProjectRow.class::isInstance)
                .map(ProjectRow.class::cast)
                .filter(row -> row.project().root().equals(root))
                .findFirst()
                .map(row -> new Focus(root, row.project().displayName())));
    }

    /** EDT: shows {@code next} alone, or every project for null, and rebuilds. */
    private void setFocus(Focus next) {
        focus = next;
        updateFocusBar();
        // The rebuild that applies the focus clears this, on every path.
        tree.setPaintBusy(true);
        requestRebuild(false);
    }

    /** EDT: moves the focus to the project of the selected session the focus hides. */
    private void focusOnOutside() {
        if (outsideFocus != null) {
            hiddenProject(outsideFocus).ifPresent(row ->
                    setFocus(new Focus(row.project().root(), row.project().displayName())));
        }
    }

    /**
     * EDT: the agent tab of {@code sessionId} was selected in the editor
     * area. Its row is selected when the tree shows it; when the focus hides
     * it, the focus bar names its project instead.
     */
    private void consoleSelected(ManagedSessionId sessionId) {
        outsideFocus = hiddenProject(sessionId).isPresent() ? sessionId : null;
        updateFocusBar();
        selectSessionRow(sessionId);
    }

    /** EDT: the project row of {@code sessionId} when the focus is what hides it. */
    private Optional<ProjectRow> hiddenProject(ManagedSessionId sessionId) {
        Focus current = focus;
        if (current == null) {
            return Optional.empty();
        }
        return ProjectFocus.projectOf(displayedUnfocusedNodes, sessionId)
                .filter(row -> !row.project().root().equals(current.root()));
    }

    /** EDT: brings the focus bar in line with the focus and the hidden selected session. */
    private void updateFocusBar() {
        Focus current = focus;
        if (current == null) {
            focusBar.display(Optional.empty());
            return;
        }
        Optional<ProjectFocusBar.Outside> outside = outsideFocus == null ? Optional.empty()
                : hiddenProject(outsideFocus).map(row -> new ProjectFocusBar.Outside(
                        sessionRowsOf(List.of(row)).get(outsideFocus.toString()).session().displayName(),
                        row.project().displayName()));
        focusBar.display(Optional.of(new ProjectFocusBar.State(current.name(), outside)));
    }

    /** Background only: each recorded repository's canonical root (filesystem IO). */
    private static Map<RepositoryId, Path> repositoryRoots(DrydockPluginService plugin) {
        Map<RepositoryId, Path> roots = new LinkedHashMap<>();
        for (Repository repository : plugin.stateStore().state().repositories()) {
            roots.putIfAbsent(repository.id(), RepositoryCatalog.canonicalize(repository.root()));
        }
        return roots;
    }

    /** The path of the first session row of {@code sessionId} under {@code node}, depth first. Pure. */
    static Optional<TreePath> sessionPath(DefaultMutableTreeNode node, ManagedSessionId sessionId) {
        if (node.getUserObject() instanceof TaskRow task && task.session().id().equals(sessionId)) {
            return Optional.of(new TreePath(node.getPath()));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Optional<TreePath> found = sessionPath((DefaultMutableTreeNode) node.getChildAt(i), sessionId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
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
