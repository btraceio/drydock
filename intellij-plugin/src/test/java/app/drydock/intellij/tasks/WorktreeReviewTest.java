package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorktreeReviewTest {

    /** The label names the branch it reviews — the worktree's own identity, never a PR number. */
    @Test
    void namedAfterTheBranch() {
        assertEquals("Review feat/balloon", WorktreeReview.displayName("feat/balloon"));
    }
}
