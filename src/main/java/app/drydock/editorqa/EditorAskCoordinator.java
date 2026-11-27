package app.drydock.editorqa;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskHarnessPolicy.Disabled;
import app.drydock.editorqa.EditorAskHarnessPolicy.Fresh;
import app.drydock.editorqa.EditorAskHarnessPolicy.FreshResolution;
import app.drydock.editorqa.EditorAskLane.Admission;
import app.drydock.editorqa.EditorAskLane.Reservation;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchRequest;
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.Resumability;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskPorts.Submission;
import app.drydock.editorqa.EditorAskPorts.Wiring;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskRowBinder.RowException;
import app.drydock.mcp.EditorAskAnswerDelegate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The application-owned, headless coordinator of editor questions. Admission
 * is keyed per canonical checkout and serialized: each ask is admitted in
 * order (the acceptance sequence is allocated there), its marked row reserved
 * and made durable, its record made durable, and only then handed to that
 * checkout's {@link EditorAskLane}, which owns reservation, deadlines and
 * turn ownership. Dispatch (launch or live write) runs off the admission
 * queue so a slow launch never blocks admitting the next question, and
 * different checkouts share nothing.
 *
 * <p>It is also the {@link EditorAskAnswerDelegate}: the one place an answer
 * is committed. The answer right is registered by the lane before the launch
 * is attempted, so an agent that answers before the launch call returns is
 * still matched (the credential generation is adopted from the registry,
 * which vouches for it, when the launch has not reported it yet).</p>
 *
 * <p>No path here sends automatically at startup or shutdown: unfinished
 * records from a previous run are loaded as interrupted by the store, and
 * after {@link #close()} no reservation is acted on.</p>
 */
public final class EditorAskCoordinator implements EditorAskAnswerDelegate, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EditorAskCoordinator.class.getName());
    private static final int REASON_LIMIT = 400;

    /** The live launch of a checkout: lane generation, provider and the (late-known) MCP generation. */
    private static final class Exec {
        final String executionId;
        final long laneGeneration;
        final AgentKind kind;
        long credentialGeneration = -1;

        Exec(String executionId, long laneGeneration, AgentKind kind) {
            this.executionId = executionId;
            this.laneGeneration = laneGeneration;
            this.kind = kind;
        }
    }

    private final class Checkout {
        final String root;
        final EditorAskLane lane;
        final Serial admission = new Serial(executor);
        final Object commitLock = new Object();
        volatile ManagedSessionId row;
        Exec exec;

        Checkout(String root) {
            this.root = root;
            this.lane = new EditorAskLane(clock, update -> onLaneUpdate(this, update));
        }
    }

    /** Runs submitted tasks one at a time, in order, on the shared executor. */
    private static final class Serial {
        private record QueuedTask(Runnable task, Runnable onRejected) {
        }

        private final Executor base;
        private final ArrayDeque<QueuedTask> tasks = new ArrayDeque<>();
        private boolean running;

        Serial(Executor base) {
            this.base = base;
        }

        void execute(Runnable task, Runnable onRejected) {
            synchronized (this) {
                tasks.add(new QueuedTask(task, onRejected));
                if (running) {
                    return;
                }
                running = true;
            }
            try {
                base.execute(this::drain);
            } catch (RuntimeException e) {
                ArrayDeque<QueuedTask> rejected;
                synchronized (this) {
                    rejected = new ArrayDeque<>(tasks);
                    tasks.clear();
                    running = false;
                }
                LOG.log(Level.WARNING, "editor ask admission could not be scheduled", e);
                for (QueuedTask queued : rejected) {
                    try {
                        queued.onRejected().run();
                    } catch (Throwable callbackFailure) {
                        LOG.log(Level.WARNING, "editor ask admission rejection callback failed", callbackFailure);
                    }
                }
            }
        }

        private void drain() {
            while (true) {
                QueuedTask next;
                synchronized (this) {
                    next = tasks.poll();
                    if (next == null) {
                        running = false;
                        return;
                    }
                }
                try {
                    next.task().run();
                } catch (Throwable e) {
                    LOG.log(Level.WARNING, "editor ask admission task failed", e);
                }
            }
        }
    }

    private final EditorAskHistoryStore history;
    private final EditorAskRowBinder rows;
    private final Wiring wiring;
    private final EditorAskClock clock;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Map<String, Checkout> checkouts = new ConcurrentHashMap<>();
    private final Map<ManagedSessionId, Checkout> byRow = new ConcurrentHashMap<>();
    private final Map<String, String> reasonOverrides = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public EditorAskCoordinator(EditorAskHistoryStore history, EditorAskRowBinder rows, Wiring wiring,
                                EditorAskClock clock) {
        this(history, rows, wiring, clock, null);
    }

    /** Test seam: a custom executor (null = an owned virtual-thread executor). */
    EditorAskCoordinator(EditorAskHistoryStore history, EditorAskRowBinder rows, Wiring wiring,
                         EditorAskClock clock, Executor executor) {
        this.history = Objects.requireNonNull(history, "history");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.wiring = Objects.requireNonNull(wiring, "wiring");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (executor == null) {
            this.ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.executor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.executor = executor;
        }
    }

    // ---- admission ----

    /**
     * Admits one confirmed question. The future completes once the row and
     * the record are durable (or the ask is refused visibly); the send, if
     * any, happens afterwards on its own task. Never blocks the caller.
     */
    public CompletableFuture<Result> submit(Submission s) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        if (closed) {
            out.complete(Result.refused("Drydock is shutting down; the question was not sent."));
            return out;
        }
        Checkout c = checkouts.computeIfAbsent(s.canonicalRoot(), Checkout::new);
        c.admission.execute(() -> {
            try {
                out.complete(admit(c, s));
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "editor ask admission failed", e);
                out.complete(Result.refused("The question could not be admitted; it was not sent."));
            }
        }, () -> out.complete(Result.refused("The question could not be admitted; it was not sent.")));
        return out;
    }

    private Result admit(Checkout c, Submission s) {
        if (closed) {
            return Result.refused("Drydock is shutting down; the question was not sent.");
        }
        ManagedAgentSession row;
        EditorAskHistoryStore.LoadResult loaded;
        try {
            loaded = history.load(c.root);
            if (loaded.problem() != null) {
                return recovery(c, "The Q&A history for this checkout is unusable: " + loaded.problem());
            }
            row = rows.reserve(s.candidateRow());
            rows.checkOwner(row.id(), loaded.records());
        } catch (RowException e) {
            return recovery(c, e.getMessage());
        }
        c.row = row.id();
        byRow.put(row.id(), c);
        String id = EditorAskRecord.newId();
        long sequence = history.lastSequence(c.root) + 1;
        EditorAskRecord record = new EditorAskRecord(id, sequence, row.id(), s.relativeFile(), s.range(),
                s.selectedText(), s.question(), clock.now(), Status.ACCEPTED, null, null, null, s.anchor());
        try {
            history.admit(c.root, record).join();
        } catch (RuntimeException e) {
            return recovery(c, "The question could not be saved; it was not sent.");
        }
        Admission admission = c.lane.admit(id);
        if (!admission.accepted()) {
            String why = "Not sent: " + switch (admission.rejection()) {
                case CAPACITY -> "this checkout already has too many unfinished questions.";
                case GATE_CLOSED -> "an earlier question may still be running. Stop the session and retry.";
                case DUPLICATE -> "duplicate question.";
                case SHUTDOWN -> "Drydock is shutting down.";
            };
            settleDirect(c, id, Status.FAILED_NOT_SENT, why);
        } else {
            dispatch(c, admission.reservation());
        }
        return Result.accepted(id, sequence, row.id());
    }

    private Result recovery(Checkout c, String message) {
        wiring.events().recoveryError(c.root, message);
        return Result.refused(message);
    }

    private void settleDirect(Checkout c, String id, Status status, String reason) {
        String safe = trim(reason);
        history.update(c.root, id, r -> r.isSettled() ? r : r.settled(status, safe))
                .whenComplete((r, e) -> published(c, id, r, e));
        wiring.events().progress(c.root, id, status, safe, safe);
    }

    private static String trim(String reason) {
        return reason.length() <= REASON_LIMIT ? reason : reason.substring(0, REASON_LIMIT);
    }

    private void published(Checkout c, String id, EditorAskRecord record, Throwable error) {
        try {
            if (error != null) {
                wiring.events().recoveryError(c.root, "A question's state could not be saved.");
            } else {
                wiring.events().recorded(c.root, record);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor ask event listener failed", e);
        }
    }

    // ---- dispatch ----

    private void dispatch(Checkout c, Reservation r) {
        if (r == null || closed) {
            return;
        }
        try {
            executor.execute(() -> run(c, r));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor ask dispatch could not be scheduled", e);
        }
    }

    private void run(Checkout c, Reservation r) {
        try {
            if (closed) {
                return;
            }
            EditorAskRecord record = history.records(c.root).stream()
                    .filter(x -> x.id().equals(r.id())).findFirst().orElse(null);
            if (record == null) {
                return;
            }
            String prompt = EditorAskPrompt.compose(record);
            if (r.mode() == EditorAskLane.Mode.WRITE) {
                runWrite(c, r, prompt);
            } else {
                runLaunch(c, r, prompt);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor ask dispatch failed", e);
        }
    }

    private void runWrite(Checkout c, Reservation r, String prompt) {
        if (!c.lane.beginSend(r, r.executionId())) {
            return;
        }
        WriteOutcome outcome;
        try {
            outcome = wiring.launcher().write(r.executionId(), r.generation(), prompt);
        } catch (RuntimeException e) {
            outcome = WriteOutcome.FAILED;
        }
        dispatch(c, c.lane.writeReturned(r, outcome));
    }

    private record Plan(AgentKind harness, Optional<String> model, Optional<String> resume, String refusal) {
    }

    private void runLaunch(Checkout c, Reservation r, String prompt) {
        String executionId = "qa-" + UUID.randomUUID();
        Plan plan;
        try {
            plan = plan(c);
        } catch (RuntimeException e) {
            plan = new Plan(null, Optional.empty(), Optional.empty(), "the agent could not be prepared.");
        }
        if (!c.lane.beginSend(r, executionId)) {
            return;
        }
        if (plan.refusal() != null) {
            refuse(c, r, executionId, plan.refusal());
            return;
        }
        Exec exec = new Exec(executionId, r.generation(), plan.harness());
        synchronized (c.commitLock) {
            c.exec = exec;
        }
        LaunchResult result;
        try {
            result = wiring.launcher().launch(new LaunchRequest(c.row, c.root, executionId, r.generation(),
                    plan.harness(), plan.model(), plan.resume(), prompt));
        } catch (RuntimeException e) {
            result = new LaunchResult(LaunchOutcome.FAILED, -1, null);
        }
        if (closed) {
            stopQuietly(executionId);
        }
        switch (result.outcome()) {
            case STARTED -> {
                synchronized (c.commitLock) {
                    if (exec.credentialGeneration < 0 && result.credentialGeneration() >= 0) {
                        exec.credentialGeneration = result.credentialGeneration();
                    }
                }
                dispatch(c, c.lane.writeReturned(r, WriteOutcome.DELIVERED));
            }
            case REFUSED -> refuse(c, r, executionId, result.reason() == null ? "the agent was not started." : result.reason());
            case FAILED -> dispatch(c, c.lane.writeReturned(r, WriteOutcome.FAILED));
        }
    }

    /** Nothing reached an agent: settle not sent, end the (never started) execution, move on. */
    private void refuse(Checkout c, Reservation r, String executionId, String reason) {
        synchronized (c.commitLock) {
            if (c.exec != null && c.exec.executionId.equals(executionId)) {
                c.exec = null;
            }
        }
        reasonOverrides.put(r.id(), trim("Not sent: " + reason));
        c.lane.writeReturned(r, WriteOutcome.REFUSED);
        reasonOverrides.remove(r.id());
        dispatch(c, c.lane.executionEnded(executionId, r.generation()));
    }

    /** Resume exactly when the bound conversation is provable; otherwise say so and start fresh. */
    private Plan plan(Checkout c) {
        Optional<ManagedAgentSession> current = rows.current(c.row);
        if (current.isEmpty()) {
            return new Plan(null, Optional.empty(), Optional.empty(), "the Q&A session no longer exists.");
        }
        ManagedAgentSession row = current.get();
        Optional<String> conversation = row.binding().sessionId();
        if (conversation.isPresent()) {
            AgentKind bound = row.binding().kind();
            if (wiring.launcher().verifyConversation(row.id(), bound, conversation.get()) == Resumability.EXACT) {
                return new Plan(EditorAskHarnessPolicy.keepOriginal(bound).harness(), Optional.empty(),
                        conversation, null);
            }
            rows.clearConversation(row.id());
            wiring.events().notice(c.root, row.id(), "Provider conversation context lost: the earlier "
                    + bound.persistedName() + " conversation cannot be resumed, so this question starts a "
                    + "new conversation under the current settings.");
        }
        FreshResolution fresh = EditorAskHarnessPolicy.resolveFresh(wiring.settings().choice(),
                wiring.settings()::available, wiring.settings()::model);
        if (fresh instanceof Disabled d) {
            return new Plan(null, Optional.empty(), Optional.empty(), d.reason());
        }
        Fresh f = (Fresh) fresh;
        rows.useHarness(row.id(), f.harness());
        return new Plan(f.harness(), f.model(), Optional.empty(), null);
    }

    private void stopQuietly(String executionId) {
        try {
            wiring.launcher().stop(executionId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "stopping an editor ask execution failed", e);
        }
    }

    // ---- lane updates, provider observations, answers ----

    private void onLaneUpdate(Checkout c, EditorAskLane.Update u) {
        String override = reasonOverrides.get(u.id());
        String reason = override != null ? override : u.reason();
        String label = override != null ? override : u.label();
        try {
            wiring.events().progress(c.root, u.id(), u.status(), label, reason);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor ask event listener failed", e);
        }
        if (u.status() == Status.ANSWERED) {
            return; // an answer is persisted by commit(), with its text and server time, never by a label
        }
        Status status = u.status();
        String why = status.isSettled() ? trim(reason == null ? label : reason) : null;
        history.update(c.root, u.id(), r -> r.isSettled() ? r : r.settled(status, why))
                .whenComplete((r, e) -> published(c, u.id(), r, e));
    }

    /** A provider observation (submitted/end/error/ready) for the checkout's current launch. */
    public void onEvent(String root, EditorAskLane.Event event) {
        Checkout c = checkouts.get(root);
        if (c != null && !closed) {
            dispatch(c, c.lane.onEvent(event));
        }
    }

    /** The execution is proven ended; a late report for a replaced launch changes nothing. */
    public void onExecutionEnded(String root, String executionId, long generation) {
        Checkout c = checkouts.get(root);
        if (c == null) {
            return;
        }
        synchronized (c.commitLock) {
            if (c.exec != null && c.exec.executionId.equals(executionId) && c.exec.laneGeneration == generation) {
                c.exec = null;
            }
        }
        dispatch(c, c.lane.executionEnded(executionId, generation));
    }

    /**
     * A provider reports the exact conversation ID of a launch. It is saved
     * only when it comes from the checkout's current launch, generation and
     * provider; a late discovery for a replaced launch is ignored. Blocking
     * (catalog write): background thread only.
     */
    public boolean onConversationProven(String root, String executionId, long generation, AgentKind kind,
                                        String conversationId) {
        Checkout c = checkouts.get(root);
        if (c == null || closed) {
            return false;
        }
        synchronized (c.commitLock) {
            Exec e = c.exec;
            if (e == null || !e.executionId.equals(executionId) || e.laneGeneration != generation || e.kind != kind) {
                return false;
            }
            return rows.saveConversation(c.row, kind, conversationId);
        }
    }

    /** Applies expired deadlines on every checkout; the host calls it periodically. */
    public void poll() {
        for (Checkout c : checkouts.values()) {
            if (!closed) {
                dispatch(c, c.lane.poll());
            }
        }
    }

    /**
     * The MCP commit: the answer is consumed atomically by the lane (right
     * registered for exactly this turn, execution and generation), then
     * persisted before success is reported. A save failure is not success
     * and never reopens the right: the agent cannot submit a second time.
     */
    @Override
    public boolean commit(ManagedSessionId caller, long generation, String questionId, String answer) {
        Checkout c = byRow.get(caller);
        if (c == null || closed) {
            return false;
        }
        Instant at;
        synchronized (c.commitLock) {
            Exec e = c.exec;
            if (e == null || !wiring.credentials().isCurrent(caller, generation)
                    || (e.credentialGeneration >= 0 && e.credentialGeneration != generation)) {
                return false;
            }
            if (!c.lane.acceptAnswer(questionId, e.executionId, e.laneGeneration)) {
                return false;
            }
            e.credentialGeneration = generation;
            at = clock.now();
        }
        try {
            EditorAskRecord saved = history.update(c.root, questionId, r -> r.answered(answer, at)).join();
            wiring.events().recorded(c.root, saved);
            return true;
        } catch (RuntimeException ex) {
            wiring.events().recoveryError(c.root, "An answer arrived but could not be saved; "
                    + "Drydock keeps it in memory and retries. The question is not asked again.");
            history.retrySave(c.root);
            return false;
        }
    }

    /** Why an ordinary Forget must not remove {@code session}, if it must not. Blocking: background thread only. */
    public Optional<String> forgetProtection(ManagedAgentSession session) {
        boolean retains;
        try {
            String root = session.workingDirectory().toString();
            retains = !history.records(root).isEmpty();
        } catch (RuntimeException e) {
            retains = true;
        }
        return EditorAskRowBinder.forgetProtection(session, retains);
    }

    /**
     * Shutdown: unfinished turns settle (nothing is sent again), live
     * executions are stopped, persistence drains. Each step is isolated.
     */
    @Override
    public void close() {
        closed = true;
        for (Checkout c : checkouts.values()) {
            String executionId;
            synchronized (c.commitLock) {
                executionId = c.exec == null ? null : c.exec.executionId;
                c.exec = null;
            }
            try {
                c.lane.shutdown();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "editor ask lane shutdown failed", e);
            }
            if (executionId != null) {
                stopQuietly(executionId);
            }
        }
        try {
            history.flushPendingSaves();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "editor ask history flush failed", e);
        }
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }
}
