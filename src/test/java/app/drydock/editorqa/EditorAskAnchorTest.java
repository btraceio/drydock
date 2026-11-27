package app.drydock.editorqa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import app.drydock.editorqa.EditorAskAnchorValidator.DocumentView;
import app.drydock.editorqa.EditorAskAnchorValidator.TrackedUpdate;
import app.drydock.editorqa.EditorAskAnchorValidator.UpdateCoalescer;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;

class EditorAskAnchorTest {

    private static final String ROOT = "/work/repo";
    private static final String FILE = "src/A.java";
    private static final String DOC = "line one\nint x = 1;\nline three\n";
    private static final String SEL = "int x = 1;";

    private static DocumentView view(String root, String file, String text) {
        return new DocumentView() {
            public String checkoutRoot() { return root; }
            public String relativeFile() { return file; }
            public CharSequence content() { return text; }
        };
    }

    private static EditorAskAnchor anchor() {
        int s = DOC.indexOf(SEL);
        return EditorAskAnchor.capture(ROOT, FILE, DOC, s, s + SEL.length());
    }

    @Test
    void unchangedDocumentValidates() {
        assertEquals(Optional.of(anchor()),
                EditorAskAnchorValidator.validate(anchor(), view(ROOT, FILE, DOC)));
    }

    @Test
    void editAboveIntactRangeUpdatesOffsetsAndDigest() {
        EditorAskAnchor a = anchor();
        String edited = "// header\n" + DOC;
        int s = edited.indexOf(SEL);
        EditorAskAnchor moved = EditorAskAnchorValidator.retrack(a,
                new TrackedUpdate(view(ROOT, FILE, edited), s, s + SEL.length())).orElseThrow();
        assertEquals(s, moved.startOffset());
        assertEquals(s + SEL.length(), moved.endOffset());
        assertEquals(a.selectionFingerprint(), moved.selectionFingerprint());
        assertFalse(a.documentDigest().equals(moved.documentDigest()));
        // the updated anchor validates against the edited text, the old one no longer does
        assertTrue(EditorAskAnchorValidator.validate(moved, view(ROOT, FILE, edited)).isPresent());
        assertTrue(EditorAskAnchorValidator.validate(a, view(ROOT, FILE, edited)).isEmpty());
    }

    @Test
    void replacementAndDeletionYieldNoAnchor() {
        EditorAskAnchor a = anchor();
        String replaced = DOC.replace(SEL, "int x = 2;");
        int s = replaced.indexOf("int x = 2;");
        assertTrue(EditorAskAnchorValidator.retrack(a,
                new TrackedUpdate(view(ROOT, FILE, replaced), s, s + SEL.length())).isEmpty());
        assertTrue(EditorAskAnchorValidator.validate(a, view(ROOT, FILE, replaced)).isEmpty());
        String deleted = DOC.replace(SEL, "");
        assertTrue(EditorAskAnchorValidator.retrack(a,
                new TrackedUpdate(view(ROOT, FILE, deleted), s, s)).isEmpty());
        assertTrue(EditorAskAnchorValidator.validate(a, view(ROOT, FILE, deleted)).isEmpty());
    }

    @Test
    void changedDocumentWithRepeatedTextIsSuppressed() {
        EditorAskAnchor a = anchor();
        // selected text still sits at the same offsets, and appears again elsewhere,
        // but the document differs: no anchor, and no search for the other copy.
        String changed = DOC + SEL + "\n";
        assertTrue(EditorAskAnchorValidator.validate(a, view(ROOT, FILE, changed)).isEmpty());
        String shifted = "x" + DOC + SEL;
        assertTrue(EditorAskAnchorValidator.validate(a, view(ROOT, FILE, shifted)).isEmpty());
    }

    @Test
    void differentFileOrCheckoutIsRejected() {
        EditorAskAnchor a = anchor();
        assertTrue(EditorAskAnchorValidator.validate(a, view(ROOT, "src/B.java", DOC)).isEmpty());
        assertTrue(EditorAskAnchorValidator.validate(a, view("/other", FILE, DOC)).isEmpty());
    }

    @Test
    void endAtNextLineColumnZeroUsesEndMinusOne() {
        int start = DOC.indexOf(SEL);
        int endAtNextLine = start + SEL.length() + 1; // includes the newline, ends at column 0
        assertEquals(endAtNextLine - 1, EditorAskAnchorValidator.cardLineOffset(start, endAtNextLine));
        assertEquals('\n', DOC.charAt(EditorAskAnchorValidator.cardLineOffset(start, endAtNextLine)));
    }

    @Test
    void boundsAreEnforced() {
        EditorAskAnchor a = anchor();
        assertThrows(IllegalArgumentException.class, () -> EditorAskAnchor.capture(ROOT, FILE, DOC, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> EditorAskAnchor.capture(ROOT, FILE, DOC, -1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> EditorAskAnchor.capture(ROOT, FILE, DOC, 0, DOC.length() + 1));
        assertTrue(EditorAskAnchorValidator.retrack(a,
                new TrackedUpdate(view(ROOT, FILE, DOC), 0, DOC.length() + 5)).isEmpty());
        // a document that shrank below the anchored range
        assertTrue(EditorAskAnchorValidator.validate(a, view(ROOT, FILE, "tiny")).isEmpty());
    }

    @Test
    void persistedShapeRoundTripsAndMalformedIsNoAnchor() {
        EditorAskAnchor a = anchor();
        assertEquals(Optional.of(a), EditorAskAnchor.fromJson(a.toJson()));
        assertTrue(EditorAskAnchor.fromJson(JsonNull.INSTANCE).isEmpty());
        assertTrue(EditorAskAnchor.fromJson(
                ((JsonObject) a.toJson())
                        .put("start", JsonNumber.of(-4))).isEmpty());
    }

    @Test
    void coalescerKeepsLatestAndSchedulesOnce() {
        UpdateCoalescer c = new UpdateCoalescer();
        TrackedUpdate u1 = new TrackedUpdate(view(ROOT, FILE, DOC), 1, 2);
        TrackedUpdate u2 = new TrackedUpdate(view(ROOT, FILE, DOC), 3, 4);
        assertTrue(c.submit(u1));
        assertFalse(c.submit(u2));
        assertEquals(Optional.of(u2), c.drain());
        assertTrue(c.drain().isEmpty());
        assertTrue(c.submit(u1));
    }
}
