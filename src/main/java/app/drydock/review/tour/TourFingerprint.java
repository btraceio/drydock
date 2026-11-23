package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkDigest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identity of a review diff: SHA-256 over every hunk's {@link HunkDigest} in
 * diff order. Two diffs with the same hunks have the same fingerprint;
 * line-number shifts alone do not change it, because digests exclude them.
 */
public final class TourFingerprint {

    private TourFingerprint() {
    }

    public static String of(UnifiedDiff diff) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                sha.update(HunkDigest.of(file.path(), hunk).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) '\n');
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
