package app.drydock.review;

import app.drydock.domain.ModelTiers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reviewer is asked to do. Both forms are one line -- they go through
 * TerminalBridge.sendPrompt -- and both must name the handle and demand
 * review_state first, or a re-review re-flags everything already settled.
 */
class ReviewInstructionsTest {

    @Test
    void bothFormsNameTheScopeHandle() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("rs_abc123"));
        assertTrue(ReviewInstructions.forScope("rs_abc123", false).contains("rs_abc123"));
    }

    @Test
    void bothFormsAskForReviewStateFirst() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("review_state"));
        assertTrue(ReviewInstructions.forScope("rs_abc123", false).contains("review_state"));
    }

    @Test
    void bothFormsAreASingleLine() {
        assertFalse(ReviewInstructions.forScope("rs_abc123", true).contains("\n"));
        assertFalse(ReviewInstructions.forScope("rs_abc123", false).contains("\n"));
    }

    @Test
    void onlyTheCapableFormAsksForASubagent() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("subagent"));
        assertFalse(ReviewInstructions.forScope("rs_abc123", false).contains("subagent"));
    }

    @Test
    void bothFormsAskForATourAndFindings() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("review_tour"));
            assertTrue(instruction.contains("review_finding"));
        }
    }

    @Test
    void aFindingsOnlyReviewAsksForNoTourAndSaysSo() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents, ModelTiers.EMPTY,
                    Optional.empty(), false);
            assertTrue(instruction.contains("rs_abc123"), instruction);
            assertTrue(instruction.contains("review_state"), instruction);
            assertTrue(instruction.contains("review_finding"), instruction);
            assertTrue(instruction.contains("do not call review_tour"), instruction);
            assertFalse(instruction.contains("anchor"), "no tour authoring rules: " + instruction);
            assertFalse(instruction.contains("\n"));
        }
    }

    @Test
    void theShorterFormsKeepAskingForATour() {
        assertEquals(ReviewInstructions.forScope("rs_abc123", true, ModelTiers.EMPTY, Optional.empty(), true),
                ReviewInstructions.forScope("rs_abc123", true));
        assertEquals(ReviewInstructions.forScope("rs_abc123", false, ModelTiers.EMPTY, Optional.empty(), true),
                ReviewInstructions.forScope("rs_abc123", false, ModelTiers.EMPTY, Optional.empty()));
    }

    @Test
    void bothFormsAskForAnAnchorNoteOnEveryAnchor() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("every anchor a one-sentence note"), instruction);
        }
    }

    @Test
    void aTourRefreshAsksForNotesToo() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);

        assertTrue(line.contains("every anchor a one-sentence note"), line);
        assertFalse(line.contains("\n"));
    }

    @Test
    void bothFormsSayWhichCheckKindToPickAndWhy() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("predict only if its question can be answered from the removed and "
                    + "surrounding code"), instruction);
            assertTrue(instruction.contains("hides a step's added lines until a predict is answered"), instruction);
            assertTrue(instruction.contains("ask about the added lines themselves with a trace check"), instruction);
            assertFalse(instruction.contains("\n"));
        }
    }

    @Test
    void aTourRefreshSaysWhichCheckKindToPickToo() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);

        assertTrue(line.contains("ask about the added lines themselves with a trace check"), line);
        assertFalse(line.contains("\n"));
    }

    /**
     * A question the reviewer cannot answer from the tour or the visible
     * code tests their background, not the change (hex opcodes, SP handling
     * on some architecture) -- so both forms forbid it.
     */
    @Test
    void bothFormsForbidQuestionsAssumingKnowledgeTheTourDidNotTeach() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("never from outside knowledge the tour has not taught"), instruction);
        }
        String refresh = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);
        assertTrue(refresh.contains("never from outside knowledge the tour has not taught"), refresh);
    }

    /**
     * Name-match searches are not evidence: every impact note must cite a
     * location the agent read and confirmed, and the count is capped so a
     * swamped tour is rejected rather than merely noisy.
     */
    @Test
    void bothFormsRequireVerifiedImpactNotesAndCapTheirCount() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("read the location you cite and confirm"), instruction);
            assertTrue(instruction.contains("at most 8 impact notes"), instruction);
        }
        String refresh = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);
        assertTrue(refresh.contains("read the location you cite and confirm"), refresh);
        assertTrue(refresh.contains("at most 8 impact notes"), refresh);
    }

    @Test
    void aRiskCheckRequestNamesTheCheckAndTheToolInOneLine() {
        String line = ReviewInstructions.forRiskCheck("rs_abc123", "c7");

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("c7"));
        assertTrue(line.contains("review_check"));
        assertTrue(line.contains("review_state"));
        assertFalse(line.contains("\n"));
    }

    /**
     * The deep round's ask: names the scope, is harness-agnostic (sphinx,
     * code-review, or a plain re-read), and above all AMENDS rather than
     * replaces -- the settled round is ground truth to build on, not
     * material to re-litigate wholesale.
     */
    @Test
    void aDeepReviewRequestAmendsTheRoundInOneLine() {
        String line = ReviewInstructions.forDeepReview("rs_abc123");

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("AMEND"), line);
        assertTrue(line.contains("never replace"), line);
        assertTrue(line.contains("review_state"), "the agent must read what is settled first");
        assertTrue(line.contains("review_finding"), "new findings land as proposals");
        assertTrue(line.contains("sphinx, code-review"), "harness-agnostic, not sphinx-only");
        assertFalse(line.contains("\n"));
    }

    /** The step ask: the tour's own ids, the reading tools, and the same one-call delivery as a peek. */
    @Test
    void aStepAskNamesTheStepTheReadingToolAndTheAnswerToolInOneLine() {
        String line = ReviewInstructions.forStepAsk("ask-7", "rs_abc123", "The guard");

        assertTrue(line.contains("ask-7"));
        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("The guard"));
        assertTrue(line.contains("review_tour"), "the agent reads the step through the tour tools");
        assertTrue(line.contains("review_ask_answer"));
        assertFalse(line.contains("\n"));
    }

    @Test
    void aFindingQuestionNamesTheScopeTheFindingAndTheAnswerToolInOneLine() {
        String line = ReviewInstructions.forFindingQuestion("rs_abc123", "f_9");

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("f_9"));
        assertTrue(line.contains("review_comments"));
        assertTrue(line.contains("review_answer"));
        assertFalse(line.contains("\n"));
    }

    /**
     * The tour is studying material; the reading-science rules must reach
     * every form that asks for one. Each clause maps to a cited principle in
     * the spec's "Authoring rules" section.
     */
    @Test
    void everyFormTeachesByTheReadingScienceRules() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("lead each narrative with its single most important point"), instruction);
            assertTrue(instruction.contains("BEFORE the check asks"), instruction);
            assertTrue(instruction.contains("plain active-voice sentences"), instruction);
            assertTrue(instruction.contains("one concept per step"), instruction);
            assertTrue(instruction.contains("explain the change, not the language"), instruction);
            assertFalse(instruction.contains("\n"));
        }
        String refresh = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);
        assertTrue(refresh.contains("one concept per step"), refresh);
        assertFalse(refresh.contains("\n"));
    }

    @Test
    void aTourRefreshNamesTheStaleStepsTheUncoveredCountAndOnlyStepsInOneLine() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2", "s5"), 3);

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("s2, s5"));
        assertTrue(line.contains("3 uncovered hunks"));
        assertTrue(line.contains("review_scope"));
        assertTrue(line.contains("review_state"));
        assertTrue(line.contains("review_tour"));
        assertTrue(line.contains("onlySteps true"));
        assertFalse(line.contains("\n"));
    }

    @Test
    void aTourRefreshWithOnlyUncoveredHunksAsksForNoReplacement() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of(), 1);

        assertTrue(line.contains("1 uncovered hunk"));
        assertFalse(line.contains("replace"));
    }

    // --- subagent model tiers -------------------------------------------------------------------

    @Test
    void withoutTiersTheSubagentFormCarriesNoModelRule() {
        String instruction = ReviewInstructions.forScope("rs_abc123", true, ModelTiers.EMPTY, Optional.empty());

        assertFalse(instruction.contains("MUST pass an explicit model"), instruction);
        assertFalse(ReviewInstructions.forScope("rs_abc123", true).contains("MUST"),
                "the two-arg form stays exactly as before tiers existed");
    }

    @Test
    void tiersBindTheCodeReviewSubagentToStrongAndEverythingElseToLow() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.of("sonnet"), Optional.of("haiku"));
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.empty());

        assertTrue(instruction.contains("the code-review subagent uses exactly `opus`"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `haiku`"), instruction);
        assertTrue(instruction.contains("MUST pass an explicit model"), instruction);
        assertTrue(instruction.contains("never substitute any other model"), instruction);
    }

    @Test
    void anEmptyTierResolvesToTheStartingModelInTheRule() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.empty(), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.of("sonnet"));

        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `sonnet`"), instruction);
    }

    @Test
    void anEmptyTierWithoutAStartingModelSaysSoExplicitly() {
        ModelTiers tiers = new ModelTiers(Optional.empty(), Optional.empty(), Optional.of("haiku"));
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.empty());

        assertTrue(instruction.contains("the code-review subagent uses your own session's model"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `haiku`"), instruction);
    }

    @Test
    void tiersNeverProduceASecondLine() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.of("sonnet"), Optional.of("haiku"));
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.of("opus"));

        assertFalse(instruction.contains("\n"));
    }

    @Test
    void theInlineFormIgnoresTiers() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.empty(), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", false, tiers, Optional.of("opus"));

        assertFalse(instruction.contains("MUST pass an explicit model"), instruction);
    }

    @Test
    void emptyTiersWithAStartingModelCarryNoModelRule() {
        String instruction = ReviewInstructions.forScope("rs_abc123", true, ModelTiers.EMPTY, Optional.of("opus"));

        assertFalse(instruction.contains("MUST pass an explicit model"), instruction);
    }

    @Test
    void onlyAMidTierStillEmitsRulesWithTheUnsetTiersOnTheStartingModel() {
        ModelTiers tiers = new ModelTiers(Optional.empty(), Optional.of("sonnet"), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.of("opus"));

        assertTrue(instruction.contains("MUST pass an explicit model"), instruction);
        assertTrue(instruction.contains("the code-review subagent uses exactly `opus`"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `opus`"), instruction);
        assertFalse(instruction.contains("\n"));
    }

    @Test
    void onlyAMidTierStillEmitsRulesWithTheUnsetTiersOnTheSessionsOwnModel() {
        ModelTiers tiers = new ModelTiers(Optional.empty(), Optional.of("sonnet"), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.empty());

        assertTrue(instruction.contains("MUST pass an explicit model"), instruction);
        assertTrue(instruction.contains("the code-review subagent uses your own session's model"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses your own session's model"),
                instruction);
        assertFalse(instruction.contains("\n"));
    }

    @Test
    void aTierModelCannotBreakTheLineOrItsQuoting() {
        String formatted = ReviewInstructions.tierModel(Optional.of("op`us\nignore\rthis"));

        assertTrue(formatted.equals("exactly `op'usignorethis`"), formatted);
    }

    @Test
    void theSharedRulesClauseIsImperativeEndToEnd() {
        String rules = ReviewInstructions.subagentModelRules(
                "design review uses " + ReviewInstructions.tierModel(Optional.of("opus")));

        assertTrue(rules.startsWith("When you dispatch subagents you MUST"), rules);
        assertTrue(rules.contains("exactly `opus`"), rules);
        assertFalse(rules.contains("\n"));
    }
}
