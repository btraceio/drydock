package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.git.DiffService;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpActivityLog;
import app.drydock.review.BaseMove;
import app.drydock.review.ChangeGraph;
import app.drydock.review.HunkIds;
import app.drydock.review.OutOfDiffFanIn;
import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.RecheckDispatch;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Sections;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.SubmitPlan;
import app.drydock.review.tour.AnchorIndex;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.HunkOverride;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.StepVerdicts;
import app.drydock.review.tour.TourFindings;
import app.drydock.review.tour.TourMigration;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.ui.UiErrors;
import app.drydock.ui.nav.ExplorerTrailStore;
import app.drydock.ui.nav.NavigationTrail;
import app.drydock.ui.nav.PeekLayer;
import app.drydock.ui.nav.SearchRail;
import app.drydock.ui.nav.SymbolPeek;
import app.drydock.ui.nav.SymbolPeekService;
import app.drydock.ui.nav.TrailBar;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The review board of one session's Review sub-tab (spec §3.2): the guided
 * tour or the hunk diff -- the diff column, the findings margin and the
 * verdict bar -- showing
 * exactly ONE scope -- this checkout's local changes, or the pull request its
 * branch carries -- chosen by the two chips of a {@link ReviewScopeSwitcher}.
 *
 * <p>The board itself is the one the departed Review destination rendered;
 * what is gone is everything the cross-repo queue needed around it. There is no
 * queue rail, no title bar with a back affordance, no session row and no
 * checkout gate here, because a session's Review sub-tab already knows whose
 * checkout it is looking at: the session's. The two scopes are measured for
 * that one checkout by {@link SessionReviewScopes} and handed in whole
 * through {@link #showScopes}.</p>
 *
 * <p>A pure view, like the destination before it: it is handed a {@link Host}
 * and owns none of the workspace's tab or terminal machinery.</p>
 */
public final class SessionReviewView extends BorderPane {

    private static final Logger LOG = System.getLogger(SessionReviewView.class.getName());

    /** What the view needs from the workspace. All calls happen on the FX thread. */
    public interface Host {

        /**
         * The centre body for {@code scope}, or empty for the built-in
         * placeholder. This is the seam the diff column arrives through.
         */
        Optional<Region> bodyFor(ReviewScope scope);

        /** Open findings for {@code scope}; empty when no reviewer has run (spec §4.1). */
        Optional<Integer> openFindings(ReviewScope scope);

        /** The {@code ?} button -- shows the shared shortcuts overlay. */
        void showShortcuts();

        /**
         * {@code ⤢} -- opens {@code file} at a 1-based line in the Explorer of
         * the session bound to {@code scope}. False when there is nowhere to
         * open it (no session, or its tab is closed).
         */
        boolean openInExplorer(ReviewScope scope, Path file, int line);

        /** Every finding of {@code scope}, newest state (the store is the truth). */
        List<ReviewAnnotation> findings(ReviewScope scope);

        /**
         * The verdict recorded on one hunk, if any -- keyed by the hunk's
         * content digest, never by a grouping. A file or a tour step has no
         * verdict of its own: what it shows is what its hunks merge to, which
         * is this view's job to derive (spec §9.2).
         */
        Optional<ReviewVerdict> verdict(ReviewScope scope, String hunkDigest);

        /**
         * Records one verdict per hunk in {@code hunkDigests}; {@code decision}
         * empty undoes them all.
         *
         * <p>{@code hunkDigests} is computed by the caller rather than by the
         * host: only this view knows which diff the human is actually looking
         * at, and a host free to re-derive them is free to derive them from a
         * different one. {@code blocked} comes along for the same reason: the
         * host refuses an {@code APPROVED} decision while it is true (spec
         * §4.6), and computing it here, with the rule the verdict bar renders
         * from ({@link #blockingFindingOpen}), is what keeps the write path
         * from refusing a keypress the bar just showed as clear.</p>
         */
        void setVerdict(ReviewScope scope, List<String> hunkDigests,
                        Optional<ReviewVerdict.Decision> decision, boolean blocked);

        /**
         * "Confirm still good" (spec §9.2): rewrites each of {@code
         * hunkDigests}' existing verdict to record it against the scope's
         * CURRENT base and head rather than the one it was judged against,
         * through {@link ReviewVerdict#confirmedAgainst}. A digest with no
         * recorded verdict is left alone -- there is nothing stale to
         * confirm. Rewriting the base rather than clearing the verdict is
         * the point: the decision survives, only the staleness does not.
         */
        void confirmStillGood(ReviewScope scope, List<String> hunkDigests);

        /**
         * The commit {@code scope}'s base ref resolves to now, or {@link
         * #UNRESOLVED_BASE} when it cannot be resolved.
         *
         * <p>A commit, never the ref name: {@link
         * ReviewVerdict#staleAgainst} is {@code !baseCommit.equals(currentBase)},
         * so a verdict recorded against {@code "main"} and compared against
         * {@code "main"} could never be stale and staleness would be an inert
         * no-op. {@code "unresolved"} can equal no real sha, so a scope whose
         * base cannot be resolved reads as stale until a human confirms it --
         * fail-safe with no second code path.</p>
         */
        String currentBase(ReviewScope scope);

        /**
         * What moved between {@code recordedBase} and {@code scope}'s current
         * base, so a base move that provably could not touch a file does
         * not spend the reader's attention on it (see {@link BaseMove}).
         *
         * <p>Called on the FX thread, so it must never block: a host that
         * cannot answer yet returns an {@link BaseMove.Delta#unresolvable}
         * delta -- "could matter", the safe direction -- rather than a
         * confident empty one.</p>
         */
        BaseMove.Delta baseMove(ReviewScope scope, String recordedBase);

        /**
         * Whether an agent said, through {@code review_recheck}, that the move
         * from {@code fromBase} to {@code toBase} undermines the approval on
         * {@code hunkDigest} (spec §9.7).
         *
         * <p>Consulted only to ADD staleness. {@link BaseMove}'s intersection
         * is file-level and lexical and names its own blind spot -- a base
         * change that alters behaviour without touching a file this scope
         * references -- and this is how that blind spot closes. The other
         * direction does not exist: an agent's "unaffected" is advice, and a
         * board that let it clear a verdict would leave a human's approval
         * standing over code nobody re-read. So false and "never asked" are
         * one answer here, deliberately.</p>
         *
         * <p>Keyed by the base PAIR, so a later base move is a new question
         * rather than an old answer carried forward.</p>
         */
        boolean assessedAffected(ReviewScope scope, String hunkDigest, String fromBase, String toBase);

        /**
         * Asks the scope's agent which approvals the move from {@code
         * fromBase} to {@code toBase} actually disturbed, so the assessment is
         * usually already there when the reviewer returns rather than arriving
         * after a wait exactly when they wanted to move on (spec §9.7).
         *
         * <p>False when the hand-off did not happen -- no bound session, or
         * its tab is not open -- exactly like {@link #runReview} and {@link
         * #askAgentToFix}. The caller must not record a dispatch it did not
         * make: nobody is watching an automatic recheck, so a failure swallowed
         * here is a scope that silently never gets one.</p>
         */
        boolean dispatchRecheck(ReviewScope scope, String fromBase, String toBase);

        /**
         * Asks the scope's agent to judge the reviewer's free-text answer to
         * {@code checkId}. The prompt carries only the check id; the agent
         * reads the answer through {@code review_state}. False when the
         * hand-off did not happen (no bound session, or its tab is not open).
         */
        boolean dispatchRiskCheck(ReviewScope scope, String checkId);

        /**
         * Asks the scope's agent to bring its tour onto a diff that moved:
         * re-issue {@code staleStepIds} and add steps for {@code
         * uncoveredHunks} uncovered hunks ({@code
         * ReviewInstructions.forTourRefresh}). False when the hand-off did
         * not happen (no bound session, or its tab is not open or its agent
         * has exited), so the caller can release its claim.
         */
        boolean dispatchTourRefresh(ReviewScope scope, List<String> staleStepIds, int uncoveredHunks);

        /** What the scope's agent is doing; UNKNOWN when it reports nothing (Codex, Pi). */
        SessionActivity agentActivity(ReviewScope scope);

        /**
         * Whether this scope's agent may be asked for a recheck WITHOUT a
         * human having asked (spec §9.7: "inline harnesses simply do not get
         * one").
         *
         * <p>An automatic dispatch types a prompt into a live terminal with
         * nobody watching. A harness that can run it in a subagent absorbs
         * that; an inline one would have it land in the middle of whatever it
         * was doing. The two providers that lack subagents also report no
         * activity at all, so there is no idle signal to wait for -- the
         * choice is dispatch-regardless or do not dispatch, and the spec
         * chose.</p>
         */
        boolean supportsAutomaticRecheck(ReviewScope scope);

        /**
         * Whether the agent has ALREADY answered about this exact base pair,
         * whatever it said.
         *
         * <p>Not {@link #assessedAffected}, which folds "said unaffected" and
         * "never asked" into one answer on purpose. Here the two must be told
         * apart: this is the durable half of the dispatch guard, and it is
         * what stops an app restart -- which empties the in-memory claim --
         * from re-asking a question whose answer is already on disk.</p>
         */
        boolean assessedMove(ReviewScope scope, String fromBase, String toBase);

        /** Resolve / Reopen one finding. */
        void setResolved(ReviewScope scope, ReviewAnnotation finding, boolean resolved);

        /** Appends a human message to a thread (Reply, and the ASK chips). */
        void postMessage(ReviewScope scope, ReviewAnnotation finding, String body);

        /**
         * Records a comment the human wrote against a line or range, minted
         * by the diff column's gutter composer.
         *
         * <p>A comment and a reviewer's finding are the same thing in this
         * model and differ only by author (see {@link ReviewAnnotation}), so
         * this lands in the same store the margin and the {@code ◆n} pins
         * already render from -- there is no second kind of note to keep in
         * sync.</p>
         *
         */
        void addComment(ReviewScope scope, ReviewAnnotation annotation);

        /**
         * The card's include/exclude toggle, for any finding -- including one
         * authored by "You"; see {@link ReviewFindingsMargin.Host#setPostToPr}.
         */
        void setPostToPr(ReviewScope scope, ReviewAnnotation finding, boolean post);

        /**
         * Records the human's triage of an agent finding. A dismissal's
         * {@code reason} is appended to the thread as
         * {@code "Dismissed: <reason>"} by the host.
         */
        void setTriage(ReviewScope scope, ReviewAnnotation finding, Triage triage, Optional<String> reason);

        /** {@code Apply patch} -- a human click; drydock never applies one on its own. */
        void applyPatch(ReviewScope scope, ReviewAnnotation finding);

        /** Records the human's severity override. */
        void overrideSeverity(ReviewScope scope, ReviewAnnotation finding, Severity severity);

        /**
         * Hands open findings to the scope's bound session, under {@code
         * subject} (the file they are on) as the prompt's heading.
         * False when there is no session to hand them to (or nothing to
         * hand), so a caller can say so rather than appear to have asked --
         * the same contract, and for the same reason, as {@link
         * #openInExplorer}: a control that reports nothing when it did
         * nothing is the silent failure this branch has now had to fix
         * three times.
         */
        boolean askAgentToFix(ReviewScope scope, String subject, List<ReviewAnnotation> findings);

        /**
         * The tour's "Send back to the author": hands confirmed blocking
         * findings to the scope's bound session through the same path as
         * {@link #askAgentToFix}, marking them sent. False when nothing was
         * handed over (no session, or nothing to send).
         */
        boolean sendFindingsToAuthor(ReviewScope scope, List<ReviewAnnotation> findings);

        /**
         * Posts the review once every hunk is settled. {@code index}
         * locates every finding's lines in the real diff (built from {@link
         * ReviewDiffColumn#displayedDiff()}, not the rendered rows -- see
         * {@link SubmitPlan.DiffIndex}), and {@code decisions} carries one
         * {@link ReviewVerdict.Decision} per file with hunks -- what its
         * hunks' verdicts merge to -- in diff order, the order {@link
         * #submitReview()} walked them in to confirm every one was decided.
         * Both live here, rather than being
         * recomputed by the host, because only this view can see a diff row
         * at all: {@code MainWorkspace} (package {@code app.drydock.ui}) has
         * no visibility into {@code app.drydock.ui.review}'s
         * package-private types.
         */
        default void submit(ReviewScope scope, SubmitPlan.DiffIndex index, List<ReviewVerdict.Decision> decisions) {
            submit(scope, index, decisions, (file, lineKey) -> Optional.empty(),
                    new ReviewSubmitSheet.Unverified(0, 0, 0));
        }

        /**
         * As {@link #submit(ReviewScope, SubmitPlan.DiffIndex, List)}, plus
         * what lets a comment on a line outside the review diff travel in the
         * review body: {@code lineText(file, lineKey)} is that line's text
         * from the whole-file diff, and {@code unverified} is what the review
         * approves without verification, for the submit sheet to state.
         */
        void submit(ReviewScope scope, SubmitPlan.DiffIndex index, List<ReviewVerdict.Decision> decisions,
                    BiFunction<String, String, Optional<String>> lineText, ReviewSubmitSheet.Unverified unverified);

        /**
         * Runs the selected reviewer against {@code scope}: grants it the
         * scope handle and asks it to review. False when it cannot run (no
         * reviewer, or no session to run it in).
         */
        boolean runReview(ReviewScope scope);

        /** {@code scope}'s guided tour with its progress, if the agent has posted one. */
        Optional<TourRecord> tour(ReviewScope scope);

        /** Applies {@code transform} to {@code scope}'s tour record; nothing when there is none. */
        void updateTour(ReviewScope scope, UnaryOperator<TourRecord> transform);

        /**
         * Brings {@code scope}'s stored hunk verdicts in line with what the
         * tour derives ({@link StepVerdicts#derive}): an empty decision
         * clears the hunk's verdict. Only hunks whose stored decision differs
         * are written, so a derivation that changed nothing writes nothing.
         */
        void applyTourVerdicts(ReviewScope scope, Map<String, Optional<ReviewVerdict.Decision>> byDigest);

        /**
         * What the tour navigates with (spec §5): the checkout peeks and
         * search read, and where the session's trail persists. Empty when
         * {@code scope} has no local checkout or no session -- the column
         * then keeps its diff-local symbol lens and the trail stays in
         * memory.
         */
        Optional<ReviewNavigation> navigation(ReviewScope scope);

        /**
         * A peek's {@code a}: asks the session bound to {@code scope} about
         * {@code peek}, as the Explorer's peek does. False when there is no
         * running session to ask.
         */
        boolean askAgentAboutPeek(ReviewScope scope, SymbolPeek peek);
    }

    /**
     * The base a scope resolves to when its ref cannot be resolved at all --
     * a branch that is not in this checkout, or a git that would not run.
     *
     * <p>A literal string rather than an {@code Optional} or a sentinel with
     * its own comparison rule: {@link ReviewVerdict#staleAgainst} already
     * asks {@code !baseCommit.equals(currentBase)}, and no real sha can equal
     * this, so an unresolvable base reads as stale through the code path that
     * was already there. Fail-safe by construction.</p>
     */
    public static final String UNRESOLVED_BASE = "unresolved";

    private final Host host;
    private final ReviewScopeSwitcher switcher = new ReviewScopeSwitcher();
    private final ReviewDiffColumn diffColumn;
    /** What a file and the review say about themselves, derived from the hunks' verdicts. */
    private final SectionStates sections;

    /**
     * Which base moves have already had their automatic recheck sent (spec
     * §9.7). Lives here, not in the store: a dispatch in flight is invisible
     * to {@code assessedAffected}, and the render pass that sends it runs many
     * times per move.
     */
    private final RecheckDispatch recheckDispatch = new RecheckDispatch();
    private final ReviewFindingsMargin margin;
    private final ReviewVerdictBar verdictBar;
    private final TourKeyStrip keyStrip = new TourKeyStrip(this::toggleKeyHints);
    /** Where the hide/show preference lives; null until the workspace wires it (tests, a bare board). */
    private BooleanSupplier keyHintsHiddenSource;
    private Consumer<Boolean> onKeyHintsHiddenChanged = hidden -> { };
    private boolean keyHintsHiddenLocal;

    /** The MCP activity panel; absent when no server is running (tests, headless). */
    private final Optional<ReviewMcpActivityPanel> mcpPanel;

    /**
     * What each scope's diff attempt produced, keyed by scope id. A scope
     * absent from this map has no diff -- which is a state, not a reason to
     * reach for someone else's.
     *
     * <p>This is also the chip switch's cache: {@link #bodyFor} renders a
     * {@link DiffOutcome.Loaded} entry straight into the column rather than
     * re-scoping it, so flipping between local and the PR and back does not
     * run git again. That is not merely an optimisation -- re-scoping
     * publishes {@link DiffOutcome.Diffing} over the entry the moment it is
     * asked for, so the cache would destroy itself on the first switch and
     * empty the verdict bar and the file count on every one after it.</p>
     */
    private final Map<String, DiffOutcome> outcomeByScope = new HashMap<>();

    /**
     * Virtual threads for building a scope's {@link ChangeGraph} -- off the
     * FX thread, because {@link ChangeGraph#of} parses every changed file
     * and can trigger a first-time native grammar load. Separate from any
     * git-lookup executor purely so a stack trace says which of the two is
     * stuck.
     */
    private static final Executor SECTION_GRAPH_EXECUTOR =
            runnable -> Thread.ofVirtual().name("drydock-section-graph").start(runnable);

    /**
     * Each scope's {@link ChangeGraph}, once built. Absent while none has
     * been requested yet, or one is still building -- the link footers, the
     * tour's impact and staleness widening all do without it meanwhile.
     */
    private final Map<String, ChangeGraph> graphByScope = new HashMap<>();

    /**
     * Guards a superseded graph build from overwriting a newer one: bumped
     * every time a fresh diff for a scope starts a new build, and checked
     * before the result is published. Without it, a scope re-diffed twice in
     * quick succession could have its second, current diff's graph
     * overwritten by the first, slower build finishing last.
     */
    private final Map<String, Integer> graphGenerationByScope = new HashMap<>();

    /**
     * The diff instance each scope's current (or in-flight) graph was built
     * from, so {@link #requestGraph} can tell "a genuinely new diff landed"
     * from "the same cached {@code Loaded} outcome was re-published" -- a
     * scope switch back to a cached diff re-publishes the SAME {@link
     * UnifiedDiff} object through {@code onDiffResolved} (see {@link
     * #outcomeByScope}'s own javadoc on exactly why), and re-parsing an
     * unchanged diff through {@link ChangeGraph#of} on every such switch
     * would waste the very work that cache exists to avoid.
     */
    private final Map<String, UnifiedDiff> graphedDiffByScope = new HashMap<>();

    /** Scopes with a {@link ChangeGraph} build currently in flight. */
    private final Set<String> graphBuilding = new HashSet<>();

    /**
     * Why the last {@link ChangeGraph} build for a scope failed, and for
     * which diff: the step panel's impact says so instead of waiting for a
     * graph that is not coming. Cleared when a build starts.
     */
    private final Map<String, GraphFailure> graphFailureByScope = new HashMap<>();

    private record GraphFailure(UnifiedDiff diff, String reason) {
    }

    /** {@link ChangeGraph#of}, unless a test swapped it through {@link #diagSetGraphBuilder}. */
    private volatile Function<UnifiedDiff, ChangeGraph> graphBuilder = ChangeGraph::of;

    /**
     * Set by {@link #close()}. A graph build already running when a view
     * closes is left to finish -- there is no cancelling a virtual thread
     * mid-parse -- but its completion must not still touch this view's state
     * or post to the FX thread afterwards: a closed view's {@link
     * SessionReviewView} instances pile up across a test suite (a fresh one
     * per test method), and an unguarded completion queues a {@code
     * Platform.runLater} for every one of them that outlives its own test,
     * competing for the FX thread with whatever runs next.
     */
    private volatile boolean closed;

    /**
     * What a scope's fan-in is until its scan has actually run: {@code
     * unavailable=true} with nothing measured, the honest input for a signal
     * nothing has measured yet. It adds nothing to {@link ReadingPath#of}'s
     * rank, and a step's measured impact shows it as callers unavailable,
     * not as callers that do not exist (spec §4.3).
     */
    private static final OutOfDiffFanIn.Result FAN_IN_NOT_SCANNED =
            new OutOfDiffFanIn.Result(Map.of(), true);

    /**
     * Each scope's out-of-diff fan-in scan, once it has finished. Absent
     * until then, which {@link #fanInFor} reads as {@link
     * #FAN_IN_NOT_SCANNED}.
     *
     * <p>Populated off the FX thread on {@link #SECTION_GRAPH_EXECUTOR},
     * from {@link #requestGraph}'s own completion: {@link
     * OutOfDiffFanIn#scan} spawns a blocking {@code git grep} with a 30s
     * timeout, and it needs the {@link ChangeGraph}'s changed declarations
     * as its patterns, so it can neither run on the FX thread nor run
     * before the graph exists.</p>
     */
    private final Map<String, OutOfDiffFanIn.Result> fanInByScope = new HashMap<>();

    /**
     * Guards a superseded fan-in scan from overwriting a newer one, exactly
     * as {@link #graphGenerationByScope} does for the graph build -- the
     * scan is the slower of the two, so the window it is stale in is wider.
     */
    private final Map<String, Integer> fanInGenerationByScope = new HashMap<>();

    /**
     * Diagnostics: the thread the last fan-in scan actually ran on.
     *
     * <p>Recorded rather than assumed. "It runs off the FX thread" is the one
     * property of this scan a reader cannot see and a refactor can silently
     * take away -- {@code Sections.of} on the FX thread already froze this
     * board for ~2.7 seconds once -- so it is written down where a test can
     * assert it. Volatile: written on a virtual thread, read on the FX one.</p>
     */
    private volatile String fanInScanThread;

    /**
     * {@link #currentPath()}'s last computed result, reused across calls
     * while its inputs are unchanged -- {@link ReadingPath#of} runs {@link
     * Sections#of} first and is, like it, string work over an already-built
     * graph rather than something to pay for on every keystroke.
     */
    private PathCacheEntry pathCache;

    private static final ReadingPath.Path EMPTY_PATH = new ReadingPath.Path(List.of(), List.of());

    /**
     * One completed {@link #currentPath()} lookup, keyed by what it was
     * computed from -- the fan-in result included, so the path recomputes
     * once a scan lands rather than serving the pre-scan order forever.
     */
    private record PathCacheEntry(String scopeId, UnifiedDiff diff, ChangeGraph graph,
                                  OutOfDiffFanIn.Result fanIn, ReadingPath.Path path) {
    }

    /** The scopes this session offers, once {@link SessionReviewScopes} has measured them. */
    private Optional<SessionReviewScopes.Scopes> scopes = Optional.empty();

    /**
     * Which chip is showing. Normalized against {@link #scopes} on every
     * render, so a choice persisted from a session whose PR has since been
     * merged reads back as {@link SessionReviewScopes.Choice#LOCAL} rather
     * than selecting a scope that is not there.
     */
    private SessionReviewScopes.Choice choice = SessionReviewScopes.Choice.LOCAL;

    /** Told when the human picks the other chip, so the choice can be persisted. */
    private Consumer<SessionReviewScopes.Choice> onChoiceChanged = ignored -> { };

    /**
     * The file the hunk diff's cursor is on; {@code [} / {@code ]} / {@code
     * n} move it. A path rather than an index, so a re-diff that adds or
     * drops a file ahead of it does not move the reader. Null, or a path the
     * diff no longer has, reads as the first file (see {@link #currentFile}).
     */
    private String filePath;

    /**
     * Which of {@link #filePath}'s hunks was last revealed -- where {@code n}
     * resumes its walk for the next unread hunk.
     */
    private int hunkCursor;

    /**
     * The file last put on the verdict bar ({@link #showFileOnBar}), or null
     * while the bar shows nothing or a tour step. The bar itself only holds a
     * label and an id; its buttons resolve back to this.
     */
    private String verdictBarFile;

    /**
     * The file {@code a}/{@code r} last recorded a verdict in, so {@code u}
     * can snap the cursor back to it -- see {@link #undoVerdict}. Not touched
     * by {@code [}/{@code ]}/{@code n}: moving around must not change what
     * {@code u} targets.
     */
    private Optional<String> lastSettledFile = Optional.empty();

    /**
     * The EXACT digests {@code a}/{@code r} last recorded a verdict on, so
     * {@code u} clears exactly those and nothing more -- one hunk, or a
     * whole file for {@code ⇧A}/{@code ⇧R}. Cleared once undone, so a
     * second {@code u} is inert rather than reaching for something else.
     */
    private List<String> lastSettledDigests = List.of();

    /** The mode {@link #applyMode} last applied; null before the first. */
    private ReviewMode appliedMode;

    /** Set by {@code m}/{@code f}; remembered independently of the responsive collapse. */
    private boolean marginCollapsedByUser;

    /** Set by {@code f} and cleared by the outline's expand button; independent of the responsive collapse. */
    private boolean outlineCollapsedByUser;

    private final Label countsLabel = new Label();

    /** The scope header (icon, title, comparison); hidden when there is no scope. */
    private VBox itemHeader;

    /**
     * True while there is no scope to show -- {@link #showResolving} or
     * {@link #showUnavailable}.
     *
     * <p>Everything except the top bar and the centred state is hidden in that
     * case, for the reason the destination's empty surface records: a findings
     * margin claiming "Nothing flagged in this file" when there is no file,
     * and a verdict bar with dead arrows
     * and a disabled Submit are regions describing something that does not
     * exist, framing one sentence that does. A surface with nothing in it
     * should be one thing, not the full chrome with the content removed.</p>
     */
    private boolean noScope = true;

    private final Label headerIcon = new Label();
    private final Label headerTitle = new Label();
    private final Label headerContext = new Label();
    private final Button densityButton = new Button();

    /**
     * "Run review", out in the top bar rather than only inside a reviewer
     * menu. An agentic review is the thing that builds the tour and fills
     * the findings margin, and while it lived behind a menu on a chip
     * labelled with an agent's name, nothing in the surface said it could be
     * asked for at all -- so nothing ever grouped anything.
     */
    private final Button runReviewButton = new Button("▶  Run review");
    private final VBox body = new VBox();

    private ReviewDensity density = ReviewDensity.COZY;

    private Region centre;

    /**
     * Which surface the board shows (spec §5): the guided tour, or the hunk
     * diff -- diff column, findings margin. {@code v} flips
     * it; otherwise a scope with a tour (or a run building one) shows the
     * tour.
     */
    enum ReviewMode { TOUR, DIFF }

    /** The centre row: the body, then the findings margin or the step panel. */
    private HBox columns;

    private final TourController tourController;
    private final TourOutline outline;
    private final StepPanel stepPanel;

    /**
     * Peek cards over the diff column (spec §5). {@link #bodyFor} hands out
     * {@link #diffStack} wherever it used to hand out the column, so the
     * layer always sits over it.
     */
    private final PeekLayer peekLayer = new PeekLayer();
    private final StackPane diffStack = new StackPane();

    /**
     * Navigation's progress and outcome lines ("Looking for X…", "Could not
     * read X"), over the diff column. Not the step panel: the trail bar and
     * ⌘[ / ⌘] work in the hunk diff too, where the panel is not in the
     * layout, and {@code m} can collapse it in the tour.
     */
    private final Label navNotice = new Label();
    private final PauseTransition navNoticeTimer = new PauseTransition(Duration.seconds(2.6));

    /**
     * The session's trail: step changes, promoted peeks and search results
     * add waypoints, plain peeks never do. Persisted under {@link
     * ExplorerTrailStore#reviewKey} while a {@link ReviewNavigation} is
     * available; otherwise kept in memory for the scope.
     */
    private final NavigationTrail trail = new NavigationTrail();
    private final TrailBar trailBar = new TrailBar();

    /** The store key {@link #trail} was restored from; a change of key restores another trail. */
    private String trailKey;

    private Optional<ReviewNavigation> navigation = Optional.empty();

    /** The outline's Search tab; null while there is no navigation to search with. */
    private SearchRail searchRail;
    private Path searchRailRoot;

    private ReviewMode mode = ReviewMode.DIFF;

    /** Set by {@code v} (and Open diff review); cleared per scope, so a fresh scope picks its own mode. */
    private boolean userChoseMode;

    /**
     * @param activityLog the MCP traffic log the {@code \} panel renders, or
     *                    {@code null} when no server is running -- Review must
     *                    work with no agent at all, so the panel is optional
     */
    public SessionReviewView(Host host, DiffService diffService, McpActivityLog activityLog) {
        this.mcpPanel = ReviewMcpActivityPanel.createIfAvailable(activityLog);
        this.host = host;
        this.sections = new SectionStates(host);
        this.diffColumn = new ReviewDiffColumn(diffService, host::openInExplorer);
        this.margin = new ReviewFindingsMargin(new MarginHost());
        this.verdictBar = new ReviewVerdictBar(new VerdictHost());
        this.tourController = new TourController(host, new TourViewAdapter(), diffColumn, SECTION_GRAPH_EXECUTOR);
        this.outline = tourController.outline();
        this.stepPanel = tourController.stepPanel();
        getStyleClass().addAll("review-destination", "session-review");
        // Review must never hold the window open. Its computed minimum is the
        // sum of the rails' own minimums plus the code column -- so below
        // that the content pane stopped shrinking and simply overflowed the
        // right edge of the window, taking a rail off-screen with it. The
        // view has to be allowed to be as narrow as the slot it is given.
        setMinWidth(0);

        setTop(buildTopBar());
        // The centre carries the code, the margin and the verdict bar; the
        // tour's outline takes the left while touring (applyMode).
        centre = buildCenter();
        setCenter(centre);

        installNavigation();

        margin.setOnToggleCollapse(() -> setMarginCollapsed(!margin.collapsed()));
        stepPanel.setOnExpand(this::expandStepPanel);
        outline.setOnExpand(this::expandOutline);
        margin.setOnFilterChanged(filter -> refreshReviewState());
        diffColumn.setPinSource(new PinSource());
        diffColumn.setCommentSink(annotation -> selectedScope().ifPresent(scope -> {
            host.addComment(scope, annotation);
            refreshReviewState();
            diffColumn.refreshPins();
        }));
        // The file cursor is derived from the diff, and the diff arrives
        // asynchronously -- so the verdict bar has to be re-rendered when it
        // lands, or it stays on the "no file" it correctly computed from an
        // empty diff and never recovers.
        diffColumn.setOnDiffResolved((scopeId, outcome) -> {
            outcomeByScope.put(scopeId, outcome);
            boolean selected = selectedScope().map(scope -> scope.id().equals(scopeId)).orElse(false);
            if (outcome instanceof DiffOutcome.Loaded loaded) {
                // The diff column's link footers (spec §7.2) and the tour's
                // impact need this scope's graph. requestGraph is a no-op
                // for a diff instance it has already graphed or is already
                // building, so this costs nothing on a re-diff or a
                // re-selection.
                requestGraph(scopeId, loaded.diff());
                tourController.migrateTour(scopeId, loaded.diff());
            } else {
                graphByScope.remove(scopeId);
            }
            // Only the selected scope's arrival changes what is on screen;
            // a superseded one still records its outcome, so coming back to
            // it does not re-run git.
            if (selected) {
                refreshReviewState();
                revealCurrentSelection();
            }
        });

        widthProperty().addListener((obs, old, width) -> applyResponsiveLayout(width.doubleValue()));
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        setFocusTraversable(true);
        // Nothing is measured yet at construction; the sub-tab hands the
        // scopes in as soon as SessionReviewScopes answers.
        showResolving();
    }

    // ---- top bar ------------------------------------------------------------

    private Region buildTopBar() {
        countsLabel.getStyleClass().add("review-title-counts");
        switcher.setOnChoiceChanged(this::chooseScope);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        runReviewButton.getStyleClass().add("review-chip-button");
        runReviewButton.setOnAction(e -> runReviewOnSelection());
        updateRunReviewButton();

        densityButton.getStyleClass().add("review-chip-button");
        densityButton.setTooltip(new Tooltip("Density: cozy · compact · dense (d)"));
        densityButton.setOnAction(e -> cycleDensity());
        applyDensity(density);

        Button shortcuts = new Button("?");
        shortcuts.getStyleClass().add("review-chip-button");
        shortcuts.setTooltip(new Tooltip("Shortcuts (?)"));
        shortcuts.setOnAction(e -> host.showShortcuts());

        HBox bar = new HBox(8, switcher, countsLabel, spacer, runReviewButton, densityButton,
                shortcuts);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("review-title-bar");
        return bar;
    }

    // ---- centre -------------------------------------------------------------

    private Region buildCenter() {
        headerIcon.getStyleClass().add("review-item-icon");
        headerTitle.getStyleClass().add("review-item-title");
        headerContext.getStyleClass().add("review-item-context");

        Region rowSpacer = new Region();
        HBox.setHgrow(rowSpacer, Priority.ALWAYS);
        HBox row = new HBox(9, headerIcon, headerTitle, headerContext, rowSpacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("review-item-header-row");

        VBox header = new VBox(row);
        header.getStyleClass().add("review-item-header");
        itemHeader = header;

        body.getStyleClass().add("review-body");
        HBox.setHgrow(body, Priority.ALWAYS);

        // The margin sits BESIDE the code, never inline, so the diff stays
        // continuous (spec §4.5); the verdict bar sits BELOW both, so
        // collapsing the margin never takes the primary action with it.
        columns = new HBox(body, margin);
        VBox.setVgrow(columns, Priority.ALWAYS);

        VBox centre = new VBox(header, columns);
        mcpPanel.ifPresent(panel -> {
            panel.setVisible(false);
            panel.setManaged(false);
            centre.getChildren().add(panel);
        });
        // The key hints sit just above the verdict bar, in tour mode only.
        centre.getChildren().add(keyStrip);
        // The verdict bar goes last, so even with the activity panel open it
        // is still the bottom-most thing and still always present.
        centre.getChildren().add(verdictBar);
        // The trail goes below even the verdict bar, in both modes, as the
        // Explorer's does below its viewer.
        centre.getChildren().add(trailBar);
        centre.getStyleClass().add("review-centre");
        return centre;
    }

    // ---- scopes -------------------------------------------------------------

    /**
     * Shows {@code scopes} with {@code choice} selected -- the queue-driven
     * {@code showItem} of the destination, with the item replaced by the one
     * checkout's own scopes.
     *
     * <p>{@code choice} is honoured only as far as the scopes allow: asking
     * for the pull request when there is none renders, and reports, {@link
     * SessionReviewScopes.Choice#LOCAL}, exactly as {@link
     * SessionReviewScopes.Scopes#forChoice} resolves it.</p>
     */
    public void showScopes(SessionReviewScopes.Scopes scopes, SessionReviewScopes.Choice choice) {
        this.scopes = Optional.of(scopes);
        this.choice = scopes.pullRequest().isEmpty()
                ? SessionReviewScopes.Choice.LOCAL
                : choice;
        noScope = false;
        switcher.show(scopes, this.choice, host::openFindings);
        renderSelectedScope();
    }

    /** The placeholder while {@link SessionReviewScopes} is still measuring. */
    public void showResolving() {
        showNoScope("Resolving this session's review scopes…",
                "Reading the checkout's base and whether its branch carries an open pull request.");
    }

    /**
     * Scope resolution failed: no git, an unreadable checkout, or a session
     * with no checkout at all. Says which, rather than showing an empty board
     * that looks like "nothing has changed here".
     */
    public void showUnavailable(String message) {
        showNoScope("Review is not available for this session", message);
    }

    private void showNoScope(String title, String detail) {
        scopes = Optional.empty();
        noScope = true;
        switcher.clear();
        headerIcon.setText("◨");
        headerTitle.setText(title);
        headerContext.setText("");
        body.getChildren().setAll(placeholder(title, detail, ""));
        refreshReviewState();
        applyResponsiveLayout(getWidth());
    }

    /**
     * The scope the switcher has selected, or empty while there is none --
     * {@link #showResolving} and {@link #showUnavailable}.
     */
    public Optional<ReviewScope> selectedScope() {
        return scopes.map(available -> available.forChoice(choice));
    }

    /**
     * Either of this session's two scopes by id, whichever it is -- unlike
     * {@link #selectedScope}, not necessarily the one the chips show. {@code
     * onDiffResolved} only carries a scope id (a diff can resolve for the
     * scope NOT currently selected), and deciding whether to build a graph
     * for it needs the real {@link ReviewScope} to ask the host about.
     */
    private Optional<ReviewScope> scopeById(String scopeId) {
        return scopes.flatMap(available -> {
            if (available.local().id().equals(scopeId)) {
                return Optional.of(available.local());
            }
            return available.pullRequest().filter(pr -> pr.id().equals(scopeId));
        });
    }

    /** Which chip is showing, after the fallback {@link #showScopes} applies. */
    public SessionReviewScopes.Choice selectedChoice() {
        return choice;
    }

    /**
     * Told when the human picks the other chip, so the workspace can persist
     * the choice. Not fired by {@link #showScopes} -- that IS the persisted
     * value being applied, and echoing it back would write it again.
     */
    public void setOnChoiceChanged(Consumer<SessionReviewScopes.Choice> handler) {
        this.onChoiceChanged = handler == null ? ignored -> { } : handler;
    }

    /**
     * Wires the key-hints preference to its persisted home: {@code hidden}
     * is read each time the tour renders, so a board built before the reader
     * hid the hints in another session still follows it, and {@code onChanged}
     * is told when this board's reader toggles. Never called by a bare board,
     * which then keeps the choice in memory.
     */
    public void setKeyHintsPreference(BooleanSupplier hidden, Consumer<Boolean> onChanged) {
        this.keyHintsHiddenSource = hidden;
        this.onKeyHintsHiddenChanged = onChanged == null ? ignored -> { } : onChanged;
        keyStrip.setHidden(keyHintsHidden());
    }

    private boolean keyHintsHidden() {
        return keyHintsHiddenSource != null ? keyHintsHiddenSource.getAsBoolean() : keyHintsHiddenLocal;
    }

    /** {@code h}, or the strip's own button: hide the hints, or show them again. */
    private void toggleKeyHints() {
        boolean hidden = !keyHintsHidden();
        keyHintsHiddenLocal = hidden;
        keyStrip.setHidden(hidden);
        onKeyHintsHiddenChanged.accept(hidden);
    }

    /** The switcher's own callback: re-render on a real change, then tell the workspace. */
    private void chooseScope(SessionReviewScopes.Choice picked) {
        if (picked == choice) {
            return;
        }
        choice = picked;
        renderSelectedScope();
        onChoiceChanged.accept(picked);
    }

    /**
     * Renders the selected scope: the same body / diff wiring the
     * destination's {@code showItem} ran for a queue row.
     */
    private void renderSelectedScope() {
        ReviewScope scope = selectedScope().orElseThrow();
        headerIcon.setText(headerGlyphFor(scope));
        headerTitle.setText(headerTitleFor(scope));
        headerContext.setText(headerContextFor(scope));
        filePath = null;
        hunkCursor = 0;
        // The two scopes of one branch share file paths, so leaving these set
        // across a scope switch could make u undo, and jump into, the same
        // file in the WRONG scope.
        lastSettledFile = Optional.empty();
        lastSettledDigests = List.of();
        // Tour cursor and mode are per scope as well: the incoming scope
        // opens on its own first unsettled step, in its own default mode.
        tourController.resetForScope();
        userChoseMode = false;
        bindNavigation(scope);
        // The cursor is reset BEFORE the body is built, which the destination
        // did the other way round: a cached diff publishes Loaded
        // synchronously from inside bodyFor, and the diff-resolved handler
        // that fires off it would otherwise render the incoming scope at the
        // OUTGOING scope's file cursor.
        body.getChildren().setAll(bodyFor(scope));
        refreshReviewState();
        applyResponsiveLayout(getWidth());
        // The diff usually has not arrived yet, in which case there is nothing
        // to reveal and the setOnDiffResolved handler does it when it lands.
        // Revealing here too covers the case where it already has -- coming
        // back to a scope whose diff is still cached.
        revealCurrentSelection();
    }

    /**
     * The centre body: the diff column, or whatever the host supplies ahead
     * of it.
     *
     * <p>A diff this view has already seen resolve is re-rendered from {@link
     * #outcomeByScope} through {@link ReviewDiffColumn#showDiff}, never by
     * re-scoping the column: {@code setScope} early-returns only on an
     * unchanged scope id, and a chip switch always changes it, so it would
     * publish {@code Diffing} over the cached outcome and run git again on
     * every toggle of a two-chip control people flip back and forth on.
     * {@code showDiff} bumps the column's request token, so an in-flight git
     * completion for the outgoing scope is dropped rather than overwriting
     * this one, and it publishes under the scope it was read FOR -- which is
     * what keeps {@code displayedScopeId} equal to the selected scope, and so
     * keeps {@link #submitReview} from refusing.</p>
     *
     * <p>A scope with no checkout cannot be diffed at all ({@link
     * ReviewDiffColumn#setScope} rejects one, because the only diff obtainable
     * would be of the wrong tree). {@link SessionReviewScopes} always mints
     * both scopes against the session's own checkout, so this is a guard
     * rather than a state anything reaches today -- but a guard that says so,
     * not a stack trace out of a layout pass.</p>
     */
    private Region bodyFor(ReviewScope scope) {
        Optional<Region> supplied = host.bodyFor(scope);
        if (supplied.isPresent()) {
            return supplied.get();
        }
        VBox.setVgrow(diffStack, Priority.ALWAYS);
        // Checked before diffability: a diff already in hand is renderable
        // whether or not git could be run for it again.
        if (outcomeByScope.get(scope.id()) instanceof DiffOutcome.Loaded loaded) {
            diffColumn.showDiff(scope, loaded.diff());
            return diffStack;
        }
        if (!scope.diffable()) {
            return placeholder("No checkout to diff",
                    "This scope has no working copy, so there is nothing to read here.", "");
        }
        diffColumn.setScope(scope);
        return diffStack;
    }

    /** The header's glyph, keyed to the scope kind exactly as the queue's was (spec §4.1). */
    private static String headerGlyphFor(ReviewScope scope) {
        return switch (scope.kind()) {
            case WORKING_TREE -> "❯_";
            case BRANCH -> "⎇";
            case WORKTREE -> "◫";
            case PR -> "◧";
            case STACK -> "⛁";
        };
    }

    /** What is being reviewed: the pull request, or the checkout's own branch. */
    private static String headerTitleFor(ReviewScope scope) {
        return scope.kind() == ReviewScope.Kind.PR
                ? "PR #" + scope.pr().map(ReviewScope.PullRequestRef::number).orElseThrow()
                : scope.head();
    }

    /**
     * The header's second half: what this is compared against.
     *
     * <p>A working tree is diffed against its own {@code HEAD}, never against
     * the base branch, so naming the branch there claimed a comparison the
     * column was not making. A worktree's diff reaches past HEAD into its
     * uncommitted work, and says so for the same reason.</p>
     */
    private static String headerContextFor(ReviewScope scope) {
        String against = switch (scope.diffScope()) {
            case WORKING_TREE -> "HEAD";
            case BRANCH_WORKING_TREE -> scope.base() + "  ·  incl. uncommitted";
            case BASE, UPSTREAM -> scope.base();
        };
        String provenance = scope.baseOrigin()
                .filter(origin -> origin == ReviewBase.Origin.DEFAULT_UNMEASURED)
                .map(origin -> "  ·  " + origin.description())
                .orElse("");
        return "vs " + against + provenance;
    }

    /** Re-renders the chips' open-finding counts (findings changed). */
    public void refreshCounts() {
        switcher.refreshCounts(host::openFindings);
    }

    /**
     * Re-reads findings, verdicts and the tour from the store. Called on
     * every store change, including the MCP router's, because a view that
     * renders from a cached value silently discards the other writer's work.
     */
    public void refreshReviewState() {
        Optional<ReviewScope> scope = selectedScope();
        updateRunReviewButton();
        updateCountsLabel();
        if (scope.isEmpty()) {
            margin.setFindings(List.of());
            showFileOnBar(null, Optional.empty(), false);
            verdictBar.showProgress(0, 0);
            mcpPanel.ifPresent(panel -> panel.setScope(null));
            return;
        }
        // A verdict (or a diff change) lands as a tour write; the risk queue
        // moves on once its in-flight check is no longer awaiting the agent.
        tourController.syncRiskQueue(scope.get());

        // Asks the agent about approvals this scope's base move disturbed.
        // Guarded per (scope, fromBase, toBase), so the many renders inside
        // one move send one recheck (see SectionStates#requestRechecks).
        board().ifPresent(current -> sections.requestRechecks(current, recheckDispatch));

        margin.invalidate(null);
        margin.setFindings(findingsForMargin(scope.get()));
        // Before anything reveals: an oversized diff renders the cursor
        // file alone, and moving the cursor changes which file that is.
        diffColumn.setCursorFile(mode == ReviewMode.DIFF ? currentFile().orElse(null) : null);
        diffColumn.refreshPins();
        diffColumn.setLinks(linksByHunk());
        mcpPanel.filter(Node::isVisible)
                .ifPresent(panel -> panel.setScope(scope.get()));
        renderVerdictBar(scope.get());
        applyMode();
    }

    /**
     * What the top bar states about the board: how much code is in it. The
     * destination said "N items · M repos" here, which a single checkout has
     * no equivalent of -- and the file count is the one number nothing else on
     * the board carries (the chips count findings, the verdict bar counts
     * hunks). Blank while the diff is still loading, or failed: a confident
     * "0 files" would read as "nothing changed".
     */
    private void updateCountsLabel() {
        int files = selectedOutcome().orElse(null) instanceof DiffOutcome.Loaded loaded
                ? loaded.diff().files().size()
                : -1;
        countsLabel.setText(files < 0 ? "" : files + (files == 1 ? " file" : " files"));
    }

    /**
     * What the margin shows: the current file's findings, or the whole
     * review's when {@code ⇧F} is on (or there is no file to narrow to).
     */
    private List<ReviewAnnotation> findingsForMargin(ReviewScope scope) {
        List<ReviewAnnotation> all = visibleFindings(scope);
        Optional<String> file = currentFile();
        if (margin.wholeReview() || file.isEmpty()) {
            return all;
        }
        return all.stream().filter(finding -> finding.file().equals(file.get())).toList();
    }

    /**
     * {@code scope}'s findings minus those a tour still withholds behind an
     * unanswered check (spec §4). Whenever a tour exists, in either mode: a
     * withheld finding shown in the hunk diff would spoil the check.
     */
    private List<ReviewAnnotation> visibleFindings(ReviewScope scope) {
        List<ReviewAnnotation> all = host.findings(scope);
        return host.tour(scope)
                .map(record -> all.stream().filter(finding -> !TourFindings.hidden(finding, record)).toList())
                .orElse(all);
    }

    /**
     * Whether a still-open finding on {@code file} blocks approving its hunks
     * (spec §4.6). One rule for the verdict bar's rendered "blocked" and for
     * the write path (every {@code host.setVerdict} call site), so neither
     * can refuse a keypress the other just showed as clear. By file, not by
     * the finding's line range: a blocker is about the change in that file,
     * and the margin shows it under the same file.
     */
    private boolean blockingFindingOpen(ReviewScope scope, String file) {
        return host.findings(scope).stream()
                .filter(finding -> finding.file().equals(file))
                .anyMatch(ReviewAnnotation::blocksApproval);
    }

    /**
     * What the selected scope's diff attempt produced, if there is a selected
     * scope at all. Empty covers both "nothing selected" and "selected, but no
     * diff has resolved for it yet" -- the callers below distinguish those.
     */
    private Optional<DiffOutcome> selectedOutcome() {
        return selectedScope().map(scope -> outcomeByScope.get(scope.id()));
    }

    /**
     * The selected scope's reading path (spec §6): {@link #EMPTY_PATH} until
     * its {@link ChangeGraph} exists, whether that is because none was
     * requested yet, one is still building off the FX thread, or the scope
     * itself has no diff -- {@link ReadingPath#of} takes a graph, not an
     * {@code Optional} of one, and there is nothing honest to compute a
     * reading order FROM before one exists.
     *
     * <p>This calls {@link Sections#of} exactly once, purely to hand its
     * result to {@link ReadingPath#of} as the grouping to reorder; what is
     * read from the path is its links ({@link #linksByHunk()}).</p>
     */
    private ReadingPath.Path currentPath() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return EMPTY_PATH;
        }
        if (!(selectedOutcome().orElse(null) instanceof DiffOutcome.Loaded loaded)) {
            return EMPTY_PATH;
        }
        String scopeId = scope.get().id();
        UnifiedDiff diff = loaded.diff();
        ChangeGraph graph = graphByScope.get(scopeId);
        if (graph == null) {
            return EMPTY_PATH;
        }
        OutOfDiffFanIn.Result fanIn = fanInFor(scopeId);
        PathCacheEntry cached = pathCache;
        if (cached != null && cached.scopeId().equals(scopeId) && cached.diff() == diff
                && cached.graph() == graph && cached.fanIn() == fanIn) {
            return cached.path();
        }
        ReadingPath.Path computed =
                ReadingPath.of(diff, graph, Sections.of(diff, graph), fanIn);
        pathCache = new PathCacheEntry(scopeId, diff, graph, fanIn, computed);
        return computed;
    }

    /**
     * {@link #currentPath()}'s links, keyed by {@link HunkIds#hunkId} --
     * what the diff column renders as a footer beneath each hunk (spec
     * §7.2). A step
     * with no links is left out of the map entirely rather than mapped to an
     * empty list, so {@link ReviewDiffColumn#setLinks} sees exactly the
     * hunks that have something to say and none that do not.
     */
    private Map<String, List<ReadingPath.Link>> linksByHunk() {
        Map<String, List<ReadingPath.Link>> byHunk = new LinkedHashMap<>();
        for (ReadingPath.Step step : currentPath().steps()) {
            if (!step.links().isEmpty()) {
                byHunk.put(step.hunkId(), step.links());
            }
        }
        return byHunk;
    }

    /**
     * Kicks off building {@code diff}'s {@link ChangeGraph} on {@link
     * #SECTION_GRAPH_EXECUTOR}, off the FX thread. Until it finishes, {@code
     * scopeId} has no entry in {@link #graphByScope}.
     *
     * <p>A no-op when {@code diff} is the SAME instance already graphed (or
     * being graphed) for this scope -- every scope flip back to a cached
     * {@code Loaded} outcome, and every untracked-files toggle, republishes
     * that diff through {@code onDiffResolved} again, and re-parsing an
     * unchanged diff on every one of those would re-open the window a
     * superseded build could publish a stale graph into, for no new
     * information.</p>
     */
    private void requestGraph(String scopeId, UnifiedDiff diff) {
        if (graphedDiffByScope.get(scopeId) == diff) {
            return;
        }
        graphedDiffByScope.put(scopeId, diff);
        int generation = graphGenerationByScope.merge(scopeId, 1, Integer::sum);
        graphByScope.remove(scopeId);
        // A new diff invalidates the old scan as surely as it does the old
        // graph: fan-in is measured against THIS diff's changed
        // declarations, and serving the previous one's counts would put a
        // clickable "called from 7 places" on a file that no longer declares
        // any of them.
        fanInByScope.remove(scopeId);
        graphBuilding.add(scopeId);
        graphFailureByScope.remove(scopeId);
        Function<UnifiedDiff, ChangeGraph> builder = graphBuilder;
        CompletableFuture.supplyAsync(() -> builder.apply(diff), SECTION_GRAPH_EXECUTOR)
                .whenComplete((graph, failure) -> {
                    // Closed already: do not even queue FX work for it. A
                    // closed view still building a graph is common under a
                    // test suite -- a fresh view per test method -- and left
                    // unguarded, every one of them posts to the FX thread
                    // whenever its parse happens to finish, well after its
                    // own test moved on.
                    if (closed) {
                        return;
                    }
                    Platform.runLater(() -> {
                        boolean current = Objects.equals(graphGenerationByScope.get(scopeId), generation);
                        if (!current) {
                            // A newer diff for this scope started a second
                            // build before this one finished; this callback
                            // is stale, and the newer build's own callback
                            // owns clearing "building" and refreshing.
                            return;
                        }
                        // The CURRENT generation clears "building" and
                        // refreshes either way, success or failure, so the
                        // link footers and the tour's impact pick it up.
                        graphBuilding.remove(scopeId);
                        if (failure == null) {
                            graphByScope.put(scopeId, graph);
                            requestFanIn(scopeId, diff, graph);
                        } else {
                            // No graph is an honest answer (no link footers,
                            // the step panel says why) -- but a failed build
                            // must not be permanent: graphedDiffByScope
                            // recorded this diff BEFORE the parse ran, so
                            // without clearing it here, requestGraph's own
                            // "already graphed" guard would treat every
                            // later republish of this same diff instance as
                            // nothing new and never retry.
                            graphedDiffByScope.remove(scopeId);
                            graphFailureByScope.put(scopeId, new GraphFailure(diff, UiErrors.message(failure)));
                            LOG.log(Level.WARNING, "Could not build a section graph for scope "
                                    + scopeId, failure);
                        }
                        if (!closed && selectedScope().map(scope -> scope.id().equals(scopeId))
                                .orElse(false)) {
                            refreshReviewState();
                            revealCurrentSelection();
                        }
                    });
                });
    }

    /**
     * Runs {@code scopeId}'s out-of-diff fan-in scan (spec §4.3) on {@link
     * #SECTION_GRAPH_EXECUTOR}, off the FX thread.
     *
     * <p>Off the FX thread is not a preference: {@link OutOfDiffFanIn#scan}
     * spawns a {@code git grep} over the whole worktree and waits up to 30
     * seconds for it. {@code Sections.of} on the FX thread already froze
     * this board for over a second on this branch's own diff; a subprocess
     * would be far worse.</p>
     *
     * <p>Kicked off from {@link #requestGraph}'s completion rather than
     * beside it, because the scan's patterns ARE the graph's changed
     * declarations -- there is nothing to grep for before one exists. It
     * inherits that build's cache for free as a result: one scan per (scope,
     * diff), since one graph is built per (scope, diff).</p>
     */
    private void requestFanIn(String scopeId, UnifiedDiff diff, ChangeGraph graph) {
        Optional<ReviewScope> target = scopeById(scopeId);
        if (target.isEmpty()) {
            return;
        }
        ReviewScope scope = target.get();
        int generation = fanInGenerationByScope.merge(scopeId, 1, Integer::sum);
        CompletableFuture
                .supplyAsync(() -> {
                    fanInScanThread = Thread.currentThread().getName();
                    return OutOfDiffFanIn.forScope(scope, graph, diff);
                }, SECTION_GRAPH_EXECUTOR)
                .whenComplete((result, failure) -> {
                    // Closed already: do not even queue FX work for it, for
                    // the reason requestGraph's own guard exists.
                    if (closed) {
                        return;
                    }
                    Platform.runLater(() -> {
                        if (closed
                                || !Objects.equals(fanInGenerationByScope.get(scopeId), generation)) {
                            // A newer diff started a second scan before this
                            // one finished; that scan's completion owns the
                            // answer.
                            return;
                        }
                        OutOfDiffFanIn.Result landed = result;
                        if (failure != null) {
                            // Recorded as unavailable -- absent, never zero --
                            // so the step panel stops "Finding callers…" and
                            // says why instead.
                            LOG.log(Level.WARNING, "Could not scan out-of-diff fan-in for scope "
                                    + scopeId, failure);
                            landed = new OutOfDiffFanIn.Result(Map.of(),
                                    Optional.of("the caller search failed: " + UiErrors.message(failure)));
                        }
                        // A scan that confirms what was already assumed does
                        // not disturb the reader. The common case is a scope
                        // with nothing to grep (no worktree, or a checkout
                        // git cannot read): the answer is the same
                        // "unavailable, nothing measured" the board started
                        // with. It is still recorded: the step panel tells
                        // "not scanned yet" from "scanned, and here is why
                        // not", and its redraw guards keep the tour still.
                        OutOfDiffFanIn.Result previous = fanInFor(scopeId);
                        boolean fanInUnchanged = previous.unavailable() == landed.unavailable()
                                && previous.bySymbol().equals(landed.bySymbol());
                        fanInByScope.put(scopeId, landed);
                        if (selectedScope().map(current -> current.id().equals(scopeId))
                                .orElse(false)) {
                            if (fanInUnchanged) {
                                tourController.render();
                                return;
                            }
                            // The scan is the reading path's first rank term,
                            // so the link footers can change with it.
                            refreshReviewState();
                        }
                    });
                });
    }

    /**
     * {@code scopeId}'s fan-in scan, or {@link #FAN_IN_NOT_SCANNED} while
     * none has finished. Never a bare empty {@link OutOfDiffFanIn.Result}:
     * "the scan has not run" and "nothing outside the change uses this" are
     * different facts, and every surface downstream of this draws that
     * distinction.
     */
    private OutOfDiffFanIn.Result fanInFor(String scopeId) {
        return fanInByScope.getOrDefault(scopeId, FAN_IN_NOT_SCANNED);
    }

    /**
     * The selected scope's diff, once it has loaded. Empty covers both "still
     * diffing" and "there is no scope" -- neither of which is a diff with no
     * hunks in it.
     */
    private Optional<UnifiedDiff> loadedDiff() {
        return selectedOutcome().orElse(null) instanceof DiffOutcome.Loaded loaded
                ? Optional.of(loaded.diff())
                : Optional.empty();
    }

    /**
     * What the board is showing, for {@link SectionStates}. Empty whenever
     * there is nothing to derive a state from -- no scope, or a diff that
     * has not landed -- which the callers below each answer for themselves.
     */
    private Optional<SectionStates.Board> board() {
        return selectedScope().flatMap(scope -> loadedDiff()
                .map(diff -> new SectionStates.Board(scope, diff,
                        Optional.ofNullable(graphByScope.get(scope.id())))));
    }

    /** The files the hunk diff walks, in diff order; none without a diff. */
    private List<String> files() {
        return board().map(sections::filesWithHunks).orElse(List.of());
    }

    /**
     * The file the cursor is on: {@link #filePath} while the diff still has
     * it, otherwise the first file with hunks. Empty without a diff, or for a
     * diff with nothing to settle.
     */
    private Optional<String> currentFile() {
        List<String> files = files();
        if (files.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(files.contains(filePath) ? filePath : files.getFirst());
    }

    private void renderVerdictBar(ReviewScope scope) {
        Optional<SectionStates.Board> board = board();
        Optional<String> file = currentFile();
        if (file.isEmpty() || board.isEmpty()) {
            showFileOnBar(null, Optional.empty(), false);
            verdictBar.showProgress(0, 0);
            verdictBar.showStale(Optional.empty());
            return;
        }
        showFileOnBar(file.get(), sections.decisionOf(board.get(), file.get()),
                blockingFindingOpen(scope, file.get()));
        // Progress is every hunk of the diff, counted once.
        verdictBar.showProgress(sections.settledHunkCount(board.get()),
                sections.distinctDigests(board.get()).size());
        verdictBar.showStale(sections.stalenessOf(board.get(), file.get()) == SectionStates.Staleness.MOVED
                ? Optional.of(new ReviewVerdictBar.StaleInfo(
                        sections.oldBaseOf(board.get(), file.get()), host.currentBase(scope),
                        sections.stalenessClaimed(board.get(), file.get())))
                : Optional.empty());
    }

    /**
     * Scrolls the diff column to the cursor's hunk. The column always shows
     * the whole scope; the cursor only says where in it the reader is.
     */
    private void revealCurrentFile() {
        currentFile().ifPresent(file -> diffColumn.revealHunk(file, hunkCursor));
    }

    /** Puts the cursor on {@code file}'s hunk {@code hunk} and shows it. */
    private void moveCursorTo(String file, int hunk) {
        filePath = file;
        hunkCursor = hunk;
        refreshReviewState();
        revealCurrentFile();
    }

    /** {@code [} / {@code ]}: the previous / next file, clamped at the ends. */
    private void moveFile(int delta) {
        List<String> files = files();
        Optional<String> current = currentFile();
        if (current.isEmpty()) {
            return;
        }
        int index = (int) Math.clamp((long) files.indexOf(current.get()) + delta, 0, files.size() - 1);
        moveCursorTo(files.get(index), 0);
    }

    /**
     * {@code n}: the next hunk with no verdict, walking forward from the
     * cursor through every file and wrapping round. Nothing unread anywhere
     * leaves the cursor where it is.
     */
    private void nextUnsettledHunk() {
        Optional<SectionStates.Board> board = board();
        Optional<String> current = currentFile();
        if (board.isEmpty() || current.isEmpty()) {
            return;
        }
        List<String> files = sections.filesWithHunks(board.get());
        int start = files.indexOf(current.get());
        // One extra lap covers the current file's hunks BEFORE the cursor.
        for (int offset = 0; offset <= files.size(); offset++) {
            String file = files.get((start + offset) % files.size());
            List<String> digests = sections.digestsOfFile(board.get(), file);
            int from = offset == 0 ? hunkCursor + 1 : 0;
            int to = offset == files.size() ? Math.min(hunkCursor + 1, digests.size()) : digests.size();
            for (int hunk = from; hunk < to; hunk++) {
                if (host.verdict(board.get().scope(), digests.get(hunk)).isEmpty()) {
                    moveCursorTo(file, hunk);
                    return;
                }
            }
        }
    }

    /** Reveals whatever the current mode has selected. */
    private void revealCurrentSelection() {
        if (mode == ReviewMode.DIFF) {
            revealCurrentFile();
        }
    }

    /**
     * The {@code ◆n} pins beside the code and their two-way linkage to the
     * margin (spec §4.4). A pin whose finding is filtered out dims rather
     * than disappearing -- the line still carries a finding, the reader has
     * simply chosen not to look at it.
     */
    private final class PinSource implements ReviewDiffColumn.PinSource {
        @Override
        public List<ReviewDiffColumn.Pin> pinsAt(String file, String lineKey) {
            Optional<ReviewScope> scope = selectedScope();
            if (scope.isEmpty()) {
                return List.of();
            }
            var numbers = margin.pinNumbers();
            List<ReviewDiffColumn.Pin> pins = new ArrayList<>();
            for (ReviewAnnotation finding : visibleFindings(scope.get())) {
                if (!finding.file().equals(file)) {
                    continue;
                }
                if (!finding.startKey().equals(lineKey) && !finding.endKey().equals(lineKey)) {
                    continue;
                }
                Integer number = numbers.get(finding.key());
                // number == null means the margin is not showing this finding
                // (the `open` filter, or another file). The pin dims and
                // drops its number rather than inventing one.
                pins.add(new ReviewDiffColumn.Pin(number == null ? 0 : number,
                        finding.effectiveSeverity().styleClass(), finding.key(), number == null));
            }
            return List.copyOf(pins);
        }

        @Override
        public void focusFinding(ReviewDiffColumn.Pin pin) {
            margin.focus(pin.key());
        }
    }

    /** The margin's window onto the host, with the scope filled in. */
    private final class MarginHost implements ReviewFindingsMargin.Host {
        @Override
        public void setResolved(ReviewAnnotation finding, boolean resolved) {
            selectedScope().ifPresent(scope -> host.setResolved(scope, finding, resolved));
        }

        @Override
        public void postMessage(ReviewAnnotation finding, String body) {
            selectedScope().ifPresent(scope -> host.postMessage(scope, finding, body));
        }

        @Override
        public void applyPatch(ReviewAnnotation finding) {
            selectedScope().ifPresent(scope -> host.applyPatch(scope, finding));
        }

        @Override
        public void overrideSeverity(ReviewAnnotation finding, Severity severity) {
            selectedScope().ifPresent(scope -> host.overrideSeverity(scope, finding, severity));
        }

        @Override
        public void focusLine(ReviewAnnotation finding) {
            diffColumn.revealLine(finding.file(), finding.startKey());
        }

        @Override
        public void setPostToPr(ReviewAnnotation finding, boolean post) {
            selectedScope().ifPresent(scope -> host.setPostToPr(scope, finding, post));
        }

        @Override
        public void triage(ReviewAnnotation finding, Triage triage, Optional<String> reason) {
            selectedScope().ifPresent(scope -> triageFinding(scope, finding, triage, reason));
        }
    }

    /**
     * Records a triage from the margin or the step panel. Dismissing a
     * finding a tour check was built on voids that check (spec §4): the
     * reviewer's answer is not counted wrong, and no alternate is required.
     */
    private void triageFinding(ReviewScope scope, ReviewAnnotation finding, Triage triage, Optional<String> reason) {
        host.setTriage(scope, finding, triage, reason);
        if (triage == Triage.DISMISSED && finding.withheldBy().isPresent() && host.tour(scope).isPresent()) {
            String checkId = finding.withheldBy().get();
            host.updateTour(scope, record -> record.tour().stepOfCheck(checkId)
                    .flatMap(step -> step.check(checkId).map(check -> {
                        StepProgress p = record.progress(step.id());
                        CheckProgress current = p.check(check.id());
                        // A check already passed stays passed: voiding only
                        // spares an answer that has not succeeded yet.
                        return current.settled()
                                ? record
                                : record.withProgress(p.withCheck(StepGrading.voided(current)));
                    }))
                    .orElse(record));
        }
    }

    /**
     * Puts {@code file} (or nothing) on the verdict bar as "i/N · path",
     * remembering it so the bar's buttons resolve back to exactly the file
     * it was showing when they were pressed -- see {@link #fileOnBar}.
     */
    private void showFileOnBar(String file, Optional<ReviewVerdict.Decision> decision, boolean blocked) {
        verdictBarFile = file;
        if (file == null) {
            verdictBar.update(null, decision, blocked);
            return;
        }
        List<String> files = files();
        verdictBar.update(new ReviewVerdictBar.Target("file:" + file,
                        (files.indexOf(file) + 1) + "/" + files.size() + " · " + file),
                decision, blocked);
    }

    /** The file the bar is showing as {@code target}; empty for a tour step or a stale target. */
    private Optional<String> fileOnBar(ReviewVerdictBar.Target target) {
        return Optional.ofNullable(verdictBarFile).filter(file -> target.id().equals("file:" + file));
    }

    /** The verdict bar's window onto the host, with the scope filled in. */
    private final class VerdictHost implements ReviewVerdictBar.Host {
        @Override
        public void approve(ReviewVerdictBar.Target target) {
            if (mode == ReviewMode.TOUR) {
                tourController.approveCurrentStep();
                return;
            }
            fileOnBar(target).ifPresent(file -> settle(file, ReviewVerdict.Decision.APPROVED));
        }

        @Override
        public void requestChanges(ReviewVerdictBar.Target target) {
            if (mode == ReviewMode.TOUR) {
                tourController.decideFromBar(StepProgress.Decision.CHANGES);
                return;
            }
            fileOnBar(target).ifPresent(file -> settle(file, ReviewVerdict.Decision.CHANGES));
        }

        /**
         * The bar's Approve / Request changes: the next unread hunk of
         * {@code file}. Remembered for {@code u} through {@link
         * #rememberSettle}, as a key's settle is, so {@code u} after a click
         * undoes the click, not the keyboard settle before it.
         */
        private void settle(String file, ReviewVerdict.Decision decision) {
            selectedScope().ifPresent(scope -> {
                List<String> digests = digestsForAction(file, false);
                if (!allRendered(digests)) {
                    notice(HUNK_NOT_RENDERED);
                    return;
                }
                Map<String, Optional<ReviewVerdict.Decision>> before = verdictsOf(scope, digests);
                host.setVerdict(scope, digests, Optional.of(decision), blockedFor(scope, digests));
                recordHunkOverrides(scope, digests, decision, before);
                rememberSettle(scope, digests, decision, file);
            });
        }

        @Override
        public boolean askAgentToFix(ReviewVerdictBar.Target target) {
            // The answer is RETURNED, not swallowed: with no session bound
            // (or nothing open to send) this hands over nothing at all, and
            // a button that then looks exactly as though it worked is the
            // silent failure ruling 1 legislated against.
            Optional<String> onBar = fileOnBar(target);
            if (onBar.isEmpty()) {
                return false;
            }
            return selectedScope().map(scope -> host.askAgentToFix(scope, onBar.get(),
                            host.findings(scope).stream()
                                    .filter(finding -> !finding.resolved())
                                    .filter(ReviewAnnotation::counts)
                                    .filter(finding -> finding.file().equals(onBar.get()))
                                    .toList()))
                    .orElse(false);
        }

        @Override
        public void undo(ReviewVerdictBar.Target target) {
            // Re-review, too (spec §9.2): a stale file's banner button and
            // the plain undo button both just clear what is recorded. An
            // undo is never refused, so the flag here is inert -- passed
            // for the sole reason that host.setVerdict has one parameter,
            // not two overloads to keep in sync.
            if (mode == ReviewMode.TOUR) {
                tourController.decideFromBar(StepProgress.Decision.NONE);
                return;
            }
            Optional<String> onBar = fileOnBar(target);
            selectedScope().filter(scope -> onBar.isPresent()).ifPresent(scope -> {
                List<String> digests = digestsOfFile(onBar.get());
                host.setVerdict(scope, digests, Optional.empty(), false);
                clearHunkOverrides(scope, digests);
            });
        }

        @Override
        public void confirmStillGood(ReviewVerdictBar.Target target) {
            Optional<String> onBar = fileOnBar(target);
            selectedScope().filter(scope -> onBar.isPresent()).ifPresent(scope -> {
                List<String> digests = digestsOfFile(onBar.get());
                // A re-dated hunk is approved against the new base, so this
                // follows settle's rule: never approve what is not displayed.
                if (!allRendered(digests)) {
                    notice(CONFIRM_NOT_RENDERED);
                    return;
                }
                host.confirmStillGood(scope, digests);
                refreshReviewState();
            });
        }

        @Override
        public void nextUnsettled() {
            if (mode == ReviewMode.TOUR) {
                tourController.selectFirstUnsettled();
                return;
            }
            nextUnsettledHunk();
        }

        @Override
        public void submit() {
            submitReview();
        }

        @Override
        public void previous() {
            if (mode == ReviewMode.TOUR) {
                tourController.moveStep(-1);
                return;
            }
            moveFile(-1);
        }

        @Override
        public void next() {
            if (mode == ReviewMode.TOUR) {
                tourController.moveStep(1);
                return;
            }
            moveFile(1);
        }
    }

    /** Every hunk digest of {@code file}; none without a diff. */
    private List<String> digestsOfFile(String file) {
        return board().map(b -> sections.digestsOfFile(b, file)).orElse(List.of());
    }

    /**
     * The digests {@code a}/{@code r} act on with the cursor on {@code
     * file} -- see {@link SectionStates#digestsForAction}. None without a
     * diff to derive them from.
     */
    private List<String> digestsForAction(String file, boolean wholeFile) {
        return board().map(b -> sections.digestsForAction(b, file, wholeFile,
                        diffColumn.currentLineSelection()))
                .orElse(List.of());
    }

    /**
     * Why {@code a}/{@code r} did nothing: a hunk they would settle has no
     * card on screen (past the row cap), and a verdict is a claim the reader
     * looked at the code.
     */
    static final String HUNK_NOT_RENDERED = "Not settled: a hunk it covers is past what the diff can show";

    /**
     * Why "Confirm still good" did nothing: it re-dates every hunk of the
     * file, and one of them has no rows on screen.
     */
    static final String CONFIRM_NOT_RENDERED = "Not confirmed: a hunk of this file is past what the diff can show";

    /**
     * Why {@code a} or "Approve without passing" did nothing in the tour: some
     * of the step's changed rows are past the whole-file view's row cap.
     */
    static final String STEP_NOT_RENDERED = "Not approved: some of this step's lines are past what the diff can show";

    /**
     * Whether every changed row of every one of {@code digests}' hunks is
     * rendered in the diff column. By line key, so it answers in the tour's
     * whole-file view too (where the stale banner's confirm is reachable),
     * and a hunk the row cap cuts off partway counts as not shown.
     */
    private boolean allRendered(List<String> digests) {
        Optional<SectionStates.Board> board = board();
        if (board.isEmpty()) {
            return false;
        }
        List<String> keys = new ArrayList<>();
        for (String digest : digests) {
            Optional<String> file = sections.fileOfDigest(board.get(), digest);
            if (file.isEmpty()) {
                return false;
            }
            int index = sections.digestsOfFile(board.get(), file.get()).indexOf(digest);
            Optional<UnifiedDiff.FileDiff> fileDiff = board.get().diff().files().stream()
                    .filter(candidate -> candidate.path().equals(file.get()))
                    .findFirst();
            if (fileDiff.isEmpty() || index < 0 || index >= fileDiff.get().hunks().size()) {
                return false;
            }
            for (UnifiedDiff.Line line : fileDiff.get().hunks().get(index).lines()) {
                if (line.kind() != UnifiedDiff.Line.Kind.CONTEXT) {
                    keys.add(file.get() + " " + line.lineKey());
                }
            }
        }
        return diffColumn.rendersLines(keys);
    }

    /**
     * Whether a blocking finding refuses approving {@code digests}: one open
     * on any file they belong to. Usually the cursor's file, but a gutter
     * selection can point {@code a} at a hunk of another one.
     */
    private boolean blockedFor(ReviewScope scope, List<String> digests) {
        Optional<SectionStates.Board> board = board();
        return board.isPresent() && digests.stream()
                .map(digest -> sections.fileOfDigest(board.get(), digest))
                .flatMap(Optional::stream)
                .distinct()
                .anyMatch(file -> blockingFindingOpen(scope, file));
    }

    /**
     * Why a Submit click did nothing, as a value rather than four literals
     * scattered through {@link #submitReview}.
     *
     * <p>Split in two because the footer at the code column's floor has room
     * for roughly forty characters: {@code reason} is what has to FIT there,
     * {@code detail} is what the ellipsis would otherwise have taken and now
     * lives on hover. Three of the four were over that budget, and the one
     * test that measured it drove only the fourth -- which is how the other
     * three shipped elided. {@link #SUBMIT_REFUSALS} exists so a test can
     * loop the real strings instead of holding its own copies -- and the
     * loop that matters runs in the REAL view, since the bar is 35px
     * narrower there than the window it sits in.</p>
     */
    record SubmitRefusal(String reason, String detail) {
    }

    static final SubmitRefusal DIFF_FAILED = new SubmitRefusal(
            "the diff failed to load",
            "This scope's diff could not be read, so there is nothing to post comments against.");

    static final SubmitRefusal DIFF_LOADING = new SubmitRefusal(
            "the diff is still loading",
            "Try again in a moment: this scope's diff has not landed yet.");

    static final SubmitRefusal NEEDS_VERDICT = new SubmitRefusal(
            "a verdict is missing; jumped to it",
            "Approve it, or request changes on it, before submitting the review.");

    static final SubmitRefusal STALE_BASE = new SubmitRefusal(
            "some approvals are stale",
            "Some approvals were given against a base that has since moved. Confirm they still "
                    + "hold, or re-review them, before submitting.");

    static final SubmitRefusal STEP_UNSETTLED = new SubmitRefusal(
            "a step is unsettled; jumped to it",
            "Pass it, approve it without passing, or request changes on it before submitting the review.");

    static final SubmitRefusal UNCOVERED_UNSETTLED = new SubmitRefusal(
            "an uncovered hunk is unsettled",
            "Settle it in the hunk diff (v), or refresh the tour so a step covers it.");

    /** Every refusal {@link #submitReview} can raise -- see {@link SubmitRefusal}. */
    static final List<SubmitRefusal> SUBMIT_REFUSALS =
            List.of(DIFF_FAILED, DIFF_LOADING, NEEDS_VERDICT, STALE_BASE, STEP_UNSETTLED, UNCOVERED_UNSETTLED);

    /** Why a stale step cannot be approved without passing: its rows are gone from the diff. */
    static final String STALE_STEP_GONE = "This step's lines are no longer in the diff. Ask the agent to "
            + "refresh it, or settle those hunks in the hunk diff (v).";

    /**
     * Submit (spec §4.6): with anything unsettled this jumps to the first
     * file with an unread hunk rather than posting a partial review; once
     * everything is settled it posts ONE review, carrying one decision per
     * file -- what its hunks' verdicts merge to ({@code VerdictMerge}).
     *
     * <p>Refuses while {@link ReviewDiffColumn#displayedDiff()} belongs to a
     * different scope than the one selected -- the window between selecting
     * a scope and its diff actually landing. {@code displayedScopeId} lags
     * {@code setScope}: it is left pointing at whatever scope's diff last
     * finished loading until the new one resolves, so a Submit pressed
     * during that "Diffing…" window would otherwise build the {@code
     * DiffIndex} from the OUTGOING scope's diff under the INCOMING scope's
     * id. A comment whose real anchor is not in that stale index gets
     * refused as "not in this diff" even though it is; worse, one that
     * happens to share a key by coincidence could be admitted with an anchor
     * GitHub rejects, and since the whole review posts as one atomic call, a
     * single bad anchor 422s every other comment in it.</p>
     *
     * <p>Pressing it again once the diff lands succeeds normally only on the
     * SUCCESS branch: {@link ReviewDiffColumn#reload} clears {@code
     * displayedScopeId} on a FAILED diff too, and re-selecting the already-
     * selected scope is a no-op ({@code setScope}), so nothing here ever
     * retries it. A PR scope genuinely has nothing to post without a diff to
     * anchor comments to, so it stays refused -- but visibly now, via {@link
     * ReviewVerdictBar#showSubmitRefused}, rather than doing nothing with no
     * explanation. A non-PR scope posts no comments either way ({@link
     * Host#submit} takes it straight to the Finish hand-off), so a failed
     * diff must not leave IT stuck refusing for the rest of the session -- it
     * falls through and submits with nothing to post.</p>
     */
    private void submitReview() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return;
        }
        if (!diffColumn.displayedScopeId().map(id -> id.equals(scope.get().id())).orElse(false)) {
            boolean failed = selectedOutcome().orElse(null) instanceof DiffOutcome.Failed;
            if (!(failed && scope.get().pr().isEmpty())) {
                SubmitRefusal refusal = failed ? DIFF_FAILED : DIFF_LOADING;
                verdictBar.showSubmitRefused(refusal.reason(), refusal.detail());
                return;
            }
        }
        List<ReviewVerdict.Decision> decisions = new ArrayList<>();
        Optional<SectionStates.Board> board = board();
        for (String file : board.map(sections::filesWithHunks).orElse(List.of())) {
            List<String> digests = sections.digestsOfFile(board.get(), file);
            Optional<ReviewVerdict.Decision> decision = sections.decisionOf(board.get(), file);
            if (decision.isEmpty()) {
                if (mode == ReviewMode.TOUR) {
                    refuseSubmitFromTour(digests, false);
                    return;
                }
                int unread = sections.digestOfFirstUnsettledHunk(board.get(), file)
                        .map(digests::indexOf).orElse(0);
                moveCursorTo(file, unread);
                verdictBar.showSubmitRefused(NEEDS_VERDICT.reason(), NEEDS_VERDICT.detail());
                return;
            }
            // A stale verdict does not count toward "everything settled"
            // (spec §9.2): it was given against a base that has since moved,
            // so posting it is a decision the reader has not actually made
            // about the code as it stands now.
            if (sections.stalenessOf(board.get(), file) == SectionStates.Staleness.MOVED) {
                if (mode == ReviewMode.TOUR) {
                    refuseSubmitFromTour(digests, true);
                    return;
                }
                moveCursorTo(file, 0);
                verdictBar.showSubmitRefused(STALE_BASE.reason(), STALE_BASE.detail());
                return;
            }
            decisions.add(decision.get());
        }
        host.submit(scope.get(), buildDiffIndex(diffColumn.displayedDiff()), decisions,
                lineTextLookup(diffColumn.renderedDiff()), unverifiedOf(scope.get()));
    }

    /**
     * Submit's refusal while the tour is showing. The hunk diff's answer --
     * move the file cursor -- points at nothing the tour shows, so here the
     * refusal jumps to a step: for a stale approval the step covering any of
     * {@code digests}, otherwise the first unsettled step. A hunk no step
     * covers has no step to jump to, and the footer says where to settle it.
     */
    private void refuseSubmitFromTour(List<String> digests, boolean staleBase) {
        boolean jumped = tourController.jumpForSubmitRefusal(digests, staleBase);
        SubmitRefusal refusal = staleBase ? STALE_BASE : jumped ? STEP_UNSETTLED : UNCOVERED_UNSETTLED;
        verdictBar.showSubmitRefused(refusal.reason(), refusal.detail());
    }

    /** What the review approves without verification: step and hunk overrides, and untriaged agent findings. */
    private ReviewSubmitSheet.Unverified unverifiedOf(ReviewScope scope) {
        int stepOverrides = 0;
        int hunkOverrides = 0;
        Optional<TourRecord> record = host.tour(scope);
        if (record.isPresent()) {
            stepOverrides = (int) record.get().progress().values().stream()
                    .filter(progress -> progress.decision() == StepProgress.Decision.OVERRIDDEN).count();
            hunkOverrides = (int) record.get().hunkOverrides().values().stream()
                    .filter(override -> override.decision() == ReviewVerdict.Decision.APPROVED).count();
        }
        return new ReviewSubmitSheet.Unverified(stepOverrides, hunkOverrides,
                SubmitPlan.untriagedCount(host.findings(scope)));
    }

    /** {@code (file, lineKey)} to that line's text in {@code diff}; empty for a line the diff does not hold. */
    private static BiFunction<String, String, Optional<String>> lineTextLookup(UnifiedDiff diff) {
        return (file, lineKey) -> {
            if (diff == null) {
                return Optional.empty();
            }
            for (UnifiedDiff.FileDiff candidate : diff.files()) {
                if (!candidate.path().equals(file)) {
                    continue;
                }
                for (UnifiedDiff.Hunk hunk : candidate.hunks()) {
                    for (UnifiedDiff.Line line : hunk.lines()) {
                        if (line.lineKey().equals(lineKey)) {
                            return Optional.of(line.text().strip());
                        }
                    }
                }
            }
            return Optional.empty();
        };
    }

    /**
     * Locates every line of {@code diff} for {@link SubmitPlan#of}, walking
     * the real diff rather than {@link ReviewDiffColumn#diagRows()}: a
     * collapsed run, the context toggle, or truncation of a large diff can
     * all leave a valid line out of the rendered rows, and validating
     * against rows would refuse a comment GitHub would happily accept.
     * {@code positionOfKey} is one running ordinal across the whole diff (the
     * position {@code gh api} anchors a comment to); {@code hunkOfKey} is a
     * per-{@link UnifiedDiff.Hunk} ordinal, which is what lets {@link
     * SubmitPlan#of} refuse a comment whose start and end land in different
     * hunks.
     */
    private static SubmitPlan.DiffIndex buildDiffIndex(UnifiedDiff diff) {
        Map<String, Integer> positionOfKey = new HashMap<>();
        Map<String, Integer> hunkOfKey = new HashMap<>();
        int position = 0;
        for (UnifiedDiff.FileDiff file : diff.files()) {
            int hunkIndex = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    String key = file.path() + " " + line.lineKey();
                    positionOfKey.put(key, position++);
                    hunkOfKey.put(key, hunkIndex);
                }
                hunkIndex++;
            }
        }
        return new SubmitPlan.DiffIndex(positionOfKey, hunkOfKey);
    }

    /**
     * @param scope what the state is talking about. Blank renders no line: an
     *              empty one would read as a scope that came back empty.
     */
    private static Region placeholder(String title, String detail, String scope) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("review-placeholder-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("review-placeholder-detail");
        detailLabel.setWrapText(true);
        detailLabel.setMaxWidth(520);
        VBox box = new VBox(8, titleLabel, detailLabel);
        if (!scope.isBlank()) {
            Label scopeLabel = new Label(scope);
            scopeLabel.getStyleClass().add("review-placeholder-scope");
            scopeLabel.setWrapText(true);
            scopeLabel.setMaxWidth(520);
            box.getChildren().add(scopeLabel);
        }
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("review-placeholder");
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    // ---- layout + keyboard --------------------------------------------------

    /**
     * The rails auto-collapse as the window narrows so the code column is
     * never crushed (spec §4.9). A manual collapse is remembered separately:
     * a user who collapsed a rail keeps it collapsed when the window grows
     * back, and one who did not gets it back.
     */
    private void applyResponsiveLayout(double width) {
        if (noScope) {
            applyEmptySurface();
            return;
        }
        showEveryRegion();
        RailLayout.Layout layout =
                RailLayout.solve(width, outlineCollapsedByUser, marginCollapsedByUser, mode);
        if (mode == ReviewMode.TOUR) {
            outline.setNarrow(layout.narrow());
            outline.setCollapsed(layout.outlineCollapsed());
            stepPanel.setNarrow(layout.narrow());
            stepPanel.setCollapsed(layout.marginCollapsed());
            return;
        }
        margin.setNarrow(layout.narrow());
        margin.setCollapsed(layout.marginCollapsed());
    }

    /**
     * The empty surface: the top bar, and one centred state in the middle of
     * the window. Every region that describes a scope is hidden rather than
     * left empty -- see {@link #noScope}.
     */
    private void applyEmptySurface() {
        setLeft(null);
        setCenter(centre);
        show(margin, false);
        show(stepPanel, false);
        show(verdictBar, false);
        keyStrip.setTourShown(false);
        show(itemHeader, false);
        mcpPanel.ifPresent(panel -> show(panel, false));
    }

    /** Undoes {@link #applyEmptySurface}; the responsive rules take it from here. */
    private void showEveryRegion() {
        show(margin, true);
        show(stepPanel, true);
        show(verdictBar, true);
        keyStrip.setTourShown(mode == ReviewMode.TOUR);
        show(itemHeader, true);
        if (mode == ReviewMode.TOUR && getLeft() == null) {
            setLeft(outline);
        }
    }

    private static void show(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** {@code m}: collapses or expands the findings margin. */
    private void setMarginCollapsed(boolean collapsed) {
        marginCollapsedByUser = collapsed;
        applyResponsiveLayout(getWidth());
    }

    /**
     * The collapsed step panel's expand button. A collapse the reader asked
     * for ({@code m}) is undone; a collapse the window's width forces is
     * not -- the code column keeps its floor -- so the reader is told to
     * widen the window rather than left with a button that did nothing.
     */
    private void expandStepPanel() {
        setMarginCollapsed(false);
        if (stepPanel.collapsed()) {
            notice("Widen the window to show the step panel beside the code");
        }
    }

    /** The collapsed outline's expand button; as {@link #expandStepPanel}, for the left rail. */
    private void expandOutline() {
        outlineCollapsedByUser = false;
        applyResponsiveLayout(getWidth());
        if (outline.collapsed()) {
            notice("Widen the window to show the tour outline beside the code");
        }
    }

    /**
     * {@code f}: collapses every rail so the code owns the window -- the
     * margin in the hunk diff, the outline and the step panel in the tour. A
     * toggle, not a one-way collapse, or the second press would be a dead
     * key.
     *
     * <p>Both modes' rails are set together, whichever mode is showing: set
     * only the shown mode's, and {@code f} in the hunk diff followed by
     * {@code v} left the tour half in focus mode (step panel collapsed,
     * outline open), so the next {@code f} collapsed the rest instead of
     * leaving focus mode.</p>
     */
    private void setFocusMode(boolean on) {
        marginCollapsedByUser = on;
        outlineCollapsedByUser = on;
        applyResponsiveLayout(getWidth());
    }

    /**
     * Whether {@code f} would currently undo focus mode: the reader has
     * collapsed every rail the current mode shows.
     */
    private boolean focusModeOn() {
        return marginCollapsedByUser && (mode != ReviewMode.TOUR || outlineCollapsedByUser);
    }

    /**
     * While a tour exists, a verdict set in the hunk diff is recorded as a
     * hunk override (spec §5), so the derivation the tour runs keeps it
     * rather than clearing it the next time tour mode shows. Only once the
     * host actually recorded {@code decision} -- an approval it refused over
     * a blocking finding is no override -- and only for the hunks this action
     * changed: a hunk the tour's steps had already derived to {@code
     * decision} was decided by those steps, and recording it as a hunk-diff
     * override would count it on the submit sheet and pin it there after
     * the step is undone.
     *
     * @param before each digest's stored decision just before this action
     */
    private void recordHunkOverrides(ReviewScope scope, List<String> digests, ReviewVerdict.Decision decision,
                                     Map<String, Optional<ReviewVerdict.Decision>> before) {
        if (host.tour(scope).isEmpty()) {
            return;
        }
        List<String> recorded = digests.stream()
                .filter(digest -> !before.getOrDefault(digest, Optional.empty()).equals(Optional.of(decision)))
                .filter(digest -> host.verdict(scope, digest).filter(v -> v.decision() == decision).isPresent())
                .toList();
        if (recorded.isEmpty()) {
            return;
        }
        Optional<HunkOverride> override = Optional.of(new HunkOverride(decision, "set in the hunk diff"));
        host.updateTour(scope, record -> {
            TourRecord next = record;
            for (String digest : recorded) {
                next = next.withHunkOverride(digest, override);
            }
            return next;
        });
    }

    /** Each digest's stored decision, for {@link #recordHunkOverrides} to tell what an action changed. */
    private Map<String, Optional<ReviewVerdict.Decision>> verdictsOf(ReviewScope scope, List<String> digests) {
        Map<String, Optional<ReviewVerdict.Decision>> decisions = new HashMap<>();
        for (String digest : digests) {
            decisions.put(digest, host.verdict(scope, digest).map(ReviewVerdict::decision));
        }
        return decisions;
    }

    /** Undo in the hunk diff removes the overrides it had recorded. */
    private void clearHunkOverrides(ReviewScope scope, List<String> digests) {
        if (host.tour(scope).isEmpty() || digests.isEmpty()) {
            return;
        }
        host.updateTour(scope, record -> {
            TourRecord next = record;
            for (String digest : digests) {
                next = next.withHunkOverride(digest, Optional.empty());
            }
            return next;
        });
    }

    /**
     * {@code a} / {@code r}: records a verdict on the cursor file's next
     * unread hunk, or with {@code wholeFile} ({@code ⇧A}/{@code ⇧R}) on every
     * hunk of the file -- and, once it actually took (the host still refuses
     * APPROVED over a blocking finding, so recording is not guaranteed),
     * remembers those exact digests as what {@code u} should undo (see
     * {@link #undoVerdict}). Once the file as a whole is decided this way,
     * the cursor moves on to the next unread hunk, as {@code n} does;
     * otherwise to the file's own next unread hunk. Refused, with a notice,
     * when a hunk it would settle has no card on screen.
     */
    private void verdictAction(ReviewVerdict.Decision decision, boolean wholeFile) {
        Optional<ReviewScope> scope = selectedScope();
        Optional<String> file = currentFile();
        Optional<SectionStates.Board> board = board();
        if (scope.isEmpty() || file.isEmpty() || board.isEmpty()) {
            return;
        }
        List<String> digests = digestsForAction(file.get(), wholeFile);
        if (digests.isEmpty()) {
            return;
        }
        if (!allRendered(digests)) {
            notice(HUNK_NOT_RENDERED);
            return;
        }
        Map<String, Optional<ReviewVerdict.Decision>> before = verdictsOf(scope.get(), digests);
        host.setVerdict(scope.get(), digests, Optional.of(decision), blockedFor(scope.get(), digests));
        Optional<String> settled = rememberSettle(scope.get(), digests, decision, file.get());
        if (settled.isEmpty()) {
            return;
        }
        recordHunkOverrides(scope.get(), digests, decision, before);
        String settledFile = settled.get();
        if (sections.decisionOf(board.get(), settledFile).filter(decision::equals).isPresent()) {
            nextUnsettledHunk();
            return;
        }
        // The file is still undecided: put the cursor (and the scroll) on
        // its next unread hunk, so the next a acts on what is on screen.
        List<String> fileDigests = sections.digestsOfFile(board.get(), settledFile);
        Optional<String> unread = sections.digestOfFirstUnsettledHunk(board.get(), settledFile);
        if (unread.isPresent()) {
            moveCursorTo(settledFile, fileDigests.indexOf(unread.get()));
        } else {
            refreshReviewState();
        }
    }

    /**
     * Records {@code digests} as what {@code u} undoes -- once the verdict
     * actually took on every one of them (the host still refuses APPROVED
     * over a blocking finding). The one place both settle paths, {@code a}/
     * {@code r} and the bar's buttons, remember a settle, so they cannot
     * drift apart. Returns the file the digests belong to, or empty when
     * nothing was recorded.
     */
    private Optional<String> rememberSettle(ReviewScope scope, List<String> digests, ReviewVerdict.Decision decision,
                                            String fallbackFile) {
        boolean applied = !digests.isEmpty() && digests.stream().allMatch(digest -> host.verdict(scope, digest)
                .filter(v -> v.decision() == decision).isPresent());
        if (!applied) {
            return Optional.empty();
        }
        String settledFile = board().flatMap(b -> sections.fileOfDigest(b, digests.getFirst())).orElse(fallbackFile);
        lastSettledFile = Optional.of(settledFile);
        lastSettledDigests = digests;
        return lastSettledFile;
    }

    /**
     * {@code u}: undoes exactly the digests {@code a}/{@code r} last
     * recorded -- NOT whatever the cursor sits on now. Since settling a file
     * advances the cursor (see {@link #verdictAction}), undoing "the current
     * file" would clear whatever it advanced TO instead -- the wrong one, and
     * silently. Snaps the cursor back to the undone file, so the reader sees
     * what changed. A second {@code u} with nothing left to undo does
     * nothing.
     */
    private void undoVerdict() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty() || lastSettledFile.isEmpty() || lastSettledDigests.isEmpty()) {
            return;
        }
        String file = lastSettledFile.get();
        List<String> digests = lastSettledDigests;
        lastSettledFile = Optional.empty();
        lastSettledDigests = List.of();
        // An undo is never refused (see the VerdictHost#undo comment); false
        // is inert here, not a claim that nothing is blocking.
        host.setVerdict(scope.get(), digests, Optional.empty(), false);
        clearHunkOverrides(scope.get(), digests);
        int hunk = Math.max(0, digestsOfFile(file).indexOf(digests.getFirst()));
        moveCursorTo(file, hunk);
    }

    /**
     * Asks the selected scope's agent for a review. Refuses the same case
     * {@code host.runReview} refuses -- a scope with no session has no agent
     * to ask -- but says so on the button beforehand rather than by doing
     * nothing when clicked; see {@link #updateRunReviewButton}.
     */
    private void runReviewOnSelection() {
        selectedScope().ifPresent(scope -> {
            if (host.tour(scope).isPresent()) {
                // A tour exists: bring it onto the diff. The full-tour
                // instruction would have the agent re-post every step, and
                // the reviewer's progress would go with them.
                refreshTourOnSelection(scope);
                return;
            }
            if (host.runReview(scope)) {
                runReviewButton.setText("▶  Review running…");
                // Only the label, and only until the next selection or state
                // refresh: what the agent then does shows up as the tour and
                // findings, which are the real progress indication.
                if (host.tour(scope).isEmpty()) {
                    tourController.startWaiting(scope.id());
                    applyMode();
                }
            } else if (host.tour(scope).isEmpty()) {
                tourController.failRun(scope.id(), "Could not reach this session's agent.");
                applyMode();
            }
        });
    }

    /** "Refresh tour": the top bar's button on a scope with a tour; see {@link TourController#askForTourRefresh}. */
    private void refreshTourOnSelection(ReviewScope scope) {
        runReviewButton.setText("⟳  Refreshing…");
        if (tourController.askForTourRefresh(scope)) {
            notice("Asked the agent to refresh the tour");
            tourController.render();
        } else {
            notice("Could not reach this session's agent to refresh the tour");
        }
        updateRunReviewButton();
    }

    /**
     * The top bar's review button. With no tour it is "Run review": one
     * click asks the agent for findings and a tour. With a tour it is
     * "Refresh tour", which asks only for the stale steps and the uncovered
     * hunks -- and has nothing to ask while the tour is current, so it is
     * disabled and says so. Disabled, with the reason, where there is no
     * agent to ask.
     */
    private void updateRunReviewButton() {
        Optional<ReviewScope> scope = selectedScope();
        boolean runnable = scope.flatMap(ReviewScope::sessionId).isPresent();
        Optional<TourRecord> tour = scope.flatMap(host::tour);
        if (!runnable || tour.isEmpty()) {
            runReviewButton.setText("▶  Run review");
            runReviewButton.setDisable(!runnable);
            runReviewButton.setTooltip(new Tooltip(runnable
                    ? "Ask this session's agent for findings and a guided tour of the change"
                    : "Needs a session — start one for this checkout first"));
            return;
        }
        runReviewButton.setText("⟳  Refresh tour");
        Optional<UnifiedDiff> diff = loadedDiff();
        if (diff.isEmpty()) {
            runReviewButton.setDisable(true);
            runReviewButton.setTooltip(new Tooltip("The diff is still loading"));
            return;
        }
        TourRecord record = tour.get();
        long stale = record.tour().steps().stream().filter(step -> record.progress(step.id()).stale()).count();
        int uncovered = TourMigration.uncoveredHunkIds(record, AnchorIndex.of(diff.get())).size();
        runReviewButton.setDisable(stale == 0 && uncovered == 0);
        runReviewButton.setTooltip(new Tooltip(stale == 0 && uncovered == 0
                ? "The tour is current"
                : "Ask the agent to re-write " + stale + (stale == 1 ? " stale step" : " stale steps")
                        + " and cover " + uncovered + (uncovered == 1 ? " uncovered hunk" : " uncovered hunks")));
    }

    private void cycleDensity() {
        applyDensity(density.next());
    }

    private void applyDensity(ReviewDensity newDensity) {
        density = newDensity;
        densityButton.setText(newDensity.label());
        diffColumn.setDensity(newDensity);
    }

    /** {@code \}: shows or hides the MCP activity panel; a hidden panel listens to nothing. */
    private void toggleMcpPanel() {
        mcpPanel.ifPresent(panel -> {
            boolean show = !panel.isVisible();
            panel.setVisible(show);
            panel.setManaged(show);
            if (show) {
                panel.setScope(selectedScope().orElse(null));
                panel.attach();
            } else {
                panel.detach();
            }
        });
    }

    /**
     * Review's keyboard table (spec §5). Keys are suppressed while a text
     * input has focus, and every one of them has a visible control too --
     * the shortcut is an accelerator, never the only way to reach an action.
     *
     * <p>{@code Esc} and {@code ?} are deliberately absent: the scene-level
     * filter in {@code DrydockApplication} owns both, and a scene filter runs
     * before a node filter, so binding them here would be dead code. {@code
     * Esc} unwinds topmost-first there -- modal, then Review.</p>
     *
     * <p>This is a thin wrapper over {@link #handleShortcut}, kept as the
     * {@code addEventFilter} target below: it only ever sees an event whose
     * target is a descendant of this view. The workspace's keyboard backstop
     * calls {@link #handleShortcut} directly for the case where the reader's
     * focus has ended up somewhere else entirely while Review is still the
     * showing sub-tab.</p>
     */
    private void onKeyPressed(KeyEvent event) {
        if (handleShortcut(event)) {
            event.consume();
        }
    }

    /**
     * Review's keyboard table, minus the consume: returns whether {@code
     * event} mapped to a shortcut and was acted on, so a caller outside the
     * normal capturing chain (see {@link #onKeyPressed}) can tell whether to
     * treat the event as handled.
     *
     * <p>The queue's keys ({@code j}/{@code k}, {@code q}, {@code /}) and
     * {@code o} are gone with the queue and the session row: there is no
     * second thing to walk here, and the session this board belongs to is the
     * one already on screen. {@code Shift+/} -- the shortcuts overlay -- still
     * falls through to {@code DrydockApplication}'s scene filter, now by
     * simply not being bound.</p>
     */
    public boolean handleShortcut(KeyEvent event) {
        if (event.isShortcutDown() || event.isAltDown()
                || event.getTarget() instanceof TextInputControl) {
            return false;
        }
        // An open peek owns these three, ahead of every other binding: u is
        // its usages, not undo; Enter opens it for real, not Submit.
        // Shift-modified keys keep their board meaning (⇧A, ⇧R).
        if (peekLayer.isOpen() && !event.isShiftDown()) {
            switch (event.getCode()) {
                case ENTER -> {
                    if (getScene() != null && getScene().getFocusOwner() instanceof ButtonBase) {
                        // Enter belongs to the focused button -- a peek
                        // action, a trail chip -- as in the Explorer.
                        return false;
                    }
                    peekLayer.promoteTop();
                    return true;
                }
                case U -> {
                    peekLayer.toggleUsages();
                    return true;
                }
                case A -> {
                    peekLayer.askTop();
                    return true;
                }
                default -> { }
            }
        }
        if (tourController.handleShortcut(event)) {
            return true;
        }
        boolean handled = switch (event.getCode()) {
            case V -> { toggleMode(); yield true; }
            case D -> { cycleDensity(); yield true; }
            case C -> { diffColumn.toggleContext(); yield true; }
            // m collapses whichever right-hand node is showing.
            case M -> {
                setMarginCollapsed(!(mode == ReviewMode.TOUR ? stepPanel.collapsed() : margin.collapsed()));
                yield true;
            }
            case BACK_SLASH -> {
                // The reader now owns the panel; the tour wait must not close it.
                tourController.readerOwnsMcpPanel();
                toggleMcpPanel();
                yield true;
            }
            // [ and ] step the file cursor; n finds the next unread hunk.
            case OPEN_BRACKET -> { moveFile(-1); yield true; }
            case CLOSE_BRACKET -> { moveFile(1); yield true; }
            case N -> { nextUnsettledHunk(); yield true; }
            case A -> {
                verdictAction(ReviewVerdict.Decision.APPROVED, event.isShiftDown());
                yield true;
            }
            case R -> {
                verdictAction(ReviewVerdict.Decision.CHANGES, event.isShiftDown());
                yield true;
            }
            case U -> { undoVerdict(); yield true; }
            case ENTER -> { submitReview(); yield true; }
            // Shift+F is the whole-review filter; plain f is focus mode.
            case F -> {
                if (event.isShiftDown()) {
                    margin.setWholeReview(!margin.wholeReview());
                } else {
                    setFocusMode(!focusModeOn());
                }
                yield true;
            }
            default -> false;
        };
        return handled;
    }

    /**
     * Escape's unwind, topmost-first (spec §5): one peek card, the
     * symbol-lens popover, the gutter composer, then the MCP activity panel.
     * Returns whether something was closed, so the scene filter knows
     * whether to keep unwinding.
     */
    public boolean unwindOne() {
        if (peekLayer.popOne()) {
            return true;
        }
        if (diffColumn.lensOpen()) {
            diffColumn.hideLens();
            return true;
        }
        if (diffColumn.composerOpen()) {
            diffColumn.closeComposer();
            return true;
        }
        if (mcpPanel.filter(Node::isVisible).isPresent()) {
            toggleMcpPanel();
            return true;
        }
        return false;
    }

    /**
     * Called by the workspace when this sub-tab becomes visible. Focus is
     * taken on the next pulse: this runs the moment the node is made visible,
     * before the layout pass that makes it focusable, so an immediate {@code
     * requestFocus} would be dropped and the keyboard table would be dead
     * until the user clicked something.
     */
    public void onShown() {
        Platform.runLater(this::requestFocus);
    }

    /**
     * Releases what this view holds that would otherwise outlive it.
     *
     * <p>The MCP activity panel's live-log subscription is the one that
     * matters: {@link ReviewMcpActivityPanel#attach} registers a listener on
     * {@link McpActivityLog}, which is app-lifetime and keeps
     * its listeners in a {@code CopyOnWriteArrayList} -- so an un-detached
     * panel keeps this whole view (diff column included) reachable, and
     * running a pointless FX-thread {@code refresh()} on every MCP call for
     * every closed session's board that was ever opened with the panel
     * showing, for the rest of the process's life. {@code detach()} is a
     * no-op if the panel was never attached (never opened, or already
     * hidden), so this is always safe to call.</p>
     *
     * <p>Call before dropping the last reference to this view -- see {@code
     * OpenSessionTab.disposeNativeResources}.</p>
     */
    public void close() {
        closed = true;
        tourController.close();
        navNoticeTimer.stop();
        mcpPanel.ifPresent(ReviewMcpActivityPanel::detach);
    }

    // ---- tour mode ----------------------------------------------------------

    /**
     * Shows the mode's nodes and renders the tour when it is showing. Runs at
     * the end of every {@link #refreshReviewState}, so it only touches a node
     * when the mode actually changed it: DIFF mode on a scope with no tour is
     * today's board, untouched.
     */
    private void applyMode() {
        Optional<TourRecord> tour = tourController.currentTour();
        tourController.endWaitIfArrived(tour);
        if (!userChoseMode) {
            // A failed run is shown where its Retry / Open diff review live,
            // and stays shown across refreshes until the reader picks one.
            mode = tour.isPresent() || tourController.pending() || tourController.failedForSelection()
                    ? ReviewMode.TOUR
                    : ReviewMode.DIFF;
        }
        boolean touring = mode == ReviewMode.TOUR;
        boolean swapped = false;
        Node left = touring ? outline : null;
        if (getLeft() != left) {
            setLeft(left);
            swapped = true;
        }
        Node right = touring ? stepPanel : margin;
        if (columns.getChildren().get(1) != right) {
            columns.getChildren().set(1, right);
            swapped = true;
        }
        if (diffColumn.wholeFiles() != touring) {
            // Before the switch: an oversized hunk diff renders the cursor
            // file alone, and v reaches here without the state refresh that
            // otherwise tells the column which file that is.
            diffColumn.setCursorFile(touring ? null : currentFile().orElse(null));
            diffColumn.setWholeFiles(touring);
        }
        verdictBar.setTourMode(touring);
        keyStrip.setTourShown(touring);
        if (touring) {
            tourController.render(tour);
        } else {
            tourController.clearFromDiffColumn();
            if (appliedMode != ReviewMode.DIFF) {
                // The bar still carries the tour's step and step counts; v
                // and Open diff review reach here without a state refresh.
                selectedScope().ifPresent(this::renderVerdictBar);
            }
        }
        appliedMode = mode;
        if (swapped) {
            applyResponsiveLayout(getWidth());
        }
    }
    // ---- navigation: peek, trail, search (spec §5) ---------------------------

    private void installNavigation() {
        navNotice.getStyleClass().addAll("explorer-toast", "review-nav-notice");
        navNotice.setVisible(false);
        navNotice.setManaged(false);
        navNoticeTimer.setOnFinished(event -> clearNotice());
        diffStack.getChildren().setAll(diffColumn, peekLayer, navNotice);
        peekLayer.setOnPromote(this::promotePeek);
        peekLayer.setOnAsk(this::askAboutPeek);
        peekLayer.setOnStackFull(() -> notice("Peek stack is full — esc to unwind"));
        // Absent, not greyed, on a scope no session is bound to (delta hard rules).
        peekLayer.setAgentAvailable(() -> selectedScope().flatMap(ReviewScope::sessionId).isPresent());
        diffColumn.setSymbolClickHandler(this::peekAtSymbol);
        trailBar.setOnStep(direction -> navigateTrail(direction));
        trailBar.setOnGoTo(index -> trail.goTo(index).ifPresent(waypoint -> {
            openWaypoint(waypoint);
            trailChanged();
        }));
        trailBar.setOnTogglePin(() -> {
            trail.togglePin();
            trailChanged();
        });
        trailBar.render(trail);
    }

    /**
     * Binds the incoming scope's navigation: its trail (restored once per
     * store key, so flipping between a session's two chips keeps the trail
     * in hand) and the outline's Search tab.
     */
    private void bindNavigation(ReviewScope scope) {
        peekLayer.clear();
        tourController.resetAnchor();
        navigation = host.navigation(scope);
        String key = navigation.map(nav -> ExplorerTrailStore.reviewKey(nav.sessionKey()))
                .orElse("scope:" + scope.id());
        if (!key.equals(trailKey)) {
            trailKey = key;
            // load() reads the store's in-memory map; the file was read when the store opened.
            ExplorerTrailStore.Trail restored = navigation.map(nav -> nav.trails().load(key))
                    .orElse(ExplorerTrailStore.Trail.EMPTY);
            trail.restore(restored.waypoints(), restored.cursor());
            trailBar.render(trail);
        }
        if (navigation.isEmpty()) {
            searchRail = null;
            searchRailRoot = null;
            outline.showSearchTab(false);
            return;
        }
        ReviewNavigation nav = navigation.get();
        if (searchRail == null || !nav.root().equals(searchRailRoot)) {
            searchRail = new SearchRail(nav.root(), nav.search(), this::openSearchResult);
            searchRail.setDiffFileTest(relativePath -> loadedDiff()
                    .map(diff -> diff.files().stream().anyMatch(file -> Path.of(file.path()).equals(relativePath)))
                    .orElse(false));
            searchRail.setChangedLines(this::changedLinesOfReviewDiff);
            searchRailRoot = nav.root();
            outline.setSearchContent(searchRail);
        }
        outline.showSearchTab(true);
    }

    /**
     * {@code ⌘[} / {@code ⌘]} (and the trail bar's ‹ ›): one step along the
     * trail. False at its ends, so the global shortcut falls through to
     * session-tab switching exactly as it does from the Explorer.
     */
    public boolean navigateTrail(int direction) {
        Optional<NavigationTrail.Waypoint> target = direction < 0 ? trail.back() : trail.forward();
        target.ifPresent(this::openWaypoint);
        if (target.isPresent()) {
            trailChanged();
        }
        return target.isPresent();
    }

    /**
     * Returns to a waypoint without adding one: a "Step N" waypoint selects
     * that step; any other reveals its row when the column shows its file,
     * or opens a location peek when it does not.
     */
    private void openWaypoint(NavigationTrail.Waypoint waypoint) {
        peekLayer.clear();
        Optional<TourStep> step = tourController.stepOfWaypoint(waypoint);
        String file = diffPath(waypoint.file());
        String key = waypoint.lineKey().orElse("n" + waypoint.line());
        if (step.isPresent()) {
            tourController.showStep(step.get().id(), false);
            if (inRenderedDiff(file)) {
                diffColumn.revealLine(file, key);
            }
            return;
        }
        if (inRenderedDiff(file)) {
            diffColumn.revealLine(file, key);
        } else {
            openLocationPeek(waypoint.file(), waypoint.line());
        }
        tourController.leftStep();
    }

    private void pushWaypoint(Path file, String label, int line, Optional<String> lineKey) {
        trail.push(file, label, line, lineKey);
        trailChanged();
    }

    /** Repaints the trail bar and saves the trail, after every change to it. */
    private void trailChanged() {
        trailBar.render(trail);
        navigation.ifPresent(nav -> nav.trails().save(trailKey,
                new ExplorerTrailStore.Trail(trail.waypoints(), trail.cursor())));
    }

    /** A relative path as the diff names its files: forward slashes. */
    private static String diffPath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    /** Whether the column's rendered diff (whole files in tour mode) has {@code file}. */
    private boolean inRenderedDiff(String file) {
        UnifiedDiff rendered = diffColumn.renderedDiff();
        return rendered != null && rendered.files().stream().anyMatch(candidate -> candidate.path().equals(file));
    }

    /**
     * Reveals {@code relativePath:line} and adds a waypoint for it when the
     * column shows that file. False when it does not, for the caller to
     * fall back on.
     */
    private boolean revealAndPush(Path relativePath, int line) {
        String file = diffPath(relativePath);
        if (!inRenderedDiff(file)) {
            return false;
        }
        String key = "n" + line;
        diffColumn.revealLine(file, key);
        Path name = relativePath.getFileName();
        pushWaypoint(relativePath, name == null ? file : name.toString(), line, Optional.of(key));
        tourController.leftStep();
        return true;
    }

    /** The new-side line numbers of each review-diff file's ADD rows, keyed by relative path. */
    private Map<Path, Set<Integer>> changedLinesOfReviewDiff() {
        Map<Path, Set<Integer>> changed = new HashMap<>();
        loadedDiff().ifPresent(diff -> {
            for (UnifiedDiff.FileDiff file : diff.files()) {
                Set<Integer> lines = new HashSet<>();
                for (UnifiedDiff.Hunk hunk : file.hunks()) {
                    for (UnifiedDiff.Line line : hunk.lines()) {
                        if (line.kind() == UnifiedDiff.Line.Kind.ADD && line.newLine().isPresent()) {
                            lines.add(line.newLine().getAsInt());
                        }
                    }
                }
                if (!lines.isEmpty()) {
                    changed.put(Path.of(file.path()), lines);
                }
            }
        });
        return changed;
    }

    /**
     * A click on an underlined symbol in tour mode: a peek card over the
     * column, resolved off the FX thread. False (the diff-local lens opens
     * instead) in the hunk diff and wherever there is nothing to search.
     */
    private boolean peekAtSymbol(String symbol) {
        if (mode != ReviewMode.TOUR || navigation.isEmpty()) {
            return false;
        }
        ReviewNavigation nav = navigation.get();
        pushPeekWhenReady(new SymbolPeekService(nav.root(), nav.search()).peek(symbol, changedLinesOfReviewDiff()),
                "Looking for " + symbol + "…", "Nothing found for " + symbol, "Could not search for " + symbol);
        return true;
    }

    /** A peek at a file the column does not show: a waypoint's, or a search result's. */
    private void openLocationPeek(Path relativePath, int line) {
        if (navigation.isEmpty()) {
            // Nothing to read it from; saying so beats a key that did nothing.
            notice("Cannot open " + relativePath + " here: this scope has no checkout to read");
            return;
        }
        ReviewNavigation nav = navigation.get();
        pushPeekWhenReady(new SymbolPeekService(nav.root(), nav.search()).peekAt(relativePath, line),
                "Opening " + relativePath + "…", "Could not read " + relativePath,
                "Could not read " + relativePath);
    }

    /** A navigation outcome over the diff column, hidden again after a moment. */
    private void notice(String message) {
        showNotice(message);
        navNoticeTimer.playFromStart();
    }

    /** A progress line over the diff column; stays until {@link #clearNotice} or the next notice. */
    private void progressNotice(String message) {
        navNoticeTimer.stop();
        showNotice(message);
    }

    private void showNotice(String message) {
        // Kept clear of the peek card's action row at the bottom, as the Explorer's toast is.
        StackPane.setAlignment(navNotice, peekLayer.isOpen() ? Pos.TOP_CENTER : Pos.BOTTOM_CENTER);
        navNotice.setText(message);
        navNotice.setVisible(true);
        navNotice.setManaged(true);
    }

    private void clearNotice() {
        navNoticeTimer.stop();
        navNotice.setVisible(false);
        navNotice.setManaged(false);
    }

    /**
     * Shows {@code progress} at once, then pushes the peek when it lands --
     * unless the board was closed or moved to another scope meanwhile, where
     * a card would sit over code it has nothing to do with.
     */
    private void pushPeekWhenReady(CompletableFuture<Optional<SymbolPeek>> pending, String progress,
                                   String missing, String failed) {
        if (peekLayer.depth() >= PeekLayer.MAX_DEPTH) {
            notice("Peek stack is full — esc to unwind");
            return;
        }
        progressNotice(progress);
        Optional<String> scopeId = selectedScope().map(ReviewScope::id);
        pending.whenComplete((peek, failure) -> Platform.runLater(() -> {
            if (closed) {
                return;
            }
            // The progress line goes on every path, a stale one included.
            clearNotice();
            if (!scopeId.equals(selectedScope().map(ReviewScope::id))) {
                return;
            }
            if (failure != null) {
                LOG.log(Level.WARNING, failed, failure);
                notice(failed);
                return;
            }
            peek.ifPresentOrElse(peekLayer::push, () -> notice(missing));
        }));
    }

    /**
     * A peek's {@code ⏎}: in the column when it shows the file (a waypoint
     * and the pill), otherwise in the session's Explorer.
     */
    private void promotePeek(SymbolPeek peek) {
        peekLayer.clear();
        if (revealAndPush(peek.relativePath(), peek.startLine())) {
            return;
        }
        selectedScope().ifPresent(scope -> {
            if (!host.openInExplorer(scope, peek.relativePath(), peek.startLine())) {
                notice("No Explorer to open " + peek.relativePath() + " in");
            }
        });
    }

    private void askAboutPeek(SymbolPeek peek) {
        selectedScope().ifPresent(scope -> notice(host.askAgentAboutPeek(scope, peek)
                ? "Asked the session about " + peek.symbol() + " — the answer is in the agent view"
                : "No running session to ask about " + peek.symbol()));
    }

    /** The Search tab's opener: in the column (with a waypoint) when it shows the file, else a location peek. */
    private void openSearchResult(Path file, Path relativePath, OptionalInt line, String highlightQuery) {
        int target = line.orElse(1);
        peekLayer.clear();
        if (!revealAndPush(relativePath, target)) {
            openLocationPeek(relativePath, target);
        }
    }

    /** {@code v}: tour ↔ hunk diff, remembered until the scope changes. */
    private void toggleMode() {
        userChoseMode = true;
        mode = mode == ReviewMode.TOUR ? ReviewMode.DIFF : ReviewMode.TOUR;
        applyMode();
        revealCurrentSelection();
        if (mode == ReviewMode.TOUR) {
            tourController.revealCurrentStep();
        }
    }

    /** The failure's "Open diff review": today's hunk diff, chosen. */
    private void openDiffReview() {
        userChoseMode = true;
        mode = ReviewMode.DIFF;
        applyMode();
        revealCurrentSelection();
    }

    /** The tour's window onto the board; see {@link TourController.View}. */
    private final class TourViewAdapter implements TourController.View {
        @Override
        public Optional<ReviewScope> selectedScope() {
            return SessionReviewView.this.selectedScope();
        }

        @Override
        public Optional<ReviewScope> scopeById(String scopeId) {
            return SessionReviewView.this.scopeById(scopeId);
        }

        @Override
        public Optional<UnifiedDiff> loadedDiff() {
            return SessionReviewView.this.loadedDiff();
        }

        @Override
        public List<ReviewAnnotation> visibleFindings(ReviewScope scope) {
            return SessionReviewView.this.visibleFindings(scope);
        }

        @Override
        public boolean touring() {
            return mode == ReviewMode.TOUR;
        }

        @Override
        public Optional<ChangeGraph> graph(String scopeId) {
            return Optional.ofNullable(graphByScope.get(scopeId));
        }

        @Override
        public Optional<String> graphFailure(String scopeId, UnifiedDiff diff) {
            GraphFailure failure = graphFailureByScope.get(scopeId);
            return failure != null && failure.diff() == diff && !graphBuilding.contains(scopeId)
                    ? Optional.of(failure.reason())
                    : Optional.empty();
        }

        @Override
        public Optional<OutOfDiffFanIn.Result> fanIn(String scopeId) {
            return Optional.ofNullable(fanInByScope.get(scopeId));
        }

        @Override
        public Optional<ReviewNavigation> navigation() {
            return navigation;
        }

        @Override
        public Map<Path, Set<Integer>> changedLinesOfReviewDiff() {
            return SessionReviewView.this.changedLinesOfReviewDiff();
        }

        @Override
        public boolean trailEmpty() {
            return trail.isEmpty();
        }

        @Override
        public void pushWaypoint(Path file, String label, int line, Optional<String> lineKey) {
            SessionReviewView.this.pushWaypoint(file, label, line, lineKey);
        }

        @Override
        public void clearVerdictBar() {
            showFileOnBar(null, Optional.empty(), false);
            verdictBar.showProgress(0, 0);
        }

        @Override
        public void showKeyHints(List<TourKeyHints.Hint> hints) {
            keyStrip.setHidden(keyHintsHidden());
            keyStrip.setHints(hints);
        }

        @Override
        public void toggleKeyHints() {
            SessionReviewView.this.toggleKeyHints();
        }

        @Override
        public void showStepOnVerdictBar(ReviewVerdictBar.Target target, Optional<ReviewVerdict.Decision> decision,
                                         int settled, int total) {
            verdictBarFile = null;
            verdictBar.update(target, decision, false);
            verdictBar.showProgress(settled, total);
            verdictBar.showStale(Optional.empty());
        }

        @Override
        public void notice(String message) {
            SessionReviewView.this.notice(message);
        }

        @Override
        public void openLocationPeek(Path relativePath, int line) {
            SessionReviewView.this.openLocationPeek(relativePath, line);
        }

        @Override
        public boolean peekOpen() {
            return peekLayer.isOpen();
        }

        @Override
        public void toggleMode() {
            SessionReviewView.this.toggleMode();
        }

        @Override
        public void toggleSearchScope() {
            if (searchRail != null) {
                searchRail.toggleScope();
            }
        }

        @Override
        public void runReview() {
            runReviewOnSelection();
        }

        @Override
        public void openDiffReview() {
            SessionReviewView.this.openDiffReview();
        }

        @Override
        public void refreshReviewState() {
            SessionReviewView.this.refreshReviewState();
        }

        @Override
        public void triageFinding(ReviewScope scope, ReviewAnnotation finding, Triage triage,
                                  Optional<String> reason) {
            SessionReviewView.this.triageFinding(scope, finding, triage, reason);
        }

        @Override
        public boolean showMcpPanel() {
            if (mcpPanel.isPresent() && !mcpPanel.get().isVisible()) {
                toggleMcpPanel();
                return true;
            }
            return false;
        }

        @Override
        public void hideMcpPanel() {
            if (mcpPanel.filter(Node::isVisible).isPresent()) {
                toggleMcpPanel();
            }
        }
    }
    // ---- diagnostics --------------------------------------------------------

    /**
     * Diagnostic-only: what the switcher's chips read, left to right.
     *
     * <p>Routed through {@link ReviewDiagFxThread} for the same reason every
     * other {@code diag*} accessor is: it reads the {@code ObservableList} of
     * children the FX thread replaces wholesale on every render.</p>
     */
    List<String> diagChipTexts() {
        return ReviewDiagFxThread.call(switcher::diagChipTexts);
    }

    /** Diagnostic-only: the text of the chip that is actually selected, if any. */
    Optional<String> diagSelectedChipText() {
        return ReviewDiagFxThread.call(switcher::diagSelectedChipText);
    }

    /**
     * Diagnostic-only: presses the chip for {@code choice}, through the same
     * selection path a click takes -- including the guard that makes pressing
     * the already-selected chip do nothing.
     */
    /**
     * Diagnostic-only: delivers one key through the SAME filter real presses
     * take ({@link #onKeyPressed}), so a driver can settle a hunk without a
     * pointer. {@code app.drydock.diag.explorerScript}'s {@code reviewkey}
     * verb has documented this since Task 18 and never had an implementation
     * -- the verb fell through to the script's default branch, which prints
     * "mark", so a run that approved nothing looked exactly like one that
     * worked.
     */
    /**
     * Diagnostic-only: opens the gutter comment composer on the first changed
     * line. {@link ReviewDiffColumn#diagOpenComposer} has existed, complete
     * and FX-thread-safe, with no caller at all -- the {@code comment} verb it
     * was written for was documented in {@code DrydockApplication} and never
     * wired, so a script asking for it hit the script's default branch and
     * printed "mark". This is the missing hop.
     *
     * <p>It exists because the composer is opened by a click on a 34px label
     * inside a virtualized cell, which the harness cannot aim at -- so without
     * this there is no way to drive, or photograph, a gutter comment.</p>
     */
    public String diagOpenComposer() {
        return diffColumn.diagOpenComposer();
    }

    public void diagReviewKey(KeyCode code) {
        fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false));
    }

    void diagSelectChoice(SessionReviewScopes.Choice choice) {
        ReviewDiagFxThread.<Void>call(() -> {
            switcher.diagSelectChoice(choice);
            return null;
        });
    }

    /**
     * Test-only: records an outcome for a scope without running git. The
     * view derives everything from these outcomes now, so a test with a
     * synthetic diff and no real checkout has no other way in.
     */
    void diagPublishOutcome(String scopeId, DiffOutcome outcome) {
        outcomeByScope.put(scopeId, outcome);
        refreshReviewState();
    }

    /**
     * Test-only: renders {@code diff} in the column as though it had been
     * read for {@code scope}, with no git behind it.
     *
     * <p>Safe after the body has already asked for a real diff: {@code
     * showDiff} bumps the column's request token, so the in-flight git
     * completion is dropped rather than overwriting this one.</p>
     */
    void diagShowDiff(ReviewScope forScope, UnifiedDiff diff) {
        diffColumn.showDiff(forScope, diff);
    }

    /** Diagnostic-only: the file the hunk diff's cursor is on, if any. */
    Optional<String> diagCurrentFile() {
        return ReviewDiagFxThread.call(this::currentFile);
    }

    /** Diagnostic-only: the cursor as {@code "<file>#<hunk>"}, or empty. */
    Optional<String> diagCursor() {
        return ReviewDiagFxThread.call(() -> currentFile().map(file -> file + "#" + hunkCursor));
    }

    /** Diagnostic-only: whether the current file's verdicts are stale (spec §9.2). */
    SectionStates.Staleness diagStalenessOfCurrentFile() {
        return ReviewDiagFxThread.call(() -> board()
                .flatMap(b -> currentFile().map(file -> sections.stalenessOf(b, file)))
                .orElse(SectionStates.Staleness.UNKNOWN));
    }

    /**
     * Diagnostic-only: whether {@code scopeId} has a {@link ChangeGraph}
     * build in flight on {@link #SECTION_GRAPH_EXECUTOR}. A fixture that
     * shows a diff and moves straight into clicking the view races that
     * background build's completion -- {@link #refreshReviewState()} runs
     * from its {@code Platform.runLater} callback regardless of success or
     * failure, and can rebuild the rows a click just focused. Letting
     * a fixture wait on this before a test method starts closes that race
     * instead of leaving every test built on it to hit it by chance.
     */
    boolean diagGraphBuildPending(String scopeId) {
        return ReviewDiagFxThread.call(() -> graphBuilding.contains(scopeId));
    }

    /** Diagnostic-only: builds the next graphs with {@code builder} instead of {@link ChangeGraph#of}. */
    void diagSetGraphBuilder(Function<UnifiedDiff, ChangeGraph> builder) {
        graphBuilder = builder;
    }

    /** Diagnostic-only: the host the step panel acts through. */
    StepPanel.Host diagStepHost() {
        return tourController.stepHost();
    }

    /** See {@link #fanInScanThread} -- the thread the last fan-in scan ran on. */
    String diagFanInScanThread() {
        return fanInScanThread;
    }

    /**
     * Diagnostic-only: replaces {@code scopeId}'s caller scan with {@code
     * result}, as if the scan had returned it. A real scan still in flight
     * is superseded, so it cannot overwrite this when it lands. FX thread.
     */
    void diagSetFanIn(String scopeId, OutOfDiffFanIn.Result result) {
        fanInGenerationByScope.merge(scopeId, 1, Integer::sum);
        fanInByScope.put(scopeId, result);
        if (selectedScope().map(scope -> scope.id().equals(scopeId)).orElse(false)) {
            refreshReviewState();
        }
    }

    /**
     * Diagnostic-only: whether the Scene's real focus owner is inside {@link
     * #diffColumn} right now. A fixture's click-driven {@code clickOn} is a real TestFX
     * robot press: Monocle turns it into an FX {@code MouseEvent} on its own
     * schedule, off the calling thread, so a single {@code
     * waitForFxEvents()} after the click can return before that event has
     * even been posted -- it only waits for whatever was ALREADY queued.
     * Polling this (see {@code ReviewViewFixture#focusDiffColumn}) waits for
     * the actual postcondition instead of guessing how many drains cover the
     * gap.
     */
    boolean diagFocusInDiffColumn() {
        return ReviewDiagFxThread.call(
                () -> isDescendantOf(getScene() == null ? null : getScene().getFocusOwner(), diffColumn));
    }

    private static boolean isDescendantOf(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Diagnostic-only: the Scene focus state, for a test to log when focus
     * did not land where a click aimed it. A bare "focus never arrived"
     * says nothing; naming the owner and its ancestors is what traced a
     * CI-only failure to a click aimed at a diff cell hanging below the
     * list's viewport, whose centre lay over the verdict bar (see {@code
     * ReviewViewFixture#focusDiffColumn}).
     */
    String diagFocusSnapshot() {
        return ReviewDiagFxThread.call(() -> {
            Node owner = getScene() == null ? null : getScene().getFocusOwner();
            return "focusOwner=" + diagDescribe(owner)
                    + " inDiffColumn=" + isDescendantOf(owner, diffColumn)
                    + " chain=" + diagAncestorChain(owner);
        });
    }

    /**
     * Diagnostic-only: one node described as {@code
     * SimpleClassName[id][.styleClass...]("text if Labeled")} -- {@code
     * getClass().getSimpleName()} alone (what {@code diagFocusSnapshot} used
     * to report) says only "a Button", not which one.
     */
    private static String diagDescribe(Node node) {
        if (node == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder(node.getClass().getSimpleName());
        if (node.getId() != null) {
            sb.append('#').append(node.getId());
        }
        node.getStyleClass().forEach(c -> sb.append('.').append(c));
        if (node instanceof javafx.scene.control.Labeled labeled) {
            sb.append("(\"").append(labeled.getText()).append("\")");
        }
        return sb.toString();
    }

    /** Diagnostic-only: {@code node}'s ancestor chain, described via {@link #diagDescribe}. */
    private static String diagAncestorChain(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node n = node == null ? null : node.getParent(); n != null; n = n.getParent()) {
            sb.append(" < ").append(diagDescribe(n));
        }
        return sb.toString();
    }

    /**
     * Diagnostic-only: the findings margin's cards, read in the order they
     * are rendered, by the text their body actually shows -- the same text
     * {@link ReviewFindingsMargin}'s {@code cardBody} renders (the thread's
     * first message when there is one, the finding's title otherwise).
     *
     * <p>Routed through {@link ReviewDiagFxThread} for the same reason every
     * other {@code diag*} accessor is: the margin rebuilds its card list on
     * the FX thread on every {@link #refreshReviewState}.</p>
     */
    List<String> diagMarginFindingTitles() {
        return ReviewDiagFxThread.call(() -> lookupAll(".review-finding-body").stream()
                .map(node -> ((Label) node).getText())
                .toList());
    }

    /** Diagnostic-only: which surface the board shows. Call on the FX thread. */
    ReviewMode diagMode() {
        return mode;
    }

    /** Test-only: opens {@code peek} over the diff column as a resolved symbol click would. */
    public void diagPushPeek(SymbolPeek peek) {
        peekLayer.push(peek);
    }

    /** Diagnostic-only: whether a peek card is open. Call on the FX thread. */
    public boolean diagPeekOpen() {
        return peekLayer.isOpen();
    }

    /** Diagnostic-only: the trail's waypoints, oldest first. Call on the FX thread. */
    public List<NavigationTrail.Waypoint> diagTrail() {
        return trail.waypoints();
    }

    /** Test-only: replaces the trail, as restoring a saved one does. */
    void diagRestoreTrail(List<NavigationTrail.Waypoint> waypoints, int cursor) {
        trail.restore(waypoints, cursor);
        trailBar.render(trail);
    }

    /** Diagnostic-only: the navigation notice over the diff column, if one shows. */
    Optional<String> diagNotice() {
        return navNotice.isVisible() ? Optional.of(navNotice.getText()) : Optional.empty();
    }

    /** Diagnostic-only: which of the current step's anchors was last revealed. */
    int diagAnchorIndex() {
        return tourController.anchorIndex();
    }

    /** Diagnostic-only: whether the "↩ back to step" pill shows. */
    boolean diagBackPillShown() {
        return stepPanel.backPillShown();
    }

    /** Diagnostic-only: the step the tour is on, or null. Call on the FX thread. */
    String diagCurrentStepId() {
        return tourController.currentStepId();
    }

    /** Test-only: Run review as the top bar's button does, which a scope with no session disables. */
    void diagRunReview() {
        runReviewOnSelection();
    }

    /** Test-only: the "Building tour…" wait running out, without waiting 15 minutes. */
    void diagExpireTourWait() {
        tourController.expireWait();
    }

    /** Test-only: the pins the diff column would draw at {@code file}/{@code lineKey}. Call on the FX thread. */
    List<ReviewDiffColumn.Pin> diagPinsAt(String file, String lineKey) {
        return new PinSource().pinsAt(file, lineKey);
    }

    TourOutline diagOutline() {
        return outline;
    }

    StepPanel diagStepPanel() {
        return stepPanel;
    }
}
