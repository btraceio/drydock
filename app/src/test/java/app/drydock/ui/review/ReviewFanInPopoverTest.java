package app.drydock.ui.review;

import app.drydock.ui.TestStages;
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
 * (spec §7.4): "called from 4 places outside the change" opens the same
 * occurrence popover the symbol lens uses, on a third source, and every row
 * names the file and line a reviewer would otherwise have to go and grep
 * for.
 *
 * <p><strong>A REAL scan, over a real repository.</strong> Before this task
 * {@code OutOfDiffFanIn.scan} had zero production callers -- every site
 * hardcoded an "unavailable" placeholder -- so a count was structurally
 * always absent and a popover over it could not exist. Nothing here is
 * stubbed for that reason: the board is pointed at a git repository this
 * test builds, and the counts come from the {@code git grep} the real board
 * spawns.</p>
 *
 * <p><strong>Two changed files, three changed symbols in one of them.</strong>
 * Not decoration. {@link #ZETA} declares three symbols with outside callers
 * and {@link #ALPHA} declares one with none, which is what makes three
 * distinct things assertable at all: that {@code bySymbol} comes out in the
 * graph's SORTED order rather than a hash order (fix round 1, item 2 -- with
 * a single symbol every map type iterates identically, so the determinism
 * test could not fail); that the scan REORDERS the reading path, since
 * fan-in is its first rank term; and that the reorder does not move the
 * reader (item 1).</p>
 *
 * <p><strong>Absent is not zero.</strong> Three scan outcomes are covered:
 * one that found callers, one that ran and found none, and one that could
 * not run. The middle and the last must not render the same, which is
 * exactly what a test asserting only "no zero is shown" would fail to
 * notice.</p>
 */
class ReviewFanInPopoverTest extends ApplicationTest {

    /** The changed file with three changed declarations, all used from outside. */
    private static final String ZETA = "src/Zeta.java";

    /** The changed file whose one declaration nothing outside uses. */
    private static final String ALPHA = "src/Alpha.java";

    /**
     * A third file, added mid-test only to displace {@link #ALPHA} as the
     * path's entry point -- so it has to sort BEFORE it. That is the shape
     * the defect needs: {@code togglePathMode} resets the cursor to 0 before
     * the refresh, so a stale re-anchor looks up the remembered ENTRY POINT,
     * and only notices if that hunk has moved.
     */
    private static final String AARDVARK = "src/Aardvark.java";

    /**
     * {@link #ZETA}'s declarations, in the order the popover must list them:
     * the graph's own sorted order. Chosen so a {@code HashMap} iterates
     * them DIFFERENTLY ({@code Astrolabe, Sextant, Compass}) -- otherwise
     * swapping the ordered map for a hashed one would leave every assertion
     * green, which is what happened when the fixture had one symbol.
     */
    private static final List<String> ZETA_SYMBOLS = List.of("Astrolabe", "Compass", "Sextant");

    /** Declared by {@link #ALPHA}; referenced nowhere outside the change. */
    private static final String ALPHA_SYMBOL = "AlphaOnly";

    /** Declared by a one-file diff, and referenced nowhere in the repository at all. */
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
        TestStages.show(stage, scene);
    }

    @AfterEach
    void tearDown() {
        interact(view::close);
        diffService.close();
        host.store.close();
    }

    // ---- the reading path moves; the reader must not ------------------------

    /**
     * The "before" this test class's reorder rests on, pinned without a
     * race: pointed at a directory git cannot grep, no fan-in ever arrives,
     * and {@link app.drydock.review.ReadingPath}'s remaining tie-breaks fall
     * through to the path -- so {@link #ALPHA} is step 1.
     */
    @Test
    void withNoFanInThePathFallsBackToPathOrder() {
        showRichBoard(notARepo);

        assertTrue(railTexts().get(0).contains(ALPHA),
                "with no fan-in the path order is alphabetical: " + railTexts());
    }

    /**
     * CRITICAL (fix round 1, item 1). Fan-in is {@code ReadingPath.rank}'s
     * FIRST term, so a scan landing mid-read re-sorts the rail under the
     * reader. {@code pathIndex} is a POSITION: left to clamping alone, the
     * cursor stays on index 0 while index 0 becomes a different hunk, the
     * diff column is re-narrowed to it, and -- since {@code settleUnit()} is
     * {@code PATH_STEP} unconditionally in this mode -- the reader's next
     * {@code a} approves a hunk they were never shown. That is the third
     * occurrence on this branch of one defect family: a gesture whose scope
     * silently stops matching what the reader sees.
     *
     * <p>The reader starts on step 1 ({@link #ALPHA}, per the test above).
     * The scan puts {@link #ZETA} first. They must still be on {@link
     * #ALPHA}.</p>
     */
    @Test
    void aScanThatReordersThePathKeepsTheReaderOnTheHunkTheyWereReading() {
        showRichBoard(repo);

        await("the scan to re-sort the path", () -> railTexts().get(0).contains(ZETA));

        List<String> rows = railTexts();
        assertEquals(2, rows.size(), "both changed files must be on the rail: " + rows);
        assertTrue(rows.get(view.selectedPathStepForTest()).contains(ALPHA),
                "the reader was reading " + ALPHA + "; after the re-sort the cursor is on row "
                        + view.selectedPathStepForTest() + " of " + rows);
    }

    /**
     * Fix round 3, item 1. The round-1 re-anchor introduced the very defect
     * it was written to prevent, one gesture over: {@code lastPathSteps}
     * survives LEAVING path mode (only {@code refreshReviewState}'s
     * {@code pathMode} branch writes it), so a path that changed while the
     * reader was away made {@code p}'s deliberate "start at the beginning"
     * lose to a re-anchor onto wherever the remembered hunk had gone -- the
     * cursor on row 3 with row 1 labelled START HERE.
     *
     * <p>Driven by a second DIFF rather than by a late scan, so nothing here
     * is a race: {@code requestGraph} is kicked from diff resolution
     * regardless of mode, which is the other way in, and this board's
     * worktree is one git cannot grep so no fan-in ever arrives to reorder
     * anything behind the test's back.</p>
     */
    @Test
    void reEnteringPathModeStartsAtTheEntryPointEvenIfThePathMovedWhileAway() {
        showRichBoard(notARepo);
        assertTrue(railTexts().get(0).contains(ALPHA), "the entry point starts as " + ALPHA);

        press(KeyCode.P).release(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(ReviewIntentRail.Mode.INTENTS, view.railMode(), "the reader left PATH mode");

        // A file that sorts FIRST lands while they are away, so the entry
        // point is no longer the hunk the stale memory holds -- which is
        // exactly what a re-anchor would chase, and where it would leave the
        // cursor while row 1 said START HERE.
        UnifiedDiff moved = new UnifiedDiff(List.of(
                oneHunkFile(AARDVARK, List.of("class AardvarkOnly { }")),
                oneHunkFile(ALPHA, List.of("class " + ALPHA_SYMBOL + " { }")),
                oneHunkFile(ZETA, ZETA_SYMBOLS.stream().map(n -> "class " + n + " { }").toList())));
        host.diff = moved;
        interact(() -> view.diagShowDiff(scope, moved));
        WaitForAsyncUtils.waitForFxEvents();

        press(KeyCode.P).release(KeyCode.P);
        await("the re-entered path to populate", () -> view.pathRowTextsForTest().size() == 3);

        assertEquals(0, view.selectedPathStepForTest(),
                "p means start at the beginning: " + railTexts());
        assertTrue(railTexts().get(view.selectedPathStepForTest()).contains("START HERE"),
                "the cursor must be on the row the rail calls START HERE: " + railTexts());
    }

    // ---- the popover --------------------------------------------------------

    @Test
    void clickingTheFanInCountListsTheCallersWithFileAndLine() {
        showRichBoard(repo);
        awaitFanInCount();

        int reading = view.selectedPathStepForTest();
        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        // Asking to see the callers is not asking to move the cursor: the
        // fan-in ActionEvent BUBBLES to the row Button that contains it, and
        // un-consumed it selects that row and re-narrows the diff column
        // under the reader.
        assertEquals(reading, view.selectedPathStepForTest(),
                "opening the popover must not move the reading cursor");
        List<String> texts = popoverTexts();
        assertTrue(texts.stream().anyMatch(text -> text.matches("src/Caller\\.java:\\d+")),
                "the popover must name the caller's file AND line: " + texts);
        assertTrue(texts.stream().anyMatch(text -> text.matches("src/More\\.java:\\d+")),
                "every caller, not just the first: " + texts);
        assertTrue(texts.stream().noneMatch(text -> text.startsWith(ZETA + ":")),
                "the changed file is not OUTSIDE the change: " + texts);
    }

    /** No new interaction is invented: it is the same popover on a third source. */
    @Test
    void thePopoverOffersUsagesAndAskTheAgent() {
        showRichBoard(repo);
        awaitFanInCount();

        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        List<String> texts = popoverTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("usages")),
                "the list IS the usages view and says so: " + texts);
        assertTrue(texts.stream().anyMatch(text -> text.contains("agent")),
                "a lexical list cannot say whether a caller breaks; the reader must be one "
                        + "click from the party that can: " + texts);
        // The button says what it DOES. A reviewer who is not told finds a
        // review comment they did not knowingly write.
        assertTrue(texts.stream().anyMatch(text -> text.contains("agent") && text.contains("comment")),
                "the ask button must say it files a comment: " + texts);
    }

    /**
     * The ask is routed through the two seams that already exist -- the
     * comment store and the bound session -- and the question names the file,
     * so the agent is not left to guess which one.
     */
    @Test
    void askingTheAgentPostsAQuestionPointedAtTheRightFile() {
        showRichBoard(repo);
        awaitFanInCount();
        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(".review-fanin-ask");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, host.findings(scope).size(), "the question must become a real thread");
        String body = host.findings(scope).get(0).thread().get(0).text();
        assertTrue(body.contains(ZETA), "the question must name the file: " + body);
        assertTrue(ZETA_SYMBOLS.stream().allMatch(body::contains),
                "the question must name the symbols: " + body);
        assertEquals(ZETA, host.findings(scope).get(0).file());
        assertEquals(1, host.handedOffPrompts.size(),
                "the question must reach the bound session, not just the store");
        assertFalse(popoverShowing(), "a hand-off that worked closes the popover");
    }

    /**
     * Fix round 1, item 5. {@code askAgentToFix} was {@code void} and the
     * boolean under it was discarded: with no bound session the popover
     * closed, a persistent OPEN comment authored as "You" was filed, nothing
     * was sent, and the reviewer was told nothing. That is the shape Ruling 1
     * legislated against for the Explorer jump, on the second button.
     */
    @Test
    void anAskWithNoBoundSessionSaysSoRatherThanClosingOnSilence() {
        host.sessionBound = false;
        showRichBoard(repo);
        awaitFanInCount();
        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn(".review-fanin-ask");
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(host.handedOffPrompts.isEmpty(), "nothing can be sent with no session");
        assertTrue(popoverShowing(), "the popover must stay open to report it");
        assertTrue(popoverTexts().stream().anyMatch(text -> text.contains("nothing was sent")),
                "the reviewer must be told nothing was sent: " + popoverTexts());
    }

    /** Escape unwinds the topmost thing; this popover is now the topmost thing. */
    @Test
    void escapeClosesTheFanInPopover() {
        showRichBoard(repo);
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
        showRichBoard(repo);
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
        showRichBoard(repo);
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
        showRichBoard(repo);
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

    /**
     * Fix round 1, item 2 (folded-in M1). {@code diagPathRowTexts} recursed
     * {@code getChildrenUnmodifiable()}, but a fan-in row's reason Label is
     * now the GRAPHIC of a nested Button, and a Labeled's graphic becomes one
     * of its children only once its skin exists -- a layout pulse away. The
     * reviewer caught the window live: a row rendered with no reason text at
     * all for ~80ms. Every rail-text assertion is timing-dependent while that
     * is true, and an {@code assertFalse(anyMatch(...))} can pass because the
     * text has not been parented yet rather than because it is absent.
     *
     * <p>Read inside the SAME {@code interact} that rebuilds the rail, so no
     * layout pulse can intervene: this is the worst case by construction
     * rather than by luck.</p>
     */
    @Test
    void theRailAccessorReadsAFanInRowsReasonInThePulseItIsBuilt() {
        showRichBoard(repo);
        awaitFanInCount();

        List<String> freshlyBuilt = new ArrayList<>();
        interact(() -> {
            view.refreshReviewState();
            freshlyBuilt.addAll(view.pathRowTextsForTest());
        });

        assertTrue(freshlyBuilt.stream().anyMatch(row -> row.contains("places outside the change")),
                "a fan-in row's reason must be readable the moment the row exists: " + freshlyBuilt);
    }

    /**
     * The reason WRAPS inside the fan-in control rather than being cut to one
     * line and ellipsized.
     *
     * <p>Found by a screenshot of the running app, not by a test: the rail's
     * only row read "file called from 16 places outside the…". A wrapping
     * Label wraps at the width it is given, and as a Button's GRAPHIC it is
     * given its own one-line preferred width instead of the card's -- so the
     * button cut it and the Label rendered an ellipsis. This project has
     * shipped that truncation once already ("R..", "...").</p>
     *
     * <p>Geometry, not computed CSS: a sentence this long cannot occupy one
     * line at the rail's width, so a single-line height IS the defect.</p>
     */
    @Test
    void theFanInReasonWrapsInsteadOfBeingCutToOneLine() {
        showRichBoard(repo);
        awaitFanInCount();
        // Narrowed on purpose. At the rail's full width this particular
        // sentence happens to fit on one line (189px of a 190px slot), and a
        // test that only ever measures the case that fits cannot see the
        // defect at all -- which is exactly why the running app showed it
        // first and this test did not.
        interact(() -> view.getScene().getWindow().setWidth(1050));
        WaitForAsyncUtils.waitForFxEvents();

        Node reason = lookup(".review-fanin-count").query().lookup(".review-path-reason");
        double height = reason.getBoundsInLocal().getHeight();
        double lineHeight = ((Labeled) reason).getFont().getSize();
        assertTrue(height > lineHeight * 1.6,
                "the reason is " + Math.round(height) + "px tall at a " + Math.round(lineHeight)
                        + "px font -- one line, so it was cut rather than wrapped: \""
                        + ((Labeled) reason).getText() + "\"");
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
        showLonelyBoard(repo);
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
        showLonelyBoard(notARepo);
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
        showRichBoard(repo);
        awaitFanInCount();

        assertEquals("drydock-section-graph", view.diagFanInScanThread(),
                "the scan must run on the section-graph executor, never on the FX thread");
    }

    /**
     * Determinism is a requirement on this branch, not a property (spec
     * §9.5). The popover walks the graph's SORTED declarations rather than
     * the scan's own map, so the same scan renders the same list in the same
     * order every time -- across runs and across processes, not merely
     * twice in one.
     *
     * <p>{@link #ZETA_SYMBOLS} is asserted as a LIST, and its members are
     * chosen so a {@code HashMap} would iterate them as {@code Astrolabe,
     * Sextant, Compass}. That is what makes this test able to fail: with the
     * one-symbol fixture it started life with, every map type iterated
     * identically and swapping the ordered map for a hashed one left the
     * whole suite green.</p>
     */
    @Test
    void thePopoverListsEverySymbolInTheGraphsSortedOrder() {
        showRichBoard(repo);
        awaitFanInCount();

        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();
        List<String> first = symbolRows();
        List<String> firstRows = whereRows();
        interact(view::unwindOne);

        clickOn(".review-fanin-count");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(ZETA_SYMBOLS, first,
                "the popover must list every changed symbol of this file, in sorted order");
        assertEquals(first, symbolRows(), "and identically on a second opening");
        assertEquals(firstRows, whereRows(), "occurrence rows too");
        assertFalse(firstRows.isEmpty(), "there is nothing to compare if nothing rendered");
    }

    // ---- board ---------------------------------------------------------------

    /**
     * The two-file board: {@link #ALPHA} (one declaration, no outside users)
     * and {@link #ZETA} (three declarations, all used from outside).
     */
    private void showRichBoard(Path worktree) {
        List<String> zetaLines = ZETA_SYMBOLS.stream().map(name -> "class " + name + " { }").toList();
        showBoard(worktree, new UnifiedDiff(List.of(
                oneHunkFile(ALPHA, List.of("class " + ALPHA_SYMBOL + " { }")),
                oneHunkFile(ZETA, zetaLines))));
    }

    /** A one-file board declaring a symbol nothing in the repository references. */
    private void showLonelyBoard(Path worktree) {
        showBoard(worktree, new UnifiedDiff(List.of(
                oneHunkFile(ALPHA, List.of("class " + LONELY_SYMBOL + " { }")))));
    }

    /**
     * Shows a board whose scope is checked out at {@code worktree}, then
     * enters PATH mode -- where the reading path's reasons, and so the fan-in
     * count, live.
     */
    private void showBoard(Path worktree, UnifiedDiff diff) {
        // Asserted, not assumed: TestFX's primary stage is shared by every
        // class in this JVM, and a class that leaves it at the code column's
        // floor (ReviewVerdictBarFitTest does, deliberately) collapses this
        // rail before the first test here even runs -- the fan-in control is
        // then present and invisible, which reads as a broken lookup.
        interact(() -> {
            view.getScene().getWindow().setWidth(1400);
            view.getScene().getWindow().setHeight(900);
        });
        WaitForAsyncUtils.waitForFxEvents();
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                worktree, Optional.of(worktree), "main", "HEAD",
                Optional.empty(), Optional.empty()));
        host.diff = diff;
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        // PATH mode BEFORE the diff, deliberately, and this ordering is what
        // makes the re-sort test deterministic rather than a race: the
        // reader is already in PATH mode when the graph lands, so the rail
        // necessarily renders the pre-scan order first (the scan is only
        // KICKED OFF by the graph's own completion) and the scan's own
        // refresh is necessarily the second one. Publishing the diff first
        // let both land before `p` was ever pressed, and the test then
        // asserted against a cursor that had never been anywhere.
        press(KeyCode.P).release(KeyCode.P);
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> view.diagShowDiff(scope, diff));
        WaitForAsyncUtils.waitForFxEvents();
        await("PATH mode to populate its rows", () -> !view.pathRowTextsForTest().isEmpty());
    }

    private static UnifiedDiff.FileDiff oneHunkFile(String path, List<String> added) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        int number = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                    OptionalInt.of(number++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", added.size(), 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1 +1 @@", lines)));
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
                collectText(popup.getScene().getRoot(), texts, null);
            }
        }));
        return texts;
    }

    /** Just the symbol headings, in rendered order. */
    private List<String> symbolRows() {
        List<String> texts = new ArrayList<>();
        interact(() -> openPopups().forEach(popup -> {
            if (popup.getScene() != null) {
                collectText(popup.getScene().getRoot(), texts, "review-fanin-symbol");
            }
        }));
        return texts;
    }

    /** Just the {@code file:line} rows, in rendered order. */
    private List<String> whereRows() {
        List<String> texts = new ArrayList<>();
        interact(() -> openPopups().forEach(popup -> {
            if (popup.getScene() != null) {
                collectText(popup.getScene().getRoot(), texts, "review-lens-where");
            }
        }));
        return texts;
    }

    private java.util.stream.Stream<PopupWindow> openPopups() {
        return Window.getWindows().stream()
                .filter(PopupWindow.class::isInstance)
                .map(PopupWindow.class::cast)
                .filter(Window::isShowing);
    }

    /** Depth-first, so the collected order is the rendered order. */
    private static void collectText(Node node, List<String> into, String styleClass) {
        if (node instanceof Labeled labeled && labeled.getText() != null
                && !labeled.getText().isBlank()
                && (styleClass == null || labeled.getStyleClass().contains(styleClass))) {
            into.add(labeled.getText());
        }
        if (node instanceof Labeled labeled && labeled.getGraphic() != null) {
            collectText(labeled.getGraphic(), into, styleClass);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (!(node instanceof Labeled labeled) || child != labeled.getGraphic()) {
                    collectText(child, into, styleClass);
                }
            }
        }
    }

    // ---- a real repository ---------------------------------------------------

    /**
     * A committed repository where {@link #ZETA}'s three declarations are
     * used from two files the diff does not touch -- the shape the whole
     * feature exists for: a public-API change whose callers are invisible to
     * a diff-scoped graph. {@link #ALPHA}'s one declaration is used nowhere
     * outside, so exactly one of the two rail rows gets a fan-in control.
     */
    private static Path initRepoWithOutsideCallers(Path parent)
            throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve(ALPHA), "class " + ALPHA_SYMBOL + " { }\n");
        Files.writeString(repo.resolve(ZETA), ZETA_SYMBOLS.stream()
                .map(name -> "class " + name + " { }\n")
                .reduce("", String::concat));
        StringBuilder caller = new StringBuilder("class Caller {\n");
        for (String symbol : ZETA_SYMBOLS) {
            caller.append("  void use").append(symbol).append("() { new ")
                    .append(symbol).append("(); }\n");
        }
        Files.writeString(repo.resolve("src/Caller.java"), caller.append("}\n").toString());
        // A second caller of exactly one symbol, so the popover has a symbol
        // with two occurrences beside two with one -- a count that is not
        // simply "one per symbol".
        Files.writeString(repo.resolve("src/More.java"),
                "class More {\n  void again() { new " + ZETA_SYMBOLS.get(2) + "(); }\n}\n");
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
