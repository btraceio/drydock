package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.delayedtasks.DelayedTaskActions;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskNode.TasksSection;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskState.TaskView;

import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import org.jetbrains.annotations.NotNull;

import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The task tree's actions and its context-menu group update on a background
 * thread: each declares {@link ActionUpdateThread#BGT}, and its update — or
 * the group's child list — runs on a pooled thread from the event's data
 * alone. A Swing read or an EDT assertion left in those paths would throw or
 * deadlock here (the test thread is the EDT, blocked on the pooled one).
 *
 * <p>Most of the suite reads no catalog and no {@code DrydockPluginService}:
 * the task actions read only the memory-only execution registry and transfer
 * table, the Jira predicate is a recording stub, and no Jira update here
 * reaches a project root. The jump-action test is the exception: the jump
 * actions are per-panel instances, so it boots the real panel over the
 * plugin's services (sandboxed catalog, no second IDE boot) and seeds one
 * session row. No agent is launched; nothing is rendered.</p>
 */
public class TaskTreeActionThreadsPlatformTest extends BasePlatformTestCase {

    private static final Path ROOT = Path.of("/work/profiling-backend");

    public void testEveryTaskActionUpdatesOffTheEdt() throws Exception {
        Set<AnAction> actions = new LinkedHashSet<>(TaskActions.taskActions().values());
        TaskRow row = row();
        for (AnAction action : actions) {
            assertEquals(action.getTemplatePresentation().getText(),
                    ActionUpdateThread.BGT, action.getActionUpdateThread());
            AnActionEvent event = event(SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, getProject())
                    .add(TaskTreePanel.SELECTED_NODE, row)
                    .add(TaskTreePanel.SELECTED_NODES, List.of(row)));
            offEdt(() -> {
                action.update(event);
                return null;
            });
        }
    }

    public void testEveryJiraTreeActionUpdatesOffTheEdt() throws Exception {
        List<AnAction> actions = List.of(JiraTreeActions.openInJira(),
                JiraTreeActions.settings(getProject(), root -> { }),
                JiraTreeActions.addGroup(getProject(), root -> { }),
                JiraTreeActions.editGroup(getProject(), root -> { }),
                JiraTreeActions.deleteGroup(getProject(), root -> { }));
        for (AnAction action : actions) {
            assertEquals(action.getTemplatePresentation().getText(),
                    ActionUpdateThread.BGT, action.getActionUpdateThread());
            AnActionEvent event = event(SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, getProject())
                    .add(TaskTreePanel.SELECTED_NODE, row()));
            offEdt(() -> {
                action.update(event);
                return null;
            });
            assertFalse(event.getPresentation().isVisible());
        }
    }

    public void testTheDelayedTaskActionsUpdateOffTheEdt() {
        for (DelayedTaskActions.Entry entry : DelayedTaskActions.menu()) {
            assertEquals(entry.id(), ActionUpdateThread.BGT, entry.local().getActionUpdateThread());
        }
    }

    /**
     * The jump actions update off the EDT from the event's data keys alone.
     * They are per-panel instances, so this boots the real panel over the
     * plugin's services and seeds one session row. The tree's live selection
     * is left on the session row while the event names a delayed-task row:
     * the two disagree, so an update() that read the tree selection instead
     * of the data keys would resolve a different lead and fail the session
     * jump's enabled answer.
     */
    public void testTheJumpActionsUpdateOffTheEdtFromTheEventDataKeys() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        try { plugin.initialization().get(60, TimeUnit.SECONDS); }
        catch (Exception e) { fail("Drydock initialization did not complete within 60 s: " + e); }
        Path repoRoot = Files.createTempDirectory("drydock-jump-threads-");
        SharedProject registered = plugin.sharedProjects().register(repoRoot,
                "jump-threads-" + System.nanoTime()).get(30, TimeUnit.SECONDS);
        ManagedSessionId sessionId = ManagedSessionId.newId();
        Instant anHourAgo = Instant.now().minusSeconds(3_600);
        IsolatedCatalog.seed(new ManagedAgentSession(sessionId, RepositoryId.newId(),
                "session the jump actions name", AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(repoRoot, Optional.empty(), false), SessionStatus.INACTIVE,
                anHourAgo, anHourAgo, Optional.empty(), PrLink.none(), false, Optional.empty(), false));
        TaskTreePanel panel = new TaskTreePanel(getProject());
        JTree tree = (JTree) ((JScrollPane) panel.getContent()).getViewport().getView();
        // Headless harness: nothing paints, so the tree keeps no UI (see
        // DelayedTaskTreePlatformTest.setUp for the production path this avoids).
        tree.setUI(null);
        try {
            awaitTree(() -> sessionRow(tree, sessionId) != null, "the seeded session's row");
            tree.setSelectionPath(sessionRow(tree, sessionId));

            AnAction showSession = panel.showLinkedAction(TaskTreePanel.ShowKind.SESSION,
                    "Show Session", "Reveal the session row this pull request belongs to");
            AnAction showTask = panel.showLinkedAction(TaskTreePanel.ShowKind.TASK,
                    "Show Task", "Reveal the task row this row is linked to");
            AnAction showPullRequest = panel.showLinkedAction(TaskTreePanel.ShowKind.PULL_REQUEST,
                    "Show Pull Request", "Reveal the pull request this row works");
            for (AnAction action : List.of(showSession, showTask, showPullRequest)) {
                assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread());
            }

            // A delayed-task row naming the displayed session, carried by the
            // event's data key alone: the session jump resolves from it on a
            // background thread while the EDT stays blocked. The selected
            // session row is a different lead -- a Swing read would answer
            // from it, and no session row links to a session.
            DelayedTaskRow task = delayedTaskRow(sessionId);
            AnActionEvent named = event(SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, getProject())
                    .add(DelayedTaskActions.SELECTED_TASK, task));
            offEdt(() -> { showSession.update(named); return null; });
            assertTrue("the session jump resolves from the event's SELECTED_TASK key, not the tree selection",
                    named.getPresentation().isEnabled());
            assertTrue(named.getPresentation().isVisible());
            offEdt(() -> { showPullRequest.update(named); return null; });
            assertFalse("no pull request is shown for this task's repository",
                    named.getPresentation().isEnabled());
            assertFalse(named.getPresentation().isVisible());

            // No key names a lead: every jump disables even though the tree
            // still shows a selected session row -- update() must not consult it.
            for (AnAction action : List.of(showSession, showTask, showPullRequest)) {
                AnActionEvent bare = event(SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, getProject()));
                offEdt(() -> { action.update(bare); return null; });
                assertFalse("no lead named: the jump disables without reading the selection",
                        bare.getPresentation().isEnabled());
                assertFalse(bare.getPresentation().isVisible());
            }
        } finally {
            try { Disposer.dispose(panel); } catch (RuntimeException e) { }
            try { IsolatedCatalog.remove(sessionId); } catch (RuntimeException e) { }
            try { plugin.sharedProjects().remove(registered.id()).get(30, TimeUnit.SECONDS); }
            catch (Exception e) { }
            delete(repoRoot);
        }
    }

    public void testTheContextMenuIsBuiltOffTheEdtFromTheEventData() throws Exception {
        Map<TaskMenu.Item, AnAction> actions = TaskActions.taskActions();
        AnAction[] delayed = { stub("Delayed") };
        AnAction newDelayed = stub("New Delayed");
        AnAction refreshTasks = stub("Refresh Tasks");
        AnAction workspaceBox = stub("Workspace Box…");
        List<Path> jiraQueries = new ArrayList<>();
        TaskTreePanel.TreeMenu menu = new TaskTreePanel.TreeMenu(actions, workspaceBox, delayed, newDelayed,
                refreshTasks,
                root -> {
            jiraQueries.add(root);
            return false;
        }, () -> List.<TaskNode>of(), () -> TaskTreePanel.TaskLayer.EMPTY, () -> null);
        assertEquals(ActionUpdateThread.BGT, menu.getActionUpdateThread());

        // A delayed-task row carries its own layer's actions.
        AnActionEvent delayedRow = event(SimpleDataContext.builder()
                .add(DelayedTaskActions.SELECTED_TASK, delayedRow()));
        assertSame(delayed, offEdt(() -> menu.getChildren(delayedRow)));

        // The Tasks section offers New Task, then the store's fresh re-read.
        AnActionEvent section = event(SimpleDataContext.builder()
                .add(TaskTreePanel.SELECTED_TASKS_SECTION, new TasksSection("Tasks", List.of())));
        assertEquals(List.of(newDelayed, refreshTasks), List.of(offEdt(() -> menu.getChildren(section))));

        // A session row gets TaskMenu's layout for the selection the event names,
        // and the Jira gate is asked about the event's project root.
        TaskRow row = row();
        AnActionEvent task = event(SimpleDataContext.builder()
                .add(TaskTreePanel.SELECTED_NODE, row)
                .add(TaskTreePanel.SELECTED_NODES, List.of(row))
                .add(TaskTreePanel.SELECTED_PROJECT_ROOT, ROOT));
        List<Object> built = shape(offEdt(() -> menu.getChildren(task)));
        List<Object> expected = shape(offEdt(() ->
                TaskMenu.group(row, List.of(row), actions, false).getChildActionsOrStubs()));
        assertFalse(built.isEmpty());
        assertEquals(expected, built);
        assertEquals(List.of(ROOT), jiraQueries);

        // No project root: the Jira gate is not consulted at all.
        jiraQueries.clear();
        AnActionEvent rootless = event(SimpleDataContext.builder()
                .add(TaskTreePanel.SELECTED_NODE, row));
        offEdt(() -> menu.getChildren(rootless));
        assertEquals(List.of(), jiraQueries);

        assertEquals(0, menu.getChildren(null).length);
    }

    /** Runs {@code body} on a pooled thread and returns its answer; the EDT stays blocked meanwhile. */
    private static <T> T offEdt(Callable<T> body) throws Exception {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> {
            assertFalse(ApplicationManager.getApplication().isDispatchThread());
            return body.call();
        }).get(30, TimeUnit.SECONDS);
    }

    /** The children as a comparable list: each action instance, every separator as one marker. */
    private static List<Object> shape(AnAction[] children) {
        List<Object> shape = new ArrayList<>();
        for (AnAction child : children) {
            shape.add(child instanceof Separator ? "-" : child);
        }
        return shape;
    }

    private static AnActionEvent event(SimpleDataContext.Builder data) {
        return AnActionEvent.createEvent(data.build(), new Presentation(), ActionPlaces.POPUP, ActionUiKind.POPUP, null);
    }

    private static AnAction stub(String text) {
        return new DumbAwareAction(text) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
            }
        };
    }

    /** A stopped task row from another worktree. */
    private static TaskRow row() {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                RepositoryId.newId(), "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/task")), Instant.EPOCH).build();
        return new TaskRow(session, Optional.empty(), false, Optional.empty(),
                new TaskPlacement.Idle(false), false, false, true);
    }

    private static DelayedTaskRow delayedRow() {
        return new DelayedTaskRow(new TaskView(TaskId.of("fix-alloc-k3f9"), TaskState.Presentation.values()[0],
                List.of(), List.of()), "Fix allocation", RepositoryId.newId(), Optional.empty(), false, List.of());
    }

    /** A delayed-task row whose stored sessions name the given session. */
    private static DelayedTaskRow delayedTaskRow(ManagedSessionId sessionId) {
        return new DelayedTaskRow(new TaskView(TaskId.of("fix-alloc-k3f9"), TaskState.Presentation.values()[0],
                List.of(), List.of()), "Fix allocation", RepositoryId.newId(), Optional.empty(), false,
                List.of(sessionId.toString()));
    }

    private static TreePath sessionRow(JTree tree, ManagedSessionId sessionId) {
        return find(tree, o -> o instanceof TaskRow r && r.session().id().equals(sessionId));
    }

    /** First depth-first path whose node carries a matching row, or null. */
    private static TreePath find(JTree tree, Predicate<Object> match) {
        Object top = tree.getModel().getRoot();
        return top instanceof DefaultMutableTreeNode node ? find(node, new TreePath(node), match) : null;
    }

    private static TreePath find(DefaultMutableTreeNode node, TreePath path, Predicate<Object> match) {
        if (match.test(node.getUserObject())) return path;
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            TreePath found = find(child, path.pathByAddingChild(child), match);
            if (found != null) return found;
        }
        return null;
    }

    /** Pumps the EDT until {@code state} holds or the deadline passes. */
    private static void awaitTree(BooleanSupplier state, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!state.getAsBoolean()) {
            assertTrue(what + " did not arrive within 20 s", System.nanoTime() < deadline);
            for (int i = 0; i < 3; i++) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                TimeoutUtil.sleep(20);
            }
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(
                    p -> { try { Files.deleteIfExists(p); } catch (IOException e) { } });
        } catch (IOException ignored) { }
    }
}
