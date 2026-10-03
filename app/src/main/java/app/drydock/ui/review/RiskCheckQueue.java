package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.review.tour.CheckProgress;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Asks the agent to judge free-text answers one at a time (spec §8).
 *
 * <p>FX-confined. A request is sent only when the agent is not known to be
 * busy (Codex and Pi report UNKNOWN and are sent to at once); one is in
 * flight at a time; a request with no verdict after {@link #timeout} is
 * handed back as unavailable so the reviewer can retry or override.</p>
 */
final class RiskCheckQueue {

    interface Dispatcher {
        SessionActivity activity(String scopeId);

        boolean dispatch(String scopeId, String checkId);

        void timedOut(String scopeId, String checkId);
    }

    private record Request(String scopeId, String checkId) { }

    private final Dispatcher dispatcher;
    private final Deque<Request> queue = new ArrayDeque<>();
    private final Set<Request> known = new HashSet<>();
    private Request inFlight;
    private boolean closed;
    Duration busyRetry = Duration.seconds(5);
    Duration timeout = Duration.minutes(3);
    private final PauseTransition retryTimer = new PauseTransition();
    private final PauseTransition timeoutTimer = new PauseTransition();

    RiskCheckQueue(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
        retryTimer.setOnFinished(event -> pump());
        timeoutTimer.setOnFinished(event -> {
            Request expired = inFlight;
            inFlight = null;
            if (expired != null) {
                known.remove(expired);
                dispatcher.timedOut(expired.scopeId(), expired.checkId());
            }
            pump();
        });
    }

    void enqueue(String scopeId, String checkId) {
        Request request = new Request(scopeId, checkId);
        if (closed || !known.add(request)) {
            return;
        }
        queue.addLast(request);
        pump();
    }

    /** Clears the in-flight request once its check is no longer awaiting the agent. */
    void onTourChanged(String scopeId, Function<String, Optional<CheckProgress.Status>> statusOfCheck) {
        if (inFlight == null || !inFlight.scopeId().equals(scopeId)) {
            return;
        }
        Optional<CheckProgress.Status> status = statusOfCheck.apply(inFlight.checkId());
        if (status.isEmpty() || status.get() != CheckProgress.Status.AWAITING_AGENT) {
            timeoutTimer.stop();
            known.remove(inFlight);
            inFlight = null;
            pump();
        }
    }

    void close() {
        closed = true;
        retryTimer.stop();
        timeoutTimer.stop();
        queue.clear();
    }

    boolean diagInFlight() {
        return inFlight != null;
    }

    private void pump() {
        if (closed || inFlight != null || queue.isEmpty()) {
            return;
        }
        Request next = queue.peekFirst();
        if (dispatcher.activity(next.scopeId()) == SessionActivity.BUSY) {
            retryTimer.setDuration(busyRetry);
            retryTimer.playFromStart();
            return;
        }
        queue.removeFirst();
        if (!dispatcher.dispatch(next.scopeId(), next.checkId())) {
            known.remove(next);
            dispatcher.timedOut(next.scopeId(), next.checkId());
            pump();
            return;
        }
        inFlight = next;
        timeoutTimer.setDuration(timeout);
        timeoutTimer.playFromStart();
    }
}
