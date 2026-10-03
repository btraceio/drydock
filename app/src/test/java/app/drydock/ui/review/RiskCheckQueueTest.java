package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.review.tour.CheckProgress;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue sends one risk check at a time and hands a silent one back as unavailable. */
class RiskCheckQueueTest extends ApplicationTest {

    private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());
    private final List<String> timedOut = Collections.synchronizedList(new ArrayList<>());
    private volatile SessionActivity activity = SessionActivity.IDLE;
    private volatile boolean dispatchSucceeds = true;

    private final RiskCheckQueue.Dispatcher dispatcher = new RiskCheckQueue.Dispatcher() {
        @Override
        public SessionActivity activity(String scopeId) {
            return activity;
        }

        @Override
        public boolean dispatch(String scopeId, String checkId) {
            dispatched.add(scopeId + "/" + checkId);
            return dispatchSucceeds;
        }

        @Override
        public void timedOut(String scopeId, String checkId) {
            timedOut.add(scopeId + "/" + checkId);
        }
    };

    @Override
    public void start(Stage stage) {
        // The queue needs only the toolkit running.
    }

    private RiskCheckQueue queue() throws Exception {
        return onFx(() -> new RiskCheckQueue(dispatcher));
    }

    private <T> T onFx(Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(5, TimeUnit.SECONDS);
    }

    private void onFx(Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(5, TimeUnit.SECONDS);
    }

    private void waitFor(Callable<Boolean> condition) throws TimeoutException {
        WaitForAsyncUtils.waitFor(3, TimeUnit.SECONDS, condition);
    }

    @Test
    void onlyOneRequestIsInFlightAndTheNextFollowsTheVerdict() throws Exception {
        RiskCheckQueue queue = queue();

        onFx(() -> {
            queue.enqueue("rs", "c1");
            queue.enqueue("rs", "c2");
        });
        assertEquals(List.of("rs/c1"), dispatched);

        onFx(() -> queue.onTourChanged("rs", id -> Optional.of(CheckProgress.Status.PASSED)));
        assertEquals(List.of("rs/c1", "rs/c2"), dispatched);
        onFx(queue::close);
    }

    @Test
    void aBusyAgentIsRetriedUntilItIsNot() throws Exception {
        activity = SessionActivity.BUSY;
        RiskCheckQueue queue = queue();
        onFx(() -> {
            queue.busyRetry = Duration.millis(50);
            queue.enqueue("rs", "c1");
        });
        Thread.sleep(200);
        assertTrue(dispatched.isEmpty());

        activity = SessionActivity.IDLE;
        waitFor(() -> dispatched.contains("rs/c1"));
        onFx(queue::close);
    }

    @Test
    void anUnknownActivityIsSentAtOnce() throws Exception {
        activity = SessionActivity.UNKNOWN;
        RiskCheckQueue queue = queue();

        onFx(() -> queue.enqueue("rs", "c1"));

        assertEquals(List.of("rs/c1"), dispatched);
        onFx(queue::close);
    }

    @Test
    void aRequestWithNoVerdictTimesOutAndTheNextIsSent() throws Exception {
        RiskCheckQueue queue = queue();
        onFx(() -> {
            queue.timeout = Duration.millis(100);
            queue.enqueue("rs", "c1");
            queue.enqueue("rs", "c2");
        });

        waitFor(() -> timedOut.contains("rs/c1") && dispatched.contains("rs/c2"));
        onFx(queue::close);
    }

    @Test
    void theSameCheckEnqueuedTwiceIsSentOnce() throws Exception {
        RiskCheckQueue queue = queue();

        onFx(() -> {
            queue.enqueue("rs", "c1");
            queue.enqueue("rs", "c1");
        });
        onFx(() -> queue.onTourChanged("rs", id -> Optional.of(CheckProgress.Status.PASSED)));

        assertEquals(List.of("rs/c1"), dispatched);
        onFx(queue::close);
    }

    @Test
    void aFailedHandOffIsReportedUnavailableImmediately() throws Exception {
        dispatchSucceeds = false;
        RiskCheckQueue queue = queue();

        onFx(() -> queue.enqueue("rs", "c1"));

        assertEquals(List.of("rs/c1"), timedOut);
        assertFalse(onFx(queue::diagInFlight));
        onFx(queue::close);
    }

    @Test
    void nothingIsSentAfterClose() throws Exception {
        activity = SessionActivity.BUSY;
        RiskCheckQueue queue = queue();
        onFx(() -> {
            queue.busyRetry = Duration.millis(50);
            queue.enqueue("rs", "c1");
        });
        onFx(queue::close);

        activity = SessionActivity.IDLE;
        Thread.sleep(300);

        assertTrue(dispatched.isEmpty());
    }
}
