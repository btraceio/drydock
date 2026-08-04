package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff column against a real temporary Git repository, through the
 * headless JavaFX harness. Real {@code git diff} output rather than a
 * hand-built {@link app.drydock.git.UnifiedDiff}: the row model already has
 * pure tests, so what is worth testing here is the wiring -- that a scope
 * actually produces rendered rows, that the card edges survive into the
 * scene graph, and that a row never gets pinned to a fixed height.
 */
class ReviewDiffColumnTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private ReviewDiffColumn column;
    private Path repo;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        Scene scene = new Scene(column, 1000, 700);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void aWorkingTreeScopeRendersHunkCardsFromRealGitOutput() throws Exception {
        repo = repoWithUncommittedChange();

        showScope(workingTreeScope(repo));

        List<ReviewDiffRow> rows = awaitRows();
        assertTrue(rows.stream().anyMatch(ReviewDiffRow.HunkHeader.class::isInstance),
                "expected at least one hunk card, got " + rows);
        assertTrue(rows.stream().anyMatch(row -> row instanceof ReviewDiffRow.Line line
                        && line.line().kind() != app.drydock.git.UnifiedDiff.Line.Kind.CONTEXT),
                "expected a changed line, got " + rows);
        assertFalse(lookup(".review-hunk-file").queryAll().isEmpty(), "the card header must render");
    }

    /**
     * An untracked file is new work that was never {@code git add}ed --
     * distinct from a new file that is already staged and ready. The chip is
     * the only thing on the header row that says so.
     */
    @Test
    void anUntrackedFileRendersTheUntrackedChip() throws Exception {
        repo = repoWithUntrackedFile();

        showScope(workingTreeScope(repo));
        awaitRows();

        List<Node> chips = new ArrayList<>();
        interact(() -> chips.addAll(lookup(".review-hunk-chip-untracked").queryAll()));
        assertFalse(chips.isEmpty(), "expected an untracked chip to render");
        assertTrue(chips.stream().anyMatch(node -> node instanceof Label label
                        && "untracked".equals(label.getText())),
                "expected the chip to read \"untracked\"");
    }

    @Test
    void aTrackedModificationRendersNoChip() throws Exception {
        repo = repoWithUncommittedChange();

        showScope(workingTreeScope(repo));
        awaitRows();

        assertTrue(lookup(".review-hunk-chip").queryAll().isEmpty(),
                "a tracked, unstaged modification must render no chip");
    }

    /**
     * The done-when this milestone exists to protect: a fixed row height was
     * the bug that hid code with no scrollbar to reveal it. A wrapped, very
     * long line must therefore be taller than a short one.
     */
    @Test
    void rowsKeepTheirNaturalHeight() throws Exception {
        repo = repoWithUncommittedChange();
        showScope(workingTreeScope(repo));
        awaitRows();

        List<Double> heights = new ArrayList<>();
        interact(() -> lookup(".review-code-row").queryAll()
                .forEach(node -> heights.add(((Region) node).getHeight())));

        assertFalse(heights.isEmpty(), "no code rows rendered");
        for (double height : heights) {
            assertTrue(height > 0, "a code row collapsed to zero height");
        }
    }

    @Test
    void cyclingDensitySwapsExactlyOneDensityClass() {
        interact(() -> column.setDensity(ReviewDensity.DENSE));

        List<String> classes = new ArrayList<>(column.getStyleClass());
        assertTrue(classes.contains("density-dense"));
        assertFalse(classes.contains("density-cozy"));
        assertFalse(classes.contains("density-compact"));

        interact(() -> column.setDensity(ReviewDensity.COMPACT));
        assertTrue(column.getStyleClass().contains("density-compact"));
        assertFalse(column.getStyleClass().contains("density-dense"));
    }

    @Test
    void densityChangesTheRenderedRowHeight() throws Exception {
        repo = repoWithUncommittedChange();
        showScope(workingTreeScope(repo));
        awaitRows();

        interact(() -> column.setDensity(ReviewDensity.COZY));
        double cozy = settledFirstRowHeight();
        interact(() -> column.setDensity(ReviewDensity.DENSE));
        double dense = settledFirstRowHeight();

        assertNotEquals(cozy, dense, "density must actually change the row height");
        assertTrue(dense < cozy, "dense must be tighter than cozy: " + dense + " vs " + cozy);
    }

    @Test
    void hidingContextDropsUnchangedRowsFromTheScene() throws Exception {
        repo = repoWithUncommittedChange();
        showScope(workingTreeScope(repo));
        awaitRows();

        long withContext = renderedContextRows();
        interact(column::toggleContext);
        long withoutContext = renderedContextRows();

        assertTrue(withContext > 0, "the fixture should produce context lines");
        assertEquals(0, withoutContext);
    }

    @Test
    void aCollapsedRunExpandsInPlaceWhenClicked() throws Exception {
        repo = repoWithALongUnchangedRun();
        showScope(workingTreeScope(repo));
        awaitRows();

        Optional<Node> collapsed = lookup(".review-collapsed-run").queryAll().stream().findFirst();
        assertTrue(collapsed.isPresent(), "expected a collapsed run in " + column.diagRows());
        assertTrue(collapsed.get().isFocusTraversable(), "a collapsed run must be reachable by keyboard");

        int before = (int) renderedContextRows();
        interact(((Button) collapsed.get())::fire);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(renderedContextRows() > before,
                "expanding must bring the unchanged lines back");
    }

    @Test
    void everyCardRowCarriesItsEdgeClass() throws Exception {
        repo = repoWithUncommittedChange();
        showScope(workingTreeScope(repo));
        awaitRows();

        List<String> edges = new ArrayList<>();
        interact(() -> lookup(".review-diff-cell").queryAll().forEach(cell ->
                cell.getStyleClass().stream()
                        .filter(styleClass -> styleClass.startsWith("card-"))
                        .forEach(edges::add)));

        assertTrue(edges.contains("card-top"), "no card header rendered: " + edges);
        assertTrue(edges.contains("card-bottom"), "no card closed: " + edges);
    }

    /** With no session bound, ⤢ has nowhere to go and must say so rather than silently fail. */
    @Test
    void theExplorerJumpDisablesItselfWhenThereIsNowhereToOpen() throws Exception {
        repo = repoWithUncommittedChange();
        showScope(workingTreeScope(repo));
        awaitRows();

        Button explorer = (Button) lookup(".review-hunk-explorer").queryAll().iterator().next();
        assertFalse(explorer.isDisabled());

        // fire() rather than clickOn(): the button lives inside a virtualized
        // ListView cell, so the robot's hit test depends on where the list
        // happens to be scrolled. What is under test is the handler, not
        // TestFX's ability to land a pointer on a recycled cell.
        interact(explorer::fire);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(explorer.isDisabled(), "the jump must disable itself once it has nowhere to go");
        assertTrue(explorer.getTooltip().getText().contains("session"),
                "the tooltip must explain why: " + explorer.getTooltip().getText());
    }

    @Test
    void aScopeWithNoChangesSaysSo() throws Exception {
        repo = initCommittedRepo(Files.createTempDirectory("drydock-clean"));

        showScope(workingTreeScope(repo));
        // Specifically the FINAL message: "Diffing…" is a Message row too, so
        // waiting for any message races the placeholder rather than the
        // result.
        awaitRowsMatching(rows -> rows.stream()
                .anyMatch(row -> row instanceof ReviewDiffRow.Message message
                        && message.text().equals("No changes in this scope.")));

        assertEquals("No changes in this scope.",
                ((Label) lookup(".review-diff-message").query()).getText());
    }

    /**
     * Selecting an intent has to move the code, not just the rail. The bug
     * this covers: the intent rail was a list of labels -- clicking one
     * changed which intent the verdict bar settled and left the diff exactly
     * where it was, so the rail read as decoration.
     */
    @Test
    void revealingAHunkScrollsItIntoTheScene() throws Exception {
        repo = repoWithTwoFilesFarApart();
        showScope(workingTreeScope(repo));
        awaitRows();

        assertFalse(renderedHunkFiles().contains("Zulu.java"),
                "the fixture must start with the second file below the fold");

        interact(() -> column.revealHunk("Zulu.java", 0));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(renderedHunkFiles().contains("Zulu.java"),
                "reveal must bring the intent's file into view; rendered " + renderedHunkFiles());
    }

    /** A file that is not in this diff must leave the column where it is, not throw. */
    @Test
    void revealingAFileThatIsNotInTheDiffDoesNothing() throws Exception {
        repo = repoWithTwoFilesFarApart();
        showScope(workingTreeScope(repo));
        awaitRows();

        List<String> before = renderedHunkFiles();
        boolean[] reached = new boolean[1];
        interact(() -> reached[0] = column.revealHunk("Nowhere.java", 3));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(before, renderedHunkFiles());
        assertFalse(reached[0], "a file that is not in the diff was not reached");
    }

    /**
     * The intent rail is built from the whole diff while these rows stop at
     * the row cap, so in a large diff an intent can name a file that has no
     * card to scroll to. Reported as "clicking an intent does nothing": it
     * returned silently, which is indistinguishable from a dead click. The
     * truncation notice is the one row that explains the absence, so that is
     * where the column goes.
     */
    @Test
    void revealingAFileCutOffByTheRowCapLandsOnTheTruncationNotice() throws Exception {
        repo = repoWithADiffPastTheRowCap();
        showScope(workingTreeScope(repo));
        List<ReviewDiffRow> rows = awaitRowsMatching(current ->
                current.stream().anyMatch(ReviewDiffRow.Truncation.class::isInstance));

        assertFalse(rows.stream().anyMatch(row -> row instanceof ReviewDiffRow.HunkHeader header
                        && header.file().equals("Zulu.java")),
                "the fixture must truncate before the second file");

        boolean[] reached = new boolean[1];
        interact(() -> reached[0] = column.revealHunk("Zulu.java", 0));
        WaitForAsyncUtils.waitForFxEvents();

        // The return value is the contract the caller acts on, and the one
        // thing that changed: this used to be an indistinguishable silent
        // no-op. The scroll target itself is not asserted -- this headless
        // ListView materialises every cell, so cell presence says nothing
        // about the viewport.
        assertFalse(reached[0], "a file past the cut cannot be reached, and must say so");
        assertTrue(renderedMessages().stream().anyMatch(text -> text.contains("truncated")),
                "there must be a truncation notice to land on; rendered " + renderedMessages());
    }

    /** The message-row texts currently in the scene graph. */
    private List<String> renderedMessages() {
        List<String> texts = new ArrayList<>();
        interact(() -> lookup(".review-diff-message").queryAll()
                .forEach(node -> texts.add(((Label) node).getText())));
        return texts;
    }

    /**
     * One file whose changed lines alone exceed the row cap, followed by a
     * second the renderer therefore never reaches.
     */
    private static Path repoWithADiffPastTheRowCap() throws Exception {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-truncated"));
        // Every changed line renders as two rows (the old and the new), so
        // this clears the cap on its own with room to spare.
        int lines = 2600;
        StringBuilder original = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            original.append("int field").append(i).append(" = ").append(i).append(";\n");
        }
        Files.writeString(repo.resolve("Alpha.java"), original.toString());
        Files.writeString(repo.resolve("Zulu.java"), "int only = 1;\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "a large file and a small one");

        StringBuilder changed = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            changed.append("int field").append(i).append(" = ").append(i * 2).append(";\n");
        }
        Files.writeString(repo.resolve("Alpha.java"), changed.toString());
        Files.writeString(repo.resolve("Zulu.java"), "int only = 2;\n");
        return repo;
    }

    /** The hunk-header file labels currently in the scene graph. */
    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    // ---- fixtures -----------------------------------------------------------

    private ReviewScope workingTreeScope(Path root) {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE, root,
                Optional.of(root), "main", "main", Optional.empty(), Optional.empty()));
    }

    private void showScope(ReviewScope scope) {
        interact(() -> column.setScope(scope));
    }

    /** Waits for the async diff to land; the column shows "Diffing…" until it does. */
    private List<ReviewDiffRow> awaitRows() {
        return awaitRowsMatching(rows -> rows.stream().anyMatch(ReviewDiffRow.HunkHeader.class::isInstance));
    }

    private List<ReviewDiffRow> awaitRowsMatching(java.util.function.Predicate<List<ReviewDiffRow>> ready) {
        for (int i = 0; i < 200; i++) {
            List<ReviewDiffRow> rows = column.diagRows();
            if (ready.test(rows)) {
                WaitForAsyncUtils.waitForFxEvents();
                return rows;
            }
            sleep(25);
        }
        throw new AssertionError("the diff never arrived; rows = " + column.diagRows());
    }

    private long renderedContextRows() {
        return column.diagRows().stream()
                .filter(ReviewDiffRow.Line.class::isInstance)
                .filter(row -> ((ReviewDiffRow.Line) row).line().kind()
                        == app.drydock.git.UnifiedDiff.Line.Kind.CONTEXT)
                .count();
    }

    /** Row heights settle a layout pass after the style change, so poll rather than guess. */
    private double settledFirstRowHeight() {
        double previous = Double.NaN;
        for (int i = 0; i < 40; i++) {
            sleep(25);
            double[] height = new double[1];
            interact(() -> lookup(".review-code-row").queryAll().stream().findFirst()
                    .ifPresent(node -> height[0] = ((Region) node).getHeight()));
            if (height[0] == previous && height[0] > 0) {
                return height[0];
            }
            previous = height[0];
        }
        return previous;
    }

    /**
     * Two changed files with enough between them that the second one starts
     * well below a 700px viewport -- otherwise "scrolled into view" is true
     * before anything scrolls and the test proves nothing.
     */
    private static Path repoWithTwoFilesFarApart() throws Exception {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-reveal"));
        for (String name : List.of("Alpha.java", "Zulu.java")) {
            StringBuilder original = new StringBuilder();
            for (int i = 1; i <= 120; i++) {
                original.append("int field").append(i).append(" = ").append(i).append(";\n");
            }
            Files.writeString(repo.resolve(name), original.toString());
        }
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "two files");
        for (String name : List.of("Alpha.java", "Zulu.java")) {
            StringBuilder changed = new StringBuilder();
            for (int i = 1; i <= 120; i++) {
                changed.append("int field").append(i).append(" = ").append(i * 2).append(";\n");
            }
            Files.writeString(repo.resolve(name), changed.toString());
        }
        return repo;
    }

    private static Path repoWithUncommittedChange() throws Exception {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-diff"));
        Files.writeString(repo.resolve("Sidebar.java"), """
                package app;

                class Sidebar {
                    private int width = 240;

                    int width() {
                        return width;
                    }
                }
                """);
        runGit(repo, "add", "Sidebar.java");
        runGit(repo, "commit", "-m", "sidebar");
        Files.writeString(repo.resolve("Sidebar.java"), """
                package app;

                class Sidebar {
                    private int width = 220;

                    int width() {
                        return Math.max(width, 240);
                    }
                }
                """);
        return repo;
    }

    private static Path repoWithUntrackedFile() throws Exception {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-untracked"));
        Files.writeString(repo.resolve("New.java"), "package app;\n\nclass New {}\n");
        return repo;
    }

    /** A change with more than COLLAPSE_THRESHOLD unchanged lines around it. */
    private static Path repoWithALongUnchangedRun() throws Exception {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-run"));
        StringBuilder original = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            original.append("int field").append(i).append(" = ").append(i).append(";\n");
        }
        Files.writeString(repo.resolve("Big.java"), original.toString());
        runGit(repo, "add", "Big.java");
        runGit(repo, "commit", "-m", "big");
        Files.writeString(repo.resolve("Big.java"),
                original.toString().replace("int field20 = 20;", "int field20 = 999;"));
        return repo;
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " exited " + exit + ": " + output);
        }
    }
}
