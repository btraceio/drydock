package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
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
    private Scene scene;
    private ReviewScope localScope;
    private ReviewScope prScope;

    @AfterEach
    void tearDown() {
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
        stage.setScene(scene);
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
     * Re-selecting the chip that is already selected must not re-render the
     * board: {@code ToggleButton} has no selection guard of its own (AGENTS.md),
     * and without one the diff column would be told to re-scope on every click.
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
}
