package app.drydock.intellij.tasks;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure per-invocation state machine: preparing, ready, waiting for an explicit
 * fallback, accepted, cancelled, committed. Every preparation gets a generation
 * token; a completion carrying a superseded or cancelled token is rejected, so late
 * work cannot accept, launch or overwrite the user's text. Edited text is never
 * recomposed; only an unedited generated seed follows a new preparation. Synchronized
 * so a background completion and the EDT cannot interleave a transition.
 *
 * @param <T> the prepared selection (opaque here)
 */
public final class WorkflowInvocationState<T> {

    public enum Phase { PREPARING, READY, WAITING_FALLBACK, ACCEPTED, CANCELLED, COMMITTED }

    /** Immutable UI input captured when a preparation starts. */
    public record Input(String target, String harness) {
        public Input {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(harness, "harness");
        }
    }

    /** What an accepted invocation launches with; frozen at acceptance. */
    public record Accepted<T>(Input input, T selection, boolean defaultOnce, String text, boolean edited) {
    }

    private Phase phase = Phase.PREPARING;
    private long generation;
    private Input input;
    private T selection;
    private boolean defaultOnce;
    private String text = "";
    private boolean edited;

    public WorkflowInvocationState(Input input) {
        begin(input);
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean isCurrent(long token) {
        return token == generation && phase != Phase.CANCELLED && phase != Phase.COMMITTED;
    }

    public synchronized String text() {
        return text;
    }

    public synchronized boolean edited() {
        return edited;
    }

    /** Starts (or restarts, for retry/harness switch) a preparation; returns its token. */
    public synchronized long begin(Input newInput) {
        requireOpen();
        input = Objects.requireNonNull(newInput, "input");
        selection = null;
        defaultOnce = false;
        phase = Phase.PREPARING;
        return ++generation;
    }

    /** Preparation finished; {@code seed} replaces the text only while it is unedited. */
    public synchronized boolean ready(long token, T prepared, String seed) {
        if (token != generation || phase != Phase.PREPARING) {
            return false;
        }
        selection = Objects.requireNonNull(prepared, "prepared");
        if (!edited) {
            text = Objects.requireNonNull(seed, "seed");
        }
        phase = Phase.READY;
        return true;
    }

    /** The selected source is unusable: wait for an explicit default-once or cancel. */
    public synchronized boolean waitForFallback(long token) {
        if (token != generation || phase != Phase.PREPARING) {
            return false;
        }
        phase = Phase.WAITING_FALLBACK;
        return true;
    }

    /** User chose the default for this invocation only; default seed follows unedited text. */
    public synchronized boolean chooseDefaultOnce(long token, String defaultSeed) {
        if (token != generation || phase != Phase.WAITING_FALLBACK) {
            return false;
        }
        defaultOnce = true;
        if (!edited) {
            text = Objects.requireNonNull(defaultSeed, "defaultSeed");
        }
        phase = Phase.READY;
        return true;
    }

    /** The user typed: from now on this text is authoritative. */
    public synchronized boolean edit(String newText) {
        if (phase == Phase.CANCELLED || phase == Phase.COMMITTED || phase == Phase.ACCEPTED) {
            return false;
        }
        text = Objects.requireNonNull(newText, "newText");
        edited = true;
        return true;
    }

    public synchronized Optional<Accepted<T>> accept(long token) {
        if (token != generation || phase != Phase.READY) {
            return Optional.empty();
        }
        phase = Phase.ACCEPTED;
        return Optional.of(new Accepted<>(input, selection, defaultOnce, text, edited));
    }

    /** Invalidates every pending token; false once the prompt has been committed to send. */
    public synchronized boolean cancel() {
        if (phase == Phase.COMMITTED) {
            return false;
        }
        if (phase != Phase.CANCELLED) {
            phase = Phase.CANCELLED;
            generation++;
        }
        return true;
    }

    /** Marks the accepted invocation as sent/spawned; cancellation can no longer undo it. */
    public synchronized boolean commit(long token) {
        if (token != generation || phase != Phase.ACCEPTED) {
            return false;
        }
        phase = Phase.COMMITTED;
        return true;
    }

    private void requireOpen() {
        if (phase == Phase.CANCELLED || phase == Phase.COMMITTED || phase == Phase.ACCEPTED) {
            throw new IllegalStateException("invocation is " + phase);
        }
    }
}
