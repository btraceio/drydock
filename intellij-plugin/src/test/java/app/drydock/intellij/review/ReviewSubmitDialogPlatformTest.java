package app.drydock.intellij.review;

import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.SubmitPlan;
import app.drydock.review.Triage;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/** The submit dialog over a real plan and a fake post: Submit's rules, the rewording, and the post's outcomes. */
public class ReviewSubmitDialogPlatformTest extends BasePlatformTestCase {

    private static final UnifiedDiff DIFF = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0,
            false, false, List.of(new UnifiedDiff.Hunk("@@ -1,1 +1,2 @@", List.of(
                    new Line(Line.Kind.CONTEXT, OptionalInt.of(1), OptionalInt.of(1), "a"),
                    new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(2), "b")))))));

    private final List<String> posts = new ArrayList<>();
    private CompletableFuture<Optional<String>> answer = new CompletableFuture<>();

    private SubmitPlan plan() {
        ReviewAnnotation finding = ReviewAnnotation.human("rs_1", "src/A.java", "n2", "n2",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Rename b.")).withTriage(Triage.CONFIRMED)
                .withPostToPr(true);
        return SubmitPlan.of(List.of(finding), List.of(), PrSubmission.diffIndex(DIFF, "this diff"));
    }

    private ReviewSubmitDialog dialog(Optional<String> unavailable) {
        return new ReviewSubmitDialog(getProject(), 7, plan(), unavailable, "", 0, (plan, edited, event, summary) -> {
            posts.add(event + " " + summary + " " + edited.values());
            return answer;
        });
    }

    public void testACommentReviewNeedsASummaryAndGhMustBeUsable() {
        ReviewSubmitDialog dialog = dialog(Optional.empty());
        try {
            assertEquals(Event.COMMENT, dialog.selectedEvent());
            assertFalse("a Comment review needs a summary", dialog.canSubmit());
            dialog.summary.setText("Looks fine.");
            assertTrue(dialog.canSubmit());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
        ReviewSubmitDialog offline = dialog(Optional.of("gh is not authenticated"));
        try {
            offline.summary.setText("x");
            assertFalse(offline.canSubmit());
        } finally {
            Disposer.dispose(offline.getDisposable());
        }
    }

    public void testSubmitPostsTheRewordingAndARejectionStaysInTheDialog() {
        ReviewSubmitDialog dialog = dialog(Optional.empty());
        try {
            assertEquals(List.of("Rename b."), dialog.commentBodies());
            dialog.summary.setText("Two notes.");
            dialog.doOKAction();
            assertEquals(List.of("COMMENT Two notes. []"), posts);
            assertFalse("busy while posting", dialog.canSubmit());

            answer.complete(Optional.of("Validation Failed"));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals("Validation Failed", dialog.error.getText());
            assertTrue(dialog.canSubmit());
            assertFalse(dialog.isDisposed());
        } finally {
            Disposer.dispose(dialog.getDisposable());
        }
    }

    public void testTheEventsAreNamedForTheReviewer() {
        assertEquals("Request changes", ReviewSubmitDialog.label(Event.REQUEST_CHANGES));
        assertEquals("Approve", ReviewSubmitDialog.label(Event.APPROVE));
    }
}
