package app.drydock.handoff;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffSeedTest {

    private static HandoffFacts facts() {
        return new HandoffFacts("feat/fork-codex", Optional.of("main"),
                List.of("Add the banner"),
                List.of("MainWorkspace.java"),
                List.of("Rework the rail"));
    }

    private static HandoffBrief brief(HandoffBrief.Author author) {
        return new HandoffBrief(ManagedSessionId.newId(), "Ship the fork gesture", "Wire the banner",
                Optional.of("Fork, never switch in place"),
                Optional.of("Chose briefing over transcript translation"),
                Optional.of("Rejected an API proxy"),
                Optional.of("Human said stop rewriting the parser"),
                Instant.parse("2026-08-12T10:15:30Z"), Optional.of("abc1234"), author);
    }

    @Test
    void anAgentBriefIsLabelledAsThePreviousSessionsTestimony() {
        String seed = HandoffSeed.compose(Optional.of(brief(HandoffBrief.Author.AGENT)), facts());

        assertTrue(seed.contains("reported by the previous session"), seed);
        assertTrue(seed.contains("testimony"), seed);
        assertTrue(seed.contains("Ship the fork gesture"), seed);
    }

    @Test
    void aHumanBriefIsLabelledAsTheHumansOwnWords() {
        String seed = HandoffSeed.compose(Optional.of(brief(HandoffBrief.Author.HUMAN)), facts());

        assertTrue(seed.contains("written by the human"), seed);
        assertFalse(seed.contains("reported by the previous session"), seed);
    }

    @Test
    void aMissingBriefIsStatedPlainlyRatherThanOmitted() {
        String seed = HandoffSeed.compose(Optional.empty(), facts());

        assertTrue(seed.contains("No handoff brief was recorded"), seed);
    }

    @Test
    void theDerivedFactsAreAlwaysPresentEvenWithNoBrief() {
        String seed = HandoffSeed.compose(Optional.empty(), facts());

        assertTrue(seed.contains("feat/fork-codex"), seed);
        assertTrue(seed.contains("main"), seed);
        assertTrue(seed.contains("Add the banner"), seed);
        assertTrue(seed.contains("MainWorkspace.java"), seed);
        assertTrue(seed.contains("Rework the rail"), seed);
    }

    @Test
    void testimonyAndDerivedFactsAreKeptVisiblyApart() {
        String seed = HandoffSeed.compose(Optional.of(brief(HandoffBrief.Author.AGENT)), facts());

        int testimony = seed.indexOf("reported by the previous session");
        int derived = seed.indexOf("derived by drydock");
        assertTrue(testimony >= 0, seed);
        assertTrue(derived >= 0, seed);
        // The successor must be able to tell which half it can check.
        assertTrue(testimony < derived, "the brief comes first, the checkable facts bound it");
    }

    @Test
    void absentOptionalSlotsAreOmittedRatherThanPrintedEmpty() {
        HandoffBrief minimal = new HandoffBrief(ManagedSessionId.newId(), "Goal", "Next",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.parse("2026-08-12T10:15:30Z"), Optional.empty(), HandoffBrief.Author.AGENT);

        String seed = HandoffSeed.compose(Optional.of(minimal), facts());

        assertFalse(seed.contains("Ruled out"), seed);
        assertFalse(seed.contains("Corrections"), seed);
        assertTrue(seed.contains("**Goal:** Goal"), seed);
        assertTrue(seed.contains("**Next step:** Next"), seed);
    }

    @Test
    void emptyFactSectionsAreOmittedRatherThanPrintedEmpty() {
        HandoffFacts bare = new HandoffFacts("feat/fork", Optional.of("main"), List.of(), List.of(), List.of());

        String seed = HandoffSeed.compose(Optional.empty(), bare);

        assertFalse(seed.contains("**Commits:**"), seed);
        assertFalse(seed.contains("**Uncommitted changes:**"), seed);
        assertFalse(seed.contains("**Open review intents:**"), seed);
        assertTrue(seed.contains("feat/fork"), seed);
    }

    @Test
    void theSuccessorIsToldItInheritedTheWork() {
        String seed = HandoffSeed.compose(Optional.of(brief(HandoffBrief.Author.AGENT)), facts());

        assertTrue(seed.contains("continuing work another agent started"), seed);
    }

    @Test
    void theBranchLineNamesOneBranchBecauseThereIsOnlyOne() {
        String seed = HandoffSeed.compose(Optional.empty(), new HandoffFacts(
                "feat/work", Optional.of("a1b2c3d"), List.of("add a.txt"), List.of(), List.of()));

        assertTrue(seed.contains("**Branch:** feat/work"), seed);
        assertFalse(seed.contains("forked from"), seed);
    }

    @Test
    void theSeedCarriesTheHeadCommitSoTheSuccessorCanCheckIt() {
        String seed = HandoffSeed.compose(Optional.empty(), new HandoffFacts(
                "feat/work", Optional.of("a1b2c3d"), List.of("add a.txt"), List.of(), List.of()));

        assertTrue(seed.contains("a1b2c3d"), seed);
    }

    @Test
    void anUnbornBranchSaysSoRatherThanOmittingTheCommitSection() {
        // Silence would read as "no commits worth mentioning", not "none".
        String seed = HandoffSeed.compose(Optional.empty(), new HandoffFacts(
                "feat/work", Optional.empty(), List.of(), List.of("?? wip.txt"), List.of()));

        assertTrue(seed.contains("no commits yet"), seed);
    }
}
