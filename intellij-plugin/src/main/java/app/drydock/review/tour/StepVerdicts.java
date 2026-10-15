package app.drydock.review.tour;

import app.drydock.review.ReviewVerdict;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives the one stored verdict per hunk from the steps covering it.
 *
 * <p>CHANGES if any covering step requested changes; otherwise a hunk
 * override from the hunk diff decides; otherwise APPROVED only when every
 * covering step has passed or been overridden; otherwise unsettled.
 * Steps never write verdicts themselves, so two steps cannot race for one
 * hunk's slot and a step covering part of a hunk cannot approve all of it.</p>
 */
public final class StepVerdicts {

    private StepVerdicts() {
    }

    public static Map<String, Optional<ReviewVerdict.Decision>> derive(TourRecord record, AnchorIndex reviewIndex) {
        Map<String, Optional<ReviewVerdict.Decision>> derived = new LinkedHashMap<>();
        for (AnchorIndex.HunkRef hunk : reviewIndex.hunks()) {
            List<StepProgress> covering = record.progress().values().stream()
                    .filter(progress -> progress.hunkDigests().contains(hunk.digest()))
                    .toList();
            derived.put(hunk.digest(), decide(covering, Optional.ofNullable(record.hunkOverrides().get(hunk.digest()))));
        }
        return derived;
    }

    private static Optional<ReviewVerdict.Decision> decide(List<StepProgress> covering,
                                                           Optional<HunkOverride> override) {
        boolean changes = covering.stream()
                .anyMatch(progress -> !progress.stale() && progress.decision() == StepProgress.Decision.CHANGES);
        if (changes) {
            return Optional.of(ReviewVerdict.Decision.CHANGES);
        }
        if (override.isPresent()) {
            return Optional.of(override.get().decision());
        }
        if (!covering.isEmpty() && covering.stream().allMatch(StepProgress::settledForApproval)) {
            return Optional.of(ReviewVerdict.Decision.APPROVED);
        }
        return Optional.empty();
    }
}
