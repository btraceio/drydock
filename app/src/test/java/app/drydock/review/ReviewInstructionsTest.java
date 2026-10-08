package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
