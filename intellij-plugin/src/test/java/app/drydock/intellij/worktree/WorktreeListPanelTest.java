package app.drydock.intellij.worktree;

import app.drydock.git.BranchCatalog;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.actions.CheckoutWorktreeAction;
import app.drydock.intellij.actions.CheckoutWorktreeAction.CheckoutRequest;
import app.drydock.intellij.actions.CheckoutWorktreeAction.PickerRow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T15 pure seams, pinned without opening a dialog or the IDE — every
 * fixture is a real temporary git repository following the core {@code
 * WorktreeServiceTest} pattern, and every seam takes plain values (a
 * listing, a catalog), so there is nothing IDE-bound to instantiate. What
 * is pinned: the worktree list renders from {@link WorktreeService#list}
 * and keeps the distinct escapes apart (AC25 last sentence — locked reads
 * "locked: reason", a stale one reads "stale", the project's own checkout
 * is the one row without an Open action), and the checkout picker's choice
 * goes through {@link app.drydock.git.BranchCheckout#resolve} — a free
 * local branch, a remote-only branch that must mint its tracking local
 * name, an occupied branch, and unknown text each produce exactly the
 * verdict the git call depends on (AC24), while the rows render {@link
 * app.drydock.git.BranchCheckout#dropdownLabel} (why a blocked row cannot
 * be picked) and the directory default reuses T14's derivation.
 */
class WorktreeListPanelTest {

    private static final Path HOME = Path.of("/home/tester");

    // ---- worktree list rendering (AC25) ----------------------------------------

    @Test
    void rowsComeFromTheWorktreeServiceListing(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path sidebar = worktreeParent.resolve("sidebar");
        runGit(repo, "worktree", "add", sidebar.toString(), "-b", "feat/sidebar");
        runGit(repo, "worktree", "lock", "--reason", "initializing", sidebar.toString());

        List<Worktree> listing;
        try (WorktreeService service = new WorktreeService()) {
            listing = service.list(repo).get();
        }

        // The production panel passes the repository's canonicalized root
        // (RepositoryCatalog.canonicalize), and git prints real paths —
        // compare real-to-real, as the plugin does.
        List<WorktreeListPanel.Row> rows = WorktreeListPanel.rowsOf(listing, repo.toRealPath());
        assertEquals(2, rows.size());
        // git lists the main checkout first; it is the project we are in,
        // so it is the one row whose own Open action is disabled.
        WorktreeListPanel.Row main = rows.get(0);
        assertTrue(main.worktree().mainCheckout());
        assertTrue(main.current());
        assertTrue(main.detail().contains("main"));
        assertTrue(main.detail().contains("main checkout"), main.detail());
        assertFalse(rows.get(1).current());
        // The branch and the lock reason — the escape git needs — both read.
        WorktreeListPanel.Row locked = rows.get(1);
        assertEquals(Optional.of("feat/sidebar"), locked.worktree().branch());
        assertTrue(locked.detail().contains("feat/sidebar"), locked.detail());
        assertTrue(locked.detail().contains("locked: initializing"), locked.detail());
        assertEquals(sidebar.toRealPath(), locked.path().toRealPath());
    }

    @Test
    void aStaleWorktreeIsMarkedApartFromALockedOne(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path stale = worktreeParent.resolve("stale");
        runGit(repo, "worktree", "add", stale.toString(), "-b", "feat/stale");
        // The directory leaves disk, but git still holds the branch — the
        // prunable case, whose escape (`git worktree prune`) differs from a
        // lock's and so must not read the same.
        deleteRecursively(stale);

        List<Worktree> listing;
        try (WorktreeService service = new WorktreeService()) {
            listing = service.list(repo).get();
        }

        List<WorktreeListPanel.Row> rows = WorktreeListPanel.rowsOf(listing, repo.toRealPath());
        WorktreeListPanel.Row staleRow = rows.get(1);
        assertTrue(staleRow.worktree().prunable());
        assertTrue(staleRow.detail().contains("stale"), staleRow.detail());
        assertFalse(staleRow.detail().contains("locked"), "stale must not read as locked: " + staleRow.detail());
    }

    @Test
    void aDetachedWorktreeRowReadsDetachedNotBranchless(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        String head = runGitCapture(repo, "rev-parse", "HEAD").strip();
        Path detached = worktreeParent.resolve("detached");
        runGit(repo, "worktree", "add", "--detach", detached.toString(), head);

        List<Worktree> listing;
        try (WorktreeService service = new WorktreeService()) {
            listing = service.list(repo).get();
        }

        WorktreeListPanel.Row row = WorktreeListPanel.rowsOf(listing, repo.toRealPath()).get(1);
        assertTrue(row.worktree().detached());
        assertTrue(row.detail().startsWith("detached"), row.detail());
    }

    // ---- picker resolution wiring (AC24) ---------------------------------------

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
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("git " + List.of(args) + " failed (" + exit + "): " + output);
        }
        return output;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
