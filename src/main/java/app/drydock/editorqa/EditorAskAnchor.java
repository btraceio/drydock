package app.drydock.editorqa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

/**
 * The best-known restorable position of an editor question, kept apart from
 * the immutable original sent range held by {@link EditorAskRecord}.
 *
 * <p>It names the canonical checkout root and the checkout-relative file,
 * the tracked half-open character range {@code [startOffset, endOffset)},
 * a SHA-256 fingerprint of the selected text and a SHA-256 digest of the
 * <em>whole</em> document at that range. Only digests are kept: no
 * surrounding or selected text is stored (INV-13). The anchor is only ever
 * trusted again through {@link EditorAskAnchorValidator}.</p>
 *
 * <p>Integration decision: {@link EditorAskRecord#anchor()} stays an opaque
 * {@link JsonValue} (T1's persisted-shape seam); this class owns the shape
 * through {@link #toJson()} / {@link #fromJson(JsonValue)}, so the record and
 * store need no change and a malformed persisted anchor decodes to "no
 * anchor" rather than failing the record.</p>
 */
public record EditorAskAnchor(
        String checkoutRoot,
        String relativeFile,
        int startOffset,
        int endOffset,
        String selectionFingerprint,
        String documentDigest) {

    private static final int SHA256_HEX_LENGTH = 64;

    public EditorAskAnchor {
        EditorAskLimits.checkRoot(Objects.requireNonNull(checkoutRoot, "checkoutRoot"));
        EditorAskLimits.checkRelativePath(Objects.requireNonNull(relativeFile, "relativeFile"));
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("anchor range must be non-empty and non-negative: "
                    + startOffset + ".." + endOffset);
        }
        checkDigest(selectionFingerprint, "selectionFingerprint");
        checkDigest(documentDigest, "documentDigest");
    }

    /** Anchor for {@code [start, end)} of {@code content}; throws if out of bounds. */
    public static EditorAskAnchor capture(
            String checkoutRoot, String relativeFile, CharSequence content, int start, int end) {
        Objects.requireNonNull(content, "content");
        if (start < 0 || end <= start || end > content.length()) {
            throw new IllegalArgumentException("range " + start + ".." + end
                    + " outside document of length " + content.length());
        }
        return new EditorAskAnchor(checkoutRoot, relativeFile, start, end,
                sha256Hex(content.subSequence(start, end)), sha256Hex(content));
    }

    /** The same selection (same fingerprint) at new tracked offsets in new content. */
    EditorAskAnchor moved(int start, int end, String newDocumentDigest) {
        return new EditorAskAnchor(checkoutRoot, relativeFile, start, end,
                selectionFingerprint, newDocumentDigest);
    }

    /** Persisted shape stored in {@link EditorAskRecord#anchor()}. */
    public JsonValue toJson() {
        return JsonObject.empty()
                .put("root", new JsonString(checkoutRoot))
                .put("file", new JsonString(relativeFile))
                .put("start", JsonNumber.of(startOffset))
                .put("end", JsonNumber.of(endOffset))
                .put("selection", new JsonString(selectionFingerprint))
                .put("document", new JsonString(documentDigest));
    }

    /** Decodes a persisted anchor; anything malformed or out of bounds is "no anchor". */
    public static Optional<EditorAskAnchor> fromJson(JsonValue json) {
        if (!(json instanceof JsonObject o)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new EditorAskAnchor(
                    string(o, "root"), string(o, "file"),
                    number(o, "start"), number(o, "end"),
                    string(o, "selection"), string(o, "document")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String string(JsonObject o, String key) {
        if (o.get(key) instanceof JsonString s) {
            return s.value();
        }
        throw new IllegalArgumentException(key);
    }

    private static int number(JsonObject o, String key) {
        if (o.get(key) instanceof JsonNumber n) {
            return n.asInt();
        }
        throw new IllegalArgumentException(key);
    }

    private static void checkDigest(String digest, String name) {
        Objects.requireNonNull(digest, name);
        if (digest.length() != SHA256_HEX_LENGTH || !digest.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new IllegalArgumentException(name + " is not a SHA-256 hex digest");
        }
    }

    static String sha256Hex(CharSequence text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
