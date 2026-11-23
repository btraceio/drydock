package app.drydock.review;

import app.drydock.git.DiffScope;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * One captured, immutable view of a scope's review data. It carries the
 * identity of the checkout it came from, so a consumer never needs (and
 * never gets) a filesystem path derived from {@link ReviewScope#diffRoot()}.
 *
 * @param checkoutId opaque identity of the verified checkout (a remote
 *                   box/common-git identity or a local root key), never a path to open
 * @param generation data/connection generation the capture belongs to
 * @param base the review base as chosen (ref plus how it was decided), kept so a
 *             consumer can say "default, could not measure" instead of presenting a guess as fact
 * @param baseCommit the commit {@code base} resolved to at capture time
 * @param leftCommit the commit the left side was read from (HEAD or merge base)
 * @param fingerprint working-state fingerprint; changes when the checkout does
 * @param sources captured file sides by relative path; may be partial
 */
public record ReviewDataSnapshot(
        String scopeId,
        ReviewDataSource.Backend backend,
        String checkoutId,
        long generation,
        DiffScope diffScope,
        ReviewBase base,
        String baseCommit,
        String headCommit,
        String leftCommit,
        String fingerprint,
        UnifiedDiff diff,
        Map<String, SourceFile> sources,
        Instant capturedAt) {

    public ReviewDataSnapshot {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(checkoutId, "checkoutId");
        Objects.requireNonNull(diffScope, "diffScope");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(headCommit, "headCommit");
        Objects.requireNonNull(leftCommit, "leftCommit");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(capturedAt, "capturedAt");
        sources = Map.copyOf(sources);
    }

    /** Both sides of one changed file. */
    public record SourceFile(Content left, Content right) {
        public SourceFile {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /**
     * One side's content. {@link Absent} (the file truly does not exist on
     * that side, e.g. an addition's left) is a distinct type from a failed
     * read, which is an {@link ReviewDataSource.Result.Unavailable}, and from
     * {@link Binary}. Bytes are original; only parser/render adapters
     * normalize to LF.
     */
    public sealed interface Content {
        record Absent() implements Content { }

        record Text(byte[] bytes) implements Content {
            public Text {
                bytes = bytes.clone();
            }

            @Override
            public byte[] bytes() {
                return bytes.clone();
            }
        }

        /** Not text: only its size and digest are kept. */
        record Binary(long size, String sha256) implements Content { }

        /** Classify originally-read bytes: a NUL in the first 8000 bytes means binary, like git. */
        static Content of(byte[] bytes) {
            int probe = Math.min(bytes.length, 8000);
            for (int i = 0; i < probe; i++) {
                if (bytes[i] == 0) {
                    try {
                        return new Binary(bytes.length, HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256").digest(bytes)));
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return new Text(bytes);
        }
    }
}
