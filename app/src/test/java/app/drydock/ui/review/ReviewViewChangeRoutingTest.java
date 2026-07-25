package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.review.ReviewAnnotation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ReviewView#routeSingleAnnotationChange}, the pure
 * decision {@code onAnnotationChanged} delegates to. Pure and
 * headless-testable (no FX Application Thread, no store) -- mirrors {@link
 * ReviewRowModelsTest}'s pattern for the same reason: this view's routing
 * logic should be checkable without standing up a live {@link ReviewView}.
 *
 * <p>Covers two review findings on the original notification wiring:
 * a present-but-unrendered annotation must route to {@code REBUILD_FILE}
 * (not silently do nothing, since {@code replaceCardRow} can't insert a row
 * that never existed), and a change belonging to another session/scope/file
 * must route to {@code IGNORE} (the store is shared across every open
 * session tab, so most notifications are not about this view at all).</p>
 */
class ReviewViewChangeRoutingTest {

    private static final Instant AT = Instant.parse("2026-07-19T12:00:00Z");
    private static final ManagedSessionId VIEW_SESSION = ManagedSessionId.newId();
    private static final DiffScope VIEW_SCOPE = DiffScope.BASE;
    private static final String SELECTED_FILE = "src/Main.java";

    private static ReviewAnnotation annotationIn(ManagedSessionId sessionId, DiffScope scope, String file) {
        return ReviewAnnotation.create(sessionId, scope, file, "n1", "n1",
                new ReviewAnnotation.Message("You", AT, "look here"));
    }

    @Test
    void rerenderedAnnotationReplacesItsRow() {
        ReviewAnnotation current = annotationIn(VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE);

        ReviewView.ChangeRoute route = ReviewView.routeSingleAnnotationChange(
                VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE, /* rendered */ true, current);

        assertEquals(ReviewView.ChangeRoute.REPLACE_ROW, route);
    }

    @Test
    void presentButUnrenderedAnnotationOnTheOnScreenFileRebuilds() {
        // E.g. a fresh add from another writer: not yet a row, but it
        // belongs to the file currently shown -- must not be dropped.
        ReviewAnnotation current = annotationIn(VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE);

        ReviewView.ChangeRoute route = ReviewView.routeSingleAnnotationChange(
                VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE, /* rendered */ false, current);

        assertEquals(ReviewView.ChangeRoute.REBUILD_FILE, route);
    }

    @Test
    void presentButDifferentFileIsIgnored() {
        ReviewAnnotation current = annotationIn(VIEW_SESSION, VIEW_SCOPE, "src/Other.java");

        ReviewView.ChangeRoute route = ReviewView.routeSingleAnnotationChange(
                VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE, /* rendered */ false, current);

        assertEquals(ReviewView.ChangeRoute.IGNORE, route);
    }

    @Test
    void anotherSessionsAnnotationIsIgnoredEvenIfSomehowRendered() {
        ReviewAnnotation current = annotationIn(ManagedSessionId.newId(), VIEW_SCOPE, SELECTED_FILE);

        ReviewView.ChangeRoute route = ReviewView.routeSingleAnnotationChange(
                VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE, /* rendered */ true, current);

        assertEquals(ReviewView.ChangeRoute.IGNORE, route);
    }

    @Test
    void anotherScopesAnnotationIsIgnored() {
        ReviewAnnotation current = annotationIn(VIEW_SESSION, DiffScope.WORKING_TREE, SELECTED_FILE);

        ReviewView.ChangeRoute route = ReviewView.routeSingleAnnotationChange(
                VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE, /* rendered */ true, current);

        assertEquals(ReviewView.ChangeRoute.IGNORE, route);
    }

    @Test
    void removedAnnotationThatWasRenderedRebuilds() {
        ReviewView.ChangeRoute route = ReviewView.routeSingleAnnotationChange(
                VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE, /* rendered */ true, /* current */ null);

        assertEquals(ReviewView.ChangeRoute.REBUILD_FILE, route);
    }

    @Test
    void removedAnnotationThatWasNeverRenderedIsIgnored() {
        // The Finding 2 scenario: e.g. a removeSession bulk delete of an
        // unrelated session must not disturb this view.
        ReviewView.ChangeRoute route = ReviewView.routeSingleAnnotationChange(
                VIEW_SESSION, VIEW_SCOPE, SELECTED_FILE, /* rendered */ false, /* current */ null);

        assertEquals(ReviewView.ChangeRoute.IGNORE, route);
    }
}
