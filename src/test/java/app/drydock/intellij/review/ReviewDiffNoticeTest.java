package app.drydock.intellij.review;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure half of {@link ReviewDiffNotice}: when the diff header carries a notice, and which. */
class ReviewDiffNoticeTest {

    @Test
    void aSnapshotAlwaysSaysSo() {
        assertEquals(Optional.of(ReviewDiffNotice.SNAPSHOT_TEXT),
                ReviewDiffNotice.text(ReviewDiffContents.RightSide.SNAPSHOT, false));
        assertEquals(Optional.of(ReviewDiffNotice.SNAPSHOT_TEXT),
                ReviewDiffNotice.text(ReviewDiffContents.RightSide.SNAPSHOT, true));
    }

    @Test
    void aFileBackedSideIsSilentUntilItTurnsStale() {
        assertEquals(Optional.empty(), ReviewDiffNotice.text(ReviewDiffContents.RightSide.FILE, false));
        assertEquals(Optional.of(ReviewDiffNotice.STALE_TEXT),
                ReviewDiffNotice.text(ReviewDiffContents.RightSide.FILE, true));
    }
}
