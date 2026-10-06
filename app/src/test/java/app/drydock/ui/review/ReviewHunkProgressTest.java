package app.drydock.ui.review;

import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.HunkDigest;
import app.drydock.review.RecheckAssessment;
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
 * What the verdict bar actually RENDERS once progress is counted in hunks
 * (spec §5.6): the progress label, the stale banner and its two answers, and
 * the Submit path's refusal of a stale approval.
 *
 * <p>The derivation behind all of it -- merge rules, counts, staleness -- is
 * {@link SectionStatesTest}, which needs no {@code Stage}. What is here is
 * only what needs a rendered board.</p>
 */
class ReviewHunkProgressTest extends FxTest {

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

    @Test
    void progressCountsEveryHunkOfTheDiff() {
        show();

        assertEquals("0/3 hunks reviewed", progressText());
    }

    @Test
    void settlingAHunkAdvancesProgressByOne() {
        show();

        approve(GUARDS_H);

        assertEquals("1/3 hunks reviewed", progressText());
    }

    @Test
    void everyHunkSettledReadsAsComplete() {
        show();

        approve(GUARDS_H);
        approve(GUARDS_CPP);
        approve(PROFILER);

        assertEquals("3/3 hunks reviewed", progressText());
    }

    // ---- verdicts are keyed by a real digest --------------------------------

    @Test
    void approvingRecordsAVerdictKeyedByTheHunksDigest() {
        show();

        clickOn(".review-verdict-action");
        FxSync.waitForFxEvents();

        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_H)).isPresent(),
                "a verdict must be keyed by the hunk's content digest");
        assertTrue(host.store.verdict(scope.id(), GUARDS_H).isEmpty(),
                "no verdict may be keyed by a file path");
    }

    /** {@code u} undoes every hunk the last settle recorded. */
    @Test
    void undoClearsEveryHunkTheLastSettleRecorded() {
        show();
        press(KeyCode.SHIFT).press(KeyCode.A).release(KeyCode.A).release(KeyCode.SHIFT);
        FxSync.waitForFxEvents();

        press(KeyCode.U).release(KeyCode.U);
        FxSync.waitForFxEvents();

        assertTrue(host.store.verdictsFor(scope.id()).isEmpty(),
                "undo must clear everything the settle recorded");
    }

    // ---- the stale banner ---------------------------------------------------

    @Test
    void aBaseMoveTouchingTheFileBannersIt() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        show();
        recordAgainstBase(GUARDS_H, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED, view.diagStalenessOfCurrentFile());
        assertTrue(staleBanner().startsWith("⚠ approved against base 0000000"),
                "the bar must say the base moved, got: " + staleBanner());
    }

    /**
     * Spec §9.7: an agent's "affected" recheck renders as CLAIMED, never as
     * measured. The move here touches only a file the scope does not read,
     * so Drydock's own filter calls it fresh; the stale banner exists only
     * because the agent said so, and it must read that way.
     */
    @Test
    void theStaleBannerSaysWhenTheAgentIsTheOneClaimingIt() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        show();
        host.store.putAssessment(new RecheckAssessment(scope.id(), digestOf(GUARDS_H),
                "0".repeat(40), host.baseCommit, true, "the guard moved", Instant.EPOCH));
        recordAgainstBase(GUARDS_H, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED, view.diagStalenessOfCurrentFile());
        assertTrue(staleBanner().startsWith("⚠ agent: "),
                "an agent-asserted staleness must not read identically to a measured one: "
                        + staleBanner());
        assertTrue(staleBannerStyleClasses().contains("provenance-claimed"),
                "the claimed banner must carry the claimed style: " + staleBannerStyleClasses());
    }

    /** The measured counterpart: Drydock's own filter found the move. */
    @Test
    void aMeasuredStaleBannerCarriesNoAgentPrefix() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        show();
        recordAgainstBase(GUARDS_H, "0".repeat(40));

        assertFalse(staleBanner().contains("agent:"),
                "a measured move must not be attributed to the agent: " + staleBanner());
        assertFalse(staleBannerStyleClasses().contains("provenance-claimed"),
                "a measured banner must not carry the claimed style: " + staleBannerStyleClasses());
    }

    /**
     * While the delta is still being computed -- or the old base can no
     * longer be diffed -- nothing is known, and nothing may be claimed. A
     * confirm-me banner on a review nobody touched is worse than no banner:
     * it trains the reader to click it reflexively.
     */
    @Test
    void anUnresolvableDeltaSaysNothingRatherThanWarning() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        show();
        recordAgainstBase(GUARDS_H, "0".repeat(40));

        assertEquals(SectionStates.Staleness.UNKNOWN, view.diagStalenessOfCurrentFile(),
                "an unanswered question is not a finding");
        assertEquals("", staleBanner(), "the bar may not warn about a move nothing established");
    }

    /**
     * A stale verdict does not count toward "everything settled" (spec
     * §9.2): Submit must refuse it rather than post a decision nobody has
     * actually confirmed against the code as it stands now.
     */
    @Test
    void submitRefusesWhileAFileIsStale() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        show();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        recordAgainstBase(GUARDS_CPP, "0".repeat(40));
        recordAgainstBase(PROFILER, "0".repeat(40));

        press(KeyCode.ENTER).release(KeyCode.ENTER);
        FxSync.waitForFxEvents();

        assertTrue(host.submittedScopes.isEmpty(), "a stale approval must not be posted silently");
        // The PRODUCTION constant, not a phrase copied out of it: this
        // assertion went stale the moment the message was shortened to fit
        // the bar's real width, which is the drift SubmitRefusal exists to
        // stop.
        assertTrue(labels(".review-verdict-submit-refusal").stream()
                        .anyMatch(text -> text.contains(SessionReviewView.STALE_BASE.reason())),
                "the reader must be told why submit did nothing");
    }

    /**
     * "Confirm still good" keeps the decision and rewrites its recorded
     * base, so the file reads fresh again without a second read.
     */
    @Test
    void confirmStillGoodRewritesTheBaseAndClearsTheBanner() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        show();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        assertEquals(SectionStates.Staleness.MOVED, view.diagStalenessOfCurrentFile());

        interact(() -> ((Button) lookup(".review-verdict-confirm-stale").query()).fire());
        FxSync.waitForFxEvents();

        assertEquals(SectionStates.Staleness.FRESH, view.diagStalenessOfCurrentFile());
        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_H))
                        .map(v -> v.decision() == ReviewVerdict.Decision.APPROVED).orElse(false),
                "confirm still good must keep the decision, not clear it");
    }

    /** "Re-review" is the other answer: it clears the stale verdicts entirely. */
    @Test
    void reReviewClearsTheStaleVerdicts() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        show();
        recordAgainstBase(GUARDS_H, "0".repeat(40));
        recordAgainstBase(GUARDS_CPP, "0".repeat(40));

        interact(() -> ((Button) lookup(".review-verdict-re-review").query()).fire());
        FxSync.waitForFxEvents();

        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_H)).isEmpty(),
                "re-review must clear the stale verdict so the file can be read again");
        assertTrue(host.store.verdict(scope.id(), digestOf(GUARDS_CPP)).isPresent(),
                "and only the file the bar shows");
    }

    /**
     * The progress line and the submit gate must not disagree: a stale hunk
     * does not count as settled in either one, or the reader is told "all
     * settled -- ⏎ submits" one keystroke before Submit refuses it.
     */
    @Test
    void theProgressLineExcludesAStaleHunk() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        show();
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

    // ---- helpers ------------------------------------------------------------    // ---- helpers ------------------------------------------------------------

    private void mintScope() {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    private void show() {
        mintScope();
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(scope, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagShowDiff(scope, host.diff));
        FxSync.waitForFxEvents();
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
        FxSync.waitForFxEvents();
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

    /** The verdict bar's stale banner; blank when none is showing. */
    private String staleBanner() {
        List<Node> nodes = new ArrayList<>();
        interact(() -> nodes.addAll(lookup(".review-verdict-stale").queryAll()));
        return nodes.stream().filter(Node::isVisible).filter(node -> node.getScene() != null)
                .filter(node -> node.getParent() != null)
                .map(node -> ((Label) node).getText()).findFirst().orElse("");
    }

    /** The stale banner's style classes; empty when none is showing. */
    private List<String> staleBannerStyleClasses() {
        List<Node> nodes = new ArrayList<>();
        interact(() -> nodes.addAll(lookup(".review-verdict-stale").queryAll()));
        return nodes.stream().filter(Node::isVisible).filter(node -> node.getParent() != null)
                .map(node -> List.copyOf(node.getStyleClass())).findFirst().orElse(List.of());
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
