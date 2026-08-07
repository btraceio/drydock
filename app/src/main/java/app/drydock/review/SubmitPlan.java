package app.drydock.review;

import app.drydock.github.GitHubLineAnchor;
import app.drydock.github.GitHubReviewRequest.Comment;
import app.drydock.github.GitHubReviewRequest.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What Submit would post to GitHub, computed before a single network call is
 * made: which findings become comments, which are refused because GitHub
 * would reject them, and which review event to preselect. Deliberately plain
 * JDK types (no {@code ReviewDiffRow}/{@code ReviewDiffColumn}) -- this record
 * is what crosses from {@code app.drydock.ui.review}, which owns the diff,
 * into {@code app.drydock.ui}, which owns the host and cannot see a diff row.
 */
public record SubmitPlan(Event preselected, List<Comment> comments, List<ReviewAnnotation.Key> posting,
                          List<Refusal> refusals) {

    /** A finding GitHub would reject, and why -- named in words a human can act on. */
    public record Refusal(ReviewAnnotation.Key key, String reason) {
        public Refusal {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Where each diff line sits, so the refusal rules below are decidable.
     * Both maps are keyed by {@code file + " " + lineKey}. {@code
     * positionOfKey} is a monotonically increasing ordinal in diff order --
     * membership in the diff is {@code positionOfKey.containsKey(...)}, and
     * position is what lets the reversed-range guard be decided at all: an
     * {@code o}-key and an {@code n}-key live in different namespaces and
     * cannot be compared by their line numbers alone.
     */
    public record DiffIndex(Map<String, Integer> positionOfKey, Map<String, Integer> hunkOfKey) {
        public DiffIndex {
            Objects.requireNonNull(positionOfKey, "positionOfKey");
            Objects.requireNonNull(hunkOfKey, "hunkOfKey");
        }

        private String key(String file, String lineKey) {
            return file + " " + lineKey;
        }
    }

    /**
     * Which review event to preselect from the human's verdicts on this
     * scope's counted intents. Any {@code CHANGES} outweighs everything else;
     * an empty list of decisions (a scope with no counted intents) preselects
     * a plain comment, since {@code ReviewDestinationView.submitReview()}
     * only reaches {@code host.submit} once every counted intent has a
     * verdict, so this is reachable only when there are none to have one.
     * Switches over {@code Decision} exhaustively with no {@code default} --
     * a fourth constant must fail to compile here, not silently approve.
     */
    public static Event preselect(List<ReviewVerdict.Decision> decisions) {
        if (decisions.isEmpty()) {
            return Event.COMMENT;
        }
        for (ReviewVerdict.Decision decision : decisions) {
            switch (decision) {
                case CHANGES -> {
                    return Event.REQUEST_CHANGES;
                }
                case APPROVED, AUTO_APPROVED -> {
                    // still eligible for APPROVE; keep scanning for a CHANGES
                }
            }
        }
        return Event.APPROVE;
    }

    /** GitHub refuses an empty body for {@code COMMENT} and {@code REQUEST_CHANGES}, but not {@code APPROVE}. */
    public static boolean needsSummary(Event event) {
        return event == Event.COMMENT || event == Event.REQUEST_CHANGES;
    }

    /**
     * Builds the plan: every {@code postToPr} finding becomes a comment or a
     * refusal, in encounter order. A finding with {@code postToPr() == false}
     * is silently excluded from both -- it simply is not being posted.
     *
     * <p>A {@link ReviewAnnotation#resolved()} finding is excluded the same
     * way, REGARDLESS of {@code postToPr}: the margin's default filter
     * ({@code Filter.OPEN}) hides resolved cards, so a stale {@code
     * postToPr} left over from before it was resolved has no visible toggle
     * to opt back out with short of switching to "all" -- posting it anyway
     * would be a publish the human never had a real chance to review.</p>
     */
    public static SubmitPlan of(List<ReviewAnnotation> findings, List<ReviewVerdict.Decision> decisions,
                                 DiffIndex index) {
        List<Comment> comments = new ArrayList<>();
        List<ReviewAnnotation.Key> posting = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();

        for (ReviewAnnotation finding : findings) {
            if (!finding.postToPr() || finding.resolved()) {
                continue;
            }
            String startCompositeKey = index.key(finding.file(), finding.startKey());
            String endCompositeKey = index.key(finding.file(), finding.endKey());

            Integer startPosition = index.positionOfKey().get(startCompositeKey);
            Integer endPosition = index.positionOfKey().get(endCompositeKey);
            if (startPosition == null || endPosition == null) {
                refusals.add(new Refusal(finding.key(), "line %s is not in this diff"
                        .formatted(startPosition == null ? finding.startKey() : finding.endKey())));
                continue;
            }

            Integer startHunk = index.hunkOfKey().get(startCompositeKey);
            Integer endHunk = index.hunkOfKey().get(endCompositeKey);
            if (!Objects.equals(startHunk, endHunk)) {
                refusals.add(new Refusal(finding.key(), "%s lines %s–%s span two hunks; GitHub takes one hunk per comment"
                        .formatted(finding.file(), finding.startKey(), finding.endKey())));
                continue;
            }

            if (startPosition > endPosition) {
                refusals.add(new Refusal(finding.key(), "%s lines %s–%s: the range runs backwards"
                        .formatted(finding.file(), finding.startKey(), finding.endKey())));
                continue;
            }

            // A cross-side range is only legal LEFT->RIGHT -- start on a
            // deleted (`o`) line, end on a post-image (`n`) line, GitHub's
            // "comment on lines -55 to +58" shape. Git routinely interleaves
            // hunks (-a +b -c +d), so a RIGHT->LEFT drag (start on an `n`
            // line, end on an `o` line) can still have startPosition <=
            // endPosition and pass the check above. GitHubLineAnchor.of would
            // then emit start_side: RIGHT with side: LEFT, which GitHub
            // rejects -- and since the whole review is one atomic POST, that
            // single rejection would 422 every other comment in it.
            boolean startDeleted = finding.startKey().startsWith("o");
            boolean endDeleted = finding.endKey().startsWith("o");
            if (!startDeleted && endDeleted) {
                refusals.add(new Refusal(finding.key(),
                        ("%s lines %s–%s: a range across a deletion must start on the deleted line and end "
                                + "on its replacement, not the other way round")
                                .formatted(finding.file(), finding.startKey(), finding.endKey())));
                continue;
            }

            GitHubLineAnchor.Anchor anchor = GitHubLineAnchor.of(finding.startKey(), finding.endKey());
            comments.add(new Comment(finding.file(), bodyOf(finding), anchor));
            posting.add(finding.key());
        }

        return new SubmitPlan(preselect(decisions), comments, posting, refusals);
    }

    /** The last message the human wrote on this thread, falling back to the finding's own first message. */
    private static String bodyOf(ReviewAnnotation finding) {
        List<ReviewAnnotation.Message> thread = finding.thread();
        for (int i = thread.size() - 1; i >= 0; i--) {
            ReviewAnnotation.Message message = thread.get(i);
            if ("You".equals(message.author())) {
                return message.text();
            }
        }
        return thread.isEmpty() ? "" : thread.get(0).text();
    }
}
