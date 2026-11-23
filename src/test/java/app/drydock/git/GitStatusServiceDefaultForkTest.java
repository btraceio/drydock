package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link GitStatusService#defaultBranchFork(Path, boolean)}:
 * the ref a task branch forks from when its base is the repository's
 * default branch. Local remotes (file-path remotes and bare clones) back
 * every case — no network involved.
 */
class GitStatusServiceDefaultForkTest {

    private final GitStatusService service = new GitStatusService();

    /**
     * Creates a bare "origin" whose default branch is {@code branch}, seeded
     * with one commit, plus a work clone of it. Returns the clone.
     */
    private Path cloneWithOrigin(Path dir, String branch) throws IOException, InterruptedException {
        Path seed = Files.createDirectories(dir.resolve("seed"));
        runGit(seed, "init", "-b", branch);
        writeFile(seed, "README.md", "hello\n");
        runGit(seed, "add", "README.md");
        commit(seed, "initial commit");
        Path remote = dir.resolve("remote.git");
        runGit(dir, "clone", "--bare", seed.toString(), remote.toString());
        Path work = dir.resolve("work");
        runGit(dir, "clone", remote.toString(), work.toString());
        runGit(work, "remote", "set-head", "origin", "-a");
        return work;
    }

    /** Advances the remote's default branch by one commit from a second clone. */
    private void pushToRemote(Path dir, String branch, String file, String content) throws Exception {
        Path other = dir.resolve("other");
        runGit(dir, "clone", dir.resolve("remote.git").toString(), other.toString());
        writeFile(other, file, content);
        runGit(other, "add", file);
        commit(other, "advance " + branch);
        runGit(other, "push", "origin", branch);
    }

    @Test
    void withoutFetchTheRemoteRefIsNotMoved(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "main");
        String before = revParse(work, "refs/remotes/origin/main");
        pushToRemote(dir, "main", "second.txt", "two\n");

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(work, false).get(30, TimeUnit.SECONDS);

        assertEquals("origin/main", fork.ref());
        assertEquals(Optional.of("main"), fork.name());
        assertFalse(fork.fetched());
        assertFalse(fork.fetchFailed());
        assertEquals(before, revParse(work, "refs/remotes/origin/main"),
                "the no-fetch probe must not move the remote-tracking ref");
    }

    @Test
    void withFetchTheForkIsTheFreshRemoteTip(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "main");
        pushToRemote(dir, "main", "second.txt", "two\n");
        String remoteTip = revParse(dir.resolve("other"), "HEAD");

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(work, true).get(30, TimeUnit.SECONDS);

        assertEquals("origin/main", fork.ref());
        assertTrue(fork.fetched());
        assertFalse(fork.fetchFailed());
        assertTrue(fork.fetchError().isEmpty());
        assertEquals(remoteTip, revParse(work, "refs/remotes/origin/main"),
                "the fork source must be the freshly fetched tip, not a stale ref");
    }

    @Test
    void aFailedFetchYieldsTheLastKnownRefAndTheFailure(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "main");
        String stale = revParse(work, "refs/remotes/origin/main");
        runGit(work, "remote", "set-url", "origin", dir.resolve("does-not-exist.git").toString());

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(work, true).get(30, TimeUnit.SECONDS);

        assertTrue(fork.fetchFailed(), "a dead remote must report the fetch failure");
        assertEquals("origin/main", fork.ref(), "falls back to the last-known remote-tracking ref");
        assertTrue(fork.fetchError().isPresent());
        assertEquals(stale, revParse(work, "refs/remotes/origin/main"));
    }

    @Test
    void originHeadNamesANonStandardDefaultBranch(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "trunk");

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(work, false).get(30, TimeUnit.SECONDS);

        assertEquals(Optional.of("trunk"), fork.name());
        assertEquals("origin/trunk", fork.ref());
    }

    @Test
    void aRemoteTrackingRefWinsOverTheLocalBranch(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "trunk");
        // The clone checked out local "trunk"; drop origin/HEAD (a symref —
        // update-ref -d would delete the branch it points AT, so use
        // symbolic-ref) so the name falls back to the well-known
        // candidates, which must still prefer origin/trunk over local.
        runGit(work, "symbolic-ref", "-d", "refs/remotes/origin/HEAD");

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(work, false).get(30, TimeUnit.SECONDS);

        assertEquals("origin/trunk", fork.ref());
    }

    @Test
    void theFetchTouchesOnlyTheDefaultBranch(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "main");
        // A second branch on the remote, advanced alongside main.
        Path other = dir.resolve("other");
        runGit(dir, "clone", dir.resolve("remote.git").toString(), other.toString());
        runGit(other, "checkout", "-b", "side");
        writeFile(other, "side.txt", "side\n");
        runGit(other, "add", "side.txt");
        commit(other, "side branch commit");
        runGit(other, "push", "origin", "side");
        runGit(other, "checkout", "main");
        writeFile(other, "more.txt", "more\n");
        runGit(other, "add", "more.txt");
        commit(other, "advance main");
        runGit(other, "push", "origin", "main");
        // Give the work clone the side branch's remote-tracking ref first, so
        // the fork's fetch would move it if it fetched more than the default.
        runGit(work, "fetch", "origin", "side");
        String staleOther = revParse(work, "refs/remotes/origin/side");
        // Advance the side branch on the remote AFTER the baseline fetch: a
        // fetch wider than the default branch would now move origin/side.
        runGit(other, "checkout", "side");
        writeFile(other, "side2.txt", "side two\n");
        runGit(other, "add", "side2.txt");
        commit(other, "advance side");
        runGit(other, "push", "origin", "side");

        GitStatusService.DefaultBranchFork fork = service.defaultBranchFork(work, true).get(30, TimeUnit.SECONDS);

        assertEquals("origin/main", fork.ref());
        assertEquals(staleOther, revParse(work, "refs/remotes/origin/side"),
                "fetching the default branch must not move other remote-tracking refs");
        assertEquals(revParse(other, "refs/remotes/origin/main"), revParse(work, "refs/remotes/origin/main"),
                "the default branch itself must be fresh");
    }

    @Test
    void aDeletedTrackingRefIsRecreatedByTheFetch(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "main");
        pushToRemote(dir, "main", "second.txt", "two\n");
        runGit(work, "update-ref", "-d", "refs/remotes/origin/main");
        String remoteTip = revParse(dir.resolve("other"), "HEAD");

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(work, true).get(30, TimeUnit.SECONDS);

        assertEquals("origin/main", fork.ref());
        assertTrue(fork.fetched());
        assertEquals(remoteTip, revParse(work, "refs/remotes/origin/main"),
                "the fetch must re-create the remote-tracking ref it needs");
    }

    @Test
    void aLocalOnlyRepositoryForksFromItsLocalDefault(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-b", "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(repo, true).get(30, TimeUnit.SECONDS);

        assertEquals("main", fork.ref());
        assertEquals(Optional.of("main"), fork.name());
        assertFalse(fork.fetched(), "no origin remote: there is nothing to fetch");
        assertFalse(fork.fetchFailed());
    }

    @Test
    void anUnresolvableRepositoryFallsBackToMain(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("empty");
        Files.createDirectories(repo);
        runGit(repo, "init", "-b", "main"); // no commits, no branches, no remotes

        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(repo, false).get(30, TimeUnit.SECONDS);

        assertEquals("main", fork.ref());
        assertEquals(Optional.empty(), fork.name());
    }

    @Test
    void theForkRefIsUsableAsAWorktreeStartPoint(@TempDir Path dir) throws Exception {
        Path work = cloneWithOrigin(dir, "main");
        pushToRemote(dir, "main", "second.txt", "two\n");
        GitStatusService.DefaultBranchFork fork =
                service.defaultBranchFork(work, true).get(30, TimeUnit.SECONDS);

        Path worktree = service.createWorktree(work, dir.resolve("wt"), "dd/test-fork",
                Optional.of(fork.ref())).get(30, TimeUnit.SECONDS);

        String tip = revParse(worktree, "HEAD");
        assertEquals(revParse(work, "refs/remotes/origin/main"), tip);
    }

    // ---- fixture helpers (mirror GitStatusServiceTest) --------------------

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void writeFile(Path repo, String name, String content) throws IOException {
        Files.writeString(repo.resolve(name), content);
    }

    private static String revParse(Path repo, String ref) throws IOException, InterruptedException {
        return runGitCapture(repo, "rev-parse", ref).strip();
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(cwd.toString());
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
