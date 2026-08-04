package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where an intent says the diff should be. Selecting an intent scrolls the
 * code column there, so this parsing is the difference between the rail
 * being a control and the rail being decoration.
 */
class ReviewIntentAnchorTest {

    /**
     * The fallback grouping addresses hunks by id exactly as a reviewer's
     * does -- one mechanism, so the column's filter and the scroll-into-view
     * cannot disagree about what an intent contains.
     */
    @Test
    void theFallbackGroupingAnchorsThroughItsHunkIds() {
        ReviewIntent intent = intentWith(List.of(ReviewIntent.hunkId("app/src/Main.java", 0)));

        assertEquals(Optional.of(new ReviewIntent.Anchor("app/src/Main.java", 0)), intent.anchor());
    }

    @Test
    void anIntentThatNamesNoHunksHasNoAnchor() {
        assertEquals(Optional.empty(), intentWith(List.of()).anchor());
    }

    @Test
    void aReviewerGroupingAnchorsToItsFirstHunk() {
        ReviewIntent intent = intentWith(List.of(
                ReviewIntent.hunkId("app/Sidebar.java", 2),
                ReviewIntent.hunkId("app/Other.java", 0)));

        assertEquals(Optional.of(new ReviewIntent.Anchor("app/Sidebar.java", 2)), intent.anchor());
    }

    /** Underscores in the path are the common case in this repository's own tree. */
    @Test
    void aPathContainingUnderscoresParsesBackToItself() {
        ReviewIntent intent = intentWith(List.of(ReviewIntent.hunkId("src/my_module/deep_file.java", 11)));

        assertEquals(Optional.of(new ReviewIntent.Anchor("src/my_module/deep_file.java", 11)),
                intent.anchor());
    }

    /** An intent may legitimately name no hunks; that is "nowhere to jump", not an error. */
    @Test
    void anIntentWithNoRecognisableHunkHasNoAnchor() {
        assertEquals(Optional.empty(), intentWith(List.of()).anchor());
        assertEquals(Optional.empty(), intentWith(List.of("not-a-hunk-id")).anchor());
        assertEquals(Optional.empty(), intentWith(List.of("h_App.java_notanumber")).anchor());
    }

    private static ReviewIntent intentWith(List<String> hunkIds) {
        return new ReviewIntent("intent-1", 1, "Rate limit the gateway", ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.MED, "", hunkIds, Optional.empty(), false);
    }
}
