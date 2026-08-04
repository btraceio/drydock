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
import java.io.UncheckedIOException;
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
 * The by-file intent fallback against a REAL asynchronous diff.
 *
 * <p>The regression this exists for: intents are derived <em>from</em> the
 * diff, and the diff arrives on a background thread. The verdict bar used to
 * render once, before the diff existed, correctly conclude there were no
 * intents, and stay that way -- so Approve, Request change and Submit were
 * all dead on a freshly opened item, with "no intent" as the only clue. Only
 * a screenshot of the running app showed it; every test until now supplied
 * the diff synchronously and so could not.</p>
 */
class ReviewIntentFallbackTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-intent")
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
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    @Test
    void theVerdictBarPicksUpIntentsOnceTheAsyncDiffLands() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.setItems(new QueueAssembly(List.of(new ReviewItem(scope, ReviewItem.Group.MINE,
                "Working tree", "drydock · uncommitted changes")), true, true), 1));

        assertEquals("1 · A.java", awaitIntentLabel(),
                "the verdict bar must re-render when the diff arrives, not stay on 'no intent'");
    }

    /**
     * The reported bug: clicking an intent moved the verdict bar and left the
     * code exactly where it was, so the rail looked ornamental and there was
     * no way to read the change an intent describes.
     */
    @Test
    void clickingAnIntentBringsItsFileIntoTheCodeColumn() throws Exception {
        Path repo = repoWithTwoFilesFarApart();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.setItems(new QueueAssembly(List.of(new ReviewItem(scope, ReviewItem.Group.MINE,
                "Working tree", "drydock · uncommitted changes")), true, true), 1));
        assertEquals("1 · Alpha.java", awaitIntentLabel());

        assertFalse(renderedHunkFiles().contains("Zulu.java"),
                "the fixture must start with the second file below the fold");

        // fire() rather than clickOn(): what is under test is the handler, not
        // TestFX's ability to land a pointer on a rail card.
        List<javafx.scene.Node> cards = new ArrayList<>(lookup(".review-intent-card").queryAll());
        assertEquals(2, cards.size(), "expected one intent per changed file");
        interact(((javafx.scene.control.Button) cards.get(1))::fire);
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();

        assertTrue(renderedHunkFiles().contains("Zulu.java"),
                "selecting the second intent must scroll to its file; rendered " + renderedHunkFiles());
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
                Files.createTempDirectory("drydock-intent-reveal").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
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

    /** Polls the label; the diff is a real git process, so its arrival is not instant. */
    private String awaitIntentLabel() {
        String last = "";
        for (int i = 0; i < 200; i++) {
            String[] text = new String[1];
            interact(() -> text[0] = ((Label) lookup(".review-verdict-intent").query()).getText());
            last = text[0];
            if (!"no intent".equals(last)) {
                return last;
            }
            sleep(25);
        }
        return last;
    }

    private static Path repoWithTwoChangedFiles() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-intent-repo").resolve("repo"));
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
