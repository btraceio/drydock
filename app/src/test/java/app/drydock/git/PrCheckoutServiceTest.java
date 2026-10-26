package app.drydock.git;

import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
    // assumes gh is installed on the runner; windows-latest does not ship it.
    @EnabledOnOs({OS.MAC, OS.LINUX})
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
     * live ONLY on the path the service is told to hand the child -- the test
     * JVM's own PATH knows neither -- so without the PATH the invocation fails
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

    // ---- fixtures -----------------------------------------------------------

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
