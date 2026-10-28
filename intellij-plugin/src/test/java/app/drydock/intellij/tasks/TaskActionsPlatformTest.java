package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.SessionCommands;
import app.drydock.intellij.tasks.TaskNode.TaskRow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The tree's real Resume actions, headless: their {@code update()} is driven
 * with a real {@link AnActionEvent} on a real selected row, so the label the
 * user would see comes from the same code the tree runs — an honest
 * Resume/Start (A6, A10, A11), and a busy session that greys the action
 * rather than hiding it (A11).
 *
 * <p>No catalog writes and no {@code DrydockPluginService.getInstance()}: the
 * actions' update path reads only the memory-only registry and transfer
 * table through {@link SessionCommands#stateOf}, and the busy span is the
 * registry's own {@code markStarting} flag, removed in {@code tearDown}. No
 * agent is ever launched. This is a headless presentation check, not a
 * visual one: nothing here is rendered on screen.</p>
 */
public class TaskActionsPlatformTest extends BasePlatformTestCase {

    private AgentExecutionRegistry registry;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
    }

    public void testResumeSaysStartForATaskWithNothingToResume() {
        AnAction resume = action("Resume");
        AnActionEvent event = event(row(false));
        resume.update(event);

        assertEquals("Start", event.getPresentation().getText());
        assertEquals(AllIcons.Actions.Execute, event.getPresentation().getIcon());
        assertTrue(event.getPresentation().isVisible());
        assertTrue(event.getPresentation().isEnabled());
    }

    public void testResumeSaysResumeForATaskWithAConversationToResumeInto() {
        AnAction resume = action("Resume");
        AnActionEvent event = event(row(true));
        resume.update(event);

        assertEquals("Resume", event.getPresentation().getText());
        assertEquals(AllIcons.Actions.Resume, event.getPresentation().getIcon());
        assertTrue(event.getPresentation().isVisible());
        assertTrue(event.getPresentation().isEnabled());
    }

    public void testTheOwnWindowResumeCarriesTheSameHonestLabel() {
        AnAction ownWindow = action("Resume in Its Own Window");

        AnActionEvent start = event(row(false));
        ownWindow.update(start);
        assertEquals("Start in Its Own Window", start.getPresentation().getText());
        assertEquals(AllIcons.Actions.MoveTo2, start.getPresentation().getIcon());
        assertTrue(start.getPresentation().isVisible());
        assertTrue(start.getPresentation().isEnabled());

        AnActionEvent resume = event(row(true));
        ownWindow.update(resume);
        assertEquals("Resume in Its Own Window", resume.getPresentation().getText());
    }

    public void testAStartInFlightGreysTheLifecycleActionsInsteadOfHidingThem() {
        TaskRow row = row(true);
        registry.markStarting(row.session().id(), true);
        try {
            AnAction resume = action("Resume");
            AnActionEvent event = event(row);
            resume.update(event);

            assertEquals("Resume", event.getPresentation().getText());
            assertTrue(event.getPresentation().isVisible());
            assertFalse(event.getPresentation().isEnabled());
        } finally {
            registry.markStarting(row.session().id(), false);
        }
    }

    public void testAnUnsupportedAgentGreysBothLaunchActionsLikeTheTabAndStripDo() {
        // Whatever the conversation state, a session whose agent this
        // build cannot run has nothing to start and nothing to resume
        // into; the tree must grey both launch actions, not just the tab
        // header and strip (F6). Both keep their honest labels.
        AnAction resume = action("Resume");
        AnAction ownWindow = action("Resume in Its Own Window");

        TaskRow withConversation = row(SessionStatus.UNSUPPORTED_AGENT, true);
        TaskRow withoutConversation = row(SessionStatus.UNSUPPORTED_AGENT, false);

        for (TaskRow row : List.of(withConversation, withoutConversation)) {
            AnActionEvent local = event(row);
            resume.update(local);
            assertTrue(local.getPresentation().isVisible());
            assertFalse(local.getPresentation().isEnabled());
            assertEquals(row.resumable() ? "Resume" : "Start", local.getPresentation().getText());

            AnActionEvent own = event(row);
            ownWindow.update(own);
            assertTrue(own.getPresentation().isVisible());
            assertFalse(own.getPresentation().isEnabled());
            assertEquals(row.resumable() ? "Resume in Its Own Window" : "Start in Its Own Window",
                    own.getPresentation().getText());
        }
    }

    public void testABusySupportedSessionStaysGreyedAndASupportedOneStaysEnabled() {
        // The shared policy must not loosen the busy guard: a supported
        // row greys under an in-flight start, and stays enabled when idle.
        TaskRow row = row(true);
        registry.markStarting(row.session().id(), true);
        try {
            AnActionEvent busy = event(row);
            action("Resume").update(busy);
            assertTrue(busy.getPresentation().isVisible());
            assertFalse(busy.getPresentation().isEnabled());

            AnActionEvent busyOwn = event(row);
            action("Resume in Its Own Window").update(busyOwn);
            assertTrue(busyOwn.getPresentation().isVisible());
            assertFalse(busyOwn.getPresentation().isEnabled());
        } finally {
            registry.markStarting(row.session().id(), false);
        }

        AnActionEvent idle = event(row);
        action("Resume").update(idle);
        assertTrue(idle.getPresentation().isEnabled());
        AnActionEvent idleOwn = event(row);
        action("Resume in Its Own Window").update(idleOwn);
        assertTrue(idleOwn.getPresentation().isEnabled());
    }

    /** The action from {@link TaskActions#taskActions()} whose template text is {@code templateText}. */
    private static AnAction action(String templateText) {
        return TaskActions.taskActions().values().stream()
                .filter(action -> templateText.equals(action.getTemplatePresentation().getText()))
                .findFirst().orElseThrow(() -> new AssertionError("no action '" + templateText + "'"));
    }

    /** The event a popup menu would deliver for {@code row} in this test's project. */
    private AnActionEvent event(TaskRow row) {
        return AnActionEvent.createEvent(SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(TaskTreePanel.SELECTED_NODE, row)
                .build(), new Presentation(), ActionPlaces.POPUP, ActionUiKind.POPUP, null);
    }

    /**
     * A stopped task row from another worktree — where both lifecycle actions
     * apply — with the model's warmed answer as {@code resumable}.
     */
    private static TaskRow row(boolean resumable) {
        return row(SessionStatus.INACTIVE, resumable);
    }

    /** The same stopped row with the session's {@code status} and warmed resumable answer. */
    private static TaskRow row(SessionStatus status, boolean resumable) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                RepositoryId.newId(), "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/task")), Instant.EPOCH)
                .status(status).build();
        return new TaskRow(session, Optional.empty(), false, Optional.empty(),
                new TaskPlacement.Idle(false), false, false, resumable);
    }
}
