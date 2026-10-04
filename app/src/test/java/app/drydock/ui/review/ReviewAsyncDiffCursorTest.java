package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hunk diff's file cursor against a REAL asynchronous diff.
 *
 * <p>The regression this exists for: the cursor's files are derived
 * <em>from</em> the diff, and the diff arrives on a background thread. The
 * verdict bar used to render once, before the diff existed, correctly
 * conclude there was nothing to settle, and stay that way -- so Approve,
 * Request change and Submit were all dead on a freshly opened item. Only a
 * screenshot of the running app showed it; tests that supply the diff
 * synchronously cannot.</p>
 */
class ReviewAsyncDiffCursorTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-cursor")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void theVerdictBarPicksUpTheFirstFileOnceTheAsyncDiffLands() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));

        assertEquals("1/2 · lib/B.java", awaitTargetLabel(),
                "the verdict bar must re-render when the diff arrives, not stay on 'no file'");
    }

    /**
     * Moving the cursor must move the code: a verdict bar that names the
     * next file while the column stays where it was settles code nobody
     * was shown.
     */
    @Test
    void theNextFileKeyBringsThatFileIntoTheCodeColumn() throws Exception {
        Path repo = repoWithTwoFilesFarApart();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        assertEquals("1/2 · alpha/Alpha.java", awaitTargetLabel());

        assertFalse(renderedHunkFiles().stream().anyMatch(p -> p.endsWith("Zulu.java")),
                "the fixture must start with the second file below the fold");

        interact(view::requestFocus);
        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("2/2 · zulu/Zulu.java", awaitTargetLabel());
        assertTrue(renderedHunkFiles().stream().anyMatch(p -> p.endsWith("Zulu.java")),
                "] must scroll to the next file; rendered " + renderedHunkFiles());
    }

    /**
     * A session refresh hands the board the SAME scopes again; the diff is
     * cached, so {@code bodyFor} restores it through {@code showDiff} rather
     * than re-running git. That must still land on the first file -- the
     * cursor AND the code -- not leave the column where the last read
     * stopped. (Ported from the intent rail's {@code
     * ReviewLandsOnFirstIntentTest}.)
     */
    @Test
    void showingTheSameScopesAgainLandsBackOnTheFirstFile() throws Exception {
        Path repo = repoWithTwoFilesFarApart();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        SessionReviewScopes.Scopes scopes = new SessionReviewScopes.Scopes(scope, Optional.empty());
        interact(() -> view.showScopes(scopes, SessionReviewScopes.Choice.LOCAL));
        assertEquals("1/2 · alpha/Alpha.java", awaitTargetLabel());
        interact(view::requestFocus);
        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("2/2 · zulu/Zulu.java", awaitTargetLabel(), "precondition: moved off file 1");

        interact(() -> view.showScopes(scopes, SessionReviewScopes.Choice.LOCAL));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("1/2 · alpha/Alpha.java", awaitTargetLabel());
        assertTrue(renderedHunkFiles().stream().anyMatch(p -> p.endsWith("Alpha.java")),
                "showing the same scopes again scrolls back to the first file; rendered " + renderedHunkFiles());
    }

    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    /** Two changed files far enough apart that the second starts below the viewport. */
    private static Path repoWithTwoFilesFarApart() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-cursor-reveal").resolve("repo"));
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

    /** Polls the label; the diff is a real git process, so its arrival is not instant. */
    private String awaitTargetLabel() {
        String last = "";
        for (int i = 0; i < 200; i++) {
            String[] text = new String[1];
            interact(() -> text[0] = ((Label) lookup(".review-verdict-target").query()).getText());
            last = text[0];
            if (!"no file".equals(last)) {
                return last;
            }
            sleep(25);
        }
        return last;
    }

    private static Path repoWithTwoChangedFiles() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-cursor-repo").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
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
