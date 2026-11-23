package app.drydock.intellij.tasks;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SessionBabysit}: which side a session is, which escalation stands, and the shared words. */
class SessionBabysitTest {

    private static final RepositoryId REPO = RepositoryId.newId();
    private static final Instant T = Instant.parse("2026-11-18T10:00:00Z");

    private static BabysitPairing pairing(ManagedSessionId babysitter, ManagedSessionId watched, BabysitState state) {
        return new BabysitPairing(babysitter, watched, REPO, 12, state);
    }

    private static Escalation escalation(BabysitPairing pairing, String message, boolean resolved) {
        return new Escalation(UUID.randomUUID(), pairing.watchedId(), pairing.babysitterId(), pairing.prNumber(),
                message, T, resolved);
    }

    @Test
    void anActivePairingGivesBothSidesTheirPartAndAStoppedOneOnlyTheBabysitter() {
        ManagedSessionId watched = ManagedSessionId.newId();
        ManagedSessionId babysitter = ManagedSessionId.newId();
        ManagedSessionId oldBabysitter = ManagedSessionId.newId();
        ManagedSessionId stoppedWatched = ManagedSessionId.newId();
        ManagedSessionId stoppedBabysitter = ManagedSessionId.newId();
        BabysitPairing active = pairing(babysitter, watched, BabysitState.ACTING);
        BabysitPairing old = pairing(oldBabysitter, watched, BabysitState.STOPPED);
        BabysitPairing ended = pairing(stoppedBabysitter, stoppedWatched, BabysitState.STOPPED);

        Map<ManagedSessionId, SessionBabysit> joined = SessionBabysit.join(List.of(old, active, ended), List.of());

        assertEquals(new SessionBabysit(SessionBabysit.Role.WATCHED, active, Optional.empty()), joined.get(watched),
                "a watched session carries its active engagement, never an older stopped one");
        assertEquals(SessionBabysit.Role.BABYSITTER, joined.get(babysitter).role());
        assertEquals(babysitter, joined.get(watched).counterpart());
        assertEquals(watched, joined.get(babysitter).counterpart());
        assertFalse(joined.get(oldBabysitter).active(), "a stopped babysitter keeps its history");
        assertFalse(joined.containsKey(stoppedWatched), "a watched session whose engagement ended is plain again");
        for (ManagedSessionId id : List.of(watched, babysitter, oldBabysitter, stoppedBabysitter, stoppedWatched)) {
            ApplicationState state = ApplicationState.empty().withPairings(List.of(old, active, ended));
            assertEquals(Optional.ofNullable(joined.get(id)), SessionBabysit.of(state, id), "of() agrees with join()");
        }
        assertEquals(Map.of(), SessionBabysit.join(List.of(), List.of()));
    }

    @Test
    void onlyTheLatestUnresolvedEscalationOfTheActiveEngagementStands() {
        ManagedSessionId watched = ManagedSessionId.newId();
        ManagedSessionId babysitter = ManagedSessionId.newId();
        ManagedSessionId stoppedBabysitter = ManagedSessionId.newId();
        BabysitPairing active = pairing(babysitter, watched, BabysitState.ESCALATED);
        BabysitPairing stopped = pairing(stoppedBabysitter, watched, BabysitState.STOPPED);
        Escalation first = escalation(active, "first", false);
        Escalation latest = escalation(active, "latest", false);
        Escalation resolved = escalation(active, "resolved", true);
        Escalation history = escalation(stopped, "from a stopped engagement", false);

        Map<ManagedSessionId, SessionBabysit> joined = SessionBabysit.join(List.of(stopped, active),
                List.of(first, latest, resolved, history));

        assertEquals(Optional.of(latest), joined.get(watched).escalation());
        assertEquals(Optional.of(latest), joined.get(babysitter).escalation());
        assertEquals(Optional.empty(), joined.get(stoppedBabysitter).escalation(), "a stopped engagement wears none");
        assertTrue(joined.get(watched).needsHuman());

        BabysitPairing watching = pairing(babysitter, watched, BabysitState.WATCHING);
        assertFalse(SessionBabysit.join(List.of(watching), List.of(resolved)).get(watched).needsHuman(),
                "nothing unresolved and not ESCALATED: the human is not needed");
        assertTrue(SessionBabysit.join(List.of(active), List.of()).get(watched).needsHuman(),
                "ESCALATED alone still needs the human (the record may have been trimmed)");
    }

    @Test
    void aWatchedPartNeverCarriesAStoppedPairing() {
        assertThrows(IllegalArgumentException.class, () -> new SessionBabysit(SessionBabysit.Role.WATCHED,
                pairing(ManagedSessionId.newId(), ManagedSessionId.newId(), BabysitState.STOPPED), Optional.empty()));
    }

    @Test
    void theChipAndTooltipNameTheStateThePrAndTheEscalation() {
        ManagedSessionId watched = ManagedSessionId.newId();
        ManagedSessionId babysitter = ManagedSessionId.newId();
        assertEquals(List.of("watching", "acting", "escalated", "stopped"),
                List.of(BabysitState.values()).stream().map(SessionBabysit::stateWord).toList());
        BabysitPairing escalated = pairing(babysitter, watched, BabysitState.ESCALATED);
        Escalation raised = escalation(escalated, "Rebase needs a design call\nDetails: the API moved", false);
        Map<ManagedSessionId, SessionBabysit> joined = SessionBabysit.join(List.of(escalated), List.of(raised));

        assertEquals("escalated #12", joined.get(babysitter).chipText());
        assertEquals("babysat · escalated", joined.get(watched).chipText());
        assertEquals("Babysitter of PR #12 (escalated) · escalated: Rebase needs a design call",
                joined.get(babysitter).tooltipText());
        assertEquals("PR #12 is babysat (escalated) · escalated: Rebase needs a design call",
                joined.get(watched).tooltipText());
    }

    @Test
    void aSummaryIsOneFlatCappedLine() {
        assertEquals("second line", SessionBabysit.summary("\n  \nsecond line\nthird"));
        assertEquals("tab and bell gone", SessionBabysit.summary("tab\tand\u0007 bell   gone"));
        assertEquals("abcde…", SessionBabysit.summary("abcdefgh", 5));
        assertEquals("abcde", SessionBabysit.summary("abcde   ", 5), "trailing blanks are no truncation");
        assertEquals("", SessionBabysit.summary(null));
        assertEquals(SessionBabysit.SUMMARY_LIMIT + 1, SessionBabysit.summary("x".repeat(500)).length());
        assertThrows(IllegalArgumentException.class, () -> SessionBabysit.summary("x", 0));
    }
}
