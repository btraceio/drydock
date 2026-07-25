package app.drydock.mcp;

/**
 * Inverse of {@code UnifiedDiff.Line.lineKey()}: turns a
 * {@link app.drydock.review.ReviewAnnotation}'s stable line key back into a
 * plain line number for the MCP {@code review_comments} tool.
 *
 * <p>The forward direction is {@code "n" + newLine} for a line present in the
 * post-image and {@code "o" + oldLine} for a deleted line. Keys are stored,
 * not recomputed, so annotations survive a re-diff -- which is why this
 * decoder must tolerate every key any build ever wrote, including the
 * {@code "o0"} that {@code lineKey()}'s {@code orElse(0)} fallback emits.</p>
 */
public final class AnnotationLines {

    private AnnotationLines() {
    }

    /**
     * One decoded key: a line number, and whether it names a line that was
     * deleted -- so it exists only in the pre-image, and the agent will not
     * find it by reading the working tree.
     */
    public record LineRef(int line, boolean deleted) {
    }

    /** @throws IllegalArgumentException if {@code key} is not a well-formed stable line key. */
    public static LineRef decode(String key) {
        if (key == null || key.length() < 2) {
            throw new IllegalArgumentException("Not a line key: " + key);
        }
        char kind = key.charAt(0);
        if (kind != 'n' && kind != 'o') {
            throw new IllegalArgumentException("Unknown line-key kind '" + kind + "' in: " + key);
        }
        String digits = key.substring(1);
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                throw new IllegalArgumentException("Non-numeric line in key: " + key);
            }
        }
        try {
            return new LineRef(Integer.parseInt(digits), kind == 'o');
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Line number out of range in key: " + key, e);
        }
    }
}
