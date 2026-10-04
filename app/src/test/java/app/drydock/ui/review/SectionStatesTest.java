package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.BaseMove;
import app.drydock.review.ChangeGraph;
import app.drydock.review.HunkDigest;
import app.drydock.review.RecheckAssessment;
import app.drydock.review.RecheckDispatch;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a file and the whole review say about themselves, derived from the
 * hunks' verdicts (spec §9.1) -- exercised directly, with no {@code Stage}.
 *
 * <p>Every question here is answered from a {@link SessionReviewView.Host}
 * and a diff; none of it is scene graph. {@link ReviewHunkProgressTest}
 * keeps the assertions that are genuinely about what the verdict bar
 * RENDERS.</p>
 */
class SectionStatesTest {

    private static final String GUARDS_H = "src/guards.h";
    private static final String GUARDS_CPP = "src/guards.cpp";
    private static final String PROFILER = "src/profiler.cpp";
    private static final String OLD_BASE = "0".repeat(40);

    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private SectionStates sections;
    private ReviewScope scope;
    private UnifiedDiff diff;

    @BeforeEach
    void setUp(@TempDir Path store) {
        host = new FakeReviewHost(store.resolve("annotations.json"));
        sections = new SectionStates(host);
        // guards.h has TWO hunks, so a file-level answer is distinguishable
        // from a hunk-level one.
        diff = new UnifiedDiff(List.of(
                file(GUARDS_H, "class JmpCtxScope;", "void enter();"),
                file(GUARDS_CPP, "void install();"),
                file(PROFILER, "resolve();"),
                new UnifiedDiff.FileDiff("bin/tool", "M", 0, 0, true, false, List.of())));
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE,
                Path.of("/tmp/nowhere"), Optional.of(Path.of("/tmp/nowhere")), "main", "main",
                Optional.empty(), Optional.empty()));
    }

    @AfterEach
    void tearDown() {
        host.store.close();
    }

    // ---- what the hunk diff walks --------------------------------------------

    /** A file with no hunk (binary, mode-only) has nothing to settle and is no stop. */
    @Test
    void theFilesWalkedAreThoseWithHunksInDiffOrder() {
        assertEquals(List.of(GUARDS_H, GUARDS_CPP, PROFILER), sections.filesWithHunks(board()));
    }

    @Test
    void progressCountsEveryHunkOfTheDiff() {
        SectionStates.Board board = board();

        assertEquals(4, sections.distinctDigests(board).size());
        assertEquals(0, sections.settledHunkCount(board));
    }

    @Test
    void settlingOneHunkCountsOne() {
        SectionStates.Board board = board();
        approve(GUARDS_H);

        assertEquals(1, sections.settledHunkCount(board));
    }

    // ---- a file's decision comes from its hunks -------------------------------

    @Test
    void anUnsettledHunkLeavesItsFileUndecided() {
        approve(GUARDS_H);

        assertEquals(Optional.empty(), sections.decisionOf(board(), GUARDS_H));
    }

    @Test
    void aFileWithEveryHunkSettledIsApproved() {
        approve(GUARDS_H, 0);
        approve(GUARDS_H, 1);

        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), sections.decisionOf(board(), GUARDS_H));
    }

    /** Any changes request wins over the rest of the file (VerdictMerge), read or not. */
    @Test
    void oneChangeRequestMakesTheWholeFileChanges() {
        record(GUARDS_H, 1, ReviewVerdict.Decision.CHANGES, host.baseCommit);

        assertEquals(Optional.of(ReviewVerdict.Decision.CHANGES), sections.decisionOf(board(), GUARDS_H));
    }

    @Test
    void aFileNotInTheDiffHasNoDecision() {
        assertEquals(Optional.empty(), sections.decisionOf(board(), "src/nowhere.cpp"));
    }

    // ---- staleness has three states, not two --------------------------------

    @Test
    void aVerdictAgainstTheCurrentBaseIsFresh() {
        approve(GUARDS_H);

        assertEquals(SectionStates.Staleness.FRESH, sections.stalenessOf(board(), GUARDS_H));
    }

    @Test
    void aBaseMoveTouchingTheFileIsMoved() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);

        assertEquals(SectionStates.Staleness.MOVED, sections.stalenessOf(board(), GUARDS_H));
    }

    /**
     * A stale verdict does not count as settled -- Submit refuses it -- but
     * the file's decision still merges it: only its freshness is in
     * question, not what was decided.
     */
    @Test
    void settledHunksExcludesAStaleOneButTheDecisionStillMergesIt() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        record(GUARDS_H, 0, ReviewVerdict.Decision.APPROVED, OLD_BASE);
        approve(GUARDS_H, 1);

        assertEquals(1, sections.settledHunkCount(board()), "the stale hunk 0 must not be counted");
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), sections.decisionOf(board(), GUARDS_H),
                "the decision persists across staleness");
    }

    /** Per file: a move touching guards.h does not stale guards.cpp's approval. */
    @Test
    void aBaseMoveTouchingAnotherFileLeavesThisOneFresh() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        record(GUARDS_CPP, ReviewVerdict.Decision.APPROVED, OLD_BASE);

        assertEquals(SectionStates.Staleness.FRESH, sections.stalenessOf(board(), GUARDS_CPP));
    }

    /** A move that provably could not matter must not spend the reader's attention. */
    @Test
    void aBaseMoveElsewhereIsFresh() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);

        assertEquals(SectionStates.Staleness.FRESH, sections.stalenessOf(board(), GUARDS_H));
    }

    /**
     * The delta is unresolvable while it is still being computed off the FX
     * thread, and when the old base can no longer be diffed. Neither is
     * evidence that the base moved, and rendering them as one would put a
     * confirm-me banner on a review nobody has touched.
     */
    @Test
    void anUnresolvableDeltaIsUnknownNotMoved() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);

        assertEquals(SectionStates.Staleness.UNKNOWN, sections.stalenessOf(board(), GUARDS_H));
    }

    /** One hunk known to have moved is the strongest thing true of the file. */
    @Test
    void aKnownMoveOutranksAnUnknownOne() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        host.baseDeltaByRecordedBase.put("9".repeat(40), new BaseMove.Delta(true, new TreeSet<>()));
        record(GUARDS_H, 0, ReviewVerdict.Decision.APPROVED, "9".repeat(40));
        record(GUARDS_H, 1, ReviewVerdict.Decision.APPROVED, OLD_BASE);

        assertEquals(SectionStates.Staleness.MOVED, sections.stalenessOf(board(), GUARDS_H));
    }

    // ---- an agent may add staleness, never take it away (spec 9.7) ----------

    /**
     * The blind spot {@link BaseMove} names in its own class comment: the
     * intersection is file-level and lexical, so a base commit that changes
     * behaviour without touching this file reads as FRESH. An agent's {@code
     * affected} recheck is the only thing that can close it.
     */
    @Test
    void anAgentsAffectedRecheckMarksAMoveTheFileFilterDismissed() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        SectionStates.Board board = board();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);
        assess(GUARDS_H, true, OLD_BASE);

        assertEquals(SectionStates.Staleness.MOVED, sections.stalenessOf(board, GUARDS_H));
        assertEquals(0, sections.settledHunkCount(board),
                "a hunk the agent marked must not count as settled either");
    }

    /**
     * <strong>The asymmetry.</strong> The filter already found this move, and
     * an agent saying "unaffected" must not take that back: an agent wrong
     * THAT way leaves a human's approval standing over code nobody re-read.
     */
    @Test
    void anAgentsUnaffectedRecheckDoesNotClearAMoveTheFilterFound() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = board();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);
        assess(GUARDS_H, false, OLD_BASE);

        assertEquals(SectionStates.Staleness.MOVED, sections.stalenessOf(board, GUARDS_H));
        assertEquals(0, sections.settledHunkCount(board),
                "an agent's advice must not re-settle a hunk the base moved under");
    }

    /** Nor may it clear the weaker "cannot tell" the same way. */
    @Test
    void anAgentsUnaffectedRecheckDoesNotClearAnUnresolvableDelta() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);
        assess(GUARDS_H, false, OLD_BASE);

        assertEquals(SectionStates.Staleness.UNKNOWN, sections.stalenessOf(board(), GUARDS_H));
    }

    /** An affected recheck DOES outrank "cannot tell": it only ever adds reading. */
    @Test
    void anAgentsAffectedRecheckOutranksAnUnresolvableDelta() {
        host.baseDelta = new BaseMove.Delta(true, new TreeSet<>());
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);
        assess(GUARDS_H, true, OLD_BASE);

        assertEquals(SectionStates.Staleness.MOVED, sections.stalenessOf(board(), GUARDS_H));
    }

    /**
     * An assessment is about one base PAIR. A recheck of an older move is not
     * an answer about this one.
     */
    @Test
    void anAgentsRecheckOfADifferentBasePairIsNotConsulted() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);
        host.store.putAssessment(new RecheckAssessment(scope.id(),
                digestOf(GUARDS_H), "9".repeat(40), host.baseCommit, true, "why", Instant.EPOCH));

        assertEquals(SectionStates.Staleness.FRESH, sections.stalenessOf(board(), GUARDS_H));
    }

    /**
     * A recheck cannot invent staleness where the base never moved: it widens
     * what counts as a move that matters, it does not decide that one happened.
     */
    @Test
    void anAgentsAffectedRecheckCannotStaleAVerdictAgainstTheCurrentBase() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        approve(GUARDS_H);
        assess(GUARDS_H, true, host.baseCommit);

        assertEquals(SectionStates.Staleness.FRESH, sections.stalenessOf(board(), GUARDS_H));
    }

    /**
     * A base commit touching a file this one does not change but DOES
     * reference can have moved the ground under an approval, and only the
     * change graph -- when already in hand -- makes that visible (spec §9.2).
     * The move touches only Guards.java, which Profiler.java references.
     */
    @Test
    void aBaseMoveTouchingAReferencedButUnchangedFileIsMoved() {
        UnifiedDiff graphDiff = new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }"),
                file("src/Profiler.java", "void go() { new JmpCtxScope(); }")));
        ChangeGraph graph = ChangeGraph.of(graphDiff);
        SectionStates.Board board = new SectionStates.Board(scope, graphDiff, Optional.of(graph));
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("src/Guards.java")));
        record(graphDiff, "src/Profiler.java", 0, ReviewVerdict.Decision.APPROVED, OLD_BASE);

        assertEquals(SectionStates.Staleness.MOVED, sections.stalenessOf(board, "src/Profiler.java"));
        assertEquals(SectionStates.Staleness.FRESH,
                sections.stalenessOf(new SectionStates.Board(scope, graphDiff), "src/Profiler.java"),
                "without the graph only the file itself counts");
    }

    @Test
    void settledHunkCountExcludesAStaleVerdict() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, OLD_BASE);
        approve(GUARDS_CPP);
        approve(PROFILER);

        assertEquals(2, sections.settledHunkCount(board()),
                "the stale GUARDS_H verdict must not count toward progress");
    }

    @Test
    void oldBaseOfNamesTheStaleVerdictsBase() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        approve(GUARDS_H, 0);
        record(GUARDS_H, 1, ReviewVerdict.Decision.APPROVED, OLD_BASE);

        assertEquals(OLD_BASE, sections.oldBaseOf(board(), GUARDS_H));
    }

    /**
     * Digests are memoized per diff INSTANCE: a re-diff that changed a
     * file's content must not be answered with the previous diff's hunks.
     */
    @Test
    void aNewDiffIsNotServedFromTheDigestMemo() {
        assertEquals(List.of(digestOf(GUARDS_CPP)), sections.digestsOfFile(board(), GUARDS_CPP));

        UnifiedDiff changed = new UnifiedDiff(List.of(file(GUARDS_CPP, "void uninstall();")));
        assertEquals(List.of(digestOf(changed, GUARDS_CPP, 0)),
                sections.digestsOfFile(new SectionStates.Board(scope, changed), GUARDS_CPP));
        assertNotEquals(digestOf(GUARDS_CPP), digestOf(changed, GUARDS_CPP, 0));
    }

    // ---- what a/r act on (spec §9.6) ------------------------------------------

    /** A gutter selection resolves to the hunk containing that exact line, in any file. */
    @Test
    void digestOfCurrentHunkPrefersTheGutterSelection() {
        assertEquals(Optional.of(digestOf(GUARDS_CPP)),
                sections.digestOfCurrentHunk(board(), GUARDS_H, Optional.of(GUARDS_CPP + " n1")));
    }

    /**
     * With nothing selected, {@code a} must not always answer hunk one: with
     * the first hunk settled, the next press has to reach the file's first
     * UNSETTLED hunk.
     */
    @Test
    void digestOfCurrentHunkFallsBackToTheFirstUnsettledHunk() {
        approve(GUARDS_H, 0);

        assertEquals(Optional.of(digestOf(GUARDS_H, 1)),
                sections.digestOfCurrentHunk(board(), GUARDS_H, Optional.empty()));
    }

    /** Once every hunk is settled, the file's first hunk is the last fallback left. */
    @Test
    void digestOfCurrentHunkFallsBackToTheFirstHunkWhenEverythingIsSettled() {
        approve(GUARDS_H, 0);
        approve(GUARDS_H, 1);

        assertEquals(Optional.of(digestOf(GUARDS_H, 0)),
                sections.digestOfCurrentHunk(board(), GUARDS_H, Optional.empty()));
    }

    /** A stale key -- selected line no longer in the diff -- is not trusted; the walk continues. */
    @Test
    void digestOfCurrentHunkIgnoresASelectionTheDiffNoLongerHas() {
        assertEquals(Optional.of(digestOf(GUARDS_H)),
                sections.digestOfCurrentHunk(board(), GUARDS_H, Optional.of(GUARDS_H + " n999")));
    }

    @Test
    void currentFileOfIsTheCursorsFileWhenNothingIsSelected() {
        assertEquals(PROFILER, sections.currentFileOf(PROFILER, Optional.empty()));
    }

    /** A gutter selection wins over the cursor's file. */
    @Test
    void currentFileOfPrefersTheGutterSelection() {
        assertEquals(GUARDS_CPP, sections.currentFileOf(GUARDS_H, Optional.of(GUARDS_CPP + " n1")));
    }

    @Test
    void digestsOfFileCoversEveryHunkOfTheFile() {
        assertEquals(List.of(digestOf(GUARDS_H, 0), digestOf(GUARDS_H, 1)),
                sections.digestsOfFile(board(), GUARDS_H));
    }

    @Test
    void digestsOfFileIsEmptyForAFileNotInTheDiff() {
        assertTrue(sections.digestsOfFile(board(), "src/nowhere.cpp").isEmpty());
    }

    @Test
    void fileOfDigestFindsTheHunksFile() {
        assertEquals(Optional.of(GUARDS_H), sections.fileOfDigest(board(), digestOf(GUARDS_H, 1)));
        assertEquals(Optional.empty(), sections.fileOfDigest(board(), "not-a-digest"));
    }

    @Test
    void digestsForActionIsJustTheOneHunk() {
        assertEquals(List.of(digestOf(GUARDS_H)),
                sections.digestsForAction(board(), GUARDS_H, false, Optional.empty()));
    }

    /** {@code wholeFile} is ⇧A/⇧R: every hunk of the file. */
    @Test
    void digestsForActionWithWholeFileIsEveryHunkOfTheFile() {
        assertEquals(List.of(digestOf(GUARDS_H, 0), digestOf(GUARDS_H, 1)),
                sections.digestsForAction(board(), GUARDS_H, true, Optional.empty()));
    }

    // ---- the automatic recheck a base move earns (spec §9.7) ----------------

    /**
     * A move that stales an approval asks the agent about it, naming the base
     * PAIR the approval was recorded against and the base it now faces.
     */
    @Test
    void aBaseMoveThatStalesAnApprovalAsksTheAgentOnce() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        RecheckDispatch dispatch = new RecheckDispatch();

        sections.requestRechecks(board, dispatch);
        sections.requestRechecks(board, dispatch);

        assertEquals(2, host.recheckDispatches.size());
    }

    /**
     * Relevance-gated: a move touching nothing this scope reads leaves every
     * file FRESH, and a fresh file has no disturbed approval to ask
     * about. Without this every base move spends a subagent.
     */
    @Test
    void aMoveThatCouldNotMatterAsksNothing() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md")));
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, SessionReviewView.UNRESOLVED_BASE);

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty(),
                "no agent can read what changed between 'unresolved' and a commit");
    }

    /** The mirror: an unresolved CURRENT base names no pair either. */
    @Test
    void anUnresolvedCurrentBaseAsksNothing() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H)));
        SectionStates.Board board = board();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        host.baseCommit = SessionReviewView.UNRESOLVED_BASE;

        sections.requestRechecks(board, new RecheckDispatch());

        assertTrue(host.recheckDispatches.isEmpty());
    }

    /**
     * <strong>Relevance is per approval, not per file.</strong> Two
     * approvals recorded at different bases: one move is resolved and could
     * matter, the other is still in flight. Gating on anything coarser than
     * the approval let the resolved one drag the unresolved one into the
     * dispatch -- asking the agent about a move before git had said whether
     * it mattered, with the claim permanent.
     */
    @Test
    void aNeighbourWhoseMoveIsStillInFlightIsNotDraggedIn() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H, GUARDS_CPP)));
        host.baseDeltaByRecordedBase.put("9".repeat(40), new BaseMove.Delta(true, new TreeSet<>()));
        SectionStates.Board board = board();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        record(GUARDS_CPP, ReviewVerdict.Decision.APPROVED, "9".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertEquals(List.of("0".repeat(40) + "->" + host.baseCommit), host.recheckDispatches,
                "only the move git has actually answered for earns a recheck");
    }

    /** The same, for a neighbour whose move is RESOLVED and provably irrelevant. */
    @Test
    void aNeighbourWhoseMoveCouldNotMatterIsNotDraggedIn() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H, GUARDS_CPP)));
        host.baseDeltaByRecordedBase.put("9".repeat(40),
                new BaseMove.Delta(false, new TreeSet<>(List.of("docs/README.md"))));
        SectionStates.Board board = board();
        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        record(GUARDS_CPP, ReviewVerdict.Decision.APPROVED, "9".repeat(40));

        sections.requestRechecks(board, new RecheckDispatch());

        assertEquals(List.of("0".repeat(40) + "->" + host.baseCommit), host.recheckDispatches,
                "a move touching only docs is exactly what the filter exists to drop");
    }

    /**
     * Two approvals recorded at two DIFFERENT older bases are two distinct
     * questions, and the loop has to emit both. Every other test here has at
     * most one stale base, so the loop was only ever exercised emitting one.
     */
    @Test
    void twoApprovalsAtDifferentOlderBasesEachEarnTheirOwnRecheck() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H, GUARDS_CPP)));
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();
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
        SectionStates.Board first = board();
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
        SectionStates.Board second = new SectionStates.Board(other, diff);

        sections.requestRechecks(second, shared);

        assertEquals(2, host.recheckDispatches.size(),
                "a different scope's identical base move is its own question");
    }

    /**
     * Only the approvals the move actually staled are asked about. A scope
     * can hold one stale hunk and one approved against the CURRENT base;
     * taking every verdict of a stale scope would ask the agent to read
     * what changed between a base and itself -- a subagent spent on an empty
     * diff.
     */
    @Test
    void aFreshApprovalBesideAStaleOneIsNotAskedAbout() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(GUARDS_H, GUARDS_CPP)));
        SectionStates.Board board = board();
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
        SectionStates.Board board = board();

        sections.requestRechecks(board, new RecheckDispatch());
        assertTrue(host.recheckDispatches.isEmpty());

        record(GUARDS_H, ReviewVerdict.Decision.APPROVED, "0".repeat(40));
        sections.requestRechecks(board, new RecheckDispatch());
        assertEquals(1, host.recheckDispatches.size(),
                "positive control: the same fixture DOES ask once an approval exists");
    }

    // ---- helpers -------------------------------------------------------------

    private SectionStates.Board board() {
        return new SectionStates.Board(scope, diff);
    }

    private void approve(String file) {
        approve(file, 0);
    }

    private void approve(String file, int hunk) {
        record(file, hunk, ReviewVerdict.Decision.APPROVED, host.baseCommit);
    }

    /**
     * An agent's recheck of the move from {@code fromBase} to the scope's
     * current base, as {@code review_recheck} records one -- against the
     * hunk's content DIGEST, which is the only thing the board ever looks a
     * recheck up by.
     */
    private void assess(String file, boolean affected, String fromBase) {
        host.store.putAssessment(new RecheckAssessment(scope.id(),
                digestOf(file), fromBase, host.baseCommit, affected, "why", Instant.EPOCH));
    }

    private void record(String file, ReviewVerdict.Decision decision, String base) {
        record(file, 0, decision, base);
    }

    private void record(String file, int hunk, ReviewVerdict.Decision decision, String base) {
        record(diff, file, hunk, decision, base);
    }

    /** As {@link #record(String, int, ReviewVerdict.Decision, String)}, over a diff other than the fixture's. */
    private void record(UnifiedDiff source, String file, int hunk, ReviewVerdict.Decision decision, String base) {
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOf(source, file, hunk), decision,
                Optional.empty(), Instant.EPOCH, base, host.headCommit));
    }

    private String digestOf(String file) {
        return digestOf(file, 0);
    }

    private String digestOf(String file, int hunk) {
        return digestOf(diff, file, hunk);
    }

    private static String digestOf(UnifiedDiff source, String file, int hunk) {
        return source.files().stream()
                .filter(candidate -> candidate.path().equals(file))
                .findFirst()
                .map(candidate -> HunkDigest.of(file, candidate.hunks().get(hunk)))
                .orElseThrow();
    }

    /** One hunk per text, each on its own new-line number (index*10 + 1). */
    private static UnifiedDiff.FileDiff file(String path, String... texts) {
        List<UnifiedDiff.Hunk> hunks = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            hunks.add(new UnifiedDiff.Hunk("@@ -1 +1 @@", List.of(
                    new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                            OptionalInt.of(i * 10 + 1), texts[i]))));
        }
        return new UnifiedDiff.FileDiff(path, "M", texts.length, 0, false, false, hunks);
    }
}
