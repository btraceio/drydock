package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pane's honest states and the pure mapping over values already gathered. */
class SessionPaneFactsTest {

    private static final Path HERE = Path.of("/w/a");
    private static final Path ELSEWHERE = Path.of("/w/b");
    private static final SessionPlace LIVE = new SessionPlace("dd-jmc", Optional.of("main"));

    /** Local, stopped, resumable, healthy; tests override one thing at a time. */
    private static SessionPaneFacts.Inputs inputs(Path workingDirectory, boolean running, OptionalLong tokens,
            Optional<SessionPlace> runningPlace, Optional<String> catalogName, boolean dirExists, boolean gitEntry) {
        return new SessionPaneFacts.Inputs(Optional.of(HERE), workingDirectory, true, running, tokens, true,
                runningPlace, catalogName, "b", dirExists, gitEntry);
    }

    private static SessionPaneFacts.Inputs local() {
        return inputs(HERE, false, OptionalLong.empty(), Optional.empty(), Optional.empty(), true, true);
    }

    @Test
    void everyStateIsDistinctAndOnlyKnownCarriesFacts() {
        Set<SessionPaneFacts.State> seen = EnumSet.noneOf(SessionPaneFacts.State.class);
        for (SessionPaneFacts facts : new SessionPaneFacts[] {SessionPaneFacts.loading(),
                SessionPaneFacts.failed("boom"), SessionPaneFacts.deleted(), SessionPaneFacts.unknown(),
                SessionPaneFacts.known(local())}) {
            seen.add(facts.state());
            assertEquals(facts.state() == SessionPaneFacts.State.KNOWN, facts.isKnown());
        }
        assertEquals(EnumSet.allOf(SessionPaneFacts.State.class), seen);
        assertNotEquals(SessionPaneFacts.deleted().message(), SessionPaneFacts.unknown().message());
        assertTrue(SessionPaneFacts.failed("boom").message().contains("boom"));
    }

    @Test
    void factsOfASessionThatDidNotResolveAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPaneFacts(SessionPaneFacts.State.DELETED, "",
                Optional.of(5), false, Optional.empty(), false, false));
        assertThrows(IllegalArgumentException.class, () -> new SessionPaneFacts(SessionPaneFacts.State.FAILED, "",
                Optional.empty(), true, Optional.empty(), false, false));
    }

    @Test
    void anUnfinishedLoadIsTheOnlyLoading() {
        assertEquals(SessionPaneFacts.State.LOADING,
                SessionPaneFacts.resolveState(false, Optional.of("x"), true, true));
        for (boolean found : new boolean[] {true, false}) {
            for (boolean before : new boolean[] {true, false}) {
                for (Optional<String> failure : List.of(Optional.<String>empty(), Optional.of("x"))) {
                    assertNotEquals(SessionPaneFacts.State.LOADING,
                            SessionPaneFacts.resolveState(true, failure, found, before));
                }
            }
        }
    }

    @Test
    void statePrecedenceFailureThenFoundThenDeletedThenUnknown() {
        assertEquals(SessionPaneFacts.State.FAILED,
                SessionPaneFacts.resolveState(true, Optional.of("io"), true, true));
        assertEquals(SessionPaneFacts.State.KNOWN,
                SessionPaneFacts.resolveState(true, Optional.empty(), true, false));
        assertEquals(SessionPaneFacts.State.DELETED,
                SessionPaneFacts.resolveState(true, Optional.empty(), false, true));
        assertEquals(SessionPaneFacts.State.UNKNOWN,
                SessionPaneFacts.resolveState(true, Optional.empty(), false, false));
        assertNotEquals(SessionPaneFacts.loading().message(), SessionPaneFacts.unknown().message());
    }

    @Test
    void tokensClampToIntAndRequireAStoppedConversation() {
        assertEquals(Optional.of(Integer.MAX_VALUE), SessionPaneFacts.known(inputs(HERE, false,
                OptionalLong.of(Long.MAX_VALUE), Optional.empty(), Optional.empty(), true, true)).resumeTokens());
        assertEquals(Optional.of(1200), SessionPaneFacts.known(inputs(HERE, false,
                OptionalLong.of(1200), Optional.empty(), Optional.empty(), true, true)).resumeTokens());
        assertEquals(Optional.empty(), SessionPaneFacts.known(inputs(HERE, true,
                OptionalLong.of(1200), Optional.empty(), Optional.empty(), true, true)).resumeTokens());
        assertEquals(Optional.empty(), SessionPaneFacts.known(local()).resumeTokens());
        assertEquals(Optional.empty(), SessionPaneFacts.known(new SessionPaneFacts.Inputs(Optional.of(HERE), HERE,
                false, false, OptionalLong.of(9), true, Optional.empty(), Optional.empty(), "a", true, true))
                .resumeTokens());
    }

    @Test
    void hostedUsesTheLivePlaceThenTheCatalogNameThenTheDirectory() {
        assertEquals(Optional.of(LIVE), SessionPaneFacts.known(inputs(ELSEWHERE, true, OptionalLong.empty(),
                Optional.of(LIVE), Optional.of("cat"), true, true)).hostedFrom());
        assertEquals(Optional.of(new SessionPlace("cat", Optional.empty())),
                SessionPaneFacts.known(inputs(ELSEWHERE, false, OptionalLong.empty(), Optional.empty(),
                        Optional.of("cat"), true, true)).hostedFrom());
        assertEquals(Optional.of(new SessionPlace("b", Optional.empty())),
                SessionPaneFacts.known(inputs(ELSEWHERE, false, OptionalLong.empty(), Optional.empty(),
                        Optional.empty(), true, true)).hostedFrom());
        assertEquals(Optional.empty(), SessionPaneFacts.known(local()).hostedFrom());
    }

    @Test
    void aMissingFolderIsMarkedOnlyForAHostedSessionAndMissingGitIsCheckoutGone() {
        SessionPaneFacts hostedGone = SessionPaneFacts.known(inputs(ELSEWHERE, false, OptionalLong.empty(),
                Optional.empty(), Optional.empty(), false, false));
        assertTrue(hostedGone.folderMissing());
        assertTrue(hostedGone.checkoutGone());
        SessionPaneFacts localGone = SessionPaneFacts.known(inputs(HERE, false, OptionalLong.empty(),
                Optional.empty(), Optional.empty(), false, false));
        assertFalse(localGone.folderMissing());
        assertTrue(localGone.checkoutGone());
        // An .idea-only directory: exists, no git entry.
        assertTrue(SessionPaneFacts.known(inputs(HERE, false, OptionalLong.empty(), Optional.empty(),
                Optional.empty(), true, false)).checkoutGone());
        assertFalse(SessionPaneFacts.known(local()).checkoutGone());
    }
}
