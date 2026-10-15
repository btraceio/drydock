package app.drydock.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnotationStatusTest {

    @Test
    void addressedRoundTripsThroughPersistence() {
        assertEquals(AnnotationStatus.ADDRESSED,
                AnnotationStatus.fromPersisted(AnnotationStatus.ADDRESSED.name()));
    }

    @Test
    void unknownStatusStillDecodesLenientToOpen() {
        // An older build reading a newer state file must see an ADDRESSED
        // thread as OPEN -- reappearing as open is safe; silently reading
        // as resolved is not.
        assertEquals(AnnotationStatus.OPEN, AnnotationStatus.fromPersisted("SOMETHING_NEWER"));
    }

    @Test
    void legacyFixedStillDecodes() {
        assertEquals(AnnotationStatus.FIXED, AnnotationStatus.fromPersisted("fixed"));
    }
}
