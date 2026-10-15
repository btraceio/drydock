package app.drydock.mcp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The activity panel's ring buffer: bounded, but its counters are not. */
class McpActivityLogTest {

    private static McpActivityLog.Entry entry(String tool, int bytes) {
        return new McpActivityLog.Entry(Instant.EPOCH, McpActivityLog.Direction.INBOUND,
                tool, "{}", Optional.of("rs_a"), bytes, false);
    }

    @Test
    void entriesAreKeptOldestFirst() {
        McpActivityLog log = new McpActivityLog();

        log.record(entry("review_scope", 10));
        log.record(entry("review_finding", 20));

        assertEquals(List.of("review_scope", "review_finding"),
                log.entries().stream().map(McpActivityLog.Entry::tool).toList());
    }

    /**
     * A long review makes thousands of calls; keeping them all would be an
     * unbounded leak behind a panel nobody has open.
     */
    @Test
    void theBufferIsBounded() {
        McpActivityLog log = new McpActivityLog();

        for (int i = 0; i < 900; i++) {
            log.record(entry("review_finding", 1));
        }

        assertTrue(log.entries().size() <= 500, "expected a bounded buffer, got " + log.entries().size());
    }

    /** The budget bar reflects the whole session, not just what is still in the buffer. */
    @Test
    void theCountersSurviveEntriesFallingOffTheEnd() {
        McpActivityLog log = new McpActivityLog();

        for (int i = 0; i < 900; i++) {
            log.record(entry("review_finding", 100));
        }

        assertEquals(900, log.totalCalls());
        assertEquals(90_000, log.totalBytes());
    }

    @Test
    void listenersSeeEachEntryAndCanUnsubscribe() {
        McpActivityLog log = new McpActivityLog();
        List<String> seen = new ArrayList<>();
        Runnable unsubscribe = log.addListener(recorded -> seen.add(recorded.tool()));

        log.record(entry("review_scope", 1));
        unsubscribe.run();
        log.record(entry("review_state", 1));

        assertEquals(List.of("review_scope"), seen);
    }

    /** A panel that throws must not fail the tool call behind it. */
    @Test
    void aThrowingListenerDoesNotBreakTheRecord() {
        McpActivityLog log = new McpActivityLog();
        log.addListener(recorded -> {
            throw new IllegalStateException("boom");
        });

        log.record(entry("review_scope", 5));

        assertEquals(1, log.entries().size());
        assertEquals(5, log.totalBytes());
    }
}
