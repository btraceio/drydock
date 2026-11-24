package app.drydock.editorqa;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import app.drydock.editorqa.EditorAskRecord.Status;

/**
 * The per-checkout FIFO and turn state machine of the editor ask feature.
 * One lane serves tab and editor turns of one checkout; lanes of different
 * checkouts share nothing.
 *
 * <p>The lane is pure bookkeeping: it never spawns, writes or blocks. A
 * mutator returns the {@link Reservation} the caller may now act on (or
 * null), and the caller performs the launch/PTY write itself, outside any
 * lane lock, reporting back with the reservation. The reservation is taken
 * before any spawn or write, so at most one turn can be in flight.</p>
 *
 * <p>Turn ownership is independent of the answer. The single holder keeps
 * the slot until a proven terminal event matching the execution identity,
 * generation, marker and a strictly increasing cursor, <em>and</em> a later
 * positive {@link EventKind#READY} for the same marker. Silence, a quiet
 * timeout, an answer, an IDLE sample or a delivery receipt never release
 * it. A deadline expiry is a failure, not proof the agent stopped: a turn
 * that may have reached the agent closes the gate until the execution is
 * proven ended ({@link #executionEnded}) and every queued entry settles
 * visibly as not sent. Late reservations, write outcomes and events are
 * fenced by token, execution identity and generation; a late completion
 * never demotes a committed answer.</p>
 *
 * <p>Deadlines expire when {@code now >= deadline}. Updates are delivered
 * to the sink outside the lane lock, serialized and in order.</p>
 */
public final class EditorAskLane {

    public static final int CAPACITY = EditorAskLimits.MAX_UNFINISHED_RECORDS;
    public static final Duration PREPARE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration TURN_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration ABSOLUTE_TIMEOUT = Duration.ofMinutes(15);

    /** LAUNCH carries the first turn as the launch prompt; WRITE is a live follow-up. */
    public enum Mode { LAUNCH, WRITE }

    public enum RejectReason { CAPACITY, GATE_CLOSED, DUPLICATE, SHUTDOWN }

    /** The right to act for one turn; {@code executionId} is null for LAUNCH until {@link #beginSend}. */
    public record Reservation(String id, long token, Mode mode, long generation, String executionId) { }

    public record Admission(boolean accepted, RejectReason rejection, Reservation reservation) { }

    /** A visible state change of one turn. */
    public record Update(String id, Status status, String label, String reason) { }

    public enum EventKind { SUBMITTED, END, ERROR, READY, ACTIVITY }

    /** A provider observation; {@code marker} is the turn ID (ignored for ACTIVITY). */
    public record Event(String executionId, long generation, EventKind kind, String marker, long cursor) { }

    public enum WriteOutcome { DELIVERED, REFUSED, FAILED }

    private enum Phase { QUEUED, PREPARING, SENDING, SUBMITTED, TERMINAL }

    private static final class Entry {
        final String id;
        final Instant admittedAt;
        Phase phase = Phase.QUEUED;
        Status status = Status.QUEUED;
        boolean rightRegistered;
        boolean answered;
        long token;
        Instant deadline;
        long terminalCursor;
        String published;
        String note;

        Entry(String id, Instant admittedAt) {
            this.id = id;
            this.admittedAt = admittedAt;
        }
    }

    private final EditorAskClock clock;
    private final Consumer<Update> sink;
    private final Object lock = new Object();
    private final Deque<Entry> queue = new ArrayDeque<>();
    private final Map<String, Entry> byId = new HashMap<>();
    private final List<Update> pending = new ArrayList<>();
    private final AtomicBoolean delivering = new AtomicBoolean();
    private Entry holder;
    private long tokens;
    private long generations;
    private String execId;
    private long execGen;
    private long lastCursor;
    private boolean ready;
    private boolean blocked;
    private boolean closed;

    public EditorAskLane(EditorAskClock clock, Consumer<Update> sink) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Admits a turn; the head is reserved at once when the gate allows, so labels are published before any I/O. */
    public Admission admit(String id) {
        Admission result;
        synchronized (lock) {
            if (closed) {
                return new Admission(false, RejectReason.SHUTDOWN, null);
            }
            if (byId.containsKey(id)) {
                return new Admission(false, RejectReason.DUPLICATE, null);
            }
            if (blocked) {
                return new Admission(false, RejectReason.GATE_CLOSED, null);
            }
            if (unfinished() >= CAPACITY) {
                return new Admission(false, RejectReason.CAPACITY, null);
            }
            Entry e = new Entry(Objects.requireNonNull(id, "id"), clock.now());
            queue.addLast(e);
            byId.put(id, e);
            Reservation next = pump();
            result = new Admission(true, null, next != null && next.id().equals(id) ? next : null);
            publishAll();
        }
        flush();
        return result;
    }

    /**
     * Called just before the launch or write is performed: binds the
     * execution identity and registers the answer right. False when the
     * reservation is stale or the execution does not match (nothing may
     * then be written).
     */
    public boolean beginSend(Reservation r, String executionId) {
        boolean ok = false;
        synchronized (lock) {
            Entry e = activeFor(r);
            if (e != null && e.phase == Phase.PREPARING && !closed && !blocked) {
                if (r.mode() == Mode.LAUNCH) {
                    execId = Objects.requireNonNull(executionId, "executionId");
                    execGen = r.generation();
                    lastCursor = 0;
                    ready = false;
                    ok = true;
                } else {
                    ok = Objects.equals(executionId, execId) && r.generation() == execGen;
                }
                if (ok) {
                    e.phase = Phase.SENDING;
                    e.status = Status.SUBMITTING;
                    e.rightRegistered = true;
                    e.deadline = clock.now().plus(SEND_TIMEOUT);
                }
            }
            publishAll();
        }
        flush();
        return ok;
    }

    /** The write/launch call returned. DELIVERED is no receipt; REFUSED is not sent; FAILED is ambiguous. */
    public Reservation writeReturned(Reservation r, WriteOutcome outcome) {
        Reservation next = null;
        synchronized (lock) {
            Entry e = activeFor(r);
            if (e != null && e.phase == Phase.SENDING && !e.answered) {
                if (outcome == WriteOutcome.REFUSED) {
                    settle(e, Status.FAILED_NOT_SENT, "Not sent: the agent refused the prompt. Retry.");
                    release();
                } else if (outcome == WriteOutcome.FAILED) {
                    interrupt(e, "The prompt may have reached the agent; stop the session before retrying.");
                }
            } else if (e != null && e.phase == Phase.SENDING && outcome == WriteOutcome.FAILED) {
                block();
            }
            next = pump();
            publishAll();
        }
        flush();
        return next;
    }

    /** Commits an answer if the right is registered for this exact turn and execution; never for a queued ID. */
    public boolean acceptAnswer(String id, String executionId, long generation) {
        boolean ok = false;
        synchronized (lock) {
            Entry e = holder;
            if (e != null && e.id.equals(id) && e.rightRegistered && !e.answered && !closed
                    && Objects.equals(executionId, execId) && generation == execGen) {
                e.answered = true;
                e.status = Status.ANSWERED;
                e.rightRegistered = false;
                ok = true;
            }
            publishAll();
        }
        flush();
        return ok;
    }

    /** Whether a call for {@code id} may arrive now (a right is registered for the holder). */
    public boolean hasAnswerRight(String id) {
        synchronized (lock) {
            return holder != null && holder.id.equals(id) && holder.rightRegistered && !holder.answered;
        }
    }

    /** A provider observation; stale, duplicate, foreign or unproven events change nothing. */
    public Reservation onEvent(Event ev) {
        Reservation next;
        synchronized (lock) {
            if (!closed && execId != null && execId.equals(ev.executionId()) && ev.generation() == execGen
                    && ev.cursor() > lastCursor) {
                lastCursor = ev.cursor();
                applyEvent(ev);
            }
            next = pump();
            publishAll();
        }
        flush();
        return next;
    }

    private void applyEvent(Event ev) {
        Entry e = holder;
        boolean mine = e != null && e.id.equals(ev.marker());
        switch (ev.kind()) {
            case SUBMITTED -> {
                if (mine && e.phase == Phase.SENDING) {
                    e.phase = Phase.SUBMITTED;
                    e.status = e.answered ? Status.ANSWERED : Status.AWAITING;
                    e.deadline = clock.now().plus(TURN_TIMEOUT);
                }
            }
            case END, ERROR -> {
                if (mine && (e.phase == Phase.SENDING || e.phase == Phase.SUBMITTED)) {
                    e.phase = Phase.TERMINAL;
                    e.terminalCursor = ev.cursor();
                    e.rightRegistered = false;
                    e.deadline = e.admittedAt.plus(ABSOLUTE_TIMEOUT);
                    if (!e.answered) {
                        settle(e, Status.NO_ANSWER, ev.kind() == EventKind.END
                                ? "No answer received." : "No answer received: the turn ended in an error.");
                    }
                }
            }
            case READY -> {
                if (mine && e.phase == Phase.TERMINAL && ev.cursor() > e.terminalCursor && !blocked) {
                    ready = true;
                    release();
                }
            }
            case ACTIVITY -> {
                if (holder == null) {
                    ready = false;
                } else if (e.phase == Phase.TERMINAL) {
                    e.terminalCursor = ev.cursor();
                }
            }
        }
    }

    /** Applies every expired deadline; returns the next reservation if one was released. */
    public Reservation poll() {
        Reservation next;
        synchronized (lock) {
            Instant now = clock.now();
            for (Entry q : new ArrayList<>(queue)) {
                if (!now.isBefore(q.admittedAt.plus(ABSOLUTE_TIMEOUT))) {
                    queue.remove(q);
                    settle(q, Status.FAILED_NOT_SENT, "Not sent: timed out waiting in the queue. Retry.");
                }
            }
            Entry h = holder;
            if (h != null && !closed) {
                Instant limit = h.admittedAt.plus(ABSOLUTE_TIMEOUT);
                Instant due = h.deadline == null || h.deadline.isAfter(limit) ? limit : h.deadline;
                if (!now.isBefore(due) && !(blocked && h.phase == Phase.TERMINAL)) {
                    expire(h);
                }
            }
            next = pump();
            publishAll();
        }
        flush();
        return next;
    }

    private void expire(Entry h) {
        switch (h.phase) {
            case PREPARING -> {
                settle(h, Status.FAILED_NOT_SENT, "Not sent: the agent did not start in time. Retry.");
                release();
            }
            case SENDING, SUBMITTED ->
                    interrupt(h, "Timed out; the prompt may have reached the agent. Stop the session before retrying.");
            case TERMINAL -> block();
            default -> { }
        }
    }

    /**
     * The execution is proven ended (process death or explicit stop). Its
     * rights are invalidated, the holder settles by what is known, and the
     * next head launches a new generation.
     */
    public Reservation executionEnded(String executionId, long generation) {
        Reservation next;
        synchronized (lock) {
            if (execId != null && execId.equals(executionId) && generation == execGen) {
                endExecution("The agent session ended.");
            }
            next = pump();
            publishAll();
        }
        flush();
        return next;
    }

    /** Shutdown: everything unfinished settles; nothing is ever sent again. */
    public void shutdown() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (holder != null) {
                endExecution("The IDE shut down.");
            }
            for (Entry q : new ArrayList<>(queue)) {
                queue.remove(q);
                settle(q, Status.FAILED_NOT_SENT, "Not sent: the IDE shut down. Retry.");
            }
            closed = true;
            publishAll();
        }
        flush();
    }

    public int unfinishedCount() {
        synchronized (lock) {
            return unfinished();
        }
    }

    // ---- state helpers (lock held) ----

    private void endExecution(String reason) {
        Entry h = holder;
        if (h != null) {
            h.rightRegistered = false;
            if (h.phase == Phase.SENDING && !h.answered) {
                settle(h, Status.INTERRUPTED_POSSIBLY_SENT, reason + " The prompt may have been sent.");
            } else if (h.phase == Phase.SUBMITTED && !h.answered) {
                settle(h, Status.NO_ANSWER, "No answer received: " + reason);
            } else if (h.phase == Phase.PREPARING) {
                settle(h, Status.FAILED_NOT_SENT, "Not sent: " + reason);
            }
            holder = null;
        }
        execId = null;
        ready = false;
        blocked = false;
    }

    private void interrupt(Entry e, String reason) {
        e.phase = Phase.TERMINAL;
        e.terminalCursor = Long.MAX_VALUE;
        if (!e.answered) {
            settle(e, Status.INTERRUPTED_POSSIBLY_SENT, reason);
        }
        block();
    }

    private void block() {
        blocked = true;
        for (Entry q : new ArrayList<>(queue)) {
            queue.remove(q);
            settle(q, Status.FAILED_NOT_SENT,
                    "Not sent: an earlier question may still be running. Stop the session and retry.");
        }
    }

    private void release() {
        if (holder != null) {
            holder.rightRegistered = false;
            byId.remove(holder.id);
            holder = null;
        }
    }

    private void settle(Entry e, Status s, String reason) {
        e.status = s;
        e.note = reason;
        pending.add(new Update(e.id, s, reason, reason));
        e.published = s + "|" + reason;
        if (e != holder) {
            byId.remove(e.id);
        }
    }

    private Entry activeFor(Reservation r) {
        Entry e = holder;
        return e != null && e.id.equals(r.id()) && e.token == r.token() ? e : null;
    }

    private int unfinished() {
        return queue.size() + (holder != null ? 1 : 0);
    }

    private Reservation pump() {
        if (closed || blocked || holder != null || queue.isEmpty()) {
            return null;
        }
        Mode mode;
        long gen;
        if (execId == null) {
            mode = Mode.LAUNCH;
            gen = ++generations;
        } else if (ready) {
            mode = Mode.WRITE;
            gen = execGen;
        } else {
            return null;
        }
        Entry e = queue.pollFirst();
        e.phase = Phase.PREPARING;
        e.status = Status.SUBMITTING;
        e.token = ++tokens;
        e.deadline = clock.now().plus(PREPARE_TIMEOUT);
        holder = e;
        return new Reservation(e.id, e.token, mode, gen, mode == Mode.WRITE ? execId : null);
    }

    private void publishAll() {
        int ahead = 0;
        List<Entry> all = new ArrayList<>();
        if (holder != null) {
            all.add(holder);
        }
        all.addAll(queue);
        for (Entry e : all) {
            String label = label(e, ahead);
            String key = e.status + "|" + label;
            if (!key.equals(e.published)) {
                e.published = key;
                pending.add(new Update(e.id, e.status, label, null));
            }
            ahead++;
        }
    }

    private static String label(Entry e, int ahead) {
        if (e.phase == Phase.QUEUED) {
            return "Queued, " + ahead + " ahead";
        }
        if (e.answered) {
            return "Answered";
        }
        if (e.note != null) {
            return e.note;
        }
        return switch (e.status) {
            case AWAITING -> "Waiting for answer…";
            case SUBMITTING -> "Starting…";
            default -> e.status.name();
        };
    }

    private void flush() {
        while (delivering.compareAndSet(false, true)) {
            try {
                while (true) {
                    List<Update> batch;
                    synchronized (lock) {
                        batch = new ArrayList<>(pending);
                        pending.clear();
                    }
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (Update u : batch) {
                        try {
                            sink.accept(u);
                        } catch (RuntimeException ignored) {
                            // a failing sink must not wedge the lane
                        }
                    }
                }
            } finally {
                delivering.set(false);
            }
            synchronized (lock) {
                if (pending.isEmpty()) {
                    return;
                }
            }
        }
    }
}
