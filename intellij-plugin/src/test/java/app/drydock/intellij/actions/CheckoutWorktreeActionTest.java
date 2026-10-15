package app.drydock.intellij.actions;

import app.drydock.git.BranchCatalog;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.actions.CheckoutWorktreeAction.CheckoutRequest;
import app.drydock.intellij.actions.CheckoutWorktreeAction.PickerRow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T15 checkout-picker pure seams, relocated here from the deleted
 * {@code WorktreeListPanelTest} (T16 absorbed that panel into the combined
 * tool-window list; these tests drive {@link CheckoutWorktreeAction}, which
 * is unchanged). Every fixture is a real temporary git repository
 * following the core {@code WorktreeServiceTest} pattern, and every seam
 * takes plain values (a catalog, typed text), so there is nothing IDE-bound
 * to instantiate. What is pinned: the picker's choice goes through {@code
 * BranchCheckout#resolve} — a free local branch, a remote-only branch that
 * must mint its tracking local name, an occupied branch, and unknown text
 * each produce exactly the verdict the git call depends on (AC24) — while
 * the rows render {@code BranchCheckout#dropdownLabel} (why a blocked row
 * cannot be picked) and the directory default reuses T14's derivation.
 */
class CheckoutWorktreeActionTest {

    private static final Path HOME = Path.of("/home/tester");

    @Test
    void aFreeLocalBranchProducesTheRequestWithItsOwnName(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        BranchCatalog catalog = catalogOfAnOccupiedAndAFreeBranch(repoDir, worktreeParent);

        Optional<CheckoutRequest> request =
                CheckoutWorktreeAction.checkoutRequest(catalog, "  feat/existing  ", "/tmp/drydock-wt");

        CheckoutRequest checkout = request.orElseThrow();
        assertEquals("feat/existing", checkout.branch().name());
        assertFalse(checkout.branch().remote());
        assertEquals("feat/existing", checkout.localName());
        assertEquals(Path.of("/tmp/drydock-wt"), checkout.directory());
        assertEquals("", CheckoutWorktreeAction.hintFor(catalog, "feat/existing"));
    }

    @Test
    void anOccupiedBranchNeverProducesARequestAndTheHintNamesWhereItIsHeld(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        BranchCatalog catalog = catalogOfAnOccupiedAndAFreeBranch(repoDir, worktreeParent);
        Path held = worktreeParent.resolve("held");

        assertTrue(CheckoutWorktreeAction.checkoutRequest(catalog, "feat/held", "/tmp/wt").isEmpty());
        String hint = CheckoutWorktreeAction.hintFor(catalog, "feat/held");
        assertTrue(hint.contains("already checked out"), hint);
        assertTrue(hint.contains(held.toRealPath().toString()), "the hint must name the worktree: " + hint);
    }

    @Test
    void unknownTextAndABlankDirectoryNeverProduceARequest(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        BranchCatalog catalog = catalogOfAnOccupiedAndAFreeBranch(repoDir, worktreeParent);

        assertTrue(CheckoutWorktreeAction.checkoutRequest(catalog, "nope", "/tmp/wt").isEmpty());
        String hint = CheckoutWorktreeAction.hintFor(catalog, "nope");
        assertTrue(hint.contains("nope"), hint);
        assertTrue(CheckoutWorktreeAction.hintFor(catalog, "  ").contains("No branch"));
        // A branch that resolves still needs a directory before git runs.
        assertTrue(CheckoutWorktreeAction.checkoutRequest(catalog, "feat/existing", "   ").isEmpty());
    }

    @Test
    void aRemoteOnlyBranchResolvesToItsTrackingLocalName(@TempDir Path cloneDir, @TempDir Path upstreamDir)
            throws Exception {
        // A clone whose upstream holds a branch the clone has no local copy
        // of: the remote-only case, where the worktree mints a local branch
        // that tracks the remote one.
        Path upstream = initCommittedRepo(upstreamDir);
        runGit(upstream, "branch", "feat/from-remote");
        Path clone = Files.createDirectories(cloneDir.resolve("clone"));
        runGit(cloneDir, "clone", upstream.toRealPath().toString(), clone.toString());

        BranchCatalog catalog;
        try (GitStatusService git = new GitStatusService(); WorktreeService worktrees = new WorktreeService()) {
            catalog = BranchCatalog.load(git, worktrees, clone).get();
        }

        // Full remote spelling and bare local name resolve identically —
        // both to the remote ref, with the local name it will mint.
        for (String text : List.of("origin/feat/from-remote", "feat/from-remote")) {
            CheckoutRequest request = CheckoutWorktreeAction.checkoutRequest(catalog, text, "/tmp/wt")
                    .orElseThrow();
            assertEquals("origin/feat/from-remote", request.branch().name());
            assertTrue(request.branch().remote());
            assertEquals("feat/from-remote", request.localName());
        }
        String hint = CheckoutWorktreeAction.hintFor(catalog, "origin/feat/from-remote");
        assertTrue(hint.contains("tracking origin/feat/from-remote"), hint);
        assertTrue(hint.contains("feat/from-remote"), hint);
    }

    @Test
    void pickerRowsRenderWhyABranchCannotBePicked(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        BranchCatalog catalog = catalogOfAnOccupiedAndAFreeBranch(repoDir, worktreeParent);
        Path held = worktreeParent.resolve("held");

        List<PickerRow> rows = CheckoutWorktreeAction.pickerRows(catalog);

        PickerRow free = rowNamed(rows, "feat/existing");
        assertEquals("feat/existing", free.label());
        // The main checkout itself holds `main` — the picker must say so
        // rather than offer a second checkout of it.
        PickerRow main = rowNamed(rows, "main");
        assertTrue(main.label().contains("in use"), main.label());
        assertTrue(main.label().contains(repoDir.toRealPath().toString()), main.label());
        PickerRow occupied = rowNamed(rows, "feat/held");
        assertTrue(occupied.label().contains("in use"), occupied.label());
        assertTrue(occupied.label().contains(held.toRealPath().toString()), occupied.label());
    }

    @Test
    void theDirectoryDefaultFollowsTheResolvedLocalNameUntilEdited(
            @TempDir Path cloneDir, @TempDir Path upstreamDir) throws Exception {
        Path upstream = initCommittedRepo(upstreamDir);
        runGit(upstream, "branch", "feat/from-remote");
        Path clone = Files.createDirectories(cloneDir.resolve("clone"));
        runGit(cloneDir, "clone", upstream.toRealPath().toString(), clone.toString());

        BranchCatalog catalog;
        try (GitStatusService git = new GitStatusService(); WorktreeService worktrees = new WorktreeService()) {
            catalog = BranchCatalog.load(git, worktrees, clone).get();
        }

        // A remote branch defaults a directory for the local name it will
        // mint — the branch the worktree actually holds — via the same
        // WorktreeNaming derivation T14's create dialog uses.
        String expected = WorktreeNaming.defaultDirectory(HOME, "Drydock", "feat/from-remote").toString();
        assertEquals(expected,
                CheckoutWorktreeAction.directoryDefault(catalog, "origin/feat/from-remote", "", false, HOME,
                        "Drydock"));
        // ...and a manual edit stops the derivation, exactly as in T14.
        assertEquals("/tmp/my-wt",
                CheckoutWorktreeAction.directoryDefault(catalog, "origin/feat/from-remote", "/tmp/my-wt", true,
                        HOME, "Drydock"));
    }

    // ---- fixtures (core WorktreeServiceTest pattern) ---------------------------

    /**
     * A repo with a free local branch ({@code feat/existing}) and one held
     * by a worktree ({@code feat/held} on {@code worktreeParent/held}), and
     * the catalog loaded from it — the smallest fixture that exercises both
     * picker verdicts through the real git plumbing.
     */
    private static BranchCatalog catalogOfAnOccupiedAndAFreeBranch(Path repoDir, Path worktreeParent)
            throws IOException, InterruptedException {
        Path repo = initCommittedRepo(repoDir);
        runGit(repo, "branch", "feat/existing");
        runGit(repo, "worktree", "add", worktreeParent.resolve("held").toString(), "-b", "feat/held");
        try (GitStatusService git = new GitStatusService(); WorktreeService worktrees = new WorktreeService()) {
            return BranchCatalog.load(git, worktrees, repo).join();
        }
    }

    private static PickerRow rowNamed(List<PickerRow> rows, String branch) {
        return rows.stream()
                .filter(row -> row.branch().name().equals(branch))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no picker row for " + branch + " in " + rows));
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Drydock Fixture");
        runGit(repo, "config", "user.email", "fixture@drydock.invalid");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("git " + List.of(args) + " failed (" + exit + "): " + output);
        }
    }
}
