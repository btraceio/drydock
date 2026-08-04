package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The publish channel carries the scope a diff belongs to, and carries a
 * failure as well as a success. Both halves are load-bearing: without the
 * scope id an intent rail cannot tell whose diff it received, and without
 * the failure a failed scope is indistinguishable from one still loading.
 */
class ReviewDiffColumnPublishTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final Map<String, DiffOutcome> published = new ConcurrentHashMap<>();
    private final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
    private ReviewDiffColumn column;

    @Override
    public void start(Stage stage) {
        column = new ReviewDiffColumn(diffService, (scope, file, line) -> false);
        column.setOnDiffResolved((scopeId, outcome) -> {
            published.put(scopeId, outcome);
            order.add(scopeId);
        });
        stage.setScene(new Scene(column, 1400, 900));
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
    }

    @Test
    void aLoadedDiffIsPublishedUnderItsOwnScopeId() throws Exception {
        Path repo = dirtyRepo();
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> column.setScope(scope));

        DiffOutcome outcome = await(scope.id());
        assertInstanceOf(DiffOutcome.Loaded.class, outcome);
        assertEquals(1, ((DiffOutcome.Loaded) outcome).diff().files().size());
    }

    @Test
    void aFailedDiffIsPublishedAsFailedRatherThanNotAtAll() throws Exception {
        // A directory that is not a git repository: git exits non-zero.
        Path notARepo = Files.createTempDirectory("drydock-not-a-repo");
        ReviewScope scope = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, notARepo, Optional.of(notARepo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> column.setScope(scope));

        assertInstanceOf(DiffOutcome.Failed.class, await(scope.id()),
                "a failure must reach the channel; silence is indistinguishable from loading");
    }

    private DiffOutcome await(String scopeId) {
        for (int i = 0; i < 200; i++) {
            DiffOutcome outcome = published.get(scopeId);
            if (outcome != null && !(outcome instanceof DiffOutcome.Diffing)) {
                return outcome;
            }
            sleep(25);
        }
        throw new AssertionError("no terminal outcome published for " + scopeId
                + "; saw " + order);
    }

    private static Path dirtyRepo() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-publish").resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 1; }\n");
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "initial");
        Files.writeString(repo.resolve("A.java"), "class A { int x = 2; }\n");
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
