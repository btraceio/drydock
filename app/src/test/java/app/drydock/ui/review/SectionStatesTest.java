package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * What a section says about itself, derived from its hunks (spec §9.1) --
 * exercised directly, with no {@code Stage}.
 *
 * <p>Every question here is answered from a {@link SessionReviewView.Host},
 * a diff and a grouping; none of it is scene graph. {@link
 * ReviewHunkProgressTest} keeps the assertions that are genuinely about what
 * the rail and the verdict bar RENDER.</p>
 */
class SectionStatesTest {

    private static final String GUARDS_H = "src/guards.h";
    private static final String GUARDS_CPP = "src/guards.cpp";
    private static final String PROFILER = "src/profiler.cpp";

    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SectionStates sections;
    private ReviewScope scope;
    private UnifiedDiff diff;

    @BeforeEach
    void setUp(@TempDir Path store) {
        host = new FakeReviewHost(store.resolve("annotations.json"));
        sections = new SectionStates(host);
        diff = new UnifiedDiff(List.of(
                file(GUARDS_H, "class JmpCtxScope;"),
                file(GUARDS_CPP, "void install();"),
                file(PROFILER, "resolve();")));
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    @AfterEach
    void tearDown() {
        host.store.close();
    }

    // ---- distinct hunks, not section slots ----------------------------------

    /** Four section slots over three hunks: anything summing sizes reads 4. */
    @Test
    void progressCountsDistinctHunksNotSectionSlots() {
        SectionStates.Board board = overlapping();

        assertEquals(3, sections.distinctDigests(board).size());
        assertEquals(0, sections.settledHunkCount(board));
    }

    @Test
    void aHunkInTwoSectionsIsOneFlagNotTwo() {
        SectionStates.Board board = overlapping();
        approve(GUARDS_H);

        assertEquals(1, sections.settledHunkCount(board));
    }

    // ---- a section's decision comes from its hunks ---------------------------

    @Test
    void anUnsettledHunkLeavesItsSectionUnsettled() {
        SectionStates.Board board = overlapping();
        approve(GUARDS_H);

        SectionStates.SectionState state = sections.stateOf(board, board.sections().get(0));
        assertEquals(Optional.empty(), state.decision());
        assertEquals(1, state.settledHunks());
        assertEquals(2, state.totalHunks());
    }

    @Test
    void aSectionWithEveryHunkSettledIsApproved() {
        SectionStates.Board board = overlapping();
        approve(GUARDS_H);
        approve(GUARDS_CPP);

        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED),
                sections.stateOf(board, board.sections().get(0)).decision());
    }

    /** Any changes request wins over the rest of the section (VerdictMerge). */
    @Test
    void oneChangeRequestMakesTheWholeSectionChanges() {
        SectionStates.Board board = overlapping();
        record(GUARDS_CPP, ReviewVerdict.Decision.CHANGES, host.baseCommit);

        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES),
                sections.stateOf(board, board.sections().get(0)).decision());
    }

    // ---- a hunk settled in a neighbouring section ----------------------------

    @Test
    void aFullySettledSiblingIsNamed() {
        SectionStates.Board board = overlapping();
        approve(GUARDS_H);
        approve(GUARDS_CPP);

        assertEquals(List.of("①"),
                sections.stateOf(board, board.sections().get(1)).settledElsewhere());
    }

    /**
     * A sibling that settled ONE shared hunk moves this card's count by
     * exactly as much as a fully settled one does. Marking only the
     * fully-settled case solves the easy half of "state changing on its own"
     * and leaves the other half exactly as mysterious.
     */
    @Test
    void aPartlySettledSiblingIsNamedToo() {
        SectionStates.Board board = overlapping();
        approve(GUARDS_H);

        assertEquals(List.of("②"),
                sections.stateOf(board, board.sections().get(0)).settledElsewhere());
        assertEquals(List.of("①"),
                sections.stateOf(board, board.sections().get(1)).settledElsewhere());
    }

    /** A section that shares nothing has nothing to point at. */
    @Test
    void aSectionSharingNoHunkNamesNobody() {
        SectionStates.Board board = board(List.of(
                section("section-1", GUARDS_H),
                section("section-2", PROFILER)));
        approve(GUARDS_H);

        assertTrue(sections.stateOf(board, board.sections().get(0)).settledElsewhere().isEmpty());
    }

    // ---- staleness has three states, not two --------------------------------

    @Test
    void aVerdictAgainstTheCurrentBaseIsFresh() {
        SectionStates.Board board = overlapping();
        approve(GUARDS_H);

        assertEquals(SectionStates.Staleness.FRESH,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    @Test
    void aBaseMoveTouchingTheSectionIsMoved() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    /**
     * Same exclusion {@link #settledHunkCount} applies globally, one layer
     * down: a card's own "n/total" must not count a stale hunk either, or
     * the card could read fully settled while the verdict bar's progress
     * line, for the SAME hunks, read one short of it (coordinator's review).
     * The DECISION still merges the stale verdict -- only the numeric count
     * excludes it.
     */
    @Test
    void settledHunksExcludesAStaleOneButTheDecisionStillMergesIt() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        approve(GUARDS_CPP);

        SectionStates.SectionState state = sections.stateOf(board, board.sections().get(0));
        assertEquals(1, state.settledHunks(), "the stale GUARDS_H verdict must not be counted");
        assertEquals(2, state.totalHunks());
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), state.decision(),
                "the decision persists across staleness -- only its freshness is in question");
    }

    /** A move that provably could not matter must not spend the reader's attention. */
    @Test
    void aBaseMoveElsewhereIsFresh() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        assertEquals(SectionStates.Staleness.FRESH,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    /**
     * The delta is unresolvable while it is still being computed off the FX
     * thread, and when the old base can no longer be diffed. Neither is
     * evidence that the base moved, and rendering them as one would put a
     * confirm-me banner on every card of a review nobody has touched.
     */
    @Test
    void anUnresolvableDeltaIsUnknownNotMoved() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        assertEquals(SectionStates.Staleness.UNKNOWN,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    /** One hunk known to have moved is the strongest thing true of the section. */
    @Test
    void aKnownMoveOutranksAnUnknownOne() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_CPP)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        record(GUARDS_CPP, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    // ---- a grouping that drifted off the diff --------------------------------

    /**
     * Hunk ids are positional ({@code h_<file>_<index>}), so an agent's
     * grouping can name hunks a later diff does not have. Such a section can
     * never be settled: counting it toward progress refuses Submit forever.
     */
    @Test
    void aSectionWhoseHunksLeftTheDiffIsAdriftNotUnread() {
        SectionStates.Board board = board(List.of(
                section("section-1", GUARDS_H),
                new ReviewIntent("section-2", 2, "Profiler", ReviewIntent.Kind.CHANGE,
                        ReviewIntent.Risk.MED, "", List.of(ReviewIntent.hunkId(PROFILER, 7)),
                        Optional.empty(), false)));

        SectionStates.SectionState adrift = sections.stateOf(board, board.sections().get(1));
        assertTrue(adrift.hunksMissing());
        assertEquals(0, adrift.totalHunks());
        assertEquals(List.of("section-1"),
                sections.counted(board).stream().map(ReviewIntent::id).toList());
        assertFalse(sections.hasResolvableHunks(board, board.sections().get(1)));
    }

    /** An intent naming no hunks at all covers the whole diff -- it is not adrift. */
    @Test
    void anIntentNamingNoHunksCoversEverything() {
        SectionStates.Board board = board(List.of(new ReviewIntent("everything", 1, "All",
                ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.MED, "", List.of(),
                Optional.empty(), false)));

        assertEquals(3, sections.digestsOf(board, board.sections().get(0)).size());
        assertFalse(sections.stateOf(board, board.sections().get(0)).hunksMissing());
    }

    /** A collapsed section is not counted: the point of the collapse is nothing to read. */
    @Test
    void aCollapsedSectionIsNotCounted() {
        ReviewIntent collapsed = new ReviewIntent("collapsed", 2, "Rename",
                ReviewIntent.Kind.MOVE, ReviewIntent.Risk.NONE, "",
                List.of(ReviewIntent.hunkId(PROFILER, 0)),
                Optional.of(new ReviewIntent.Collapse("pure rename", "git -M", 1, 1)), false);
        SectionStates.Board board = board(List.of(section("section-1", GUARDS_H), collapsed));

        assertEquals(List.of("section-1"),
                sections.counted(board).stream().map(ReviewIntent::id).toList());
        assertEquals(1, sections.distinctDigests(board).size());
    }

    // ---- the digest memo -----------------------------------------------------

    /**
     * A reviewer may re-issue the same id over DIFFERENT hunks. A memo keyed
     * by the id would answer with the hunks of a grouping that no longer
     * exists.
     */
    @Test
    void reIssuingAnIdOverDifferentHunksIsNotServedFromTheMemo() {
        SectionStates.Board first = board(List.of(section("s", GUARDS_H)));
        assertEquals(List.of(digestOf(GUARDS_H)), sections.digestsOf(first, first.sections().get(0)));

        SectionStates.Board second = board(List.of(section("s", PROFILER)));
        assertEquals(List.of(digestOf(PROFILER)),
                sections.digestsOf(second, second.sections().get(0)));
    }

    @Test
    void sectionMarksAreCircledUpToTwentyThenPlain() {
        assertEquals("①", SectionStates.sectionMark(1));
        assertEquals("⑳", SectionStates.sectionMark(20));
        assertEquals("#21", SectionStates.sectionMark(21));
    }

    // ---- what a/r/u act on (spec §9.6) ----------------------------------------

    /** The anchor hunk is the FIRST one named, matching where the diff column scrolls to. */
    @Test
    void digestOfAnchorHunkIsTheFirstHunkNamed() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);

        assertEquals(Optional.of(digestOf(GUARDS_H)), sections.digestOfAnchorHunk(board, section1));
    }

    @Test
    void digestOfAnchorHunkIsEmptyForAnUnresolvableSection() {
        SectionStates.Board board = board(List.of(section("adrift", "src/gone.cpp")));

        assertTrue(sections.digestOfAnchorHunk(board, board.sections().get(0)).isEmpty());
    }

    @Test
    void currentFileOfIsTheAnchorHunksFileWhenNothingIsSelected() {
        SectionStates.Board board = overlapping();
        ReviewIntent section2 = board.sections().get(1);

        assertEquals(Optional.of(GUARDS_H), sections.currentFileOf(board, section2, Optional.empty()));
    }

    /**
     * An intent naming no hunks at all covers the whole diff (see {@link
     * ReviewIntent#containsHunk}); the anchor-file fallback inside {@link
     * SectionStates#currentFileOf} falls back further, to the first file of
     * the diff, rather than answering nothing.
     */
    @Test
    void currentFileOfFallsBackToTheDiffsFirstFileWhenTheSectionNamesNone() {
        SectionStates.Board board = board(List.of(
                new ReviewIntent("whole-diff", 1, "Everything", ReviewIntent.Kind.CHANGE,
                        ReviewIntent.Risk.MED, "", List.of(), Optional.empty(), false)));

        assertEquals(Optional.of(GUARDS_H),
                sections.currentFileOf(board, board.sections().get(0), Optional.empty()));
    }

    /** A gutter selection wins over the section's own anchor file. */
    @Test
    void currentFileOfPrefersTheGutterSelectionOverTheAnchor() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);
        String selectionKey = GUARDS_CPP + " n1";

        assertEquals(Optional.of(GUARDS_CPP),
                sections.currentFileOf(board, section1, Optional.of(selectionKey)));
    }

    /** A gutter selection resolves to the hunk containing that exact line. */
    @Test
    void digestOfCurrentHunkPrefersTheGutterSelection() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);
        String selectionKey = GUARDS_CPP + " n1";

        assertEquals(Optional.of(digestOf(GUARDS_CPP)),
                sections.digestOfCurrentHunk(board, section1, Optional.of(selectionKey)));
    }

    /**
     * With nothing selected, HUNK mode must not always answer hunk one:
     * with the anchor hunk already settled, the next press has to reach
     * the section's first UNSETTLED hunk, or a reader who never opens the
     * gutter composer could never approve anything past the first hunk.
     */
    @Test
    void digestOfCurrentHunkFallsBackToTheFirstUnsettledHunk() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);
        approve(GUARDS_H);

        assertEquals(Optional.of(digestOf(GUARDS_CPP)),
                sections.digestOfCurrentHunk(board, section1, Optional.empty()));
    }

    /** Once every hunk is settled, the anchor is the last fallback left. */
    @Test
    void digestOfCurrentHunkFallsBackToTheAnchorWhenEverythingIsSettled() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);
        approve(GUARDS_H);
        approve(GUARDS_CPP);

        assertEquals(Optional.of(digestOf(GUARDS_H)),
                sections.digestOfCurrentHunk(board, section1, Optional.empty()));
    }

    /** A stale key -- selected line no longer in the diff -- is not trusted; the walk continues. */
    @Test
    void digestOfCurrentHunkIgnoresASelectionTheDiffNoLongerHas() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);

        assertEquals(Optional.of(digestOf(GUARDS_H)),
                sections.digestOfCurrentHunk(board, section1, Optional.of(GUARDS_H + " n999")));
    }

    // ---- what a/r/u act on does not count as settled while stale (spec §9.2) --

    @Test
    void settledHunkCountExcludesAStaleVerdict() {
        SectionStates.Board board = overlapping();
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        approve(GUARDS_CPP);
        approve(PROFILER);

        assertEquals(2, sections.settledHunkCount(board),
                "the stale GUARDS_H verdict must not count toward progress");
    }

    /**
     * {@code ⇧A}/{@code ⇧R} settle every hunk of the file across the WHOLE
     * diff -- not just the hunks the current section happens to name.
     */
    @Test
    void digestsOfFileCoversEveryHunkOfTheFileRegardlessOfSection() {
        SectionStates.Board board = overlapping();

        assertEquals(List.of(digestOf(GUARDS_H)), sections.digestsOfFile(board, GUARDS_H));
    }

    @Test
    void digestsOfFileIsEmptyForAFileNotInTheDiff() {
        SectionStates.Board board = overlapping();

        assertTrue(sections.digestsOfFile(board, "src/nowhere.cpp").isEmpty());
    }

    @Test
    void digestsForActionInHunkModeIsJustTheOneHunk() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);

        assertEquals(List.of(digestOf(GUARDS_H)), sections.digestsForAction(
                board, section1, SessionReviewView.SettleUnit.HUNK, false, Optional.empty()));
    }

    @Test
    void digestsForActionInSectionModeIsEveryHunkTheSectionNames() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);

        assertEquals(List.of(digestOf(GUARDS_H), digestOf(GUARDS_CPP)), sections.digestsForAction(
                board, section1, SessionReviewView.SettleUnit.SECTION, false, Optional.empty()));
    }

    /** {@code wholeFile} wins over the unit even in HUNK mode -- ⇧A/⇧R always mean the file. */
    @Test
    void digestsForActionWithWholeFileIgnoresTheUnit() {
        SectionStates.Board board = overlapping();
        ReviewIntent section1 = board.sections().get(0);

        assertEquals(List.of(digestOf(GUARDS_H)), sections.digestsForAction(
                board, section1, SessionReviewView.SettleUnit.HUNK, true, Optional.empty()));
    }

    // ---- helpers -------------------------------------------------------------

    /** Section ① covers both guards files; section ② covers guards.h again and profiler. */
    private SectionStates.Board overlapping() {
        return board(List.of(
                section("section-1", GUARDS_H, GUARDS_CPP),
                section("section-2", GUARDS_H, PROFILER)));
    }

    private SectionStates.Board board(List<ReviewIntent> grouping) {
        List<ReviewIntent> numbered = new ArrayList<>();
        int number = 1;
        for (ReviewIntent intent : grouping) {
            numbered.add(new ReviewIntent(intent.id(), number++, intent.title(), intent.kind(),
                    intent.risk(), intent.rationale(), intent.hunkIds(), intent.collapse(),
                    intent.autoApprove()));
        }
        return new SectionStates.Board(scope, diff, numbered);
    }

    private static ReviewIntent section(String id, String... files) {
        List<String> hunkIds = new ArrayList<>();
        for (String file : files) {
            hunkIds.add(ReviewIntent.hunkId(file, 0));
        }
        return new ReviewIntent(id, 0, id, ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.MED,
                "", hunkIds, Optional.empty(), false);
    }

    private void approve(String file) {
        record(file, ReviewVerdict.Decision.APPROVED, host.baseCommit);
    }

    private void record(String file, ReviewVerdict.Decision decision, String base) {
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOf(file), decision,
                Optional.empty(), Instant.EPOCH, base, host.headCommit));
    }

    private String digestOf(String file) {
        return diff.files().stream()
                .filter(candidate -> candidate.path().equals(file))
                .findFirst()
                .map(candidate -> HunkDigest.of(file, candidate.hunks().get(0)))
                .orElseThrow();
    }

    private static UnifiedDiff.FileDiff file(String path, String text) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(
                new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                                OptionalInt.of(1), text)))));
    }
}
