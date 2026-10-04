package app.drydock.review.tour;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The reviewer's progress on one step, and the digests of the review-diff
 * hunks the step covers -- the key both staleness and verdict derivation
 * work from.
 */
public record StepProgress(String stepId, List<String> hunkDigests, Map<String, CheckProgress> checks,
                           Decision decision, Optional<String> overrideReason, boolean stale) {

    public enum Decision { NONE, PASSED, CHANGES, OVERRIDDEN }

    public StepProgress {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(overrideReason, "overrideReason");
        hunkDigests = List.copyOf(hunkDigests);
        checks = Map.copyOf(checks);
    }

    public static StepProgress fresh(TourStep step, AnchorIndex index) {
        Map<String, CheckProgress> checks = new LinkedHashMap<>();
        for (TourCheck check : step.checks()) {
            checks.put(check.id(), CheckProgress.fresh(check.id()));
        }
        List<String> digests = step.anchors().stream()
                .flatMap(anchor -> index.hunksTouched(anchor).stream())
                .map(AnchorIndex.HunkRef::digest)
                .distinct()
                .toList();
        return new StepProgress(step.id(), digests, checks, Decision.NONE, Optional.empty(), false);
    }

    /** Progress on top-level check {@code checkId}; fresh if none was recorded. */
    public CheckProgress check(String checkId) {
        return checks.getOrDefault(checkId, CheckProgress.fresh(checkId));
    }

    public StepProgress withCheck(CheckProgress progress) {
        Map<String, CheckProgress> next = new LinkedHashMap<>(checks);
        next.put(progress.checkId(), progress);
        return new StepProgress(stepId, hunkDigests, next, decision, overrideReason, stale);
    }

    public StepProgress withDecision(Decision newDecision, Optional<String> reason) {
        return new StepProgress(stepId, hunkDigests, checks, newDecision, reason, stale);
    }

    public StepProgress withStale(boolean newStale) {
        return new StepProgress(stepId, hunkDigests, checks, decision, overrideReason, newStale);
    }

    /** Passed or overridden, and not stale. */
    public boolean settledForApproval() {
        return !stale && (decision == Decision.PASSED || decision == Decision.OVERRIDDEN);
    }
}
