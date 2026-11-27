package app.drydock.editorqa;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure validation of an {@link EditorAskAnchor} against an immutable
 * document snapshot. File/checkout, full-document digest, bounds and selected
 * content must all agree; otherwise there is no anchor. It never searches for
 * similar text, tracks renames or rebases a range: an ambiguous or changed
 * document simply has no historical inlay, and the record is kept by the
 * caller regardless.
 */
public final class EditorAskAnchorValidator {

    /**
     * Immutable snapshot of one document, safe to hash on a background thread.
     * The platform adapter (T18) implements it from a copied document text.
     */
    public interface DocumentView {
        /** Canonical checkout root the file belongs to. */
        String checkoutRoot();

        /** Checkout-relative path of the file. */
        String relativeFile();

        /** Full, immutable document content. */
        CharSequence content();
    }

    /** A tracked-range update: where the live marker now sits in {@code view}. */
    public record TrackedUpdate(DocumentView view, int startOffset, int endOffset) {
        public TrackedUpdate {
            Objects.requireNonNull(view, "view");
        }
    }

    private EditorAskAnchorValidator() {
    }

    /**
     * The anchor if it is still exactly valid for {@code view} (reopen path):
     * same checkout/file, identical full-document digest, in-bounds range and
     * matching selected-text fingerprint.
     */
    public static Optional<EditorAskAnchor> validate(EditorAskAnchor anchor, DocumentView view) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(view, "view");
        if (!sameFile(anchor, view)) {
            return Optional.empty();
        }
        CharSequence content = view.content();
        if (!selectionMatches(anchor, content, anchor.startOffset(), anchor.endOffset())
                || !EditorAskAnchor.sha256Hex(content).equals(anchor.documentDigest())) {
            return Optional.empty();
        }
        return Optional.of(anchor);
    }

    /**
     * Moves the anchor to the live marker's new offsets when the text there is
     * still the selected text (an edit above an intact range): updates the
     * offsets and full-document digest. Empty when the selection is changed,
     * replaced, deleted or out of bounds; the caller then keeps the previous
     * anchor (or none) and never drops the record.
     */
    public static Optional<EditorAskAnchor> retrack(EditorAskAnchor anchor, TrackedUpdate update) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(update, "update");
        DocumentView view = update.view();
        if (!sameFile(anchor, view)) {
            return Optional.empty();
        }
        CharSequence content = view.content();
        if (!selectionMatches(anchor, content, update.startOffset(), update.endOffset())) {
            return Optional.empty();
        }
        return Optional.of(anchor.moved(update.startOffset(), update.endOffset(),
                EditorAskAnchor.sha256Hex(content)));
    }

    /**
     * Offset whose line hosts the card: {@code end - 1}, so a selection ending
     * at column zero of the next line stays on its last selected line.
     */
    public static int cardLineOffset(int startOffset, int endOffset) {
        return endOffset > startOffset ? endOffset - 1 : startOffset;
    }

    private static boolean sameFile(EditorAskAnchor anchor, DocumentView view) {
        return anchor.checkoutRoot().equals(view.checkoutRoot())
                && anchor.relativeFile().equals(view.relativeFile());
    }

    private static boolean selectionMatches(EditorAskAnchor anchor, CharSequence content, int start, int end) {
        if (start < 0 || end <= start || end > content.length()) {
            return false;
        }
        return EditorAskAnchor.sha256Hex(content.subSequence(start, end)).equals(anchor.selectionFingerprint());
    }

    /**
     * Latest-wins coalescing of tracked updates: many document events collapse
     * into one pending update, and only the first submission after a drain asks
     * the caller to schedule work.
     */
    public static final class UpdateCoalescer {
        private TrackedUpdate pending;

        /** Stores the update, replacing any pending one; true if a drain must be scheduled. */
        public synchronized boolean submit(TrackedUpdate update) {
            boolean schedule = pending == null;
            pending = Objects.requireNonNull(update, "update");
            return schedule;
        }

        /** The latest pending update, if any; clears it. */
        public synchronized Optional<TrackedUpdate> drain() {
            TrackedUpdate latest = pending;
            pending = null;
            return Optional.ofNullable(latest);
        }
    }
}
