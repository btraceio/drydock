package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.agent.SessionDoneBulk.Done;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bulk cleanup's selection seam: of a project's sessions and its
 * worktree listing, exactly the sessions sitting on a done worktree are
 * selected -- merged, prunable, or detached, never the main checkout, a
 * locked worktree, or an unmerged branch -- with the running answer taken
 * from the injected predicate. No plugin service, no registry.
 */
class SessionDoneBulkTest {

    private static final Path MAIN = Path.of("/work/profiling-backend");
    private static final Path FIX = Path.of("/work/profiling-backend-fix");
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");

    private static ManagedAgentSession session(Path workingDirectory) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(workingDirectory), NOW)
                .build();
    }

    private static Worktree worktree(Path path, String branch, boolean mainCheckout, boolean detached,
                                     boolean prunable, boolean locked, boolean merged) {
        return new Worktree(path, Optional.ofNullable(branch), mainCheckout, detached, prunable, locked,
                Optional.empty(), merged);
    }

    private static List<Worktree> listing() {
        return List.of(
                worktree(MAIN, "main", true, false, false, false, false),
                worktree(FIX, "fix-allocation-profile", false, false, false, false, true));
    }

    @Test
    void aSessionOnAMergedWorktreeIsDoneWhicheverWayItsAgentRuns() {
        ManagedAgentSession idle = session(FIX);
        ManagedAgentSession running = session(FIX);
        List<Done> selected = SessionDoneBulk.select(List.of(idle, running), listing(),
                session -> session == running);
        // Both sessions of the shared worktree are done; each carries its own running answer.
        assertEquals(2, selected.size());
        assertEquals(List.of(false, true), selected.stream().map(Done::running).toList());
    }

    @Test
    void aSessionOnAnUnmergedBranchIsNotDone() {
        List<Worktree> unmerged = List.of(
                worktree(MAIN, "main", true, false, false, false, false),
                worktree(FIX, "fix-allocation-profile", false, false, false, false, false));
        assertTrue(SessionDoneBulk.select(List.of(session(FIX)), unmerged, ignored -> false).isEmpty());
    }

    @Test
    void theMainCheckoutAndALockedWorktreeAreNeverDone() {
        assertTrue(SessionDoneBulk.select(List.of(session(MAIN)), listing(), ignored -> false).isEmpty());
        List<Worktree> locked = List.of(
                worktree(FIX, "fix-allocation-profile", false, false, false, true, true));
        assertTrue(SessionDoneBulk.select(List.of(session(FIX)), locked, ignored -> false).isEmpty());
    }

    @Test
    void aPrunableOrDetachedWorktreeIsDone() {
        List<Worktree> prunable = List.of(
                worktree(FIX, "fix-allocation-profile", false, false, true, false, false));
        assertEquals(1, SessionDoneBulk.select(List.of(session(FIX)), prunable, ignored -> false).size());
        List<Worktree> detached = List.of(
                worktree(FIX, null, false, true, false, false, false));
        assertEquals(1, SessionDoneBulk.select(List.of(session(FIX)), detached, ignored -> false).size());
    }

    @Test
    void aSubdirectorySessionResolvesToItsDeepestWorktree() {
        Path subdirectory = FIX.resolve("profiler/src");
        List<Done> selected = SessionDoneBulk.select(List.of(session(subdirectory)), listing(),
                ignored -> false);
        assertEquals(1, selected.size());
        assertEquals(FIX, selected.get(0).worktree().path());
    }

    @Test
    void aNonCanonicallyWrittenWorktreePathStillMatchesByItsCanonicalRoot() {
        // Redundant segments canonicalize to FIX, but the selected Done
        // carries the raw listed entry, not the canonicalized path.
        Path nonCanonical = FIX.resolve("a").resolve("..");
        List<Worktree> listed = List.of(
                worktree(MAIN, "main", true, false, false, false, false),
                worktree(nonCanonical, "fix-allocation-profile", false, false, false, false, true));
        List<Done> selected = SessionDoneBulk.select(List.of(session(FIX)), listed, ignored -> false);
        assertEquals(1, selected.size());
        assertEquals(nonCanonical, selected.get(0).worktree().path());
    }

    @Test
    void aSessionUnderNoListedWorktreeIsNeverSelected() {
        assertTrue(SessionDoneBulk.select(List.of(session(Path.of("/work/elsewhere"))), listing(),
                ignored -> false).isEmpty());
    }
}
