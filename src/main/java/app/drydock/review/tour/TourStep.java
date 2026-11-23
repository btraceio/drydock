package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One step of a tour: what it covers, why it exists, and what checks gate it.
 *
 * <p>{@code dependsOn} names the earlier steps this one builds on (step ids;
 * {@link TourValidator} requires each to come before it). Empty for a step
 * that builds on nothing, and for every tour written before the field
 * existed.</p>
 */
public record TourStep(String id, String title, String narrative, List<TourAnchor> anchors,
                       List<ImpactNote> impactNotes, List<TourCheck> checks,
                       Optional<TourDiagram> diagram, List<String> dependsOn) {

    /** As the wire's older shape: a step without a diagram. */
    public TourStep(String id, String title, String narrative, List<TourAnchor> anchors,
                    List<ImpactNote> impactNotes, List<TourCheck> checks) {
        this(id, title, narrative, anchors, impactNotes, checks, Optional.empty(), List.of());
    }

    /** As the wire's shape before dependencies: a step that builds on no other step. */
    public TourStep(String id, String title, String narrative, List<TourAnchor> anchors,
                    List<ImpactNote> impactNotes, List<TourCheck> checks,
                    Optional<TourDiagram> diagram) {
        this(id, title, narrative, anchors, impactNotes, checks, diagram, List.of());
    }

    public TourStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(narrative, "narrative");
        anchors = List.copyOf(anchors);
        impactNotes = List.copyOf(impactNotes);
        checks = List.copyOf(checks);
        diagram = Objects.requireNonNull(diagram, "diagram");
        dependsOn = List.copyOf(dependsOn);
    }

    /** The top-level check with {@code checkId}, or the top-level check owning that alternate id. */
    public Optional<TourCheck> check(String checkId) {
        for (TourCheck check : checks) {
            if (check.id().equals(checkId)) {
                return Optional.of(check);
            }
            for (TourCheck alternate : check.alternates()) {
                if (alternate.id().equals(checkId)) {
                    return Optional.of(check);
                }
            }
        }
        return Optional.empty();
    }
}
