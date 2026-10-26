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
import java.util.Objects;

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
 * 0-based editor lines; {@code kb/find-line-key-contract}); the parsing
 * itself lives in {@link ReviewFindingPlacement} (its one home), with
 * {@link #scrollTarget} kept as the compatibility seam the P2 preview
 * wiring calls. The position→key direction has no caller and is
 * deliberately not written. {@code FORCE_READ_ONLY},
 * {@code CUSTOM_DIFF_COMPUTER} and {@code SCROLL_TO_LINE} were
 * live-verified on {@code SimpleDiffRequest} in the P2 spike
 * ({@code kb/find-p2-spikes-live-results}).</p>
 */
public final class ReviewDiffRequests {

    /**
     * The pinned wire-contract name of the Drydock request key: requests
     * built before any rename carry this exact string in their user data,
     * so renaming it would stop identifying them as Drydock review diffs.
     */
    public static final String REQUEST_KEY_NAME = "drydock.review.diffRequest";

    /**
     * The Drydock request user-data key decorating only Drydock review
     * diffs (the value is {@link Boolean#TRUE}): the marker that tells a
     * Drydock-rendered diff from any other diff the IDE shows. A
     * {@link Key} compares by identity, not by its name string:
     * {@code Key.create(REQUEST_KEY_NAME)} mints a fresh, never-equal key,
     * so readers must use this one static instance (or receive it), never
     * re-{@code Key.create} the name.
     *
     * <p>No {@code DiffExtension} gates on this key: the P3 live spike
     * showed that EP never fires for these viewers, so the decoration seam
     * is a request-processor listener instead (cairn:
     * {@code intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam}).
     * The key exists to mark Drydock's own requests, nothing more.</p>
     */
    public static final Key<Boolean> REQUEST_KEY = Key.create(REQUEST_KEY_NAME);

    /**
     * The pinned wire-contract name of the Drydock request-context key,
     * which carries the request's {@link RequestContext} — a value whose
     * plan is treated as read-only — on every request the reusable diff
     * editor applies.
     */
    public static final String REQUEST_CONTEXT_KEY_NAME = "drydock.review.diffRequestContext";

    /**
     * The user-data key carrying one request's {@link RequestContext}
     * (a value whose plan is treated as read-only, not a defensively
     * copied one). Same identity rule as {@link #REQUEST_KEY}: a
     * {@link Key} compares by identity, so readers must use this one
     * static instance, never re-{@code Key.create} the name.
     */
    public static final Key<RequestContext> CONTEXT_KEY = Key.create(REQUEST_CONTEXT_KEY_NAME);

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

    /**
     * The context one reusable diff request carries: the diff's
     * file path and the {@link Plan} it was built from. A value whose
     * plan is treated as read-only — <em>not</em> an immutably copied
     * one: the plan is a record that retains the caller's
     * {@code CharSequence} texts and fragment {@code List} by reference,
     * so the context guarantees nothing against a caller that mutates
     * them after creation. The contract is that nothing in Drydock
     * mutates the plan after handing it over; callers must pass texts
     * and fragments they will not change. Two things the
     * reusable editor (one project-owned {@code DiffVirtualFile} that swaps
     * requests in place) needs and cannot get any other way:
     *
     * <ul>
     *   <li><strong>Identity that is not document identity.</strong> The
     *   file path names the request (the request title) and identifies the
     *   file being shown, so swap and stale decisions compare paths. The
     *   unified viewer creates its own document, so matching request
     *   content documents cannot identify anything (cairn:
     *   {@code intellij-findings-triage/2026-10-25-editor-area-diff-decoration-seam}).</li>
     *   <li><strong>The plan a newly-created processor is seeded from.</strong>
     *   After the reusable tab is closed and reopened (or a viewer is
     *   rebuilt), the editor re-applies the current request by rebuilding it
     *   from this plan; the plan is treated as read-only by contract
     *   (a record over texts and a fragment list retained by reference,
     *   not copied), so nothing in Drydock mutates it after creation —
     *   callers must hand over texts and fragments they will not
     *   change.</li>
     * </ul>
     *
     * <p>Seeding matters because the first editor-tab request must reach a
     * freshly created processor: a request kept only on the reusable file
     * leaves the processor's request null, so its first
     * {@code selectNotify -> updateRequest} applies nothing and the tab
     * stays blank until the second selection.</p>
     */
    public record RequestContext(String path, Plan plan) {
        public RequestContext {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(plan, "plan");
        }
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
     *
     * <p>The parsing itself is centralized in
     * {@link ReviewFindingPlacement#parse} (its one home — placement, drift
     * and source-editor decisions share it); this method stays as the
     * compatibility seam the existing preview wiring calls, and refuses
     * exactly what that parsing refuses.</p>
     */
    public static ScrollTarget scrollTarget(String lineKey) {
        ReviewFindingPlacement.LineKey key = ReviewFindingPlacement.parse(lineKey);
        return new ScrollTarget(key.side(), key.gitLine() - 1);
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
     * The context variant of {@link #applyUserData(UserDataHolder, Plan)}:
     * the four review keys of the plan, plus the
     * {@link RequestContext} (a value whose plan is treated as read-only)
     * under {@link #CONTEXT_KEY}. Pure; the headless
     * tests assert the produced user data here.
     */
    static void applyUserData(UserDataHolder target, RequestContext context) {
        Objects.requireNonNull(context, "context");
        applyUserData(target, context.plan());
        target.putUserData(CONTEXT_KEY, context);
    }

    /**
     * Assembles the platform request from a plan. Thin on purpose: two
     * documents, two read-only plain-text contents, and the user data from
     * {@link #applyUserData} — everything testable lives in the pure half.
     *
     * <p>Content 1 is the left (base) side and content 2 the right (current)
     * side, so {@link Side#LEFT}/{@link Side#RIGHT} of the scroll target
     * address the sides the fragments were computed for. The content
     * titles are null — the plan carries no per-side file names to resolve
     * titles or a syntax-highlighting file type from; both remain
     * presentation gaps. The <em>request</em> title is null here too: this
     * is the plan-only creation the P2 preview wiring calls; the widened
     * {@link #toRequest(Project, Disposable, RequestContext)} adds the
     * file-path title and the request context the reusable editor
     * needs, without changing what this method produces.
     *
     * <p>{@code disposable} is accepted for the caller's lifecycle. Nothing
     * is registered on it: 263's {@link EditorFactory} has no
     * document-release API (bytecode), so the documents created here are
     * never explicitly released.</p>
     */
    public static SimpleDiffRequest toRequest(Project project, Disposable disposable, Plan plan) {
        SimpleDiffRequest request = assemble(project, null, plan);
        applyUserData(request, plan);
        return request;
    }

    /**
     * The widened creation the reusable diff editor is fed: the request
     * from {@link #toRequest(Project, Disposable, Plan)}'s plan, plus the
     * file path as the request title and the
     * {@link RequestContext} (a value whose plan is treated as read-only)
     * under {@link #CONTEXT_KEY}. The four review
     * keys ({@code FORCE_READ_ONLY}, {@code CUSTOM_DIFF_COMPUTER},
     * {@code SCROLL_TO_LINE}, the request key) are exactly what the
     * plan-only creation produces — the context is added, nothing is
     * changed.
     */
    public static SimpleDiffRequest toRequest(Project project, Disposable disposable,
                                              RequestContext context) {
        Objects.requireNonNull(context, "context");
        SimpleDiffRequest request = assemble(project, context.path(), context.plan());
        applyUserData(request, context);
        return request;
    }

    private static SimpleDiffRequest assemble(Project project, String title, Plan plan) {
        Document left = EditorFactory.getInstance().createDocument(plan.leftText().toString());
        Document right = EditorFactory.getInstance().createDocument(plan.rightText().toString());
        return new SimpleDiffRequest(title,
                new DocumentContentImpl(project, left, PlainTextFileType.INSTANCE),
                new DocumentContentImpl(project, right, PlainTextFileType.INSTANCE),
                null, null);
    }
}
