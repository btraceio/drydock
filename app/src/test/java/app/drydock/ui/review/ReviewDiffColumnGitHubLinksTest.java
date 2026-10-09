package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hunk headers' GitHub quick links: github.com and github.dev, on the
 * FIRST header of a file only, aimed at the hunk's first line, with the
 * "no github.com remote here" answer arriving as a disabled button + tooltip
 * -- the honesty {@code ⤢ Explorer} already renders.
 */
class ReviewDiffColumnGitHubLinksTest extends FxTest {

    /** What the link host was asked, when it was asked. */
    private record Ask(ReviewScope scope, String file, int line, boolean vscode) { }

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;
    private final List<Ask> asked = new ArrayList<>();
    /** Whether the fake link host answers "openable" (a github.com remote exists). */
    private boolean openable = true;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        column.setGitHubLinks((scope, file, line, vscode) -> {
            asked.add(new Ask(scope, file, line, vscode));
            return openable;
        });
        Scene scene = new Scene(column, 900, 700);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    private void show() {
        interact(() -> column.showDiff(scope(), twoHunks("src/Widget.java")));
        FxSync.waitForFxEvents();
    }

    private ReviewScope scope() {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of(System.getProperty("java.io.tmpdir")),
                Optional.of(Path.of(System.getProperty("java.io.tmpdir"))), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    private static UnifiedDiff twoHunks(String path) {
        List<UnifiedDiff.Line> first = List.of(
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(5),
                        "    int added = 1;"));
        List<UnifiedDiff.Line> second = List.of(
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(40),
                        "    int later = 2;"));
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff(path, "M", 2, 2, false, false, List.of(
                new UnifiedDiff.Hunk("@@", first), new UnifiedDiff.Hunk("@@", second)))));
    }

    @Test
    void theFirstHunkHeaderCarriesBothLinksAndRoutesFileAndLine() {
        show();

        javafx.scene.control.Button github = firstHeaderButton(".review-hunk-github", "GitHub");
        javafx.scene.control.Button vscode = firstHeaderButton(".review-hunk-github", "vscode");
        assertTrue(github != null && vscode != null, "both buttons render on the first header");

        interact(github::fire);
        interact(vscode::fire);
        FxSync.waitForFxEvents();

        assertEquals(2, asked.size());
        assertEquals("src/Widget.java", asked.get(0).file());
        assertEquals(5, asked.get(0).line(), "the hunk's own first line, not 1");
        assertFalse(asked.get(0).vscode());
        assertTrue(asked.get(1).vscode());
    }

    @Test
    void aLaterHunkHeaderCarriesNoRepeatLinks() {
        show();

        long linkButtons = column.lookupAll(".review-hunk-github").size();
        assertEquals(2, linkButtons, "one file, one GitHub + one vscode button, both on hunk 0");
    }

    @Test
    void aRefusedOpenDisablesTheButtonAndSaysWhy() {
        openable = false;
        show();

        javafx.scene.control.Button github = firstHeaderButton(".review-hunk-github", "GitHub");
        interact(github::fire);
        FxSync.waitForFxEvents();

        assertTrue(github.isDisabled(), "no github.com remote, no button that pretends otherwise");
        assertTrue(github.getTooltip().getText().contains("no github.com remote"), "the tooltip says why");
    }

    /** The first hunk header's link button with {@code text}, or null if none rendered. */
    private javafx.scene.control.Button firstHeaderButton(String styleClass, String text) {
        javafx.scene.control.Button[] found = {null};
        interact(() -> column.lookupAll(styleClass).stream()
                .filter(node -> node instanceof javafx.scene.control.Button b && b.getText().equals(text))
                .findFirst()
                .ifPresent(node -> found[0] = (javafx.scene.control.Button) node));
        return found[0];
    }
}