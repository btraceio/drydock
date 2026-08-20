package app.drydock.review;

import org.junit.jupiter.api.Test;

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
    void bothFormsAskForIntentsAndFindings() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("review_intents"));
            assertTrue(instruction.contains("review_finding"));
        }
    }
}
