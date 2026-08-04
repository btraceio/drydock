package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A missing or unauthenticated gh does not fail the assembly -- every fetch
 * failure is absorbed into Fetch(value, complete=false), so the future
 * completes successfully with fewer items. That completeness is the only
 * signal an empty queue has, and it used to stay inside the service.
 */
class ReviewQueueCompletenessTest {

    @Test
    void anAssemblyIsCompleteOnlyWhenEverySourceAnswered() {
        assertTrue(new QueueAssembly(List.of(), true, true).complete());
        assertFalse(new QueueAssembly(List.of(), true, false).complete());
        assertFalse(new QueueAssembly(List.of(), false, true).complete());
    }

    @Test
    void anIncompleteAssemblyStillCarriesWhatItDidFind() {
        QueueAssembly assembly = new QueueAssembly(List.of(), true, false);

        assertTrue(assembly.items().isEmpty());
        assertTrue(assembly.localComplete(), "git answered even though gh did not");
    }
}
