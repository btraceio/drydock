package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.SessionActivityListener;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;

import com.intellij.icons.AllIcons;
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
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.awt.BorderLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** The main checkout of the project the selected row belongs to. */
    public static final DataKey<Path> SELECTED_PROJECT_ROOT = DataKey.create("drydock.task.projectRoot");

    private static final Logger LOG = System.getLogger(TaskTreePanel.class.getName());

    private static final int SEARCH_DEBOUNCE_MILLIS = 150;

    private final Project project;
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final SearchTextField search = new SearchTextField(false);
    private final TaskTreeInputs inputs = new TaskTreeInputs();
    private final AtomicBoolean rebuildPending = new AtomicBoolean();
    private final Alarm searchAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final DefaultActionGroup contextActions = new DefaultActionGroup();
    private volatile String filter = "";
    private Runnable unsubscribeShared = () -> { };

    public TaskTreePanel(Project project) {
        super(true, true);
        this.project = project;

        DefaultActionGroup toolbarActions = new DefaultActionGroup();
        AnAction newTask = ActionManager.getInstance().getAction("Drydock.NewTask");
        if (newTask != null) {
            toolbarActions.add(newTask);
        }
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
        top.add(search, BorderLayout.SOUTH);
        setToolbar(top);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new TaskTreeRenderer());
        setContent(ScrollPaneFactory.createScrollPane(tree));

        contextActions.add(new DumbAwareAction("Switch to Task", "Raise the task's window and select its agent",
                AllIcons.Actions.Forward) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabledAndVisible(e.getData(SELECTED_NODE) instanceof TaskRow);
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
        });
        AnAction newTaskAction = ActionManager.getInstance().getAction("Drydock.NewTask");
        if (newTaskAction != null) {
            contextActions.add(newTaskAction);
        }
        contextActions.add(new DumbAwareAction("New Task in This Worktree…",
                "Start another agent in this task's worktree", AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                e.getPresentation().setEnabledAndVisible(root != null
                        && worktreeOf(e.getData(SELECTED_NODE), root).isPresent());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Path root = e.getData(SELECTED_PROJECT_ROOT);
                worktreeOf(e.getData(SELECTED_NODE), root).ifPresent(fixed ->
                        TaskLauncher.open(project, Optional.ofNullable(root), Optional.of(fixed)));
            }
        });
        PopupHandler.installPopupMenu(tree, contextActions, "Drydock.TaskTree.Popup");
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                Object node = path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (node instanceof AddCurrentProject add && event.getClickCount() == 1) {
                    register(add);
                } else if (node instanceof IdleWorktree idle && event.getClickCount() == 1) {
                    TaskLauncher.open(project, projectRootOf(path),
                            Optional.of(new NewTaskPlan.FixedWorktree(idle.worktree(), idle.branch())));
                } else if (node instanceof TaskRow task && event.getClickCount() == 2) {
                    TaskNavigator.switchTo(task.session());
                }
            }
        });
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER && selected().orElse(null) instanceof TaskRow task) {
                    TaskNavigator.switchTo(task.session());
                }
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
        DrydockPluginService.getInstance().initialization().thenRun(() -> {
            unsubscribeShared = DrydockPluginService.getInstance().sharedProjects()
                    .addListener(() -> requestRebuild(true));
            requestRebuild(true);
        });
    }

    /** The actions of the tree's context menu; later tasks add theirs. */
    public DefaultActionGroup contextActions() {
        return contextActions;
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
        selected().ifPresent(node -> sink.set(SELECTED_NODE, node));
        projectRootOf(tree.getSelectionPath()).ifPresent(root -> sink.set(SELECTED_PROJECT_ROOT, root));
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

    private Optional<TaskNode> selected() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
            return Optional.empty();
        }
        return node.getUserObject() instanceof TaskNode taskNode ? Optional.of(taskNode) : Optional.empty();
    }

    /** Schedules one rebuild; {@code worktreesChanged} re-lists worktrees instead of using the cache. */
    public void requestRebuild(boolean worktreesChanged) {
        if (worktreesChanged) {
            inputs.invalidateWorktrees();
        }
        if (!rebuildPending.compareAndSet(false, true)) {
            return;
        }
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.initialization().thenRunAsync(() -> {
            rebuildPending.set(false);
            if (project.isDisposed()) {
                return;
            }
            List<TaskNode> nodes = TaskTreeModel.build(inputs.gather(project, filter));
            ApplicationManager.getApplication().invokeLater(() -> apply(nodes), ModalityState.any(),
                    project.getDisposed());
        }, plugin.executor()).exceptionally(failure -> {
            // Never silent: an empty tree with no reason given is the worst
            // outcome this panel can have.
            LOG.log(Level.WARNING, "Could not rebuild the Drydock task tree", failure);
            return null;
        });
    }

    /** EDT: swaps in the new rows, keeping the selected task selected. */
    private void apply(List<TaskNode> nodes) {
        Optional<ManagedSessionId> selectedSession = selected()
                .filter(TaskRow.class::isInstance)
                .map(node -> ((TaskRow) node).session().id());
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultMutableTreeNode[] toSelect = new DefaultMutableTreeNode[1];
        for (TaskNode node : nodes) {
            root.add(treeNode(node, selectedSession, toSelect));
        }
        ((DefaultTreeModel) tree.getModel()).setRoot(root);
        TreeUtil.expandAll(tree);
        if (toSelect[0] != null) {
            tree.setSelectionPath(new TreePath(toSelect[0].getPath()));
        }
    }

    private static DefaultMutableTreeNode treeNode(TaskNode node, Optional<ManagedSessionId> selected,
                                                   DefaultMutableTreeNode[] toSelect) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        switch (node) {
            case ProjectRow project -> project.children().forEach(
                    child -> treeNode.add(treeNode(child, selected, toSelect)));
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
        unsubscribeShared.run();
        inputs.close();
    }
}
