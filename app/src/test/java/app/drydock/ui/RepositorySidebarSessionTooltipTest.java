package app.drydock.ui;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The session row's tooltip layout: labeled fields, wrapped long values. */
class RepositorySidebarSessionTooltipTest {

    private static final Instant RECENT = Instant.now().minusSeconds(2 * 3600);

    @Test
    void fieldsComeOutOneLabeledLineEach() {
        String text = RepositorySidebar.sessionRowTooltipText(
                "Refactor the parser", "RUNNING", "Claude", null, "working",
                RECENT, "/opt/work/demo", null);
        assertEquals(String.join("\n",
                "Refactor the parser",
                "Status: RUNNING",
                "Agent: Claude",
                "Activity: working",
                "Last opened: 2h ago",
                "Working directory: /opt/work/demo"), text);
    }

    @Test
    void unknownActivityOmitsTheLineRatherThanSayingUnknown() {
        String text = RepositorySidebar.sessionRowTooltipText(
                "Refactor the parser", "RUNNING", "Claude", null, null,
                RECENT, "/opt/work/demo", null);
        assertFalse(text.contains("Activity:"), text);
    }

    @Test
    void aLongDisplayNameWordWrapsAtTheTooltipWidth() {
        String text = RepositorySidebar.sessionRowTooltipText(
                "Investigate the flaky handoff test in the review pipeline and pin it down",
                "RUNNING", "Claude", null, null, RECENT, "/opt/work/demo", null);
        for (String line : text.split("\n")) {
            assertTrue(line.length() <= UiFormats.TOOLTIP_LINE_WIDTH, line);
        }
        assertEquals(String.join("\n",
                "Investigate the flaky handoff test in the review pipeline and pin it",
                "down",
                "Status: RUNNING",
                "Agent: Claude",
                "Last opened: 2h ago",
                "Working directory: /opt/work/demo"), text);
    }

    @Test
    void evalModeCarriesItsLineBetweenAgentAndLastOpened() {
        String text = RepositorySidebar.sessionRowTooltipText(
                "Eval run", "RUNNING", "Claude", "Eval mode: on (x-target-account: eval via the eval container)",
                "working", RECENT, "/opt/work/demo", null);
        assertEquals(String.join("\n",
                "Eval run",
                "Status: RUNNING",
                "Agent: Claude",
                "Eval mode: on (x-target-account: eval via the eval container)",
                "Activity: working",
                "Last opened: 2h ago",
                "Working directory: /opt/work/demo"), text);
    }

    @Test
    void longEvalLinesWrapAndEmbeddedEvalNewlinesRemainSeparate() {
        String longEvalText = "x".repeat(UiFormats.TOOLTIP_LINE_WIDTH + 8)
                + "\nEval detail: preserved";
        String text = RepositorySidebar.sessionRowTooltipText(
                "Eval run", "RUNNING", "Claude", longEvalText, null,
                RECENT, "/opt/work/demo", null);

        assertTrue(text.contains("Agent: Claude\n" + "x".repeat(UiFormats.TOOLTIP_LINE_WIDTH)
                + "\n" + "x".repeat(8) + "\nEval detail: preserved\nLast opened:"), text);
    }

    @Test
    void filterExemptionIsTheLastLine() {
        String text = RepositorySidebar.sessionRowTooltipText(
                "Refactor the parser", "RUNNING", "Claude", null, null,
                RECENT, "/opt/work/demo",
                "Shown because it is open — it does not match the current filter.");
        assertTrue(text.endsWith(
                "Shown because it is open — it does not match the current filter."), text);
    }

    @Test
    void aLongWorkingDirectoryWrapsUnderItsLabel() {
        String text = RepositorySidebar.sessionRowTooltipText(
                "Refactor the parser", "RUNNING", "Claude", null, null,
                RECENT, "/opt/very/long/worktrees/demo/session/one/two/three/four/five", null);
        assertEquals(String.join("\n",
                "Refactor the parser",
                "Status: RUNNING",
                "Agent: Claude",
                "Last opened: 2h ago",
                "Working directory: /opt/very/long/worktrees/demo/session/one/two/three/",
                "                   four/five"), text);
    }
}
