package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The close question's hosted fact comes from the window controller's
 * resolved facts, falling back to the legacy file's facts only while no
 * resolved answer exists; it never needs a per-session file lookup to
 * answer "hosted".
 */
class AgentCloseHostedFactsTest {

    private static final ManagedSessionId ID = ManagedSessionId.newId();
    private static final SessionPlace OTHER = new SessionPlace("dd-jmc", Optional.of("fix/x"));
    private static final SessionPlace LEGACY = new SessionPlace("legacy", Optional.empty());

    private static Function<ManagedSessionId, Optional<SessionPaneFacts>> answering(
            SessionPaneFacts.State state, Optional<SessionPlace> hostedFrom) {
        return id -> Optional.of(new SessionPaneFacts(state, "", Optional.empty(), false,
                hostedFrom, false, false));
    }

    private static String rowFor(Optional<SessionPlace> hosted, boolean needsYou) {
        return CloseWindowPlan.row(hosted.map(SessionPlace::repository).orElse("here"),
                hosted.isPresent(), needsYou);
    }

    @Test
    void aHostedAgentIsRowedHostedFromTheResolvedFacts() {
        Optional<SessionPlace> place = HostedTab.placeIn(
                answering(SessionPaneFacts.State.KNOWN, Optional.of(OTHER)), ID, () -> Optional.of(LEGACY));
        assertEquals(Optional.of(OTHER), place);
        assertEquals("dd-jmc · hosted", rowFor(place, false));
        assertEquals("dd-jmc · hosted · needs you", rowFor(place, true));
    }

    @Test
    void aResolvedNotHostedAgentIsRowedThisWindowDespiteALegacyFact() {
        Optional<SessionPlace> place = HostedTab.placeIn(
                answering(SessionPaneFacts.State.KNOWN, Optional.empty()), ID, () -> Optional.of(LEGACY));
        assertEquals(Optional.empty(), place);
        assertEquals("here · this window", rowFor(place, false));
    }

    @Test
    void anUnresolvedAgentFallsBackToTheLegacyFileFactOnlyWhileItExists() {
        assertEquals(Optional.of(LEGACY), HostedTab.placeIn(
                answering(SessionPaneFacts.State.LOADING, Optional.empty()), ID, () -> Optional.of(LEGACY)));
        assertEquals(Optional.empty(), HostedTab.placeIn(id -> Optional.empty(), ID, Optional::empty));
    }
}
