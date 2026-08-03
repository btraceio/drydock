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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(cardCount() == 2, "the worktree's own intents come back");
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
        Files.writeString(repo.resolve("A.java"), "class A { int x = 1; }\n");
        Files.writeString(repo.resolve("B.java"), "class B { int y = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 2; }\n");
        Files.writeString(repo.resolve("B.java"), "class B { int y = 2; }\n");
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
