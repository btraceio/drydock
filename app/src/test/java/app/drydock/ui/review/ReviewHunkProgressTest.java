package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SessionReviewScopes;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the rail and the verdict bar actually RENDER once progress is counted
 * in hunks (spec §5.6): the progress label, the settled card, the marker for
 * a hunk settled in a neighbouring section, the stale banner, and the
 * keyboard and Submit paths that walk sections.
 *
 * <p>The derivation behind all of it -- merge rules, counts, staleness,
 * adrift groupings -- is {@link SectionStatesTest}, which needs no {@code
 * Stage}. What is here is only what needs a rendered board.</p>
 *
 * <p>This also re-pins the two assertions {@code ReviewCarriedOverVerdictTest}
 * held before it was deleted with its subject: that a settled card carries
 * the rail's {@code settled} style class, and that the verdict bar's progress
 * label reads the count out. Both were the only coverage of their surface.</p>
 */
class ReviewHunkProgressTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SessionReviewView view;
    private ReviewScope scope;

    /** Three files, one hunk each, so a digest is addressable by its file alone. */
    private static final String GUARDS_H = "src/guards.h";
    private static final String GUARDS_CPP = "src/guards.cpp";
    private static final String PROFILER = "src/profiler.cpp";

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-progress")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        host.diff = new UnifiedDiff(List.of(
                file(GUARDS_H, "class JmpCtxScope;"),
                file(GUARDS_CPP, "void install();"),
                file(PROFILER, "resolve();")));
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

    // ---- the verdict bar counts distinct hunks ------------------------------

    /**
     * Two sections that share {@code guards.h}: four section slots over three
     * hunks. Anything summing section sizes reads 4 here.
     */
    @Test
    void progressCountsDistinctHunksNotSectionSlots() {
        showOverlappingSections();

        assertEquals("0/3 hunks reviewed", progressText());
    }

    @Test
    void settlingASharedHunkAdvancesProgressExactlyOnce() {
        showOverlappingSections();

        approve(GUARDS_H);

        assertEquals("1/3 hunks reviewed", progressText(),
                "a hunk in two sections is one flag, not two");
    }

    @Test
    void everyHunkSettledReadsAsComplete() {
        showOverlappingSections();

        approve(GUARDS_H);
        approve(GUARDS_CPP);
        approve(PROFILER);

        assertEquals("3/3 hunks reviewed", progressText());
    }

    // ---- re-pinned: the rail's settled card ---------------------------------

    /**
     * The {@code settled} style class is what dims a card. Deleted along with
     * {@code ReviewCarriedOverVerdictTest}; nothing else asserts it.
     */
    @Test
    void aSettledSectionDimsItsCard() {
        showOverlappingSections();
        assertEquals(0, settledCardCount(), "nothing is settled before a verdict");

        approve(GUARDS_H);
        approve(GUARDS_CPP);

        assertEquals(1, settledCardCount(),
                "the section whose every hunk is settled dims; the other does not");
    }

    /**
     * Settling section ① settles a hunk section ② also contains. Without
     * saying where, ②'s count changes with no visible cause.
     */
    @Test
    void aHunkSettledElsewhereSaysWhereItWasSettled() {
        showOverlappingSections();

        approve(GUARDS_H);
        approve(GUARDS_CPP);

        assertTrue(railText().contains("✓ reviewed in ①"),
                "the rail must name the section that settled it, got: " + railText());
    }

    /** A settled card explains itself with its own verdict; the marker would be noise. */
    @Test
    void aFullySettledCardDoesNotAlsoPointElsewhere() {
        showOverlappingSections();

        approve(GUARDS_H);
        approve(GUARDS_CPP);

        assertFalse(railText().contains("✓ reviewed in ②"),
                "settled section ① must not point at ②, got: " + railText());
    }

    // ---- verdicts are keyed by a real digest --------------------------------

    @Test
    void approvingASectionRecordsOneVerdictPerHunkKeyedByItsDigest() {
        showOverlappingSections();

        clickOn(".review-verdict-action");
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_H)).isPresent(),
                "a verdict must be keyed by the hunk's content digest");
        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_CPP)).isPresent());
        assertTrue(host.store.verdict(scope.id(), "section-1").isEmpty(),
                "no verdict may be keyed by an intent id");
    }

    /** {@code u} undoes every hunk of the section it settled, not just one. */
    @Test
    void undoingASectionClearsEveryHunkItSettled() {
        showOverlappingSections();
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        press(KeyCode.U).release(KeyCode.U);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(host.store.verdictsFor(scope.id()).isEmpty(),
                "undo must clear the whole section it settled");
    }

    // ---- the stale banner ---------------------------------------------------

    @Test
    void aBaseMoveTouchingTheSectionBannersIt() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        showOverlappingSections();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        recordAgainstBase(GUARDS_CPP, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED, view.diagSectionState(0).staleness());
        assertTrue(railLabels(".review-intent-stale").contains("⚠ base moved — confirm"));
    }

    /**
     * While the delta is still being computed -- or the old base can no
     * longer be diffed -- nothing is known, and nothing may be claimed. A
     * confirm-me banner on every settled card of a review nobody touched is
     * worse than no banner: it trains the reader to click it reflexively.
     */
    @Test
    void anUnresolvableDeltaSaysNothingRatherThanWarning() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        showOverlappingSections();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        recordAgainstBase(GUARDS_CPP, "0".repeat(40));

        assertEquals(SectionStates.Staleness.UNKNOWN, view.diagSectionState(0).staleness(),
                "an unanswered question is not a finding");
        assertTrue(railLabels(".review-intent-stale").isEmpty(),
                "no card may warn about a move nothing established");
    }

    /**
     * A stale verdict does not count toward "everything settled" (spec
     * §9.2): Submit must refuse it rather than post a decision nobody has
     * actually confirmed against the code as it stands now.
     */
    @Test
    void submitRefusesWhileTheCurrentSectionIsStale() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        showOverlappingSections();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        recordAgainstBase(GUARDS_CPP, "0".repeat(40));

        press(KeyCode.ENTER).release(KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(host.submittedScopes.isEmpty(), "a stale approval must not be posted silently");
        assertTrue(labels(".review-verdict-submit-refusal").stream()
                        .anyMatch(text -> text.contains("older base")),
                "the reader must be told why submit did nothing");
    }

    /**
     * "Confirm still good" keeps the decision and rewrites its recorded
     * base, so the section reads fresh again without a second read.
     */
    @Test
    void confirmStillGoodRewritesTheBaseAndClearsTheBanner() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        showOverlappingSections();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        recordAgainstBase(GUARDS_CPP, "0".repeat(40));
        assertEquals(SectionStates.Staleness.MOVED, view.diagSectionState(0).staleness());

        interact(() -> ((Button) lookup(".review-verdict-confirm-stale").query()).fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(SectionStates.Staleness.FRESH, view.diagSectionState(0).staleness());
        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_H))
                        .map(v -> v.decision() == ReviewVerdict.Decision.APPROVED).orElse(false),
                "confirm still good must keep the decision, not clear it");
    }

    /** "Re-review" is the other answer: it clears the stale verdicts entirely. */
    @Test
    void reReviewClearsTheStaleVerdicts() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        showOverlappingSections();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        recordAgainstBase(GUARDS_CPP, "0".repeat(40));

        interact(() -> ((Button) lookup(".review-verdict-re-review").query()).fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_H)).isEmpty(),
                "re-review must clear the stale verdict so the section can be read again");
        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_CPP)).isEmpty());
    }

    /**
     * The progress line and the submit gate must not disagree: a stale hunk
     * does not count as settled in either one, or the reader is told "all
     * settled -- ⏎ submits" one keystroke before Submit refuses it.
     */
    @Test
    void theProgressLineExcludesAStaleHunk() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        showOverlappingSections();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        approve(GUARDS_CPP);
        approve(PROFILER);

        assertEquals("2/3 hunks reviewed", progressText(),
                "the stale GUARDS_H verdict must not read as reviewed");
        assertFalse(navHintText().contains("all settled"),
                "the hint must not claim done while a stale hunk would refuse Submit");
    }

    private String navHintText() {
        return labels(".review-verdict-hint").stream()
                .filter(text -> !text.equals("press ? for shortcuts"))
                .findFirst().orElse("<no nav hint>");
    }

    /**
     * Same bug as {@link #theProgressLineExcludesAStaleHunk}, one layer up
     * (coordinator's review): {@link SectionStates#stateOf} used to count a
     * stale verdict toward its OWN section's "n/total", so a card could
     * read fully settled while the verdict bar's global progress line, for
     * the identical hunks, read one short of it.
     */
    @Test
    void theRailCardsOwnCountExcludesAStaleHunkTooNotJustTheBar() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        showOverlappingSections();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        approve(GUARDS_CPP);

        SectionStates.SectionState state = view.diagSectionState(0);
        assertEquals(1, state.settledHunks(),
                "section ①'s own count must exclude the stale GUARDS_H verdict, same as the bar's");
        assertEquals(2, state.totalHunks());
    }

    // ---- a grouping that drifted off the diff -------------------------------

    /**
     * Hunk ids are positional ({@code h_<file>_<index>}), so an agent's
     * grouping can name hunks a later diff does not have. Such a section can
     * never be settled; counting it toward progress refuses Submit forever
     * and jumps to the one card that cannot be settled.
     */
    @Test
    void aSectionWhoseHunksLeftTheDiffSaysSoAndIsNotCounted() {
        showSectionsWithOneAdrift();

        assertTrue(view.diagSectionState(1).hunksMissing(),
                "a section naming hunks the diff does not have is adrift, not unread");
        assertEquals("0/2 hunks reviewed", progressText(),
                "only the resolvable section's hunks may be counted");
        assertTrue(railLabels(".review-intent-adrift")
                        .contains("hunks are no longer in this diff"),
                "the card has to say why it can never be settled");
    }

    /** With every countable hunk settled, Submit must go through. */
    @Test
    void anAdriftSectionDoesNotDeadlockSubmit() {
        showSectionsWithOneAdrift();
        approve(GUARDS_H);
        approve(GUARDS_CPP);

        press(KeyCode.ENTER).release(KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(scope.id()), host.submittedScopes,
                "a section with nothing to settle must not hold the review hostage");
    }

    /** {@code n} must not park the cursor on a card that can never be settled. */
    @Test
    void nextUnsettledSkipsAnAdriftSection() {
        showSectionsWithOneAdrift();
        approve(GUARDS_H);
        approve(GUARDS_CPP);

        press(KeyCode.N).release(KeyCode.N);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(intentLabel().startsWith("2 "),
                "n must not land on the adrift section, got: " + intentLabel());
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Section ① covers guards.h and guards.cpp; section ② covers guards.h
     * again and profiler.cpp. Three hunks, four slots.
     */
    private void showOverlappingSections() {
        mintScope();
        host.intents.set(scope.id(), List.of(
                section("section-1", "Guards", GUARDS_H, GUARDS_CPP),
                section("section-2", "Profiler", GUARDS_H, PROFILER)));
        show();
    }

    /**
     * Section ① covers both guards files; section ② names a hunk index that
     * file does not have, which is what a stale positional id looks like.
     */
    private void showSectionsWithOneAdrift() {
        mintScope();
        ReviewIntent adrift = new ReviewIntent("section-2", 0, "Profiler",
                ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.MED, "",
                List.of(ReviewIntent.hunkId(PROFILER, 7)), Optional.empty(), false);
        host.intents.set(scope.id(), List.of(
                section("section-1", "Guards", GUARDS_H, GUARDS_CPP), adrift));
        show();
    }

    private void mintScope() {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    private void show() {
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.diff));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ReviewIntent section(String id, String title, String... files) {
        List<String> hunkIds = new ArrayList<>();
        for (String file : files) {
            hunkIds.add(ReviewIntent.hunkId(file, 0));
        }
        return new ReviewIntent(id, 0, title, ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.MED,
                "", hunkIds, Optional.empty(), false);
    }

    private void approve(String file) {
        put(file, ReviewVerdict.Decision.APPROVED, host.baseCommit);
    }

    private void recordAgainstBase(String file, String base) {
        put(file, ReviewVerdict.Decision.APPROVED, base);
    }

    private void put(String file, ReviewVerdict.Decision decision, String base) {
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOf(file), decision,
                Optional.empty(), Instant.EPOCH, base, host.headCommit));
        interact(() -> view.refreshReviewState());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private String digestOf(String file) {
        return host.diff.files().stream()
                .filter(candidate -> candidate.path().equals(file))
                .findFirst()
                .map(candidate -> HunkDigest.of(file, candidate.hunks().get(0)))
                .orElseThrow();
    }

    private String progressText() {
        return labels(".review-verdict-progress-label").stream()
                .findFirst().orElse("<no progress label>");
    }

    private long settledCardCount() {
        List<Node> cards = new ArrayList<>();
        interact(() -> cards.addAll(lookup(".review-intent-card").queryAll()));
        return cards.stream().filter(card -> card.getStyleClass().contains("settled")).count();
    }

    /** The texts of every label the rail drew under {@code selector}. */
    private List<String> railLabels(String selector) {
        return labels(selector);
    }

    private String intentLabel() {
        return labels(".review-verdict-intent").stream().findFirst().orElse("");
    }

    private String railText() {
        return String.join(" ", labels(".review-intent-settled-elsewhere"));
    }

    private List<String> labels(String selector) {
        List<Node> nodes = new ArrayList<>();
        interact(() -> nodes.addAll(lookup(selector).queryAll()));
        return nodes.stream().filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText()).toList();
    }

    private static UnifiedDiff.FileDiff file(String path, String text) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                                OptionalInt.of(1), text)))));
    }
}
