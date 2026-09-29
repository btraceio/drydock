package app.drydock.review;

import app.drydock.git.UnifiedDiff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The content identity of one hunk: what an approval is valid for
 * (spec §9.2).
 *
 * <p>Covers the file path, the hunk's changed lines <em>and</em> its context
 * lines. Context is included because a hunk means what it means in place --
 * change the line above it and its changed lines are byte-identical, so a
 * changed-lines-only digest would leave an approval standing over code whose
 * surroundings moved. It stops at the context window rather than the whole
 * file: a file-wide digest would unsettle every hunk whenever a file is
 * touched again, re-reviewing code nobody changed.</p>
 *
 * <p>Line NUMBERS are deliberately excluded. A hunk that only moved is the
 * same code and stays approved; that is the whole reason this is not the
 * positional line key findings use.</p>
 */
public final class HunkDigest {

    private HunkDigest() {
    }

    /** The digest {@code hunk} in {@code path} is approved under. */
    public static String of(String path, UnifiedDiff.Hunk hunk) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(hunk, "hunk");
        StringBuilder material = new StringBuilder(path).append('\n');
        for (UnifiedDiff.Line line : hunk.lines()) {
            // The kind is part of the material: an added line and a deleted
            // line carrying the same text are not the same thing to approve.
            material.append(line.kind().name()).append(' ').append(line.text()).append('\n');
        }
        return hex(material.toString());
    }

    private static String hex(String material) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; its absence is not a
            // condition this application can meaningfully continue past.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
