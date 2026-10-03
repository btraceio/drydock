package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An agent-written tour of one review scope, valid for the diff whose fingerprint it carries. */
public record ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps) {

    public ReviewTour {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(diffFingerprint, "diffFingerprint");
        steps = List.copyOf(steps);
    }

    public Optional<TourStep> step(String id) {
        return steps.stream().filter(step -> step.id().equals(id)).findFirst();
    }

    /** The step whose checks (or their alternates) include {@code checkId}. */
    public Optional<TourStep> stepOfCheck(String checkId) {
        return steps.stream().filter(step -> step.check(checkId).isPresent()).findFirst();
    }

    /** 1-based position of the step, or 0 when absent. */
    public int number(String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i + 1;
            }
        }
        return 0;
    }

    public ReviewTour withSteps(List<TourStep> newSteps) {
        return new ReviewTour(scopeId, diffFingerprint, newSteps);
    }

    public ReviewTour withFingerprint(String fingerprint) {
        return new ReviewTour(scopeId, fingerprint, steps);
    }
}
