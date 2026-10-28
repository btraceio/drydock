package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SphinxReviewSupportTest {

    @Test
    void slashInvocationProducesTypedCommands() {
        SphinxReviewSupport support = new SphinxReviewSupport(SphinxReviewSupport.Invocation.SLASH_COMMAND);
        assertEquals("/sphinx review pr 9127", support.reviewPullRequestPrompt(9127));
        assertEquals("/sphinx review", support.reviewLocalChangesPrompt());
    }

    @Test
    void skillInvocationProducesProseRoutingToTheSameArgs() {
        SphinxReviewSupport support = new SphinxReviewSupport(SphinxReviewSupport.Invocation.SKILL_REQUEST);
        assertEquals("Use the sphinx skill to review pull request #42 (sphinx review pr 42).",
                support.reviewPullRequestPrompt(42));
        assertEquals("Use the sphinx skill to review this checkout's local changes (sphinx review).",
                support.reviewLocalChangesPrompt());
    }

    @Test
    void invocationIsRequired() {
        assertThrows(NullPointerException.class, () -> new SphinxReviewSupport(null));
    }
}
