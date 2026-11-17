package app.drydock.intellij.session;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
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
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.intellij.workspace.RemoteSessionLiveness;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.process.SshCommandBuilder;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.CommandRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static app.drydock.intellij.workspace.RemoteSessionLiveness.State.ALIVE;
import static app.drydock.intellij.workspace.RemoteSessionLiveness.State.DEAD;
import static app.drydock.intellij.workspace.RemoteSessionLiveness.State.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * The remote half of the end-of-agent settle (workspace persistent
 * sessions, T17/AC-8): one has-session probe per ssh-client end, the
 * ALIVE/DEAD/UNKNOWN settle mapping, the claim guard that keeps a Resume
 * which claimed during the probe, the quit-time rejected executor, and the
 * local row's unchanged exit-code settle as the regression oracle. The fake
 * {@link CommandRunner} is the whole spawn surface this class has: every
 * argv it records must be the one has-session probe — no kill-session, no
 * agent input, on any path (INV-10).
 */
class SessionEndsTest {

    private static final String HOST = "workspace-alpha-box";
    private static final WorkspaceBoxBinding BOX = new WorkspaceBoxBinding("alpha-box", "/home/dev/clone");
    private static final Instant OPENED_AT = Instant.parse("2026-08-12T11:00:00Z");

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
        Logger.getLogger(SessionEnds.class.getName()).addHandler(recorder);
    }

    @AfterEach
    void detachRecorder() {
        Logger.getLogger(SessionEnds.class.getName()).removeHandler(recorder);
    }

    /**
     * A fake {@link CommandRunner} recording every argv it is handed and
     * answering one canned outcome (a reply, a timeout, or a missing tool),
     * with a hook for what happens <em>during</em> the probe — a Resume
     * claiming the session, a forget removing the row. Never spawns a child.
     */
    private static final class RecordingRunner implements CommandRunner {

        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        ProcessResult reply = new ProcessResult(0, "", "");
        ProcessTimeoutException timeoutFailure;
        IOException ioFailure;
        Runnable duringRun;

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            calls.add(List.copyOf(argv));
            if (duringRun != null) {
                duringRun.run();
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

    /**
     * An {@link java.util.concurrent.ExecutorService} that runs every task
     * inline on the submitting thread — so the probe's scheduling and its
     * result land inside one {@code executionTerminated} call — and refuses
     * everything once {@code rejected} is set, as a quit-drained executor does.
     */
    private static final class DirectExecutor extends AbstractExecutorService {

        volatile boolean rejected;

        @Override public void execute(Runnable command) {
            if (rejected) {
                throw new RejectedExecutionException("shut down");
            }
            command.run();
        }
        @Override public void shutdown() { rejected = true; }
        @Override public List<Runnable> shutdownNow() { rejected = true; return List.of(); }
        @Override public boolean isShutdown() { return rejected; }
        @Override public boolean isTerminated() { return rejected; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return rejected; }
    }

    private static final class InMemoryRepository implements ApplicationStateRepository {

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

    /** Records releases; grants nothing (a remote launch never asks). */
    private static final class RecordingAccess implements SessionMcpAccess {

        final List<ManagedSessionId> released = new CopyOnWriteArrayList<>();

        @Override
        public Optional<McpAccess> accessFor(ManagedSessionId sessionId, McpDelivery delivery) {
            return Optional.empty();
        }

        @Override
        public void release(ManagedSessionId sessionId) {
            released.add(sessionId);
        }
    }

    /**
     * A fake of the requested-client-end coordination: the same mark
     * semantics the registry implements, with the live-execution answer
     * delegated to the harness's flag. Never consults the IDE.
     */
    private static final class FakeClientEnds implements AgentExecutionRegistry.RemoteClientEnds {

        private final Map<ManagedSessionId, AgentExecutionRegistry.RemoteClientEnds.RequestedEnd> marks =
                new ConcurrentHashMap<>();
        private final BooleanSupplier liveExecution;

        FakeClientEnds(BooleanSupplier liveExecution) {
            this.liveExecution = liveExecution;
        }

        @Override public void beginExplicitStop(ManagedSessionId id) {
            marks.put(id, AgentExecutionRegistry.RemoteClientEnds.RequestedEnd.explicitStopPending());
        }

        @Override public void stopVerdict(ManagedSessionId id, boolean killConfirmed) {
            marks.compute(id, (session, existing) ->
                    existing != null && existing.origin() == AgentExecutionRegistry.RemoteClientEnds.Origin.EXPLICIT_STOP
                            ? AgentExecutionRegistry.RemoteClientEnds.RequestedEnd.explicitStopVerdict(killConfirmed)
                            : existing);
        }

        @Override public void endExplicitStop(ManagedSessionId id) {
            marks.compute(id, (session, existing) ->
                    existing != null && existing.origin() == AgentExecutionRegistry.RemoteClientEnds.Origin.EXPLICIT_STOP
                            ? null : existing);
        }

        @Override public void beginClose(ManagedSessionId id) {
            marks.put(id, AgentExecutionRegistry.RemoteClientEnds.RequestedEnd.close());
        }

        @Override public void endClose(ManagedSessionId id) {
            marks.compute(id, (session, existing) ->
                    existing != null && existing.origin() == AgentExecutionRegistry.RemoteClientEnds.Origin.CLOSE
                            ? null : existing);
        }

        @Override public void clear(ManagedSessionId id) {
            marks.remove(id);
        }

        @Override public Optional<AgentExecutionRegistry.RemoteClientEnds.RequestedEnd> requestedEnd(ManagedSessionId id) {
            return Optional.ofNullable(marks.get(id));
        }

        @Override public boolean hasLiveExecution(ManagedSessionId id) {
            return liveExecution.getAsBoolean();
        }
    }

    /**
     * The settle under test: the real single writer over an in-memory
     * state file, the real liveness map, the fake runner, claims whose
     * live-execution answer the test flips, and the fake requested-end
     * coordination. The registry supplier of the remote harness throws: the
     * remote settle never consults the agent registry (no local transcript
     * machinery for a remote row).
     */
    private static final class Harness {

        final InMemoryRepository repository = new InMemoryRepository();
        final ApplicationStateStore store;
        final RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        final RecordingRunner runner = new RecordingRunner();
        final RecordingAccess mcpAccess = new RecordingAccess();
        final DirectExecutor executor = new DirectExecutor();
        final AtomicBoolean liveExecution = new AtomicBoolean();
        final LaunchClaims claims;
        final FakeClientEnds clientEnds;
        final SessionEnds ends;

        private Harness(Supplier<AgentRegistry> registry, List<ManagedAgentSession> sessions) {
            repository.state = new ApplicationState(List.of(), new ArrayList<>(sessions), List.of(),
                    WorkspaceUiState.empty(), List.of());
            this.store = ApplicationStateStore.forRepository(repository);
            this.claims = new LaunchClaims(id -> liveExecution.get());
            this.clientEnds = new FakeClientEnds(liveExecution::get);
            this.ends = new SessionEnds(() -> store, registry, executor, mcpAccess, claims,
                    new BoxShell(runner), () -> liveness, clientEnds);
        }
    }

    private static Harness remoteHarness(ManagedAgentSession... sessions) {
        Supplier<AgentRegistry> never = () -> {
            throw new IllegalStateException("the remote settle never consults the agent registry");
        };
        return new Harness(never, List.of(sessions));
    }

    /** A local harness with a real (empty) registry: the local half's late bind may look at it. */
    private static Harness localHarness(ManagedAgentSession... sessions) {
        AgentRegistry registry = new AgentRegistry(List.of(),
                new AgentContext(Path.of("build/unused-state"), Path.of("build/unused-activity"),
                        new DirectExecutor()));
        return new Harness(() -> registry, List.of(sessions));
    }

    // ---- rows ------------------------------------------------------------------

    private static ManagedAgentSession remoteRow(ManagedSessionId id, SessionStatus status) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "Remote " + id,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("agent-session-of-" + id), Optional.empty()),
                SessionWorkspace.inRepository(Path.of("/never/local")),
                Instant.parse("2026-08-12T10:15:30Z"))
                .status(status)
                .lastOpenedAt(OPENED_AT)
                .workspaceBox(Optional.of(BOX))
                .build();
    }

    private static ManagedAgentSession localRow(ManagedSessionId id) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "Local " + id,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("agent-session-of-" + id), Optional.empty()),
                SessionWorkspace.inRepository(Path.of("/tmp/local")),
                Instant.parse("2026-08-12T10:15:30Z"))
                .status(SessionStatus.RUNNING)
                .lastOpenedAt(OPENED_AT)
                .build();
    }

    private static ManagedAgentSession row(Harness harness, ManagedSessionId id) {
        return harness.store.state().sessions().stream()
                .filter(session -> session.id().equals(id))
                .findFirst().orElseThrow();
    }

    /** The one argv the remote settle may ever issue: batch ssh + has-session, per SshCommandBuilder. */
    private static List<String> hasSessionArgv(ManagedSessionId id) {
        return SshCommandBuilder.remoteCommand(HOST,
                List.of("tmux", "has-session", "-t", SshCommandBuilder.tmuxSessionName(id)));
    }

    private static void assertOnlyHasSessionProbe(Harness harness, ManagedSessionId id) {
        List<List<String>> expected = List.of(hasSessionArgv(id));
        assertEquals(expected, harness.runner.calls,
                "the ssh client's end issues exactly the one has-session probe — "
                        + "no kill-session, no agent input, nothing else (INV-10)");
    }

    // ---- AC-8 ------------------------------------------------------------------

    /** ALIVE → the row settles IDLE, liveness ALIVE, and no exit code is recorded. */
    @Test
    void anAliveProbeSettlesTheRowIdleWithLivenessAlive() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.runner.reply = new ProcessResult(0, "", "");

        harness.ends.executionTerminated(id, false, 0);

        assertEquals(SessionStatus.IDLE, row(harness, id).status());
        assertEquals(Optional.empty(), row(harness, id).lastExitCode(),
                "the ssh client's exit code is not the agent's");
        assertEquals(Optional.of(ALIVE), harness.liveness.state(id));
        assertOnlyHasSessionProbe(harness, id);
        assertEquals(List.of(), harness.mcpAccess.released,
                "a remote launch never mints, so nothing is released");
    }

    /** DEAD → today's EXITED (or INACTIVE on a requested stop), without the ssh client's exit code. */
    @Test
    void aDeadProbeSettlesTodaySExitedOrInactiveWithoutTheSshExitCode() {
        ManagedSessionId ended = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(ended, SessionStatus.RUNNING));
        harness.runner.reply = new ProcessResult(1, "",
                "can't find session: " + SshCommandBuilder.tmuxSessionName(ended));

        harness.ends.executionTerminated(ended, false, 9);

        assertEquals(SessionStatus.EXITED, row(harness, ended).status());
        assertEquals(Optional.empty(), row(harness, ended).lastExitCode(),
                "the ssh client's exit code is not the agent's");
        assertEquals(Optional.of(DEAD), harness.liveness.state(ended));
        assertOnlyHasSessionProbe(harness, ended);

        ManagedSessionId stopped = ManagedSessionId.newId();
        Harness stopping = remoteHarness(remoteRow(stopped, SessionStatus.RUNNING));
        stopping.runner.reply = new ProcessResult(1, "",
                "no server running on /private/tmp/tmux-501/default");

        stopping.ends.executionTerminated(stopped, true, 9);

        assertEquals(SessionStatus.INACTIVE, row(stopping, stopped).status(),
                "a requested stop settles INACTIVE, as today");
        assertEquals(Optional.empty(), row(stopping, stopped).lastExitCode());
        assertEquals(Optional.of(DEAD), stopping.liveness.state(stopped));
        assertOnlyHasSessionProbe(stopping, stopped);
    }

    /** UNREACHABLE → IDLE, liveness UNKNOWN, and a WARNING carrying the probe's excerpt. */
    @Test
    void anUnreachableBoxSettlesIdleUnknownAndWarnsWithTheExcerpt() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.runner.reply = new ProcessResult(255, "",
                "ssh: connect to host " + HOST + " port 22: Connection timed out");

        harness.ends.executionTerminated(id, false, 255);

        assertEquals(SessionStatus.IDLE, row(harness, id).status());
        assertEquals(Optional.of(UNKNOWN), harness.liveness.state(id));
        assertOnlyHasSessionProbe(harness, id);
        assertTrue(warnings.stream().map(LogRecord::getMessage)
                        .anyMatch(message -> message.contains("Connection timed out")),
                "a WARNING carries the stderr excerpt: " + warnings);
    }

    /** TIMEOUT (and a missing ssh) → IDLE, liveness UNKNOWN, WARNING logged. */
    @Test
    void aTimedOutOrMissingProbeSettlesIdleUnknown() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.runner.timeoutFailure = new ProcessTimeoutException(List.of("ssh"), Duration.ofSeconds(10));

        harness.ends.executionTerminated(id, false, null);

        assertEquals(SessionStatus.IDLE, row(harness, id).status());
        assertEquals(Optional.of(UNKNOWN), harness.liveness.state(id));
        assertOnlyHasSessionProbe(harness, id);
        assertTrue(warnings.stream().map(LogRecord::getMessage)
                        .anyMatch(message -> message.contains("timed out")),
                "the timeout is warned about: " + warnings);

        ManagedSessionId noSsh = ManagedSessionId.newId();
        Harness missing = remoteHarness(remoteRow(noSsh, SessionStatus.RUNNING));
        missing.runner.ioFailure = new IOException("Cannot run program \"ssh\": error=2");

        missing.ends.executionTerminated(noSsh, false, null);

        assertEquals(SessionStatus.IDLE, row(missing, noSsh).status(),
                "a missing ssh is UNKNOWN, never DEAD");
        assertEquals(Optional.of(UNKNOWN), missing.liveness.state(noSsh));
        assertOnlyHasSessionProbe(missing, noSsh);
    }

    /** A Resume claiming the session during the probe is never overwritten by the settle. */
    @Test
    void aResumeClaimingDuringTheProbeIsNeverOverwritten() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.runner.reply = new ProcessResult(0, "", "");   // ALIVE — the settle would say IDLE
        harness.runner.duringRun = () -> harness.claims.tryClaim(id);

        harness.ends.executionTerminated(id, false, 0);

        assertEquals(SessionStatus.RUNNING, row(harness, id).status(),
                "the claim owns the row; its RUNNING stands");
        assertEquals(OPENED_AT, row(harness, id).lastOpenedAt(), "nothing but the status changed");
        assertEquals(Optional.empty(), harness.liveness.state(id),
                "no liveness is recorded for a row a live claim owns");
        assertEquals(List.of(hasSessionArgv(id)), harness.runner.calls, "the probe still ran");
    }

    /** A live execution for the id suppresses the settle the same way a claim does. */
    @Test
    void aLiveExecutionSuppressesTheSettle() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.runner.reply = new ProcessResult(0, "", "");
        harness.liveExecution.set(true);

        harness.ends.executionTerminated(id, false, 0);

        assertEquals(SessionStatus.RUNNING, row(harness, id).status());
        assertEquals(Optional.empty(), harness.liveness.state(id));
        assertOnlyHasSessionProbe(harness, id);
    }

    /** The row forgotten while the probe was in flight settles nothing at all. */
    @Test
    void aRowForgottenDuringTheProbeSettlesNothing() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.runner.reply = new ProcessResult(0, "", "");
        harness.runner.duringRun = () -> harness.store.update(state -> {
            List<ManagedAgentSession> sessions = new ArrayList<>(state.sessions());
            sessions.removeIf(session -> session.id().equals(id));
            return state.withSessions(sessions);
        });

        harness.ends.executionTerminated(id, false, 0);

        assertTrue(harness.store.state().sessions().isEmpty(), "the row is gone and stays gone");
        assertEquals(Optional.empty(), harness.liveness.state(id),
                "no liveness lingers for a forgotten row");
        assertOnlyHasSessionProbe(harness, id);
    }

    /** A quit-rejected executor still settles the row IDLE, and leaves the liveness map alone. */
    @Test
    void aRejectedExecutorSettlesIdleAndLeavesLivenessAlone() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.executor.rejected = true;

        harness.ends.executionTerminated(id, false, 0);

        assertEquals(SessionStatus.IDLE, row(harness, id).status());
        assertEquals(Optional.empty(), harness.liveness.state(id),
                "no probe answer exists, so nothing is recorded");
        assertEquals(List.of(), harness.runner.calls, "no probe was ever issued");
        assertEquals(List.of(), harness.mcpAccess.released);
    }

    // ---- requested client ends: explicit Stop and close (review R1) ---------

    /**
     * A close-originated end settles the durable row IDLE — never EXITED,
     * never INACTIVE (AC-7) — even though the stop flag the close's stop
     * set reads as a requested stop: the mark, set before the client was
     * asked to end, is what the end settles by. No probe runs (AC-8 is the
     * story of an end nobody requested), so no liveness is recorded either.
     */
    @Test
    void aCloseOriginatedClientEndSettlesTheRowIdleWithoutAProbe() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.clientEnds.beginClose(id);

        harness.ends.executionTerminated(id, true, 0);

        assertEquals(SessionStatus.IDLE, row(harness, id).status(),
                "AC-7: a close-originated end never persists EXITED or INACTIVE");
        assertEquals(Optional.empty(), harness.liveness.state(id),
                "no probe ran, so no liveness answer was earned");
        assertEquals(List.of(), harness.runner.calls,
                "no probe, no kill-session, no input — the close touches the box never (INV-10)");
        assertEquals(List.of(), harness.mcpAccess.released);
    }

    /**
     * An explicit Stop's client end settles by the kill's own verdict, with
     * no probe — including the end that arrives before the Stop's stop
     * flag could be set (the client terminated during the kill round-trip)
     * and the end that arrives after the Stop's own settle was suppressed
     * by the still-dying client: neither may be overwritten by a probe, and
     * neither may overwrite the confirmed outcome (AC-9).
     */
    @Test
    void anExplicitStopsClientEndSettlesByTheKillsVerdictWithoutAProbe() {
        ManagedSessionId stopped = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(stopped, SessionStatus.STOPPING));
        harness.clientEnds.beginExplicitStop(stopped);
        harness.clientEnds.stopVerdict(stopped, true);

        harness.ends.executionTerminated(stopped, false, 0);

        assertEquals(SessionStatus.INACTIVE, row(harness, stopped).status(),
                "a confirmed kill settles INACTIVE whatever a probe would have said");
        assertEquals(Optional.of(DEAD), harness.liveness.state(stopped));
        assertEquals(List.of(), harness.runner.calls, "no probe competes with the Stop's verdict");

        ManagedSessionId inconclusive = ManagedSessionId.newId();
        Harness failed = remoteHarness(remoteRow(inconclusive, SessionStatus.STOPPING));
        failed.clientEnds.beginExplicitStop(inconclusive);
        failed.clientEnds.stopVerdict(inconclusive, false);

        failed.ends.executionTerminated(inconclusive, true, 0);

        assertEquals(SessionStatus.IDLE, row(failed, inconclusive).status(),
                "a kill that did not conclude settles IDLE, never INACTIVE");
        assertEquals(Optional.of(UNKNOWN), failed.liveness.state(inconclusive));
        assertEquals(List.of(), failed.runner.calls);
    }

    /**
     * The end that arrives while the Stop's kill is still in flight settles
     * nothing and probes nothing: the Stop flow owns the settle and will
     * write its verdict when the kill replies — this is the ordering where a
     * probe scheduled here used to persist EXITED over a later confirmed
     * INACTIVE (review R1, defect 2's first ordering).
     */
    @Test
    void anExplicitStopsClientEndWhileTheKillIsInFlightLeavesTheSettleToTheStop() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.STOPPING));
        harness.clientEnds.beginExplicitStop(id);

        harness.ends.executionTerminated(id, false, 0);

        assertEquals(SessionStatus.STOPPING, row(harness, id).status(),
                "the Stop flow holds the settle until its kill replies");
        assertEquals(Optional.empty(), harness.liveness.state(id));
        assertEquals(List.of(), harness.runner.calls, "no probe is scheduled against the in-flight kill");
    }

    /**
     * An early end under its own launch's claim is deferred, not discarded:
     * the probe's verdict waits until the launch's claim ends (its
     * finalization has run by then), and is re-applied — so a completed
     * launch never leaves RUNNING persisted when this IDE owns no live ssh
     * execution (INV-4, review R1 defect 4).
     */
    @Test
    void anEarlyEndUnderItsOwnLaunchClaimIsDeferredAndReappliedWhenTheClaimEnds() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = remoteHarness(remoteRow(id, SessionStatus.RUNNING));
        harness.runner.reply = new ProcessResult(0, "", "");   // ALIVE — the settle would say IDLE
        harness.runner.duringRun = () -> harness.claims.tryClaim(id);   // the launch still holds its claim

        harness.ends.executionTerminated(id, false, 0);

        assertEquals(SessionStatus.RUNNING, row(harness, id).status(),
                "while the claim lives, the settle still waits");
        assertEquals(Optional.empty(), harness.liveness.state(id));

        // The launch's claim ends (the service's endClaim): the deferred
        // settle is re-applied and now lands.
        harness.claims.finish(id, () -> { });
        harness.ends.settleDeferredRemoteEnd(id);

        assertEquals(SessionStatus.IDLE, row(harness, id).status(),
                "the deferred verdict lands once nothing owns the row");
        assertEquals(Optional.of(ALIVE), harness.liveness.state(id));
    }

    // ---- regression oracle: the local half is unchanged ------------------------

    /** A local row keeps today's settle: EXITED with the process's own exit code, no probe. */
    @Test
    void aLocalRowKeepsTodaySExitCodeSettle() {
        ManagedSessionId id = ManagedSessionId.newId();
        Harness harness = localHarness(localRow(id));

        harness.ends.executionTerminated(id, false, 7);

        assertEquals(SessionStatus.EXITED, row(harness, id).status());
        assertEquals(Optional.of(7), row(harness, id).lastExitCode(),
                "the local process's exit code is the agent's and is recorded");
        assertEquals(Optional.empty(), harness.liveness.state(id), "no liveness exists for local rows");
        assertEquals(List.of(id), harness.mcpAccess.released, "the local release still runs");
        assertEquals(List.of(), harness.runner.calls, "no box command ever runs for a local row");
    }
}
