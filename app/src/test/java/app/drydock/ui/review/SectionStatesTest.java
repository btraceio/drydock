package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.ChangeGraph;
import app.drydock.review.HunkDigest;
import app.drydock.review.RecheckDispatch;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * Task 18's correction 6b, unreachable until sections overlapped: with
     * THREE sections sharing one hunk, a verdict is keyed {@code (scopeId,
     * hunkDigest)} alone -- nothing records which of them the reader actually
     * settled it through -- so naming every sharer would credit sections
     * that, as far as this model can tell, reviewed nothing. At most one is
     * named per card; the two-section tests above (still passing, unchanged)
     * are the case where "at most one" and "the only one" coincide.
     */
    @Test
    void threeSectionsSharingAHunkNameAtMostOneEach() {
        SectionStates.Board board = board(List.of(
                section("section-1", GUARDS_H, GUARDS_CPP),
                section("section-2", GUARDS_H, PROFILER),
                section("section-3", GUARDS_H)));
        approve(GUARDS_H);

        assertEquals(List.of("②"),
                sections.stateOf(board, board.sections().get(0)).settledElsewhere(),
                "section 1 must name at most one sharer, not both 2 and 3");
        assertEquals(List.of("①"),
                sections.stateOf(board, board.sections().get(1)).settledElsewhere(),
                "section 2 must name at most one sharer, not both 1 and 3");
        assertEquals(List.of("①"),
                sections.stateOf(board, board.sections().get(2)).settledElsewhere(),
                "section 3 must name at most one sharer, not both 1 and 2");
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

    /**
     * Task 18's correction 6a: a section with one stale-approved hunk and one
     * genuinely UNREAD hunk has {@code settledHunks()==0} -- correct, nothing
     * here is safely settled -- but the card must not read as though NOTHING
     * was ever recorded either. {@code recordedHunks()} is what the rail's
     * progress LABEL reads instead, so "1/2 hunks" survives exactly this gap.
     */
    @Test
    void recordedHunksCountsAStaleVerdictEvenWhenNothingElseIsSettled() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        // GUARDS_CPP is left entirely unread -- no verdict of any kind.

        SectionStates.SectionState state = sections.stateOf(board, board.sections().get(0));
        assertEquals(0, state.settledHunks(), "the stale hunk must not count as SETTLED");
        assertEquals(1, state.recordedHunks(),
                "but it WAS recorded -- the card must not understate to zero hunks touched");
        assertEquals(2, state.totalHunks());
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

    // ---- an agent may add staleness, never take it away (spec 9.7) ----------

    /**
     * The blind spot {@link BaseMove} names in its own class comment: the
     * intersection is file-level and lexical, so a base commit that changes
     * behaviour without touching a file this section names reads as FRESH.
     * An agent's {@code affected} recheck is the only thing that can close
     * it, and this is the case where it has to.
     */
    @Test
    void anAgentsAffectedRecheckMarksAMoveTheFileFilterDismissed() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        assess(GUARDS_H, true, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED,
                sections.stateOf(board, board.sections().get(0)).staleness());
        assertEquals(0, sections.settledHunkCount(board),
                "a hunk the agent marked must not count as settled either");
    }

    /**
     * <strong>The asymmetry.</strong> The filter already found this move, and
     * an agent saying "unaffected" must not take that back: an agent wrong
     * THAT way leaves a human's approval standing over code nobody re-read,
     * which is the outcome the whole reviewed-state model refuses. False and
     * "never asked" are one answer here, deliberately.
     */
    @Test
    void anAgentsUnaffectedRecheckDoesNotClearAMoveTheFilterFound() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        assess(GUARDS_H, false, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED,
                sections.stateOf(board, board.sections().get(0)).staleness());
        assertEquals(0, sections.settledHunkCount(board),
                "an agent's advice must not re-settle a hunk the base moved under");
    }

    /** Nor may it clear the weaker "cannot tell" the same way. */
    @Test
    void anAgentsUnaffectedRecheckDoesNotClearAnUnresolvableDelta() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        assess(GUARDS_H, false, "0".repeat(40));

        assertEquals(SectionStates.Staleness.UNKNOWN,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    /** An affected recheck DOES outrank "cannot tell": it only ever adds reading. */
    @Test
    void anAgentsAffectedRecheckOutranksAnUnresolvableDelta() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        assess(GUARDS_H, true, "0".repeat(40));

        assertEquals(SectionStates.Staleness.MOVED,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    /**
     * An assessment is about one base PAIR. A recheck of an older move is not
     * an answer about this one, and carrying it forward would be the agent
     * answering something it was never asked.
     */
    @Test
    void anAgentsRecheckOfADifferentBasePairIsNotConsulted() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        // Marked affected -- but about a move FROM a base this verdict was
        // never judged against.
        host.store.putAssessment(new app.drydock.review.RecheckAssessment(scope.id(),
                digestOf(GUARDS_H), "9".repeat(40), host.baseCommit, true, "why", Instant.EPOCH));

        assertEquals(SectionStates.Staleness.FRESH,
                sections.stateOf(board, board.sections().get(0)).staleness());
    }

    /**
     * A recheck cannot invent staleness where the base never moved. The
     * agent's answer is consulted only once the verdict is already stale
     * against the current base -- it widens what counts as a move that
     * matters, it does not decide that one happened.
     */
    @Test
    void anAgentsAffectedRecheckCannotStaleAVerdictAgainstTheCurrentBase() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        approve(GUARDS_H);
        assess(GUARDS_H, true, host.baseCommit);

        assertEquals(SectionStates.Staleness.FRESH,
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

    /**
     * The half Task 5 deferred: a base commit touching a file this section
     * does not change but DOES reference can have moved the ground under an
     * approval, and only the change graph -- when already in hand -- makes
     * that visible (spec §9.2). Section-1 here names only Profiler.java;
     * the base move touches only Guards.java, which Profiler.java
     * references. Without the graph's widening this reads FRESH -- the
     * scope's own files never touch Guards.java at all.
     */
    @Test
    void aBaseMoveTouchingAReferencedButUnchangedFileIsMoved() {
        UnifiedDiff graphDiff = new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }"),
                file("src/Profiler.java", "void go() { new JmpCtxScope(); }")));
        ChangeGraph graph = ChangeGraph.of(graphDiff);
        SectionStates.Board board = new SectionStates.Board(scope, graphDiff,
                List.of(section("section-1", "src/Profiler.java")), Optional.of(graph));
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("src/Guards.java")));
        record(graphDiff, "src/Profiler.java", ReviewVerdict.Decision.APPROVED, "0".repeat(40));

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

    // ---- the automatic recheck a base move earns (spec §9.7) ----------------

    /**
     * A move that stales an approval asks the agent about it, naming the base
     * PAIR the approval was recorded against and the base it now faces.
     */
    @Test
    void aBaseMoveThatStalesAnApprovalAsksTheAgentOnce() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertEquals(List.of("0".repeat(40) + "->" + host.baseCommit), host.recheckDispatches);
    }

    /**
     * <strong>The window the store cannot see.</strong> Between the dispatch
     * and the agent's first {@code review_recheck} there is no assessment, and
     * {@code assessedAffected} reads exactly the same as never having asked.
     * A board re-renders whenever a background git answer lands, so a guard
     * built on the store alone would send a subagent per render.
     */
    @Test
    void aSecondRenderInsideTheSameMoveDoesNotAskAgain() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        RecheckDispatch dispatch = new RecheckDispatch();

        sections.requestRechecks(board, dispatch);
        sections.requestRechecks(board, dispatch);

        assertEquals(1, host.recheckDispatches.size(),
                "no assessment has arrived yet, and that must not read as 'never asked'");
    }

    /**
     * A hand-off that did not happen must not be remembered as done: the send
     * reached no terminal, and no human is present to notice the silence.
     */
    @Test
    void aRecheckWhoseHandOffFailedIsAskedAgainOnTheNextRender() {
        host.recheckHandOffSucceeds = false;
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        RecheckDispatch dispatch = new RecheckDispatch();

        sections.requestRechecks(board, dispatch);
        sections.requestRechecks(board, dispatch);

        assertEquals(2, host.recheckDispatches.size());
    }

    /**
     * Relevance-gated: a move touching nothing this scope reads leaves every
     * section FRESH, and a fresh section has no disturbed approval to ask
     * about. Without this every base move spends a subagent.
     */
    @Test
    void aMoveThatCouldNotMatterAsksNothing() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty());
    }

    /**
     * <strong>The relevance gate, for real.</strong> The production host
     * returns an UNRESOLVABLE delta on the FIRST call for any base pair --
     * it spawns the git off-thread and answers later -- and that renders as
     * UNKNOWN, not FRESH. Gating on "not FRESH" therefore dispatched on the
     * very render that discovers the move, before couldMatter had answered
     * anything, and the claim is permanent. Only MOVED means "the move could
     * matter"; UNKNOWN means "ask again once git has spoken".
     */
    @Test
    void aMoveNobodyCanResolveYetAsksNothing() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty(),
                "an unanswered question is not a reason to spend an agent");
    }

    /**
     * "unresolved" is not a revision. The guard exists for the CURRENT base
     * forty lines from where the recorded one is read, and a verdict can
     * carry it too -- baselineOf returns the sentinel while git is still
     * answering and permanently when resolveRef fails.
     */
    @Test
    void aVerdictRecordedAgainstAnUnresolvedBaseAsksNothing() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, SessionReviewView.UNRESOLVED_BASE);

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty(),
                "no agent can read what changed between 'unresolved' and a commit");
    }

    /** The mirror: an unresolved CURRENT base names no pair either. */
    @Test
    void anUnresolvedCurrentBaseAsksNothing() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        host.baseCommit = SessionReviewView.UNRESOLVED_BASE;

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty());
    }

    /**
     * Two approvals recorded at two DIFFERENT older bases are two distinct
     * questions, and the loop has to emit both. Every other test here has at
     * most one stale base, so the loop was only ever exercised emitting one.
     */
    @Test
    void twoApprovalsAtDifferentOlderBasesEachEarnTheirOwnRecheck() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H, GUARDS_CPP)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        record(GUARDS_CPP, ReviewVerdict.Decision.APPROVED, "9".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertEquals(List.of("0".repeat(40) + "->" + host.baseCommit,
                        "9".repeat(40) + "->" + host.baseCommit),
                host.recheckDispatches);
    }

    /**
     * Spec §9.7: "inline harnesses simply do not get one". Only a harness
     * that can run the recheck in a subagent is asked automatically -- an
     * inline agent would have an unrequested prompt typed into whatever it
     * was doing, with no human present to have asked for it.
     */
    @Test
    void aHarnessWithoutSubagentsIsNeverAskedAutomatically() {
        host.supportsAutomaticRecheck = false;
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty());
    }

    /**
     * The in-memory claim dies with the view; the stale mark outlives it. An
     * answer already in the store is what stops a restart re-asking the same
     * question forever.
     */
    @Test
    void aMoveTheAgentHasAlreadyAnsweredIsNotAskedAgain() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        assess(GUARDS_H, false, "0".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty(),
                "the answer is already on disk; a fresh RecheckDispatch must not re-ask");
    }

    /** The instruction says "for each approved hunk"; a CHANGES verdict is not one. */
    @Test
    void aRequestedChangesVerdictEarnsNoRecheck() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.CHANGES, "0".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty());
    }

    /**
     * One {@link RecheckDispatch} serves every scope the view shows -- it is a
     * single field for the life of the view. A claim keyed by anything less
     * than the scope would let one scope's move permanently silence another's
     * identical one. {@code RecheckDispatchTest} proves the SET discriminates
     * on scope; only this proves the CALLER supplies it.
     */
    @Test
    void oneDispatchMemoryServesTwoScopesIndependently() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        RecheckDispatch shared = new RecheckDispatch();
        SectionStates.Board first = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        sections.requestRechecks(first, shared);
        assertEquals(1, host.recheckDispatches.size(), "precondition");

        // A DIFFERENT identity, or ReviewScopeRegistry.mint hands back the
        // same scope: it does computeIfAbsent on (kind, roots, refs), so a
        // spec equal to an existing one is the same handle, not a new one.
        ReviewScope other = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKING_TREE, Path.of("/tmp/elsewhere"),
                Optional.of(Path.of("/tmp/elsewhere")), "main", "main",
                Optional.empty(), Optional.empty()));
        assertNotEquals(scope.id(), other.id(), "precondition: two distinct scopes");
        host.store.putVerdict(new ReviewVerdict(other.id(), digestOf(GUARDS_H),
                ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH,
                "0".repeat(40), host.headCommit));
        SectionStates.Board second = new SectionStates.Board(other, diff, first.sections());

        sections.requestRechecks(second, shared);

        assertEquals(2, host.recheckDispatches.size(),
                "a different scope's identical base move is its own question");
    }

    /**
     * Only the approvals the move actually staled are asked about. A section
     * can hold one stale hunk and one approved against the CURRENT base;
     * taking every verdict in a non-FRESH section would ask the agent to read
     * what changed between a base and itself -- a subagent spent on an empty
     * diff.
     */
    @Test
    void aFreshApprovalSharingAStaleSectionIsNotAskedAbout() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H, GUARDS_CPP)));
        SectionStates.Board board = overlapping();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        record(GUARDS_CPP, ReviewVerdict.Decision.APPROVED, host.baseCommit);

        sections.requestRechecks(board, new RecheckDispatch());

        assertEquals(List.of("0".repeat(40) + "->" + host.baseCommit), host.recheckDispatches,
                "a verdict already recorded against the current base has not moved");
    }

    /**
     * No approval, nothing staled, nothing to ask. Paired with a positive
     * control: on its own this passes against an EMPTY method body, so it
     * pins nothing until the same fixture is shown to dispatch once a verdict
     * exists.
     */
    @Test
    void aScopeWithNoRecordedApprovalAsksNothing() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = overlapping();

        sections.requestRechecks(board, new RecheckDispatch());
        assertTrue(host.recheckDispatches.isEmpty());

        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        sections.requestRechecks(board, new RecheckDispatch());
        assertEquals(1, host.recheckDispatches.size(),
                "positive control: the same fixture DOES ask once an approval exists");
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

    /**
     * An agent's recheck of the move from {@code fromBase} to the scope's
     * current base, as {@code review_recheck} records one -- against the
     * hunk's content DIGEST, which is the only thing the board ever looks a
     * recheck up by.
     */
    private void assess(String file, boolean affected, String fromBase) {
        host.store.putAssessment(new app.drydock.review.RecheckAssessment(scope.id(),
                digestOf(file), fromBase, host.baseCommit, affected, "why", Instant.EPOCH));
    }

    private void record(String file, ReviewVerdict.Decision decision, String base) {
        record(diff, file, decision, base);
    }

    /** As {@link #record(String, ReviewVerdict.Decision, String)}, over a diff other than the fixture's. */
    private void record(UnifiedDiff source, String file, ReviewVerdict.Decision decision, String base) {
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOf(source, file), decision,
                Optional.empty(), Instant.EPOCH, base, host.headCommit));
    }

    private String digestOf(String file) {
        return digestOf(diff, file);
    }

    private static String digestOf(UnifiedDiff source, String file) {
        return source.files().stream()
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
