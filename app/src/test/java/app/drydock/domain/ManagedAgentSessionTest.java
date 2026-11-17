package app.drydock.domain;

import app.drydock.testing.TestPaths;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.PrLink;
import app.drydock.domain.AgentBinding;
import app.drydock.agent.api.AgentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedAgentSessionTest {

    @TempDir
    Path tempDir;

    private ManagedAgentSession sessionAt(Path workingDirectory) {
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(workingDirectory, Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    @Test
    void acceptsAbsoluteNormalizedWorkingDirectory() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession session = sessionAt(dir);
        assertEquals(dir, session.workingDirectory());
    }

    @Test
    void rejectsRelativeWorkingDirectory() {
        assertThrows(IllegalArgumentException.class, () -> sessionAt(Path.of("relative/path")));
    }

    @Test
    void rejectsNonNormalizedWorkingDirectory() {
        Path notNormalized = tempDir.toAbsolutePath().resolve("child/../child");
        assertThrows(IllegalArgumentException.class, () -> sessionAt(notNormalized));
    }

    @Test
    void rejectsNonNormalizedWorktreeRoot() {
        Path dir = tempDir.toAbsolutePath().normalize();
        Path notNormalized = tempDir.toAbsolutePath().resolve("child/../child");
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(dir, Optional.of(notNormalized), true),
                SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty()));
    }

    @Test
    void rejectsBlankDisplayName() {
        Path dir = tempDir.toAbsolutePath().normalize();
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "   ",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(dir, Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty()));
    }

    @Test
    void withStatusPreservesOtherFieldsAndKeepsIdentifiersDistinct() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir)
                .withAgentSessionId(Optional.of("claude-session-id-123"))
                .withAgentSessionName(Optional.of("my-named-session"));

        ManagedAgentSession updated = original.withStatus(SessionStatus.RUNNING);

        assertEquals(SessionStatus.RUNNING, updated.status());
        assertEquals(original.id(), updated.id());
        assertEquals(original.repositoryId(), updated.repositoryId());
        // The application session id, the Claude Code session id, and the
        // Claude Code session name must all remain independently addressable
        // and none of them collapses into another (plan section 10.2).
        assertTrue(updated.id().value().toString().length() > 0);
        assertEquals("claude-session-id-123", updated.agentSessionId().orElseThrow());
        assertEquals("my-named-session", updated.agentSessionName().orElseThrow());
    }

    @Test
    void withLastExitCodeAndWorkingDirectoryPreserveOtherFields() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir);

        ManagedAgentSession reassigned = original.withWorkingDirectory(dir).withLastExitCode(Optional.of(1));

        assertEquals(1, reassigned.lastExitCode().orElseThrow());
        assertEquals(original.displayName(), reassigned.displayName());
    }

    @Test
    void withPrSetsStateAndNumberTogetherAndPreservesOtherFields() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir);

        ManagedAgentSession withPr = original.withPr(PrState.OPEN, Optional.of(128));

        assertEquals(PrState.OPEN, withPr.prState());
        assertEquals(128, withPr.prNumber().orElseThrow());
        assertEquals(original.id(), withPr.id());
        assertEquals(original.displayName(), withPr.displayName());
    }

    @Test
    void otherWithersPreservePrStateAndNumber() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir).withPr(PrState.MERGED, Optional.of(7));

        ManagedAgentSession renamed = original.withDisplayName("renamed").withStatus(SessionStatus.RUNNING);

        assertEquals(PrState.MERGED, renamed.prState());
        assertEquals(7, renamed.prNumber().orElseThrow());
    }

    @Test
    void withWorktreeRootPreservesOtherFieldsAndValidatesPath() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir);

        ManagedAgentSession tagged = original.withWorktreeRoot(Optional.of(dir));
        assertEquals(dir, tagged.worktreeRoot().orElseThrow());
        assertEquals(original.id(), tagged.id());

        Path notNormalized = tempDir.toAbsolutePath().resolve("child/../child");
        assertThrows(IllegalArgumentException.class, () -> original.withWorktreeRoot(Optional.of(notNormalized)));
    }

    @Test
    void withDisplayNameLeavesNamePinnedUnchanged() {
        // Renaming is not pinning: only an explicit withNamePinned(true) call
        // (a human's confirmed rename) may set the pin.
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession pinned = sessionAt(dir).withNamePinned(true);

        assertTrue(pinned.withDisplayName("new name").namePinned());
        assertFalse(sessionAt(dir).withDisplayName("new name").namePinned());
    }

    @Test
    void withNamePinnedPreservesOtherFields() {
        Path dir = tempDir.toAbsolutePath().normalize();
        ManagedAgentSession original = sessionAt(dir);

        ManagedAgentSession pinned = original.withNamePinned(true);

        assertTrue(pinned.namePinned());
        assertEquals(original.displayName(), pinned.displayName());
        assertEquals(original.id(), pinned.id());
    }

    @Test
    void agentKindDefaultsAreCarriedThroughWithers() {
        ManagedAgentSession session = new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "Session 1",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(TestPaths.abs("/tmp"), Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
        assertEquals(AgentKind.CODEX, session.withAgentKind(AgentKind.CODEX).agentKind());
        assertEquals(Optional.of("x"), session.withAgentSessionId(Optional.of("x")).agentSessionId());
    }

    @Test
    void firstObservedPrTakesThePrimarySlot() {
        ManagedAgentSession session = sessionAt(tempDir);

        ManagedAgentSession observed = session.withObservedPr(PrState.OPEN, 42);

        assertEquals(Optional.of(42), observed.prNumber());
        assertEquals(PrState.OPEN, observed.prState());
        assertTrue(observed.additionalPrs().isEmpty());
        assertEquals(List.of(PrLink.of(PrState.OPEN, Optional.of(42))), observed.prLinks());
    }

    @Test
    void observedPrimaryNumberUpdatesThePrimaryLink() {
        ManagedAgentSession session = sessionAt(tempDir).withPr(PrState.OPEN, Optional.of(42));

        ManagedAgentSession observed = session.withObservedPr(PrState.MERGED, 42);

        assertEquals(PrState.MERGED, observed.prState());
        assertEquals(Optional.of(42), observed.prNumber());
        assertTrue(observed.additionalPrs().isEmpty());
    }

    @Test
    void observedNewNumberWithAKnownPrimaryAppendsASecondaryLink() {
        // The first PR stays primary: a session checked out from PR #7 that
        // later opens PR #9 from the same worktree must not lose #7.
        ManagedAgentSession session = sessionAt(tempDir).withPr(PrState.OPEN, Optional.of(7));

        ManagedAgentSession observed = session.withObservedPr(PrState.OPEN, 9);

        assertEquals(Optional.of(7), observed.prNumber());
        assertEquals(List.of(PrLink.of(PrState.OPEN, Optional.of(9))), observed.additionalPrs());
        assertEquals(List.of(
                PrLink.of(PrState.OPEN, Optional.of(7)),
                PrLink.of(PrState.OPEN, Optional.of(9))), observed.prLinks());
    }

    @Test
    void novelObservationAppendsWithoutRewritingNonMatchingSecondaryLinks() {
        ManagedAgentSession session = sessionAt(tempDir)
                .withPr(PrState.OPEN, Optional.of(7))
                .withObservedPr(PrState.MERGED, 9)
                .withObservedPr(PrState.OPEN, 11);

        ManagedAgentSession observed = session.withObservedPr(PrState.OPEN, 13);

        assertEquals(Optional.of(7), observed.prNumber());
        assertEquals(List.of(
                PrLink.of(PrState.MERGED, Optional.of(9)),
                PrLink.of(PrState.OPEN, Optional.of(11)),
                PrLink.of(PrState.OPEN, Optional.of(13))), observed.additionalPrs());
    }

    @Test
    void observedSecondaryNumberUpdatesThatLink() {
        ManagedAgentSession session = sessionAt(tempDir)
                .withPr(PrState.OPEN, Optional.of(7))
                .withObservedPr(PrState.OPEN, 9);

        ManagedAgentSession observed = session.withObservedPr(PrState.MERGED, 9);

        assertEquals(PrState.OPEN, observed.prState());
        assertEquals(List.of(PrLink.of(PrState.MERGED, Optional.of(9))), observed.additionalPrs());
    }

    @Test
    void unchangedObservationReturnsTheSameInstance() {
        ManagedAgentSession session = sessionAt(tempDir)
                .withPr(PrState.OPEN, Optional.of(7))
                .withObservedPr(PrState.OPEN, 9);

        assertSame(session, session.withObservedPr(PrState.OPEN, 9));
        assertSame(session, session.withObservedPr(PrState.OPEN, 7));
    }

    @Test
    void secondaryLinksEvictTheOldestMergedLinkAtCapacity() {
        List<PrLink> links = new ArrayList<>();
        links.add(PrLink.of(PrState.MERGED, Optional.of(9)));
        links.add(PrLink.of(PrState.OPEN, Optional.of(10)));
        links.add(PrLink.of(PrState.MERGED, Optional.of(11)));
        for (int number = 12; links.size() < ManagedAgentSession.MAX_ADDITIONAL_PR_LINKS; number++) {
            links.add(PrLink.of(PrState.OPEN, Optional.of(number)));
        }
        ManagedAgentSession session = sessionAt(tempDir).withPr(PrState.OPEN, Optional.of(7))
                .toBuilder().additionalPrs(links).build();

        ManagedAgentSession primaryUpdated = session.withObservedPr(PrState.MERGED, 7);
        assertEquals(PrState.MERGED, primaryUpdated.prState());
        assertEquals(links, primaryUpdated.additionalPrs());
        ManagedAgentSession secondaryUpdated = session.withObservedPr(PrState.MERGED, 10);
        // In-place update keeps the link at its original index; only a novel
        // observation at capacity evicts (asserted below).
        assertEquals(PrState.MERGED, secondaryUpdated.additionalPrs().get(1).state());
        assertEquals(Optional.of(10), secondaryUpdated.additionalPrs().get(1).number());
        assertSame(session, session.withObservedPr(PrState.OPEN, 10));

        ManagedAgentSession observed = session.withObservedPr(PrState.OPEN, 200);
        List<PrLink> expected = new ArrayList<>(links);
        expected.removeFirst();
        expected.add(PrLink.of(PrState.OPEN, Optional.of(200)));
        assertEquals(ManagedAgentSession.MAX_ADDITIONAL_PR_LINKS, observed.additionalPrs().size());
        assertEquals(expected, observed.additionalPrs());
    }

    @Test
    void fullListWithoutMergedSecondariesDoesNotRecordANovelObservation() {
        List<PrLink> links = new ArrayList<>();
        for (int number = 9; links.size() < ManagedAgentSession.MAX_ADDITIONAL_PR_LINKS; number++) {
            links.add(PrLink.of(PrState.OPEN, Optional.of(number)));
        }
        ManagedAgentSession session = sessionAt(tempDir).withPr(PrState.OPEN, Optional.of(7))
                .toBuilder().additionalPrs(links).build();

        assertSame(session, session.withObservedPr(PrState.OPEN, 200));
        assertEquals(links, session.additionalPrs());
    }

    @Test
    void constructorRejectsMoreThanTheSecondaryLinkLimit() {
        List<PrLink> overLimit = new ArrayList<>();
        for (int number = 9; overLimit.size() <= ManagedAgentSession.MAX_ADDITIONAL_PR_LINKS; number++) {
            overLimit.add(PrLink.of(PrState.OPEN, Optional.of(number)));
        }

        assertThrows(IllegalArgumentException.class, () -> sessionAt(tempDir).withPr(PrState.OPEN, Optional.of(7))
                .toBuilder().additionalPrs(overLimit).build());
    }

    @Test
    void secondaryLinkWithoutAPrimaryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(tempDir, Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                PrLink.none(),
                List.of(PrLink.of(PrState.OPEN, Optional.of(42))),
                false, Optional.empty(), false, Optional.empty(), false));
    }

    @Test
    void secondaryLinkWithoutANumberIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(tempDir, Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                PrLink.of(PrState.OPEN, Optional.empty()),
                List.of(PrLink.of(PrState.OPEN, Optional.empty())),
                false, Optional.empty(), false, Optional.empty(), false));
    }

    @Test
    void secondaryLinkDuplicatingThePrimaryNumberIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(tempDir, Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                PrLink.of(PrState.OPEN, Optional.of(42)),
                List.of(PrLink.of(PrState.OPEN, Optional.of(42))),
                false, Optional.empty(), false, Optional.empty(), false));
    }

    @Test
    void duplicateSecondaryLinksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(tempDir, Optional.empty(), true),
                SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(),
                PrLink.of(PrState.OPEN, Optional.of(42)),
                List.of(
                        PrLink.of(PrState.OPEN, Optional.of(43)),
                        PrLink.of(PrState.OPEN, Optional.of(43))),
                false, Optional.empty(), false, Optional.empty(), false));
    }
}
