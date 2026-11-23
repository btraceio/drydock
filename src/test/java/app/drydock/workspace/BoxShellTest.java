package app.drydock.workspace;

import app.drydock.domain.ManagedSessionId;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxShellTest {

    private static final String HOST = "workspace-drydock-x";
    private static final ManagedSessionId ID = ManagedSessionId.of("3f1a2b3c-1234-4d5e-9f0a-bcdef0123456");

    private static final List<String> BATCH_OPTIONS = List.of(
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=3",
            "-o", "ServerAliveCountMax=2");

    /** A fake CommandRunner: records every run and replies with a canned outcome. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> argvRuns = new ArrayList<>();
        final List<ProcessRunner.Options> optionsRuns = new ArrayList<>();
        final List<Integer> caps = new ArrayList<>();
        ProcessResult reply = new ProcessResult(0, "", "");
        ProcessTimeoutException timeoutFailure;
        IOException ioFailure;

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            argvRuns.add(List.copyOf(argv));
            optionsRuns.add(options);
            caps.add(maxOutputBytes);
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (timeoutFailure != null) {
                throw timeoutFailure;
            }
            return reply;
        }
    }

    /** Captures the JUL records BoxShell's System.Logger emits. */
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

    // ------------------------------------------------------------------
    // Argv construction per operation, and the timeout each one carries
    // ------------------------------------------------------------------

    @Test
    void probeIsTheBatchSshNoOpOnA10sBudget() throws Exception {
        FakeRunner runner = new FakeRunner();
        new BoxShell(runner).probe(HOST);

        List<String> expected = new ArrayList<>();
        expected.add("ssh");
        expected.addAll(BATCH_OPTIONS);
        expected.add("--");
        expected.add(HOST);
        expected.add("'true'");
        assertEquals(expected, runner.argvRuns.get(0));
        assertEquals(Duration.ofSeconds(10), runner.optionsRuns.get(0).timeout());
        assertEquals(BoxShell.MAX_OUTPUT_BYTES, runner.caps.get(0));
    }

    @Test
    void hasSessionProbesTheNamedSessionOnA10sBudget() throws Exception {
        FakeRunner runner = new FakeRunner();
        new BoxShell(runner).hasSession(HOST, ID);

        List<String> expected = new ArrayList<>();
        expected.add("ssh");
        expected.addAll(BATCH_OPTIONS);
        expected.add("--");
        expected.add(HOST);
        expected.add("'tmux' 'has-session' '-t' 'drydock-3f1a2b3c-1234-4d5e-9f0a-bcdef0123456'");
        assertEquals(expected, runner.argvRuns.get(0));
        assertEquals(Duration.ofSeconds(10), runner.optionsRuns.get(0).timeout());
    }

    @Test
    void killSessionTargetsTheNamedSessionOnA10sBudget() throws Exception {
        FakeRunner runner = new FakeRunner();
        new BoxShell(runner).killSession(HOST, ID);

        List<String> expected = new ArrayList<>();
        expected.add("ssh");
        expected.addAll(BATCH_OPTIONS);
        expected.add("--");
        expected.add(HOST);
        expected.add("'tmux' 'kill-session' '-t' 'drydock-3f1a2b3c-1234-4d5e-9f0a-bcdef0123456'");
        assertEquals(expected, runner.argvRuns.get(0));
        assertEquals(Duration.ofSeconds(10), runner.optionsRuns.get(0).timeout());
    }

    @Test
    void tmuxLsCarriesTheCallersOwnTimeout() throws Exception {
        FakeRunner runner = new FakeRunner();
        new BoxShell(runner).tmuxLs(HOST, Duration.ofSeconds(20));

        List<String> expected = new ArrayList<>();
        expected.add("ssh");
        expected.addAll(BATCH_OPTIONS);
        expected.add("--");
        expected.add(HOST);
        expected.add("'tmux' 'ls' '-F' '#{session_name}'");
        assertEquals(expected, runner.argvRuns.get(0));
        assertEquals(Duration.ofSeconds(20), runner.optionsRuns.get(0).timeout(),
                "tmux ls is sized inside the revalidation pass's 30 s bound, never a class default");
    }

    @Test
    void copyRunsTheBuilderScpArgvWithTheCallersTimeout() throws Exception {
        FakeRunner runner = new FakeRunner();
        BoxShell.BoxResult result = new BoxShell(runner)
                .copy(Path.of("/tmp/omlx_proxy"), HOST, "omlx_proxy", Duration.ofMinutes(2));

        assertInstanceOf(BoxShell.BoxResult.Ok.class, result);
        assertEquals(List.of("scp", "--", "/tmp/omlx_proxy", HOST + ":omlx_proxy"),
                runner.argvRuns.get(0));
        assertEquals(Duration.ofMinutes(2), runner.optionsRuns.get(0).timeout());
    }

    // ------------------------------------------------------------------
    // The five result classes: typed, distinct, and distinctly logged
    // ------------------------------------------------------------------

    @Test
    void allFiveResultClassesAreDistinctWithDistinctWarningText() throws Exception {
        FakeRunner runner = new FakeRunner();
        BoxShell shell = new BoxShell(runner);
        List<BoxShell.BoxResult> results = new ArrayList<>();

        Logger logger = Logger.getLogger(BoxShell.class.getName());
        Recording recorder = new Recording();
        logger.addHandler(recorder);
        try {
            runner.reply = new ProcessResult(0, "ok-out", "");
            results.add(shell.probe(HOST));

            runner.reply = new ProcessResult(1, "", "remote boom\nremote stack");
            results.add(shell.probe(HOST));

            runner.reply = new ProcessResult(255, "", "ssh: connect timed out");
            results.add(shell.probe(HOST));

            runner.reply = null;
            runner.timeoutFailure = new ProcessTimeoutException(List.of("ssh"), Duration.ofSeconds(10));
            results.add(shell.probe(HOST));

            runner.timeoutFailure = null;
            runner.ioFailure = new IOException("Cannot run program \"ssh\": error=2, No such file or directory");
            results.add(shell.probe(HOST));
        } finally {
            logger.removeHandler(recorder);
        }

        // Five distinct result classes…
        Set<Class<?>> classes = new HashSet<>();
        for (BoxShell.BoxResult result : results) {
            classes.add(result.getClass());
        }
        assertEquals(5, classes.size(), "every failure mode is its own type, never conflated");

        // …each with a describe() line distinct from every other.
        Set<String> described = new HashSet<>();
        for (BoxShell.BoxResult result : results) {
            described.add(result.describe());
        }
        assertEquals(5, described.size());

        // One WARNING per failure, nothing for Ok, all pairwise distinct.
        assertEquals(4, recorder.messages.size());
        assertEquals(4, new HashSet<>(recorder.messages).size());
        assertTrue(recorder.messages.stream().noneMatch(m -> m.contains("succeeded")),
                "a successful probe logs nothing");

        BoxShell.BoxResult.Ok ok = assertInstanceOf(BoxShell.BoxResult.Ok.class, results.get(0));
        assertEquals("ok-out", ok.stdout());

        BoxShell.BoxResult.RemoteFailed failed =
                assertInstanceOf(BoxShell.BoxResult.RemoteFailed.class, results.get(1));
        assertEquals(1, failed.exitCode());
        assertTrue(failed.excerpt().contains("remote boom"), failed.excerpt());
        assertTrue(recorder.messages.get(0).contains("exit 1"), recorder.messages.get(0));

        BoxShell.BoxResult.Unreachable unreachable =
                assertInstanceOf(BoxShell.BoxResult.Unreachable.class, results.get(2));
        assertTrue(unreachable.describe().contains("run `workspaces ssh-config`"),
                "UNREACHABLE text surfaces the ssh-config hint (ASM-9): " + unreachable.describe());
        assertTrue(recorder.messages.get(1).contains("exit 255"), recorder.messages.get(1));

        BoxShell.BoxResult.Timeout timeout =
                assertInstanceOf(BoxShell.BoxResult.Timeout.class, results.get(3));
        assertEquals(Duration.ofSeconds(10), timeout.timeout());
        assertTrue(recorder.messages.get(2).contains("timed out"), recorder.messages.get(2));

        BoxShell.BoxResult.SshNotInstalled missing =
                assertInstanceOf(BoxShell.BoxResult.SshNotInstalled.class, results.get(4));
        assertEquals("ssh", missing.tool());
        assertTrue(recorder.messages.get(3).contains("is not installed"), recorder.messages.get(3));
    }

    @Test
    void anOversizedRunIsAFailedRunNotAMissingTool() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.ioFailure = new IOException("Process output exceeded " + BoxShell.MAX_OUTPUT_BYTES + " bytes");
        BoxShell.BoxResult result = new BoxShell(runner).probe(HOST);

        BoxShell.BoxResult.RemoteFailed failed =
                assertInstanceOf(BoxShell.BoxResult.RemoteFailed.class, result);
        assertTrue(failed.excerpt().contains("exceeded"), failed.excerpt());
    }

    // ------------------------------------------------------------------
    // Argument lists, never shells (INV-5)
    // ------------------------------------------------------------------

    @Test
    void everyOperationPassesAnArgumentListNeverAShellString() throws Exception {
        FakeRunner runner = new FakeRunner();
        BoxShell shell = new BoxShell(runner);
        shell.probe(HOST);
        shell.hasSession(HOST, ID);
        shell.killSession(HOST, ID);
        shell.tmuxLs(HOST, Duration.ofSeconds(20));
        shell.copy(Path.of("/tmp/omlx_proxy"), HOST, "omlx_proxy", Duration.ofMinutes(2));

        assertFalse(runner.argvRuns.isEmpty());
        for (List<String> argv : runner.argvRuns) {
            assertTrue(argv.get(0).equals("ssh") || argv.get(0).equals("scp"), argv.toString());
            assertFalse(argv.contains("-c"), "never a shell -c: " + argv);
            assertFalse(argv.contains("sh") || argv.contains("bash") || argv.contains("zsh"),
                    "no login shell ever appears as the runner: " + argv);
            assertFalse(argv.stream().anyMatch(a -> a.startsWith("|") || a.contains("; rm ")),
                    argv.toString());
            if (argv.get(0).equals("ssh")) {
                int dashDash = argv.indexOf("--");
                assertEquals(HOST, argv.get(dashDash + 1),
                        "-- immediately before the host, the host never an option");
            }
        }
    }

    // ------------------------------------------------------------------
    // has-session classification (the exact liveness signal)
    // ------------------------------------------------------------------

    @Test
    void hasSessionExit0MeansAlive() throws Exception {
        FakeRunner runner = new FakeRunner();
        assertSame(BoxShell.Liveness.ALIVE, new BoxShell(runner).hasSession(HOST, ID));
    }

    @Test
    void hasSessionSessionGoneMarkersMeanDeadAndLogNothing() throws Exception {
        List<String> goneForms = List.of(
                "can't find session: drydock-" + ID,
                "no server running on /tmp/tmux-501/default",
                "error connecting to /tmp/tmux-501/default: No such file or directory");
        for (String stderr : goneForms) {
            FakeRunner runner = new FakeRunner();
            runner.reply = new ProcessResult(1, "", stderr);
            Logger logger = Logger.getLogger(BoxShell.class.getName());
            Recording recorder = new Recording();
            logger.addHandler(recorder);
            BoxShell.Liveness verdict;
            try {
                verdict = new BoxShell(runner).hasSession(HOST, ID);
            } finally {
                logger.removeHandler(recorder);
            }
            assertSame(BoxShell.Liveness.DEAD, verdict, stderr);
            assertTrue(recorder.messages.isEmpty(),
                    "DEAD is a valid answer from a successful probe, not a warning: " + recorder.messages);
        }
    }

    @Test
    void hasSessionEverythingElseMeansUnknownWithTheReason() throws Exception {
        record ProbeCase(String description, FakeRunner runner) {
        }
        List<ProbeCase> cases = new ArrayList<>();
        FakeRunner weird = new FakeRunner();
        weird.reply = new ProcessResult(1, "", "some unrecognized tmux error");
        cases.add(new ProbeCase("exit 1 without a gone marker", weird));
        FakeRunner crashed = new FakeRunner();
        crashed.reply = new ProcessResult(2, "", "internal error");
        cases.add(new ProbeCase("exit 2", crashed));
        FakeRunner unreachable = new FakeRunner();
        unreachable.reply = new ProcessResult(255, "", "ssh: connect timed out");
        cases.add(new ProbeCase("ssh exit 255", unreachable));
        FakeRunner hung = new FakeRunner();
        hung.timeoutFailure = new ProcessTimeoutException(List.of("ssh"), Duration.ofSeconds(10));
        cases.add(new ProbeCase("timeout", hung));
        FakeRunner missing = new FakeRunner();
        missing.ioFailure = new IOException("Cannot run program \"ssh\": error=2");
        cases.add(new ProbeCase("ssh not installed", missing));

        for (ProbeCase probeCase : cases) {
            BoxShell.Liveness verdict = new BoxShell(probeCase.runner()).hasSession(HOST, ID);
            BoxShell.Liveness.Unknown unknown = assertInstanceOf(
                    BoxShell.Liveness.Unknown.class, verdict, probeCase.description());
            assertFalse(unknown.reason().isBlank(), probeCase.description());
        }

        BoxShell.Liveness.Unknown unreachableReason = assertInstanceOf(
                BoxShell.Liveness.Unknown.class, new BoxShell(unreachable).hasSession(HOST, ID));
        assertTrue(unreachableReason.reason().contains("ssh: connect timed out"),
                unreachableReason.reason());
        assertTrue(unreachableReason.reason().contains(BoxShell.SSH_CONFIG_HINT),
                "the UNREACHABLE reason surfaces the hint: " + unreachableReason.reason());
    }

    @Test
    void killSessionKeepsTheRawResultForTheStopCallerToRead() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(1, "", "can't find session: drydock-" + ID);
        BoxShell.BoxResult result = new BoxShell(runner).killSession(HOST, ID);
        BoxShell.BoxResult.RemoteFailed failed =
                assertInstanceOf(BoxShell.BoxResult.RemoteFailed.class, result);
        assertEquals(1, failed.exitCode());
        assertTrue(failed.excerpt().contains("can't find session"),
                "the Stop/Forget caller reads this form itself (D1): " + failed.excerpt());
    }

    // ------------------------------------------------------------------
    // tmux ls through the seam
    // ------------------------------------------------------------------

    @Test
    void tmuxLsParsesOkThroughTmuxListingAndMapsRunFailuresToUnknown() throws Exception {
        String name = "drydock-3f1a2b3c-1234-4d5e-9f0a-bcdef0123456";
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, name + "\nmain\n", "");
        TmuxListing.Result listing = new BoxShell(runner).tmuxLs(HOST, Duration.ofSeconds(20));
        TmuxListing.Sessions sessions = assertInstanceOf(TmuxListing.Sessions.class, listing);
        assertEquals(Set.of(name), sessions.names());

        runner.reply = new ProcessResult(255, "", "ssh: connect timed out");
        TmuxListing.Unknown unreachable = assertInstanceOf(TmuxListing.Unknown.class,
                new BoxShell(runner).tmuxLs(HOST, Duration.ofSeconds(20)));
        assertTrue(unreachable.reason().contains("exit 255"), unreachable.reason());

        runner.reply = null;
        runner.timeoutFailure = new ProcessTimeoutException(List.of("ssh"), Duration.ofSeconds(20));
        TmuxListing.Unknown timedOut = assertInstanceOf(TmuxListing.Unknown.class,
                new BoxShell(runner).tmuxLs(HOST, Duration.ofSeconds(20)));
        assertTrue(timedOut.reason().contains("timed out"), timedOut.reason());
    }

    @Test
    void tmuxLsRecognizesTheNoServerAnswerThroughTheSshExitCode() throws Exception {
        // ssh forwards the remote exit status: tmux's exit-1 no-server answer
        // reaches the seam as ssh exit 1 with tmux's stderr, and stays the
        // valid empty set — never an error, never an empty-result conflation.
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(1, "", "no server running on /tmp/tmux-501/default");
        TmuxListing.Result listing = new BoxShell(runner).tmuxLs(HOST, Duration.ofSeconds(20));
        TmuxListing.Sessions sessions = assertInstanceOf(TmuxListing.Sessions.class, listing);
        assertEquals(Set.of(), sessions.names());
    }

    @Test
    void aFailedBoxCallIsNeverSilent() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(255, "", "ssh: connect to host ... timed out");
        Logger logger = Logger.getLogger(BoxShell.class.getName());
        Recording recorder = new Recording();
        logger.addHandler(recorder);
        try {
            new BoxShell(runner).probe(HOST);
        } finally {
            logger.removeHandler(recorder);
        }
        assertEquals(1, recorder.messages.size());
        assertTrue(recorder.messages.get(0).contains("run `workspaces ssh-config`"),
                recorder.messages.get(0));
    }
}
