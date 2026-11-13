package app.drydock.intellij.workspace;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.SshCommandBuilder;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.workspace.CommandRunner;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.messages.MessageBusConnection;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static app.drydock.intellij.workspace.RemoteSessionLiveness.State.ALIVE;
import static app.drydock.intellij.workspace.RemoteSessionLiveness.State.DEAD;
import static app.drydock.intellij.workspace.RemoteSessionLiveness.State.UNKNOWN;

/**
 * The startup revalidation pass (workspace persistent sessions, AC-12/AC-13)
 * with a fake {@link CommandRunner}: exactly one {@code tmux ls} per distinct
 * box counted per host, the ALIVE/DEAD/UNKNOWN mapping including the
 * no-server valid-empty case, the flag gate and once-per-run coalescing, and
 * the startup discipline — no row write, no state-store update, and no argv
 * beyond {@code tmux ls} ever issued. There is no box to create, no console
 * to spawn and no dialog to open in this class by construction — the runner
 * sees the whole spawn surface, and it sees only probes.
 */
public class WorkspaceRevalidationTest extends BasePlatformTestCase {

    /**
     * A fake {@link CommandRunner} recording every argv it is handed,
     * answering per host: exit code, stdout and stderr, or an exception to
     * throw (a missing tool). Never spawns a child.
     */
    private static final class RecordingRunner implements CommandRunner {

        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        final Map<String, ProcessResult> resultsByHost = new HashMap<>();
        final Map<String, IOException> failuresByHost = new HashMap<>();

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException {
            calls.add(List.copyOf(argv));
            String host = host(argv);
            IOException failure = failuresByHost.get(host);
            if (failure != null) {
                throw failure;
            }
            return resultsByHost.getOrDefault(host, new ProcessResult(0, "", ""));
        }

        int callsFor(String host) {
            int count = 0;
            for (List<String> argv : calls) {
                if (host(argv).equals(host)) {
                    count++;
                }
            }
            return count;
        }

        static String host(List<String> argv) {
            return argv.get(argv.indexOf("--") + 1);
        }
    }

    /** The one argv shape the pass may ever issue: batch ssh + tmux ls, built by SshCommandBuilder. */
    private static List<String> tmuxLsArgv(String host) {
        return SshCommandBuilder.remoteCommand(host, List.of("tmux", "ls", "-F", "#{session_name}"));
    }

    private static ManagedAgentSession remoteSession(ManagedSessionId id, String boxName) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "Remote " + id,
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/never-local"), Optional.of(Path.of("/tmp/never-local")), true),
                Instant.parse("2026-08-12T10:15:30Z"))
                .status(SessionStatus.IDLE)
                .workspaceBox(Optional.of(new WorkspaceBoxBinding(boxName, "/Users/dev/clone")))
                .build();
    }

    private static ManagedAgentSession localSession(ManagedSessionId id) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "Local " + id,
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/local"), Optional.of(Path.of("/tmp/local")), true),
                Instant.parse("2026-08-12T10:15:30Z"))
                .status(SessionStatus.IDLE)
                .build();
    }

    /** Runs one pass in this test: the catalog and flag are injected, the executor runs inline. */
    private RemoteSessionLiveness revalidate(List<ManagedAgentSession> catalog, RecordingRunner runner,
            BooleanSupplier enabled) throws Exception {
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        WorkspaceRevalidation revalidation = new WorkspaceRevalidation(
                () -> catalog, Runnable::run, liveness, runner, enabled);
        revalidation.runOnce().get(10, TimeUnit.SECONDS);
        return liveness;
    }

    /** AC-12: exactly one tmux ls per distinct box, calls counted per host, and the ALIVE/DEAD split. */
    public void testOneTmuxLsPerDistinctBoxWithCallsCountedByHost() throws Exception {
        ManagedSessionId aliveOnAlpha = ManagedSessionId.newId();
        ManagedSessionId deadOnAlpha = ManagedSessionId.newId();
        ManagedSessionId deadOnBeta = ManagedSessionId.newId();
        ManagedSessionId local = ManagedSessionId.newId();
        List<ManagedAgentSession> catalog = List.of(
                remoteSession(aliveOnAlpha, "alpha-box"),
                remoteSession(deadOnAlpha, "alpha-box"),
                remoteSession(deadOnBeta, "beta-box"),
                localSession(local));
        RecordingRunner runner = new RecordingRunner();
        // A box with foreign sessions and one drydock one: only the drydock
        // names count, and an unlisted drydock id is DEAD, not an error.
        runner.resultsByHost.put("workspace-alpha-box", new ProcessResult(0,
                "other-session\n" + SshCommandBuilder.tmuxSessionName(aliveOnAlpha) + "\n", ""));
        runner.resultsByHost.put("workspace-beta-box", new ProcessResult(0, "", ""));

        RemoteSessionLiveness liveness = revalidate(catalog, runner, () -> true);

        assertEquals("one probe for the two rows sharing alpha-box", 1,
                runner.callsFor("workspace-alpha-box"));
        assertEquals("one probe for beta-box", 1, runner.callsFor("workspace-beta-box"));
        assertEquals("no other box was ever probed", 2, runner.calls.size());
        for (List<String> argv : runner.calls) {
            assertTrue("every argv is the tmux ls shape, host included: " + argv,
                    argv.equals(tmuxLsArgv("workspace-alpha-box"))
                            || argv.equals(tmuxLsArgv("workspace-beta-box")));
        }
        assertEquals(Optional.of(ALIVE), liveness.state(aliveOnAlpha));
        assertEquals(Optional.of(DEAD), liveness.state(deadOnAlpha));
        assertEquals(Optional.of(DEAD), liveness.state(deadOnBeta));
        assertEquals("a local row is never probed or marked", Optional.empty(), liveness.state(local));
    }

    /** AC-12: both no-server stderr forms are the valid empty answer — DEAD, never an error. */
    public void testNoServerAnswersMarkDead() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        RecordingRunner noServer = new RecordingRunner();
        noServer.resultsByHost.put("workspace-alpha-box",
                new ProcessResult(1, "", "no server running on /private/tmp/tmux-501/default"));
        RemoteSessionLiveness liveness =
                revalidate(List.of(remoteSession(id, "alpha-box")), noServer, () -> true);
        assertEquals(Optional.of(DEAD), liveness.state(id));

        ManagedSessionId other = ManagedSessionId.newId();
        RecordingRunner cannotConnect = new RecordingRunner();
        cannotConnect.resultsByHost.put("workspace-alpha-box",
                new ProcessResult(1, "", "error connecting to /private/tmp/tmux-501/default: No such file or directory"));
        RemoteSessionLiveness otherLiveness =
                revalidate(List.of(remoteSession(other, "alpha-box")), cannotConnect, () -> true);
        assertEquals(Optional.of(DEAD), otherLiveness.state(other));
    }

    /** AC-12/INV-9: anything else is UNKNOWN — a failed or missing ssh is never a DEAD. */
    public void testUnrecognizedFailureAndMissingSshMarkUnknown() throws Exception {
        ManagedSessionId failed = ManagedSessionId.newId();
        RecordingRunner failedRunner = new RecordingRunner();
        failedRunner.resultsByHost.put("workspace-alpha-box",
                new ProcessResult(2, "", "some unexpected tmux failure"));
        RemoteSessionLiveness failedLiveness =
                revalidate(List.of(remoteSession(failed, "alpha-box")), failedRunner, () -> true);
        assertEquals(Optional.of(UNKNOWN), failedLiveness.state(failed));

        ManagedSessionId unreachableTool = ManagedSessionId.newId();
        RecordingRunner noSsh = new RecordingRunner();
        noSsh.failuresByHost.put("workspace-alpha-box",
                new IOException("Cannot run program \"ssh\": error=2, No such file or directory"));
        RemoteSessionLiveness noSshLiveness =
                revalidate(List.of(remoteSession(unreachableTool, "alpha-box")), noSsh, () -> true);
        assertEquals(Optional.of(UNKNOWN), noSshLiveness.state(unreachableTool));
    }

    /** The flag gate: with the opt-in off, the pass runs nothing and marks nothing. */
    public void testFlagOffRunsNoProbeAndMarksNothing() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        RecordingRunner runner = new RecordingRunner();
        RemoteSessionLiveness liveness = revalidate(
                List.of(remoteSession(id, "alpha-box")), runner, () -> false);

        assertEquals(0, runner.calls.size());
        assertEquals(Optional.empty(), liveness.state(id));
    }

    /** Once per IDE run, with concurrent and later triggers coalesced into the first pass. */
    public void testRunOnceCoalescesEveryTriggerIntoOnePass() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        RecordingRunner runner = new RecordingRunner();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        WorkspaceRevalidation revalidation = new WorkspaceRevalidation(
                () -> List.of(remoteSession(id, "alpha-box")), Runnable::run, liveness, runner, () -> true);

        CompletableFuture<Void> first = revalidation.runOnce();
        assertSame("a concurrent trigger joins the first pass", first, revalidation.runOnce());
        first.get(10, TimeUnit.SECONDS);
        assertSame("a later trigger is the same once-per-run pass", first, revalidation.runOnce());
        assertEquals(1, runner.callsFor("workspace-alpha-box"));
        assertEquals(Optional.of(DEAD), liveness.state(id));
    }

    /** A counting repository behind a real store: the pass must not update it even once. */
    private static final class RecordingRepository implements ApplicationStateRepository {

        private ApplicationState state;
        private int saves;

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState state) {
            saves++;
            this.state = state;
        }
    }

    /**
     * AC-13: the pass writes no row — the state store never sees an update
     * (no save is ever scheduled; the class holds only a read seam, so there
     * is no store reference to call) — and the only argv ever issued is the
     * tmux ls probe: no {@code workspaces}, no scp, no kill-session, and by
     * construction no console and no dialog, which the runner's full spawn
     * record would have had to carry.
     */
    public void testPassWritesNoRowAndNeverUpdatesTheStateStore() throws Exception {
        ManagedSessionId alive = ManagedSessionId.newId();
        ManagedSessionId dead = ManagedSessionId.newId();
        RecordingRepository repository = new RecordingRepository();
        repository.state = new ApplicationState(List.of(),
                List.of(remoteSession(alive, "alpha-box"), remoteSession(dead, "alpha-box")),
                List.of(), WorkspaceUiState.empty(), List.of());
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);
        ApplicationState before = store.state();

        RecordingRunner runner = new RecordingRunner();
        runner.resultsByHost.put("workspace-alpha-box", new ProcessResult(0,
                SshCommandBuilder.tmuxSessionName(alive) + "\n", ""));
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        WorkspaceRevalidation revalidation = new WorkspaceRevalidation(
                () -> store.state().sessions(), Runnable::run, liveness, runner, () -> true);
        revalidation.runOnce().get(10, TimeUnit.SECONDS);
        store.flush();

        assertEquals("the row must stay exactly as decoded", before, store.state());
        assertEquals("the store must never be updated — no save was ever scheduled", 0, repository.saves);
        assertEquals(1, runner.calls.size());
        assertEquals(tmuxLsArgv("workspace-alpha-box"), runner.calls.get(0));
        assertEquals("the pass really ran — it marked both rows", Optional.of(ALIVE), liveness.state(alive));
        assertEquals(Optional.of(DEAD), liveness.state(dead));
    }

    /** The liveness map publishes a sessions-changed event on every real change, and only then. */
    public void testLivenessSetPublishesSessionsChangedEvent() {
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ManagedSessionId id = ManagedSessionId.newId();
        AtomicInteger events = new AtomicInteger();
        MessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().connect();
        try {
            connection.subscribe(DrydockSessionsListener.TOPIC, events::incrementAndGet);

            liveness.set(id, ALIVE);
            assertEquals(1, events.get());
            liveness.set(id, ALIVE);
            assertEquals("storing the same state is not a change", 1, events.get());
            liveness.set(id, DEAD);
            assertEquals(2, events.get());
            liveness.remove(id);
            assertEquals(3, events.get());
            liveness.remove(id);
            assertEquals("removing nothing is not a change", 3, events.get());

            assertEquals(Optional.empty(), liveness.state(id));
        } finally {
            connection.disconnect();
        }
    }
}
