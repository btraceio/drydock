package app.drydock.review.tour;

import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** What still stands between a step and passing it, first requirement first. */
public final class StepGate {

    public enum Kind { STALE, CHECK, TRIAGE, BLOCKER }

    public record Unmet(Kind kind, String id, String message) {
        public Unmet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(message, "message");
        }
    }

    private StepGate() {
    }

    public static Optional<Unmet> unmet(TourStep step, StepProgress progress) {
        if (progress.stale()) {
            return Optional.of(new Unmet(Kind.STALE, step.id(),
                    "This step's code changed since the tour was written; it is waiting for the agent."));
        }
        for (TourCheck check : step.checks()) {
            if (!progress.check(check.id()).settled()) {
                return Optional.of(new Unmet(Kind.CHECK, check.id(), "Answer this check first."));
            }
        }
        return Optional.empty();
    }

    /**
     * As {@link #unmet(TourStep, StepProgress)}, then the step's findings
     * (spec §4): every agent finding visible on the step must be triaged,
     * and a confirmed, unresolved blocker can only be rejected or
     * overridden, never passed.
     */
    public static Optional<Unmet> unmet(TourStep step, StepProgress progress, List<ReviewAnnotation> stepFindings,
                                        TourRecord record) {
        Optional<Unmet> checks = unmet(step, progress);
        if (checks.isPresent()) {
            return checks;
        }
        for (ReviewAnnotation finding : stepFindings) {
            if (finding.triage() == Triage.PROPOSED && !TourFindings.hidden(finding, record)) {
                return Optional.of(new Unmet(Kind.TRIAGE, finding.id(),
                        "Triage the agent's finding first: confirm it, dismiss it, or ask."));
            }
        }
        for (ReviewAnnotation finding : stepFindings) {
            if (finding.blocksApproval()) {
                return Optional.of(new Unmet(Kind.BLOCKER, finding.id(),
                        "A confirmed blocking finding is open here: request changes (r) or approve without passing."));
            }
        }
        return Optional.empty();
    }
}
