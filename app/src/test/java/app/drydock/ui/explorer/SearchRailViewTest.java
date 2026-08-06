package app.drydock.ui.explorer;

import app.drydock.search.SessionSearchService;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file rail's funnel through the headless harness: the scope segmented
 * control, the dimming (never hiding) of out-of-change files, and the footer
 * that is the way back out.
 */
class SearchRailViewTest extends ApplicationTest {

    private Path root;
    private SessionSearchService searchService;
    private SearchRail rail;

    /**
     * Built once for the whole class, not once per test. ApplicationTest runs
     * {@code start} for every test, and re-writing this tree each time (it is
     * deliberately larger than the rail's render cap) slowed the shared
     * JavaFX toolkit enough to time other UI test classes out. No test
     * mutates the tree, so one copy is safe.
     */
    private static Path sharedRoot;

    private static synchronized Path sharedRoot() throws IOException {
        if (sharedRoot == null) {
            sharedRoot = buildTree();
        }
        return sharedRoot;
    }

    @org.junit.jupiter.api.AfterAll
    static void deleteSharedRoot() throws IOException {
        if (sharedRoot != null && Files.exists(sharedRoot)) {
            try (var walk = Files.walk(sharedRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // A temp tree that outlives the run is not a failure.
                    }
                });
            }
            sharedRoot = null;
        }
    }

    private static Path buildTree() throws IOException {
        Path root = Files.createTempDirectory("drydock-rail");
        Files.createDirectories(root.resolve("ui"));
        Files.createDirectories(root.resolve("core"));
        // Many matches on one in-diff file, so querying it expands into a
        // tall list of match lines. That is the vertical pressure the footer
        // has to survive -- see the wrap test below.
        StringBuilder sidebar = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sidebar.append("int width").append(i).append("() { return clamp(raw); }\n");
        }
        Files.writeString(root.resolve("ui/Sidebar.java"), sidebar.toString());
        Files.writeString(root.resolve("ui/DragTracker.java"), "double raw() { return startW; }\n");
        Files.writeString(root.resolve("core/LayoutMath.java"), "double lerp(double a) { return clamp(a); }\n");
        // More untouched files than the rail will render at once, so repo
        // scope has something to hold back and has to say so.
        Files.createDirectories(root.resolve("bulk"));
        for (int i = 0; i < 250; i++) {
            Files.writeString(root.resolve("bulk/filler_" + i + ".java"), "class F" + i + " { }\n");
        }
        return root;
    }

    @Override
    public void start(Stage stage) {
        try {
            root = sharedRoot();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        searchService = new SessionSearchService();
        rail = new SearchRail(root, searchService, (file, relative, line, query) -> { });
        rail.setChangedLines(() -> Map.of(
                Path.of("ui/Sidebar.java"), Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
                Path.of("ui/DragTracker.java"), Set.of(1)));
        rail.setFindings(path -> path.endsWith("Sidebar.java")
                ? List.of(new ExplorerFinding(1, "leak"))
                : List.of());
        rail.setPrefWidth(324);
        // A wrapper root, so a test can size the RAIL itself. Resizing the
        // stage instead leaks across classes: TestFX shares one primary stage
        // for the whole JVM, and a stage left narrow-and-short broke a dozen
        // layout assertions over in the review package.
        javafx.scene.layout.StackPane wrapper = new javafx.scene.layout.StackPane(rail);
        Scene scene = new Scene(wrapper, 324, 800);
        scene.getStylesheets().addAll(
                SearchRail.class.getResource("/app/drydock/ui/theme-dark.css").toExternalForm(),
                SearchRail.class.getResource("/app/drydock/ui/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        WaitForAsyncUtils.waitForFxEvents();
    }

    @AfterEach
    void tearDown() {
        searchService.close();
    }

    private List<String> rowNames() {
        return lookup(".result-file-name").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private String footerText() {
        return ((Button) lookup(".rail-funnel-footer").query()).getText();
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void diffScopeShowsTheChangeAndTheFooterSizesTheRepo() {
        interact(() -> rail.refresh());
        settle();
        assertEquals(List.of("Sidebar.java", "DragTracker.java"), rowNames());
        assertTrue(footerText().startsWith("this change · 2 files — repo has"), footerText());
    }

    @Test
    void repoScopeDimsOutOfChangeFilesRatherThanHidingThem() {
        interact(() -> rail.toggleScope());
        settle();
        assertTrue(rowNames().contains("LayoutMath.java"), "the untouched file is listed: " + rowNames());

        Button untouched = lookup(".rail-file-row").queryAll().stream()
                .map(Button.class::cast)
                .filter(row -> row.getTooltip() != null && row.getTooltip().getText().contains("LayoutMath"))
                .findFirst().orElseThrow();
        assertTrue(untouched.getPseudoClassStates().stream()
                        .anyMatch(pseudo -> pseudo.getPseudoClassName().equals("out-of-change")),
                "…and it is dimmed rather than dropped");
    }

    @Test
    void theFooterIsTheWayOutOfADiffScopedContentSearch() {
        interact(() -> rail.setSearch("lerp"));
        // The content search runs git grep / a walk; wait for the rail to
        // report on it rather than sleeping.
        waitForFooter("more file");
        assertTrue(rowNames().isEmpty() || !rowNames().contains("LayoutMath.java"),
                "the match is outside the change, so diff scope does not show it");
        assertTrue(footerText().contains("— show"), footerText());

        clickOn(lookup(".rail-funnel-footer").queryButton());
        settle();
        assertTrue(rowNames().contains("LayoutMath.java"), "…and the footer switched to repo scope");
    }

    @Test
    void aFileWithFindingsCarriesItsChip() {
        interact(() -> rail.refresh());
        settle();
        Label chip = (Label) lookup(".rail-finding-chip").query();
        assertEquals("◆1", chip.getText());
    }

    @Test
    void sortCyclesAndSaysWhichOneIsOn() {
        Button sort = lookup(".rail-sort-button").queryButton();
        assertEquals("⇅ churn", sort.getText());
        clickOn(sort);
        settle();
        assertEquals("⇅ findings", sort.getText());
        clickOn(sort);
        settle();
        assertEquals("⇅ a-z", sort.getText());
    }

    @Test
    void everyRailControlIsFocusTraversable() {
        interact(() -> rail.refresh());
        settle();
        assertTrue(lookup(".rail-file-row").queryAll().stream().allMatch(node -> node.isFocusTraversable()));
        assertTrue(lookup(".scope-toggle-button").queryAll().stream().allMatch(node -> node.isFocusTraversable()));
        assertTrue(lookup(".rail-sort-button").query().isFocusTraversable());
    }

    @Test
    void aStatementFooterIsNotAButtonThatGoesNowhere() {
        interact(() -> rail.toggleScope());
        settle();
        Button footer = lookup(".rail-funnel-footer").queryButton();
        assertTrue(footer.getText().startsWith("whole repo · "), footer.getText());
        assertFalse(footer.isFocusTraversable(),
                "in repo scope the footer reports; it does not offer anything to press");
    }

    /**
     * The footer's wording is the escape hatch, so it has to be READ, not
     * merely set. The scroll pane above it grows into every spare pixel; the
     * first cut of this rail let that squeeze the footer to one line and
     * ellipsize the "— show" off the end, which every string-level assertion
     * here still passed. So this measures the laid-out node instead.
     */
    @Test
    void aFooterTooLongForOneLineWrapsInsteadOfLosingItsEnd() {
        // A short window, so the rail's children want more height than there
        // is. That is the half that actually bites -- with room to spare the
        // footer wraps correctly and the bug is invisible.
        // Narrow enough that the wording needs a second line at all, short
        // enough that there is no spare height to give it. The RAIL is sized,
        // never the shared stage.
        interact(() -> {
            rail.setMinWidth(230);
            rail.setPrefWidth(230);
            rail.setMaxWidth(230);
            rail.setMinHeight(300);
            rail.setPrefHeight(300);
            rail.setMaxHeight(300);
        });
        settle();
        // "clamp" hits the in-diff file 40 times -- those expanded match
        // lines are what push the rail past its height -- and one file
        // outside the change, so the footer carries the long "— show".
        interact(() -> rail.setSearch("clamp"));
        waitForFooter("more file");
        settle();

        Button footer = lookup(".rail-funnel-footer").queryButton();

        double[] measured = new double[3];
        interact(() -> {
            measured[0] = footer.getHeight();
            measured[1] = footer.prefHeight(footer.getWidth());
            // The same text with all the room it wants: one line.
            measured[2] = footer.prefHeight(10_000);
        });
        assertTrue(measured[1] > measured[2] * 1.4,
                "precondition: at this width the wording needs more than one line (wrapped="
                        + measured[1] + ", single line=" + measured[2] + ")");
        assertTrue(measured[0] >= measured[1] - 0.5,
                "the footer is given its full wrapped height instead of being squeezed to one "
                        + "ellipsized line: actual=" + measured[0] + ", needed=" + measured[1]
                        + ", text=" + footer.getText());
    }

    /**
     * Repo scope with no query matches the whole worktree. Building a row per
     * file would freeze the FX thread, so the rail caps what it renders --
     * and a cap the reader cannot see would make a truncated list read as the
     * whole worktree.
     */
    @Test
    void repoScopeCapsWhatItBuildsAndSaysWhatItHeldBack() {
        interact(() -> rail.toggleScope());
        waitForRows(row -> row > 1);
        settle();

        assertTrue(rowNames().size() <= 200, "the rail renders at most a screenful: " + rowNames().size());
        Label note = lookup(".rail-empty").queryAll().stream()
                .map(Label.class::cast)
                .filter(label -> label.getText().contains("more not shown"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the held-back files are named, not silently dropped"));
        assertTrue(note.getText().startsWith("+"), note.getText());
    }

    /**
     * A checked row that scrolls out of the funnel cannot be unchecked, and
     * openCheckedFiles falls back to the bare filename as the relative path
     * for anything it can no longer resolve -- which then misses on changed
     * lines, waypoints and findings alike.
     */
    @Test
    void narrowingTheFunnelDropsSelectionsItCanNoLongerResolve() {
        interact(() -> rail.toggleScope());
        waitForRows(row -> row > 1);
        settle();

        CheckBox untouched = lookup(".rail-file-row").queryAll().stream()
                .map(Button.class::cast)
                .filter(row -> row.getTooltip() != null && row.getTooltip().getText().contains("LayoutMath"))
                .findFirst().orElseThrow()
                .lookupAll(".result-check").stream()
                .map(CheckBox.class::cast)
                .findFirst().orElseThrow();
        interact(() -> untouched.setSelected(true));
        settle();
        assertEquals("1 selected", ((Label) lookup(".open-selected-count").query()).getText());

        // Back to diff scope: LayoutMath is out of the change, so its row is
        // gone and the selection has to go with it.
        interact(() -> rail.toggleScope());
        settle();
        assertFalse(rowNames().contains("LayoutMath.java"));
        assertEquals("0 selected", ((Label) lookup(".open-selected-count").query()).getText(),
                "the vanished row's selection went with it");
    }

    /** {@code ⏎} has to land focus somewhere that will actually take it. */
    @Test
    void enterHandsTheKeyboardBackOutOfTheQueryField() {
        interact(() -> rail.focusSearch());
        settle();
        assertTrue(rail.getScene().getFocusOwner() instanceof javafx.scene.control.TextInputControl,
                "precondition: the query field has focus");

        interact(() -> rail.setSearch("clamp"));
        waitForRows(row -> row >= 1);
        push(javafx.scene.input.KeyCode.ENTER);
        settle();
        assertFalse(rail.getScene().getFocusOwner() instanceof javafx.scene.control.TextInputControl,
                "…and Enter moved it off the field, so d/s/z fire instead of being typed");
    }

    private void waitForRows(java.util.function.IntPredicate enough) {
        try {
            WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                    () -> WaitForAsyncUtils.<Boolean>waitForAsyncFx(2000,
                            () -> enough.test(rowNames().size())));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("rows never arrived: " + rowNames().size(), e);
        }
        settle();
    }

    private void waitForFooter(String fragment) {
        try {
            WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                    () -> WaitForAsyncUtils.<Boolean>waitForAsyncFx(2000,
                            () -> footerText().contains(fragment)));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("footer never reported \"" + fragment + "\": " + footerText(), e);
        }
        settle();
    }

    @Test
    void theMatchGroupCaretIsVisibleAndCollapsesTheGroup() {
        interact(() -> rail.setSearch("lerp"));
        waitForFooter("more file");
        interact(() -> rail.toggleScope());
        settle();

        ToggleButton caret = lookup(".result-caret").queryAll().stream()
                .map(ToggleButton.class::cast)
                .filter(Node::isVisible)
                .findFirst().orElseThrow(() -> new AssertionError("no visible caret on a row with matches"));
        // A target the reader can actually hit: the design's rail rows are
        // 324px wide and every other pixel of the row opens the file.
        assertTrue(caret.getWidth() >= 14 && caret.getHeight() >= 14,
                "caret hit target is " + caret.getWidth() + "x" + caret.getHeight());
        assertEquals("▾", caret.getText(), "expanded groups point down");

        Node lines = lookup(".result-match-lines").query();
        assertTrue(lines.isVisible(), "the group starts expanded and is laid out");

        clickOn(caret);
        settle();
        assertEquals("▸", caret.getText(), "collapsed groups point right");
        assertFalse(lines.isVisible(), "the match lines are hidden");
        assertFalse(lines.isManaged(), "…and no longer take up space in the rail");
    }

    @Test
    void aCollapsedGroupStaysCollapsedWhenTheRailRebuilds() {
        interact(() -> rail.setSearch("lerp"));
        waitForFooter("more file");
        interact(() -> rail.toggleScope());
        settle();

        ToggleButton caret = lookup(".result-caret").queryAll().stream()
                .map(ToggleButton.class::cast)
                .filter(Node::isVisible)
                .findFirst().orElseThrow();
        clickOn(caret);
        settle();
        assertEquals("▸", caret.getText());

        // Exactly what opening a file from the rail does.
        interact(() -> rail.setOpenFile(Path.of("ui/LayoutMath.java")));
        settle();

        ToggleButton afterRebuild = lookup(".result-caret").queryAll().stream()
                .map(ToggleButton.class::cast)
                .filter(Node::isVisible)
                .findFirst().orElseThrow();
        assertEquals("▸", afterRebuild.getText(), "the group is still collapsed after a rebuild");
        Node lines = lookup(".result-match-lines").query();
        assertFalse(lines.isVisible(), "…and its match lines are still hidden");
        assertFalse(lines.isManaged(), "…and still take up no space");
    }

    @Test
    void theResultListKeepsItsScrollPositionAcrossARebuild() {
        interact(() -> rail.toggleScope());
        settle();
        ScrollPane scroll = (ScrollPane) lookup(".search-results-scroll").query();
        interact(() -> scroll.setVvalue(0.5));
        settle();

        interact(() -> rail.refresh());
        settle();
        assertEquals(0.5, scroll.getVvalue(), 0.05,
                "a refresh must not throw the reader back to the top of the list");
    }

    @Test
    void aScrollMadeDuringARebuildIsNotOverwrittenByTheRestore() {
        interact(() -> rail.toggleScope());
        settle();
        ScrollPane scroll = (ScrollPane) lookup(".search-results-scroll").query();
        interact(() -> scroll.setVvalue(0.5));
        settle();

        // The rebuild and the reader's scroll land in the same FX pulse, before
        // the deferred restore runs.
        interact(() -> {
            rail.refresh();
            scroll.setVvalue(0.9);
        });
        settle();
        assertEquals(0.9, scroll.getVvalue(), 0.05,
                "the reader's scroll outranks the position the rebuild captured");
    }
}
