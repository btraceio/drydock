package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessRunner;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The out-of-diff fan-in count, as an affordance rather than a statistic
 * (spec §7.4): "called from 3 places outside the change" opens the same
 * occurrence popover the symbol lens uses, on a third source, and every row
 * names the file and line a reviewer would otherwise have to go and grep
 * for.
 *
 * <p><strong>A REAL scan, over a real repository.</strong> Before this task
 * {@code OutOfDiffFanIn.scan} had zero production callers -- every site
 * hardcoded an "unavailable" placeholder -- so a count was structurally
 * always absent and a popover over it could not exist. Nothing here is
 * stubbed for that reason: the board is pointed at a git repository this
 * test builds, and the counts come from the {@code git grep} the real
 * board spawns. Reverting the wiring to the old placeholder fails
 * {@link #aScanThatRanAndFoundNothingIsNotAnUnavailableScan} and
 * {@link #clickingTheFanInCountListsTheCallersWithFileAndLine} at once.</p>
 *
 * <p><strong>Absent is not zero.</strong> The three scope variants below are
 * the whole point of the class: a scan that found callers, a scan that ran
 * and found none, and a scan that could not run. The middle and the last
 * must not render the same, which is exactly what a test asserting only
 * "no zero is shown" would fail to notice.</p>
 */
class ReviewFanInPopoverTest extends ApplicationTest {

    /** The changed file, and the only one the diff carries. */
    private static final String CHANGED_FILE = "src/Guards.java";

    /** What the change declares, and what the unchanged files below use. */
    private static final String SYMBOL = "JmpCtxScope";

    /** Declared by the change, referenced nowhere in the repository. */
    private static final String LONELY_SYMBOL = "TotallyAbsentSymbolXyz";

    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final DiffService diffService = new DiffService();

    private FakeReviewHost host;
    private SessionReviewView view;
    private Path repo;
    private Path notARepo;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-fanin")
                    .resolve("annotations.json"));
            Path parent = Files.createTempDirectory("drydock-fanin-repo");
            repo = initRepoWithOutsideCallers(parent);
            notARepo = Files.createDirectories(parent.resolve("plain-directory"));
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
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        interact(view::close);
        diffService.close();
        host.store.close();
    }

    // ---- the popover --------------------------------------------------------

    @Test
    void clickingTheFanInCountListsTheCallersWithFileAndLine() {
        showBoard(repo, SYMBOL);
        awaitFanInCount();

        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        List<String> texts = popoverTexts();
        assertTrue(texts.stream().anyMatch(text -> text.matches("src/Other\\.java:\\d+")),
                "the popover must name the caller's file AND line: " + texts);
        assertTrue(texts.stream().anyMatch(text -> text.matches("src/More\\.java:\\d+")),
                "every caller, not just the first: " + texts);
        assertTrue(texts.stream().noneMatch(text -> text.startsWith(CHANGED_FILE + ":")),
                "the changed file is not OUTSIDE the change: " + texts);
    }

    /** No new interaction is invented: it is the same popover on a third source. */
    @Test
    void thePopoverOffersUsagesAndAskTheAgent() {
        showBoard(repo, SYMBOL);
        awaitFanInCount();

        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        List<String> texts = popoverTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("usages")),
                "the list IS the usages view and says so: " + texts);
        assertTrue(texts.stream().anyMatch(text -> text.contains("agent")),
                "a lexical list cannot say whether a caller breaks; the reader must be one "
                        + "click from the party that can: " + texts);
    }

    /**
     * The ask is routed through the two seams that already exist -- the
     * comment store and the bound session -- and the question names the file,
     * so the agent is not left to guess which one.
     */
    @Test
    void askingTheAgentPostsAQuestionPointedAtTheRightFile() {
        showBoard(repo, SYMBOL);
        awaitFanInCount();
        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(".review-fanin-ask");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, host.findings(scope).size(), "the question must become a real thread");
        String body = host.findings(scope).get(0).thread().get(0).text();
        assertTrue(body.contains(CHANGED_FILE), "the question must name the file: " + body);
        assertTrue(body.contains(SYMBOL), "the question must name the symbol: " + body);
        assertEquals(CHANGED_FILE, host.findings(scope).get(0).file());
        assertEquals(1, host.handedOffPrompts.size(),
                "the question must reach the bound session, not just the store");
        assertFalse(popoverShowing(), "asking closes the popover");
    }

    /** Escape unwinds the topmost thing; this popover is now the topmost thing. */
    @Test
    void escapeClosesTheFanInPopover() {
        showBoard(repo, SYMBOL);
        awaitFanInCount();
        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(popoverShowing());

        boolean[] unwound = new boolean[1];
        interact(() -> unwound[0] = view.unwindOne());

        assertTrue(unwound[0], "Escape must be handled by the open fan-in popover");
        assertFalse(popoverShowing());
    }

    /**
     * The Explorer lives inside a session's tab, so the jump can legitimately
     * fail. It must say so: a row that reports nothing when it does nothing
     * is the silent-failure shape this branch has already had to fix twice.
     */
    @Test
    void aRefusedExplorerJumpSaysSoInThePopover() {
        host.explorerAvailable = false;
        showBoard(repo, SYMBOL);
        awaitFanInCount();
        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(".review-lens-line");
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(popoverTexts().stream().anyMatch(text -> text.startsWith("Could not open ")),
                "a refused jump must be reported: " + popoverTexts());
        assertTrue(popoverShowing(), "the popover stays open to carry the message");
    }

    @Test
    void anAcceptedExplorerJumpOpensTheOutsideFile() {
        host.explorerAvailable = true;
        showBoard(repo, SYMBOL);
        awaitFanInCount();
        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(".review-lens-line");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, host.explorerJumps.size(), "the jump must reach the Explorer");
        assertTrue(host.explorerJumps.get(0).toString().startsWith("src/"),
                "and at the file the row names: " + host.explorerJumps);
        assertFalse(popoverShowing(), "a jump that worked closes the popover");
    }

    /**
     * The rail row is a card, and a card is tens of pixels tall. The fan-in
     * control wraps the reason Label inside the row Button, which is exactly
     * the nesting that once made a wrapping label measure itself at zero
     * width and report the height of a column of single characters (see
     * {@code ReviewIntentRailCardHeightTest}).
     */
    @Test
    void theFanInRowStaysCardSized() {
        showBoard(repo, SYMBOL);
        awaitFanInCount();

        double height = lookup(".review-fanin-count").query().getScene().getRoot()
                .lookupAll(".review-intent-card").stream()
                .filter(node -> !node.lookupAll(".review-fanin-count").isEmpty())
                .mapToDouble(node -> node.getBoundsInParent().getHeight())
                .max()
                .orElse(0);
        assertTrue(height > 0 && height < 220,
                "the fan-in row is " + Math.round(height) + "px tall; rows are tens of pixels");
    }

    // ---- absent is not zero -------------------------------------------------

    /**
     * The distinction the whole affordance rests on. Both scopes below show
     * no count -- but only one of them may claim the outside is quiet.
     *
     * <p>This is the test that cannot be written vacuously: it fails if the
     * scan is not wired (a never-run scan reports unknown, so the "ran and
     * found nothing" assertion below never comes true and this times out),
     * and it fails if {@code unavailable()} is folded into "zero" (both
     * scopes would then read identically).</p>
     */
    @Test
    void aScanThatRanAndFoundNothingIsNotAnUnavailableScan() {
        showBoard(repo, LONELY_SYMBOL);
        await("the scan to report an empty-but-available answer",
                () -> railTexts().stream().noneMatch(text -> text.contains("outside callers unknown")));

        List<String> ran = railTexts();
        assertTrue(ran.stream().anyMatch(text -> text.contains("nothing in the change references it")),
                "a scan that ran and found nothing still says the change is self-contained: " + ran);
        assertTrue(lookup(".review-fanin-count").queryAll().isEmpty(),
                "zero places outside is no affordance, not a zero-count button");
    }

    @Test
    void anUnavailableScanShowsNoCountRatherThanZero() {
        showBoard(notARepo, SYMBOL);
        await("the scan to fail against a directory git cannot grep",
                () -> railTexts().stream().anyMatch(text -> text.contains("outside callers unknown")));

        List<String> texts = railTexts();
        assertFalse(texts.stream().anyMatch(text -> text.contains("0 places outside")),
                "a scan that could not run must not render as a measured zero: " + texts);
        assertFalse(texts.stream().anyMatch(text -> text.contains("places outside the change")),
                "nor as any count at all: " + texts);
        assertTrue(lookup(".review-fanin-count").queryAll().isEmpty(),
                "and there is nothing to click, because nothing was measured");
    }

    // ---- the scan itself ----------------------------------------------------

    /**
     * {@code OutOfDiffFanIn.scan} spawns a {@code git grep} and waits up to
     * thirty seconds for it. Asserted, not assumed: {@code Sections.of} on
     * the FX thread already froze this board for ~2.7 seconds once, and a
     * subprocess there would be far worse.
     */
    @Test
    void theScanNeverRunsOnTheFxThread() {
        showBoard(repo, SYMBOL);
        awaitFanInCount();

        assertEquals("drydock-section-graph", view.diagFanInScanThread(),
                "the scan must run on the section-graph executor, never on the FX thread");
    }

    /**
     * Determinism is a requirement on this branch, not a property (spec
     * §9.5). The popover walks the graph's sorted declarations rather than
     * the scan's own map, so the same scan renders the same list every time
     * it is opened.
     */
    @Test
    void thePopoverListsTheSameCallersInTheSameOrderEveryTime() {
        showBoard(repo, SYMBOL);
        awaitFanInCount();

        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();
        List<String> first = whereRows();
        interact(view::unwindOne);

        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();
        List<String> second = whereRows();

        assertEquals(first, second);
        assertFalse(first.isEmpty(), "there is nothing to compare if nothing rendered");
    }

    // ---- board ---------------------------------------------------------------

    /**
     * Shows a board whose scope is checked out at {@code worktree} and whose
     * one changed file declares {@code declared}, then enters PATH mode --
     * where the reading path's reasons, and so the fan-in count, live.
     */
    private void showBoard(Path worktree, String declared) {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                worktree, Optional.of(worktree), "main", "HEAD",
                Optional.empty(), Optional.empty()));
        UnifiedDiff diff = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff(
                CHANGED_FILE, "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                                OptionalInt.of(1), "class " + declared + " { }")))))));
        host.diff = diff;
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, diff));
        WaitForAsyncUtils.waitForFxEvents();
        press(KeyCode.P).release(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
        await("PATH mode to populate its rows", () -> !view.pathRowTextsForTest().isEmpty());
    }

    private List<String> railTexts() {
        return view.pathRowTextsForTest();
    }

    private void awaitFanInCount() {
        await("the fan-in scan to land a clickable count",
                () -> !lookup(".review-fanin-count").queryAll().isEmpty());
    }

    /**
     * Polls wall time, as {@code ReviewPathModeTest.awaitPathReady} does: the
     * graph build and the {@code git grep} behind it both run on virtual
     * threads, and how long they take depends on whether this JVM has
     * already warmed the tree-sitter grammar.
     */
    private void await(String what, BooleanSupplier condition) {
        long start = System.nanoTime();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start > 60_000_000_000L) {
                throw new AssertionError("timed out waiting for " + what
                        + "; rail said: " + railTexts());
            }
            sleep(50);
        }
    }

    // ---- the popover, as rendered -------------------------------------------

    private boolean popoverShowing() {
        boolean[] showing = new boolean[1];
        interact(() -> showing[0] = openPopups().findAny().isPresent());
        return showing[0];
    }

    /**
     * Every {@link Labeled}'s text in the open popover. Read off the popup's
     * own scene root rather than {@code PopupWindow.getContent()}, which is
     * not public outside {@code javafx.stage}.
     */
    private List<String> popoverTexts() {
        List<String> texts = new ArrayList<>();
        interact(() -> openPopups().forEach(popup -> {
            if (popup.getScene() != null) {
                collectText(popup.getScene().getRoot(), texts);
            }
        }));
        return texts;
    }

    /** Just the {@code file:line} rows, in rendered order. */
    private List<String> whereRows() {
        return popoverTexts().stream().filter(text -> text.matches("[^\\s]+:\\d+")).toList();
    }

    private java.util.stream.Stream<PopupWindow> openPopups() {
        return Window.getWindows().stream()
                .filter(PopupWindow.class::isInstance)
                .map(PopupWindow.class::cast)
                .filter(Window::isShowing);
    }

    private static void collectText(Node node, List<String> into) {
        if (node instanceof Labeled labeled && labeled.getText() != null
                && !labeled.getText().isBlank()) {
            into.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, into);
            }
        }
    }

    // ---- a real repository ---------------------------------------------------

    /**
     * A committed repository where {@link #SYMBOL} is declared in the changed
     * file and used from two files the diff does not touch -- the shape the
     * whole feature exists for: a public-API change whose callers are
     * invisible to a diff-scoped graph.
     */
    private static Path initRepoWithOutsideCallers(Path parent)
            throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Guards.java"), "class " + SYMBOL + " { }\n");
        Files.writeString(repo.resolve("src/Other.java"),
                "class Other {\n  void a() { new " + SYMBOL + "(); }\n}\n");
        Files.writeString(repo.resolve("src/More.java"),
                "class More {\n  void b() { new " + SYMBOL + "(); }\n}\n");
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
