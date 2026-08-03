package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The narrow-width drill-in (spec §4.9), driven through the headless JavaFX
 * harness against a real Stage so the width listener runs for real.
 *
 * <p>What this pins down is the thing that was broken: below ~1180px the
 * rails auto-collapsed to 44/40px slivers that no key could expand, so the
 * whole tab became unusable. The fix is two alternating full-width pages
 * below 980px, and every assertion here is about a property of that -- no
 * sliver anywhere, both directions of the transition, and state surviving
 * the round trip.</p>
 */
class ReviewNarrowLayoutTest extends ApplicationTest {

    /** Comfortably above the 980px drill-in threshold and the 1320px narrow band. */
    private static final double WIDE = 1400;

    /** The width the spec names: below the threshold, wide enough to be legible. */
    private static final double NARROW = 900;

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;
    private Stage stage;

    @Override
    public void start(Stage primaryStage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-review-narrow")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Two files, so the by-file intent fallback yields intents to drill into.
        host.diff = new UnifiedDiff(List.of(file("src/Main.java"), file("src/Other.java")));
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, WIDE, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        this.stage = primaryStage;
        primaryStage.setScene(scene);
        // Explicit, because the harness reuses one Stage across the class: a
        // stage another test left at 900px keeps that width when this test's
        // scene is installed, and every width assertion below would then be
        // measuring the previous test's window.
        primaryStage.setWidth(WIDE);
        primaryStage.setHeight(900);
        primaryStage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void aboveTheThresholdTheLayoutStaysThreeColumn() {
        seedQueue();

        assertEquals("wide", view.diagNarrowPage());
        assertTrue(railWidth(".review-queue-rail") > ReviewQueueRail.COLLAPSED_WIDTH);
        assertTrue(isShowing(".review-item-header"), "the item header belongs to the wide layout");
    }

    /**
     * The defect this whole feature exists for: at 900px nothing may render
     * as a sliver, and both rails have to be readable at once.
     */
    @Test
    void browseGivesBothRailsTheWholeWindowWithNoSliver() {
        seedQueue();
        resizeTo(NARROW);

        assertEquals("browse", view.diagNarrowPage());
        double queue = railWidth(".review-queue-rail");
        double intents = railWidth(".review-intent-rail");
        assertTrue(queue > ReviewQueueRail.COLLAPSED_WIDTH,
                "the queue rail is a " + queue + "px sliver");
        assertTrue(intents > ReviewIntentRail.COLLAPSED_WIDTH,
                "the intent rail is a " + intents + "px sliver");
        assertEquals(NARROW, queue + intents, 1.0,
                "the two rails together own the window on Browse");
        assertTrue(queue < intents, "the queue takes the smaller ~44% share");
        assertFalse(isShowing(".review-verdict-bar"),
                "Browse shows no verdict bar -- there is nothing to settle from the queue");
    }

    @Test
    void enterOpensDetailAndEscapeReturnsToBrowse() {
        seedQueue();
        resizeTo(NARROW);

        type(KeyCode.ENTER);
        assertEquals("detail", view.diagNarrowPage());
        assertFalse(isShowing(".review-queue-rail"),
                "Detail hides the rails outright rather than collapsing them");
        assertFalse(isShowing(".review-intent-rail"));
        assertTrue(isShowing(".review-verdict-bar"), "Detail keeps the verdict bar");
        assertTrue(isShowing(".review-item-header"));

        // Detail gives the window to the code, so the margin starts collapsed
        // -- and `m` has to genuinely toggle it back, not be a dead key.
        double collapsed = settledWidth(".review-findings-margin");
        type(KeyCode.M);
        assertTrue(settledWidth(".review-findings-margin") > collapsed,
                "m must expand the findings margin on the Detail page");
        type(KeyCode.M);
        assertEquals(collapsed, settledWidth(".review-findings-margin"),
                "and collapse it again");

        assertTrue(unwindOne(), "Esc from Detail is absorbed by the page, not by Review");
        assertEquals("browse", view.diagNarrowPage());
    }

    /**
     * Escape only leaves Review from Browse: a reader deep in a diff must not
     * lose the whole surface to one keystroke.
     */
    @Test
    void escapeFromBrowseFallsThroughSoReviewItselfCanClose() {
        seedQueue();
        resizeTo(NARROW);

        assertEquals("browse", view.diagNarrowPage());
        assertFalse(unwindOne(),
                "with nothing left to unwind, Esc must fall through to the origin tab");
    }

    /** The {@code ‹ queue} chip is the mouse's version of that same Escape. */
    @Test
    void theQueueBackChipReturnsToBrowse() {
        seedQueue();
        resizeTo(NARROW);
        type(KeyCode.ENTER);
        assertEquals("detail", view.diagNarrowPage());

        Button chip = lookup(".review-item-header .review-chip-button").queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> button.getText().contains("queue"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ‹ queue chip in the item header"));
        assertTrue(chip.isVisible());
        assertTrue(chip.isFocusTraversable(), "the back chip must be reachable by keyboard");
        interact(chip::fire);

        assertEquals("browse", view.diagNarrowPage());
    }

    /**
     * Spec §4.9(4): selecting an intent records Detail, so shrinking
     * mid-review lands on the diff being read rather than back at the queue.
     */
    @Test
    void clickingAnIntentWideRecordsDetailForTheNextShrink() {
        seedQueue();
        assertEquals("wide", view.diagNarrowPage());

        clickFirstIntentCard();

        resizeTo(NARROW);
        assertEquals("detail", view.diagNarrowPage(),
                "a shrink mid-review must land on the diff, not back at the queue");
    }

    /**
     * {@code [} and {@code ]} keep working on Browse (spec §4.9(1)), so they
     * must NOT drill in -- stepping through intents to see what is there is
     * exactly the thing Browse is for.
     */
    @Test
    void steppingThroughIntentsWithBracketsStaysOnBrowse() {
        seedQueue();
        resizeTo(NARROW);

        type(KeyCode.CLOSE_BRACKET);
        assertEquals("browse", view.diagNarrowPage());
        type(KeyCode.OPEN_BRACKET);
        assertEquals("browse", view.diagNarrowPage());
    }

    /**
     * The round trip in both directions: crossing 980px must restore the
     * three-column layout with exactly the collapse state the user chose,
     * and must not have quietly rewritten it while narrow.
     */
    @Test
    void crossingTheThresholdBothWaysRestoresTheUsersCollapseState() {
        seedQueue();
        type(KeyCode.Q);
        double collapsed = settledWidth(".review-queue-rail");
        assertEquals(ReviewQueueRail.COLLAPSED_WIDTH, collapsed);
        String selectedBefore = view.diagSelectedScopeId().orElseThrow();

        resizeTo(NARROW);
        assertEquals("browse", view.diagNarrowPage());
        assertTrue(railWidth(".review-queue-rail") > ReviewQueueRail.COLLAPSED_WIDTH,
                "Browse must be readable even if the user had collapsed the queue while wide");

        resizeTo(WIDE);
        assertEquals("wide", view.diagNarrowPage());
        assertEquals(ReviewQueueRail.COLLAPSED_WIDTH, settledWidth(".review-queue-rail"),
                "the user's own collapse must come back with the wide layout");
        assertEquals(Optional.of(selectedBefore), view.diagSelectedScopeId(),
                "the round trip must not lose the current item");
    }

    /**
     * A collapse key that appears to do nothing and then takes effect on the
     * next resize is worse than a dead key. While drilled in, {@code q},
     * {@code i} and {@code f} are inert -- and, critically, must not have
     * written the flag the wide layout reads back.
     */
    @Test
    void collapseKeysAreInertWhileDrilledInAndLeaveNoDelayedEffect() {
        seedQueue();
        double expandedWide = settledWidth(".review-queue-rail");
        resizeTo(NARROW);

        type(KeyCode.Q);
        type(KeyCode.I);
        type(KeyCode.F);
        assertTrue(railWidth(".review-queue-rail") > ReviewQueueRail.COLLAPSED_WIDTH,
                "the rails stay full-width pages while drilled in");

        resizeTo(WIDE);
        assertEquals(expandedWide, settledWidth(".review-queue-rail"),
                "a keystroke that did nothing narrow must not fire later when widened");
    }

    /** A zero width is the pre-layout state, not a narrow window. */
    @Test
    void anUnlaidOutViewDoesNotDrillIn() {
        ReviewDestinationView fresh = new ReviewDestinationView(host, diffService);
        assertEquals("wide", fresh.diagNarrowPage());
    }

    // ---- the ‹ back affordance (nav §3) -------------------------------------

    @Test
    void theBackAffordanceIsAbsentUntilAnOriginIsSet() {
        seedQueue();

        assertFalse(isShowing(".review-back-button"),
                "a back button with nowhere to go is worse than no back button");

        boolean[] went = new boolean[1];
        interact(() -> view.setBackTarget(Optional.of("feat/login"), () -> went[0] = true));

        Button back = (Button) lookup(".review-back-button").query();
        assertTrue(back.isVisible());
        assertTrue(back.getText().contains("feat/login"), "the ‹ must name where it goes: " + back.getText());
        assertTrue(back.isFocusTraversable());
        interact(back::fire);
        assertTrue(went[0]);

        interact(() -> view.setBackTarget(Optional.empty(), null));
        assertFalse(isShowing(".review-back-button"));
    }


    // ---- fixtures -----------------------------------------------------------

    /** The click gesture that drills in: an intent card is a real Button. */
    private void clickFirstIntentCard() {
        Button card = lookup(".review-intent-card").queryAll().stream()
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the intent rail rendered no cards"));
        interact(card::fire);
    }

    private void seedQueue() {
        interact(() -> view.setItems(List.of(item("feat/a"), item("feat/b")), 1));
    }

    private ReviewItem item(String head) {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/" + head)),
                "master", head, Optional.empty(), Optional.<ManagedSessionId>empty()));
        return new ReviewItem(scope, ReviewItem.Group.MINE, head, "drydock · vs master");
    }

    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false,
                List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                        UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "x")))));
    }

    /**
     * Resizes the window and lets the width listener and the layout pass that
     * follows it settle. The rails animate on collapse, so this waits for the
     * width to stop moving rather than for a fixed interval.
     */
    private void resizeTo(double width) {
        interact(() -> stage.setWidth(width));
        settledWidth(".review-queue-rail");
    }

    private boolean unwindOne() {
        boolean[] result = new boolean[1];
        interact(() -> result[0] = view.unwindOne());
        return result[0];
    }

    private void type(KeyCode key) {
        interact(view::requestFocus);
        press(key).release(key);
    }

    /** Whether a node with this selector exists AND is currently rendered. */
    private boolean isShowing(String selector) {
        return lookup(selector).queryAll().stream().anyMatch(node -> node.isVisible() && node.getScene() != null
                && node.getParent() != null);
    }

    private double railWidth(String selector) {
        double[] width = new double[1];
        interact(() -> width[0] = lookup(selector).queryAll().stream()
                .findFirst()
                .map(Region.class::cast)
                .map(Region::getWidth)
                .orElse(0.0));
        return width[0];
    }

    /** Polls until the (possibly animating) width stops moving; see the sibling test's note. */
    private double settledWidth(String selector) {
        double previous = Double.NaN;
        for (int i = 0; i < 60; i++) {
            sleep(20);
            double current = railWidth(selector);
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        return previous;
    }
}
