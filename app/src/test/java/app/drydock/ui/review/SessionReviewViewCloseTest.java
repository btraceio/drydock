package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code close()}'s one new job (coordinator's review): detaching {@link
 * SessionReviewView}'s focus-owner listener from the Scene. The Scene is
 * app-lifetime ({@code AppShell} builds one for the whole application), so a
 * listener left on it after this view is done keeps the WHOLE view --
 * diff column included -- strongly reachable for the process's life, and
 * re-renders its verdict bar on every focus change anywhere in the app, for
 * every session ever closed.
 *
 * <p>Kept in its own class with its own Stage, rather than folded into
 * {@code ReviewSettleActionsTest}'s shared fixture: calling {@code close()}
 * permanently detaches the listener from that Scene for the rest of the
 * class's lifetime, and JUnit does not guarantee test order within a class
 * -- doing it against a SHARED view would intermittently break every other
 * test in that class depending on which happened to run first.</p>
 */
class SessionReviewViewCloseTest extends ApplicationTest {

    private DiffService diffService;
    private FakeReviewHost host;
    private SessionReviewView view;

    @Override
    public void start(Stage stage) throws IOException {
        diffService = new DiffService();
        host = new FakeReviewHost(Files.createTempDirectory("drydock-close")
                .resolve("annotations.json"));
        host.diff = new UnifiedDiff(List.of(file("src/a.java", "void foo();")));
        ReviewScopeRegistry registry = new ReviewScopeRegistry();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        host.intents.set(scope.id(), List.of(
                new ReviewIntent("section-1", 0, "A", ReviewIntent.Kind.CHANGE,
                        ReviewIntent.Risk.MED, "", List.of(ReviewIntent.hunkId("src/a.java", 0)),
                        Optional.empty(), false)));
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    /**
     * Verified indirectly, since {@code ObservableValue} exposes no way to
     * count or inspect its listeners: after {@code close()}, a later real
     * focus change into the diff column must no longer move the Approve
     * button's label off "(section)".
     */
    @Test
    void closeStopsTheBarFromReactingToLaterFocusChanges() {
        clickOn(".review-intent-card");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Approve (section)", approveButtonText());

        interact(view::close);
        clickOn(".review-diff-cell");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("Approve (section)", approveButtonText(),
                "close() must detach the focus listener so a later focus change no longer "
                        + "re-renders this view's verdict bar");
    }

    private String approveButtonText() {
        String[] text = new String[1];
        interact(() -> text[0] = lookup(".review-verdict-action").queryAll().stream()
                .map(Button.class::cast)
                .map(Button::getText)
                .filter(t -> t.startsWith("Approve ("))
                .findFirst()
                .orElse("<no approve button found>"));
        return text[0];
    }

    private static UnifiedDiff.FileDiff file(String path, String text) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                                OptionalInt.of(1), text)))));
    }
}
