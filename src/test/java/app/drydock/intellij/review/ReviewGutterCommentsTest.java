package app.drydock.intellij.review;

import app.drydock.review.DiffSelection;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ReviewGutterComments#clickTarget}: a "+" comments on the selection it is in, else on its own line. */
class ReviewGutterCommentsTest {

    private static final ReviewSelection.Target LINE_11 =
            new ReviewSelection.Target("A.java", DiffSelection.Side.NEW, 11, 11);

    @Test
    void insideTheSelectionTheClickCommentsOnTheSelection() {
        ReviewSelection.Target selection = new ReviewSelection.Target("A.java", DiffSelection.Side.NEW, 10, 12);
        assertEquals(selection, ReviewGutterComments.clickTarget(LINE_11, Optional.of(selection)));
    }

    @Test
    void outsideTheSelectionOrOnTheOtherSideTheClickCommentsOnItsLine() {
        assertEquals(LINE_11, ReviewGutterComments.clickTarget(LINE_11,
                Optional.of(new ReviewSelection.Target("A.java", DiffSelection.Side.NEW, 20, 22))));
        assertEquals(LINE_11, ReviewGutterComments.clickTarget(LINE_11,
                Optional.of(new ReviewSelection.Target("A.java", DiffSelection.Side.OLD, 10, 12))));
        assertEquals(LINE_11, ReviewGutterComments.clickTarget(LINE_11, Optional.empty()));
    }
}
