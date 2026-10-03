package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One step of a tour: what it covers, why it exists, and what checks gate it. */
public record TourStep(String id, String title, String narrative, List<TourAnchor> anchors,
                       List<ImpactNote> impactNotes, List<TourCheck> checks) {

    public TourStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(narrative, "narrative");
        anchors = List.copyOf(anchors);
        impactNotes = List.copyOf(impactNotes);
        checks = List.copyOf(checks);
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
