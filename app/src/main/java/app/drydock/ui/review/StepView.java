package app.drydock.ui.review;

import app.drydock.review.tour.StepGate;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourStep;

import java.util.Objects;
import java.util.Optional;

/**
 * What the step panel shows for the current step.
 *
 * @param unmet             what stands between the step and passing it, as
 *                          {@link StepGate} judges it with the step's
 *                          findings; decides whether the override is offered
 * @param refreshDispatched whether a refresh of this tour was actually handed
 *                          to the agent for the diff it is on, so a stale step
 *                          can say "re-writing" only when that is true
 */
record StepView(TourStep step, int number, int total, StepProgress progress, Optional<StepGate.Unmet> unmet,
                boolean refreshDispatched) {

    StepView {
        Objects.requireNonNull(unmet, "unmet");
    }

    /** A step with nothing judged against it yet and no refresh sent. */
    StepView(TourStep step, int number, int total, StepProgress progress) {
        this(step, number, total, progress, Optional.empty(), false);
    }
}
