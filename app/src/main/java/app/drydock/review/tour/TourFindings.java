package app.drydock.review.tour;

import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;

import java.util.List;
import java.util.Optional;

/**
 * How agent findings meet the tour (spec §4): which lie on a step, which are
 * still withheld behind a check, and which are blockers that stop the tour.
 */
public final class TourFindings {

    private TourFindings() {
    }

    /** The findings whose first line lies inside one of {@code step}'s anchors. */
    public static List<ReviewAnnotation> onStep(TourStep step, List<ReviewAnnotation> findings,
                                                AnchorIndex reviewIndex) {
        return findings.stream()
                .filter(finding -> step.anchors().stream()
                        .anyMatch(anchor -> reviewIndex.contains(anchor, finding.file(), finding.startKey())))
                .toList();
    }

    /**
     * Whether {@code finding} is still withheld: it names a check, has not
     * been dismissed, its step was not overridden, and its check has not
     * been answered yet. Once answered -- right or wrong -- it is revealed,
     * and that reveal is its triage moment.
     */
    public static boolean hidden(ReviewAnnotation finding, TourRecord record) {
        if (finding.withheldBy().isEmpty() || finding.triage() == Triage.DISMISSED) {
            return false;
        }
        String checkId = finding.withheldBy().get();
        Optional<TourStep> step = record.tour().stepOfCheck(checkId);
        if (step.isEmpty()) {
            // A check the tour does not have can never be answered; hiding
            // the finding behind it would lose it.
            return false;
        }
        StepProgress progress = record.progress(step.get().id());
        if (progress.decision() == StepProgress.Decision.OVERRIDDEN) {
            return false;
        }
        TourCheck owner = step.get().check(checkId).orElseThrow();
        CheckProgress check = progress.check(owner.id());
        return check.status() == CheckProgress.Status.OPEN && check.attempt() == 0
                && check.lastExplanation().isEmpty();
    }

    /** Unresolved BLOCKING findings the reviewer has not dismissed. */
    public static List<ReviewAnnotation> blockers(List<ReviewAnnotation> findings) {
        return findings.stream()
                .filter(finding -> finding.effectiveSeverity() == Severity.BLOCKING)
                .filter(finding -> !finding.resolved())
                .filter(finding -> finding.triage() != Triage.DISMISSED)
                .toList();
    }

    /** Whether the blocker banner stands in front of the tour. */
    public static boolean needsBanner(TourRecord record, List<ReviewAnnotation> findings) {
        return !record.reviewAnyway() && !blockers(findings).isEmpty();
    }
}
