package app.drydock.domain;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.Escalation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationStateTest {

    private static final BabysitPairing PAIRING = new BabysitPairing(ManagedSessionId.newId(),
            ManagedSessionId.newId(), RepositoryId.newId(), 42, BabysitState.WATCHING);
    private static final Escalation ESCALATION = new Escalation(UUID.randomUUID(), PAIRING.watchedId(),
            PAIRING.babysitterId(), 42, "m", Instant.parse("2026-11-18T10:15:30Z"), false);

    private static ApplicationState babysat() {
        return ApplicationState.empty().withPairings(List.of(PAIRING)).withEscalations(List.of(ESCALATION));
    }

    @Test
    void everyOtherWithMethodKeepsPairingsAndEscalations() {
        Repository repo = new Repository(RepositoryId.newId(), Path.of("/tmp/repo"), "repo",
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        List<ApplicationState> derived = List.of(
                babysat().withRepositories(List.of(repo)),
                babysat().withSessions(List.of()),
                babysat().withWorkflows(List.of()),
                babysat().withUi(WorkspaceUiState.empty()),
                babysat().withHandoffBriefs(List.of()));

        for (ApplicationState state : derived) {
            assertEquals(List.of(PAIRING), state.pairings());
            assertEquals(List.of(ESCALATION), state.escalations());
        }
    }

    @Test
    void withPairingsAndWithEscalationsReplaceOnlyTheirOwnList() {
        ApplicationState state = babysat().withPairings(List.of());

        assertEquals(List.of(), state.pairings());
        assertEquals(List.of(ESCALATION), state.escalations());
        assertEquals(List.of(PAIRING), babysat().withEscalations(List.of()).pairings());
    }

    @Test
    void theListsAreDefensivelyCopied() {
        List<BabysitPairing> pairings = new ArrayList<>(List.of(PAIRING));
        ApplicationState state = ApplicationState.empty().withPairings(pairings);
        pairings.clear();

        assertEquals(List.of(PAIRING), state.pairings());
        assertThrows(UnsupportedOperationException.class, () -> state.pairings().add(PAIRING));
    }

    @Test
    void theLegacyConstructorStartsWithNoPairingsOrEscalations() {
        ApplicationState state = new ApplicationState(List.of(), List.of(), List.of(), WorkspaceUiState.empty(),
                List.of());

        assertEquals(ApplicationState.empty(), state);
    }
}
