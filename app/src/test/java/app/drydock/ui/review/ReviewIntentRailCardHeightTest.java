package app.drydock.ui.review;

import app.drydock.review.ReviewIntent;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An intent card is a title, a rationale and a heat bar: three lines of
 * chrome, tens of pixels tall. Seven of them belong on screen together --
 * scanning the rail is how a reviewer decides where to go next.
 *
 * <p>They were {@code ~1100px} each. The card's graphic had its preferred
 * width bound to the card's own width, and a {@code Button} sizes itself
 * from its graphic -- so during the pass that decides the height the bound
 * width is still zero, both wrapping labels wrap at zero, and each one
 * reports the height of a column of single characters. One card then filled
 * the rail and the other six sat below the viewport, which reads exactly
 * like "this branch has one intent".</p>
 *
 * <p>Measured against the rendered node, not the row model: the model was
 * always right -- the rail's own header said {@code 0/7} over a single
 * card.</p>
 */
class ReviewIntentRailCardHeightTest extends ApplicationTest {

    /**
     * Generous by an order of magnitude. A real card renders at roughly 50-70px;
     * the bug produced 800-1200. Anything between is still a card whose height
     * came from wrapped-at-zero text, and the exact figure is a font metric
     * that must not be pinned down here.
     */
    private static final double SANE_CARD_HEIGHT = 220;

    private ReviewIntentRail rail;

    @Override
    public void start(Stage stage) {
        rail = new ReviewIntentRail();
        // The rail sits in a fixed-width slot in the real view, and its own
        // width is pinned; a StackPane the size of Review's left column is as
        // close to that as this test needs to be.
        StackPane root = new StackPane(rail);
        Scene scene = new Scene(root, 400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void everyCardIsCardSized() {
        showIntents(sevenIntents());

        List<Double> heights = cardHeights();
        assertEquals(7, heights.size(), "one card per intent");
        for (int i = 0; i < heights.size(); i++) {
            assertTrue(heights.get(i) > 0 && heights.get(i) < SANE_CARD_HEIGHT,
                    "card " + (i + 1) + " is " + Math.round(heights.get(i))
                            + "px tall; cards are tens of pixels, not hundreds: " + heights);
        }
    }

    /** The point of the rail is comparing intents, which needs them on screen at once. */
    @Test
    void sevenIntentsFitTheRailWithoutScrolling() {
        showIntents(sevenIntents());

        double total = cardHeights().stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(total < 900, "seven cards total " + Math.round(total)
                + "px and cannot be scanned in a 900px window");
    }

    /** A single intent must not claim the whole rail either. */
    @Test
    void oneIntentIsOneCardTall() {
        showIntents(List.of(intent(1, "ghostty", ReviewIntent.Kind.CHANGE,
                "third_party · +1 −1 · grouped by drydock, no reviewer has run")));

        double height = cardHeights().get(0);
        assertTrue(height < SANE_CARD_HEIGHT,
                "the only card is " + Math.round(height) + "px tall; " + diagCard());
    }

    // ---- helpers --------------------------------------------------------

    private void showIntents(List<ReviewIntent> intents) {
        interact(() -> rail.setIntents(intents, intents.get(0).id(), ReviewIntentRail.Empty.NONE));
        WaitForAsyncUtils.waitForFxEvents();
        // Heights are only real once a layout pass has run over the shown scene.
        interact(() -> rail.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String diagCard() {
        StringBuilder sb = new StringBuilder();
        interact(() -> lookup(".review-intent-card").queryAll().stream()
                .map(Button.class::cast)
                .findFirst()
                .ifPresent(card -> {
                    javafx.scene.layout.Region graphic = (javafx.scene.layout.Region) card.getGraphic();
                    sb.append("rail=").append((int) rail.getWidth())
                            .append(" card=").append((int) card.getWidth())
                            .append(" graphicW=").append((int) graphic.getWidth())
                            .append(" graphicPrefW=").append((int) graphic.getPrefWidth())
                            .append(" graphicH=").append((int) graphic.getHeight())
                            .append(" parts=");
                    for (javafx.scene.Node kid : graphic.getChildrenUnmodifiable()) {
                        sb.append(kid.getClass().getSimpleName()).append(':')
                                .append((int) kid.getBoundsInParent().getWidth()).append('x')
                                .append((int) kid.getBoundsInParent().getHeight()).append(' ');
                    }
                }));
        return sb.toString();
    }

    private List<Double> cardHeights() {
        List<Double> heights = new ArrayList<>();
        interact(() -> lookup(".review-intent-card").queryAll().stream()
                .map(Button.class::cast)
                .forEach(card -> heights.add(card.getBoundsInParent().getHeight())));
        return heights;
    }

    /** The fallback grouping of this branch, titles and rationales verbatim. */
    private static List<ReviewIntent> sevenIntents() {
        return List.of(
                intent(1, "DrydockApplication.java", ReviewIntent.Kind.CHANGE,
                        "app/src/main/java/app/drydock · +2 −0 · grouped by drydock, no reviewer has run"),
                intent(2, "drydock/review · 4 files", ReviewIntent.Kind.CHANGE,
                        "app/src/main/java/app/drydock/review · +412 −38 · grouped by drydock,"
                                + " no reviewer has run"),
                intent(3, "ui/review · 6 files", ReviewIntent.Kind.CHANGE,
                        "app/src/main/java/app/drydock/ui/review · +508 −44 · grouped by drydock,"
                                + " no reviewer has run"),
                intent(4, "MainWorkspace.java", ReviewIntent.Kind.CHANGE,
                        "app/src/main/java/app/drydock/ui · +31 −2 · grouped by drydock,"
                                + " no reviewer has run"),
                intent(5, "ui/review · 7 files", ReviewIntent.Kind.TESTS,
                        "app/src/test/java/app/drydock/ui/review · +742 −18 · grouped by drydock,"
                                + " no reviewer has run"),
                intent(6, "drydock/review · 2 files", ReviewIntent.Kind.TESTS,
                        "app/src/test/java/app/drydock/review · +268 −0 · grouped by drydock,"
                                + " no reviewer has run"),
                intent(7, "app.css", ReviewIntent.Kind.CONFIG,
                        "app/src/main/resources/app/drydock/ui · +52 −1 · grouped by drydock,"
                                + " no reviewer has run"));
    }

    private static ReviewIntent intent(int number, String title, ReviewIntent.Kind kind,
            String rationale) {
        return new ReviewIntent("auto:" + number, number, title, kind, ReviewIntent.Risk.LOW,
                rationale, List.of(), Optional.empty(), false);
    }
}
