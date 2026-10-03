package app.drydock.review.tour;

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
}
