package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session Review board driven through the same headless JavaFX harness
 * {@link ReviewDestinationViewTest} uses (Monocle + TestFX; see the Monocle
 * block in {@code app/build.gradle.kts}) and against the same {@link
 * FakeReviewHost}, so the two views are held to the same store.
 *
 * <p>What is under test here is the part the destination never had: exactly
 * one scope on screen, chosen by a two-chip switcher.</p>
 */
class SessionReviewViewTest extends ApplicationTest {

    /** Wide enough that the responsive layout leaves both rails expanded. */
    private static final double SCENE_WIDTH = 1400;

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();

    private FakeReviewHost host;
    private Stage stage;

    /**
     * The Stage's size as this class found it. TestFX hands every class in the
     * JVM the same primary Stage, so a class that resizes it -- and the layout
     * test below must -- leaves every later class measuring ITS window. {@code
     * ReviewDiffColumnWidthTest} sizes only its Scene and so inherits whatever
     * width it is given; put back what was here rather than making it someone
     * else's problem.
     */
    private double incomingStageWidth = Double.NaN;
    private double incomingStageHeight = Double.NaN;
    private Scene scene;
    private ReviewScope localScope;
    private ReviewScope prScope;

    @AfterEach
    void tearDown() {
        if (stage != null && Double.isFinite(incomingStageWidth) && incomingStageWidth > 0) {
            interact(() -> {
                stage.setWidth(incomingStageWidth);
                stage.setHeight(incomingStageHeight);
            });
        }
        diffService.close();
        host.store.close();
    }

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-session-review")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        localScope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/feature")),
                "master", "feature/x", Optional.empty(), Optional.empty()));
        prScope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, Path.of("/repo"), Optional.of(Path.of("/wt/feature")),
                "master", "feature/x",
                Optional.of(new ReviewScope.PullRequestRef(42, Optional.empty())),
                Optional.empty()));
        scene = new Scene(new BorderPane(), SCENE_WIDTH, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        this.stage = stage;
        stage.setScene(scene);
        incomingStageWidth = stage.getWidth();
        incomingStageHeight = stage.getHeight();
        // Explicit, because the harness reuses one Stage across the class: a
        // stage a previous test left narrow keeps that width when this scene
        // is installed, and every width assertion below would then be
        // measuring the previous test's window.
        stage.setWidth(SCENE_WIDTH);
        stage.setHeight(900);
        stage.show();
    }

    @Test
    void aScopeWithNoPullRequestShowsOneChip() {
        SessionReviewView view = newView();
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL);

        assertEquals(1, view.diagChipTexts().size());
        assertEquals("Local changes", view.diagChipTexts().get(0));
    }

    @Test
    void aScopeWithAPullRequestShowsBothChipsAndHonoursTheChoice() {
        SessionReviewView view = newView();
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.of(prScope)),
                SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(List.of("Local changes", "PR #42"), view.diagChipTexts());
        assertEquals(prScope.id(), view.selectedScope().orElseThrow().id());
    }

    @Test
    void switchingChipsChangesTheSelectedScope() {
        SessionReviewView view = newView();
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.of(prScope)),
                SessionReviewScopes.Choice.LOCAL);

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(prScope.id(), view.selectedScope().orElseThrow().id());
        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, view.selectedChoice());
        assertEquals(Optional.of("PR #42"), view.diagSelectedChipText(),
                "the chip the human pressed has to be the one left looking selected");
    }

    @Test
    void askingForAPullRequestChoiceWithNoPullRequestFallsBackToLocal() {
        SessionReviewView view = newView();
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.empty()),
                SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(localScope.id(), view.selectedScope().orElseThrow().id());
        assertEquals(SessionReviewScopes.Choice.LOCAL, view.selectedChoice());
    }

    @Test
    void theViewNeverHoldsTheWindowOpen() {
        assertEquals(0.0, newView().minWidth(-1));
    }

    /**
     * The two placeholder states have no scope at all, which every derived
     * read has to survive: an unavailable session Review must not leave the
     * previous scope's board on screen.
     */
    @Test
    void resolvingAndUnavailableShowNoScope() {
        SessionReviewView view = newView();
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL);

        interact(view::showResolving);
        assertTrue(view.selectedScope().isEmpty(), "a resolving board has no scope yet");
        assertTrue(view.diagChipTexts().isEmpty(), "chips describe scopes that exist");

        interact(() -> view.showUnavailable("no git here"));
        assertTrue(view.selectedScope().isEmpty(), "an unavailable board has no scope");
    }

    /**
     * Pressing the chip that is already selected must change nothing -- and
     * above all must not leave the switcher with NO chip selected, which is
     * what a {@code ToggleButton} does here: its {@code fire()} toggles with
     * only a disabled guard, so a two-chip group can be clicked into no scope
     * at all. The chips are {@code RadioButton}s for that guarantee (see
     * {@code NewWorktreeModal}), and this presses through {@code fire()} --
     * the whole of what a click does -- rather than {@code setSelected}, which
     * would be a no-op here and so could never catch the defect.
     */
    @Test
    void pressingTheSelectedChipAgainChangesNothing() {
        SessionReviewView view = newView();
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.of(prScope)),
                SessionReviewScopes.Choice.PULL_REQUEST);
        int[] changes = new int[1];
        interact(() -> view.setOnChoiceChanged(choice -> changes[0]++));

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(0, changes[0], "the already-selected chip must not fire a change");
        assertEquals(prScope.id(), view.selectedScope().orElseThrow().id());
        assertEquals(Optional.of("PR #42"), view.diagSelectedChipText(),
                "pressing the selected chip must never deselect it into no scope at all");
    }

    /**
     * Step 7.3: switching chips must not re-run git for a diff already
     * resolved. Both scopes are seeded with a resolved diff and nothing else;
     * a re-scope would publish {@code Diffing} over the cached outcome the
     * instant the chip flips, blanking the file count -- so the count staying
     * put across a round trip is what proves the cache was used rather than
     * merely kept.
     */
    @Test
    void switchingBackToACachedScopeDoesNotRunGitAgain() {
        SessionReviewView view = newView();
        interact(() -> {
            view.diagPublishOutcome(localScope.id(),
                    new DiffOutcome.Loaded(diffOf("src/A.java", "src/B.java")));
            view.diagPublishOutcome(prScope.id(), new DiffOutcome.Loaded(diffOf("src/C.java")));
        });
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.of(prScope)),
                SessionReviewScopes.Choice.LOCAL);
        assertEquals("2 files", countsText());

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        assertEquals("1 file", countsText(), "the PR chip shows the PR's cached diff");

        view.diagSelectChoice(SessionReviewScopes.Choice.LOCAL);
        assertEquals("2 files", countsText(), "coming back must not re-run git");

        // And it must still be there once every queued FX event has run: a
        // git call would have failed asynchronously on a path that does not
        // exist and replaced the count with nothing.
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("2 files", countsText());
    }

    /**
     * The one member the brief specified as rewritten rather than moved.
     * With the queue gone there are three columns, and the trade order is the
     * solver's: narrow both rails, then collapse the margin, then the intent
     * rail -- the code column is the last thing to give up width, because it
     * is the only thing anyone opened Review to read.
     */
    @Test
    void railsGiveUpWidthMarginFirstThenTheIntentRail() {
        SessionReviewView view = newView();
        interact(() -> view.diagPublishOutcome(localScope.id(),
                new DiffOutcome.Loaded(diffOf("src/A.java"))));
        showScopes(view, new SessionReviewScopes.Scopes(localScope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL);

        // 1150px is the width that proves this is a THREE-column solve: the
        // intent rail and the margin want 568px, leaving 582px of code, so
        // both stay expanded -- while charging this view for the departed
        // queue rail's 44px too would already have narrowed them here.
        resizeTo(1150);
        assertEquals(ReviewIntentRail.EXPANDED_WIDTH, settledWidth(".review-intent-rail"), 1.0);
        assertEquals(ReviewFindingsMargin.EXPANDED_WIDTH, settledWidth(".review-findings-margin"), 1.0);

        resizeTo(1100);
        assertEquals(ReviewIntentRail.NARROW_WIDTH, settledWidth(".review-intent-rail"), 1.0,
                "narrowing comes before any collapse");
        assertEquals(ReviewFindingsMargin.NARROW_WIDTH, settledWidth(".review-findings-margin"), 1.0);

        resizeTo(900);
        assertEquals(ReviewFindingsMargin.COLLAPSED_WIDTH, settledWidth(".review-findings-margin"), 1.0,
                "the margin is the first rail to be collapsed");
        assertEquals(ReviewIntentRail.NARROW_WIDTH, settledWidth(".review-intent-rail"), 1.0,
                "the intent rail must still be readable while the margin can pay");

        resizeTo(700);
        assertEquals(ReviewIntentRail.COLLAPSED_WIDTH, settledWidth(".review-intent-rail"), 1.0,
                "with nothing else left to trade the intent rail collapses too");
        assertEquals(ReviewFindingsMargin.COLLAPSED_WIDTH, settledWidth(".review-findings-margin"), 1.0);
    }

    // ---- fixtures -----------------------------------------------------------

    /** A fresh view, built and installed on the FX thread as the app builds it. */
    private SessionReviewView newView() {
        SessionReviewView[] created = new SessionReviewView[1];
        interact(() -> {
            created[0] = new SessionReviewView(host, diffService, null);
            scene.setRoot(created[0]);
        });
        return created[0];
    }

    private void showScopes(SessionReviewView view, SessionReviewScopes.Scopes scopes,
                            SessionReviewScopes.Choice choice) {
        interact(() -> view.showScopes(scopes, choice));
    }

    /** A synthetic diff of {@code paths}, one one-line hunk each. */
    private static UnifiedDiff diffOf(String... paths) {
        return new UnifiedDiff(Arrays.stream(paths)
                .map(path -> new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                        List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                                UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                                OptionalInt.of(1), "x"))))))
                .toList());
    }

    /** What the top bar says about the board -- the loaded diff's file count. */
    private String countsText() {
        String[] text = new String[1];
        interact(() -> text[0] = ((Label) lookup(".review-title-counts").query()).getText());
        return text[0];
    }

    private void resizeTo(double width) {
        interact(() -> stage.setWidth(width));
        settledWidth(".review-findings-margin");
    }

    /** Polls until the (possibly animating) width stops moving; see {@code ReviewNarrowLayoutTest}. */
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

    private double railWidth(String selector) {
        double[] width = new double[1];
        interact(() -> width[0] = lookup(selector).queryAll().stream()
                .findFirst()
                .map(Region.class::cast)
                .map(Region::getWidth)
                .orElse(0.0));
        return width[0];
    }
}
