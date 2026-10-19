package app.drydock.intellij.actions;

import app.drydock.domain.SessionMode;
import app.drydock.intellij.actions.NewAgentSessionPlan.Placement;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The New Agent Session dialog's rules, as the handoff states them. These are
 * the assertions worth having without Swing: which placement a mode defaults
 * to, which of the two warnings applies, and what the primary button is
 * therefore called.
 */
class NewAgentSessionPlanTest {

    @Test
    void aWritingSessionDefaultsToItsOwnWorktreeAndAReadingOneToHere() {
        // A writing agent gets isolation it actually needs; isolating a
        // read-only session buys nothing and costs a checkout.
        assertEquals(Placement.NEW_WORKTREE, NewAgentSessionPlan.defaultPlacement(SessionMode.WRITES));
        assertEquals(Placement.HERE, NewAgentSessionPlan.defaultPlacement(SessionMode.READ_ONLY));
    }

    @Test
    void onlyAWritingSessionInTheCurrentTreeWarns() {
        // Its own new worktree: nothing to collide with.
        assertTrue(NewAgentSessionPlan.warning(SessionMode.WRITES, Placement.NEW_WORKTREE, false).isEmpty());
        assertTrue(NewAgentSessionPlan.warning(SessionMode.WRITES, Placement.NEW_WORKTREE, true).isEmpty());
        // Read-only here: cannot overwrite anyone.
        assertTrue(NewAgentSessionPlan.warning(SessionMode.READ_ONLY, Placement.HERE, true).isEmpty());
    }

    @Test
    void theTwoWarningsNameDifferentProblemsNotSeverities() {
        assertEquals(Optional.of("A writing agent here shares the tree with your own edits."),
                NewAgentSessionPlan.warning(SessionMode.WRITES, Placement.HERE, false));
        assertEquals(Optional.of(
                        "Another writing session is active in this worktree. Two writers can overwrite each other."),
                NewAgentSessionPlan.warning(SessionMode.WRITES, Placement.HERE, true));
    }

    @Test
    void theButtonNamesTheRiskWhenThereIsOne() {
        assertEquals("Start anyway", NewAgentSessionPlan.primaryLabel(Placement.HERE, true));
        assertEquals("Start anyway", NewAgentSessionPlan.primaryLabel(Placement.NEW_WORKTREE, true));
        assertEquals("Start Here", NewAgentSessionPlan.primaryLabel(Placement.HERE, false));
        assertEquals("Create & Open Window", NewAgentSessionPlan.primaryLabel(Placement.NEW_WORKTREE, false));
    }

    @Test
    void theBranchIsTheTasksFirstThreeWords() {
        assertEquals(Optional.of("fix-the-parser"),
                NewAgentSessionPlan.branchSuggestion("Fix the parser crash on empty input"));
        assertEquals(Optional.of("add-retry-to"),
                NewAgentSessionPlan.branchSuggestion("  Add   retry to   the uploader  "));
        // Fewer than three words is fine; it is a suggestion, not a quota.
        assertEquals(Optional.of("rename"), NewAgentSessionPlan.branchSuggestion("Rename"));
    }

    @Test
    void aTaskWithNothingSluggableSuggestsNoBranchAtAll() {
        // slug() answers "worktree" when nothing survives, which is a fine
        // directory name and a meaningless branch name — better to leave the
        // field to the human than to invent one that says nothing.
        assertTrue(NewAgentSessionPlan.branchSuggestion("").isEmpty());
        assertTrue(NewAgentSessionPlan.branchSuggestion("   ").isEmpty());
        assertTrue(NewAgentSessionPlan.branchSuggestion(null).isEmpty());
        assertTrue(NewAgentSessionPlan.branchSuggestion("!!! ??? ***").isEmpty());
    }

    @Test
    void theInitialTitleIsTheTasksFirstLineBounded() {
        assertEquals("Fix the parser crash", NewAgentSessionPlan.sessionTitle("Fix the parser crash"));
        // Only the first line: a pasted multi-paragraph task must not become
        // a tab label with a newline in it.
        assertEquals("Fix the parser", NewAgentSessionPlan.sessionTitle("Fix the parser\n\nIt crashes on empty input"));
        assertEquals("Agent session", NewAgentSessionPlan.sessionTitle("  "));
        assertEquals("Agent session", NewAgentSessionPlan.sessionTitle(null));

        String long_ = "Refactor the entire session lifecycle so that durable rows outlive every window";
        String title = NewAgentSessionPlan.sessionTitle(long_);
        assertTrue(title.length() <= 49, title);
        assertTrue(title.endsWith("…"), title);
    }
}
