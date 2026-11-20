package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tab hosted from another worktree, in the handoff's words (frame F2). */
class HostedTabTest {

    private static final SessionPlace DD_JMC = new SessionPlace("dd-jmc", Optional.of("fix/chunk_memory"));

    @Test
    void aSessionFromThisWindowsWorktreeIsNotHosted() {
        assertFalse(HostedTab.isHosted(Optional.of(Path.of("/w/a")), Path.of("/w/a")));
    }

    @Test
    void aSessionFromAnotherWorktreeIsHosted() {
        assertTrue(HostedTab.isHosted(Optional.of(Path.of("/w/a")), Path.of("/w/b")));
    }

    @Test
    void aWindowWithoutABaseHostsNothing() {
        assertFalse(HostedTab.isHosted(Optional.empty(), Path.of("/w/b")));
    }

    @Test
    void theTitleCarriesTheRepoChip() {
        assertEquals("dd-jmc · Fix chunk memory growth", HostedTab.title("dd-jmc", "Fix chunk memory growth"));
    }

    @Test
    void theTooltipSaysHosted() {
        assertEquals("dd-jmc · fix/chunk_memory · hosted", HostedTab.tooltip(DD_JMC));
    }

    @Test
    void provenanceNamesRepoAndBranch() {
        assertEquals("from dd-jmc ⑂ fix/chunk_memory", DD_JMC.provenance());
        assertEquals("from dd-jmc", new SessionPlace("dd-jmc", Optional.empty()).provenance());
    }

    private static final ManagedSessionId ID = ManagedSessionId.newId();
    private static final SessionPlace LEGACY = new SessionPlace("legacy", Optional.empty());

    private static Function<ManagedSessionId, Optional<SessionPaneFacts>> answering(
            SessionPaneFacts.State state, Optional<SessionPlace> hostedFrom) {
        boolean known = state == SessionPaneFacts.State.KNOWN;
        return id -> Optional.of(new SessionPaneFacts(state, "", Optional.empty(), false,
                known ? hostedFrom : Optional.empty(), false, false));
    }

    @Test
    void anIdFactPlaceWinsOverTheLegacyFallback() {
        assertEquals(Optional.of(DD_JMC), HostedTab.placeIn(
                answering(SessionPaneFacts.State.KNOWN, Optional.of(DD_JMC)), ID, () -> Optional.of(LEGACY)));
    }

    @Test
    void aResolvedIdFactThatIsNotHostedIsNotHostedWhateverTheLegacyFactSays() {
        assertEquals(Optional.empty(), HostedTab.placeIn(
                answering(SessionPaneFacts.State.KNOWN, Optional.empty()), ID, () -> Optional.of(LEGACY)));
    }

    @Test
    void withoutAResolvedIdFactTheLegacyFallbackAppliesWhileItExists() {
        assertEquals(Optional.of(LEGACY), HostedTab.placeIn(id -> Optional.empty(), ID, () -> Optional.of(LEGACY)));
        assertEquals(Optional.of(LEGACY), HostedTab.placeIn(
                answering(SessionPaneFacts.State.LOADING, Optional.empty()), ID, () -> Optional.of(LEGACY)));
    }

    @Test
    void withNeitherSourceThereIsNoPlace() {
        assertEquals(Optional.empty(), HostedTab.placeIn(id -> Optional.empty(), ID, Optional::empty));
    }
}
