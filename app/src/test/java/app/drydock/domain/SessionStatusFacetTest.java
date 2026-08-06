package app.drydock.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SessionStatusFacetTest {

    @Test
    void runningAndStartingAreRunning() {
        assertEquals(SessionStatusFacet.RUNNING, SessionStatusFacet.of(SessionStatus.RUNNING));
        assertEquals(SessionStatusFacet.RUNNING, SessionStatusFacet.of(SessionStatus.STARTING));
    }

    @Test
    void inactiveAndExitedAreIdle() {
        assertEquals(SessionStatusFacet.IDLE, SessionStatusFacet.of(SessionStatus.INACTIVE));
        assertEquals(SessionStatusFacet.IDLE, SessionStatusFacet.of(SessionStatus.EXITED));
    }

    @Test
    void failedAndMissingDirectoryAreError() {
        assertEquals(SessionStatusFacet.ERROR, SessionStatusFacet.of(SessionStatus.FAILED));
        assertEquals(SessionStatusFacet.ERROR,
                SessionStatusFacet.of(SessionStatus.MISSING_WORKING_DIRECTORY));
    }

    /**
     * Pinned deliberately: a session whose agent this build cannot run is
     * broken, and the sidebar's `error` chip has to find it. Changing this
     * back silently re-breaks that chip.
     */
    @Test
    void unsupportedAgentIsError() {
        assertEquals(SessionStatusFacet.ERROR, SessionStatusFacet.of(SessionStatus.UNSUPPORTED_AGENT));
    }

    @Test
    void everyStatusMapsToAFacet() {
        for (SessionStatus status : SessionStatus.values()) {
            assertNotNull(SessionStatusFacet.of(status), "no facet for " + status);
        }
    }
}
