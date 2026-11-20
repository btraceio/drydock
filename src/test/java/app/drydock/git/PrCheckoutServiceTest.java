package app.drydock.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the PR checkout that can be tested without GitHub: the
 * pre-flight refusals, the local branch naming, and -- the one that matters
 * most -- that a failed checkout leaves nothing behind and never disturbs
 * the main checkout.
 *
 * <p>The {@code gh pr checkout} step itself needs a real pull request and is
 * covered by the manual pass in the milestone report.</p>
 */
class PrCheckoutServiceTest {

    private final PrCheckoutService service = new PrCheckoutService();

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void aPullRequestGetsADeterministicLocalBranchName() {
        assertEquals("pr-412", PrCheckoutService.localBranchFor(412));
    }

    @Test
    void aNonPositivePrNumberIsRefusedBeforeAnythingRuns(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);

        assertThrows(PrCheckoutService.PrCheckoutException.class,
                () -> service.checkoutBlocking(repo, dir.resolve("wt"), 0));

        assertFalse(Files.exists(dir.resolve("wt")));
    }

    @Test
    void anOccupiedDirectoryIsRefusedRatherThanOverwritten(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        Path occupied = Files.createDirectories(dir.resolve("taken"));
        Files.writeString(occupied.resolve("precious.txt"), "do not delete me\n");

        PrCheckoutService.PrCheckoutException failure =
                assertThrows(PrCheckoutService.PrCheckoutException.class,
                        () -> service.checkoutBlocking(repo, occupied, 412));

        assertTrue(failure.getMessage().contains("already something at"), failure.getMessage());
        assertEquals("do not delete me\n", Files.readString(occupied.resolve("precious.txt")));
    }

    /**
     * The load-bearing one. {@code gh pr checkout} always operates on the
     * working tree it runs in, so a failure must not leave a half-made
     * worktree behind -- the next attempt would collide with it -- and the
     * main checkout must be exactly where the reviewer left it.
     */
    @Test
    void aFailedCheckoutCleansUpAndLeavesTheMainCheckoutAlone(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        String branchBefore = runGitCapture(repo, "rev-parse", "--abbrev-ref", "HEAD").strip();
        String headBefore = runGitCapture(repo, "rev-parse", "HEAD").strip();
        Path worktree = dir.resolve("pr-worktree");

        // No GitHub remote, so gh pr checkout cannot succeed here -- which is
        // exactly the failure path under test.
        assertThrows(RuntimeException.class, () -> service.checkoutBlocking(repo, worktree, 999999));

        assertFalse(Files.exists(worktree), "a failed checkout must not leave a worktree behind");
        assertEquals(branchBefore, runGitCapture(repo, "rev-parse", "--abbrev-ref", "HEAD").strip(),
                "the main checkout's branch must not move");
        assertEquals(headBefore, runGitCapture(repo, "rev-parse", "HEAD").strip(),
                "the main checkout's HEAD must not move");
        assertEquals(1, worktreeCount(repo), "no stray worktree may remain registered");
    }

    private static int worktreeCount(Path repo) throws IOException, InterruptedException {
        return (int) runGitCapture(repo, "worktree", "list").lines().filter(line -> !line.isBlank()).count();
    }

    /**
     * The shim re-exec regression: the found {@code gh} may be a wrapper that
     * re-execs a real gh from PATH (the Dogbrew shim does exactly this), so the
     * child must receive a PATH that reaches one. The wrapper and its target
     * live ONLY on the path the service is told to hand the child — the test
     * JVM's own PATH knows neither — so without the PATH the invocation fails
     * with "exec: gh: not found" and no worktree can ever be made.
     */
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theGhChildReExecsARealGhThroughThePathItIsGiven(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        Path bin = dir.resolve("bin");
        Files.createDirectories(bin);
        Path receipt = dir.resolve("receipt.txt");
        Files.writeString(bin.resolve("gh-real"),
                "#!/bin/sh\nprintf 'cwd=%s|args=%s\\n' \"$(pwd)\" \"$*\" >> " + receipt + "\nexit 0\n");
        Files.writeString(bin.resolve("gh"), "#!/bin/sh\nexec gh-real \"$@\"\n");
        bin.resolve("gh").toFile().setExecutable(true);
        bin.resolve("gh-real").toFile().setExecutable(true);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PrCheckoutService service = new PrCheckoutService(new GitExecutableLocator(), executor, false,
                () -> Optional.of(bin.resolve("gh")), () -> bin.toString());
        try {
            Path worktree = service.checkoutBlocking(repo, dir.resolve("wt"), 858);
            assertTrue(Files.exists(worktree), "the wrapper gh reported success, so the worktree stays");
        } finally {
            service.close();
        }
        String logged = Files.readString(receipt);
        assertTrue(logged.contains("args=pr checkout 858"), logged);
        assertTrue(logged.contains("cwd=" + dir.resolve("wt").toRealPath()), logged);
    }

    /**
     * A fully merged leftover pr-<n> from an earlier review is DELETED
     * before gh runs (git's safe delete allows it: a branch at main's HEAD
     * is merged), not synced by gh: gh's sync is version-dependent (merge --ff-only without --force, which dies
     * on a rebase-diverged branch -- the re-review that can never start
     * again). After the delete, gh has nothing to fast-forward over. The
     * plugin's copy of this flow ran without --force at all until now, which
     * is what kept failing IDE re-reviews of PR #664.
     */
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aStaleScratchBranchIsDeletedBeforeGhRuns(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        runGit(repo, "branch", "pr-858");
        Path bin = dir.resolve("bin");
        Path receipt = scriptedGh(bin);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PrCheckoutService service = new PrCheckoutService(new GitExecutableLocator(), executor, false,
                () -> Optional.of(bin.resolve("gh")), () -> bin.toString());
        try {
            service.checkoutBlocking(repo, dir.resolve("wt"), 858);
        } finally {
            service.close();
        }

        assertEquals("", runGitCapture(repo, "branch", "--list", "pr-858").strip(),
                "a fully merged leftover pr-<n> must be gone before gh is asked to check out");
        String logged = Files.readString(receipt);
        assertTrue(logged.contains("args=pr checkout 858 --branch pr-858 --force"), logged);
    }

    /**
     * A delete that cannot run -- the pr-<n> branch is checked out in some
     * other live worktree -- must not abort the checkout: the flow hands off
     * to gh anyway, whose refusal (or, here, success) is the answer the
     * caller acts on. The branch is left exactly where it was.
     */
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aHeldScratchBranchStillHandsOffToGh(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        runGit(repo, "branch", "pr-858");
        // The branch is checked out here, so `git branch -d` must refuse.
        runGit(repo, "worktree", "add", dir.resolve("holder").toString(), "pr-858");
        Path bin = dir.resolve("bin");
        Path receipt = scriptedGh(bin);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PrCheckoutService service = new PrCheckoutService(new GitExecutableLocator(), executor, false,
                () -> Optional.of(bin.resolve("gh")), () -> bin.toString());
        try {
            Path worktree = service.checkoutBlocking(repo, dir.resolve("wt"), 858);
            assertTrue(Files.exists(worktree), "the scripted gh reported success, so the worktree stays");
        } finally {
            service.close();
        }

        assertTrue(runGitCapture(repo, "branch", "--list", "pr-858").strip().endsWith("pr-858"),
                "a held branch is left alone");
        assertTrue(Files.readString(receipt).contains("args=pr checkout 858"),
                "the failed delete must not abort the gh hand-off");
    }

    /**
     * A pr-<n> holding unmerged commits -- a user's own branch, or a leftover
     * a PR rebase diverged -- is never destroyed by drydock's own pre-gh
     * delete: git's safe delete refuses it and the flow hands off to gh. This
     * pins only that pre-gh step; the scripted gh resets nothing, so it says
     * nothing about the branch surviving a real {@code gh --force}.
     */
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anUnmergedPrBranchIsNeverForceDeleted(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        String tip = commitOnUnmergedPrBranch(repo);
        Path bin = dir.resolve("bin");
        Path receipt = scriptedGh(bin);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PrCheckoutService service = new PrCheckoutService(new GitExecutableLocator(), executor, false,
                () -> Optional.of(bin.resolve("gh")), () -> bin.toString());
        try {
            service.checkoutBlocking(repo, dir.resolve("wt"), 858);
        } finally {
            service.close();
        }

        assertEquals(tip, runGitCapture(repo, "rev-parse", "refs/heads/pr-858").strip(),
                "the unmerged commit must survive the pre-gh delete");
        String logged = Files.readString(receipt);
        assertTrue(logged.contains("args=pr checkout 858 --branch pr-858 --force"), logged);
    }

    /**
     * No pr-<n> at all is the common first-review case: the delete exits
     * non-zero ("not found"), which is not an error -- gh still runs.
     */
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anAbsentPrBranchGoesStraightToGh(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        Path bin = dir.resolve("bin");
        Path receipt = scriptedGh(bin);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PrCheckoutService service = new PrCheckoutService(new GitExecutableLocator(), executor, false,
                () -> Optional.of(bin.resolve("gh")), () -> bin.toString());
        try {
            Path worktree = service.checkoutBlocking(repo, dir.resolve("wt"), 858);
            assertTrue(Files.exists(worktree), "the scripted gh reported success, so the worktree stays");
        } finally {
            service.close();
        }

        String logged = Files.readString(receipt);
        assertTrue(logged.contains("args=pr checkout 858 --branch pr-858 --force"), logged);
    }

    /**
     * A gh failure after the refused delete still removes the step-1
     * worktree, and the unmerged pr-<n> survives that path too. (Where the
     * delete sits relative to the try is a static check, not this test.)
     */
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aFailingGhAfterTheDeleteStillCleansUp(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        String tip = commitOnUnmergedPrBranch(repo);
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path receipt = dir.resolve("receipt.txt");
        Files.writeString(bin.resolve("gh"),
                "#!/bin/sh\nprintf 'args=%s\\n' \"$*\" >> " + receipt + "\necho boom >&2\nexit 1\n");
        bin.resolve("gh").toFile().setExecutable(true);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        PrCheckoutService service = new PrCheckoutService(new GitExecutableLocator(), executor, false,
                () -> Optional.of(bin.resolve("gh")), () -> bin.toString());
        try {
            assertThrows(RuntimeException.class, () -> service.checkoutBlocking(repo, dir.resolve("wt"), 858));
        } finally {
            service.close();
        }

        assertFalse(Files.exists(dir.resolve("wt")), "a failed checkout must not leave a worktree behind");
        assertEquals(1, worktreeCount(repo), "no stray worktree may remain registered");
        assertEquals(tip, runGitCapture(repo, "rev-parse", "refs/heads/pr-858").strip(),
                "the unmerged commit must survive a failed checkout too");
        assertTrue(Files.readString(receipt).contains("args=pr checkout 858"));
    }

    // ---- fixtures -----------------------------------------------------------

    /**
     * A fake gh that records its argv and exits 0: enough to drive the
     * checkout's git steps while asserting what gh was asked to do.
     */
    private static Path scriptedGh(Path bin) throws IOException {
        Files.createDirectories(bin);
        Path receipt = bin.getParent().resolve("receipt.txt");
        Files.writeString(bin.resolve("gh"),
                "#!/bin/sh\nprintf 'args=%s\\n' \"$*\" >> " + receipt + "\nexit 0\n");
        bin.resolve("gh").toFile().setExecutable(true);
        return receipt;
    }

    /**
     * Puts a unique commit on a new pr-858 and returns to main, so pr-858 is
     * not merged into main's HEAD; returns pr-858's tip.
     */
    private static String commitOnUnmergedPrBranch(Path repo) throws IOException, InterruptedException {
        runGit(repo, "checkout", "-b", "pr-858");
        Files.writeString(repo.resolve("work.txt"), "unmerged work\n");
        runGit(repo, "add", "work.txt");
        runGit(repo, "commit", "-m", "unmerged work");
        runGit(repo, "checkout", "main");
        return runGitCapture(repo, "rev-parse", "refs/heads/pr-858").strip();
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        String output = capture(repo, true, args);
        if (output == null) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
        return capture(repo, true, args);
    }

    private static String capture(Path repo, boolean requireSuccess, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (requireSuccess && exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " exited " + exit
                    + ": " + output);
        }
        return output;
    }
}
