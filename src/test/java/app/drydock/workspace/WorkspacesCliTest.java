package app.drydock.workspace;

import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkspacesCli on a fake runner: the exact list/create argv, the observed
 * table format (header, footnote, mixed statuses), the STOPPED/OTHER/ABSENT
 * classification with `8` as the only stopped code, the distinct typed
 * failure outcomes each logged as its own WARNING (AC-17, AC-20 halves),
 * and the bounded-output refusals (INV-20). Nothing here spawns a child.
 */
class WorkspacesCliTest {

    /**
     * The table shape observed from the real CLI on 2026-11-12
     * (find-box-provisioning-facts, ASM-8): a NAME header whose STATUS
     * column sits at index 2, rows with fewer tokens than the header
     * (the DEVCONTAINER REPO column is one whitespace token in data
     * rows but two header words), and one `*` footnote line.
     */
    private static final String OBSERVED_TABLE = """
            NAME                  REGION      STATUS   DEVCONTAINER REPO   AGE    TTL*
            jb-refchain-nightly   us-east-1   8        ddoghq/dd-source    20d    159d
            jb-tester             us-east-1   2        ddoghq/dd-source    28d    151d
            jb                    us-east-1   8        ddoghq/dd-source    128d   51d
            * Maximum time-to-live for the workspace. Garbage collector may delete sooner if unused.
            """;

    private static final Path BINARY = Path.of("/opt/homebrew/bin/workspaces");

    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler recorder = new Handler() { // System.getLogger routes to JUL
        @Override public void publish(LogRecord record) {
            if (record.getLevel() == java.util.logging.Level.WARNING) {
                warnings.add(record);
            }
        }
        @Override public void flush() { }
        @Override public void close() { }
    };

    @BeforeEach
    void attachRecorder() {
        Logger.getLogger(WorkspacesCli.class.getName()).addHandler(recorder);
    }

    @AfterEach
    void detachRecorder() {
        Logger.getLogger(WorkspacesCli.class.getName()).removeHandler(recorder);
    }

    /** Records every call and replies with a canned result or throws the canned failure. */
    private static final class FakeRunner implements CommandRunner {
        final List<List<String>> argv = new ArrayList<>();
        final List<ProcessRunner.Options> options = new ArrayList<>();
        final List<Integer> caps = new ArrayList<>();
        ProcessResult reply = new ProcessResult(0, "", "");
        IOException ioFailure;
        ProcessTimeoutException timeoutFailure;
        boolean interrupted;

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            this.argv.add(List.copyOf(argv));
            this.options.add(options);
            this.caps.add(maxOutputBytes);
            if (interrupted) {
                throw new InterruptedException("cancelled");
            }
            if (timeoutFailure != null) {
                throw timeoutFailure;
            }
            if (ioFailure != null) {
                throw ioFailure;
            }
            return reply;
        }
    }

    /** A locator that always resolves to a fixed path, whatever the host has installed. */
    private static HostBinaryLocator binaryAt(String path) {
        return new HostBinaryLocator("workspaces", List.of()) {
            @Override protected Optional<Path> discover() {
                return Optional.of(Path.of(path));
            }
        };
    }

    /** A locator that never resolves, forcing "not installed" without touching the host. */
    private static HostBinaryLocator noBinary() {
        return new HostBinaryLocator("workspaces", List.of()) {
            @Override protected Optional<Path> discover() {
                return Optional.empty();
            }
        };
    }

    // --- list: the table parse (header, footnote skip, mixed statuses) ---

    @Test
    void listParsesTheObservedTableHeaderFootnoteAndMixedStatuses() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, OBSERVED_TABLE, "");
        WorkspacesCli.ListResult result = new WorkspacesCli(binaryAt(BINARY.toString()), runner).list();

        WorkspacesCli.Listed listed = assertInstanceOf(WorkspacesCli.Listed.class, result);
        assertEquals(Map.of(
                "jb-refchain-nightly", "8",
                "jb-tester", "2",
                "jb", "8"), listed.statusesByName(), "the footnote row is skipped, not parsed");
        assertEquals(List.of("jb-refchain-nightly", "jb-tester", "jb"),
                List.copyOf(listed.names()), "names keep the table order");
    }

    @Test
    void statusOfClassifiesEightAsStoppedOtherListedAsOtherAndUnlistedAsAbsent() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, OBSERVED_TABLE, "");
        WorkspacesCli.Listed listed = (WorkspacesCli.Listed)
                new WorkspacesCli(binaryAt(BINARY.toString()), runner).list();

        assertEquals(WorkspacesCli.BoxStatus.STOPPED, listed.statusOf("jb-refchain-nightly"));
        assertEquals(WorkspacesCli.BoxStatus.STOPPED, listed.statusOf("jb"));
        assertEquals(WorkspacesCli.BoxStatus.OTHER, listed.statusOf("jb-tester"));
        assertEquals(WorkspacesCli.BoxStatus.ABSENT, listed.statusOf("no-such-box"));
    }

    @Test
    void onlyTheExactStatusEightMeansStopped() throws Exception {
        String table = """
                NAME      REGION     STATUS   REPO   AGE
                stopped   us-east-1  8        r      1d
                running   us-east-1  2        r      1d
                starting  us-east-1  0        r      1d
                wordy     us-east-1  running  r      1d
                padded    us-east-1  08       r      1d
                """;
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, table, "");
        WorkspacesCli.Listed listed = (WorkspacesCli.Listed)
                new WorkspacesCli(binaryAt(BINARY.toString()), runner).list();

        assertEquals(WorkspacesCli.BoxStatus.STOPPED, listed.statusOf("stopped"),
                "the observed stopped code is the only one interpreted");
        assertEquals(WorkspacesCli.BoxStatus.OTHER, listed.statusOf("running"));
        assertEquals(WorkspacesCli.BoxStatus.OTHER, listed.statusOf("starting"));
        assertEquals(WorkspacesCli.BoxStatus.OTHER, listed.statusOf("wordy"));
        assertEquals(WorkspacesCli.BoxStatus.OTHER, listed.statusOf("padded"),
                "even a numeric near-miss is not stopped");
    }

    @Test
    void aHeaderOnlyTableIsAValidEmptyListing() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, """
                NAME   REGION   STATUS   AGE   TTL*
                * Maximum time-to-live for the workspace.
                """, "");
        WorkspacesCli.Listed listed = (WorkspacesCli.Listed)
                new WorkspacesCli(binaryAt(BINARY.toString()), runner).list();

        assertTrue(listed.names().isEmpty(), "a user with no boxes lists zero rows, not an error");
        assertEquals(WorkspacesCli.BoxStatus.ABSENT, listed.statusOf("anything"));
    }

    // --- list: argv and bounds ---

    @Test
    void listRunsTheExactArgvWithThirtySecondTimeoutAndBoundedCap() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, OBSERVED_TABLE, "");
        new WorkspacesCli(binaryAt(BINARY.toString()), runner).list();

        assertEquals(1, runner.argv.size());
        assertEquals(List.of(BINARY.toString(), "list", "--no-color"), runner.argv.get(0));
        ProcessRunner.Options options = runner.options.get(0);
        assertNull(options.workingDirectory(), "list inherits the plugin's cwd");
        assertEquals(Duration.ofSeconds(30), options.timeout());
        assertTrue(options.discardInput(), "list never waits on stdin");
        assertEquals(Map.of(), options.environment());
        assertEquals(256 * 1024, runner.caps.get(0));
    }

    // --- create: argv exactness (AC-17) ---

    @Test
    void createRunsTheExactArgvInTheRepositoryCwdWithTenMinuteTimeout(@TempDir Path repoRoot)
            throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, "Workspace created.\n", "");
        WorkspacesCli.CreateResult result = new WorkspacesCli(binaryAt(BINARY.toString()), runner)
                .create("drydock-profiling-rt-ai-plugins", "ddoghq/profiling-rt-ai-plugins", repoRoot);

        assertInstanceOf(WorkspacesCli.Created.class, result);
        assertEquals(1, runner.argv.size());
        assertEquals(List.of(BINARY.toString(), "create", "drydock-profiling-rt-ai-plugins",
                "-R", "ddoghq/profiling-rt-ai-plugins", "-s", "bash", "-y", "--no-color"),
                runner.argv.get(0));
        ProcessRunner.Options options = runner.options.get(0);
        assertEquals(repoRoot, options.workingDirectory(), "create derives the repo context from the cwd checkout");
        assertEquals(Duration.ofMinutes(10), options.timeout());
        assertTrue(options.discardInput(), "the -y flag is backed by a closed stdin, never a prompt");
        assertEquals(1024 * 1024, runner.caps.get(0));
    }

    @Test
    void createRejectsAnInvalidNameBeforeAnySpawn() {
        FakeRunner runner = new FakeRunner();
        WorkspacesCli cli = new WorkspacesCli(binaryAt(BINARY.toString()), runner);

        assertThrows(IllegalArgumentException.class, () -> cli.create("-n", "ddoghq/x", Path.of(".")),
                "an option-looking name can never reach the argv");
        assertThrows(IllegalArgumentException.class, () -> cli.create("UPPER", "ddoghq/x", Path.of(".")));
        assertThrows(IllegalArgumentException.class, () -> cli.create(null, "ddoghq/x", Path.of(".")));
        assertTrue(runner.argv.isEmpty(), "the guard fires before the runner is touched");
    }

    // --- distinct failure outcomes, each its own WARNING (AC-20 half, INV-9) ---

    @Test
    void listDistinguishesNotInstalledFailedTimedOutAndCapRefusedWithDistinctWarnings()
            throws Exception {
        // 1. Not installed: the binary is located nowhere, so nothing spawns at all.
        FakeRunner missing = new FakeRunner();
        assertInstanceOf(WorkspacesCli.NotInstalled.class,
                new WorkspacesCli(noBinary(), missing).list());
        assertTrue(missing.argv.isEmpty(), "no spawn is attempted when the CLI is missing");

        // 2. A spawn failure is the same not-installed family: the binary could not be launched.
        FakeRunner unlaunchable = new FakeRunner();
        unlaunchable.ioFailure = new IOException("Cannot run program \"workspaces\": No such file or directory");
        assertInstanceOf(WorkspacesCli.NotInstalled.class,
                new WorkspacesCli(binaryAt(BINARY.toString()), unlaunchable).list());

        // 3. Non-zero exit is a failure, never an empty listing.
        FakeRunner failing = new FakeRunner();
        failing.reply = new ProcessResult(3, "", "auth required: run workspaces login");
        WorkspacesCli.Failed failed = assertInstanceOf(WorkspacesCli.Failed.class,
                (WorkspacesCli.ListResult) new WorkspacesCli(binaryAt(BINARY.toString()), failing).list());
        assertEquals(3, failed.exitCode());
        assertTrue(failed.excerpt().contains("run workspaces login"), "the excerpt carries the stderr");

        // 4. A hang past the timeout is its own outcome.
        FakeRunner hanging = new FakeRunner();
        hanging.timeoutFailure = new ProcessTimeoutException(
                List.of(BINARY.toString(), "list"), Duration.ofSeconds(30));
        assertInstanceOf(WorkspacesCli.TimedOut.class,
                new WorkspacesCli(binaryAt(BINARY.toString()), hanging).list());

        // 5. Output beyond the cap is refused, never trusted or truncated.
        FakeRunner chatty = new FakeRunner();
        chatty.ioFailure = new IOException("Process output exceeded 262144 bytes");
        assertInstanceOf(WorkspacesCli.OutputTooLarge.class,
                new WorkspacesCli(binaryAt(BINARY.toString()), chatty).list());

        // Every outcome logged as its own distinct WARNING.
        Set<String> messages = warnings.stream().map(LogRecord::getMessage).collect(Collectors.toSet());
        assertEquals(5, warnings.size(), "one WARNING per outcome: " + messages);
        assertEquals(5, messages.size(), "each WARNING text is distinct: " + messages);
    }

    @Test
    void createDistinguishesNotInstalledFailedTimedOutAndCapRefused(@TempDir Path repoRoot)
            throws Exception {
        FakeRunner missing = new FakeRunner();
        assertInstanceOf(WorkspacesCli.NotInstalled.class,
                new WorkspacesCli(noBinary(), missing).create("drydock-x", "ddoghq/x", repoRoot));
        assertTrue(missing.argv.isEmpty());

        FakeRunner failing = new FakeRunner();
        failing.reply = new ProcessResult(1, "", "this repository is outside the allowed orgs");
        WorkspacesCli.Failed failed = assertInstanceOf(WorkspacesCli.Failed.class,
                (WorkspacesCli.CreateResult) new WorkspacesCli(binaryAt(BINARY.toString()), failing)
                        .create("drydock-x", "ddoghq/x", repoRoot));
        assertEquals(1, failed.exitCode());
        assertTrue(failed.excerpt().contains("outside the allowed orgs"));

        FakeRunner hanging = new FakeRunner();
        hanging.timeoutFailure = new ProcessTimeoutException(
                List.of(BINARY.toString(), "create"), Duration.ofMinutes(10));
        assertInstanceOf(WorkspacesCli.TimedOut.class,
                new WorkspacesCli(binaryAt(BINARY.toString()), hanging)
                        .create("drydock-x", "ddoghq/x", repoRoot));

        FakeRunner chatty = new FakeRunner();
        chatty.ioFailure = new IOException("Process output exceeded 1048576 bytes");
        assertInstanceOf(WorkspacesCli.OutputTooLarge.class,
                new WorkspacesCli(binaryAt(BINARY.toString()), chatty)
                        .create("drydock-x", "ddoghq/x", repoRoot));

        Set<String> messages = warnings.stream().map(LogRecord::getMessage).collect(Collectors.toSet());
        assertEquals(4, warnings.size(), "one WARNING per outcome: " + messages);
        assertEquals(4, messages.size(), "each WARNING text is distinct: " + messages);
    }

    // --- malformed input and bounded parsing (INV-20) ---

    @Test
    void malformedTablesAreTypedLoggedFailuresNeverEmptyListings() throws Exception {
        List<String> malformed = List.of(
                "",
                "This CLI does not print a table at all.",
                "NAME   REGION   AGE\njb   us-east-1   3d",
                "NAME   REGION   STATUS\njust-a-name");
        for (String stdout : malformed) {
            FakeRunner runner = new FakeRunner();
            runner.reply = new ProcessResult(0, stdout, "");
            WorkspacesCli.ListResult result = new WorkspacesCli(binaryAt(BINARY.toString()), runner).list();
            WorkspacesCli.Unparseable unparseable = assertInstanceOf(WorkspacesCli.Unparseable.class, result,
                    "malformed input must be typed, not an empty listing: " + stdout);
            assertTrue(!unparseable.reason().isEmpty());
        }
        assertEquals(malformed.size(), warnings.size(), "each malformed table logs its own WARNING");
    }

    @Test
    void aTableBeyondTheLineBoundIsRefused() throws Exception {
        StringBuilder table = new StringBuilder("NAME   REGION   STATUS   AGE\n");
        for (int i = 0; i < 5000; i++) {
            table.append("box-").append(i).append("   us-east-1   8   1d\n");
        }
        FakeRunner runner = new FakeRunner();
        runner.reply = new ProcessResult(0, table.toString(), "");
        assertInstanceOf(WorkspacesCli.Unparseable.class,
                new WorkspacesCli(binaryAt(BINARY.toString()), runner).list());
    }

    // --- cancellation is control flow, not a typed outcome ---

    @Test
    void interruptionPropagatesAsCancellation() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.interrupted = true;
        assertThrows(InterruptedException.class,
                () -> new WorkspacesCli(binaryAt(BINARY.toString()), runner).list());
        FakeRunner createRunner = new FakeRunner();
        createRunner.interrupted = true;
        assertThrows(InterruptedException.class,
                () -> new WorkspacesCli(binaryAt(BINARY.toString()), createRunner)
                        .create("drydock-x", "ddoghq/x", Path.of(".")));
    }

    // --- Listed is a defensive copy of the parsed map ---

    @Test
    void listedCopiesTheStatusMapItWasBuiltFrom() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("jb", "8");
        WorkspacesCli.Listed listed = new WorkspacesCli.Listed(source);
        source.put("sneaky", "2");
        assertEquals(Set.of("jb"), listed.names(), "later mutation of the source cannot reach the listing");
    }
}
