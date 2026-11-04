package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SubmitPlan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The pull request lane's submit, the parts with no UI (spec §8, "PR lane"):
 * the diff index a {@link SubmitPlan} checks anchors against, the fresh-head
 * check's classification, and what a posted review records in the store.
 * Ported from the app's submit flow ({@code MainWorkspace}), which the plugin
 * does not share.
 */
final class PrSubmission {

    private PrSubmission() {
    }

    /**
     * Where each row of {@code diff} sits (pure): a running position in diff
     * order and the hunk it belongs to, keyed {@code file + " " + lineKey},
     * {@code name} naming the diff in a refusal ("the PR's current head").
     */
    static SubmitPlan.DiffIndex diffIndex(UnifiedDiff diff, String name) {
        Map<String, Integer> positionOfKey = new HashMap<>();
        Map<String, Integer> hunkOfKey = new HashMap<>();
        int position = 0;
        for (UnifiedDiff.FileDiff file : diff.files()) {
            int hunkIndex = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    String key = file.path() + " " + line.lineKey();
                    positionOfKey.put(key, position++);
                    hunkOfKey.put(key, hunkIndex);
                }
                hunkIndex++;
            }
        }
        return new SubmitPlan.DiffIndex(positionOfKey, hunkOfKey, name);
    }

    /** The text of {@code diff}'s row {@code (file, lineKey)}, for a body note's excerpt (pure). */
    static BiFunction<String, String, Optional<String>> lineText(UnifiedDiff diff) {
        Map<String, String> text = new HashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    text.put(file.path() + " " + line.lineKey(), line.text());
                }
            }
        }
        return (file, key) -> Optional.ofNullable(text.get(file + " " + key));
    }

    /**
     * The body notes for the refused findings the reviewer chose to post in
     * the review body anyway (pure): each from the finding itself and the
     * <em>reviewed</em> diff's line text -- the anchor and excerpt the review
     * read, which is the point of an override: the content posts on its own
     * terms, not on a moved diff's. A key with no finding left is skipped.
     */
    static List<SubmitPlan.BodyNote> overriddenNotes(Set<ReviewAnnotation.Key> overridden,
                                                     List<ReviewAnnotation> findings,
                                                     BiFunction<String, String, Optional<String>> lineText) {
        List<SubmitPlan.BodyNote> notes = new ArrayList<>();
        for (ReviewAnnotation.Key key : overridden) {
            findings.stream().filter(finding -> finding.key().equals(key)).findFirst().ifPresent(finding ->
                    notes.add(new SubmitPlan.BodyNote(key, finding.file(),
                            SubmitPlan.lineLabel(finding.startKey(), finding.endKey()),
                            lineText.apply(finding.file(), finding.startKey()).orElse(""),
                            SubmitPlan.bodyOf(finding))));
        }
        return List.copyOf(notes);
    }

    /** The decisions {@link SubmitPlan#preselect} weighs: one per verdict of the scope (pure). */
    static List<ReviewVerdict.Decision> decisions(List<ReviewVerdict> verdicts) {
        return verdicts.stream().map(ReviewVerdict::decision).toList();
    }

    /** What the fresh-head check found, and the note the dialog shows (empty when there is nothing to say). */
    record FreshHead(State state, String note) {
        enum State { UNCHANGED, MOVED, UNCERTAIN }

        FreshHead {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(note, "note");
        }

        static FreshHead uncertain(String why) {
            return new FreshHead(State.UNCERTAIN, "Could not verify against the pull request's current head ("
                    + why + "): anchors were checked against the diff as reviewed.");
        }
    }

    /**
     * The fresh-head classification (pure): the same commit means the
     * review and the PR describe the same code; a different one means the
     * plan is rebuilt against the PR's current diff; no answer from gh is
     * uncertain, and the submit proceeds against the reviewed diff with a
     * note that says so.
     */
    static FreshHead classify(Optional<String> remoteHead, Optional<String> localHead) {
        if (remoteHead.isEmpty() || remoteHead.get().isBlank()) {
            return FreshHead.uncertain("gh did not answer");
        }
        if (localHead.isPresent() && remoteHead.get().equals(localHead.get())) {
            return new FreshHead(FreshHead.State.UNCHANGED, "");
        }
        String remote = remoteHead.get();
        return new FreshHead(FreshHead.State.MOVED, "The pull request has newer commits ("
                + remote.substring(0, Math.min(8, remote.length())) + "…): every comment's anchor was "
                + "checked against its current head, and the ones a newer push moved are listed as not posting.");
    }

    /**
     * Records a posted review (the store's writer): the scope submitted, and
     * every posted finding SENT with {@code postToPr} cleared, so it moves to
     * the inbox's Done tab and no later hand-off sends it to the session as a
     * fix. A finding whose wording was changed in the dialog gets the text
     * that reached GitHub as a reply ("As posted to the PR: …"), where the
     * reader and the agent's next round find it.
     */
    static void recordPosted(AnnotationStore store, String scopeId, SubmitPlan posted,
                             Map<ReviewAnnotation.Key, String> editedBodies, Instant at) {
        store.markSubmitted(scopeId);
        for (ReviewAnnotation.Key key : posted.posting()) {
            store.mutate(key, finding -> finding.withPostToPr(false).withStatus(AnnotationStatus.SENT));
        }
        for (Map.Entry<ReviewAnnotation.Key, String> edited : new LinkedHashMap<>(editedBodies).entrySet()) {
            store.mutate(edited.getKey(), finding -> finding.withReply(
                    new ReviewAnnotation.Message("You", at, "As posted to the PR: " + edited.getValue())));
        }
    }
}
