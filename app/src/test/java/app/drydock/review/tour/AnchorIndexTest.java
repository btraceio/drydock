package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorIndexTest {

    private final AnchorIndex index = AnchorIndex.of(twoFileDiff());

    @Test
    void anAnchorResolvesOnlyWhenBothKeysAreRowsOfItsFileInOrder() {
        assertTrue(index.resolves(new TourAnchor("src/A.java", "n1", "n4")));
        assertFalse(index.resolves(new TourAnchor("src/A.java", "n4", "n1")), "backwards range");
        assertFalse(index.resolves(new TourAnchor("src/A.java", "n1", "n99")), "end key not a row");
        assertFalse(index.resolves(new TourAnchor("src/Nope.java", "n1", "n1")), "file not in diff");
    }

    @Test
    void containmentFollowsDiffRowOrderAcrossHunks() {
        TourAnchor whole = new TourAnchor("src/A.java", "n3", "n21");
        assertTrue(index.contains(whole, "src/A.java", "o20"), "a removed row between the endpoints");
        assertTrue(index.contains(whole, "src/A.java", "n20"));
        assertFalse(index.contains(whole, "src/A.java", "n1"));
        assertFalse(index.contains(whole, "src/B.java", "o5"), "other file");
    }

    @Test
    void aDeletionOnlyHunkIsCoverableByOldLineKeys() {
        TourAnchor deletion = new TourAnchor("src/B.java", "o5", "o6");
        assertTrue(index.resolves(deletion));
        assertEquals(List.of("src/B.java#0"), index.hunksTouched(deletion).stream()
                .map(ref -> ref.file() + "#" + ref.index()).toList());
    }

    @Test
    void changedRowsListsEveryAddAndDelInDiffOrder() {
        assertEquals(List.of("src/A.java n3", "src/A.java o20", "src/A.java n21", "src/B.java o5", "src/B.java o6"),
                index.changedRows().stream().map(row -> row.file() + " " + row.lineKey()).toList());
    }

    @Test
    void hunksTouchedNeedsAChangedRowInsideTheAnchor() {
        TourAnchor contextOnly = new TourAnchor("src/A.java", "n1", "n2");
        assertTrue(index.hunksTouched(contextOnly).isEmpty());
    }

    @Test
    void keyAtOrBeforeFindsTheNearestNewLineRow() {
        assertEquals("n21", index.keyAtOrBefore("src/A.java", 21).orElseThrow());
        assertEquals("n4", index.keyAtOrBefore("src/A.java", 10).orElseThrow());
        assertTrue(index.keyAtOrBefore("src/Nope.java", 3).isEmpty());
    }

    @Test
    void hunkIdsUseTheSharedHunkIdFormat() {
        UnifiedDiff diff = twoFileDiff();
        assertEquals("h_src/A.java_1", AnchorIndex.of(diff).hunks().get(1).hunkId());
    }
}
