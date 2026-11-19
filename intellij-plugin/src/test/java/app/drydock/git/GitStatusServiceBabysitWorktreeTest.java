package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The babysitter's detached worktree and the write guard's remote tip
 * ({@link GitStatusService#addDetachedWorktreeAtRemoteBranchBlocking},
 * {@link GitStatusService#remoteBranchTipBlocking}): a bare local "origin"
 * and clones of it, no network.
 */
class GitStatusServiceBabysitWorktreeTest {

    private static final String BRANCH = "feature/babysit-me";

    private final GitStatusService service = new GitStatusService();

    @Test
    void theCommandsAreTheOneBranchRefspecAndADetachedAddAfterEndOfOptions() {
        Path git = Path.of("/usr/bin/git");
        Path root = Path.of("/repo");
        assertEquals(List.of("/usr/bin/git", "-C", "/repo", "fetch", "origin",
                        "+feature/x:refs/remotes/origin/feature/x"),
                GitStatusService.fetchBranchCommand(git, root, "feature/x"));
        assertEquals(List.of("/usr/bin/git", "-C", "/repo", "worktree", "add", "--detach", "/wt/repo--babysit-pr7",
                        "--end-of-options", "abc123"),
                GitStatusService.addDetachedWorktreeCommand(git, root, Path.of("/wt/repo--babysit-pr7"), "abc123"));
    }

    @Test
    void theWorktreeIsDetachedAtTheFreshRemoteTipAndCreatesNoBranch(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);
        String tip = advanceRemoteBranch(dir, "second.txt");
        String branchesBefore = runGitCapture(work, "branch", "--list");

        GitStatusService.DetachedWorktree worktree = service.addDetachedWorktreeAtRemoteBranchBlocking(
                work, dir.resolve("wt").resolve("repo--babysit-pr7"), BRANCH);

        assertEquals(dir.resolve("wt").resolve("repo--babysit-pr7").toAbsolutePath().normalize(), worktree.directory());
        assertEquals(tip, worktree.baseCommit(), "the base is the freshly fetched tip, not the stale ref");
        assertEquals(Optional.empty(), worktree.fetchError());
        assertEquals(tip, revParse(worktree.directory(), "HEAD"));
        assertFalse(runGitAllowingFailure(worktree.directory(), "symbolic-ref", "-q", "HEAD"),
                "HEAD must be detached");
        assertEquals(branchesBefore, runGitCapture(work, "branch", "--list"), "no branch may be created");
    }

    @Test
    void aLocalBranchNamedLikeTheRemoteRefDoesNotShadowIt(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);
        String tip = advanceRemoteBranch(dir, "second.txt");
        // refs/heads/origin/<branch> would win the plain "origin/<branch>" lookup.
        runGit(work, "branch", "origin/" + BRANCH, "main");

        GitStatusService.DetachedWorktree worktree =
                service.addDetachedWorktreeAtRemoteBranchBlocking(work, dir.resolve("wt"), BRANCH);

        assertEquals(tip, worktree.baseCommit());
        assertEquals(tip, revParse(worktree.directory(), "HEAD"));
    }

    @Test
    void anExistingDirectoryIsRefusedBeforeGitRuns(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);
        Path occupied = Files.createDirectories(dir.resolve("occupied"));
        Files.writeString(occupied.resolve("keep.txt"), "mine\n");

        WorktreeDirectoryExistsException refused = assertThrows(WorktreeDirectoryExistsException.class,
                () -> service.addDetachedWorktreeAtRemoteBranchBlocking(work, occupied, BRANCH));

        assertEquals(occupied.toAbsolutePath().normalize(), refused.directory());
        assertTrue(Files.exists(occupied.resolve("keep.txt")));
        assertFalse(runGitCapture(work, "worktree", "list").contains(occupied.toString()));
    }

    @Test
    void aFailedFetchFallsBackToTheLastKnownTipAndSaysSo(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);
        String stale = revParse(work, "refs/remotes/origin/" + BRANCH);
        runGit(work, "remote", "set-url", "origin", dir.resolve("gone.git").toString());

        GitStatusService.DetachedWorktree worktree =
                service.addDetachedWorktreeAtRemoteBranchBlocking(work, dir.resolve("wt"), BRANCH);

        assertTrue(worktree.fetchError().isPresent());
        assertEquals(stale, worktree.baseCommit());
    }

    @Test
    void aBranchTheRemoteDoesNotHaveFailsWithoutAWorktree(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);

        GitCommandFailedException failed = assertThrows(GitCommandFailedException.class,
                () -> service.addDetachedWorktreeAtRemoteBranchBlocking(work, dir.resolve("wt"), "no/such-branch"));

        assertEquals(GitCommandFailedException.Outcome.KNOWN_FAILED, failed.outcome());
        assertTrue(failed.stderrExcerpt().contains("fetch failed"), failed.stderrExcerpt());
        assertTrue(Files.notExists(dir.resolve("wt")));
    }

    @Test
    void anOptionLikeBranchIsRefusedOutright(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);

        assertThrows(IllegalArgumentException.class,
                () -> service.addDetachedWorktreeAtRemoteBranchBlocking(work, dir.resolve("wt"), "--upload-pack=x"));
        assertThrows(IllegalArgumentException.class, () -> service.remoteBranchTipBlocking(work, "a..b"));
        assertTrue(Files.notExists(dir.resolve("wt")));
    }

    @Test
    void theRemoteTipFollowsAPushAndAFailedFetchIsReported(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);
        String before = revParse(work, "refs/remotes/origin/" + BRANCH);

        assertEquals(new GitStatusService.RemoteBranchTip(Optional.of(before), Optional.empty()),
                service.remoteBranchTipBlocking(work, BRANCH));

        String pushed = advanceRemoteBranch(dir, "third.txt");
        assertEquals(Optional.of(pushed), service.remoteBranchTipBlocking(work, BRANCH).commit());

        runGit(work, "remote", "set-url", "origin", dir.resolve("gone.git").toString());
        GitStatusService.RemoteBranchTip offline = service.remoteBranchTipBlocking(work, BRANCH);
        assertTrue(offline.fetchFailed());
        assertEquals(Optional.of(pushed), offline.commit(), "the stale ref is reported, flagged by the failure");
    }

    @Test
    void removeWorktreeQuietlyUndoesTheAdd(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);
        GitStatusService.DetachedWorktree worktree =
                service.addDetachedWorktreeAtRemoteBranchBlocking(work, dir.resolve("wt"), BRANCH);

        service.removeWorktreeQuietly(work, worktree.directory());

        assertTrue(Files.notExists(worktree.directory()));
        assertFalse(runGitCapture(work, "worktree", "list").contains(worktree.directory().toString()));
        // A second removal has nothing to remove: logged, never thrown.
        service.removeWorktreeQuietly(work, worktree.directory());
    }

    @Test
    void thePruneCommandIsTheRepositorysWorktreePrune() {
        assertEquals(List.of("/usr/bin/git", "-C", "/repo", "worktree", "prune"),
                GitStatusService.pruneWorktreesCommand(Path.of("/usr/bin/git"), Path.of("/repo")));
    }

    @Test
    void pruneWorktreesQuietlyClearsAStaleEntrySoTheSamePathCanBeAddedAgain(@TempDir Path dir) throws Exception {
        Path work = cloneWithBranch(dir);
        Path target = dir.resolve("wt");
        GitStatusService.DetachedWorktree worktree =
                service.addDetachedWorktreeAtRemoteBranchBlocking(work, target, BRANCH);
        // What an add killed mid-way can leave: git's entry, no usable directory.
        deleteRecursively(worktree.directory());
        assertTrue(runGitCapture(work, "worktree", "list", "--porcelain").contains("prunable"));

        service.pruneWorktreesQuietly(work);

        assertFalse(runGitCapture(work, "worktree", "list").contains(worktree.directory().toString()));
        service.addDetachedWorktreeAtRemoteBranchBlocking(work, target, BRANCH);
        // Nothing to prune is not a failure either.
        service.pruneWorktreesQuietly(work);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    // ---- fixtures ----------------------------------------------------------------

    /** A bare origin with {@code main} and {@link #BRANCH}, and a work clone that has fetched both. */
    private static Path cloneWithBranch(Path dir) throws IOException, InterruptedException {
        Path seed = Files.createDirectories(dir.resolve("seed"));
        runGit(seed, "init", "-b", "main");
        Files.writeString(seed.resolve("README.md"), "hello\n");
        runGit(seed, "add", "README.md");
        commit(seed, "initial commit");
        runGit(seed, "checkout", "-b", BRANCH);
        Files.writeString(seed.resolve("feature.txt"), "feature\n");
        runGit(seed, "add", "feature.txt");
        commit(seed, "feature commit");
        runGit(seed, "checkout", "main");
        Path remote = dir.resolve("remote.git");
        runGit(dir, "clone", "--bare", seed.toString(), remote.toString());
        Path work = dir.resolve("work");
        runGit(dir, "clone", remote.toString(), work.toString());
        return work;
    }

    /** Pushes one more commit to {@link #BRANCH} from a second clone; returns the new tip. */
    private static String advanceRemoteBranch(Path dir, String file) throws IOException, InterruptedException {
        Path other = dir.resolve("other-" + file);
        runGit(dir, "clone", "--branch", BRANCH, dir.resolve("remote.git").toString(), other.toString());
        Files.writeString(other.resolve(file), file + "\n");
        runGit(other, "add", file);
        commit(other, "advance " + file);
        runGit(other, "push", "origin", BRANCH);
        return revParse(other, "HEAD");
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static String revParse(Path repo, String ref) throws IOException, InterruptedException {
        return runGitCapture(repo, "rev-parse", ref).strip();
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static boolean runGitAllowingFailure(Path cwd, String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command(cwd, args)).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
    }

    private static String runGitCapture(Path cwd, String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command(cwd, args)).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
        return output;
    }

    private static List<String> command(Path cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(cwd.toString());
        command.addAll(Arrays.asList(args));
        return command;
    }
}
