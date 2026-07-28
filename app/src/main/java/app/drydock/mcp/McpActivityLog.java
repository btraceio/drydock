package app.drydock.mcp;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A bounded ring buffer of MCP traffic, for the Review destination's
 * activity panel (spec §4.7): the wiring made visible, and the thing you
 * read first when a reviewer is not doing what you expected.
 *
 * <p>Bounded on purpose. A long review can make thousands of calls, and a
 * panel that keeps them all would be an unbounded leak behind a UI nobody
 * has open. The oldest entries fall off; the counters do not, so the budget
 * bar still reflects the whole session.</p>
 *
 * <p>Thread-safe: the MCP server writes from its request threads, the UI
 * reads on the FX thread.</p>
 */
public final class McpActivityLog {

    /** How many entries the panel can show before the oldest fall off. */
    private static final int CAPACITY = 500;

    /** Which way a call went, as the panel's arrow column shows it. */
    public enum Direction {
        /** An agent wrote to drydock ({@code ←} in the panel). */
        INBOUND("←"),
        /** drydock answered, or a human action was recorded ({@code →}). */
        OUTBOUND("→");

        private final String glyph;

        Direction(String glyph) {
            this.glyph = glyph;
        }

        public String glyph() {
            return glyph;
        }
    }

    /** One logged call: what it was, when, how big, and whether it failed. */
    public record Entry(Instant at, Direction direction, String tool, String detail,
                        Optional<String> scopeId, int responseBytes, boolean failed) {
        public Entry {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(scopeId, "scopeId");
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();
    private long totalBytes;
    private long totalCalls;

    /** Records one call. Safe to call from any thread. */
    public void record(Entry entry) {
        synchronized (this) {
            entries.addLast(entry);
            while (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
            totalBytes += entry.responseBytes();
            totalCalls++;
        }
        // Fired outside the monitor: a listener rebuilding an FX panel must
        // not hold the lock the server's request threads are writing under.
        for (Consumer<Entry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException e) {
                // A panel that throws must not fail the tool call behind it.
            }
        }
    }

    /** The entries currently held, oldest first. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Total response bytes over the whole session, including entries that have fallen off. */
    public synchronized long totalBytes() {
        return totalBytes;
    }

    /** Total calls over the whole session, including entries that have fallen off. */
    public synchronized long totalCalls() {
        return totalCalls;
    }

    /** Subscribes to new entries; the returned runnable unsubscribes. */
    public Runnable addListener(Consumer<Entry> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
