package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An agent-written tour of one review scope, valid for the diff whose
 * fingerprint it carries. {@code intent} is the agent's one-sentence
 * statement of what the change sets out to do; empty when it gave none,
 * and for every tour written before the field existed. {@code takes} are
 * the agent's per-hunk takes, at most one per hunk digest; empty for every
 * tour written before them. {@code kind} is what the agent was asked for:
 * the educational tour, whose steps carry checks, or the section-based
 * review, whose steps carry none; every tour written before the field is a
 * {@link Kind#TOUR}.
 */
public record ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps, Optional<String> intent,
                         List<HunkTake> takes, Kind kind) {

    /** What kind of review the tour is. */
    public enum Kind {
        /** The educational tour: every step carries at least one check. */
        TOUR("tour"),
        /** The section-based review: the change grouped into sections, no step carries a check. */
        SECTIONS("sections");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /** The name on the wire and in the store; a stable contract. */
        public String wireName() {
            return wireName;
        }

        /** The kind named {@code name}, if one is. */
        public static Optional<Kind> ofWireName(String name) {
            for (Kind kind : values()) {
                if (kind.wireName.equals(name)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    public ReviewTour {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(diffFingerprint, "diffFingerprint");
        steps = List.copyOf(steps);
        intent = Objects.requireNonNull(intent, "intent").map(String::strip).filter(text -> !text.isEmpty());
        takes = List.copyOf(takes);
        Objects.requireNonNull(kind, "kind");
    }

    /** An educational tour. */
    public ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps, Optional<String> intent,
                      List<HunkTake> takes) {
        this(scopeId, diffFingerprint, steps, intent, takes, Kind.TOUR);
    }

    /** A tour without a stated intent or takes. */
    public ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps) {
        this(scopeId, diffFingerprint, steps, Optional.empty(), List.of());
    }

    /** A tour without takes. */
    public ReviewTour(String scopeId, String diffFingerprint, List<TourStep> steps, Optional<String> intent) {
        this(scopeId, diffFingerprint, steps, intent, List.of());
    }

    /** The take on the hunk with {@code digest}, if the agent gave one. */
    public Optional<HunkTake> take(String digest) {
        return takes.stream().filter(take -> take.hunkDigest().equals(digest)).findFirst();
    }

    public ReviewTour withTakes(List<HunkTake> newTakes) {
        return new ReviewTour(scopeId, diffFingerprint, steps, intent, newTakes, kind);
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
        return new ReviewTour(scopeId, diffFingerprint, newSteps, intent, takes, kind);
    }

    public ReviewTour withFingerprint(String fingerprint) {
        return new ReviewTour(scopeId, fingerprint, steps, intent, takes, kind);
    }

    public ReviewTour withIntent(Optional<String> newIntent) {
        return new ReviewTour(scopeId, diffFingerprint, steps, newIntent, takes, kind);
    }
}
