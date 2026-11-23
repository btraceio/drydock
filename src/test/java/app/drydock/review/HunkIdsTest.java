package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hunk-id scheme reviewers, tour steps and reading-path links address
 * hunks by. It has to read back exactly what it wrote, or a link or a step
 * anchor points at the wrong hunk.
 */
class HunkIdsTest {

    @Test
    void anIdParsesBackToItsFileAndIndex() {
        assertEquals(Optional.of(new HunkIds.Anchor("app/src/Main.java", 3)),
                HunkIds.parseHunkId(HunkIds.hunkId("app/src/Main.java", 3)));
    }

    /** Underscores in the path are the common case in this repository's own tree. */
    @Test
    void aPathContainingUnderscoresParsesBackToItself() {
        assertEquals(Optional.of(new HunkIds.Anchor("src/my_module/deep_file.java", 11)),
                HunkIds.parseHunkId(HunkIds.hunkId("src/my_module/deep_file.java", 11)));
    }

    @Test
    void anythingNotShapedLikeAnIdParsesToNothing() {
        assertEquals(Optional.empty(), HunkIds.parseHunkId(null));
        assertEquals(Optional.empty(), HunkIds.parseHunkId("not-a-hunk-id"));
        assertEquals(Optional.empty(), HunkIds.parseHunkId("h_App.java_notanumber"));
        assertEquals(Optional.empty(), HunkIds.parseHunkId("h__0"));
        assertEquals(Optional.empty(), HunkIds.parseHunkId("h_App.java_-1"));
    }
}
