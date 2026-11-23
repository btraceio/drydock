package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSnapshot.Content;
import app.drydock.review.ReviewDataSource.Result;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a captured remote {@link ReviewDataSnapshot} file into the existing
 * {@link ReviewDiffRequests.RequestContext} the workbench diff editor shows
 * (INV-59). The result is built from snapshot bytes alone: its
 * {@code worktreeFile} is always empty, so {@link ReviewDiffContents#create}
 * never looks up, opens or reads a local file, however a registered local
 * file of the same name looks; both sides are read-only typed contents.
 *
 * <p>Outcomes stay distinct (INV-55): an {@link Content.Absent} side is an
 * empty text, {@link Content.Binary} is {@link NoText}, a failed or refused
 * read is {@link Unavailable}, and texts that no longer carry the diff's
 * rows are {@link Unavailable} too, never rendered with misplaced fragments.
 * A snapshot retained after invalidation is shown only with
 * {@code stale == true}, which puts an explicit marker in the request title
 * (INV-60). Pure: no I/O, no IDE state.</p>
 */
public final class RemoteWorkbenchSnapshots {

    static final String TITLE_PREFIX = "[remote snapshot] ";
    static final String STALE_TITLE_PREFIX = "[STALE remote snapshot] ";

    /** What the workbench can show for one remote file. */
    public sealed interface View {
    }

    /** A read-only snapshot-backed request context; {@code stale} marks a retained failed snapshot. */
    public record Ready(ReviewDiffRequests.RequestContext context, boolean stale) implements View {
    }

    /** A side is not text (binary): only size/digest exist, no text view. */
    public record NoText(String file, String detail) implements View {
    }

    /** The file or a side could not be read, was refused or no longer matches the diff. */
    public record Unavailable(String file, String reason) implements View {
    }

    private RemoteWorkbenchSnapshots() {
    }

    /** The view of {@code file} from the sides captured in {@code snapshot}. */
    public static View open(ReviewDataSnapshot snapshot, String file, boolean stale) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(file, "file");
        ReviewDataSnapshot.SourceFile sides = snapshot.sources().get(file);
        if (sides == null) {
            return new Unavailable(file, "source not captured in this snapshot");
        }
        return fromSides(snapshot, file, new Result.Ok<>(sides.left()), new Result.Ok<>(sides.right()), stale);
    }

    /** As {@link #open} but over per-side read results, so a failed read is not an absent side. */
    public static View fromSides(ReviewDataSnapshot snapshot, String file,
                                 Result<Content> left, Result<Content> right, boolean stale) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(file, "file");
        Optional<UnifiedDiff.FileDiff> diff = snapshot.diff().files().stream()
                .filter(f -> f.path().equals(file)).findFirst();
        if (diff.isEmpty()) {
            return new Unavailable(file, "file is not in the snapshot's diff");
        }
        Content l;
        Content r;
        if (left instanceof Result.Ok<Content>(Content lv)) {
            l = lv;
        } else {
            return new Unavailable(file, "left side: " + describe(left));
        }
        if (right instanceof Result.Ok<Content>(Content rv)) {
            r = rv;
        } else {
            return new Unavailable(file, "right side: " + describe(right));
        }
        if (l instanceof Content.Binary || r instanceof Content.Binary) {
            return new NoText(file, "binary content (no text view)");
        }
        String leftText = text(l);
        String rightText = text(r);
        if (!ReviewContents.matchesRows(leftText, diff.get(), false)
                || !ReviewContents.matchesRows(rightText, diff.get(), true)) {
            return new Unavailable(file, "captured text does not match the diff rows");
        }
        ReviewDiffRequests.Plan plan = ReviewDiffRequests.plan(diff.get(), leftText, rightText,
                ReviewFragments.fragmentsFor(diff.get(), leftText, rightText));
        String title = (stale ? STALE_TITLE_PREFIX : TITLE_PREFIX) + file;
        return new Ready(new ReviewDiffRequests.RequestContext(title, plan, Optional.empty()), stale);
    }

    private static String text(Content content) {
        return content instanceof Content.Text(byte[] bytes)
                ? ReviewContents.normalizeLineSeparators(new String(bytes, StandardCharsets.UTF_8))
                : "";
    }

    private static String describe(Result<Content> result) {
        return switch (result) {
            case Result.Unavailable<Content> u -> "read failed: " + u.reason();
            case Result.Stale<Content> s -> "stale: " + s.reason();
            case Result.Refused<Content> f -> "refused: " + f.reason();
            case Result.Ok<Content> ok -> "ok";
        };
    }
}
