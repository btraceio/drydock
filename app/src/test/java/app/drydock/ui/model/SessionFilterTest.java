package app.drydock.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionFilterTest {

    private static final RepositoryId REPO = RepositoryId.newId();

    private static ManagedAgentSession session(AgentKind kind, SessionStatus status) {
        return new ManagedAgentSession(ManagedSessionId.newId(), REPO, kind, "s",
                Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(),
                status, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), false, false);
    }

    @Test
    void anEmptyFilterIsInactiveAndMatchesEverything() {
        SessionFilter filter = SessionFilter.none();
        assertFalse(filter.isActive());
        assertTrue(filter.matches(session(AgentKind.CLAUDE, SessionStatus.RUNNING)));
        assertTrue(filter.matches(session(AgentKind.PI, SessionStatus.EXITED)));
    }

    @Test
    void facetsOrWithinTheStatusAxis() {
        SessionFilter filter = new SessionFilter(
                EnumSet.of(SessionStatusFacet.RUNNING, SessionStatusFacet.ERROR), Set.of());
        assertTrue(filter.isActive());
        assertTrue(filter.matches(session(AgentKind.CLAUDE, SessionStatus.RUNNING)));
        assertTrue(filter.matches(session(AgentKind.CLAUDE, SessionStatus.FAILED)));
        assertFalse(filter.matches(session(AgentKind.CLAUDE, SessionStatus.EXITED)));
    }

    @Test
    void axesAnd() {
        SessionFilter filter = new SessionFilter(
                EnumSet.of(SessionStatusFacet.RUNNING), Set.of(AgentKind.CODEX));
        assertTrue(filter.matches(session(AgentKind.CODEX, SessionStatus.RUNNING)));
        assertFalse(filter.matches(session(AgentKind.CLAUDE, SessionStatus.RUNNING)));
        assertFalse(filter.matches(session(AgentKind.CODEX, SessionStatus.EXITED)));
    }

    /**
     * Selecting every chip on an axis is the natural way to say "any of
     * these", so it must not be the one selection that hides the session
     * matching no chip at all (see unsupportedAgent... below).
     */
    @Test
    void selectingEveryChipOnAnAxisIsNoConstraint() {
        SessionFilter allAgents = new SessionFilter(Set.of(), Set.copyOf(AgentKind.preferenceOrder()));
        assertTrue(allAgents.matches(session(AgentKind.CLAUDE, SessionStatus.UNSUPPORTED_AGENT)));

        SessionFilter allStatuses = new SessionFilter(EnumSet.allOf(SessionStatusFacet.class), Set.of());
        assertTrue(allStatuses.matches(session(AgentKind.PI, SessionStatus.EXITED)));
    }

    /**
     * An UNSUPPORTED_AGENT session's agentKind() is a placeholder written by
     * the state decoder, so matching it against Claude asserts the one thing
     * known to be false. It stays reachable through `error`.
     */
    @Test
    void unsupportedAgentMatchesNoAgentChipButIsFoundByError() {
        ManagedAgentSession broken = session(AgentKind.CLAUDE, SessionStatus.UNSUPPORTED_AGENT);
        assertFalse(new SessionFilter(Set.of(), Set.of(AgentKind.CLAUDE)).matches(broken));
        assertTrue(new SessionFilter(EnumSet.of(SessionStatusFacet.ERROR), Set.of()).matches(broken));
    }

    @Test
    void bothSetsAreCopiedDefensively() {
        Set<AgentKind> mutable = new HashSet<>(Set.of(AgentKind.CODEX));
        SessionFilter filter = new SessionFilter(Set.of(), mutable);
        mutable.add(AgentKind.PI);
        assertEquals(Set.of(AgentKind.CODEX), filter.agents());
    }
}
