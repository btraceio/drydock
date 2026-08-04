package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The untracked-files toggle: on by default (an off-by-default toggle would
 * reproduce the "rail promises uncommitted changes, column shows nothing"
 * defect this branch exists to fix), filters the already-loaded diff rather
 * than re-diffing, and -- the important one -- publishes exactly what it
 * renders so the intent rail cannot disagree with the column.
 */
class ReviewDiffColumnUntrackedToggleTest extends ApplicationTest {

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
    void untrackedFilesAreIncludedByDefault() throws Exception {
        repo = repoWithTrackedAndUntrackedFiles();
        showScope(workingTreeScope(repo));
        awaitRows();

        awaitRenderedHunkFiles(Set.of("Tracked.java", "NewA.java", "NewB.java"),
                "untracked files must render by default");
    }

    @Test
    void togglingOffHidesUntrackedFilesOnly() throws Exception {
        repo = repoWithTrackedAndUntrackedFiles();
        showScope(workingTreeScope(repo));
        awaitRows();

        Button toggle = untrackedToggle();
        interact(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();

        awaitRenderedHunkFiles(Set.of("Tracked.java"),
                "toggling off must hide only the untracked files");
    }

    @Test
    void togglingBackOnRestoresThem() throws Exception {
        repo = repoWithTrackedAndUntrackedFiles();
        showScope(workingTreeScope(repo));
        awaitRows();

        Button toggle = untrackedToggle();
        interact(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();
        interact(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();

        awaitRenderedHunkFiles(Set.of("Tracked.java", "NewA.java", "NewB.java"),
                "toggling back on must restore the untracked files -- the full diff must have been kept");
    }

    @Test
    void theToggleIsHiddenWhenNothingIsUntracked() throws Exception {
        repo = repoWithOnlyATrackedModification();
        showScope(workingTreeScope(repo));
        awaitRows();

        Button toggle = untrackedToggle();
        assertFalse(toggle.isManaged(), "a dead toggle with nothing to filter is clutter");
        assertFalse(toggle.isVisible());
    }

    @Test
    void thePublishedOutcomeMatchesWhatIsRendered() throws Exception {
        repo = repoWithTrackedAndUntrackedFiles();
        AtomicReference<DiffOutcome> published = new AtomicReference<>();
        interact(() -> column.setOnDiffResolved((scopeId, outcome) -> published.set(outcome)));

        showScope(workingTreeScope(repo));
        awaitRows();

        Button toggle = untrackedToggle();
        interact(toggle::fire);
        WaitForAsyncUtils.waitForFxEvents();

        // Let the filtered render settle before comparing the two, or a lagging
        // layout pulse makes this look like a publish/render disagreement when
        // it is only a not-yet-realized cell.
        awaitRenderedHunkFiles(Set.of("Tracked.java"), "the toggle must leave only the tracked file");

        DiffOutcome outcome = published.get();
        assertInstanceOf(DiffOutcome.Loaded.class, outcome);
        UnifiedDiff publishedDiff = ((DiffOutcome.Loaded) outcome).diff();
        Set<String> publishedFiles = new TreeSet<>();
        publishedDiff.files().forEach(f -> publishedFiles.add(f.path()));

        assertEquals(renderedHunkFiles(), publishedFiles,
                "the published diff must contain exactly what is rendered -- "
                        + "otherwise the intent rail disagrees with the column");
        assertFalse(publishedFiles.contains("NewA.java"));
        assertFalse(publishedFiles.contains("NewB.java"));
    }

    // ---- helpers --------------------------------------------------------

    private Button untrackedToggle() {
        List<Node> found = new ArrayList<>();
        interact(() -> found.addAll(lookup(".review-chip-button").queryAll()));
        return found.stream()
                .map(Button.class::cast)
                .filter(b -> b.getText() != null
                        && (b.getText().equals("untracked") || b.getText().equals("no untracked")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no untracked toggle found among " + found));
    }

    private Set<String> renderedHunkFiles() {
        Set<String> files = new TreeSet<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    private ReviewScope workingTreeScope(Path root) {
        return registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE, root,
                Optional.of(root), "main", "main", Optional.empty(), Optional.empty()));
    }

    private void showScope(ReviewScope scope) {
        interact(() -> column.setScope(scope));
    }

    /**
     * Polls the rendered hunk-header labels until they match {@code expected}.
     *
     * <p>A single {@code waitForFxEvents()} is not enough after a toggle: the
     * diff list is a virtualized {@code ListView}, so which cells exist as
     * nodes is decided by a layout pulse that may not have run yet. Asserting
     * on the query directly made these tests fail intermittently, reporting a
     * subset of the files that were genuinely in the model.</p>
     */
    private void awaitRenderedHunkFiles(Set<String> expected, String message) {
        Set<String> seen = Set.of();
        for (int i = 0; i < 100; i++) {
            seen = renderedHunkFiles();
            if (expected.equals(seen)) {
                return;
            }
            WaitForAsyncUtils.waitForFxEvents();
            sleep(20);
        }
        assertEquals(expected, seen, message);
    }

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

    private static Path repoWithTrackedAndUntrackedFiles() throws Exception {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-untracked-toggle"));
        Files.writeString(repo.resolve("Tracked.java"), "class Tracked { int x = 1; }\n");
        runGit(repo, "add", "Tracked.java");
        runGit(repo, "commit", "-m", "tracked");
        Files.writeString(repo.resolve("Tracked.java"), "class Tracked { int x = 2; }\n");
        Files.writeString(repo.resolve("NewA.java"), "class NewA {}\n");
        Files.writeString(repo.resolve("NewB.java"), "class NewB {}\n");
        return repo;
    }

    private static Path repoWithOnlyATrackedModification() throws Exception {
        Path repo = initCommittedRepo(Files.createTempDirectory("drydock-no-untracked"));
        Files.writeString(repo.resolve("Tracked.java"), "class Tracked { int x = 1; }\n");
        runGit(repo, "add", "Tracked.java");
        runGit(repo, "commit", "-m", "tracked");
        Files.writeString(repo.resolve("Tracked.java"), "class Tracked { int x = 2; }\n");
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
