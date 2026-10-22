package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.SessionActivityListener;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.tasks.TaskTreeModel.ViewFilter;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
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
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final TaskTreeInputs inputs = new TaskTreeInputs();
    private final TaskTreeRefreshRequests refreshRequests = new TaskTreeRefreshRequests();
    private final Alarm searchAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final DefaultActionGroup contextActions = new DefaultActionGroup();
    private volatile String filter = "";
    private volatile ViewFilter viewFilter = ViewFilter.ALL;
    private List<TaskNode> displayedNodes = List.of();
    private String displayedFilter = "";
    private ViewFilter displayedViewFilter = ViewFilter.ALL;
    /** Main roots whose lazy containers the user has opened in this panel. EDT only. */
    private final Set<Path> requestedWorktrees = ConcurrentHashMap.newKeySet();
    private final Set<Path> requestedIssues = ConcurrentHashMap.newKeySet();
    /** Guarded by {@code this}: subscribing after dispose() must undo itself. */
    private Runnable unsubscribeShared = () -> { };
    private volatile boolean disposed;

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
        AnAction openInGitHub = new DumbAwareAction("Open in GitHub", "Open this issue in the browser",
                AllIcons.Vcs.Vendors.Github) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IssueRow issue
                        && issue.issue().url().isPresent());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IssueRow issue) {
                    issue.issue().url().ifPresent(BrowserUtil::browse);
                }
            }
        };
        AnAction implement = new DumbAwareAction("Implement…",
                "Create a writable task and worktree for this issue", AllIcons.Actions.Execute) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IssueRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IssueRow issue) {
                    TaskLauncher.implement(project, issue.mainRoot(), issue.issue());
                }
            }
        };
        AnAction triage = new DumbAwareAction("Triage…",
                "Validate and prioritize this issue against the current source", AllIcons.Actions.Show) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(SELECTED_NODE) instanceof IssueRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(SELECTED_NODE) instanceof IssueRow issue) {
                    TaskLauncher.triage(project, issue.mainRoot(), issue.issue());
                }
            }
        };
        AnAction issueGithubSettings = githubSettingsAction(node -> node instanceof IssueRow);

        contextActions.add(implement);
        contextActions.add(triage);
        contextActions.addSeparator();
        contextActions.add(openInGitHub);
        contextActions.add(issueGithubSettings);
        contextActions.addSeparator();
        contextActions.add(switchToTask);
        contextActions.add(newTask);
        contextActions.add(newTaskInMain);
        contextActions.add(githubSettingsAction(TaskTreePanel::githubSettingsNode));
        contextActions.addAll(TaskActions.taskActions());
        PopupHandler.installPopupMenu(tree, contextActions, "Drydock.TaskTree.Popup");

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
        toolbarActions.addAll(TaskActions.taskActions());
        toolbarActions.addSeparator();
        toolbarActions.add(new DumbAwareAction("Refresh", "Re-read projects and worktrees", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                requestRebuild(true);
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("Drydock.TaskTree", toolbarActions, true);
        toolbar.setTargetComponent(this);
        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar.getComponent(), BorderLayout.NORTH);
        JPanel query = new JPanel(new BorderLayout());
        query.add(search, BorderLayout.NORTH);
        query.add(quickFilters(), BorderLayout.SOUTH);
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
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                Object component = event.getPath().getLastPathComponent();
                if (!(component instanceof DefaultMutableTreeNode node)
                        || !(node.getUserObject() instanceof Section section)) {
                    return;
                }
                Path root = projectRootOf(event.getPath()).orElse(null);
                if (root == null) {
                    return;
                }
                boolean requested = switch (section.kind()) {
                    case WORKTREES -> requestedWorktrees.add(root);
                    case ISSUES -> requestedIssues.add(root);
                    default -> false;
                };
                if (requested) {
                    requestRebuild(false);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
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

    /** The actions of the tree's context menu; later tasks add theirs. */
    public DefaultActionGroup contextActions() {
        return contextActions;
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
    private static Optional<Path> projectRootOf(TreePath path) {
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
            case IssueRow ignored -> Optional.empty();
            case null, default -> Optional.empty();
        };
    }

    private static boolean githubSettingsNode(TaskNode node) {
        return node instanceof ProjectRow || node instanceof Section section && section.kind() == SectionKind.ISSUES;
    }

    /**
     * Compact, mutually exclusive filters; the text search narrows the
     * selected view further.
     *
     * <p>The selected filter is painted through the {@code
     * JButton.backgroundColor} client property, not left to the LaF's focus
     * outline: DarculaToggleButtonUI paints no selected background at all
     * (its paint path fills the plain button gradient whatever {@code
     * isSelected()} says), so the only visible "on" state is the focus
     * ring — which disappears the moment focus moves to the editor, leaving
     * no way to tell which filter is on. The client property gives a
     * persistent selected background that follows the theme.
     */
    private JPanel quickFilters() {
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        ButtonGroup group = new ButtonGroup();
        for (ViewFilter candidate : ViewFilter.values()) {
            JToggleButton button = new JToggleButton(candidate.label(), candidate == viewFilter);
            button.setMargin(JBUI.insets(2, 7));
            button.setToolTipText(switch (candidate) {
                case ALL -> "Show sessions, worktrees, and issues";
                case ACTIVE -> "Show only sessions whose agent is running";
                case INACTIVE -> "Show only sessions whose agent is not running";
                case ISSUES -> "Show only open GitHub issues";
            });
            button.addActionListener(event -> {
                if (viewFilter != candidate) {
                    viewFilter = candidate;
                    requestRebuild(false);
                }
                // ButtonGroup clears the model of the previously selected
                // button without firing its action listener; refresh the
                // painted state of every chip from its model.
                for (Component sibling : filters.getComponents()) {
                    if (sibling instanceof JToggleButton toggle) {
                        paintSelectedState(toggle);
                    }
                }
            });
            paintSelectedState(button);
            group.add(button);
            filters.add(button);
        }
        return filters;
    }

    /**
     * A theme-aware, focus-independent background for the selected chip.
     * {@code JButton.backgroundColor} is what DarculaButtonUI.getBackground
     * reads before any LaF gradient — with it set, the fill is the same in
     * the focused and the unfocused window.
     */
    private static void paintSelectedState(JToggleButton button) {
        button.putClientProperty("JButton.backgroundColor", button.isSelected()
                ? JBColor.namedColor("ToggleButton.selectedBackground", UIUtil.getListSelectionBackground(true))
                : null);
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
            List<TaskNode> nodes = TaskTreeModel.build(
                    inputs.gather(project, requestedFilter, requestedViewFilter,
                            Set.copyOf(requestedWorktrees), Set.copyOf(requestedIssues)));
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
        if (failure != null) {
            // Never silent: an empty tree with no reason given is the worst
            // outcome this panel can have.
            LOG.log(Level.WARNING, "Could not rebuild the Drydock task tree", failure);
        } else if (requestedVersion == refreshRequests.version() && !disposed && !project.isDisposed()) {
            apply(nodes, requestedFilter, requestedViewFilter);
        }
        refreshRequests.finishPass();
        if (!disposed && !project.isDisposed() && requestedVersion != refreshRequests.version()) {
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
        expandEagerAndRequested(root, new TreePath(root.getPath()), null);
        if (toSelect[0] != null) {
            tree.setSelectionPath(new TreePath(toSelect[0].getPath()));
        }
    }

    /** Sessions stay eager; worktree and issue containers expand only after the user opened them. */
    private void expandEagerAndRequested(DefaultMutableTreeNode node, TreePath path, Path projectRoot) {
        Object value = node.getUserObject();
        Path root = value instanceof ProjectRow row ? row.project().root() : projectRoot;
        boolean expand = switch (value) {
            case null -> false;
            case ProjectRow ignored -> true;
            case WorktreeGroup ignored -> true;
            case StaleWorktrees ignored -> true;
            case Section section -> switch (section.kind()) {
                case SESSIONS, ACTIVE, INACTIVE -> true;
                case WORKTREES -> root != null && requestedWorktrees.contains(root);
                case ISSUES -> root != null && requestedIssues.contains(root);
            };
            default -> false;
        };
        if (expand) {
            tree.expandPath(path);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            expandEagerAndRequested(child, path.pathByAddingChild(child), root);
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

    private static String emptyText(String appliedFilter, ViewFilter appliedViewFilter) {
        if (!appliedFilter.isBlank()) {
            return "Nothing in " + appliedViewFilter.label().toLowerCase() + " matches the search.";
        }
        return switch (appliedViewFilter) {
            case ALL -> "No projects yet. Start a task with ＋ to add this one.";
            case ACTIVE -> "No active sessions.";
            case INACTIVE -> "No inactive sessions.";
            case ISSUES -> "No open GitHub issues.";
        };
    }

    private static DefaultMutableTreeNode treeNode(TaskNode node, Optional<ManagedSessionId> selected,
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
