package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.SessionMode;
import app.drydock.git.GitStatusService;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fork point a task branch is cut from, decided at launch time: a
 * start point that names the repository's default branch is re-resolved
 * against a FRESHLY FETCHED {@code origin/<default>}; anything else passes
 * through untouched. Real git repositories back every case.
 */
class TaskLauncherForkTest {

    private final GitStatusService git = new GitStatusService();

    @Test
    void aNonDefaultStartPassesThroughUntouchedAndFetchesNothing(@TempDir Path dir) throws Exception {
        Path work = originClone(dir, "main");
        pushToRemote(dir, "main", "second.txt", "two\n");
        String staleRef = revParse(work, "refs/remotes/origin/main");

        assertEquals("dd/some-task", fork(work, "dd/some-task"));
        assertEquals("feature/topic", fork(work, "feature/topic"));
        assertEquals("origin/feature/topic", fork(work, "origin/feature/topic"));
        assertEquals(staleRef, revParse(work, "refs/remotes/origin/main"),
                "a non-default start point must not trigger a fetch");
    }

    @Test
    void theBareDefaultBranchForksFromFreshOrigin(@TempDir Path dir) throws Exception {
        Path work = originClone(dir, "main");
        String stale = revParse(work, "refs/remotes/origin/main");
        pushToRemote(dir, "main", "second.txt", "two\n");

        String fork = fork(work, "main");

        assertEquals("origin/main", fork);
        String after = revParse(work, "refs/remotes/origin/main");
        assertTrue(!stale.equals(after), "the launch must fetch origin/main before forking");
    }

    @Test
    void theOriginQualifiedDefaultForksFromFreshOrigin(@TempDir Path dir) throws Exception {
        Path work = originClone(dir, "main");
        pushToRemote(dir, "main", "second.txt", "two\n");

        assertEquals("origin/main", fork(work, "origin/main"));
    }

    @Test
    void aFetchFailureForksFromTheLastKnownRemoteRef(@TempDir Path dir) throws Exception {
        Path work = originClone(dir, "main");
        pushToRemote(dir, "main", "second.txt", "two\n");
        String stale = revParse(work, "refs/remotes/origin/main");
        runGit(work, "remote", "set-url", "origin", dir.resolve("gone.git").toString());

        String fork = fork(work, "main");

        assertEquals("origin/main", fork, "a failed fetch proceeds from the last-known ref");
        assertEquals(stale, revParse(work, "refs/remotes/origin/main"));
    }

    @Test
    void aLocalOnlyRepositoryForksFromItsLocalDefault(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-b", "main");
        writeFile(repo, "README.md", "hello\n");
        runGit(repo, "add", "README.md");
        commit(repo, "initial commit");

        assertEquals("main", fork(repo, "main"));
    }

    @Test
    void aRepositoryWithoutGitStillLaunchesTheOldWay(@TempDir Path dir) throws Exception {
        // Not a git repository at all: fork resolution is best-effort and
        // must not block the launch; createWorktree will report the truth.
        Path plain = Files.createDirectories(dir.resolve("plain"));

        assertEquals("main", fork(plain, "main"));
    }

    /**
     * Pins {@code createWorktreeFresh} end to end against real git: the
     * fork point handed to the worktree creation is the FRESH origin tip,
     * not the stale local one the dialog was showing. The single production
     * call site inside {@code TaskLauncher.start()} is verified by
     * inspection -- a headless test cannot drive the surrounding IntelliJ
     * background task.
     */
    @Test
    void createWorktreeFreshForksFromTheFreshOriginTip(@TempDir Path dir) throws Exception {
        Path work = originClone(dir, "main");
        pushToRemote(dir, "main", "second.txt", "two\n");
        String staleLocal = revParse(work, "refs/heads/main");

        Choice choice = new Choice("Task", Optional.empty(), AgentKind.CLAUDE, SessionMode.WRITES,
                WorkIn.NEW_WORKTREE, "dd/fork-test", "main", dir.resolve("worktree"),
                OpenIn.THIS_WINDOW, Optional.empty(), Optional.empty(), Optional.empty());

        TaskLauncher.createWorktreeFresh(git, work, choice, dir.resolve("worktree"), null, null);

        String forked = revParse(work, "refs/heads/dd/fork-test");
        assertEquals(revParse(work, "refs/remotes/origin/main"), forked);
        assertTrue(!forked.equals(staleLocal),
                "the task branch must not fork from the stale local main");
    }

    // ---- the fixture ----------------------------------------------------

    private String fork(Path repo, String start) throws Exception {
        return TaskLauncher.forkPointForLaunch(git, repo, start, null, null);
    }

    private Path originClone(Path dir, String branch) throws IOException, InterruptedException {
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

    private void pushToRemote(Path dir, String branch, String file, String content) throws IOException, InterruptedException {
        Path other = dir.resolve("other");
        runGit(dir, "clone", dir.resolve("remote.git").toString(), other.toString());
        writeFile(other, file, content);
        runGit(other, "add", file);
        commit(other, "advance " + branch);
        runGit(other, "push", "origin", branch);
    }

    private static void commit(Path repo, String message) throws IOException, InterruptedException {
        runGit(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", message);
    }

    private static void writeFile(Path repo, String name, String content) throws IOException {
        Files.writeString(repo.resolve(name), content);
    }

    private static String revParse(Path repo, String ref) throws IOException, InterruptedException {
        return runGit(repo, "rev-parse", ref).strip();
    }

    private static String runGit(Path cwd, String... args) throws IOException, InterruptedException {
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
