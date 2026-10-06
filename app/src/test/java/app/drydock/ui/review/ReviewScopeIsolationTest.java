package app.drydock.ui.review;

import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reported defect: the board described whichever scope last
 * produced a diff, not the scope the header named. A not-checked-out PR
 * never runs a diff at all, so it inherited the previous item's files and
 * kept them -- which is how a repository with no diffable item at all came
 * to show another repository's files.
 */
class ReviewScopeIsolationTest extends FxTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-isolation")
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
    void aGateItemDoesNotInheritThePreviousItemsFiles() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        ReviewScope gate = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, repo, Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(worktree, Optional.of(gate)),
                SessionReviewScopes.Choice.LOCAL));

        awaitTarget("1/2 · lib/B.java");

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        FxSync.waitForFxEvents();

        assertEquals("no file", targetLabel(),
                "a scope with no diff of its own must show no file, not the previous scope's");
    }

    @Test
    void comingBackToTheWorktreeRestoresItsOwnFiles() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));
        ReviewScope gate = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, repo, Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(worktree, Optional.of(gate)),
                SessionReviewScopes.Choice.LOCAL));
        awaitTarget("1/2 · lib/B.java");

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        FxSync.waitForFxEvents();
        view.diagSelectChoice(SessionReviewScopes.Choice.LOCAL);

        awaitTarget("1/2 · lib/B.java");
    }

    /**
     * The reported defect: {@code refreshReviewState}'s early return for "no
     * scope selected" cleared the margin but not everything that described
     * the scope, so a rescan that emptied the queue left the previous
     * scope's files on screen -- a dead click describing an item no longer
     * queued.
     *
     * <p>The queue that used to empty is gone with the Review destination;
     * the board now loses its scope the same way the two placeholder states
     * do -- {@link SessionReviewView#showResolving()} -- which runs through
     * the exact same {@code refreshReviewState} early return this guards.</p>
     */
    @Test
    void theVerdictBarClearsWhenTheScopeIsLost() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(worktree, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        awaitTarget("1/2 · lib/B.java");

        interact(view::showResolving);
        FxSync.waitForFxEvents();

        assertEquals("no file", targetLabel(),
                "losing the scope must clear the bar, not keep the departed scope's file");
    }

    /**
     * The spec's own statement of the defect (design §"cross-repository"):
     * "selecting an item in a second repository never shows the first
     * repository's files." Two distinct repos, two distinctly-named files,
     * selecting the second must show only its own titles.
     *
     * <p>The name is inherited from the deleted queue, where two arbitrary
     * repositories really could sit side by side. {@link SessionReviewScopes}
     * always mints both of a board's scopes against ONE checkout, so a
     * genuine second repository is not reachable here any more -- the two
     * scopes below are minted from different repos only because the switcher
     * takes any two {@link ReviewScope}s and this is the cheapest way to get
     * two that are diffably distinct. What still holds, and is what this
     * pins, is per-scope isolation of the file cursor across a chip switch -- the
     * same guarantee, exercised through the switcher's two slots rather than
     * a queue's rows.</p>
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

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scopeOne, Optional.of(scopeTwo)),
                SessionReviewScopes.Choice.LOCAL));
        awaitTarget("1/1 · Alpha.java");

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        awaitTarget("1/1 · Zulu.java");
    }

    /**
     * Step 7.3's addition: the switcher must not carry a finding minted
     * against one scope into the margin of the other. Synthetic findings
     * rather than a real diff -- what is under test is the margin's own
     * scoping, which does not depend on any diff having resolved at all.
     */
    @Test
    void switchingChipsDoesNotCarryFindingsAcrossScopes() {
        ReviewScope local = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/feature")),
                "main", "feature", Optional.empty(), Optional.empty()));
        ReviewScope pr = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.PR, Path.of("/repo"), Optional.of(Path.of("/wt/feature")),
                "main", "feature", Optional.of(new ReviewScope.PullRequestRef(9, Optional.empty())),
                Optional.empty()));
        host.addFinding(local, finding("A.java", 10, "local only"));
        host.addFinding(pr, finding("B.java", 20, "pr only"));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(local, Optional.of(pr)),
                SessionReviewScopes.Choice.LOCAL));
        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(List.of("pr only"), view.diagMarginFindingTitles(),
                "the PR chip must show only the PR's own finding, not the local scope's carried over");

        view.diagSelectChoice(SessionReviewScopes.Choice.LOCAL);

        assertEquals(List.of("local only"), view.diagMarginFindingTitles());
    }

    private ReviewAnnotation finding(String file, int line, String text) {
        return ReviewAnnotation.human("placeholder", file, "n" + line, "n" + line,
                new ReviewAnnotation.Message("Claude", Instant.EPOCH, text));
    }

    private String targetLabel() {
        String[] text = new String[1];
        interact(() -> text[0] = ((Label) lookup(".review-verdict-target").query()).getText());
        return text[0];
    }

    /** Polls the bar's label; the diff is a real git process, so it does not land at once. */
    private void awaitTarget(String expected) {
        for (int i = 0; i < 200; i++) {
            if (expected.equals(targetLabel())) {
                return;
            }
            sleep(25);
        }
        assertEquals(expected, targetLabel());
    }

    private static Path repoWithTwoChangedFiles() throws Exception {
        Path repo = Files.createDirectories(
                Files.createTempDirectory("drydock-isolation-repo").resolve("repo"));
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
