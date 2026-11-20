package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static app.drydock.review.tour.TourFixtures.add;
import static app.drydock.review.tour.TourFixtures.file;
import static app.drydock.review.tour.TourFixtures.hunk;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TourFingerprintTest {

    @Test
    void theSameDiffHasTheSameFingerprint() {
        assertEquals(TourFingerprint.of(twoFileDiff()), TourFingerprint.of(twoFileDiff()));
    }

    @Test
    void anyHunkContentChangeChangesTheFingerprint() {
        UnifiedDiff other = new UnifiedDiff(List.of(file("src/A.java", hunk(add(3, "  int z;")))));
        assertNotEquals(TourFingerprint.of(twoFileDiff()), TourFingerprint.of(other));
    }

    @Test
    void aFingerprintIsSixtyFourHexCharacters() {
        assertEquals(64, TourFingerprint.of(twoFileDiff()).length());
    }
}
