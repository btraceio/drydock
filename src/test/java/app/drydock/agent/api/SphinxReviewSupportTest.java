package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SphinxReviewSupportTest {

    @Test
    void promptsAreTheSlashCommandsForEveryHarness() {
        SphinxReviewSupport support = new SphinxReviewSupport();
        assertEquals("/sphinx review pr 9127", support.reviewPullRequestPrompt(9127));
        assertEquals("/sphinx review", support.reviewLocalChangesPrompt());
    }
}
