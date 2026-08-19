package app.drydock.ui.review;

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
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(worktree, Optional.of(gate)),
                SessionReviewScopes.Choice.LOCAL));

        awaitCardCount(2);

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        WaitForAsyncUtils.waitForFxEvents();

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

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(worktree, Optional.of(gate)),
                SessionReviewScopes.Choice.LOCAL));
        awaitCardCount(2);

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        WaitForAsyncUtils.waitForFxEvents();
        view.diagSelectChoice(SessionReviewScopes.Choice.LOCAL);

        awaitCardCount(2);
    }

    /**
     * The reported defect: {@code refreshReviewState}'s early return for "no
     * scope selected" cleared the margin and the verdict bar but never the
     * rail, so a rescan that emptied the queue left the previous scope's
     * cards on screen -- a dead click describing an item no longer queued.
     *
     * <p>The queue that used to empty is gone with {@code ReviewDestinationView};
     * the board now loses its scope the same way the two placeholder states
     * do -- {@link SessionReviewView#showResolving()} -- which runs through
     * the exact same {@code refreshReviewState} early return this guards.</p>
     */
    @Test
    void theRailClearsWhenTheScopeIsLost() throws Exception {
        Path repo = repoWithTwoChangedFiles();
        ReviewScope worktree = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, repo, Optional.of(repo), "main", "main",
                Optional.empty(), Optional.empty()));

        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(worktree, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        awaitCardCount(2);

        interact(view::showResolving);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(0, cardCount(),
                "losing the scope must clear the rail, not keep the departed scope's cards");
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
     * pins, is per-scope isolation of the rail across a chip switch -- the
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
        awaitCardCount(1);
        assertEquals(List.of("Alpha.java"), cardTitles());

        view.diagSelectChoice(SessionReviewScopes.Choice.PULL_REQUEST);
        awaitCardCount(1);
        List<String> titles = cardTitles();
        assertEquals(List.of("Zulu.java"), titles);
        assertFalse(titles.contains("Alpha.java"), "the second repository must not carry the first's files");
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
