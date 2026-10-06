package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessRunner;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import app.drydock.testing.FxSync;
import app.drydock.ui.TestStages;

import app.drydock.testing.FxTest;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The board's out-of-diff fan-in scan (spec §4.3), which the tour's step
 * panel reads for a step's impact. A REAL scan over a real repository: the
 * board spawns the {@code git grep} itself once the change graph lands.
 */
class ReviewFanInScanTest extends FxTest {

    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final DiffService diffService = new DiffService();

    private FakeReviewHost host;
    private SessionReviewView view;
    private Path repo;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-fanin")
                    .resolve("annotations.json"));
            repo = initRepoWithAnOutsideCaller(Files.createTempDirectory("drydock-fanin-repo"));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UncheckedIOException(new IOException(e));
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
        interact(view::close);
        diffService.close();
        host.store.close();
    }

    /**
     * {@code OutOfDiffFanIn.scan} spawns a {@code git grep} and waits up to
     * thirty seconds for it. Asserted, not assumed: {@code Sections.of} on
     * the FX thread already froze this board for ~2.7 seconds once, and a
     * subprocess there would be far worse.
     */
    @Test
    void theScanNeverRunsOnTheFxThread() {
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                repo, Optional.of(repo), "main", "HEAD", Optional.empty(), Optional.empty()));
        UnifiedDiff diff = new UnifiedDiff(List.of(oneHunkFile("src/Zeta.java", "class Astrolabe { }")));
        host.diff = diff;
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, diff));
        FxSync.waitForFxEvents();

        long start = System.nanoTime();
        while (view.diagFanInScanThread() == null) {
            if (System.nanoTime() - start > 60_000_000_000L) {
                throw new AssertionError("timed out waiting for the fan-in scan to run");
            }
            sleep(50);
        }

        assertEquals("drydock-section-graph", view.diagFanInScanThread(),
                "the scan must run on the section-graph executor, never on the FX thread");
    }

    private static UnifiedDiff.FileDiff oneHunkFile(String path, String added) {
        List<UnifiedDiff.Line> lines = List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(1), added));
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1 +1 @@", lines)));
    }

    /** A committed repository where the changed file's declaration is used from a file the diff does not touch. */
    private static Path initRepoWithAnOutsideCaller(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Zeta.java"), "class Astrolabe { }\n");
        Files.writeString(repo.resolve("src/Caller.java"),
                "class Caller {\n  void use() { new Astrolabe(); }\n}\n");
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "add", "-A");
        runGit(repo, "commit", "-m", "seed");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.run(command, repo, Duration.ofSeconds(30));
    }
}
