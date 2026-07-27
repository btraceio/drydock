package app.drydock.mcp;

import app.drydock.mcp.AnnotationLines.LineRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnotationLinesTest {

    @Test
    void newLineKeyDecodesToAPostImageLine() {
        assertEquals(new LineRef(42, false), AnnotationLines.decode("n42"));
    }

    @Test
    void oldLineKeyDecodesToADeletedLine() {
        assertEquals(new LineRef(17, true), AnnotationLines.decode("o17"));
    }

    @Test
    void zeroIsAcceptedBecauseLineKeyEmitsItForAMissingOldLine() {
        // UnifiedDiff.Line.lineKey() falls back to "o" + oldLine.orElse(0).
        assertEquals(new LineRef(0, true), AnnotationLines.decode("o0"));
    }

    @Test
    void malformedKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode(""));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("n"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("x9"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("nabc"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("n-3"));
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode("42"));
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AnnotationLines.decode(null));
    }
}
