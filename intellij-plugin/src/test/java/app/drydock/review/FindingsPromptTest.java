package app.drydock.review;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The findings prompt: one text, one line, rendered identically by both hosts. */
class FindingsPromptTest {

    private static final Instant AT = Instant.parse("2026-10-03T10:00:00Z");

    private static ReviewAnnotation finding(String file, String startKey, String title) {
        return new ReviewAnnotation("rs_a", "f_1", file, startKey, startKey,
                Severity.BLOCKING, Confidence.HIGH, Optional.of(title), "Claude", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", AT, "body")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), true,
                Triage.CONFIRMED, Optional.empty());
    }

    @Test
    void forFixRendersTheAppPromptExactly() {
        String prompt = FindingsPrompt.forFix("Add the review card",
                List.of(finding("src/Main.java", "n10", "Drops the close listener")));
        assertEquals("Address these review findings on \"Add the review card\", "
                + "then summarize what you changed: "
                + "[1] src/Main.java n10: Drops the close listener.", prompt);
    }

    @Test
    void findingsKeepTheirOrderAndNumberFromOne() {
        String prompt = FindingsPrompt.forFix("Scope", List.of(
                finding("src/A.java", "n1", "First"),
                finding("src/B.java", "o2", "Second")));
        assertEquals("Address these review findings on \"Scope\", then summarize what you changed: "
                + "[1] src/A.java n1: First. [2] src/B.java o2: Second.", prompt);
    }

    @Test
    void aTitleRunsOfWhitespaceCollapseToOneSpace() {
        String prompt = FindingsPrompt.forFix("Scope", List.of(
                finding("src/A.java", "n1", "Counts   the rows\n\ttwice  on refresh")));
        assertEquals("Address these review findings on \"Scope\", then summarize what you changed: "
                + "[1] src/A.java n1: Counts the rows twice on refresh.", prompt);
    }

    @Test
    void renderCarriesItsHeadingUntouched() {
        String prompt = FindingsPrompt.render(
                "The reviewer sent these blocking findings back. Address them, then summarize what you changed: ",
                List.of(finding("src/A.java", "n1", "First")));
        assertEquals("The reviewer sent these blocking findings back. "
                + "Address them, then summarize what you changed: "
                + "[1] src/A.java n1: First.", prompt);
    }

    @Test
    void anEmptyListRendersTheStrippedHeadingAlone() {
        assertEquals("Address these review findings on \"Scope\", then summarize what you changed:",
                FindingsPrompt.forFix("Scope", List.of()));
        assertEquals("Only the heading",
                FindingsPrompt.render(" Only the heading ", List.of()));
    }

    @Test
    void aStackedSubPrTargetTellsTheAgentToBranchFromThePrAndOpenAPrOnIt() {
        String plain = FindingsPrompt.forDecisions("PR #7", List.of(), List.of());
        assertEquals(plain, FindingsPrompt.forDecisions("PR #7", List.of(), List.of(), FixTarget.SAME_BRANCH),
                "the same branch adds nothing to the hand-off");

        String stacked = FindingsPrompt.forDecisions("PR #7", List.of(), List.of(), new FixTarget.StackedSubPr(7));
        assertTrue(stacked.startsWith(plain + " "), stacked);
        assertTrue(stacked.contains("create a new branch from the head of pull request #7"), stacked);
        assertTrue(stacked.contains("gh pr view 7 --json headRefName"), stacked);
        assertTrue(stacked.contains("gh pr create --base"), stacked);
        assertFalse(stacked.contains("\n"), "one line, like every hand-off");
    }
}
