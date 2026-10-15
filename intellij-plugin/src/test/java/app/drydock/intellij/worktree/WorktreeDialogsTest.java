package app.drydock.intellij.worktree;

import app.drydock.git.BranchNameRules;
import app.drydock.git.GitCommandFailedException;
import app.drydock.git.WorktreeLockedException;
import app.drydock.git.WorktreeNotCleanException;
import app.drydock.process.ProcessTimeoutException;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T14 pure seams, pinned without opening a dialog (and without the
 * IDE): the live branch-validation wiring (AC23 — a refused name shows
 * exactly the core's human sentence and never produces a create request,
 * which is how "no git is invoked" holds: {@code createRequest} takes no
 * service, so there is nothing else it could call), the
 * default-directory wiring (AC23 — WorktreeNaming's derivation, and the
 * stop-deriving-after-edit rule), and the error mapping (AC26 — each git
 * failure mode reaches the user as text carrying its specifics, the
 * stderr excerpt above all).
 */
class WorktreeDialogsTest {

    private static final Path HOME = Path.of("/home/tester");
    private static final List<String> REMOTES = List.of("origin", "upstream");

    // ---- branch-validation wiring (AC23) ----------------------------------------

    @Test
    void invalidNameIsRefusedWithTheHumanSentence() {
        Optional<BranchNameRules.Refusal> refusal = WorktreeDialogs.refusalFor("origin/main", REMOTES);
        assertTrue(refusal.isPresent());
        assertEquals(BranchNameRules.Clause.SHADOWS_REMOTE, refusal.get().clause());
        assertEquals("A branch by that name would shadow the remote 'origin'.",
                WorktreeDialogs.hintText(refusal));
    }

    @Test
    void everyRefusalHintIsItsHumanSentence() {
        for (BranchNameRules.Clause clause : BranchNameRules.Clause.values()) {
            BranchNameRules.Refusal refusal = new BranchNameRules.Refusal(clause, "origin");
            assertEquals(BranchNameRules.humanSentence(refusal), WorktreeDialogs.hintText(Optional.of(refusal)),
                    "hint for " + clause);
        }
    }

    @Test
    void validNameHasNoRefusalAndNoHint() {
        assertTrue(WorktreeDialogs.refusalFor("feat/sidebar-resize", REMOTES).isEmpty());
        assertEquals("", WorktreeDialogs.hintText(WorktreeDialogs.refusalFor("feat/sidebar-resize", REMOTES)));
    }

    @Test
    void blankNameIsRefused() {
        assertEquals(BranchNameRules.Clause.BLANK, WorktreeDialogs.refusalFor("   ", List.of()).orElseThrow().clause());
    }

    @Test
    void anInvalidNameNeverProducesACreateRequest_noGitInvoked() {
        // The create request is the only thing that reaches the git service;
        // a refused or blank name yields none at all.
        assertTrue(WorktreeDialogs.createRequest("origin/main", "/tmp/wt", REMOTES).isEmpty());
        assertTrue(WorktreeDialogs.createRequest("", "/tmp/wt", REMOTES).isEmpty());
        assertTrue(WorktreeDialogs.createRequest("feat/x", "   ", REMOTES).isEmpty());
    }

    @Test
    void aValidNameProducesTheTrimmedBranchAndDirectory() {
        Optional<WorktreeDialogs.CreateRequest> request =
                WorktreeDialogs.createRequest("  feat/sidebar  ", "/tmp/drydock-wt", REMOTES);
        WorktreeDialogs.CreateRequest create = request.orElseThrow();
        assertEquals("feat/sidebar", create.branch());
        assertEquals(Path.of("/tmp/drydock-wt"), create.directory());
    }

    // ---- default-directory wiring (AC23) -----------------------------------------

    @Test
    void directoryDefaultsToTheWorktreeNamingDerivation() {
        assertEquals(
                Path.of("/home/tester/dev/wt/drydock-sidebar-resize").toString(),
                WorktreeDialogs.directoryFor("feat/Sidebar Resize!", "", false, HOME, "Drydock"));
    }

    @Test
    void blankBranchDefaultsToTheBareSlug() {
        assertEquals(
                Path.of("/home/tester/dev/wt/drydock-worktree").toString(),
                WorktreeDialogs.directoryFor("", "", false, HOME, "Drydock"));
    }

    @Test
    void aManualEditStopsTheAutoDerivation() {
        assertEquals("/tmp/my-wt",
                WorktreeDialogs.directoryFor("feat/other", "/tmp/my-wt", true, HOME, "Drydock"));
    }

    @Test
    void anUneditedFieldFollowsTheBranchAsItChanges() {
        String first = WorktreeDialogs.directoryFor("feat/one", "", false, HOME, "drydock");
        String second = WorktreeDialogs.directoryFor("feat/two", first, false, HOME, "drydock");
        assertEquals(Path.of("/home/tester/dev/wt/drydock-two").toString(), second);
    }

    // ---- error mapping (AC26) -----------------------------------------------------

    @Test
    void gitFailureSurfacesTheStderrExcerpt() {
        GitCommandFailedException failure = new GitCommandFailedException(
                List.of("git", "worktree", "add", "/tmp/wt", "-b", "bad"), 128,
                "fatal: a branch named 'bad' already exists");
        String message = WorktreeErrors.visibleMessage(failure);
        assertTrue(message.contains("fatal: a branch named 'bad' already exists"),
                "stderr excerpt missing from: " + message);
        assertTrue(message.contains("128"), "exit code missing from: " + message);
    }

    @Test
    void futureWrappersAreUnwrappedBeforeMapping() {
        GitCommandFailedException cause = new GitCommandFailedException(
                List.of("git", "worktree", "add"), 1, "stderr detail");
        String message = WorktreeErrors.visibleMessage(new CompletionException(cause));
        assertTrue(message.contains("stderr detail"), "excerpt missing through the future wrapper: " + message);
    }

    @Test
    void lockedAndNotCleanRefusalsKeepTheirOwnWords() {
        WorktreeLockedException locked = new WorktreeLockedException(Path.of("/tmp/wt"), Optional.of("sphinx"));
        assertTrue(WorktreeErrors.visibleMessage(locked).contains("locked"));
        assertTrue(WorktreeErrors.visibleMessage(locked).contains("sphinx"));

        WorktreeNotCleanException notClean = new WorktreeNotCleanException(Path.of("/tmp/wt"));
        assertTrue(WorktreeErrors.visibleMessage(notClean).contains("modified or untracked files"));
    }

    @Test
    void aTimeoutNamesTheCommandAndTheBound() {
        ProcessTimeoutException timeout =
                new ProcessTimeoutException(List.of("git", "worktree", "add"), Duration.ofSeconds(15));
        String message = WorktreeErrors.visibleMessage(timeout);
        assertTrue(message.contains("15"), "timeout bound missing from: " + message);
        assertTrue(message.contains("git worktree add"), "command missing from: " + message);
    }

    @Test
    void anUnmappedFailureStillGetsVisibleText() {
        String message = WorktreeErrors.visibleMessage(new IllegalStateException("boom"));
        assertTrue(message.contains("boom"));
        assertFalse(message.isBlank());
    }
}
