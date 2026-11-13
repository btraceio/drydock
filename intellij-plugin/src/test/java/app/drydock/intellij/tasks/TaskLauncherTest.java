package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.intellij.tasks.TaskLauncher.Steps;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.projects.SharedProject;
import app.drydock.workspace.BoxCheckout;
import app.drydock.workspace.CommandRunner;
import app.drydock.workspace.WorkspaceBoxNames;
import app.drydock.workspace.WorkspacesCli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLauncherTest {

    private static final Path MAIN = Path.of("/w/drydock");
    private static final Path FOLDER = Path.of("/wt/drydock-task");
    private static final Optional<SharedProject> REGISTERED =
            Optional.of(SharedProject.local(RepositoryId.newId(), MAIN, "drydock", Instant.EPOCH));

    private static Choice choice(SessionMode mode, WorkIn workIn, OpenIn openIn) {
        return new Choice("Task", Optional.empty(), AgentKind.CLAUDE, mode, workIn,
                workIn == WorkIn.MAIN_CHECKOUT ? "" : "task", "origin/main",
                workIn == WorkIn.MAIN_CHECKOUT ? MAIN : FOLDER, openIn, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    @Test
    void unregisteredProjectIsRegisteredFirst() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.OWN_WINDOW),
                Optional.empty(), MAIN, false, false);

        assertTrue(steps.registerFirst());
    }

    @Test
    void newWorktreeCreatesThenOpensNewWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, false, false);

        assertEquals(new Steps(false, true, FOLDER, OpenIn.OWN_WINDOW, false, false), steps);
    }

    @Test
    void thisWindowOnAnotherWorktreeHostsTheTask() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.NEW_WORKTREE, OpenIn.THIS_WINDOW),
                REGISTERED, MAIN, true, false);

        assertTrue(steps.hosted());
        assertFalse(steps.reuseOpenWindow());
    }

    @Test
    void thisWindowOnItsOwnWorktreeIsAPlainTab() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.MAIN_CHECKOUT, OpenIn.THIS_WINDOW),
                REGISTERED, MAIN, true, true);

        assertFalse(steps.hosted());
        assertFalse(steps.reuseOpenWindow());
    }

    @Test
    void itsOwnWindowOpensOrReusesAWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.EXISTING_WORKTREE, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, true, false);

        assertTrue(steps.reuseOpenWindow());
        assertFalse(steps.hosted());
    }

    @Test
    void mainCheckoutReusesOpenWindow() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.MAIN_CHECKOUT, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, true, false);

        assertEquals(new Steps(false, false, MAIN, OpenIn.OWN_WINDOW, true, false), steps);
    }

    @Test
    void existingWorktreeNeverCreates() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.WRITES, WorkIn.EXISTING_WORKTREE, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, false, false);

        assertFalse(steps.createWorktree());
        assertEquals(FOLDER, steps.workingDirectory());
    }

    @Test
    void readOnlyNeverCreates() {
        Steps steps = TaskLauncher.plan(choice(SessionMode.READ_ONLY, WorkIn.MAIN_CHECKOUT, OpenIn.OWN_WINDOW),
                REGISTERED, MAIN, false, false);

        assertFalse(steps.createWorktree());
        assertEquals(MAIN, steps.workingDirectory());
    }

    @Test
    void defaultStartPrefersTheRemoteDefaultBranch() {
        assertEquals("origin/main", TaskLauncher.defaultStart("main", List.of("main", "origin/main"),
                List.of("origin")));
        assertEquals("main", TaskLauncher.defaultStart("main", List.of("main"), List.of()));
    }

    @Test
    void aWindowThatDidNotOpenIsReportedWithItsFolder() {
        assertEquals("The worktree /wt/drydock-x is ready, but its window did not open. "
                        + "Open it with File › Open, or double-click the task in the Drydock tool window.",
                TaskLauncher.windowDidNotOpen(Path.of("/wt/drydock-x")));
    }

    // ---- the workspace-box remote branch (T20) ---------------------------------

    private static final Path BOX_MAIN = Path.of("/w/profiling-plugins");
    private static final String BOX_REPO = "profiling-plugins";
    private static final String DEFAULT_BOX = "drydock-profiling-plugins";
    private static final String BOX_HOST = "workspace-" + DEFAULT_BOX;
    private static final String DD_ORIGIN = "git@github.com:DataDog/profiling-plugins.git";

    /** One recorded CommandRunner call: the argv, the options, the output cap. */
    private record BoxCall(List<String> argv, ProcessRunner.Options options, int cap) {
    }

    /**
     * Wraps a scenario runner, recording every call: the launch chain runs
     * entirely on fakes (no child is ever spawned), and every command a
     * scenario did not plan for fails the test instead of being misread.
     */
    private static final class RecordingRunner implements CommandRunner {

        private CommandRunner scenario;
        final List<BoxCall> calls = new ArrayList<>();

        RecordingRunner(CommandRunner scenario) {
            this.scenario = scenario;
        }

        void setScenario(CommandRunner scenario) {
            this.scenario = scenario;
        }

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            calls.add(new BoxCall(List.copyOf(argv), options, maxOutputBytes));
            return scenario.run(argv, options, maxOutputBytes);
        }

        /** The workspaces subcommands seen, in order: only list and create may ever appear. */
        List<String> workspacesSubcommands() {
            List<String> subcommands = new ArrayList<>();
            for (BoxCall call : calls) {
                if (call.argv().get(0).endsWith("workspaces")) {
                    subcommands.add(call.argv().get(1));
                }
            }
            return subcommands;
        }

        BoxCall workspacesCreate() {
            return calls.stream().filter(call -> call.argv().contains("create")).findFirst()
                    .orElseThrow(() -> new AssertionError("no workspaces create was issued"));
        }
    }

    /** The mapping fake: the `.box`, `.usedBoxes` and `.clone` keys as in-memory records. */
    private static final class RecordingMapping implements TaskLauncher.BoxMapping {

        Optional<String> box;
        Optional<String> clone = Optional.empty();
        String cloneBox; // the box the cached clone path was recorded for
        final Set<String> usedNames = new LinkedHashSet<>();
        final List<String> recordedBoxes = new ArrayList<>();
        final List<String> storedClones = new ArrayList<>();

        RecordingMapping(Optional<String> box) {
            this.box = box;
        }

        /** Seeds the used-names history with a name the mapping pointed at before. */
        void boxUsed(String name) {
            usedNames.add(name);
        }

        /** Pre-seeds the clone cache as recorded for {@code boxName} (a discovery or dialog override). */
        void cachedClone(String boxName, String path) {
            clone = Optional.of(path);
            cloneBox = boxName;
        }

        @Override
        public Optional<String> box() {
            return box;
        }

        @Override
        public void recordBox(String boxName) {
            recordedBoxes.add(boxName);
            box = Optional.of(boxName);
            usedNames.add(boxName); // the settings store records the history on the same write
        }

        @Override
        public Set<String> usedBoxes() {
            return new HashSet<>(usedNames);
        }

        @Override
        public BoxCheckout.CloneCache cloneCache(String boxName) {
            return new BoxCheckout.CloneCache() {
                @Override
                public Optional<String> cached() {
                    // Box-scoped like the settings store: a path recorded for
                    // another box generation is not served.
                    return boxName.equals(cloneBox) ? clone : Optional.empty();
                }

                @Override
                public void store(String clonePath) {
                    storedClones.add(clonePath);
                    RecordingMapping.this.clone = Optional.of(clonePath);
                    RecordingMapping.this.cloneBox = boxName;
                }
            };
        }
    }

    /** The provisioning-claim fake: the acquires, the releases, and a switch to deny. */
    private static final class RecordingGuard implements TaskLauncher.ProvisioningGuard {

        final List<Path> acquired = new ArrayList<>();
        final List<Path> released = new ArrayList<>();
        boolean allow = true;

        @Override
        public boolean acquire(Path mainRoot) {
            acquired.add(mainRoot);
            return allow;
        }

        @Override
        public void release(Path mainRoot) {
            released.add(mainRoot);
        }
    }

    /** The progress fake: the steps set, and the clear every exit owes. */
    private static final class RecordingProgress implements TaskLauncher.ProgressSink {

        final List<String> steps = new ArrayList<>();
        int clears;

        @Override
        public void step(String text) {
            steps.add(text);
        }

        @Override
        public void clear() {
            clears++;
        }
    }

    /**
     * One remote-launch scenario's seams: the scenario runner wrapped in a
     * recorder, an in-memory mapping, recorded progress, confirmations,
     * refusals and bootstrap calls — the whole box chain on fakes.
     */
    private static final class BoxHarness {

        final RecordingRunner runner;
        final RecordingMapping mapping;
        final RecordingProgress progress = new RecordingProgress();
        final RecordingGuard guard = new RecordingGuard();
        final List<String> confirmations = new ArrayList<>();
        final List<String> refusals = new ArrayList<>();
        final List<String> bootstrappedHosts = new ArrayList<>();
        boolean confirmAnswer = true;
        boolean bootstrapFails;
        String bootstrapFailure = "the box's ddtool vault check failed (exit 1)";

        BoxHarness(CommandRunner scenario, Optional<String> mappedBox) {
            this.runner = new RecordingRunner(scenario);
            this.mapping = new RecordingMapping(mappedBox);
        }

        TaskLauncher.RemoteBoxDeps deps(boolean boxAlreadyBootstrapped) {
            return new TaskLauncher.RemoteBoxDeps(runner,
                    new WorkspacesCli(boxBinary(), runner),
                    host -> {
                        bootstrappedHosts.add(host);
                        if (bootstrapFails) {
                            throw new TaskLauncher.BootstrapRefusal(bootstrapFailure);
                        }
                    },
                    mapping,
                    guard,
                    message -> {
                        confirmations.add(message);
                        return confirmAnswer;
                    },
                    refusals::add, progress, boxAlreadyBootstrapped);
        }
    }

    /** A `workspaces list` table reply: the header, the given rows, the TTL footnote. */
    private static ProcessResult listing(String... rows) {
        StringBuilder out = new StringBuilder("NAME REGION STATUS\n");
        for (String row : rows) {
            out.append(row).append('\n');
        }
        out.append("* Boxes idle for two weeks are stopped\n");
        return new ProcessResult(0, out.toString(), "");
    }

    /** A locator that always resolves the workspaces binary, so replies decide the outcomes. */
    private static HostBinaryLocator boxBinary() {
        return new HostBinaryLocator("workspaces", List.of()) {
            @Override
            protected Optional<Path> discover() {
                return Optional.of(Path.of("/opt/homebrew/bin/workspaces"));
            }
        };
    }

    /** A locator that never resolves: the CLI is not installed. */
    private static HostBinaryLocator missingBinary() {
        return new HostBinaryLocator("workspaces", List.of()) {
            @Override
            protected Optional<Path> discover() {
                return Optional.empty();
            }
        };
    }

    private static TaskLauncher.RemoteBoxRequest mainCheckoutRequest() {
        return new TaskLauncher.RemoteBoxRequest(BOX_MAIN, BOX_REPO, new BoxCheckout.Work.MainCheckout());
    }

    /** A scenario whose box answers the probe; {@code boxStatus} null lists no boxes. */
    private static CommandRunner reachableScenario(String boxStatus) {
        return (argv, ignoredOptions, ignoredCap) -> {
            if ("git".equals(argv.get(0))) {
                return new ProcessResult(0, DD_ORIGIN, "");
            }
            if ("ssh".equals(argv.get(0))) {
                String remote = argv.get(argv.size() - 1);
                if (remote.equals("'true'")) {
                    return new ProcessResult(0, "", "");
                }
                if (remote.contains("get-url")) {
                    return new ProcessResult(0, DD_ORIGIN, "");
                }
                if (remote.contains("'cat'")) {
                    return new ProcessResult(0, ".drydock-worktrees/\n", "");
                }
                throw new IllegalStateException("unexpected ssh in this scenario: " + remote);
            }
            if (argv.contains("list")) {
                return boxStatus == null ? listing() : listing(DEFAULT_BOX + " us1 " + boxStatus);
            }
            throw new IllegalStateException("unexpected command: " + argv);
        };
    }

    /** A scenario whose box is unreachable over ssh, lists the given rows, and creates on
      * demand; a fresh box's own clone is discovered at /home/dev/profiling-plugins. */
    private static CommandRunner absentBoxScenario(String... rows) {
        return (argv, ignoredOptions, ignoredCap) -> {
            if ("git".equals(argv.get(0))) {
                return new ProcessResult(0, DD_ORIGIN, "");
            }
            if ("ssh".equals(argv.get(0))) {
                String remote = argv.get(argv.size() - 1);
                if (remote.equals("'true'")) {
                    return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
                }
                if (remote.contains("find")) {
                    return new ProcessResult(0, "/home/dev/profiling-plugins/.git\n", "");
                }
                if (remote.contains("get-url")) {
                    return new ProcessResult(0, DD_ORIGIN, "");
                }
                if (remote.contains("'cat'")) {
                    return new ProcessResult(0, ".drydock-worktrees/\n", "");
                }
                if (remote.contains("echo")) {
                    return new ProcessResult(0, "", "");
                }
                throw new IllegalStateException("unexpected ssh in this scenario: " + remote);
            }
            if (argv.contains("list")) {
                return listing(rows);
            }
            if (argv.contains("create")) {
                return new ProcessResult(0, "created\n", "");
            }
            throw new IllegalStateException("unexpected command: " + argv);
        };
    }

    /** A scenario whose box is unreachable over ssh, lists no boxes, and creates on demand. */
    private static CommandRunner unreachableProbeScenario() {
        return absentBoxScenario();
    }

    /** As {@link #unreachableProbeScenario()}, but the table lists the box stopped (status 8). */
    private static CommandRunner stoppedBoxScenario() {
        return absentBoxScenario(DEFAULT_BOX + " us1 8");
    }

    @Test
    void theLauncherGatesRemoteLaunchesBeforeAnyBoxCommand() {
        // INV-16's launcher half: the dialog gates the checkbox; this is
        // the guard a programmatically built Choice cannot get past.
        Optional<String> otherAgent = TaskLauncher.remoteLaunchRefusal(
                AgentKind.CODEX, WorkIn.NEW_WORKTREE, false);
        assertTrue(otherAgent.isPresent());
        assertTrue(otherAgent.get().contains("CODEX"));
        assertTrue(otherAgent.get().contains("codex"));
        assertTrue(TaskLauncher.remoteLaunchRefusal(
                AgentKind.CLAUDE, WorkIn.EXISTING_WORKTREE, false).isPresent());
        assertTrue(TaskLauncher.remoteLaunchRefusal(
                AgentKind.CLAUDE, WorkIn.NEW_WORKTREE, true).isPresent());
        assertTrue(TaskLauncher.remoteLaunchRefusal(
                AgentKind.CLAUDE, WorkIn.MAIN_CHECKOUT, false).isEmpty());
        assertTrue(TaskLauncher.remoteLaunchRefusal(
                AgentKind.CLAUDE, WorkIn.NEW_WORKTREE, false).isEmpty());
    }

    @Test
    void nonGithubAndNonDataDogOriginsAreRefusedBeforeAnyBoxCommand() throws Exception {
        // AC-15, launcher half: neither refusal may reach workspaces create
        // — or any box command at all: the origin query is the only call.
        for (String origin : new String[] {"git@gitlab.internal:team/plugins.git",
                "git@github.com:octocat/plugins.git"}) {
            BoxHarness harness = new BoxHarness(
                    (argv, ignoredOptions, ignoredCap) -> new ProcessResult(0, origin, ""), Optional.empty());
            TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                    harness.deps(false), mainCheckoutRequest());
            assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome, origin);
            String reason = ((TaskLauncher.RemoteBoxOutcome.RefusedBox) outcome).reason();
            assertTrue(reason.contains(origin.contains("github.com")
                    ? "DataDog or ddoghq" : "not a GitHub repository"), reason);
            assertEquals(List.of(reason), harness.refusals);
            assertEquals(1, harness.runner.calls.size());
            assertEquals(List.of("git", "-C", BOX_MAIN.toString(), "remote", "get-url", "origin"),
                    harness.runner.calls.get(0).argv());
            assertEquals(List.of(), harness.runner.workspacesSubcommands());
            assertEquals(1, harness.progress.clears); // cleared on the early return
        }
    }

    @Test
    void anUnreadableOriginIsRefusedNotTreatedAsEmpty() throws Exception {
        BoxHarness harness = new BoxHarness(
                (argv, ignoredOptions, ignoredCap) -> new ProcessResult(128, "", "not a git repository"), Optional.empty());
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome);
        assertEquals(1, harness.runner.calls.size());
        assertEquals(1, harness.progress.clears);
    }

    @Test
    void anInvalidBoxOverrideIsRefusedNamingIt() throws Exception {
        BoxHarness harness = new BoxHarness(reachableScenario("1"), Optional.of("Bad_Box!"));
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome);
        assertTrue(((TaskLauncher.RemoteBoxOutcome.RefusedBox) outcome).reason().contains("Bad_Box!"));
        assertEquals(1, harness.runner.calls.size()); // the origin query only
    }

    @Test
    void aReachableBoxIsReusedWithTheBindingAndNeverCreated() throws Exception {
        for (boolean bootstrapped : new boolean[] {false, true}) {
            // The REUSE and BOOTSTRAP_THEN_REUSE rows of the lifecycle table,
            // wired: both reuse the box and run the (idempotent) bootstrap.
            BoxHarness harness = new BoxHarness(reachableScenario("1"), Optional.of(DEFAULT_BOX));
            harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/profiling-plugins");
            TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                    harness.deps(bootstrapped), mainCheckoutRequest());
            assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
            WorkspaceBoxBinding binding = ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding();
            assertEquals(DEFAULT_BOX, binding.boxName());
            assertEquals("/home/dev/profiling-plugins", binding.boxWorkingPath());
            assertEquals(List.of(BOX_HOST), harness.bootstrappedHosts);
            assertEquals(List.of(), harness.mapping.recordedBoxes); // reused, nothing written
            assertEquals(List.of("list"), harness.runner.workspacesSubcommands());
            assertEquals(List.of(TaskLauncher.ORIGIN_STEP, TaskLauncher.probeStep(DEFAULT_BOX),
                    TaskLauncher.LIST_STEP, TaskLauncher.BOOTSTRAP_STEP, TaskLauncher.WORKTREE_STEP),
                    harness.progress.steps);
            assertEquals(1, harness.progress.clears);
        }
    }

    @Test
    void anUnmappedAbsentBoxIsCreatedUnderTheBaseName() throws Exception {
        BoxHarness harness = new BoxHarness(unreachableProbeScenario(), Optional.empty());
        harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/profiling-plugins");
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
        assertEquals(DEFAULT_BOX, ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding().boxName());
        // The mapping now points at the box it created (INV-25 bookkeeping).
        assertEquals(List.of(DEFAULT_BOX), harness.mapping.recordedBoxes);
        // AC-17: workspaces create through CommandRunner, exact argv, 10 min,
        // discardInput, in the local main checkout.
        BoxCall create = harness.runner.workspacesCreate();
        assertEquals(List.of("/opt/homebrew/bin/workspaces", "create", DEFAULT_BOX,
                "-R", "DataDog/profiling-plugins", "-s", "bash", "-y", "--no-color"), create.argv());
        assertEquals(Duration.ofMinutes(10), create.options().timeout());
        assertTrue(create.options().discardInput());
        assertEquals(BOX_MAIN, create.options().workingDirectory());
        assertEquals(List.of("list", "create"), harness.runner.workspacesSubcommands());
        assertEquals(List.of(TaskLauncher.ORIGIN_STEP, TaskLauncher.probeStep(DEFAULT_BOX),
                TaskLauncher.LIST_STEP, TaskLauncher.createStep(DEFAULT_BOX),
                TaskLauncher.BOOTSTRAP_STEP, TaskLauncher.WORKTREE_STEP), harness.progress.steps);
        assertEquals(1, harness.progress.clears);
    }

    @Test
    void aStoppedMappedBoxConfirmedRecreatesTheNextGeneration() throws Exception {
        BoxHarness harness = new BoxHarness(stoppedBoxScenario(), Optional.of(DEFAULT_BOX));
        harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/profiling-plugins");
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
        String fresh = DEFAULT_BOX + "-2";
        assertEquals(fresh, ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding().boxName());
        // AC-18: the confirmation names the old box and the loss...
        assertEquals(1, harness.confirmations.size());
        String message = harness.confirmations.get(0);
        assertTrue(message.contains(DEFAULT_BOX), message);
        assertTrue(message.contains("unreachable"), message);
        assertTrue(message.contains("not recovered by this action"), message);
        assertTrue(message.contains(fresh), message);
        // ...confirm creates <base>-2 and updates the mapping; the stopped
        // box itself is left in place (never deleted, never restarted).
        assertEquals(List.of(fresh), harness.mapping.recordedBoxes);
        assertEquals(List.of("/opt/homebrew/bin/workspaces", "create", fresh,
                "-R", "DataDog/profiling-plugins", "-s", "bash", "-y", "--no-color"),
                harness.runner.workspacesCreate().argv());
        assertEquals(List.of("list", "create"), harness.runner.workspacesSubcommands());
    }

    @Test
    void aCancelledRecreateChangesNothing() throws Exception {
        BoxHarness harness = new BoxHarness(stoppedBoxScenario(), Optional.of(DEFAULT_BOX));
        harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/profiling-plugins");
        harness.confirmAnswer = false;
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.BoxCancelled.class, outcome);
        assertEquals(List.of(), harness.mapping.recordedBoxes); // the .box key is untouched
        assertEquals(List.of("list"), harness.runner.workspacesSubcommands()); // no create
        assertEquals(List.of(), harness.bootstrappedHosts); // and no bootstrap
        assertEquals(List.of(), harness.refusals); // a cancel is not a failure
        assertEquals(1, harness.progress.clears);
    }

    @Test
    void aGoneMappedBoxConfirmedRecreatesTheNextGeneration() throws Exception {
        BoxHarness harness = new BoxHarness(unreachableProbeScenario(), Optional.of(DEFAULT_BOX));
        harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/profiling-plugins");
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
        assertEquals(DEFAULT_BOX + "-2", ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding().boxName());
        assertEquals(1, harness.confirmations.size());
        String message = harness.confirmations.get(0);
        assertTrue(message.contains("no longer exists"), message);
        assertTrue(message.contains("gone"), message);
        assertTrue(message.contains(DEFAULT_BOX), message);
        assertTrue(message.contains("not recovered by this action"), message);
        assertEquals(List.of(DEFAULT_BOX + "-2"), harness.mapping.recordedBoxes);
    }

    @Test
    void ambiguousBoxAnswersRefuseAsTransientAndNeverOfferARecreate() throws Exception {
        // AC-16 integration: every ambiguous row of the lifecycle table — a
        // listed non-stopped status, a CLI failure, a CLI timeout, an
        // unparseable table, a missing CLI — is a transient refusal (INV-13),
        // never a create, a confirmation, or a rebind.
        List<CommandRunner> ambiguous = List.of(
                // listed, but not status 8
                (argv, ignoredOptions, ignoredCap) -> {
                    if ("git".equals(argv.get(0))) {
                        return new ProcessResult(0, DD_ORIGIN, "");
                    }
                    if ("ssh".equals(argv.get(0))) {
                        return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
                    }
                    if (argv.contains("list")) {
                        return listing(DEFAULT_BOX + " us1 1");
                    }
                    throw new IllegalStateException("unexpected command: " + argv);
                },
                // the CLI itself failed
                (argv, ignoredOptions, ignoredCap) -> {
                    if ("git".equals(argv.get(0))) {
                        return new ProcessResult(0, DD_ORIGIN, "");
                    }
                    if ("ssh".equals(argv.get(0))) {
                        return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
                    }
                    if (argv.contains("list")) {
                        return new ProcessResult(3, "", "workspaces: backend error");
                    }
                    throw new IllegalStateException("unexpected command: " + argv);
                },
                // the CLI hung past its timeout
                (argv, ignoredOptions, ignoredCap) -> {
                    if ("git".equals(argv.get(0))) {
                        return new ProcessResult(0, DD_ORIGIN, "");
                    }
                    if ("ssh".equals(argv.get(0))) {
                        return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
                    }
                    if (argv.contains("list")) {
                        throw new ProcessTimeoutException(argv, Duration.ofSeconds(30));
                    }
                    throw new IllegalStateException("unexpected command: " + argv);
                },
                // the table could not be parsed
                (argv, ignoredOptions, ignoredCap) -> {
                    if ("git".equals(argv.get(0))) {
                        return new ProcessResult(0, DD_ORIGIN, "");
                    }
                    if ("ssh".equals(argv.get(0))) {
                        return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
                    }
                    if (argv.contains("list")) {
                        return new ProcessResult(0, "no header row at all\n", "");
                    }
                    throw new IllegalStateException("unexpected command: " + argv);
                });
        for (CommandRunner scenario : ambiguous) {
            BoxHarness harness = new BoxHarness(scenario, Optional.of(DEFAULT_BOX));
            TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                    harness.deps(false), mainCheckoutRequest());
            assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome, scenario.toString());
            assertTrue(((TaskLauncher.RemoteBoxOutcome.RefusedBox) outcome).reason()
                    .contains("could not be reached"));
            assertEquals(List.of(), harness.mapping.recordedBoxes);
            assertEquals(List.of(), harness.confirmations);
            assertEquals(List.of("list"), harness.runner.workspacesSubcommands());
            assertEquals(1, harness.progress.clears);
        }

        // A missing workspaces CLI is the same refusal: never an empty
        // listing standing in for a missing tool (INV-9).
        BoxHarness missing = new BoxHarness((argv, ignoredOptions, ignoredCap) -> {
            if ("git".equals(argv.get(0))) {
                return new ProcessResult(0, DD_ORIGIN, "");
            }
            if ("ssh".equals(argv.get(0))) {
                return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
            }
            throw new IllegalStateException("unexpected command: " + argv);
        }, Optional.of(DEFAULT_BOX));
        TaskLauncher.RemoteBoxDeps deps = new TaskLauncher.RemoteBoxDeps(missing.runner,
                new WorkspacesCli(missingBinary(), missing.runner), host -> {
                }, missing.mapping, missing.guard, message -> true, reason -> {
                }, missing.progress, false);
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class,
                TaskLauncher.prepareBox(deps, mainCheckoutRequest()));
        assertEquals(List.of(), missing.runner.workspacesSubcommands());
    }

    @Test
    void aFailedOrTimedOutCreateRefusesTheLaunch() throws Exception {
        List<CommandRunner> createFailures = List.of(
                // the CLI refused the create
                (argv, ignoredOptions, ignoredCap) -> {
                    if ("git".equals(argv.get(0))) {
                        return new ProcessResult(0, DD_ORIGIN, "");
                    }
                    if ("ssh".equals(argv.get(0))) {
                        return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
                    }
                    if (argv.contains("list")) {
                        return listing();
                    }
                    if (argv.contains("create")) {
                        return new ProcessResult(4, "", "workspaces: quota exceeded");
                    }
                    throw new IllegalStateException("unexpected command: " + argv);
                },
                // the create hung past its 10-minute timeout
                (argv, ignoredOptions, ignoredCap) -> {
                    if ("git".equals(argv.get(0))) {
                        return new ProcessResult(0, DD_ORIGIN, "");
                    }
                    if ("ssh".equals(argv.get(0))) {
                        return new ProcessResult(255, "", "ssh: connect to host " + BOX_HOST);
                    }
                    if (argv.contains("list")) {
                        return listing();
                    }
                    if (argv.contains("create")) {
                        throw new ProcessTimeoutException(argv, Duration.ofMinutes(10));
                    }
                    throw new IllegalStateException("unexpected command: " + argv);
                });
        for (CommandRunner createFailure : createFailures) {
            BoxHarness harness = new BoxHarness(createFailure, Optional.empty());
            TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                    harness.deps(false), mainCheckoutRequest());
            assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome);
            String reason = ((TaskLauncher.RemoteBoxOutcome.RefusedBox) outcome).reason();
            assertTrue(reason.contains("Creating workspace box " + DEFAULT_BOX + " failed")
                    || reason.contains("timed out"), reason);
            assertEquals(List.of(), harness.mapping.recordedBoxes); // nothing recorded on failure
            assertEquals(List.of(), harness.bootstrappedHosts); // and nothing bootstrapped
            assertEquals(1, harness.progress.clears);
            assertEquals(List.of("list", "create"), harness.runner.workspacesSubcommands());
        }
    }

    @Test
    void aFailedBootstrapRefusesTheLaunchVisibly() throws Exception {
        // AC-19: a failed bootstrap never launches; the refusal carries
        // the bootstrap's own reason and the chain stops there.
        BoxHarness harness = new BoxHarness(reachableScenario("1"), Optional.of(DEFAULT_BOX));
        harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/profiling-plugins");
        harness.bootstrapFails = true;
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome);
        String reason = ((TaskLauncher.RemoteBoxOutcome.RefusedBox) outcome).reason();
        assertTrue(reason.contains("bootstrap failed"), reason);
        assertTrue(reason.contains("ddtool vault check"), reason);
        assertEquals(List.of(BOX_HOST), harness.bootstrappedHosts);
        // No checkout ran after the refused bootstrap (no further ssh).
        assertEquals(3, harness.runner.calls.size()); // origin, probe, list
        assertEquals(1, harness.progress.clears);
    }

    @Test
    void aNewWorktreeLaunchPreparesTheBoxWorktreeUnderTheClone() throws Exception {
        CommandRunner scenario = (argv, ignoredOptions, ignoredCap) -> {
            if ("git".equals(argv.get(0))) {
                return new ProcessResult(0, DD_ORIGIN, "");
            }
            if ("ssh".equals(argv.get(0))) {
                String remote = argv.get(argv.size() - 1);
                if (remote.equals("'true'")) {
                    return new ProcessResult(0, "", "");
                }
                if (remote.contains("find")) {
                    return new ProcessResult(0, "/home/dev/profiling-plugins/.git\n", "");
                }
                if (remote.contains("get-url")) {
                    return new ProcessResult(0, DD_ORIGIN, "");
                }
                if (remote.contains("'cat'")) {
                    return new ProcessResult(0, "", ""); // the exclude file is empty
                }
                if (remote.contains("echo")) {
                    return new ProcessResult(0, "", ""); // the exclude line is appended
                }
                if (remote.contains("fetch")) {
                    return new ProcessResult(0, "", "");
                }
                if (remote.contains("worktree")) {
                    return new ProcessResult(0, "", ""); // the box worktree is added
                }
                throw new IllegalStateException("unexpected ssh: " + remote);
            }
            if (argv.contains("list")) {
                return listing(DEFAULT_BOX + " us1 1");
            }
            throw new IllegalStateException("unexpected command: " + argv);
        };
        BoxHarness harness = new BoxHarness(scenario, Optional.of(DEFAULT_BOX));
        TaskLauncher.RemoteBoxRequest request = new TaskLauncher.RemoteBoxRequest(BOX_MAIN, BOX_REPO,
                new BoxCheckout.Work.NewWorktree("fix-parser", Optional.of("origin/main")));
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(harness.deps(false), request);
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
        WorkspaceBoxBinding binding = ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding();
        assertEquals(DEFAULT_BOX, binding.boxName());
        assertEquals("/home/dev/profiling-plugins/.drydock-worktrees/fix-parser", binding.boxWorkingPath());
        // The discovered clone was cached for the next launch.
        assertTrue(harness.mapping.storedClones.contains("/home/dev/profiling-plugins"));
        assertEquals(1, harness.progress.clears);
    }

    @Test
    void aCancelledLaunchPropagatesInterruptionWithTheProgressCleared() throws Exception {
        BoxHarness harness = new BoxHarness((argv, ignoredOptions, ignoredCap) -> {
            throw new InterruptedException("cancelled");
        }, Optional.empty());
        assertThrows(InterruptedException.class,
                () -> TaskLauncher.prepareBox(harness.deps(false), mainCheckoutRequest()));
        assertEquals(1, harness.progress.clears);
        assertEquals(List.of(), harness.refusals); // a cancel is not a failure
    }

    // ---- R2 fix round: the five review findings, each pinned ------------------

    @Test
    void anEarlierGenerationAbsentFromTheListIsNeverReused() throws Exception {
        // R2 defect 1: the repo used drydock-profiling-plugins, then -2, then
        // -3; the earlier boxes have been garbage-collected, so the current
        // listing shows only -3 (stopped). The persisted used-names history —
        // not the current listing — must keep -2 out of the re-create's reach.
        BoxHarness harness = new BoxHarness(absentBoxScenario(DEFAULT_BOX + "-3 us1 8"),
                Optional.of(DEFAULT_BOX + "-3"));
        harness.mapping.boxUsed(DEFAULT_BOX);
        harness.mapping.boxUsed(DEFAULT_BOX + "-2");
        harness.mapping.boxUsed(DEFAULT_BOX + "-3");
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
        // -2 is absent from the listing yet still taken: the fresh box is -4.
        String fresh = DEFAULT_BOX + "-4";
        assertEquals(fresh, ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding().boxName());
        assertEquals(List.of("/opt/homebrew/bin/workspaces", "create", fresh,
                "-R", "DataDog/profiling-plugins", "-s", "bash", "-y", "--no-color"),
                harness.runner.workspacesCreate().argv());
        assertEquals(List.of(fresh), harness.mapping.recordedBoxes);
        assertEquals(1, harness.progress.clears);
    }

    @Test
    void anUnmappedDefaultBoxIsRememberedOnceUsedSoItsLaterLossIsARecreateOffer() throws Exception {
        // R2 defect 2, first half: a launch on an unmapped reachable default
        // box must record it — otherwise the box's later disappearance is a
        // first-ever same-name CREATE, silently rebinding old rows' alias to
        // a fresh box.
        BoxHarness harness = new BoxHarness(reachableScenario("1"), Optional.empty());
        harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/profiling-plugins");
        TaskLauncher.RemoteBoxOutcome first = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, first);
        assertEquals(DEFAULT_BOX, ((TaskLauncher.RemoteBoxOutcome.ReadyBox) first).binding().boxName());
        assertEquals(List.of(DEFAULT_BOX), harness.mapping.recordedBoxes, "the used box is remembered");
        assertEquals(List.of("list"), harness.runner.workspacesSubcommands(), "a reuse never creates");
        assertEquals(List.of(), harness.confirmations, "a reuse never confirms");

        // Second half: the box has disappeared entirely. Its loss is the
        // destructive GONE re-create offer under a fresh name — never a
        // silent create of the same default name.
        harness.runner.setScenario(absentBoxScenario());
        TaskLauncher.RemoteBoxOutcome second = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, second);
        assertEquals(DEFAULT_BOX + "-2", ((TaskLauncher.RemoteBoxOutcome.ReadyBox) second).binding().boxName());
        assertEquals(1, harness.confirmations.size());
        assertTrue(harness.confirmations.get(0).contains("no longer exists"),
                harness.confirmations.get(0));
        assertEquals(List.of("/opt/homebrew/bin/workspaces", "create", DEFAULT_BOX + "-2",
                "-R", "DataDog/profiling-plugins", "-s", "bash", "-y", "--no-color"),
                harness.runner.workspacesCreate().argv());
        assertEquals(List.of(DEFAULT_BOX, DEFAULT_BOX + "-2"), harness.mapping.recordedBoxes);
    }

    @Test
    void aClearedOverrideDoesNotMakeAUsedBoxFirstEverAgain() throws Exception {
        // R2 defect 2, the cleared-override variant: the Workspace box dialog
        // can clear `.box` while rows still bind the old box. The used-names
        // history keeps the box's loss a GONE re-create offer — never a
        // first-ever CREATE of the same name without any confirmation.
        BoxHarness harness = new BoxHarness(absentBoxScenario(), Optional.empty());
        harness.mapping.boxUsed(DEFAULT_BOX); // used before; the override was since cleared
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
        assertEquals(DEFAULT_BOX + "-2", ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding().boxName());
        assertEquals(1, harness.confirmations.size(), "the loss is offered, never silent");
        assertTrue(harness.confirmations.get(0).contains("no longer exists"), harness.confirmations.get(0));
        assertEquals(List.of("/opt/homebrew/bin/workspaces", "create", DEFAULT_BOX + "-2",
                "-R", "DataDog/profiling-plugins", "-s", "bash", "-y", "--no-color"),
                harness.runner.workspacesCreate().argv());
    }

    @Test
    void anOverlappingLaunchForTheSameRepositoryFailsVisiblyWithoutASecondCreate() throws Exception {
        // R2 defect 3: a second launch for the same repository while another
        // holds the provisioning claim refuses visibly — never a second,
        // differently-named concurrent create.
        BoxHarness harness = new BoxHarness(absentBoxScenario(), Optional.empty());
        harness.guard.allow = false; // another launch already holds the claim
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome);
        String reason = ((TaskLauncher.RemoteBoxOutcome.RefusedBox) outcome).reason();
        assertTrue(reason.contains("already preparing"), reason);
        assertEquals(0, harness.runner.calls.size(), "no command at all — the claim is checked first");
        assertEquals(List.of(), harness.runner.workspacesSubcommands());
        assertEquals(List.of(), harness.confirmations);
        assertEquals(List.of(), harness.mapping.recordedBoxes);
        assertEquals(1, harness.progress.clears);
    }

    @Test
    void theProductionProvisioningClaimAllowsOneLaunchPerRepositoryAtATime() {
        Path repo = Path.of("/w/one-repo");
        Path other = Path.of("/w/other-repo");
        assertTrue(TaskLauncher.PRODUCTION_PROVISIONING.acquire(repo));
        // A second overlapping launch for the SAME repository is refused…
        assertFalse(TaskLauncher.PRODUCTION_PROVISIONING.acquire(repo));
        // …while another repository's launch proceeds.
        assertTrue(TaskLauncher.PRODUCTION_PROVISIONING.acquire(other));
        TaskLauncher.PRODUCTION_PROVISIONING.release(other);
        // The claim is returned on every exit, so the next launch may take it.
        TaskLauncher.PRODUCTION_PROVISIONING.release(repo);
        assertTrue(TaskLauncher.PRODUCTION_PROVISIONING.acquire(repo));
        TaskLauncher.PRODUCTION_PROVISIONING.release(repo);
    }

    @Test
    void theProvisioningClaimIsReleasedOnEveryExit() throws Exception {
        // Cancel and refusal both hand the claim back, so a later launch for
        // the same repository is never stranded behind a dead claim.
        BoxHarness harness = new BoxHarness(stoppedBoxScenario(), Optional.of(DEFAULT_BOX));
        harness.confirmAnswer = false; // the cancel path
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.BoxCancelled.class,
                TaskLauncher.prepareBox(harness.deps(false), mainCheckoutRequest()));
        assertEquals(List.of(BOX_MAIN), harness.guard.acquired);
        assertEquals(List.of(BOX_MAIN), harness.guard.released);

        harness.confirmAnswer = true;
        harness.bootstrapFails = true; // the refusal path
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class,
                TaskLauncher.prepareBox(harness.deps(false), mainCheckoutRequest()));
        assertEquals(2, harness.guard.acquired.size());
        assertEquals(2, harness.guard.released.size());
    }

    @Test
    void aFreshBoxGenerationDiscoversItsOwnCloneNotTheOldBoxCache() throws Exception {
        // R2 defect 4: the old box's clone was a manual override at
        // /home/dev/custom-repo (the dialog's escape hatch, recorded for the
        // old box). After the confirmed re-create, the fresh box must
        // discover its own clone — the cached path is not carried into a
        // different box generation.
        BoxHarness harness = new BoxHarness(absentBoxScenario(DEFAULT_BOX + " us1 8"),
                Optional.of(DEFAULT_BOX));
        harness.mapping.cachedClone(DEFAULT_BOX, "/home/dev/custom-repo");
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.ReadyBox.class, outcome);
        WorkspaceBoxBinding binding = ((TaskLauncher.RemoteBoxOutcome.ReadyBox) outcome).binding();
        assertEquals(DEFAULT_BOX + "-2", binding.boxName());
        assertEquals("/home/dev/profiling-plugins", binding.boxWorkingPath(),
                "the fresh box's own discovery, never the old box's cached path");
        assertTrue(harness.runner.calls.stream()
                        .anyMatch(call -> String.join(" ", call.argv()).contains("find")),
                "the fresh box ran its own discovery scan");
        assertTrue(harness.mapping.storedClones.contains("/home/dev/profiling-plugins"));
    }

    @Test
    void aMappedNameTooLongToSuffixRefusesBeforeTheRecreateIsOffered() throws Exception {
        // R2 defect 5: a valid 62-character mapped override leaves no valid
        // generation name. The launch refuses visibly BEFORE the
        // confirmation is offered — never offering a candidate the CLI
        // adapter itself would reject after the user confirms it.
        String longBox = "drydock-" + "x".repeat(54); // 62 characters, valid
        assertTrue(WorkspaceBoxNames.validate(longBox), longBox);
        BoxHarness harness = new BoxHarness(absentBoxScenario(longBox + " us1 8"), Optional.of(longBox));
        TaskLauncher.RemoteBoxOutcome outcome = TaskLauncher.prepareBox(
                harness.deps(false), mainCheckoutRequest());
        assertInstanceOf(TaskLauncher.RemoteBoxOutcome.RefusedBox.class, outcome);
        String reason = ((TaskLauncher.RemoteBoxOutcome.RefusedBox) outcome).reason();
        assertTrue(reason.contains("63-character"), reason);
        assertTrue(reason.contains(longBox), reason);
        assertEquals(List.of(), harness.confirmations, "no re-create is ever offered");
        assertEquals(List.of("list"), harness.runner.workspacesSubcommands());
        assertEquals(List.of(), harness.mapping.recordedBoxes);
        assertEquals(1, harness.progress.clears);
    }
}
