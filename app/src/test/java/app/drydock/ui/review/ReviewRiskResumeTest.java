package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.review.SessionReviewScopes;
import javafx.scene.control.Button;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A risk answer persisted as awaiting the agent is asked about again when the board opens (spec section 8). */
class ReviewRiskResumeTest extends ReviewTourFixture {

    private void storeAwaitingAnswerAndReopen() throws Exception {
        TourCheck risk = new TourCheck("r1", TourCheck.Kind.RISK, "What could go wrong?", List.of(),
                OptionalInt.empty(), "It could throw.", List.of());
        TourCheck alt = new TourCheck("r1_alt", TourCheck.Kind.RISK, "Again?", List.of(), OptionalInt.empty(),
                "E.", List.of());
        TourCheck withAlt = new TourCheck(risk.id(), risk.kind(), risk.prompt(), List.of(), OptionalInt.empty(),
                risk.explanation(), List.of(alt));
        ReviewTour tour = new ReviewTour(scope.id(), TourFingerprint.of(host.diff), List.of(
                new TourStep("s1", "Guards header", "Why.", List.of(new TourAnchor(FILE_A, "n1", "n11")),
                        List.of(), List.of(withAlt)),
                new TourStep("s2", "Guards source", "Why.", List.of(new TourAnchor(FILE_B, "n1", "n1")),
                        List.of(), List.of(predictCheck()))));
        TourRecord fresh = TourRecord.fresh(tour, host.diff);
        CheckProgress waiting = StepGrading.submitRisk(CheckProgress.fresh("r1"), "it might throw");
        StepProgress step = fresh.progress("s1").withCheck(waiting);
        host.tours.put(fresh.withProgress(step));
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static TourCheck predictCheck() {
        TourCheck.Choice a = new TourCheck.Choice("a", Optional.empty());
        TourCheck.Choice b = new TourCheck.Choice("b", Optional.empty());
        TourCheck alt = new TourCheck("c2_alt", TourCheck.Kind.PREDICT, "Alt?", List.of(a, b), OptionalInt.of(0),
                "E.", List.of());
        return new TourCheck("c2", TourCheck.Kind.PREDICT, "What?", List.of(a, b), OptionalInt.of(0), "E.",
                List.of(alt));
    }

    @Test
    void anAnswerLeftAwaitingByAPreviousRunIsAskedAboutAgain() throws Exception {
        storeAwaitingAnswerAndReopen();

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> host.riskCheckDispatches.contains("r1"));
        assertEquals(List.of("r1"), host.riskCheckDispatches);
    }

    @Test
    void aFailedHandOffLeavesTheCheckUnavailableNotWaitingForever() throws Exception {
        host.riskCheckHandOffSucceeds = false;

        storeAwaitingAnswerAndReopen();

        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> progress("s1").check("r1").status()
                == CheckProgress.Status.AGENT_UNAVAILABLE);
        assertTrue(lookup("Retry").tryQuery().isPresent() || lookup(".button").queryAllAs(
                Button.class).stream().anyMatch(b -> "Retry".equals(b.getText())));
    }
}
