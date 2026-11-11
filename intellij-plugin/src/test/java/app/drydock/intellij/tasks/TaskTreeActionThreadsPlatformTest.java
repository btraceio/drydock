package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.delayedtasks.DelayedTaskActions;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
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
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * The task tree's actions and its context-menu group update on a background
 * thread: each declares {@link ActionUpdateThread#BGT}, and its update — or
 * the group's child list — runs on a pooled thread from the event's data
 * alone. A Swing read or an EDT assertion left in those paths would throw or
 * deadlock here (the test thread is the EDT, blocked on the pooled one).
 *
 * <p>No catalog writes and no {@code DrydockPluginService} reads: the task
 * actions read only the memory-only execution registry and transfer table,
 * the Jira predicate is a recording stub, and no Jira update here reaches a
 * project root. No agent is launched; nothing is rendered.</p>
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

    public void testTheContextMenuIsBuiltOffTheEdtFromTheEventData() throws Exception {
        Map<TaskMenu.Item, AnAction> actions = TaskActions.taskActions();
        AnAction[] delayed = { stub("Delayed") };
        AnAction newDelayed = stub("New Delayed");
        AnAction refreshTasks = stub("Refresh Tasks");
        List<Path> jiraQueries = new ArrayList<>();
        TaskTreePanel.TreeMenu menu = new TaskTreePanel.TreeMenu(actions, delayed, newDelayed, refreshTasks,
                root -> {
            jiraQueries.add(root);
            return false;
        });
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
}
