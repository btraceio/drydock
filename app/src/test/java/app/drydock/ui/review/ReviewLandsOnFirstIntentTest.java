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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>{@code setScope}'s same-id no-op still exists, but {@link
 * SessionReviewView#showScopes} never reaches it on a repeat call: {@link
 * SessionReviewView#renderSelectedScope} always resets the cursor, and for a
 * cached {@code Loaded} outcome {@code bodyFor} renders via {@code showDiff}
 * instead of {@code setScope} -- and {@code showDiff} nulls the column's live
 * scope, so even a LATER {@code setScope} call could not take the early
 * return either. The guard is bypassed, not removed: this test exercises the
 * exact branch ({@code bodyFor}'s cache path) that now does the bypassing.
 * What this pins is the guarantee the old trap broke: handing the board the
 * SAME scopes again must still land back on the first intent -- both the
 * cursor AND the column's rendered hunks, not merely "scrolled near the
 * top" (see the second assertion below) -- not leave the column wherever the
 * last read happened to stop.</p>
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

        List<String> rendered = renderedHunkFiles();
        assertTrue(rendered.stream().anyMatch(p -> p.endsWith("Alpha.java")),
                "showing the same scopes again lands on the first intent; rendered " + rendered);
        // Pins the intent-1 FILTER at the MODEL level, not merely "Alpha.java
        // happened to be materialized on screen". renderedHunkFiles() reads
        // the ListView's virtualized cells: a rebuild that scrolls an
        // UNFILTERED diff to the top (ReviewDiffColumn#rebuild's scrollTo(0),
        // which runs on every showDiff regardless of whether setIntent ever
        // narrowed anything) would already put Alpha.java on screen and leave
        // Zulu.java simply un-materialized -- indistinguishable, at the
        // rendered-label level, from a genuine narrow. diagRows() reads the
        // column's row model directly, so a HunkHeader naming Zulu.java shows
        // up here even when its cell was never realized on screen. Confirmed
        // by mutation (see task-6-report.md): deleting ONLY
        // renderSelectedScope's revealCurrentIntent() call does not fail this
        // assertion, because SessionReviewView's setOnDiffResolved handler
        // (registered in the constructor) also calls revealCurrentIntent()
        // and fires synchronously off this same showDiff -- deleting BOTH
        // call sites does fail it, with rows named
        // [alpha/Alpha.java, zulu/Zulu.java].
        List<String> modelFiles = narrowedFiles();
        assertFalse(modelFiles.stream().anyMatch(p -> p.endsWith("Zulu.java")),
                "showing the same scopes again must narrow the column back to intent 1's hunks, not merely "
                        + "scroll an unfiltered diff to the top; rows named " + modelFiles);
    }

    private List<String> renderedHunkFiles() {
        List<String> files = new ArrayList<>();
        interact(() -> lookup(".review-hunk-file").queryAll()
                .forEach(node -> files.add(((Label) node).getText())));
        return files;
    }

    /**
     * The files named by the diff column's row MODEL -- unlike {@link
     * #renderedHunkFiles()}, unaffected by which cells the virtualized {@code
     * ListView} happens to have materialized. This is what tells "narrowed to
     * one intent" apart from "showing the whole diff, scrolled so only the
     * first file's cells are realized" -- see the caller.
     */
    private List<String> narrowedFiles() {
        ReviewDiffColumn[] found = new ReviewDiffColumn[1];
        interact(() -> found[0] = (ReviewDiffColumn) lookup(".review-diff-column").query());
        return found[0].diagRows().stream()
                .filter(ReviewDiffRow.HunkHeader.class::isInstance)
                .map(ReviewDiffRow.HunkHeader.class::cast)
                .map(ReviewDiffRow.HunkHeader::file)
                .distinct()
                .toList();
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
