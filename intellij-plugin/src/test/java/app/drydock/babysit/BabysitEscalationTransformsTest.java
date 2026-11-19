package app.drydock.babysit;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The identity-gate lookup, the escalation append, and the instructions file the start points at. */
class BabysitEscalationTransformsTest {

    private static final Instant AT = Instant.parse("2026-11-18T10:15:30Z");

    private final RepositoryId repo = RepositoryId.newId();
    private final ManagedSessionId babysitter = ManagedSessionId.newId();
    private final ManagedSessionId watched = ManagedSessionId.newId();

    private BabysitPairing pairing(BabysitState state) {
        return new BabysitPairing(babysitter, watched, repo, 42, state);
    }

    @Test
    void onlyTheBabysitterOfANonStoppedPairingHasAnActivePairing() {
        BabysitPairing other = new BabysitPairing(ManagedSessionId.newId(), ManagedSessionId.newId(), repo, 7,
                BabysitState.WATCHING);
        ApplicationState state = ApplicationState.empty().withPairings(List.of(other, pairing(BabysitState.ACTING)));

        assertEquals(Optional.of(pairing(BabysitState.ACTING)),
                BabysitStateTransforms.activePairingOf(state, babysitter));
        assertEquals(Optional.empty(), BabysitStateTransforms.activePairingOf(state, watched),
                "the watched session is not a babysitter");
        assertEquals(Optional.empty(), BabysitStateTransforms.activePairingOf(
                state.withPairings(List.of(pairing(BabysitState.STOPPED))), babysitter));
    }

    @Test
    void anEscalationIsAppendedAndItsEngagementMarkedEscalated() {
        BabysitPairing other = new BabysitPairing(ManagedSessionId.newId(), ManagedSessionId.newId(), repo, 7,
                BabysitState.WATCHING);
        Escalation earlier = new Escalation(UUID.randomUUID(), watched, babysitter, 42, "first", AT, true);
        ApplicationState state = ApplicationState.empty()
                .withPairings(List.of(pairing(BabysitState.WATCHING), other))
                .withEscalations(List.of(earlier));
        Escalation escalation = new Escalation(UUID.randomUUID(), watched, babysitter, 42, "second", AT, false);

        ApplicationState after = BabysitStateTransforms.withEscalationAppended(state, escalation);

        assertEquals(List.of(earlier, escalation), after.escalations());
        assertEquals(List.of(pairing(BabysitState.ESCALATED), other), after.pairings());
    }

    @Test
    void anEscalationThatNamesNoActiveEngagementIsRefused() {
        ApplicationState stopped = ApplicationState.empty().withPairings(List.of(pairing(BabysitState.STOPPED)));
        ApplicationState active = ApplicationState.empty().withPairings(List.of(pairing(BabysitState.WATCHING)));

        assertThrows(IllegalArgumentException.class, () -> BabysitStateTransforms.withEscalationAppended(stopped,
                new Escalation(UUID.randomUUID(), watched, babysitter, 42, "m", AT, false)));
        assertThrows(IllegalArgumentException.class, () -> BabysitStateTransforms.withEscalationAppended(active,
                new Escalation(UUID.randomUUID(), ManagedSessionId.newId(), babysitter, 42, "m", AT, false)),
                "the watched session must be the pairing's");
        assertThrows(IllegalArgumentException.class, () -> BabysitStateTransforms.withEscalationAppended(active,
                new Escalation(UUID.randomUUID(), watched, babysitter, 43, "m", AT, false)),
                "the PR must be the pairing's");
    }

    @Test
    void theInstructionsFileSitsBesideTheStateFileAndIsWrittenOnlyWhenAbsent(@TempDir Path dir) throws Exception {
        Path file = BabysitInstructions.fileFor(dir, watched, 42);
        assertEquals(BabysitStateFile.defaultFile(dir).getParent().resolve(watched + "-pr42-instructions.md"), file);

        assertTrue(BabysitInstructions.writeIfAbsent(file, BabysitInstructions.baseline(42, "feat/x")));
        String baseline = Files.readString(file);
        assertTrue(baseline.contains("ci_rerun") && baseline.contains("babysit_escalate")
                && baseline.contains("HEAD:feat/x"), baseline);

        Files.writeString(file, "monitor-written");
        assertFalse(BabysitInstructions.writeIfAbsent(file, "baseline again"));
        assertEquals("monitor-written", Files.readString(file));
        try (var listing = Files.list(file.getParent())) {
            assertEquals(List.of(file), listing.toList(), "no temp file is left behind");
        }
    }
}
