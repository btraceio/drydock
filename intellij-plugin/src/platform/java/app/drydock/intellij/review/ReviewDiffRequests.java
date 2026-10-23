package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import com.intellij.diff.contents.DocumentContentImpl;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.diff.util.Side;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataHolder;

import java.util.List;

/**
 * The review tab's diff-request plan: a pure, headless-testable
 * {@link Plan} plus a thin {@link #toRequest} that assembles the platform
 * {@link SimpleDiffRequest} from it.
 *
 * <p>Hunk identity stays with git: the request carries
 * {@link DiffUserDataKeysEx#CUSTOM_DIFF_COMPUTER} returning the precomputed
 * fragments, so the IDE viewer renders exactly the hunks the agent saw
 * (never its own comparison — {@code kb/hyp-git-diff-stays-authoritative}).
 * Scroll positions are the key→position direction of the annotation line-key
 * contract only ({@code n<N>}/{@code o<N>}, 1-based git line numbers →
 * 0-based editor lines; {@code kb/find-line-key-contract}); the
 * position→key direction has no P2 caller and is deliberately not written.
 * {@code FORCE_READ_ONLY}, {@code CUSTOM_DIFF_COMPUTER} and
 * {@code SCROLL_TO_LINE} were live-verified on {@code SimpleDiffRequest} in
 * the P2 spike ({@code kb/find-p2-spikes-live-results}).</p>
 */
public final class ReviewDiffRequests {

    /**
     * The pinned wire-contract name of the Drydock request key. P3's
     * {@code DiffExtension} must gate on the key mints from this exact
     * string; renaming it would silently stop matching requests built
     * before the rename.
     */
    public static final String REQUEST_KEY_NAME = "drydock.review.diffRequest";

    /**
     * The Drydock request user-data key decorating only Drydock review
     * diffs (the value is {@link Boolean#TRUE}). A {@link Key} compares by
     * identity, not by its name string: {@code Key.create(REQUEST_KEY_NAME)}
     * mints a fresh, never-equal key, so readers must use this one static
     * instance — P3's {@code DiffExtension} must read exactly this field
     * (or receive it), never re-{@code Key.create} the name. No
     * {@code DiffExtension} is registered in P2.
     */
    public static final Key<Boolean> REQUEST_KEY = Key.create(REQUEST_KEY_NAME);

    /**
     * One file's diff request before it becomes a platform request.
     * {@code leftText} is the base-side text (content 1, {@link Side#LEFT}),
     * {@code rightText} the current-side text (content 2, {@link Side#RIGHT});
     * the scroll line is 0-based on {@code scrollToSide}.
     *
     * <p>Both texts must be LF-only ({@code ReviewContents.normalizeLineSeparators}):
     * {@link #toRequest} hands them to {@code EditorFactory.createDocument},
     * which throws an {@code AssertionError} on a {@code \r} (263), and the
     * fragments must have been computed over these exact texts. A CR is
     * refused here, at the plan boundary, rather than silently normalized —
     * normalizing only the documents would leave the fragment offsets
     * pointing into the longer CRLF text.</p>
     */
    public record Plan(
            CharSequence leftText,
            CharSequence rightText,
            List<LineFragment> fragments,
            Side scrollToSide,
            int scrollToLine
    ) {
        public Plan {
            requireLfOnly(leftText, "leftText");
            requireLfOnly(rightText, "rightText");
        }

        private static void requireLfOnly(CharSequence text, String name) {
            if (text == null) {
                return;
            }
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\r') {
                    throw new IllegalArgumentException(name
                            + " carries a CR: normalize line separators before computing fragments");
                }
            }
        }
    }

    /** A 0-based scroll position in the two-side viewer. */
    public record ScrollTarget(Side side, int line) {
    }

    private ReviewDiffRequests() {
    }

    /**
     * Maps a review line key to its scroll position — the key→position
     * direction of the line-key contract only. {@code n<N>} (a line that
     * exists in the new file) → {@code (RIGHT, N-1)}; {@code o<N>} (a deleted
     * line) → {@code (LEFT, N-1)}. Git line numbers are 1-based, editor
     * lines 0-based. Anything not matching {@code [no]<digits>} is refused,
     * never silently re-anchored.
     */
    public static ScrollTarget scrollTarget(String lineKey) {
        if (lineKey == null || lineKey.length() < 2
                || (lineKey.charAt(0) != 'n' && lineKey.charAt(0) != 'o')) {
            throw new IllegalArgumentException("not a review line key (n<new>/o<old>): " + lineKey);
        }
        int gitLine;
        try {
            gitLine = Integer.parseInt(lineKey.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a review line key (n<new>/o<old>): " + lineKey, e);
        }
        // Git line numbers are 1-based: parseInt accepts leading '-' and
        // '0' ("n-4" parses to -4), which are not line numbers at all.
        if (gitLine < 1) {
            throw new IllegalArgumentException("git line numbers are 1-based: " + lineKey);
        }
        return new ScrollTarget(lineKey.charAt(0) == 'n' ? Side.RIGHT : Side.LEFT, gitLine - 1);
    }

    /**
     * The plan for a file row: scrolls to the first added line on the right
     * side, {@code (RIGHT, firstAddRow.newLine()-1)}.
     *
     * <p>A file with no added rows (a pure deletion) scrolls to its first
     * deleted line on the left — the first <em>changed</em> line, on the side
     * the key contract assigns old lines to. A file with no changed rows at
     * all (no hunks to render) scrolls to the top of the right side.</p>
     */
    public static Plan plan(UnifiedDiff.FileDiff file, CharSequence leftText, CharSequence rightText,
                            List<LineFragment> fragments) {
        ScrollTarget scroll = fileScrollTarget(file);
        return new Plan(leftText, rightText, fragments, scroll.side(), scroll.line());
    }

    /**
     * The plan for a finding row (or any selection whose scroll position is
     * already known): the caller maps the finding's line key with
     * {@link #scrollTarget} and passes the result here.
     */
    public static Plan plan(CharSequence leftText, CharSequence rightText, List<LineFragment> fragments,
                            ScrollTarget scrollTarget) {
        return new Plan(leftText, rightText, fragments, scrollTarget.side(), scrollTarget.line());
    }

    private static ScrollTarget fileScrollTarget(UnifiedDiff.FileDiff file) {
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                if (line.kind() == UnifiedDiff.Line.Kind.ADD) {
                    return new ScrollTarget(Side.RIGHT, line.newLine().orElse(1) - 1);
                }
            }
        }
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                if (line.kind() == UnifiedDiff.Line.Kind.DEL) {
                    return new ScrollTarget(Side.LEFT, line.oldLine().orElse(1) - 1);
                }
            }
        }
        return new ScrollTarget(Side.RIGHT, 0);
    }

    /**
     * Puts the review request user data on {@code target}: read-only, the
     * precomputed-fragment computer, the scroll line, and the Drydock
     * request key. Pure (no IDE state), so the headless tests assert the
     * produced user data here; {@link #toRequest} adds nothing beyond it.
     */
    static void applyUserData(UserDataHolder target, Plan plan) {
        target.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, Boolean.TRUE);
        // Pure per the Threading table: compute returns the precomputed
        // fragments and reads nothing but the plan.
        target.putUserData(DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER,
                (DiffUserDataKeysEx.DiffComputer) (text1, text2, policy, innerFragments, indicator) -> plan.fragments());
        target.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
                Pair.create(plan.scrollToSide(), plan.scrollToLine()));
        target.putUserData(REQUEST_KEY, Boolean.TRUE);
    }

    /**
     * Assembles the platform request from a plan. Thin on purpose: two
     * documents, two read-only plain-text contents, and the user data from
     * {@link #applyUserData} — everything testable lives in the pure half.
     *
     * <p>Content 1 is the left (base) side and content 2 the right (current)
     * side, so {@link Side#LEFT}/{@link Side#RIGHT} of the scroll target
     * address the sides the fragments were computed for. The request and
     * content titles are null — a {@code Plan} carries no path (the tree row
     * already names the file) and {@code SimpleDiffRequest} accepts null
     * titles. The contents are plain text for the same reason: the plan
     * carries no file name to resolve a syntax-highlighting file type from.
     * Both are presentation gaps P3 can close by widening the plan.</p>
     *
     * <p>{@code disposable} is accepted for the caller's lifecycle. Nothing
     * is registered on it: 263's {@link EditorFactory} has no
     * document-release API (bytecode), so the documents created here are
     * never explicitly released.</p>
     */
    public static SimpleDiffRequest toRequest(Project project, Disposable disposable, Plan plan) {
        Document left = EditorFactory.getInstance().createDocument(plan.leftText().toString());
        Document right = EditorFactory.getInstance().createDocument(plan.rightText().toString());
        SimpleDiffRequest request = new SimpleDiffRequest(null,
                new DocumentContentImpl(project, left, PlainTextFileType.INSTANCE),
                new DocumentContentImpl(project, right, PlainTextFileType.INSTANCE),
                null, null);
        applyUserData(request, plan);
        return request;
    }
}
