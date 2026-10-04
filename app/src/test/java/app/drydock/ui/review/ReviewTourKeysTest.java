package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.ui.nav.SymbolPeek;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keys that must act on what the reader can see, and nothing else. */
class ReviewTourKeysTest extends ReviewTourFixture {

    private void key(KeyCode code) {
        press(code).release(code);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void digitsDoNotAnswerTheCheckUnderAnOpenPeek() {
        interact(() -> view.diagPushPeek(new SymbolPeek("bar", "bar · guards.h",
                Path.of("/tmp/nowhere").resolve(FILE_A), Path.of(FILE_A), 11, List.of("void bar();"), Set.of(),
                List.of(), true)));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(ReviewDiagFxThread.call(view::diagPeekOpen));

        key(KeyCode.DIGIT2);

        CheckProgress c1 = progress("s1").check("c1");
        assertEquals(CheckProgress.Status.OPEN, c1.status(), "the check sits behind the peek card");
        assertEquals(0, c1.attempt());
    }

    @Test
    void approvingInTheHunkDiffWhatTheStepsAlreadyApprovedRecordsNoOverride() throws Exception {
        key(KeyCode.DIGIT2);
        key(KeyCode.A);
        key(KeyCode.DIGIT2);
        key(KeyCode.A);
        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision());
        assertEquals(StepProgress.Decision.PASSED, progress("s2").decision());

        key(KeyCode.V);
        focusDiffColumn();
        press(KeyCode.SHIFT).press(KeyCode.A).release(KeyCode.A).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(Map.of(), ReviewDiagFxThread.call(() ->
                        host.tours.forScope(scope.id()).orElseThrow().hunkOverrides()),
                "every hunk was already approved by its steps; nothing was decided in the hunk diff");
    }
}
