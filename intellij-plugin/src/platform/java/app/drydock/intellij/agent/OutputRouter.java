package app.drydock.intellij.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * The single path an agent's output takes: into its {@link ReplayBuffer},
 * and on to whichever console shows the agent right now.
 *
 * <p>One lock covers both the append-and-forward and the replay-and-swap.
 * That is what makes a move exact: a chunk is either in the snapshot the new
 * console is filled with, or arrives at the new console after it — never
 * both, never neither. The spike's mid-stream moves (3000 lines, 14 runs)
 * held at zero lost and zero doubled lines this way.</p>
 */
final class OutputRouter {

    /** Where output is shown: a console, in production. */
    interface Sink {
        void print(String text);
    }

    private final Object lock = new Object();
    private final ReplayBuffer buffer;
    private Sink current;

    OutputRouter(ReplayBuffer buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    /** One chunk of the agent's output, on the process reader's thread. */
    void onText(String text) {
        synchronized (lock) {
            buffer.append(text);
            if (current != null) {
                current.print(text);
            }
        }
    }

    /**
     * Fills {@code next} with the history and makes it the one that gets the
     * live output; returns the sink it replaced. {@code afterReplay} runs
     * once, after the lock is released: it asks the agent to repaint, since
     * a trimmed history can lack the escape sequences that set its screen up.
     */
    Optional<Sink> attach(Sink next, Runnable afterReplay) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(afterReplay, "afterReplay");
        Sink previous;
        synchronized (lock) {
            previous = current;
            String history = buffer.snapshot();
            if (!history.isEmpty()) {
                next.print(history);
            }
            current = next;
        }
        afterReplay.run();
        return Optional.ofNullable(previous);
    }

    /** No sink any more; output is still buffered. Returns the sink that was current. */
    Optional<Sink> detach() {
        synchronized (lock) {
            Sink previous = current;
            current = null;
            return Optional.ofNullable(previous);
        }
    }
}
