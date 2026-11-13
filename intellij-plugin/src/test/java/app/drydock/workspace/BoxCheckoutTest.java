package app.drydock.workspace;

import app.drydock.github.GitHubLinkService;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box-side worktree recipe on fakes: every ssh argv is pinned exactly,
 * the discovery's zero/one/several answers are typed, the exclude step is
 * idempotent, and every timeout is the plan's own value. No test here
 * spawns a process.
 */
class BoxCheckoutTest {

    private static final String HOST = "workspace-drydock-profiling-backend";
    private static final GitHubLinkService.Remote ORIGIN =
            new GitHubLinkService.Remote("DataDog", "profiling-backend");
    private static final String CLONE = "/home/u/dev/profiling-backend";
    private static final String GIT_DIR = CLONE + "/.git";

    private static final List<String> BATCH_OPTIONS = List.of(
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=3",
            "-o", "ServerAliveCountMax=2");

    /**
     * A fake CommandRunner: records every run (argv, options, cap) and
     * replies with a queued outcome per call — a {@link ProcessResult}, a
     * {@link ProcessTimeoutException}, or an {@link IOException}.
     */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> argvRuns = new ArrayList<>();
        final List<ProcessRunner.Options> optionsRuns = new ArrayList<>();
        final List<Integer> caps = new ArrayList<>();
        final Deque<Object> replies = new ArrayDeque<>();

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            argvRuns.add(List.copyOf(argv));
            optionsRuns.add(options);
            caps.add(maxOutputBytes);
            if (replies.isEmpty()) {
                return new ProcessResult(0, "", "");
            }
            Object reply = replies.remove();
            if (reply instanceof ProcessResult result) {
                return result;
            }
            if (reply instanceof ProcessTimeoutException timeout) {
                throw timeout;
            }
            if (reply instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("unscripted reply: " + reply);
        }

        /** The joined remote command — always the last argv element. */
        String remoteOf(int call) {
            return argvRuns.get(call).get(argvRuns.get(call).size() - 1);
        }
    }

    /** A fake CloneCache: what discovery stored, readable back. */
    private static final class FakeCache implements BoxCheckout.CloneCache {
        String stored;
        String cached;

        FakeCache() {
        }

        FakeCache(String cached) {
            this.cached = cached;
        }

        @Override
        public Optional<String> cached() {
            return Optional.ofNullable(cached);
        }

        @Override
        public void store(String clonePath) {
            stored = clonePath;
            cached = clonePath;
        }
    }

    /** Captures the JUL records BoxCheckout's System.Logger emits. */
    private static final class Recording extends Handler {
        final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static List<String> ssh(String remoteCommand) {
        List<String> expected = new ArrayList<>();
        expected.add("ssh");
        expected.addAll(BATCH_OPTIONS);
        expected.add("--");
        expected.add(HOST);
        expected.add(remoteCommand);
        return expected;
    }

    // ------------------------------------------------------------------
    // Discovery: exactly one match accepted, zero and several refused
    // ------------------------------------------------------------------

    @Test
    void discoveryWithExactlyOneMatchUsesThatCloneAndCachesIt() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0,
                "/home/u/dev/other/.git\n" + GIT_DIR + "\n", ""));
        runner.replies.add(new ProcessResult(0, "git@gitlab.ddbuild.io:DataDog/other.git\n", ""));
        runner.replies.add(new ProcessResult(0, "https://github.com/DataDog/profiling-backend.git\n", ""));
        runner.replies.add(new ProcessResult(0, "# nothing excluded yet\n", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        FakeCache cache = new FakeCache();

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), cache);

        BoxCheckout.Outcome.Ready ready =
                (BoxCheckout.Outcome.Ready) outcome;
        assertEquals(CLONE, ready.clonePath());
        assertEquals(CLONE, ready.workingPath(), "MAIN_CHECKOUT uses the clone itself");
        assertEquals(CLONE, cache.stored, "the accepted clone is cached in the .clone key");

        // The find runs as a fixed sh -c script so $HOME expands on the box.
        assertEquals(ssh("'sh' '-c' 'find \"$HOME\" -maxdepth 4 -name .git -type d'"),
                runner.argvRuns.get(0));
        assertEquals(BoxCheckout.FIND_TIMEOUT, runner.optionsRuns.get(0).timeout());
        // The origin of every candidate is read with git itself, $HOME path
        // and .git directory included.
        assertEquals(ssh("'git' '-C' '" + GIT_DIR + "' 'remote' 'get-url' 'origin'"),
                runner.argvRuns.get(2));
        assertEquals(BoxCheckout.GIT_QUERY_TIMEOUT, runner.optionsRuns.get(2).timeout());
        // The exclude file is read with -- before the path (INV-6).
        assertEquals(ssh("'cat' '--' '" + CLONE + "/.git/info/exclude'"), runner.argvRuns.get(3));
    }

    @Test
    void discoveryMatchesTheOriginCaseInsensitively() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, GIT_DIR + "\n", ""));
        runner.replies.add(new ProcessResult(0, "git@github.com:datadog/Profiling-Backend.git\n", ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache());

        assertTrue(outcome instanceof BoxCheckout.Outcome.Ready,
                "GitHub names are case-insensitive: " + outcome);
    }

    @Test
    void discoveryWithNoMatchRefusesAndCachesNothing() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, GIT_DIR + "\n", ""));
        runner.replies.add(new ProcessResult(0, "git@gitlab.ddbuild.io:DataDog/other.git\n", ""));
        FakeCache cache = new FakeCache();

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), cache);

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertTrue(refused.reason().contains("DataDog/profiling-backend"), refused.reason());
        assertTrue(refused.reason().contains("no checkout"), refused.reason());
        assertNull(cache.stored);
        // The refusal ends the launch: no exclude step, no git runs at all.
        assertEquals(2, runner.argvRuns.size(), () -> runner.argvRuns.toString());
    }

    @Test
    void discoveryWithSeveralMatchesRefusesSurfacingTheCandidates() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, "/home/u/a/.git\n/home/u/b/.git\n", ""));
        runner.replies.add(new ProcessResult(0, "https://github.com/DataDog/profiling-backend.git\n", ""));
        runner.replies.add(new ProcessResult(0, "git@github.com:DataDog/profiling-backend.git\n", ""));
        FakeCache cache = new FakeCache();

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), cache);

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        // ASM-17: the candidates are surfaced — as clone roots, not .git dirs.
        assertTrue(refused.reason().contains("/home/u/a"), refused.reason());
        assertTrue(refused.reason().contains("/home/u/b"), refused.reason());
        assertNull(cache.stored);
    }

    @Test
    void anEmptyFindListingRefusesAsNoCheckout() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, "\n", ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache());

        assertTrue(((BoxCheckout.Outcome.Refused) outcome).reason().contains("no checkout"));
    }

    @Test
    void aFindListingBeyondTheBoundRefuses() throws Exception {
        FakeRunner runner = new FakeRunner();
        StringBuilder listing = new StringBuilder();
        for (int i = 0; i <= BoxCheckout.MAX_FIND_RESULTS; i++) {
            listing.append("/home/u/repo").append(i).append("/.git\n");
        }
        runner.replies.add(new ProcessResult(0, listing.toString(), ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache());

        String reason = ((BoxCheckout.Outcome.Refused) outcome).reason();
        assertTrue(reason.contains(String.valueOf(BoxCheckout.MAX_FIND_RESULTS)), reason);
        assertTrue(reason.contains("cannot be identified"), reason);
    }

    @Test
    void anUnscannableFindLineIsATypedRefusal() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, "/home/u/.git\nnot-a-checkout-line\n", ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache());

        assertTrue(((BoxCheckout.Outcome.Refused) outcome).reason().contains("unexpected line"));
    }

    @Test
    void aFailedGetUrlForOneCandidateIsANonMatchNotAnError() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, "/home/u/broken/.git\n" + GIT_DIR + "\n", ""));
        // exit 2: no such remote — this checkout simply has no origin to compare.
        runner.replies.add(new ProcessResult(2, "", "error: No such remote 'origin'\n"));
        runner.replies.add(new ProcessResult(0, "https://github.com/DataDog/profiling-backend.git\n", ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache());

        assertTrue(outcome instanceof BoxCheckout.Outcome.Ready,
                "a candidate without an origin is classified, not a failed scan: " + outcome);
    }

    @Test
    void anUnreachableBoxDuringDiscoveryRefusesWithTheSshConfigHint() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(255, "", "ssh: connect to host workspace-x timed out\n"));
        Logger logger = Logger.getLogger(BoxCheckout.class.getName());
        Recording recorder = new Recording();
        BoxCheckout.Outcome outcome;
        try {
            logger.addHandler(recorder);
            outcome = new BoxCheckout(runner).prepare(
                    HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache());
        } finally {
            logger.removeHandler(recorder);
        }

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertTrue(refused.reason().contains("run `workspaces ssh-config`"),
                "the unreachable text surfaces the hint (ASM-9): " + refused.reason());
        assertEquals(1, recorder.messages.size());
        assertTrue(recorder.messages.get(0).contains("exit 255"), recorder.messages.get(0));
    }

    @Test
    void sshMissingIsItsOwnTypedRefusal() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new IOException(
                "Cannot run program \"ssh\": error=2, No such file or directory"));
        Logger logger = Logger.getLogger(BoxCheckout.class.getName());
        Recording recorder = new Recording();
        BoxCheckout.Outcome outcome;
        try {
            logger.addHandler(recorder);
            outcome = new BoxCheckout(runner).prepare(
                    HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache());
        } finally {
            logger.removeHandler(recorder);
        }

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertTrue(refused.reason().contains("is not installed"), refused.reason());
        assertFalse(refused.reason().contains("exit"), "not installed is not a failed run: " + refused.reason());
        assertTrue(recorder.messages.get(0).contains("is not installed"));
    }

    @Test
    void aCachedCloneSkipsDiscoveryEntirely() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, BoxCheckout.EXCLUDE_LINE + "\n", ""));
        FakeCache cache = new FakeCache(CLONE);

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), cache);

        assertTrue(outcome instanceof BoxCheckout.Outcome.Ready);
        assertEquals(1, runner.argvRuns.size(), "only the exclude read runs: " + runner.argvRuns);
        assertNull(cache.stored, "a cached clone is trusted, never re-discovered or re-stored");
    }

    // ------------------------------------------------------------------
    // The exclude step: listed once, idempotently
    // ------------------------------------------------------------------

    @Test
    void theExcludeLineIsAppendedOnceOnly() throws Exception {
        FakeRunner runner = new FakeRunner();
        // First prepare: the line is not there yet — one append.
        runner.replies.add(new ProcessResult(0, "# template\n", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        FakeCache cache = new FakeCache(CLONE);
        BoxCheckout box = new BoxCheckout(runner);

        box.prepare(HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), cache);

        assertEquals(ssh("'sh' '-c' 'echo \"$1\" >> \"$2\"' 'drydock' '"
                + BoxCheckout.EXCLUDE_LINE + "' '" + CLONE + "/.git/info/exclude'"),
                runner.argvRuns.get(1));
        assertEquals(BoxCheckout.EXCLUDE_TIMEOUT, runner.optionsRuns.get(1).timeout());

        // Second prepare: the line is there — no append, only the read.
        runner.replies.add(new ProcessResult(0, "# template\n" + BoxCheckout.EXCLUDE_LINE + "\n", ""));
        box.prepare(HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), cache);

        long appends = runner.argvRuns.stream()
                .filter(argv -> argv.get(argv.size() - 1).contains(">> \"$2\""))
                .count();
        assertEquals(1, appends, "the append runs only when the line is absent");
        assertEquals(3, runner.argvRuns.size(), () -> runner.argvRuns.toString());
    }

    @Test
    void aMissingExcludeFileIsRecreatedByTheAppend() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(1, "", "cat: " + CLONE
                + "/.git/info/exclude: No such file or directory\n"));
        runner.replies.add(new ProcessResult(0, "", ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache(CLONE));

        assertTrue(outcome instanceof BoxCheckout.Outcome.Ready,
                "a template-less clone is not a failure — the append recreates the file");
        assertEquals(2, runner.argvRuns.size());
    }

    @Test
    void aFailedExcludeReadRefusesWithTheStep() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(1, "", "cat: permission denied\n"));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN, new BoxCheckout.Work.MainCheckout(), new FakeCache(CLONE));

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertTrue(refused.reason().contains(".git/info/exclude"), refused.reason());
        assertTrue(refused.reason().contains("exit 1"), refused.reason());
    }

    // ------------------------------------------------------------------
    // NEW_WORKTREE: the pinned git recipe
    // ------------------------------------------------------------------

    @Test
    void newWorktreeRunsThePinnedRecipeWithItsOwnTimeouts() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, BoxCheckout.EXCLUDE_LINE + "\n", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        runner.replies.add(new ProcessResult(0, "", ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN,
                new BoxCheckout.Work.NewWorktree("feature-x", Optional.of("origin/main")),
                new FakeCache(CLONE));

        BoxCheckout.Outcome.Ready ready = (BoxCheckout.Outcome.Ready) outcome;
        assertEquals(CLONE, ready.clonePath());
        assertEquals(CLONE + "/" + BoxCheckout.WORKTREES_DIR + "/feature-x", ready.workingPath());

        assertEquals(ssh("'git' '-C' '" + CLONE + "' 'fetch' '--quiet' 'origin'"),
                runner.argvRuns.get(1));
        assertEquals(Duration.ofMinutes(3), runner.optionsRuns.get(1).timeout());
        assertEquals(ssh("'git' '-C' '" + CLONE + "' 'worktree' 'add' '-b' 'feature-x'"
                        + " '--end-of-options' '" + CLONE + "/" + BoxCheckout.WORKTREES_DIR
                        + "/feature-x' 'origin/main'"),
                runner.argvRuns.get(2));
        assertEquals(Duration.ofMinutes(3), runner.optionsRuns.get(2).timeout(),
                "the worktree add carries the plan's 3 min budget");

        for (Integer cap : runner.caps) {
            assertEquals(BoxCheckout.MAX_OUTPUT_BYTES, cap);
        }
    }

    @Test
    void newWorktreeWithoutAStartRefForksFromHead() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, BoxCheckout.EXCLUDE_LINE + "\n", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        runner.replies.add(new ProcessResult(0, "", ""));

        new BoxCheckout(runner).prepare(HOST, ORIGIN,
                new BoxCheckout.Work.NewWorktree("feature-x", Optional.empty()),
                new FakeCache(CLONE));

        assertEquals(ssh("'git' '-C' '" + CLONE + "' 'worktree' 'add' '-b' 'feature-x'"
                        + " '--end-of-options' '" + CLONE + "/" + BoxCheckout.WORKTREES_DIR + "/feature-x'"),
                runner.argvRuns.get(2));
    }

    @Test
    void aBranchRefusedByTheRulesRefusesBeforeAnyGitRun() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, BoxCheckout.EXCLUDE_LINE + "\n", ""));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN,
                new BoxCheckout.Work.NewWorktree("origin/main", Optional.empty()),
                new FakeCache(CLONE));

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertEquals("A branch by that name would shadow the remote 'origin'.", refused.reason());
        assertEquals(1, runner.argvRuns.size(),
                "the branch check precedes every git run — no fetch, no worktree add");
    }

    @Test
    void aFailedFetchRefusesWithTheStepAndItsExcerpt() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, BoxCheckout.EXCLUDE_LINE + "\n", ""));
        runner.replies.add(new ProcessResult(128, "", "fatal: could not read from remote repository\n"));
        Logger logger = Logger.getLogger(BoxCheckout.class.getName());
        Recording recorder = new Recording();
        BoxCheckout.Outcome outcome;
        try {
            logger.addHandler(recorder);
            outcome = new BoxCheckout(runner).prepare(
                    HOST, ORIGIN,
                    new BoxCheckout.Work.NewWorktree("feature-x", Optional.empty()),
                    new FakeCache(CLONE));
        } finally {
            logger.removeHandler(recorder);
        }

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertTrue(refused.reason().contains("fetching origin in the box clone failed"), refused.reason());
        assertTrue(refused.reason().contains("could not read from remote"), refused.reason());
        assertEquals(1, recorder.messages.size());
        assertEquals(2, runner.argvRuns.size(), "the worktree add never runs after a failed fetch");
    }

    @Test
    void aWorktreeTimeoutRefusesWithTheStep() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, BoxCheckout.EXCLUDE_LINE + "\n", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        runner.replies.add(new ProcessTimeoutException(List.of("ssh"), Duration.ofMinutes(3)));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN,
                new BoxCheckout.Work.NewWorktree("feature-x", Optional.empty()),
                new FakeCache(CLONE));

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertTrue(refused.reason().contains("creating the box-side worktree"), refused.reason());
        assertTrue(refused.reason().contains("timed out after 180 s"), refused.reason());
    }

    @Test
    void aFailedWorktreeAddRefusesWithTheStep() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, BoxCheckout.EXCLUDE_LINE + "\n", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        runner.replies.add(new ProcessResult(128, "", "fatal: 'feature-x' already exists\n"));

        BoxCheckout.Outcome outcome = new BoxCheckout(runner).prepare(
                HOST, ORIGIN,
                new BoxCheckout.Work.NewWorktree("feature-x", Optional.empty()),
                new FakeCache(CLONE));

        BoxCheckout.Outcome.Refused refused = (BoxCheckout.Outcome.Refused) outcome;
        assertTrue(refused.reason().contains("creating the box-side worktree"), refused.reason());
        assertTrue(refused.reason().contains("already exists"), refused.reason());
    }

    // ------------------------------------------------------------------
    // INV-5: every run is an ssh argument list with -- before the host
    // ------------------------------------------------------------------

    @Test
    void everyRunIsAnSshArgumentListWithTheHostGuarded() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.replies.add(new ProcessResult(0, GIT_DIR + "\n", ""));
        runner.replies.add(new ProcessResult(0, "https://github.com/DataDog/profiling-backend.git\n", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        runner.replies.add(new ProcessResult(0, "", ""));
        runner.replies.add(new ProcessResult(0, "", ""));

        new BoxCheckout(runner).prepare(HOST, ORIGIN,
                new BoxCheckout.Work.NewWorktree("feature-x", Optional.of("origin/main")),
                new FakeCache());

        assertFalse(runner.argvRuns.isEmpty());
        for (List<String> argv : runner.argvRuns) {
            assertEquals("ssh", argv.get(0), "the only tool spawned is the batch ssh seam: " + argv);
            int dashDash = argv.indexOf("--");
            assertEquals(HOST, argv.get(dashDash + 1),
                    "-- immediately before the host (INV-6): " + argv);
            assertFalse(argv.contains("-c"), "never a local shell -c: " + argv);
        }
    }
}
