package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The reported defect: the intent rail described whichever scope last
 * produced a diff, not the scope the header named. A not-checked-out PR
 * never runs a diff at all, so it inherited the previous item's files and
 * kept them -- which is how a repository with no diffable item at all came
 * to show another repository's files.
 */
class ReviewIntentScopeIsolationTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-isolation")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void aGateItemDoesNotInheritThePreviousItemsFiles() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        ReviewScope gate = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, repo, Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty()));

        interact(() -> view.setItems(new QueueAssembly(List.of(
                new ReviewItem(worktree, ReviewItem.Group.MINE, "Working tree", "repo · uncommitted"),
                new ReviewItem(gate, ReviewItem.Group.REQUESTED, "PR #7 feature", "repo · not checked out")),
                true, true), 1));

        awaitCardCount(2);

        interact(() -> view.selectScope(gate.id()));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, cardCount(),
                "a scope with no diff of its own must show no intents, not the previous scope's");
        assertEquals("Not checked out — check out to group changes", railMessage());
    }

    @Test
    void comingBackToTheWorktreeRestoresItsOwnIntents() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        ReviewScope gate = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, repo, Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty()));

        interact(() -> view.setItems(new QueueAssembly(List.of(
                new ReviewItem(worktree, ReviewItem.Group.MINE, "Working tree", "repo · uncommitted"),
                new ReviewItem(gate, ReviewItem.Group.REQUESTED, "PR #7 feature", "repo · not checked out")),
                true, true), 1));
        awaitCardCount(2);

        interact(() -> view.selectScope(gate.id()));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        interact(() -> view.selectScope(worktree.id()));

        awaitCardCount(2);
    }

    /**
     * The reported defect: {@code refreshReviewState}'s early return for "no
     * scope selected" cleared the margin and the verdict bar but never the
     * rail, so a rescan that emptied the queue left the previous scope's
     * cards on screen -- a dead click describing an item no longer queued.
     */
    @Test
    void theRailClearsWhenTheQueueEmpties() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.setItems(new QueueAssembly(List.of(
                new ReviewItem(worktree, ReviewItem.Group.MINE, "Working tree", "repo · uncommitted")),
                true, true), 1));
        awaitCardCount(2);

        // A rescan that finds nothing (worktree pruned, or gh down): the
        // SCAN_INCOMPLETE empty state, exactly as QueueAssembly.complete()
        // computes it for localComplete=true, requestsComplete=false.
        interact(() -> view.setItems(new QueueAssembly(List.of(), true, false), 1));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, cardCount(), "an empty queue must clear the rail, not keep the departed scope's cards");
    }

    /**
     * The spec's own statement of the defect (design §"cross-repository"):
     * "selecting an item in a second repository never shows the first
     * repository's files." Two distinct repos, two distinctly-named files,
     * selecting the second must show only its own titles.
     */
    @Test
    void aSecondRepositoryNeverShowsTheFirstRepositorysFiles() throws Exception {
        Path repoOne = repoWithNamedFile("drydock-isolation-repo-one", "Alpha.java");
        Path repoTwo = repoWithNamedFile("drydock-isolation-repo-two", "Zulu.java");
        ReviewScope scopeOne = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repoOne, Optional.of(repoOne), "main", "main",
                Optional.empty(), Optional.empty()));
        ReviewScope scopeTwo = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repoTwo, Optional.of(repoTwo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.setItems(new QueueAssembly(List.of(
                new ReviewItem(scopeOne, ReviewItem.Group.MINE, "repo-one", "repo-one · uncommitted"),
                new ReviewItem(scopeTwo, ReviewItem.Group.MINE, "repo-two", "repo-two · uncommitted")),
                true, true), 2));

        interact(() -> view.selectScope(scopeOne.id()));
        awaitCardCount(1);
        assertEquals(List.of("Alpha.java"), cardTitles());

        interact(() -> view.selectScope(scopeTwo.id()));
        awaitCardCount(1);
        List<String> titles = cardTitles();
        assertEquals(List.of("Zulu.java"), titles);
        assertFalse(titles.contains("Alpha.java"), "the second repository must not carry the first's files");
    }

    private List<String> cardTitles() {
        List<String> titles = new ArrayList<>();
        interact(() -> lookup(".review-intent-title").queryAll()
                .forEach(node -> titles.add(((Label) node).getText())));
        return titles;
    }

    private int cardCount() {
        int[] count = new int[1];
        interact(() -> count[0] = lookup(".review-intent-card").queryAll().size());
        return count[0];
    }

    private String railMessage() {
        String[] text = new String[1];
        interact(() -> text[0] = lookup(".review-intent-empty").tryQuery()
                .map(node -> ((Label) node).getText()).orElse(""));
        return text[0];
    }

    private void awaitCardCount(int expected) {
        for (int i = 0; i < 200; i++) {
            if (cardCount() == expected) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("expected " + expected + " intent cards, saw " + cardCount());
    }

    private static Path repoWithTwoChangedFiles() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-isolation-repo").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        // Two directories, not two root-level files: the fallback grouping
        // clusters by directory, so a pair of siblings is one card and this
        // test needs two.
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(repo.resolve("lib"));
        Files.writeString(repo.resolve("src/A.java"), "class A { int x = 1; }\n");
        Files.writeString(repo.resolve("lib/B.java"), "class B { int y = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        Files.writeString(repo.resolve("src/A.java"), "class A { int x = 2; }\n");
        Files.writeString(repo.resolve("lib/B.java"), "class B { int y = 2; }\n");
        return repo;
    }

    /** A repo with a single changed file named {@code fileName}, for cross-repository isolation. */
    private static Path repoWithNamedFile(String tempDirPrefix, String fileName) throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory(tempDirPrefix).resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve(fileName), "class Original { int x = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        Files.writeString(repo.resolve(fileName), "class Original { int x = 2; }\n");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
