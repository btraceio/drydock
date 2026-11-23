package app.drydock.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The paused-batch remainder: JSON round-trip, foreign-system refusal, malformed-input tolerance. */
class BatchTriageQueueTest {

    @Test
    void roundTripsThroughJson() {
        BatchTriageQueue queue = new BatchTriageQueue("GitHub", List.of(
                new BatchTriageQueue.Entry("7", "Broken build", "https://github.com/o/r/issues/7"),
                new BatchTriageQueue.Entry("9", "Flaky test", "")));
        Optional<String> json = queue.toJson();
        assertTrue(json.isPresent());

        Optional<BatchTriageQueue> read = BatchTriageQueue.fromJson("GitHub", json.get());
        assertEquals(Optional.of(queue), read);
    }

    @Test
    void anEmptyQueueHasNothingToStore() {
        assertTrue(new BatchTriageQueue("Jira", List.of()).toJson().isEmpty());
    }

    @Test
    void aForeignSystemNameNeverResumes() {
        String json = new BatchTriageQueue("Jira", List.of(
                new BatchTriageQueue.Entry("A-1", "Summary", "https://jira/browse/A-1"))).toJson().orElseThrow();
        assertTrue(BatchTriageQueue.fromJson("GitHub", json).isEmpty());
    }

    @Test
    void malformedAndAbsentInputsAreEmptyNeverFailures() {
        assertTrue(BatchTriageQueue.fromJson("GitHub", null).isEmpty());
        assertTrue(BatchTriageQueue.fromJson("GitHub", "").isEmpty());
        assertTrue(BatchTriageQueue.fromJson("GitHub", "not json").isEmpty());
        assertTrue(BatchTriageQueue.fromJson("GitHub", "{\"system\":\"GitHub\"}").isEmpty());
        assertTrue(BatchTriageQueue.fromJson("GitHub",
                "{\"system\":\"GitHub\",\"entries\":[{\"title\":\"no key\"}]}").isEmpty());
    }

    @Test
    void blankTitleAndUrlAreToleratedOnRead() {
        Optional<BatchTriageQueue> read = BatchTriageQueue.fromJson("GitHub",
                "{\"system\":\"GitHub\",\"entries\":[{\"key\":\"12\"}]}");
        assertEquals(Optional.of(new BatchTriageQueue("GitHub",
                List.of(new BatchTriageQueue.Entry("12", "", "")))), read);
    }
}
