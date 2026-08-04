package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Four situations produce an empty rail, and rendering one as another is
 * how a reader learns to distrust it: a failed diff is not "still loading",
 * and a worktree with nothing changed is not "not checked out".
 *
 * <p>{@link #everyEmptyReasonHasItsOwnSentence()} only pins the enum's own
 * string table down; it never exercises {@code ReviewDestinationView
 * .emptyReason()}, the actual {@code DiffOutcome} → {@code Empty} mapping.
 * The tests below drive that mapping for real, through the
 * {@code diagPublishOutcome} seam, for the two cases the isolation and
 * fallback tests do not already cover (a failed diff, and a loaded diff with
 * no files).</p>
 */
class ReviewIntentRailEmptyStateTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) throws Exception {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-empty-reason")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-empty-reason-repo").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("A.java"), "class A {}\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        interact(() -> view.setItems(new QueueAssembly(
                List.of(new ReviewItem(scope, ReviewItem.Group.MINE, "Working tree", "repo · uncommitted")),
                true, true), 1));
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void everyEmptyReasonHasItsOwnSentence() {
        assertEquals("Diffing…", ReviewIntentRail.Empty.DIFFING.message());
        assertEquals("Not checked out — check out to group changes",
                ReviewIntentRail.Empty.NOT_CHECKED_OUT.message());
        assertEquals("Could not diff — see the message beside this",
                ReviewIntentRail.Empty.DIFF_FAILED.message());
        assertEquals("No changes", ReviewIntentRail.Empty.NO_CHANGES.message());
    }

    @Test
    void theNonEmptyCaseHasNoMessageAtAll() {
        assertEquals("", ReviewIntentRail.Empty.NONE.message());
    }

    @Test
    void aFailedDiffShowsTheDiffFailedMessage() {
        interact(() -> view.diagPublishOutcome(scope.id(), new DiffOutcome.Failed("git exploded")));

        assertEquals(ReviewIntentRail.Empty.DIFF_FAILED.message(), railMessage());
    }

    @Test
    void aLoadedDiffWithNoFilesShowsTheNoChangesMessage() {
        interact(() -> view.diagPublishOutcome(scope.id(), new DiffOutcome.Loaded(new UnifiedDiff(List.of()))));

        assertEquals(ReviewIntentRail.Empty.NO_CHANGES.message(), railMessage());
    }

    private String railMessage() {
        String[] text = new String[1];
        interact(() -> text[0] = lookup(".review-intent-empty").tryQuery()
                .map(node -> ((Label) node).getText()).orElse(""));
        return text[0];
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
    }
}
