package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recheck is a small bounded task -- it reads one base delta and the stale
 * hunks, not the change -- which is why it earns a dispatch of its own rather
 * than a full re-review (spec §9.7).
 */
class ReviewInstructionsRecheckTest {

    @Test
    void theSubagentFormNamesBothBasesAndTheTool() {
        String instruction = ReviewInstructions.forRecheck("scope-1", "a1b2c3", "d4e5f6", true);

        assertTrue(instruction.contains("a1b2c3"));
        assertTrue(instruction.contains("d4e5f6"));
        assertTrue(instruction.contains("review_recheck"));
        assertTrue(instruction.contains("subagent"));
    }

    /**
     * The bases are ORDERED, not merely mentioned: "between {@code toBase} and
     * {@code fromBase}" asks the agent to read the delta backwards, and a
     * containment check on each base separately cannot tell the two apart --
     * both are present either way.
     */
    @Test
    void bothFormsReadTheDeltaFromTheOldBaseToTheNew() {
        for (boolean subagents : new boolean[] {true, false}) {
            assertTrue(ReviewInstructions.forRecheck("s", "a1b2c3", "d4e5f6", subagents)
                    .contains("between a1b2c3 and d4e5f6"));
        }
    }

    @Test
    void theInlineFormDoesTheSameWorkWithoutASubagent() {
        String instruction = ReviewInstructions.forRecheck("scope-1", "a1b2c3", "d4e5f6", false);

        assertTrue(instruction.contains("review_recheck"));
        assertFalse(instruction.contains("subagent"));
    }

    /** The agent must be told it cannot clear an approval, not left to infer it. */
    @Test
    void bothFormsSayThatUnaffectedIsAdviceOnly() {
        for (boolean subagents : new boolean[] {true, false}) {
            assertTrue(ReviewInstructions.forRecheck("s", "a", "b", subagents)
                    .contains("does not clear"));
        }
    }

    @Test
    void bothFormsNameTheScopeHandle() {
        for (boolean subagents : new boolean[] {true, false}) {
            assertTrue(ReviewInstructions.forRecheck("rs_abc123", "a", "b", subagents)
                    .contains("rs_abc123"));
        }
    }

    /**
     * Delivered through TerminalBridge.sendPrompt, which types the string into
     * a prompt: a newline would submit half an instruction.
     */
    @Test
    void bothFormsAreASingleLine() {
        for (boolean subagents : new boolean[] {true, false}) {
            assertFalse(ReviewInstructions.forRecheck("s", "a", "b", subagents).contains("\n"));
        }
    }
}
