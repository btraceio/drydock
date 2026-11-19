package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.domain.RepositoryId;
import app.drydock.review.ReviewIntent.Checkout;
import app.drydock.review.ReviewIntent.Origin;
import app.drydock.review.tour.ReviewTour;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReviewIntentTest {
    private static final RepositoryId REPO = RepositoryId.newId();
    private static final Checkout CHECKOUT = new ReviewIntent.Local(Path.of("/work/pr-77"));
    private static final PullRequestTarget PR = new PullRequestTarget("github.com/DataDog/drydock", 12);

    @Test
    void equalFieldsAreEqualAndAnyDifferentFieldIsNot() {
        var session = Optional.of(ManagedSessionId.newId());
        var a = ReviewIntent.pullRequest(REPO, CHECKOUT, session, PR, Origin.PULL_REQUEST_ROW, ReviewTour.Kind.TOUR);
        var b = ReviewIntent.pullRequest(REPO, new ReviewIntent.Local(Path.of("/work/pr-77")), session, PR,
                Origin.PULL_REQUEST_ROW, ReviewTour.Kind.TOUR);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.withTarget(ReviewIntent.LocalChanges.INSTANCE));
        assertNotEquals(a, ReviewIntent.pullRequest(REPO, CHECKOUT, session,
                new PullRequestTarget("github.com/DataDog/drydock", 13), Origin.PULL_REQUEST_ROW, ReviewTour.Kind.TOUR));
        assertNotEquals(a, ReviewIntent.pullRequest(REPO, CHECKOUT, Optional.empty(), PR, Origin.PULL_REQUEST_ROW,
                ReviewTour.Kind.TOUR));
        assertNotEquals(a, ReviewIntent.pullRequest(REPO, CHECKOUT, session, PR, Origin.WORKBENCH, ReviewTour.Kind.TOUR));
        assertNotEquals(a, ReviewIntent.pullRequest(REPO, CHECKOUT, session, PR, Origin.PULL_REQUEST_ROW,
                ReviewTour.Kind.SECTIONS));
    }

    @Test
    void localFactoryIsExplicitLocalEvenForACheckoutNamedLikeAPullRequest() {
        var intent = ReviewIntent.local(REPO, CHECKOUT, Optional.empty(), Origin.LOCAL_TREE, ReviewTour.Kind.SECTIONS);
        assertSame(ReviewIntent.LocalChanges.INSTANCE, intent.target());
        assertTrue(intent.pullRequestTarget().isEmpty());
        assertEquals(Origin.LOCAL_TREE, intent.origin());
        assertEquals(ReviewTour.Kind.SECTIONS, intent.kind());
    }

    @Test
    void explicitPullRequestIsExposedAndSurvivesNothingElseChanging() {
        var intent = ReviewIntent.pullRequest(REPO, CHECKOUT, Optional.empty(), PR, Origin.WORKBENCH, ReviewTour.Kind.TOUR);
        assertEquals(Optional.of(PR), intent.pullRequestTarget());
        var local = intent.withTarget(ReviewIntent.LocalChanges.INSTANCE);
        assertEquals(intent.repository(), local.repository());
        assertEquals(intent.checkout(), local.checkout());
        assertEquals(intent.origin(), local.origin());
    }

    @Test
    void invalidPullRequestTargetsAndNullsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PullRequestTarget("not a repo", 3));
        assertThrows(IllegalArgumentException.class, () -> new PullRequestTarget("github.com/o/r", 0));
        assertThrows(NullPointerException.class, () -> new ReviewIntent.PullRequest(null));
        assertThrows(NullPointerException.class, () -> ReviewIntent.local(REPO, null, Optional.empty(),
                Origin.LOCAL_TREE, ReviewTour.Kind.TOUR));
        assertThrows(NullPointerException.class, () -> ReviewIntent.local(REPO, CHECKOUT, null,
                Origin.LOCAL_TREE, ReviewTour.Kind.TOUR));
        assertThrows(IllegalArgumentException.class, () -> new ReviewIntent.Remote(" "));
    }

    @Test
    void remotePlaceholderIsOpaqueAndComparedByValue() {
        assertEquals(new ReviewIntent.Remote("box:abc"), new ReviewIntent.Remote("box:abc"));
        assertNotEquals(new ReviewIntent.Remote("box:abc"), new ReviewIntent.Remote("box:abd"));
    }

    @Test
    void noPublicHelperInfersAPullRequestFromIncidentalText() {
        for (Method m : ReviewIntent.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                assertNotEquals(String.class, p, m.getName() + " takes raw text");
                assertNotEquals(Path.class, p, m.getName() + " takes a bare path");
            }
            assertFalse(m.getName().toLowerCase().contains("infer"), m.getName());
            assertFalse(m.getName().toLowerCase().contains("branch"), m.getName());
        }
    }

    @Test
    void valueImportsNoPlatformUiType() {
        for (Class<?> c : new Class<?>[] {ReviewIntent.class, ReviewIntent.Local.class, ReviewIntent.Remote.class,
                ReviewIntent.PullRequest.class}) {
            for (var f : c.getDeclaredFields()) {
                String t = f.getType().getName();
                assertFalse(t.startsWith("com.intellij") || t.startsWith("javax.swing") || t.startsWith("javafx"), t);
            }
        }
    }
}
