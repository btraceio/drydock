package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-place {@code checkout} ({@link GitStatusService#checkoutBranch(Path, String)}):
 * against real temporary Git repositories, pinning the success contract (the
 * checked-out branch is returned and HEAD moves), git's own refusal surfaces
 * (a branch already checked out in another worktree, a dirty tree a switch
 * would overwrite), and that a blank or dash-prefixed name can never reach
 * git as an option -- nor spawn it at all.
 *
 * <p>Fixtures mirror {@link GitStatusServiceTest}: raw {@code git} through a
 * read-to-EOF-then-waitFor ProcessBuilder (the pattern that never wedges),
 * with the ident the test environment already pins so no child waits on a
 * hostname lookup.</p>
 */
class GitStatusServiceCheckoutTest {

    private final GitStatusService service = new GitStatusService();

    @Test
    void switchingToAnExistingBranchReturnsItAndMovesHead(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        runGit(repo, "checkout", "-b", "feature");
        writeFile(repo, "README.md", "hello, feature\n");
        runGit(repo, "add", "README.md");
        commit(repo, "feature commit");
        runGit(repo, "checkout", "main");

        // Distinct commits on the two branches, so a "success" that left HEAD
        // where it was cannot pass the assertion below.
        String featureHead = runGitCapture(repo, "rev-parse", "feature").strip();
        String mainHead = runGitCapture(repo, "rev-parse", "main").strip();
        assertFalse(featureHead.equals(mainHead), "fixture guard: branches must diverge");

        Optional<String> checkedOut = service.checkoutBranchBlocking(repo, "feature");

        assertEquals(Optional.of("feature"), checkedOut);
        // headCommitBlocking strips; the fixture's rev-parse capture must too.
        assertEquals(featureHead, service.headCommitBlocking(repo).orElseThrow(
                () -> new AssertionError("HEAD unresolvable after checkout")));
    }

    @Test
    void asyncFormCompletesWithTheCheckedOutBranch(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");
        runGit(repo, "branch", "feature");

        assertEquals(Optional.of("feature"), service.checkoutBranch(repo, "feature").get());
    }

    @Test
    void branchCheckedOutInAWorktreeFailsNamingTheBranch(@TempDir Path repo,
                                                         @TempDir Path worktreeParent) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        Path worktree = worktreeParent.resolve("occupied-wt");
        runGit(repo, "worktree", "add", worktree.toString(), "-b", "feature");

        GitCommandFailedException failure = assertThrows(GitCommandFailedException.class,
                () -> service.checkoutBranchBlocking(repo, "feature"));
        // git's own refusal, verbatim: which branch, and where it is checked out.
        assertTrue(failure.getMessage().contains("feature"),
                "refusal must name the branch: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(worktree.toString()),
                "refusal must name the occupying worktree: " + failure.getMessage());
        // The refusal must have changed nothing.
        assertEquals(Optional.of("main"), service.currentBranchBlocking(repo));
    }

    @Test
    void conflictingDirtyTreeFailsWithTheStderrExcerpt(@TempDir Path repo) throws Exception {
        initRepo(repo, "main");
        writeFile(repo, "file.txt", "one\n");
        runGit(repo, "add", "file.txt");
        commit(repo, "initial commit");
        runGit(repo, "checkout", "-b", "feature");
        writeFile(repo, "file.txt", "two\n");
        runGit(repo, "add", "file.txt");
        commit(repo, "feature commit");
        runGit(repo, "checkout", "main");

        // An uncommitted change a switch to the diverged branch would overwrite.
        writeFile(repo, "file.txt", "local edit\n");

        GitCommandFailedException failure = assertThrows(GitCommandFailedException.class,
                () -> service.checkoutBranchBlocking(repo, "feature"));
        assertTrue(failure.getMessage().contains("file.txt"),
                "refusal must name the conflicting file: " + failure.getMessage());
        assertEquals(Optional.of("main"), service.currentBranchBlocking(repo));
    }

    @Test
    void leadingDashNameNeverSpawnsGit(@TempDir Path repo) {
        GitExecutableLocator missingLocator = new GitExecutableLocator(Path.of("/nonexistent/git-does-not-exist"));
        GitStatusService serviceWithMissingGit = new GitStatusService(missingLocator);
        try {
            // Not GitExecutableNotFoundException: the name was refused before
            // the executable was ever looked for, so no process could spawn.
            GitCommandFailedException failure = assertThrows(GitCommandFailedException.class,
                    () -> serviceWithMissingGit.checkoutBranchBlocking(repo, "-b"));
            assertTrue(failure.getMessage().contains("-b"),
                    "refusal must echo the rejected name: " + failure.getMessage());
        } finally {
            serviceWithMissingGit.close();
        }
    }

    @Test
    void blankNameIsRefusedBeforeSpawningGit(@TempDir Path repo) {
        GitExecutableLocator missingLocator = new GitExecutableLocator(Path.of("/nonexistent/git-does-not-exist"));
        GitStatusService serviceWithMissingGit = new GitStatusService(missingLocator);
        try {
            GitCommandFailedException failure = assertThrows(GitCommandFailedException.class,
                    () -> serviceWithMissingGit.checkoutBranchBlocking(repo, "   "));
            assertTrue(failure.getMessage().contains("blank"),
                    "refusal must say the name was blank: " + failure.getMessage());
        } finally {
            serviceWithMissingGit.close();
        }
    }

    // ---- fixtures (GitStatusServiceTest's pattern: read to EOF, then waitFor) ----

    private static void initRepo(Path repo, String initialBranch) throws IOException, InterruptedException {
        runGit(repo, "init", "-b", initialBranch);
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void writeFile(Path repo, String name, String content) throws IOException {
        Files.writeString(repo.resolve(name), content);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
        return output;
    }
}
