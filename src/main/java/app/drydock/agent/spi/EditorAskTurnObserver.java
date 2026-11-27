package app.drydock.agent.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import app.drydock.editorqa.EditorAskRecord;

/**
 * The Q&A-only turn evidence a provider contributes for a marked editor-ask
 * launch. It is deliberately <em>not</em> an {@link app.drydock.agent.api.ActivityReporter}:
 * activity badges sample overwriteable state words and are read through
 * {@code SessionActivityMonitor}; an observer instead yields bounded, ordered,
 * atomic receipts that carry the exact turn marker, and they flow to the
 * Q&A coordinator, never through the activity monitor.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Launch nonce.</b> Each launch has a fresh, <em>nonsecret</em> nonce
 *       ({@link #launchNonce()}); receipts of another launch (stale files,
 *       a replaced execution) do not carry it and are ignored.</li>
 *   <li><b>Armed before spawn/write.</b> The coordinator {@link #arm arms} a
 *       turn marker before it spawns the process or writes the prompt. Arming
 *       fixes the turn's fence: every receipt already present when the turn is
 *       armed is stale for it. A marker can be armed once.</li>
 *   <li><b>Ordered evidence.</b> Events carry a monotonic, strictly increasing
 *       sequence. {@link #read(long)} returns the events after a cursor and a
 *       new cursor; reading with the same cursor again returns the same
 *       events (no consumption by reading).</li>
 *   <li><b>Per turn: BOUND, then one SETTLED.</b> {@link Kind#BOUND} says the
 *       exact marker was bound to a run of the conversation named by the event;
 *       {@link Kind#SETTLED} is the single terminal event for that marker,
 *       emitted only after continuations, retries and compaction are over.
 *       A foreign marker, a duplicate, an out-of-order or stale event never
 *       surfaces.</li>
 *   <li><b>Bounded.</b> A limit hit is {@link Observation#unavailable()}, never
 *       an empty or partial result presented as complete.</li>
 *   <li><b>No secrets.</b> Events hold the marker and conversation ID only:
 *       no credential, endpoint, prompt text, selected code or answer.</li>
 * </ul>
 */
public interface EditorAskTurnObserver extends AutoCloseable {

    /** Most turn markers one observer (one launch) can hold armed. */
    int MAX_ARMED_MARKERS = 64;

    /** Most events {@link #read} can return in one call. */
    int MAX_EVENTS_PER_READ = 256;

    /** What happened to a marked turn. */
    enum Kind {
        /** The exact marker was bound to a run of {@link Event#conversationId()}. */
        BOUND,
        /** The terminal event of the turn: the run, with its continuations, is over. */
        SETTLED
    }

    /**
     * One piece of evidence.
     *
     * @param sequence       monotonic position, {@code > 0}
     * @param kind           what happened
     * @param marker         the canonical editor-ask turn ID
     * @param conversationId the provider's exact conversation ID (required)
     */
    record Event(long sequence, Kind kind, String marker, String conversationId) {
        public Event {
            if (sequence <= 0) {
                throw new IllegalArgumentException("sequence must be > 0, got " + sequence);
            }
            Objects.requireNonNull(kind, "kind");
            EditorAskRecord.parseId(Objects.requireNonNull(marker, "marker"));
            if (Objects.requireNonNull(conversationId, "conversationId").isBlank()
                    || conversationId.length() > 256) {
                throw new IllegalArgumentException("conversation ID must be nonblank and at most 256 chars");
            }
        }
    }

    /**
     * A read result.
     *
     * @param events      events with sequence greater than the cursor, ordered
     * @param cursor      pass to the next {@link #read}; never lower than the argument
     * @param unavailable present when a bound was hit or the evidence source is
     *                    unreadable: the events are then not proof of absence
     */
    record Observation(List<Event> events, long cursor, Optional<String> unavailable) {
        public Observation {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            Objects.requireNonNull(unavailable, "unavailable");
        }
    }

    /** The fresh, nonsecret nonce of this launch. */
    String launchNonce();

    /**
     * Arms {@code marker} and returns its fence: events of this marker must have a
     * sequence above it. Call before the process is spawned or the prompt written.
     *
     * @throws IllegalArgumentException if the marker is not canonical or already armed
     * @throws IllegalStateException    if the armed-marker bound is reached or the
     *                                  observer is closed
     */
    long arm(String marker);

    /** Events after {@code cursor} for armed markers; see the type contract. */
    Observation read(long cursor);

    /** Releases the evidence source. Idempotent; never throws. */
    @Override
    void close();
}
