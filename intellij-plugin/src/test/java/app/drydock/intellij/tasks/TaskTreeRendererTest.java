package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.tasks.TaskNode.TaskRow;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTreeRendererTest {

    private static final Path DIR = Path.of("/wt/dd-jmc-fix");

    private static TaskRow row(boolean running, TaskPlacement placement) {
        return row(running, placement, true);
    }

    private static TaskRow row(boolean running, TaskPlacement placement, boolean resumable) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Fix chunk memory growth", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(DIR), Instant.EPOCH).status(SessionStatus.INACTIVE).build();
        return new TaskRow(session, Optional.empty(), running, Optional.of("fix/chunk_memory"), placement, false,
                false, resumable);
    }

    @Test
    void aSingleClickActsOnlyOnTheLinkText() {
        // A stray click while selecting the "isn't in Drydock · Add" row must
        // not register the project on the shared list.
        assertTrue(TaskTreeRenderer.clickActs(true, true, 1));
        assertFalse(TaskTreeRenderer.clickActs(true, false, 1));
        assertTrue(TaskTreeRenderer.clickActs(true, false, 2), "a double-click on the row is deliberate");
        assertFalse(TaskTreeRenderer.clickActs(true, true, 0), "a synthetic click with no count is not a click");
    }

    @Test
    void aSecondaryClickNeverActivatesTheRow() {
        assertFalse(TaskTreeRenderer.clickActs(false, true, 1),
                "right-clicking Start must open the context menu, not start a task");
        assertFalse(TaskTreeRenderer.clickActs(false, false, 2),
                "a secondary double-click must not switch tasks");
    }

    @Test
    void aRunningRowSaysWhereItIsShown() {
        assertEquals("fix/chunk_memory · here · Pi",
                TaskTreeRenderer.secondary(row(true, new TaskPlacement.Here()), "Pi", Duration.ZERO));
    }

    @Test
    void aNotShownRowOffersShowHere() {
        TaskRow row = row(true, new TaskPlacement.NotShown());

        assertEquals("fix/chunk_memory · not shown · Pi", TaskTreeRenderer.secondary(row, "Pi", Duration.ZERO));
        assertEquals(Optional.of("Show here"), TaskTreeRenderer.link(row));
    }

    @Test
    void anIdleRowFromAnotherWorktreeOffersResumeHereWhenResumable() {
        assertEquals(Optional.of("Resume here"),
                TaskTreeRenderer.link(row(false, new TaskPlacement.Idle(false), true)));
    }

    @Test
    void anIdleRowFromAnotherWorktreeOffersStartHereWhenNotResumable() {
        // "Resume" on a task with no conversation left to resume into is a
        // broken promise: the click would start a fresh one, so say that.
        assertEquals(Optional.of("Start here"),
                TaskTreeRenderer.link(row(false, new TaskPlacement.Idle(false), false)));
    }

    @Test
    void anIdleRowFromThisWorktreeKeepsItsMvpMeta() {
        TaskRow row = row(false, new TaskPlacement.Idle(true));

        assertEquals("fix/chunk_memory · this window · Pi", TaskTreeRenderer.secondary(row, "Pi", Duration.ZERO));
        assertEquals(Optional.empty(), TaskTreeRenderer.link(row));
    }

    @Test
    void anIdleRowElsewhereKeepsItsMvpMeta() {
        assertEquals("⑂ fix/chunk_memory · Pi · 5m",
                TaskTreeRenderer.secondary(row(false, new TaskPlacement.Idle(false)), "Pi", Duration.ofMinutes(5)));
    }

    @Test
    void aRowShownElsewhereOffersNoInlineLink() {
        assertEquals(Optional.empty(), TaskTreeRenderer.link(row(true, new TaskPlacement.OwnWindow())));
    }
}
