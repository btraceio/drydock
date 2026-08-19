package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A background queue reassembly used to leave the centre wherever the last
 * read had scrolled it, even though it re-selects the same scope and resets
 * the verdict bar back to intent 1 -- so the bar said "intent 1" while the
 * code stayed parked on whatever hunk the previous intent had scrolled to.
 *
 * <p>The trap was {@link ReviewDiffColumn#setScope} taking its same-id no-op
 * branch: re-selecting a scope the column is already showing re-ran no diff,
 * so {@code setOnDiffResolved} never fired and nothing revealed anything on
 * its own. The deleted queue's {@code setItems} re-selecting the
 * previously-selected scope on every reassembly was exactly this path.
 *
 * <p>{@link SessionReviewView#showScopes} has no such no-op branch --
 * {@link SessionReviewView#renderSelectedScope} always resets the cursor and
 * re-renders, cached diff or not (see {@code bodyFor}'s cache, which this
 * test now goes through instead: the second {@code showScopes} restores the
 * diff via {@code showDiff} rather than re-running git). What this pins is
 * the guarantee that trap broke: handing the board the SAME scopes again
 * must still land back on the first intent, not leave the column wherever
 * the last read happened to stop.</p>
 */
class ReviewLandsOnFirstIntentTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-land")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        view = new SessionReviewView(host, diffService, null);
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
    void showingTheSameScopesAgainLandsBackOnTheFirstIntent() throws Exception {
        Path repo = repoWithTwoFilesFarApart();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        SessionReviewScopes.Scopes scopes = new SessionReviewScopes.Scopes(scope, Optional.empty());

        interact(() -> view.showScopes(scopes, SessionReviewScopes.Choice.LOCAL));
        awaitCardCount(2);

        // Walk to the second intent, which scrolls the column to Zulu.java.
        List<Node> cards = new ArrayList<>(lookup(".review-intent-card").queryAll());
        interact(((Button) cards.get(1))::fire);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(renderedHunkFiles().stream().anyMatch(p -> p.endsWith("Zulu.java")),
                "precondition: moved off intent 1");

        // A session refresh hands the board the SAME scopes again -- the diff
        // is already cached, so bodyFor restores it via showDiff rather than
        // re-running git (see SessionReviewView#bodyFor). That must still
        // land on the first intent, not leave the column where the last read
        // of it happened to stop.
        interact(() -> view.showScopes(scopes, SessionReviewScopes.Choice.LOCAL));
        awaitCardCount(2);

        assertTrue(renderedHunkFiles().stream().anyMatch(p -> p.endsWith("Alpha.java")),
                "showing the same scopes again lands on the first intent; rendered " + renderedHunkFiles());
    }

    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    private void awaitCardCount(int expected) {
        for (int i = 0; i < 200; i++) {
            int[] count = new int[1];
            interact(() -> count[0] = lookup(".review-intent-card").queryAll().size());
            if (count[0] == expected) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("never reached " + expected + " intent cards");
    }

    /**
     * Two changed files far enough apart that the second starts below the
     * viewport -- the same fixture shape ReviewIntentFallbackTest uses, and
     * the reason it can tell "scrolled to Alpha" from "scrolled to Zulu".
     */
    private static Path repoWithTwoFilesFarApart() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-land-repo").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        for (String name : List.of("alpha/Alpha.java", "zulu/Zulu.java")) {
            StringBuilder original = new StringBuilder();
            for (int i = 1; i <= 120; i++) {
                original.append("int field").append(i).append(" = ").append(i).append(";\n");
            }
            Files.createDirectories(repo.resolve(name).getParent());
            Files.writeString(repo.resolve(name), original.toString());
        }
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "two files");
        for (String name : List.of("alpha/Alpha.java", "zulu/Zulu.java")) {
            StringBuilder changed = new StringBuilder();
            for (int i = 1; i <= 120; i++) {
                changed.append("int field").append(i).append(" = ").append(i * 2).append(";\n");
            }
            Files.createDirectories(repo.resolve(name).getParent());
            Files.writeString(repo.resolve(name), changed.toString());
        }
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
