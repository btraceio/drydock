package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An agent-written tour of one review scope, valid for the diff whose
 * fingerprint it carries. {@code intent} is the agent's one-sentence
 * statement of what the change sets out to do; empty when it gave none,
 * and for every tour written before the field existed.
 */
public record ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps, Optional<String> intent) {

    public ReviewTour {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(diffFingerprint, "diffFingerprint");
        steps = List.copyOf(steps);
        intent = Objects.requireNonNull(intent, "intent").map(String::strip).filter(text -> !text.isEmpty());
    }

    /** A tour without a stated intent. */
    public ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps) {
        this(scopeId, diffFingerprint, steps, Optional.empty());
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
        return new ReviewTour(scopeId, diffFingerprint, newSteps, intent);
    }

    public ReviewTour withFingerprint(String fingerprint) {
        return new ReviewTour(scopeId, fingerprint, steps, intent);
    }

    public ReviewTour withIntent(Optional<String> newIntent) {
        return new ReviewTour(scopeId, diffFingerprint, steps, newIntent);
    }
}
