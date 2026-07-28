package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Review destination driven end to end through the headless JavaFX
 * harness (Monocle + TestFX; see the Monocle block in
 * {@code app/build.gradle.kts}): real key events against a real Stage with
 * the real stylesheets applied.
 *
 * <p>Covers the part of the keyboard table (spec §5) that M1 binds, and the
 * hard rule that every interactive element is focus-traversable -- neither
 * is reachable from a pure unit test, and both are exactly what regresses
 * silently.</p>
 */
class ReviewDestinationViewTest extends ApplicationTest {

    /** Wider than the 1320px narrow threshold, so the rails start expanded. */
    private static final double SCENE_WIDTH = 1400;

    private final List<ManagedSessionId> openedSessions = new ArrayList<>();
    private ReviewDestinationView view;

    @Override
    public void start(Stage stage) {
        view = new ReviewDestinationView(new RecordingHost());
        Scene scene = new Scene(view, SCENE_WIDTH, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private final class RecordingHost implements ReviewDestinationView.Host {
        @Override
        public void refreshQueue() { }

        @Override
        public void openSession(ManagedSessionId sessionId) {
            openedSessions.add(sessionId);
        }

        @Override
        public Optional<Region> bodyFor(ReviewScope scope) {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> openFindings(ReviewScope scope) {
            return scope.head().equals("feat/b") ? Optional.of(3) : Optional.empty();
        }

        @Override
        public Optional<String> sessionState(ReviewScope scope) {
            return scope.sessionId().map(id -> "running");
        }

        @Override
        public void showShortcuts() { }
    }

    @Test
    void jAndKWalkTheQueueAndClampAtBothEnds() {
        seedQueue();

        assertEquals("feat/a", selectedTitle());
        type(KeyCode.J);
        assertEquals("feat/b", selectedTitle());
        type(KeyCode.J);
        assertEquals("feat/c", selectedTitle());
        type(KeyCode.J);
        assertEquals("feat/c", selectedTitle(), "j must clamp at the last item, never wrap round");
        type(KeyCode.K);
        assertEquals("feat/b", selectedTitle());
        type(KeyCode.K);
        type(KeyCode.K);
        assertEquals("feat/a", selectedTitle(), "k must clamp at the first item");
    }

    @Test
    void qCollapsesAndReExpandsTheQueueRail() {
        seedQueue();
        double expanded = railWidth();
        assertTrue(expanded > ReviewQueueRail.COLLAPSED_WIDTH, "expected an expanded rail, got " + expanded);

        type(KeyCode.Q);
        assertEquals(ReviewQueueRail.COLLAPSED_WIDTH, settledRailWidth());

        type(KeyCode.Q);
        assertEquals(expanded, settledRailWidth());
    }

    /** Focus mode is a toggle, not a one-way collapse -- {@code f} twice must return the rails. */
    @Test
    void fTogglesFocusMode() {
        seedQueue();
        double expanded = railWidth();

        type(KeyCode.F);
        assertEquals(ReviewQueueRail.COLLAPSED_WIDTH, settledRailWidth());

        type(KeyCode.F);
        assertEquals(expanded, settledRailWidth());
    }

    @Test
    void oOpensTheBoundSessionAndDoesNothingWithoutOne() {
        ManagedSessionId session = ManagedSessionId.newId();
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        interact(() -> view.setItems(List.of(
                item(registry, "feat/a", Optional.empty()),
                item(registry, "feat/b", Optional.of(session))), 1));

        type(KeyCode.O);
        assertTrue(openedSessions.isEmpty(), "an item with no session must not open one");

        type(KeyCode.J);
        type(KeyCode.O);
        assertEquals(List.of(session), openedSessions);
    }

    /**
     * The handoff's hard rule: no {@code Label} + mouse handler. Every queue
     * row, and the rail's own collapse control, has to be reachable by
     * keyboard -- the prototype having zero focusable controls was a defect,
     * not a style.
     */
    @Test
    void everyQueueRowIsFocusTraversable() {
        seedQueue();

        List<Node> rows = new ArrayList<>(lookup(".review-queue-item").queryAll());
        assertEquals(3, rows.size());
        for (Node row : rows) {
            assertTrue(row instanceof Button, "queue rows must be real Buttons, got " + row.getClass());
            assertTrue(row.isFocusTraversable(), "queue row is not focus-traversable: " + row);
        }
        assertTrue(lookup(".review-rail-header").query().isFocusTraversable(),
                "the rail's collapse control is not focus-traversable");
    }

    /**
     * The count is derived, never stored: an item whose reviewer has never
     * run shows no badge at all, rather than a confident zero that would
     * read as "reviewed, nothing found".
     */
    @Test
    void onlyItemsWithFindingsCarryACountBadge() {
        seedQueue();

        List<String> badges = lookup(".review-queue-count").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
        assertEquals(List.of("3"), badges);
    }

    @Test
    void anEmptyQueueShowsTheZeroStateInsteadOfCrashing() {
        interact(() -> view.setItems(List.of(), 0));

        assertTrue(lookup(".review-queue-item").queryAll().isEmpty());
        assertFalse(lookup(".review-placeholder-title").queryAll().isEmpty());
        assertEquals("Nothing to review", ((Label) lookup(".review-placeholder-title").query()).getText());
        // Keys against an empty queue must be inert, not throw.
        type(KeyCode.J);
        type(KeyCode.K);
        type(KeyCode.O);
    }

    @Test
    void aPullRequestWithNoCheckoutShowsTheCheckoutGate() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, Path.of("/repo"), Optional.empty(), "main", "feat/gateway",
                Optional.of(new ReviewScope.PullRequestRef(412, Optional.empty())), Optional.empty()));
        interact(() -> view.setItems(
                List.of(new ReviewItem(scope, ReviewItem.Group.REQUESTED, "PR #412 feat/gateway",
                        "drydock · not checked out")), 1));

        assertEquals("PR #412 has no session yet",
                ((Label) lookup(".review-placeholder-title").query()).getText());
        assertEquals("gh pr checkout 412 --worktree",
                ((Label) lookup(".review-placeholder-mono").query()).getText());
    }

    // ---- fixtures -----------------------------------------------------------

    private void seedQueue() {
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        interact(() -> view.setItems(List.of(
                item(registry, "feat/a", Optional.empty()),
                item(registry, "feat/b", Optional.empty()),
                item(registry, "feat/c", Optional.empty())), 1));
    }

    private static ReviewItem item(ReviewScopeRegistry registry, String head,
                                   Optional<ManagedSessionId> session) {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/" + head)),
                "master", head, Optional.empty(), session));
        return new ReviewItem(scope, ReviewItem.Group.MINE, head, "drydock · vs master");
    }

    private void type(KeyCode key) {
        interact(view::requestFocus);
        press(key).release(key);
    }

    private double railWidth() {
        return ((Region) lookup(".review-queue-rail").query()).getWidth();
    }

    /**
     * The rail's collapse is a 160ms width {@link javafx.animation.Timeline},
     * so its width has to be read after the animation, not after the key
     * press. Polls rather than sleeping a fixed interval: a fixed wait is
     * either flaky or slow, and this settles as soon as the width stops
     * moving.
     */
    private double settledRailWidth() {
        double previous = Double.NaN;
        for (int i = 0; i < 60; i++) {
            sleep(20);
            double current = readRailWidth();
            if (current == previous) {
                return current;
            }
            previous = current;
        }
        return previous;
    }

    private double readRailWidth() {
        double[] width = new double[1];
        interact(() -> width[0] = railWidth());
        return width[0];
    }

    /** The title of the row carrying the {@code :selected} pseudo-class. */
    private String selectedTitle() {
        return lookup(".review-queue-item").queryAll().stream()
                .filter(node -> node.getPseudoClassStates().stream()
                        .anyMatch(state -> state.getPseudoClassName().equals("selected")))
                .findFirst()
                .map(node -> (Parent) ((Button) node).getGraphic())
                .flatMap(graphic -> graphic.lookupAll(".review-queue-title").stream().findFirst())
                .map(label -> ((Label) label).getText())
                .orElse(null);
    }
}
