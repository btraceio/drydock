package app.drydock.intellij.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PullRequestReviewPromptTest {

    @Test
    void everyPlaceholderBecomesThePullRequestNumber() {
        assertEquals("/sphinx review pr 854",
                PullRequestReviewPrompt.render("/sphinx review pr $PR$", 854));
        assertEquals("pr 7 and pr 7 again", PullRequestReviewPrompt.render("pr $PR$ and pr $PR$ again", 7));
    }

    @Test
    void aTemplateWithoutThePlaceholderIsSentUnchanged() {
        assertEquals("review it", PullRequestReviewPrompt.render("review it", 7));
    }
}
