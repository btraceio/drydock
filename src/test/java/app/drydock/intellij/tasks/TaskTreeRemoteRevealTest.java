package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.review.ReviewTabOpener;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.EmptyAction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The remote session row's reveal-only "Open in Review Workbench" entry (AC-43/AC-45 UI half). */
class TaskTreeRemoteRevealTest {

    private static TaskNode.TaskRow row(boolean remote, boolean running) {
        ManagedAgentSession local = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Fix login", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/task")), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build();
        ManagedAgentSession session = remote
                ? local.toBuilder().workspaceBox(Optional.of(
                        new WorkspaceBoxBinding("drydock-box", "/home/user/repo"))).build()
                : local;
        return new TaskNode.TaskRow(session, Optional.empty(), running, Optional.empty(),
                running ? new TaskPlacement.ThisWindow() : new TaskPlacement.Idle(true), false, false);
    }

    @Test
    void aRemoteRowGetsTheEntryAppendedAfterItsExistingMenu() {
        AnAction existing = new EmptyAction();
        AnAction[] menu = TaskActions.withRemoteReveal(row(true, true), new AnAction[] {existing});

        assertEquals(3, menu.length);
        assertSame(existing, menu[0]);
        assertEquals("Open in Review Workbench", menu[2].getTemplatePresentation().getText());
    }

    @Test
    void aLocalRowAndANonRowKeepTheirMenuUnchanged() {
        AnAction[] menu = {new EmptyAction()};

        assertSame(menu, TaskActions.withRemoteReveal(row(false, true), menu));
        assertSame(menu, TaskActions.withRemoteReveal(null, menu));
        assertFalse(TaskActions.remoteSession(row(false, false)));
    }

    @Test
    void revealDelegatesOnceWithTheSessionIdentityAndASafeLabel() {
        TaskNode.TaskRow row = row(true, true);
        List<ReviewTabOpener.RemoteContext> opened = new ArrayList<>();

        TaskActions.revealRemote(row, opened::add);

        assertEquals(1, opened.size());
        assertEquals(row.session().id(), opened.get(0).session());
        assertEquals("Remote workspace drydock-box \u00b7 local changes", opened.get(0).label());
        assertFalse(opened.get(0).label().contains("/home/user"), "no path in the label");
    }

    @Test
    void aDetachedRemoteRowRevealsWithoutAnyConsole() {
        List<ReviewTabOpener.RemoteContext> opened = new ArrayList<>();

        TaskActions.revealRemote(row(true, false), opened::add);

        assertEquals(1, opened.size(), "an idle/detached remote session reveals; nothing needs an attached console");
    }

    @Test
    void aLocalRowNeverReveals() {
        List<ReviewTabOpener.RemoteContext> opened = new ArrayList<>();

        TaskActions.revealRemote(row(false, true), opened::add);

        assertTrue(opened.isEmpty());
    }

    @Test
    void theTreePanelDoesNotGrowBeyondItsBaseline() throws IOException {
        Path panel = Path.of("src/platform/java/app/drydock/intellij/tasks/TaskTreePanel.java");

        // The panel is a reviewability budget (per-source cap discipline): every
        // feature that grows it bumps this baseline deliberately, never silently.
        assertTrue(Files.readAllLines(panel).size() <= 2_891, "line baseline 2,891");
        assertTrue(Files.size(panel) <= 141_233, "byte baseline 141,233");
    }
}
