package app.drydock.babysit;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BabysitStateTransformsTest {

    private final RepositoryId repo = RepositoryId.newId();

    private BabysitPairing pairing(ManagedSessionId babysitter, ManagedSessionId watched) {
        return new BabysitPairing(babysitter, watched, repo, 42, BabysitState.WATCHING);
    }

    @Test
    void deletingTheWatchedSessionDropsItsPairing() {
        ManagedSessionId watched = ManagedSessionId.newId();
        BabysitPairing doomed = pairing(ManagedSessionId.newId(), watched);
        BabysitPairing other = pairing(ManagedSessionId.newId(), ManagedSessionId.newId());
        ApplicationState state = ApplicationState.empty().withPairings(List.of(doomed, other));

        assertEquals(List.of(other), BabysitStateTransforms.onSessionDeleted(state, watched).pairings());
    }

    @Test
    void deletingTheBabysitterDropsItsPairing() {
        ManagedSessionId babysitter = ManagedSessionId.newId();
        BabysitPairing doomed = pairing(babysitter, ManagedSessionId.newId());
        BabysitPairing other = pairing(ManagedSessionId.newId(), ManagedSessionId.newId());
        ApplicationState state = ApplicationState.empty().withPairings(List.of(other, doomed));

        assertEquals(List.of(other), BabysitStateTransforms.onSessionDeleted(state, babysitter).pairings());
    }

    @Test
    void escalationsSurviveTheDeletion() {
        ManagedSessionId watched = ManagedSessionId.newId();
        ManagedSessionId babysitter = ManagedSessionId.newId();
        Escalation escalation = new Escalation(UUID.randomUUID(), watched, babysitter, 42, "m",
                Instant.parse("2026-11-18T10:15:30Z"), false);
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(pairing(babysitter, watched)))
                .withEscalations(List.of(escalation));

        ApplicationState after = BabysitStateTransforms.onSessionDeleted(state, babysitter);

        assertEquals(List.of(), after.pairings());
        assertEquals(List.of(escalation), after.escalations());
    }

    @Test
    void anUninvolvedSessionLeavesTheStateUntouched() {
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(pairing(ManagedSessionId.newId(), ManagedSessionId.newId())));

        assertSame(state, BabysitStateTransforms.onSessionDeleted(state, ManagedSessionId.newId()));
    }

    // ---- start refusals -------------------------------------------------------

    private ManagedAgentSession session(Optional<WorkspaceBoxBinding> box) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), repo, "watched",
                AgentBinding.unlaunched(AgentKind.CLAUDE), SessionWorkspace.inRepository(Path.of("/repo")),
                Instant.parse("2026-11-18T10:15:30Z"))
                .workspaceBox(box)
                .build();
    }

    @Test
    void aLocalUnwatchedSessionMayBeBabysat() {
        ManagedAgentSession watched = session(Optional.empty());
        ApplicationState state = ApplicationState.empty().withSessions(List.of(watched));

        assertEquals(Optional.empty(), BabysitStateTransforms.startRefusal(state, watched.id()));
    }

    @Test
    void anUnknownSessionIsRefused() {
        assertEquals(Optional.of(BabysitStartRefusal.WATCHED_UNKNOWN),
                BabysitStateTransforms.startRefusal(ApplicationState.empty(), ManagedSessionId.newId()));
    }

    @Test
    void aRemoteSessionIsRefused() {
        ManagedAgentSession watched = session(Optional.of(new WorkspaceBoxBinding("drydock-demo", "/Users/dev/clone")));
        ApplicationState state = ApplicationState.empty().withSessions(List.of(watched));

        assertEquals(Optional.of(BabysitStartRefusal.WATCHED_REMOTE),
                BabysitStateTransforms.startRefusal(state, watched.id()));
    }

    @Test
    void aBabysitterIsNeverBabysatEvenWhenItsEngagementStopped() {
        ManagedAgentSession babysitter = session(Optional.empty());
        ApplicationState state = ApplicationState.empty().withSessions(List.of(babysitter))
                .withPairings(List.of(pairing(babysitter.id(), ManagedSessionId.newId())
                        .withState(BabysitState.STOPPED)));

        assertEquals(Optional.of(BabysitStartRefusal.WATCHED_IS_BABYSITTER),
                BabysitStateTransforms.startRefusal(state, babysitter.id()));
    }

    @Test
    void anActivePairingRefusesASecondBabysitterButAStoppedOneDoesNot() {
        ManagedAgentSession watched = session(Optional.empty());
        for (BabysitState active : List.of(BabysitState.WATCHING, BabysitState.ACTING, BabysitState.ESCALATED,
                BabysitState.SUSPENDED)) {
            ApplicationState state = ApplicationState.empty().withSessions(List.of(watched))
                    .withPairings(List.of(pairing(ManagedSessionId.newId(), watched.id()).withState(active)));
            assertEquals(Optional.of(BabysitStartRefusal.ALREADY_BABYSAT),
                    BabysitStateTransforms.startRefusal(state, watched.id()), active.name());
        }
        ApplicationState stopped = ApplicationState.empty().withSessions(List.of(watched))
                .withPairings(List.of(pairing(ManagedSessionId.newId(), watched.id())
                        .withState(BabysitState.STOPPED)));
        assertEquals(Optional.empty(), BabysitStateTransforms.startRefusal(stopped, watched.id()));
    }

    @Test
    void withPairingAddedAppends() {
        BabysitPairing first = pairing(ManagedSessionId.newId(), ManagedSessionId.newId());
        BabysitPairing second = pairing(ManagedSessionId.newId(), ManagedSessionId.newId());
        ApplicationState state = ApplicationState.empty().withPairings(List.of(first));

        assertEquals(List.of(first, second), BabysitStateTransforms.withPairingAdded(state, second).pairings());
    }

    // ---- monitor transforms ------------------------------------------------------

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEXT = "89abcdef0123456789abcdef0123456789abcdef";

    @Test
    void withPairingHeadSetsTheActivePairingsHeadOnly() {
        ManagedSessionId babysitter = ManagedSessionId.newId();
        BabysitPairing mine = pairing(babysitter, ManagedSessionId.newId());
        BabysitPairing other = pairing(ManagedSessionId.newId(), ManagedSessionId.newId());
        ApplicationState state = ApplicationState.empty().withPairings(List.of(mine, other));

        ApplicationState after = BabysitStateTransforms.withPairingHead(state, babysitter, HEAD);

        assertEquals(List.of(mine.withKnownHead(HEAD), other), after.pairings());
        assertSame(after, BabysitStateTransforms.withPairingHead(after, babysitter, HEAD));
        assertEquals(Optional.of(NEXT), BabysitStateTransforms.withPairingHead(after, babysitter, NEXT)
                .pairings().get(0).knownHead());
        assertThrows(IllegalArgumentException.class,
                () -> BabysitStateTransforms.withPairingHead(state, babysitter, "HEAD"));
    }

    @Test
    void aStoppedPairingIsNeverChangedByTheMonitorTransforms() {
        ManagedSessionId babysitter = ManagedSessionId.newId();
        ApplicationState state = ApplicationState.empty().withPairings(List.of(
                pairing(babysitter, ManagedSessionId.newId()).withState(BabysitState.STOPPED)));

        assertSame(state, BabysitStateTransforms.withPairingHead(state, babysitter, HEAD));
        assertSame(state, BabysitStateTransforms.withPairingState(state, babysitter, BabysitState.WATCHING));
        assertSame(state, BabysitStateTransforms.withPairingStateIf(state, babysitter, BabysitState.STOPPED,
                BabysitState.WATCHING));
    }

    @Test
    void withPairingStateEndsTheEngagementAndKeepsThePairing() {
        ManagedSessionId babysitter = ManagedSessionId.newId();
        BabysitPairing pairing = pairing(babysitter, ManagedSessionId.newId()).withKnownHead(HEAD);
        ApplicationState state = ApplicationState.empty().withPairings(List.of(pairing));

        ApplicationState after = BabysitStateTransforms.withPairingState(state, babysitter, BabysitState.STOPPED);

        assertEquals(List.of(pairing.withState(BabysitState.STOPPED)), after.pairings());
        assertEquals(Optional.empty(), BabysitStateTransforms.activePairingOf(after, babysitter));
        assertSame(state, BabysitStateTransforms.withPairingState(state, ManagedSessionId.newId(),
                BabysitState.STOPPED));
    }

    @Test
    void withPairingStateIfIsACompareAndSet() {
        ManagedSessionId babysitter = ManagedSessionId.newId();
        ApplicationState watching = ApplicationState.empty().withPairings(List.of(
                pairing(babysitter, ManagedSessionId.newId())));

        ApplicationState acting = BabysitStateTransforms.withPairingStateIf(watching, babysitter,
                BabysitState.WATCHING, BabysitState.ACTING);
        assertEquals(BabysitState.ACTING, acting.pairings().get(0).state());

        ApplicationState escalated = BabysitStateTransforms.withPairingState(acting, babysitter,
                BabysitState.ESCALATED);
        assertSame(escalated, BabysitStateTransforms.withPairingStateIf(escalated, babysitter,
                BabysitState.ACTING, BabysitState.WATCHING));
        assertSame(escalated, BabysitStateTransforms.withPairingStateIf(escalated, babysitter,
                BabysitState.WATCHING, BabysitState.ACTING));
    }

    // ---- suspension: the watched session's life drives the engagement ------

    @Test
    void suspendingMarksTheLivePairingsOfTheWatchedSession() {
        ManagedSessionId watched = ManagedSessionId.newId();
        BabysitPairing watching = pairing(ManagedSessionId.newId(), watched);
        BabysitPairing escalated = pairing(ManagedSessionId.newId(), watched)
                .withState(BabysitState.ESCALATED);
        BabysitPairing stopped = pairing(ManagedSessionId.newId(), watched).withState(BabysitState.STOPPED);
        BabysitPairing elsewhere = pairing(ManagedSessionId.newId(), ManagedSessionId.newId());
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(watching, escalated, stopped, elsewhere));

        ApplicationState after = BabysitStateTransforms.withWatchedSuspended(state, watched);

        assertEquals(List.of(watching.withState(BabysitState.SUSPENDED),
                escalated.withState(BabysitState.SUSPENDED), stopped, elsewhere), after.pairings());
        // Already suspended: nothing to write, the same instance comes back.
        assertSame(after, BabysitStateTransforms.withWatchedSuspended(after, watched));
        assertSame(state, BabysitStateTransforms.withWatchedSuspended(state, ManagedSessionId.newId()));
    }

    @Test
    void resumingReturnsTheSuspendedPairingsToWatching() {
        ManagedSessionId watched = ManagedSessionId.newId();
        BabysitPairing suspended = pairing(ManagedSessionId.newId(), watched)
                .withState(BabysitState.SUSPENDED);
        BabysitPairing live = pairing(ManagedSessionId.newId(), watched);
        BabysitPairing elsewhere = pairing(ManagedSessionId.newId(), ManagedSessionId.newId())
                .withState(BabysitState.SUSPENDED);
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(suspended, live, elsewhere));

        ApplicationState after = BabysitStateTransforms.withWatchedResumed(state, watched);

        assertEquals(List.of(suspended.withState(BabysitState.WATCHING), live, elsewhere), after.pairings());
        assertSame(after, BabysitStateTransforms.withWatchedResumed(after, watched));
        assertSame(state, BabysitStateTransforms.withWatchedResumed(state, ManagedSessionId.newId()));
    }

    @Test
    void aSuspendedEngagementIsNotActiveForItsBabysitter() {
        ManagedSessionId babysitter = ManagedSessionId.newId();
        ApplicationState state = ApplicationState.empty().withPairings(List.of(
                pairing(babysitter, ManagedSessionId.newId()).withState(BabysitState.SUSPENDED)));

        assertEquals(Optional.empty(), BabysitStateTransforms.activePairingOf(state, babysitter));
        assertThrows(IllegalArgumentException.class,
                () -> BabysitStateTransforms.withEscalationAppended(state,
                        escalation(ManagedSessionId.newId(), babysitter, "m", false)));
    }

    @Test
    void aSuspendedPairingCanStillBeStoppedAndKeepsItsHead() {
        ManagedSessionId babysitter = ManagedSessionId.newId();
        BabysitPairing suspended = pairing(babysitter, ManagedSessionId.newId())
                .withState(BabysitState.SUSPENDED).withKnownHead(HEAD);
        ApplicationState state = ApplicationState.empty().withPairings(List.of(suspended));

        ApplicationState ended = BabysitStateTransforms.withPairingState(state, babysitter,
                BabysitState.STOPPED);
        assertEquals(List.of(suspended.withState(BabysitState.STOPPED)), ended.pairings());
        assertSame(state, BabysitStateTransforms.withPairingHead(state, babysitter, HEAD));
    }

    @Test
    void liveWatchedPairingsNamesExactlyWhatSuspensionPauses() {
        ManagedSessionId watched = ManagedSessionId.newId();
        BabysitPairing watching = pairing(ManagedSessionId.newId(), watched);
        BabysitPairing escalated = pairing(ManagedSessionId.newId(), watched)
                .withState(BabysitState.ESCALATED);
        BabysitPairing suspended = pairing(ManagedSessionId.newId(), watched)
                .withState(BabysitState.SUSPENDED);
        BabysitPairing stopped = pairing(ManagedSessionId.newId(), watched).withState(BabysitState.STOPPED);
        BabysitPairing elsewhere = pairing(ManagedSessionId.newId(), ManagedSessionId.newId());
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(watching, escalated, suspended, stopped, elsewhere));

        assertEquals(List.of(watching, escalated),
                BabysitStateTransforms.liveWatchedPairings(state, watched));
        assertEquals(List.of(), BabysitStateTransforms.liveWatchedPairings(state, ManagedSessionId.newId()));
    }

    @Test
    void suspendedWatchedPairingsNamesExactlyWhatResumeWakes() {
        ManagedSessionId watched = ManagedSessionId.newId();
        BabysitPairing suspended = pairing(ManagedSessionId.newId(), watched)
                .withState(BabysitState.SUSPENDED);
        BabysitPairing watching = pairing(ManagedSessionId.newId(), watched);
        BabysitPairing elsewhere = pairing(ManagedSessionId.newId(), ManagedSessionId.newId())
                .withState(BabysitState.SUSPENDED);
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(suspended, watching, elsewhere));

        assertEquals(List.of(suspended),
                BabysitStateTransforms.suspendedWatchedPairings(state, watched));
        assertEquals(List.of(),
                BabysitStateTransforms.suspendedWatchedPairings(state, ManagedSessionId.newId()));
    }

    // ---- bounded escalations ----------------------------------------------------

    private static Escalation escalation(ManagedSessionId watched, ManagedSessionId babysitter, String message,
                                         boolean resolved) {
        return new Escalation(UUID.randomUUID(), watched, babysitter, 42, message,
                Instant.parse("2026-11-18T10:15:30Z"), resolved);
    }

    @Test
    void theEscalationListIsBoundedDroppingTheOldestResolvedFirst() {
        ManagedSessionId watched = ManagedSessionId.newId();
        ManagedSessionId babysitter = ManagedSessionId.newId();
        List<Escalation> full = new ArrayList<>();
        for (int i = 0; i < BabysitStateTransforms.MAX_ESCALATIONS; i++) {
            full.add(escalation(watched, babysitter, "e" + i, i == 10 || i == 20));
        }
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(pairing(babysitter, watched))).withEscalations(full);

        ApplicationState once = BabysitStateTransforms.withEscalationAppended(state,
                escalation(watched, babysitter, "new1", false));
        assertEquals(BabysitStateTransforms.MAX_ESCALATIONS, once.escalations().size());
        assertEquals("e0", once.escalations().get(0).message(), "an unresolved old one stays while a resolved exists");
        assertFalse(once.escalations().stream().anyMatch(e -> e.message().equals("e10")),
                "the oldest resolved went first");
        assertEquals("new1", once.escalations().get(once.escalations().size() - 1).message());

        ApplicationState twice = BabysitStateTransforms.withEscalationAppended(once,
                escalation(watched, babysitter, "new2", false));
        assertFalse(twice.escalations().stream().anyMatch(e -> e.message().equals("e20")));
        assertEquals("e0", twice.escalations().get(0).message());

        ApplicationState thrice = BabysitStateTransforms.withEscalationAppended(twice,
                escalation(watched, babysitter, "new3", false));
        assertEquals(BabysitStateTransforms.MAX_ESCALATIONS, thrice.escalations().size());
        assertEquals("e1", thrice.escalations().get(0).message(), "none resolved: the oldest of all goes");
        assertEquals("new3", thrice.escalations().get(thrice.escalations().size() - 1).message());
    }

    @Test
    void aTerminalSessionIsRefused() {
        ManagedAgentSession terminal = new ManagedAgentSession.Builder(ManagedSessionId.newId(), repo, "shell",
                AgentBinding.unlaunched(AgentKind.TERMINAL), SessionWorkspace.inRepository(Path.of("/repo")),
                Instant.parse("2026-11-18T10:15:30Z")).build();
        ApplicationState state = ApplicationState.empty().withSessions(List.of(terminal));

        assertEquals(Optional.of(BabysitStartRefusal.WATCHED_IS_TERMINAL),
                BabysitStateTransforms.startRefusal(state, terminal.id()));
    }
}
