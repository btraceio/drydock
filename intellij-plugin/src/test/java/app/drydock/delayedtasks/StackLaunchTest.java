package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskPrompt;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.StackUnits;
import app.drydock.tasks.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The branch-wait behavior of a stacked launch (S2 of
 * docs/StackedPrExecution.md) and the root unit's start-point rule: an
 * immediately satisfied wait completes without the poll delay, a branch
 * that appears later completes the wait, an expired deadline fails
 * visibly, and a root unit's branch is cut from the repository's default
 * branch -- the same branch its draft pull request opens against. Real
 * temporary git repositories back every probe; assertions are on
 * completion order and rendered text, never on thread timing. */
class StackLaunchTest {

    private static final RepositoryId REPO = RepositoryId.of("11111111-1111-1111-1111-111111111111");

    // ---- fixtures -------------------------------------------------------

    /** Runs one git command in {@code cwd}; a non-zero exit fails the test. */
    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + List.of(args) + " failed in " + cwd);
        }
    }

    /** A real temporary repository with one empty commit on {@code branch}. */
    private static Path freshRepo(Path dir, String branch) throws IOException, InterruptedException {
        Path repo = dir.resolve("repo-" + branch);
        Files.createDirectories(repo);
        git(repo, "init");
        git(repo, "-c", "user.email=test@example.com", "-c", "user.name=test",
                "commit", "--allow-empty", "-m", "seed");
        git(repo, "branch", branch);
        return repo;
    }

    private static TaskDocument doc(String id, String title, DoneBy doneBy, String... after) {
        return new TaskDocument(TaskId.of(id), REPO, title, TaskStatus.OPEN, doneBy, Optional.empty(),
                List.of(after), List.of(), "", "");
    }

    private static TaskGraph graphOf(List<TaskDocument> docs) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        docs.forEach(document -> byId.put(document.id(), document));
        return TaskGraph.of(new TaskSnapshot(docs, List.of(REPO), byId, Map.of(), List.of(), List.of()),
                r -> "demo");
    }

    // ---- the branch wait (T3) -------------------------------------------

    @Test
    void anImmediatelySatisfiedWaitCompletesWithoutThePollDelay(@TempDir Path dir) throws Exception {
        Path repo = freshRepo(dir, "dd/chain-a");
        long started = System.nanoTime();
        // The old code delayed the first probe by the 10 s poll interval:
        // this wait must complete well before that.
        StackLaunch.awaitBranches(repo, List.of("dd/chain-a")).get(5, TimeUnit.SECONDS);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).getSeconds() < 5,
                "an already-existing branch satisfied the wait without the poll delay");
    }

    @Test
    void aBranchThatAppearsAfterTheWaitStartedCompletesIt(@TempDir Path dir) throws Exception {
        Path repo = freshRepo(dir, "main");
        CompletableFuture<Void> done = StackLaunch.awaitBranches(repo, List.of("dd/chain-a"));
        git(repo, "branch", "dd/chain-a");
        done.get(30, TimeUnit.SECONDS);
    }

    @Test
    void aWaitPastItsDeadlineFailsVisibly(@TempDir Path dir) throws Exception {
        Path repo = freshRepo(dir, "main");
        CompletableFuture<Void> done = StackLaunch.awaitBranches(repo, List.of("dd/never"),
                Duration.ofNanos(1));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> done.get(30, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("did not appear within"),
                "the deadline expiry names the refused wait: " + failure.getCause().getMessage());
    }

    // ---- the root start point (T4) --------------------------------------

    @Test
    void theRootStartResolvesTheRepositoryDefaultBranchFromOriginHead(@TempDir Path dir) throws Exception {
        Path repo = freshRepo(dir, "trunk");
        git(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/trunk");
        assertEquals("trunk", StackLaunch.defaultBranch(repo).get(30, TimeUnit.SECONDS));
    }

    @Test
    void aLocalOnlyRepositoryFallsBackToItsWellKnownDefaultBranch(@TempDir Path dir) throws Exception {
        Path repo = freshRepo(dir, "main");
        assertEquals("main", StackLaunch.defaultBranch(repo).get(30, TimeUnit.SECONDS));
    }

    @Test
    void anUnresolvableDefaultBranchFallsBackToMain(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("empty");
        Files.createDirectories(repo);
        git(repo, "init"); // no commit, no branches: nothing resolves
        assertEquals("main", StackLaunch.defaultBranch(repo).get(30, TimeUnit.SECONDS));
    }

    @Test
    void aRootPromptRendersTheResolvedDefaultBranch(@TempDir Path dir) throws Exception {
        Path repo = freshRepo(dir, "trunk");
        git(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/trunk");
        String defaultBranch = StackLaunch.defaultBranch(repo).get(30, TimeUnit.SECONDS);

        List<TaskDocument> docs = List.of(
                doc("chain-a", "Task chain-a", DoneBy.HUMAN),
                doc("chain-b", "Task chain-b", DoneBy.AGENT, "chain-a"));
        TaskGraph graph = graphOf(docs);
        StackLaunch.PromptInputs inputs = StackLaunch.PromptInputs.of(TaskId.of("chain-b"), 1, 1,
                "demo-project", REPO, docs, graph);
        StackUnits.Unit root = StackLaunch.units(docs, graph).get(0);
        String prompt = StackLaunch.prompt(TaskPrompt.Kind.PI, inputs, root, null, defaultBranch);
        assertTrue(prompt.contains("First branch starts at: trunk\n"));
        assertTrue(prompt.contains("START FROM trunk\n"));
    }

    @Test
    void aStackedUnitStillStartsAtItsStackBaseBranch(@TempDir Path dir) throws Exception {
        List<TaskDocument> docs = List.of(
                doc("chain-a", "Task chain-a", DoneBy.HUMAN),
                doc("chain-b", "Task chain-b", DoneBy.AGENT, "chain-a"),
                doc("fork-x", "Task fork-x", DoneBy.HUMAN, "chain-a"));
        TaskGraph graph = graphOf(docs);
        StackLaunch.PromptInputs inputs = StackLaunch.PromptInputs.of(TaskId.of("chain-b"), 1, 1,
                "demo-project", REPO, docs, graph);
        List<StackUnits.Unit> units = StackLaunch.units(docs, graph);
        assertEquals(2, units.size());
        String prompt = StackLaunch.prompt(TaskPrompt.Kind.PI, inputs, units.get(1), "dd/chain-a", "main");
        assertTrue(prompt.contains("START FROM dd/chain-a\n"));
    }

    // ---- the session label cut (T5.3) -----------------------------------

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String prefix = "x".repeat(59);
        String title = prefix + "😀" + "tail";
        assertEquals(65, title.length());
        String cut = StackLaunch.truncateTitle(title);
        assertEquals(59, cut.length());
        assertEquals(prefix, cut);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)),
                "the cut label never ends in an unpaired surrogate");
        // A BMP-only label is cut at the plain 60-char boundary, as before.
        assertEquals("y".repeat(60), StackLaunch.truncateTitle("y".repeat(65)));
        assertEquals("z", StackLaunch.truncateTitle("z"));
    }
}
