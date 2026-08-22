package app.drydock.ui.review;

import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewIntent;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PATH row is a {@code Button} whose text lives on child {@link Label}s
 * (badge, file, reason, links), styled by {@code .review-path-badge}/
 * {@code -file}/{@code -reason}/{@code -links} in {@code app.css} -- rebuilt
 * that way specifically because {@code Button.setText} alone has no {@code
 * -fx-text-fill} of its own here, and modena's default button-face text
 * colour measured 1.13:1 contrast on a SELECTED row in a real screenshot
 * (worse than the 1.70:1 unselected rows still failed at, because the
 * lighter {@code :selected} background made a light-on-light problem
 * worse).
 *
 * <p>Rather than hard-coding hex values from {@code theme-dark.css} (which
 * would silently stop meaning anything the day the palette changes), this
 * pins PATH rows against the ALREADY-SHIPPED reference this task deliberately
 * reused: an intents card's own {@code .review-intent-title}/{@code -number}
 * resolve to identical colours, selected and unselected both, because
 * {@code app.css} gives {@code .review-path-file}/{@code -badge} the exact
 * same tokens. A regression back to {@code Button.setText} (no fill at all,
 * so {@link Label#getTextFill()} would come back as modena's default rather
 * than matching) or a copy-paste of the wrong token both fail this.</p>
 */
class ReviewPathRowContrastTest extends ApplicationTest {

    private ReviewIntentRail rail;

    @Override
    public void start(Stage stage) {
        rail = new ReviewIntentRail();
        StackPane root = new StackPane(rail);
        Scene scene = new Scene(root, 400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void aSelectedPathRowsFileNameMatchesAnIntentCardsSelectedTitle() {
        Color intentTitleSelected = titleFill(true);
        Color intentTitleUnselected = titleFill(false);
        Color pathFileSelected = fileFill(true);
        Color pathFileUnselected = fileFill(false);

        assertEquals(intentTitleSelected, pathFileSelected,
                "a selected PATH row's file name must be exactly as legible as a selected intent "
                        + "card's title -- both are meant to use -drydock-text");
        assertEquals(intentTitleUnselected, pathFileUnselected,
                "an unselected PATH row's file name must match an unselected intent card's title");
        assertNotEquals(pathFileUnselected, pathFileSelected,
                "selecting a row must actually change its text colour, not just its background");
    }

    @Test
    void theSelectedRowIsNeverTheHardestToRead() {
        // The measured defect, restated as an assertion: a screenshot found
        // the SELECTED row's own contrast (1.13:1) BELOW the unselected
        // rows' (1.70:1) -- selecting made it worse, not better. Luminance
        // is a monotonic stand-in for contrast against the same dark
        // background both rows sit on, so "selected is at least as bright"
        // is the same claim as "selected is at least as legible".
        double unselected = relativeLuminance(fileFill(false));
        double selected = relativeLuminance(fileFill(true));

        assertTrue(selected >= unselected,
                "selected file text (luminance " + selected + ") must not be DARKER than "
                        + "unselected (" + unselected + ") -- that is exactly the regression a "
                        + "real screenshot caught");
        // And both must clear a floor that is trivially true for the
        // reused -drydock-text/-drydock-text-dim tokens, but would catch a
        // return to an unstyled Button's near-black default.
        assertTrue(selected > 0.3, "selected text is too dark to read: luminance " + selected);
    }

    // ---- helpers --------------------------------------------------------------

    private Color titleFill(boolean selected) {
        List<ReviewIntent> intents = List.of(
                new ReviewIntent("a", 1, "alpha", ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.LOW,
                        "", List.of(), Optional.empty(), false),
                new ReviewIntent("b", 2, "beta", ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.LOW,
                        "", List.of(), Optional.empty(), false));
        // "a" is always selected; asking for the UNselected fill reads "b"'s
        // card instead, so both renders always have exactly one of each.
        interact(() -> rail.setIntents(intents, "a", ReviewIntentRail.Empty.NONE));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> rail.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();
        return labelFill(".review-intent-title", selected);
    }

    private Color fileFill(boolean selected) {
        ReadingPath.Step first = new ReadingPath.Step("h_a_0", "src/a.txt", 1, "builds on nothing",
                List.of(), true);
        ReadingPath.Step second = new ReadingPath.Step("h_b_0", "src/b.txt", 2, "builds on nothing",
                List.of(), false);
        interact(() -> rail.showPath(List.of(first, second), "h_a_0", ReviewIntentRail.Empty.NONE));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> rail.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();
        return labelFill(".review-path-file", selected);
    }

    /** The matching Label's resolved text fill, from whichever of the two rendered cards is (un)selected. */
    private Color labelFill(String styleClass, boolean selected) {
        Color[] found = new Color[1];
        interact(() -> lookup(styleClass).queryAll().stream()
                .map(Node.class::cast)
                .filter(node -> node instanceof Label)
                .map(Label.class::cast)
                .filter(label -> isSelected(label) == selected)
                .findFirst()
                .ifPresentOrElse(label -> found[0] = (Color) label.getTextFill(),
                        () -> {
                            throw new AssertionError("no " + (selected ? "selected" : "unselected")
                                    + " " + styleClass + " found");
                        }));
        return found[0];
    }

    /** Walks up from a row's Label to the Button card and reads its own :selected pseudo-class. */
    private static boolean isSelected(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof Button button && button.getStyleClass().contains("review-intent-card")) {
                return button.getPseudoClassStates().stream()
                        .anyMatch(pc -> pc.getPseudoClassName().equals("selected"));
            }
        }
        return false;
    }

    /** WCAG relative luminance (sRGB), so "brighter" has a single number to compare. */
    private static double relativeLuminance(Color color) {
        return 0.2126 * linearize(color.getRed())
                + 0.7152 * linearize(color.getGreen())
                + 0.0722 * linearize(color.getBlue());
    }

    private static double linearize(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
