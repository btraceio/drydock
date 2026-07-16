package app.cpm.app;

import app.cpm.domain.ManagedSessionId;

/**
 * A {@link SessionManager} operation was asked to act on a {@link
 * ManagedSessionId} that is not present in the persisted {@code
 * ApplicationState} -- e.g. a stale id from a closed/reloaded window, or a
 * caller bug. Kept as its own specific type rather than a generic {@code
 * IllegalArgumentException} or {@code NoSuchElementException} per plan
 * section 20 ("never a generic 'something went wrong'").
 */
public final class UnknownSessionException extends RuntimeException {

    public UnknownSessionException(ManagedSessionId sessionId) {
        super("No managed session with id " + sessionId);
    }
}
