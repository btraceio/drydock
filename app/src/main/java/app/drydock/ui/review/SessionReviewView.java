package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpActivityLog;
import app.drydock.review.BaseMove;
import app.drydock.review.ChangeGraph;
import app.drydock.review.HunkDigest;
import app.drydock.review.IntentGrouping;
import app.drydock.review.IntentHunks;
import app.drydock.review.OutOfDiffFanIn;
import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Sections;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.Severity;
import app.drydock.review.SubmitPlan;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The review board of one session's Review sub-tab (spec §3.2): the intent
 * rail, the diff column, the findings margin and the verdict bar, showing
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
         * The intents of {@code scope}, grouping {@code diff}: the reviewer's
         * grouping when one was supplied, otherwise the computed sections of
         * {@code graph} when one has finished building, otherwise one intent
         * per (kind, directory) cluster of the diff handed in.
         *
         * <p>The diff is a parameter rather than something the host fetches,
         * because the only correct diff here is the one the caller has
         * already established belongs to {@code scope}. A host that looked it
         * up would be free to look up the wrong one, which is exactly the
         * defect this shape removes.</p>
         *
         * <p>{@code graph} is empty both before one has been requested and
         * while it is still building -- {@link ChangeGraph#of} is blocking
         * and runs off the FX thread, so this view hands through whatever it
         * has on hand rather than waiting.</p>
         */
        List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff, Optional<ChangeGraph> graph);

        /**
         * How many times {@code scope}'s reviewer-supplied grouping has
         * changed ({@code IntentGrouping.version}). {@code diff} and {@code
         * graph} are values this view already compares by identity to keep
         * {@link #intents()}'s own cache fresh; a reviewer's grouping is the
         * one input to {@link #intents} that changes with NEITHER of those
         * changing; this is what lets the cache survive across more than
         * one call without polling {@link #intents} on every one just to
         * find out nothing changed -- which is what running {@code
         * Sections.of} on every navigation keypress amounted to.
         */
        long groupingVersion(ReviewScope scope);

        /**
         * Whether a reviewer has already supplied {@code scope}'s grouping.
         * A reviewer's grouping always wins over the computed sections (see
         * {@link #intents}), so building the {@link ChangeGraph} it would
         * otherwise take to compute them is pure waste when one already has
         * -- real parsing work, and a background completion whose only
         * observable effect is a needless extra {@code refreshReviewState}.
         */
        boolean hasReviewerGrouping(ReviewScope scope);

        /**
         * The verdict recorded on one hunk, if any -- keyed by the hunk's
         * content digest, never by an intent id. A section has no verdict of
         * its own: sections overlap, and an agent may regroup them at any
         * time, so a verdict keyed on a grouping would be orphaned by that
         * regrouping (spec §9.2). What a section shows is what its hunks
         * merge to, which is this view's job to derive.
         */
        Optional<ReviewVerdict> verdict(ReviewScope scope, String hunkDigest);

        /**
         * Records one verdict per hunk of {@code intent}; {@code decision}
         * empty undoes them all.
         *
         * <p>{@code hunkDigests} is computed by the caller rather than by the
         * host, for the reason {@link #intents} takes its diff as a parameter:
         * only this view knows which diff the human is actually looking at,
         * and a host free to re-derive them is free to derive them from a
         * different one. {@code blocked} comes along for the same reason:
         * the host refuses an {@code APPROVED} decision while it is true
         * (spec §4.6), and only this view can say so -- it is the one place
         * with the full current intents list a finding's named id has to be
         * checked against, which {@link #belongsToIntent} needs to tell a
         * finding that legitimately names a DIFFERENT, still-current intent
         * from one whose named id no longer resolves to anything at all. A
         * host computing its own approximation from {@code intent} alone
         * previously disagreed with the verdict bar's own rendered "blocked"
         * for exactly that case -- silently refusing a keypress the bar had
         * just shown as clear.</p>
         */
        void setVerdict(ReviewScope scope, ReviewIntent intent, List<String> hunkDigests,
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
         * base, so a base move that provably could not touch a section does
         * not spend the reader's attention on it (see {@link BaseMove}).
         *
         * <p>Called on the FX thread, so it must never block: a host that
         * cannot answer yet returns an {@link BaseMove.Delta#unresolvable}
         * delta -- "could matter", the safe direction -- rather than a
         * confident empty one.</p>
         */
        BaseMove.Delta baseMove(ReviewScope scope, String recordedBase);

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
         * <p>{@code annotation} already carries its intent: the view stamps
         * it with {@link ReviewAnnotation#withIntentId} before calling this,
         * for the same reason {@link #intents} takes its diff as a parameter
         * -- the only correct grouping is the one the caller has already
         * established belongs to this scope's current diff. Empty when no
         * intent covers the file, which costs the comment nothing -- the
         * margin falls back to matching it by file.</p>
         */
        void addComment(ReviewScope scope, ReviewAnnotation annotation);

        /**
         * The card's include/exclude toggle, for any finding -- including one
         * authored by "You"; see {@link ReviewFindingsMargin.Host#setPostToPr}.
         */
        void setPostToPr(ReviewScope scope, ReviewAnnotation finding, boolean post);

        /** {@code Apply patch} -- a human click; drydock never applies one on its own. */
        void applyPatch(ReviewScope scope, ReviewAnnotation finding);

        /** Records the human's severity override. */
        void overrideSeverity(ReviewScope scope, ReviewAnnotation finding, Severity severity);

        /**
         * Hands an intent's open findings to the scope's bound session.
         * False when there is no session to hand them to (or nothing to
         * hand), so a caller can say so rather than appear to have asked --
         * the same contract, and for the same reason, as {@link
         * #openInExplorer}: a control that reports nothing when it did
         * nothing is the silent failure this branch has now had to fix
         * three times.
         */
        boolean askAgentToFix(ReviewScope scope, ReviewIntent intent, List<ReviewAnnotation> findings);

        /**
         * Posts the review once every intent is settled. {@code index}
         * locates every finding's lines in the real diff (built from {@link
         * ReviewDiffColumn#displayedDiff()}, not the rendered rows -- see
         * {@link SubmitPlan.DiffIndex}), and {@code decisions} carries one
         * {@link ReviewVerdict.Decision} per counted intent, in the same
         * order {@link #submitReview()} already walked them in to confirm
         * every one had a verdict. Both live here, rather than being
         * recomputed by the host, because only this view can see a diff row
         * at all: {@code MainWorkspace} (package {@code app.drydock.ui}) has
         * no visibility into {@code app.drydock.ui.review}'s
         * package-private types.
         */
        void submit(ReviewScope scope, SubmitPlan.DiffIndex index, List<ReviewVerdict.Decision> decisions);

        /**
         * Runs the selected reviewer against {@code scope}: grants it the
         * scope handle and asks it to review. False when it cannot run (no
         * reviewer, or no session to run it in).
         */
        boolean runReview(ReviewScope scope);
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

    /**
     * What {@code a} / {@code r} / {@code u} act on (spec §9.6). Reading is
     * per hunk; settling usually is not, so one key needs several possible
     * targets rather than one key needing one each.
     */
    enum SettleUnit {
        /** The rail has focus: every hunk of the current section, as before this task. */
        SECTION,
        /** The diff column has focus: just the hunk it is anchored on. */
        HUNK,
        /** {@code ⇧A} / {@code ⇧R}: every hunk of the current file, regardless of focus. */
        FILE,
        /**
         * PATH mode is showing (Task 18): exactly the row selected there,
         * regardless of where real Scene focus is. Unlike {@code HUNK} --
         * which settles a SECTION's next unread hunk, never literally the
         * one under the pointer (see {@code ReviewVerdictBar#unitWord}) --
         * this settles the literal hunk the rail is displaying, because a
         * reader looking at one specific row and pressing {@code a} must not
         * have something else entirely recorded.
         */
        PATH_STEP
    }

    private final Host host;
    private final ReviewScopeSwitcher switcher = new ReviewScopeSwitcher();
    private final ReviewDiffColumn diffColumn;
    private final ReviewIntentRail intentRail = new ReviewIntentRail();
    /** Everything a section says about itself, derived from its hunks. */
    private final SectionStates sections;
    private final ReviewFindingsMargin margin;
    private final ReviewVerdictBar verdictBar;

    /**
     * Re-renders the verdict bar's acting-unit statement on every Scene
     * focus change (see {@link #settleUnit()}). Held as a field, rather
     * than an inline lambda passed straight to {@code addListener}, purely
     * so {@link #close()} can remove the SAME instance it was added with --
     * {@code ObservableValue.removeListener} matches by reference, and a
     * second lambda expression is never {@code equals} to the first.
     * Assigned in the constructor body (not here) because it closes over
     * {@link #verdictBar}, itself assigned in the constructor body -- a
     * field initializer referencing it here runs, per javac's definite-
     * assignment analysis, before that assignment has happened.
     */
    private final ChangeListener<Node> focusOwnerListener;

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
     * empty the rail, the verdict bar and the file count on every one after
     * it.</p>
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
     * been requested yet, or one is still building -- {@link #intents()}
     * passes {@link Optional#empty()} through in that gap, and {@link
     * IntentGrouping} falls back to the (kind, directory) clustering, so the
     * rail is never empty while the graph is in flight.
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

    /**
     * Scopes with a {@link ChangeGraph} build currently in flight, so the
     * rail can say the grouping on screen is provisional -- the (kind,
     * directory) fallback, not necessarily the final computed one -- rather
     * than silently swapping cards under a reviewer with no warning at all.
     */
    private final Set<String> graphBuilding = new HashSet<>();

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
     * {@link #intents()}'s last computed result, reused across as many
     * calls -- and as many {@link #refreshReviewState()} passes -- as
     * {@code scope}, {@code diff}, {@code graph} and {@link
     * Host#groupingVersion} stay the same. Every navigation keypress
     * ({@code [}, {@code ]}, {@code n}, {@code a}, {@code r}, {@code u})
     * ends in a full refresh, and {@link #findingsForMargin}, {@link
     * #currentIntent()} (itself called from several places), {@link
     * #renderVerdictBar} and the rail's own {@code setIntents} call all
     * read {@link #intents()} independently within each one -- so without
     * this cache, {@code Sections.of} ran on the FX thread multiple times
     * PER KEYPRESS, measured at over a second of real work on this branch's
     * own diff, none of which {@code Sections.of}'s own contract permits.
     *
     * <p>Those four fields are the ONLY inputs {@link IntentGrouping
     * intentsFor} has: {@code diff} and {@code graph} are plain values
     * compared by identity, and {@code groupingVersion} is the one thing
     * that can change with neither of those changing -- a reviewer's own
     * {@code set}/{@code clear}. Four unchanged fields is therefore exactly
     * as fresh a claim as recomputing, for however many refreshes that
     * holds, which is normally many: a reviewer's grouping changes far less
     * often than the cursor moves.</p>
     */
    private IntentsCacheEntry intentsCache;

    /** One completed {@link #intents()} lookup, keyed by what it was computed from. */
    private record IntentsCacheEntry(String scopeId, UnifiedDiff diff, ChangeGraph graph,
                                     long groupingVersion, List<ReviewIntent> intents) {
    }

    /**
     * {@code p}: the rail's second mode, one row per hunk in reading order
     * across section boundaries (spec §7.1). A mode of the rail, never a
     * fourth column -- {@link RailLayout} is untouched by this task -- so
     * this is the ONE bit that decides which of {@link
     * ReviewIntentRail#setIntents} / {@link ReviewIntentRail#showPath} the
     * next {@link #refreshReviewState} calls.
     */
    private boolean pathMode;

    /**
     * What a scope's fan-in is until its scan has actually run: {@code
     * unavailable=true}, the honest input for a signal nothing has measured
     * yet. {@link ReadingPath#of}'s own reason text says so ("outside callers
     * unknown") rather than reading a scan that did not run as one that
     * found nothing -- which is the whole distinction the fan-in affordance
     * rests on (spec §4.3).
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

    /** The row the verdict bar's {@code [} / {@code ]} / {@code n} move in PATH mode. */
    private int pathIndex;

    /**
     * {@link #currentPath()}'s last computed result, reused across calls the
     * same way {@link #intentsCache} is -- {@link ReadingPath#of} runs
     * {@link Sections#of} first and is, like it, string work over an
     * already-built graph rather than something to pay for on every
     * keystroke.
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

    /** The intent the verdict bar is settling; {@code [} / {@code ]} / {@code n} move it. */
    private int intentIndex;

    /**
     * {@link #intents()}'s result as of the last {@link #refreshReviewState}
     * pass, purely so the NEXT pass can tell whether the grouping changed
     * underneath the same scope and re-anchor {@link #intentIndex} by
     * content when it did -- see {@link #reanchorCursor}.
     */
    private List<ReviewIntent> lastIntents = List.of();

    /** The scope {@link #lastIntents} belongs to; a scope switch must not reanchor against it. */
    private String lastIntentsScopeId;

    /**
     * PATH mode's counterpart to {@link #lastIntents}: the steps the rail
     * last rendered, so a path that RE-SORTS under the reader can be told
     * from one that merely re-rendered -- see {@link #reanchorPathCursor}.
     */
    private List<ReadingPath.Step> lastPathSteps = List.of();

    /** The scope {@link #lastPathSteps} belongs to; a scope switch must not reanchor against it. */
    private String lastPathScopeId;

    /**
     * The id of the intent {@code a}/{@code r} last recorded a verdict on,
     * so {@code u} can snap the cursor back to it -- see {@link
     * #undoVerdict}. Cleared once undone, so a second {@code u} with
     * nothing left to undo is inert rather than reaching for an unrelated
     * intent. Not touched by {@code [}/{@code ]}/{@code n}: moving the
     * cursor around must not change what {@code u} targets, or "settle one,
     * look at another, undo" would undo the wrong one.
     */
    private Optional<String> lastSettledIntentId = Optional.empty();

    /**
     * The EXACT digests {@code a}/{@code r} last recorded a verdict on, so
     * {@code u} clears exactly those and nothing more -- since {@code a}/
     * {@code r} may have settled one hunk, one section or one file
     * depending on {@link #settleUnit()} at the time, undoing "the whole
     * current intent" (as before this task) would over-clear a single-hunk
     * approval or under-clear a whole-file one.
     */
    private List<String> lastSettledDigests = List.of();

    /**
     * Whether {@link #lastSettledDigests} was recorded by {@link
     * #pathVerdictAction} rather than {@link #verdictAction}'s intents-mode
     * branch -- {@code u} has to know which of the two to undo through,
     * since a step has no {@code id} the way an intent does (a step's own
     * identity is its hunk id, tracked in {@link #lastSettledPathHunkId}
     * instead). Set at the moment a verdict actually took, not read from
     * {@link #pathMode} at undo time: pressing {@code p} between settling
     * and undoing must not change which of the two {@code u} reaches for.
     */
    private boolean lastSettledWasPath;

    /** PATH mode's counterpart to {@link #lastSettledIntentId}: the exact step {@code u} snaps back to. */
    private Optional<String> lastSettledPathHunkId = Optional.empty();

    /** Set by {@code m}/{@code f}; remembered independently of the responsive collapse. */
    private boolean marginCollapsedByUser;

    /** Set by {@code i}/{@code f}; remembered independently of the responsive collapse. */
    private boolean intentsCollapsedByUser;

    private final Label countsLabel = new Label();

    /** The scope header (icon, title, comparison); hidden when there is no scope. */
    private VBox itemHeader;

    /**
     * True while there is no scope to show -- {@link #showResolving} or
     * {@link #showUnavailable}.
     *
     * <p>Everything except the top bar and the centred state is hidden in that
     * case, for the reason the destination's empty surface records: an intent
     * rail reading {@code 0/0}, a findings margin claiming "Nothing flagged in
     * this intent" when there is no intent, and a verdict bar with dead arrows
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
     * menu. An agentic review is the thing that fills the intents rail and
     * the findings margin, and while it lived behind a menu on a chip
     * labelled with an agent's name, nothing in the surface said it could be
     * asked for at all -- so nothing ever grouped anything.
     */
    private final Button runReviewButton = new Button("▶  Run review");
    private final VBox body = new VBox();

    private ReviewDensity density = ReviewDensity.COZY;

    private Region centre;

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
        this.focusOwnerListener =
                (obs, oldOwner, newOwner) -> verdictBar.showActingUnit(settleUnit());
        getStyleClass().addAll("review-destination", "session-review");
        // Review must never hold the window open. Its computed minimum is the
        // sum of the rail's and the margin's own minimums plus the code
        // column -- so below that the content pane stopped shrinking and
        // simply overflowed the right edge of the window, taking the intent
        // rail off-screen with it. That is the "unusable at narrow widths"
        // the responsive collapse exists to fix, and no amount of
        // re-splitting the rails would have fixed it: the view has to be
        // allowed to be as narrow as the slot it is given.
        setMinWidth(0);

        setTop(buildTopBar());
        // The intent rail on the left; the centre carries the code, the
        // margin and the verdict bar.
        centre = buildCenter();
        setLeft(intentRail);
        setCenter(centre);

        margin.setOnToggleCollapse(() -> setMarginCollapsed(!margin.collapsed()));
        intentRail.setOnToggleCollapse(() -> setIntentsCollapsed(!intentRail.collapsed()));
        intentRail.setSectionStateLookup(this::sectionState);
        intentRail.setOnSelected(intent -> {
            List<ReviewIntent> current = intents();
            int index = current.indexOf(intent);
            if (index >= 0) {
                intentIndex = index;
                refreshReviewState();
                revealCurrentIntent();
            }
        });
        intentRail.setOnPathSelected(step -> {
            List<ReadingPath.Step> steps = currentPath().steps();
            int index = steps.indexOf(step);
            if (index >= 0) {
                pathIndex = index;
                refreshReviewState();
                revealCurrentPathStep();
            }
        });
        // The fan-in count is an affordance, not a statistic (spec §7.4):
        // "called from 7 places outside the change" is the one reason on the
        // rail naming evidence the reader cannot see from where they are.
        intentRail.setFanIn(step -> !fanInOccurrences(step.file()).isEmpty(),
                (step, anchor) -> diffColumn.showFanIn(step.file(),
                        fanInOccurrences(step.file()), anchor, () -> askAboutFanIn(step)));
        margin.setOnFilterChanged(filter -> refreshReviewState());
        diffColumn.setPinSource(new PinSource());
        diffColumn.setCommentSink(annotation -> selectedScope().ifPresent(scope -> {
            // The intent that owns this code, so the comment lands under it
            // rather than floating outside the grouping.
            Optional<String> intentId = intents().stream()
                    .filter(intent -> intent.touches(annotation.file()))
                    .findFirst()
                    .map(ReviewIntent::id);
            host.addComment(scope, annotation.withIntentId(intentId));
            refreshReviewState();
            diffColumn.refreshPins();
        }));
        // The by-file intent fallback is derived from the diff, and the diff
        // arrives asynchronously -- so the verdict bar has to be re-rendered
        // when it lands, or it stays on the "no intent" it correctly computed
        // from an empty diff and never recovers.
        diffColumn.setOnDiffResolved((scopeId, outcome) -> {
            outcomeByScope.put(scopeId, outcome);
            boolean selected = selectedScope().map(scope -> scope.id().equals(scopeId)).orElse(false);
            if (outcome instanceof DiffOutcome.Loaded loaded) {
                // Unconditional (Task 19): the diff column's link footers
                // (spec §7.2) need this scope's graph regardless of the
                // rail's own mode or grouping source, not only where a
                // reviewer's grouping was itself computed from one or where
                // PATH mode is showing. requestGraph is a no-op for a diff
                // instance it has already graphed or is already building, so
                // this costs nothing on a re-diff or a re-selection. The
                // rail's OWN "refining grouping…" banner is gated
                // separately in refreshReviewState -- a reviewer's already-
                // final INTENTS grouping must not flash it while this build
                // runs purely for links.
                requestGraph(scopeId, loaded.diff());
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

        // See settleUnit()'s javadoc for why this reads real Scene focus
        // rather than a hand-tracked region flag: a flag toggled from a
        // MOUSE_PRESSED filter on the whole rail/column desyncs from a
        // scrollbar drag, the rail's own collapse toggle, and keyboard-only
        // navigation, none of which are a click on a card or into the diff.
        // The label has to stay live across whatever moves real focus, not
        // just the actions this view itself triggers, so it listens for
        // that directly rather than piggybacking on refreshReviewState().
        //
        // focusOwnerListener is held as a field, and this add/remove pair
        // (repeated in close()) is deliberate: the Scene handed in here is
        // app-lifetime (AppShell builds one for the whole application), so
        // a listener added and never removed keeps every SessionReviewView
        // ever opened -- diff column included -- strongly reachable for
        // the process's life, and re-attaching without removing the old
        // one first would stack a second listener under the same Scene.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.focusOwnerProperty().removeListener(focusOwnerListener);
            }
            if (newScene != null) {
                newScene.focusOwnerProperty().addListener(focusOwnerListener);
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
        HBox columns = new HBox(body, margin);
        VBox.setVgrow(columns, Priority.ALWAYS);

        VBox centre = new VBox(header, columns);
        mcpPanel.ifPresent(panel -> {
            panel.setVisible(false);
            panel.setManaged(false);
            centre.getChildren().add(panel);
        });
        // The verdict bar goes last, so even with the activity panel open it
        // is still the bottom-most thing and still always present.
        centre.getChildren().add(verdictBar);
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
     * Renders the selected scope: the same body / diff / intent wiring the
     * destination's {@code showItem} ran for a queue row.
     */
    private void renderSelectedScope() {
        ReviewScope scope = selectedScope().orElseThrow();
        headerIcon.setText(headerGlyphFor(scope));
        headerTitle.setText(headerTitleFor(scope));
        headerContext.setText(headerContextFor(scope));
        intentIndex = 0;
        pathIndex = 0;
        // Fallback intent ids are NOT scope-namespaced ("auto:change:src" is
        // just (kind, directory)), so two different scopes with a similar
        // layout can mint the identical id -- leaving this set across a
        // scope switch could make u undo, and jump into, a same-named
        // intent in the WRONG scope.
        lastSettledIntentId = Optional.empty();
        lastSettledDigests = List.of();
        lastSettledPathHunkId = Optional.empty();
        lastSettledWasPath = false;
        // The cursor is reset BEFORE the body is built, which the destination
        // did the other way round: a cached diff publishes Loaded
        // synchronously from inside bodyFor, and the diff-resolved handler
        // that fires off it would otherwise render the incoming scope at the
        // OUTGOING scope's intent index.
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
     * of it -- that override is the seam the findings margin and intent rail
     * arrive through.
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
     * keeps {@link #submitReview} from refusing. What it does NOT do for
     * itself is clear the outgoing scope's intent filter; this does, below,
     * before handing it the diff.</p>
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
        VBox.setVgrow(diffColumn, Priority.ALWAYS);
        // Checked before diffability: a diff already in hand is renderable
        // whether or not git could be run for it again.
        if (outcomeByScope.get(scope.id()) instanceof DiffOutcome.Loaded loaded) {
            // The outgoing scope's intent must not filter the incoming
            // scope's diff -- the rule setScope enforces for itself, which
            // showDiff does not, because it was written for a caller that
            // never navigates. Left alone, the filter survives: fallback
            // intent ids are not scope-namespaced ("auto:change:src" is just
            // (kind, directory)), so the two scopes of one branch collide on
            // them essentially always, and setIntent early-returns on an
            // equal id -- leaving the restored column filtered by the OTHER
            // scope's hunk ids, with its own files missing and nothing on
            // screen to say so.
            diffColumn.setIntent(null);
            diffColumn.showDiff(scope, loaded.diff());
            return diffColumn;
        }
        if (!scope.diffable()) {
            return placeholder("No checkout to diff",
                    "This scope has no working copy, so there is nothing to read here.", "");
        }
        diffColumn.setScope(scope);
        return diffColumn;
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
     * column was not making.</p>
     */
    private static String headerContextFor(ReviewScope scope) {
        String against = scope.kind() == ReviewScope.Kind.WORKING_TREE
                ? "HEAD"
                : scope.base();
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
     * Re-reads findings, intents and verdicts from the store. Called on every
     * store change, including the MCP router's, because a view that renders
     * from a cached value silently discards the other writer's work.
     */
    public void refreshReviewState() {
        // #intentsCache is NOT invalidated here: every input intentsFor has
        // -- scope, diff, graph, the reviewer's groupingVersion -- is
        // already covered by the cache's own key, so a refresh triggered by
        // something else entirely (a finding written, a verdict recorded)
        // correctly reuses it rather than re-running Sections.of to
        // rediscover the same answer.
        Optional<ReviewScope> scope = selectedScope();
        updateRunReviewButton();
        updateCountsLabel();
        if (scope.isEmpty()) {
            margin.setFindings(List.of());
            verdictBar.update(null, Optional.empty(), false);
            verdictBar.showProgress(0, 0);
            // No scope selected means no rail: leaving the previous scope's
            // cards up here is how the rail came to list a departed item's
            // files (see the whole-branch review this fixes).
            intentRail.setIntents(List.of(), null, ReviewIntentRail.Empty.NONE);
            intentRail.setGroupingPending(false);
            mcpPanel.ifPresent(panel -> panel.setScope(null));
            lastIntents = List.of();
            lastIntentsScopeId = null;
            return;
        }
        String scopeId = scope.get().id();
        List<ReviewIntent> currentIntents = intents();
        // Re-anchor the cursor BEFORE anything below reads it: a grouping
        // swap for the SAME scope (the computed graph landing over the
        // fallback shown while it built, or a reviewer's own regroup) must
        // not leave intentIndex pointing at whatever now happens to sit at
        // the same position -- verdictAction reads currentIntent() fresh at
        // keypress time, so a swap between a read and a keypress would
        // otherwise record an approval against hunks never actually read.
        if (scopeId.equals(lastIntentsScopeId) && !currentIntents.equals(lastIntents)) {
            reanchorCursor(lastIntents, currentIntents);
        }
        lastIntents = currentIntents;
        lastIntentsScopeId = scopeId;

        margin.invalidate(null);
        margin.setFindings(findingsForMargin(scope.get()));
        diffColumn.refreshPins();
        diffColumn.setLinks(linksByHunk());
        if (pathMode) {
            List<ReadingPath.Step> steps = currentPath().steps();
            // The same re-anchoring reanchorCursor does for INTENTS, for the
            // same reason and with more at stake: pathIndex is a POSITION,
            // and the out-of-diff fan-in scan is the reading path's first
            // rank term, so a scan landing mid-read re-sorts these steps
            // under the reader. Clamping alone would leave the cursor on
            // whatever hunk now occupies that position -- and since
            // settleUnit() is PATH_STEP unconditionally in this mode, the
            // reader's next `a` would approve a hunk they were never shown.
            if (scopeId.equals(lastPathScopeId) && !steps.equals(lastPathSteps)) {
                pathIndex = reanchorPathCursor(steps);
            }
            lastPathSteps = steps;
            lastPathScopeId = scopeId;
            if (!steps.isEmpty()) {
                pathIndex = Math.clamp(pathIndex, 0, steps.size() - 1);
            }
            String selectedHunkId = steps.isEmpty() ? null : steps.get(pathIndex).hunkId();
            intentRail.showPath(steps, selectedHunkId, emptyReason());
        } else {
            intentRail.setIntents(currentIntents, currentIntent().map(ReviewIntent::id).orElse(null),
                    emptyReason());
        }
        // The graph now builds unconditionally (Task 19, for the diff
        // column's link footers), but the rail's OWN "refining grouping…"
        // banner is about the RAIL's content, not the graph's existence: a
        // reviewer's INTENTS grouping is already final and does not change
        // when this build lands, so the banner stays gated on the same two
        // cases requestGraph used to be gated on before this task widened
        // its OWN trigger -- PATH mode (which reads the graph directly) and
        // no reviewer grouping (whose INTENTS fallback is what the graph
        // completing actually refines).
        intentRail.setGroupingPending(graphBuilding.contains(scopeId)
                && (pathMode || !host.hasReviewerGrouping(scope.get())));
        mcpPanel.filter(Node::isVisible)
                .ifPresent(panel -> panel.setScope(scope.get()));
        renderVerdictBar(scope.get());
    }

    /**
     * Re-anchors {@link #intentIndex} across a grouping change for the same
     * scope: to the same id when it still exists (nothing about the
     * selected intent actually changed), otherwise to whichever new intent
     * overlaps it in the most hunks (the grouping changed identity, not the
     * code being read). Left alone -- clamped to the new list's bounds at
     * most -- only when nothing in the new grouping shares any hunk with
     * what was selected, which a scope switch already guards this from
     * being asked to do at all (see the call site).
     */
    private void reanchorCursor(List<ReviewIntent> previous, List<ReviewIntent> current) {
        if (previous.isEmpty() || current.isEmpty()) {
            return;
        }
        ReviewIntent previouslySelected = previous.get(Math.clamp(intentIndex, 0, previous.size() - 1));
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).id().equals(previouslySelected.id())) {
                intentIndex = i;
                return;
            }
        }
        Set<String> previousHunks = new HashSet<>(previouslySelected.hunkIds());
        int bestIndex = -1;
        int bestOverlap = 0;
        for (int i = 0; i < current.size(); i++) {
            int overlap = 0;
            for (String hunkId : current.get(i).hunkIds()) {
                if (previousHunks.contains(hunkId)) {
                    overlap++;
                }
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestIndex = i;
            }
        }
        intentIndex = bestIndex >= 0 ? bestIndex : Math.clamp(intentIndex, 0, current.size() - 1);
    }

    /**
     * What the top bar states about the board: how much code is in it. The
     * destination said "N items · M repos" here, which a single checkout has
     * no equivalent of -- and the file count is the one number nothing else on
     * the board carries (the chips count findings, the verdict bar counts
     * intents). Blank while the diff is still loading, or failed: a confident
     * "0 files" would read as "nothing changed".
     */
    private void updateCountsLabel() {
        int files = selectedOutcome().orElse(null) instanceof DiffOutcome.Loaded loaded
                ? loaded.diff().files().size()
                : -1;
        countsLabel.setText(files < 0 ? "" : files + (files == 1 ? " file" : " files"));
    }

    /**
     * What the margin shows: the current intent's findings, or the whole
     * review's when {@code F} is on. A finding that names no intent is shown
     * either way -- it belongs to the review even if nothing grouped it.
     */
    private List<ReviewAnnotation> findingsForMargin(ReviewScope scope) {
        List<ReviewAnnotation> all = host.findings(scope);
        if (margin.wholeReview()) {
            return all;
        }
        Optional<ReviewIntent> current = currentIntent();
        if (current.isEmpty()) {
            return all;
        }
        return all.stream().filter(finding -> belongsToCurrentIntent(finding)).toList();
    }

    /** Whether a finding belongs under the intent now selected. See {@link #belongsToIntent}. */
    private boolean belongsToCurrentIntent(ReviewAnnotation finding) {
        return belongsToIntent(finding, currentIntent().orElse(null));
    }

    /**
     * Whether {@code finding} belongs under {@code intent}.
     *
     * <p>Matched by id when the finding names an intent the current grouping
     * actually contains, and by file otherwise. That second path is the
     * important one: a finding can name an intent that no longer exists --
     * a reviewer re-grouped, or the computed graph landed over the fallback
     * grouping the finding was recorded against. Matching on the id alone
     * made such a finding belong to no intent at all, so it silently
     * disappeared from every margin instead of being shown somewhere. A
     * finding is a thing a human or an agent went to the trouble of writing
     * down; it must not be possible for the UI to lose one by regrouping
     * around it.</p>
     *
     * <p>{@code intent} is a parameter rather than always {@link
     * #currentIntent()} because {@link #blockingFindingOpen} needs the SAME
     * rule stated for an arbitrary intent -- a finding naming a DIFFERENT
     * intent that still exists must not count against this one just because
     * it happens to touch one of this intent's files, which is exactly the
     * distinction a stale, no-longer-resolvable id cannot make for itself.
     * Reusing this one method is what keeps the verdict bar's own rendered
     * "blocked" and the write path's refusal from disagreeing.</p>
     *
     * <p>This is a deliberate relaxation from the write-path filter this
     * method replaced, which ended in {@code .orElse(true)}: an unnamed
     * finding used to block approval of EVERY intent, no matter which files
     * it actually touched. Here an unnamed finding only blocks the intents
     * whose files it touches, same as a named-but-stale one -- consistent
     * with what the verdict bar already showed, but it does mean an unnamed
     * blocking finding no longer blocks approval of an intent none of whose
     * files it touches.</p>
     */
    private boolean belongsToIntent(ReviewAnnotation finding, ReviewIntent intent) {
        if (intent == null) {
            return true;
        }
        String named = finding.intentId().orElse(null);
        if (named != null && intents().stream().anyMatch(candidate -> candidate.id().equals(named))) {
            return named.equals(intent.id());
        }
        // Unnamed, or naming an intent this grouping does not have: fall back
        // to where the finding actually is.
        return intent.touches(finding.file());
    }

    /**
     * Whether a still-open finding blocks approving {@code intent} (spec
     * §4.6) -- the same rule {@link #belongsToIntent} states for the
     * verdict bar's own rendered "blocked", reused here so the write path
     * (every {@code host.setVerdict} call site) can never refuse a keypress
     * the bar just showed as clear, or the reverse.
     */
    private boolean blockingFindingOpen(ReviewScope scope, ReviewIntent intent) {
        return host.findings(scope).stream()
                .filter(finding -> belongsToIntent(finding, intent))
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
     * The selected scope's intents. A scope whose diff has not loaded -- or
     * never will, because it has no checkout -- has none, and says so
     * through {@link #emptyReason()} rather than borrowing another's.
     */
    private List<ReviewIntent> intents() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return List.of();
        }
        if (!(selectedOutcome().orElse(null) instanceof DiffOutcome.Loaded loaded)) {
            return List.of();
        }
        String scopeId = scope.get().id();
        UnifiedDiff diff = loaded.diff();
        ChangeGraph graph = graphByScope.get(scopeId);
        long groupingVersion = host.groupingVersion(scope.get());
        IntentsCacheEntry cached = intentsCache;
        if (cached != null && cached.scopeId().equals(scopeId) && cached.diff() == diff
                && cached.graph() == graph && cached.groupingVersion() == groupingVersion) {
            return cached.intents();
        }
        List<ReviewIntent> computed = host.intents(scope.get(), diff, Optional.ofNullable(graph));
        intentsCache = new IntentsCacheEntry(scopeId, diff, graph, groupingVersion, computed);
        return computed;
    }

    /**
     * The selected scope's reading path (spec §6): {@link #EMPTY_PATH} until
     * its {@link ChangeGraph} exists, whether that is because none was
     * requested yet, one is still building off the FX thread, or the scope
     * itself has no diff -- {@link ReadingPath#of} takes a graph, not an
     * {@code Optional} of one, and there is nothing honest to compute a
     * reading order FROM before one exists.
     *
     * <p>Correction 2 of this task, in code: this calls {@link Sections#of}
     * exactly once, purely to hand its result to {@link ReadingPath#of} as
     * the grouping to reorder -- the result of that one call is never itself
     * rendered. Every reader of PATH mode (the rail, and {@link
     * #revealCurrentPathStep}) walks {@link ReadingPath.Path#steps()}, whose
     * {@link ReadingPath.Step#sectionNumber} already indexes {@link
     * ReadingPath.Path#sections()} -- the grouping's own order is never on
     * screen anywhere in this mode.</p>
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
     * {@link #currentPath()}'s links, keyed by {@link ReviewIntent#hunkId} --
     * what the diff column renders as a footer beneath each hunk (spec
     * §7.2), independent of whether the rail itself is in PATH mode. A step
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
     * scopeId} has no entry in {@link #graphByScope}, so {@link #intents()}
     * passes {@link Optional#empty()} through and the rail shows the (kind,
     * directory) clustering rather than nothing.
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
        CompletableFuture.supplyAsync(() -> ChangeGraph.of(diff), SECTION_GRAPH_EXECUTOR)
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
                        // refreshes either way, success or failure: a stale
                        // "refining grouping..." banner is exactly the
                        // regression a build that settles without a refresh
                        // produces, and it would otherwise sit there until
                        // some unrelated store change happened to refresh.
                        graphBuilding.remove(scopeId);
                        if (failure == null) {
                            graphByScope.put(scopeId, graph);
                            requestFanIn(scopeId, diff, graph);
                        } else {
                            // The (kind, directory) fallback is the honest
                            // answer, not a broken rail -- but a failed
                            // build must not be permanent: graphedDiffByScope
                            // recorded this diff BEFORE the parse ran, so
                            // without clearing it here, requestGraph's own
                            // "already graphed" guard would treat every
                            // later republish of this same diff instance as
                            // nothing new and never retry.
                            graphedDiffByScope.remove(scopeId);
                            LOG.log(Level.WARNING, "Could not build a section graph for scope "
                                    + scopeId, failure);
                        }
                        if (!closed && selectedScope().map(scope -> scope.id().equals(scopeId))
                                .orElse(false)) {
                            refreshReviewState();
                            // PATH mode's own reveal is a no-op with no graph
                            // (revealCurrentPathStep falls back to "whole
                            // scope" -- see its javadoc), and nothing else
                            // re-narrows the diff column once this landed:
                            // without this, entering PATH mode BEFORE a graph
                            // exists leaves the column showing the whole diff
                            // forever, even once real steps appear in the
                            // rail moments later.
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
                        if (failure != null) {
                            // Nothing is recorded, so fanInFor keeps
                            // reporting "not scanned" -- absent, never zero.
                            LOG.log(Level.WARNING, "Could not scan out-of-diff fan-in for scope "
                                    + scopeId, failure);
                            return;
                        }
                        // A scan that confirms what the board is already
                        // showing does not disturb the reader. The common
                        // case is a scope with nothing to grep (no worktree,
                        // or a checkout git cannot read): the answer is the
                        // same "unavailable, nothing measured" the board
                        // started with, and re-rendering the rail and
                        // re-narrowing the diff column to say so would move
                        // the ground under whoever is mid-review.
                        OutOfDiffFanIn.Result previous = fanInFor(scopeId);
                        if (previous.unavailable() == result.unavailable()
                                && previous.bySymbol().equals(result.bySymbol())) {
                            return;
                        }
                        fanInByScope.put(scopeId, result);
                        if (selectedScope().map(current -> current.id().equals(scopeId))
                                .orElse(false)) {
                            refreshReviewState();
                            // The scan is the reading path's FIRST rank term,
                            // so a landing scan can reorder the rail under
                            // the reader: the selected index then names a
                            // different step, and the diff column is narrowed
                            // to the old one until something re-reveals it.
                            revealCurrentSelection();
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
     * Every out-of-diff use of what {@code file} declares, by symbol.
     *
     * <p>Iterated over {@link ChangeGraph#changedDeclarations()} -- a sorted
     * set -- rather than over {@code bySymbol()}, whose iteration order is
     * the scan's to choose and therefore not something a rendered list may
     * rest on. {@link ReadingPath} documents the same rule for the same
     * reason; a popover that listed the same callers in a different order on
     * a second run would be a determinism defect (spec §9.5), not a
     * cosmetic one.</p>
     *
     * <p>Empty for an unavailable scan, so a count that was never measured
     * cannot render as one that came out zero.</p>
     */
    private Map<String, List<OutOfDiffFanIn.Occurrence>> fanInOccurrences(String file) {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return Map.of();
        }
        ChangeGraph graph = graphByScope.get(scope.get().id());
        OutOfDiffFanIn.Result fanIn = fanInFor(scope.get().id());
        if (graph == null || fanIn.unavailable()) {
            return Map.of();
        }
        Map<String, List<OutOfDiffFanIn.Occurrence>> bySymbol = new LinkedHashMap<>();
        for (String symbol : graph.changedDeclarations()) {
            if (!graph.fileDeclaring(symbol).filter(file::equals).isPresent()) {
                continue;
            }
            List<OutOfDiffFanIn.Occurrence> occurrences = fanIn.bySymbol().get(symbol);
            if (occurrences != null && !occurrences.isEmpty()) {
                bySymbol.put(symbol, List.copyOf(occurrences));
            }
        }
        return bySymbol;
    }

    /**
     * Which empty the rail is showing. A scope with a checkout whose diff has
     * not arrived is loading; one without a checkout never will; a loaded
     * diff with no files is a genuine "nothing changed here".
     */
    private ReviewIntentRail.Empty emptyReason() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return ReviewIntentRail.Empty.NONE;
        }
        DiffOutcome outcome = selectedOutcome().orElse(null);
        if (outcome instanceof DiffOutcome.Failed) {
            return ReviewIntentRail.Empty.DIFF_FAILED;
        }
        if (outcome instanceof DiffOutcome.Loaded loaded) {
            return loaded.diff().files().isEmpty()
                    ? ReviewIntentRail.Empty.NO_CHANGES
                    : ReviewIntentRail.Empty.NONE;
        }
        return scope.get().worktree().isEmpty()
                ? ReviewIntentRail.Empty.NOT_CHECKED_OUT
                : ReviewIntentRail.Empty.DIFFING;
    }

    private Optional<ReviewIntent> currentIntent() {
        List<ReviewIntent> intents = intents();
        if (intents.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(intents.get(Math.clamp(intentIndex, 0, intents.size() - 1)));
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
     * there is nothing to derive a section state from -- no scope, or a diff
     * that has not landed -- which the callers below each answer for
     * themselves rather than guessing at a default here.
     */
    private Optional<SectionStates.Board> board() {
        return selectedScope().flatMap(scope -> loadedDiff()
                .map(diff -> new SectionStates.Board(scope, diff, intents(),
                        Optional.ofNullable(graphByScope.get(scope.id())))));
    }

    /** The content digests of the hunks {@code intent} covers; none without a diff. */
    private List<String> digestsOf(ReviewIntent intent) {
        return board().map(b -> sections.digestsOf(b, intent)).orElse(List.of());
    }

    /**
     * The digests {@code a}/{@code r}/{@code u} act on for {@code intent}
     * over {@code unit} -- see {@link SectionStates#digestsForAction}. None
     * without a diff to derive them from.
     *
     * <p>{@code unit} is a parameter, never {@link #settleUnit()} read
     * afresh in here: the keyboard path computes it once, at key-press time,
     * and a mouse click on the verdict bar's own Approve/Request-changes
     * button captures it at PRESS time (see {@code ReviewVerdictBar}) --
     * pressing a focusable button moves Scene focus off the diff column
     * before the button's action fires, and re-reading {@code settleUnit()}
     * here would silently answer with whatever focus became by release,
     * not what it was when the reader decided to press.</p>
     */
    private List<String> digestsForAction(ReviewIntent intent, SettleUnit unit, boolean wholeFile) {
        return board().map(b -> sections.digestsForAction(b, intent, unit, wholeFile,
                        diffColumn.currentLineSelection()))
                .orElse(List.of());
    }

    /** What {@code intent}'s hunks merge to; nothing without a diff to merge over. */
    private Optional<ReviewVerdict.Decision> decisionOf(ReviewIntent intent) {
        return board().flatMap(b -> sections.decisionOf(b, intent));
    }

    /** One section's rendered state (spec §9.1). */
    private SectionStates.SectionState sectionState(ReviewIntent intent) {
        return board().map(b -> sections.stateOf(b, intent))
                .orElseGet(SectionStates.SectionState::unknown);
    }

    /** The sections progress is measured over and Submit demands a verdict on. */
    private List<ReviewIntent> countedSections() {
        return board().map(sections::counted).orElse(List.of());
    }

    /**
     * What {@code a} / {@code r} / {@code u} act on right now (spec §9.6):
     * {@code HUNK} when the diff column has real focus, {@code SECTION}
     * otherwise -- the same default the keys have always had, so a reader
     * who has never clicked into the diff column sees no change.
     *
     * <p>Reads the Scene's actual focus owner and walks its parent chain,
     * rather than a hand-tracked flag toggled from a {@code MOUSE_PRESSED}
     * filter on the whole rail or column: that flag desyncs the moment
     * something else moves real focus without going through this view's own
     * filters -- dragging the diff's scrollbar, clicking the rail's own
     * collapse toggle, or Tab-key navigation, none of which are "the reader
     * clicked a card or into the diff." A live Scene read has none of those
     * gaps, and is equally immune to the bug an earlier attempt hit with
     * {@code Node.isFocusWithin()}: that bug was a stuck ref-count (see
     * {@code ReviewIntentRail#rebuild}'s card replacement and JavaFX's
     * {@code Direction.NEXT} focus-cleanup traversal, in the project's
     * JavaFX-traps memory) that read {@code true} while the REAL focus
     * owner's own parent chain never touched the diff column at all -- a
     * fresh read of {@code getFocusOwner()} every time never accumulates
     * that kind of staleness.</p>
     */
    SettleUnit settleUnit() {
        // PATH mode wins outright, regardless of focus: the whole point of
        // the mode is that the reader is looking at one specific hunk, and
        // "focus happens to be elsewhere" must not silently widen what a/r/u
        // touch back out to a whole section the reader never opened -- see
        // the CRITICAL fix this constant carries (Task 18 follow-up).
        if (pathMode) {
            return SettleUnit.PATH_STEP;
        }
        return isDescendantOf(getScene() == null ? null : getScene().getFocusOwner(), diffColumn)
                ? SettleUnit.HUNK
                : SettleUnit.SECTION;
    }

    private static boolean isDescendantOf(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void renderVerdictBar(ReviewScope scope) {
        Optional<SectionStates.Board> board = board();
        if (pathMode) {
            renderVerdictBarForPathStep(scope, board);
            return;
        }
        Optional<ReviewIntent> current = currentIntent();
        if (current.isEmpty() || board.isEmpty()) {
            verdictBar.update(null, Optional.empty(), false);
            verdictBar.showProgress(0, 0);
            verdictBar.showStale(Optional.empty());
            verdictBar.showActingUnit(settleUnit());
            return;
        }
        boolean blocked = blockingFindingOpen(scope, current.get());
        SectionStates.SectionState state = sectionState(current.get());
        verdictBar.update(current.get(), state.decision(), blocked);
        // Progress is the UNION of the counted sections' hunks, counted once.
        verdictBar.showProgress(sections.settledHunkCount(board.get()),
                sections.distinctDigests(board.get()).size());
        verdictBar.showStale(state.staleness() == SectionStates.Staleness.MOVED
                ? Optional.of(new ReviewVerdictBar.StaleInfo(
                        sections.oldBaseOf(board.get(), current.get()), host.currentBase(scope)))
                : Optional.empty());
        verdictBar.showActingUnit(settleUnit());
    }

    /**
     * PATH mode's own verdict-bar render: the SELECTED ROW's own state, not
     * the (now invisible) intents cursor's -- a screenshot proved the bar
     * used to read "2 · Profiler" with a completely different row selected,
     * and clicking Undo cleared two hunks nowhere near the one on screen.
     * Reuses {@link SectionStates} against a throwaway single-hunk {@link
     * ReviewIntent} ({@link #pathStepAsIntent}) rather than deriving
     * anything new: asking "what does this one-hunk grouping's state look
     * like" is exactly the question {@code SectionStates.stateOf} already
     * answers correctly for any {@link ReviewIntent}, real or synthetic.
     * Progress stays the whole-review count either way -- it was never the
     * current intent's own count, so PATH mode changes nothing about it.
     */
    private void renderVerdictBarForPathStep(ReviewScope scope, Optional<SectionStates.Board> board) {
        Optional<ReadingPath.Step> step = currentPathStep();
        if (step.isEmpty() || board.isEmpty()) {
            verdictBar.update(null, Optional.empty(), false);
            verdictBar.showProgress(0, 0);
            verdictBar.showStale(Optional.empty());
            verdictBar.showActingUnit(settleUnit());
            return;
        }
        ReviewIntent synthetic = pathStepAsIntent(step.get());
        boolean blocked = blockingFindingOpenForPathStep(scope, step.get(), false);
        SectionStates.SectionState state = sectionState(synthetic);
        verdictBar.update(synthetic, state.decision(), blocked);
        verdictBar.showProgress(sections.settledHunkCount(board.get()),
                sections.distinctDigests(board.get()).size());
        verdictBar.showStale(state.staleness() == SectionStates.Staleness.MOVED
                ? Optional.of(new ReviewVerdictBar.StaleInfo(
                        sections.oldBaseOf(board.get(), synthetic), host.currentBase(scope)))
                : Optional.empty());
        verdictBar.showActingUnit(settleUnit());
    }

    /**
     * Points the diff column at the current intent.
     *
     * <p>The column narrows to that intent's hunks rather than merely
     * scrolling to them. Scrolling was what this did before, and on a
     * 45-file diff it was indistinguishable from doing nothing: the reader
     * clicked intent 12 and got the same wall of code, so the rail read as
     * decoration. The column's own {@code whole scope} chip is the way
     * back out (spec §4.4).</p>
     */
    private void revealCurrentIntent() {
        ReviewIntent intent = currentIntent().orElse(null);
        diffColumn.setIntent(intent);
        // Still scrolled, for the case the reader has taken the escape hatch:
        // the whole scope is on screen and the intent has to be found in it.
        if (intent != null) {
            intent.anchor().ifPresent(anchor ->
                    diffColumn.revealHunk(anchor.file(), anchor.hunkIndex()));
        }
    }

    /** {@code [} / {@code ]}: moves the intent the verdict bar is settling. */
    private void moveIntent(int delta) {
        List<ReviewIntent> intents = intents();
        if (intents.isEmpty()) {
            return;
        }
        intentIndex = (int) Math.clamp((long) intentIndex + delta, 0, intents.size() - 1);
        refreshReviewState();
        revealCurrentIntent();
    }

    /** {@code n}: jumps to the next intent with no verdict yet. */
    private void nextUnsettledIntent() {
        Optional<ReviewScope> scope = selectedScope();
        List<ReviewIntent> intents = intents();
        if (scope.isEmpty() || intents.isEmpty()) {
            return;
        }
        // Only a section that can actually be settled: a collapsed one has
        // nothing to read, and one whose hunk ids no longer resolve has
        // nothing to settle, so parking the cursor on either is a dead end.
        List<ReviewIntent> countable = countedSections();
        for (int offset = 1; offset <= intents.size(); offset++) {
            int candidate = (intentIndex + offset) % intents.size();
            ReviewIntent intent = intents.get(candidate);
            if (countable.contains(intent) && decisionOf(intent).isEmpty()) {
                intentIndex = candidate;
                refreshReviewState();
                revealCurrentIntent();
                return;
            }
        }
    }

    // ---- PATH mode ------------------------------------------------------------

    /** Which of the rail's two modes is showing -- test seam for the {@code p} parity test. */
    ReviewIntentRail.Mode railMode() {
        return intentRail.mode();
    }

    /**
     * {@code p}: flips the rail between {@code INTENTS} and {@code PATH}
     * (spec §7.1). The mode flips immediately either way -- {@link
     * #refreshReviewState} renders PATH mode with however many steps {@link
     * #currentPath()} can answer with right now, which is {@code List.of()}
     * until a {@link ChangeGraph} exists.
     *
     * <p>Entering PATH mode is what makes this task ask for a graph a
     * reviewer's own grouping would otherwise never need: {@link
     * #requestGraph} is a no-op when one is already in flight or already
     * built for this diff, so a scope with no reviewer grouping (which
     * already triggered a build on diff-resolved) pays nothing extra here,
     * and one that DOES have a reviewer's grouping -- which skips that
     * automatic build entirely, see {@code Host#hasReviewerGrouping} -- gets
     * its graph built for the first time, lazily, only once a human actually
     * asks to read in this order.</p>
     */
    private void togglePathMode() {
        pathMode = !pathMode;
        if (pathMode) {
            pathIndex = 0;
            selectedScope().ifPresent(scope -> loadedDiff().ifPresent(diff ->
                    requestGraph(scope.id(), diff)));
        }
        refreshReviewState();
        revealCurrentSelection();
    }

    /** {@code [} / {@code ]}: moves whichever cursor the rail is currently showing. */
    private void moveSelection(int delta) {
        if (pathMode) {
            movePathStep(delta);
        } else {
            moveIntent(delta);
        }
    }

    /** {@code n}: jumps to the next unsettled hunk, in whichever order the rail is showing. */
    private void nextUnsettled() {
        if (pathMode) {
            nextUnsettledPathStep();
        } else {
            nextUnsettledIntent();
        }
    }

    /** Reveals whatever the rail's current mode has selected. */
    private void revealCurrentSelection() {
        if (pathMode) {
            revealCurrentPathStep();
        } else {
            revealCurrentIntent();
        }
    }

    /**
     * Points the diff column at the current PATH row -- the same narrowing
     * {@link #revealCurrentIntent} does for an intent, over a single hunk
     * instead of a whole section. Built as a one-hunk {@link ReviewIntent}
     * purely to reuse {@link ReviewDiffColumn#setIntent}'s existing filter
     * and anchor machinery -- {@code containsHunk} and {@code anchor()} both
     * already do exactly what a single {@link ReadingPath.Step} needs, and
     * duplicating them for a second selectable type would be the same
     * behaviour twice.
     *
     * <p>Falls back to the whole scope ({@code setIntent(null)}) while {@link
     * #currentPath()} has no steps yet -- entering PATH mode before its
     * {@link ChangeGraph} exists is the common case, not a corner one, so
     * this must be called again once the graph lands (see {@link
     * #requestGraph}'s completion callback) or the column would stay on
     * "whole scope" forever even after the rail fills in with real rows.</p>
     */
    private void revealCurrentPathStep() {
        List<ReadingPath.Step> steps = currentPath().steps();
        if (steps.isEmpty()) {
            diffColumn.setIntent(null);
            return;
        }
        ReadingPath.Step step = steps.get(Math.clamp(pathIndex, 0, steps.size() - 1));
        ReviewIntent synthetic = pathStepAsIntent(step);
        diffColumn.setIntent(synthetic);
        synthetic.anchor().ifPresent(anchor -> diffColumn.revealHunk(anchor.file(), anchor.hunkIndex()));
    }

    /** {@code [} / {@code ]} in PATH mode: moves the row the rail is showing. */
    private void movePathStep(int delta) {
        List<ReadingPath.Step> steps = currentPath().steps();
        if (steps.isEmpty()) {
            return;
        }
        pathIndex = (int) Math.clamp((long) pathIndex + delta, 0, steps.size() - 1);
        refreshReviewState();
        revealCurrentPathStep();
    }

    /**
     * {@code n} in PATH mode: the next row whose hunk has no verdict yet --
     * "next unsettled" stated over hunks, which is what it has always meant
     * (spec's own correction on this task: a property of hunks, not of
     * whichever grouping the rail happens to be showing).
     */
    private void nextUnsettledPathStep() {
        Optional<ReviewScope> scope = selectedScope();
        Optional<UnifiedDiff> diff = loadedDiff();
        List<ReadingPath.Step> steps = currentPath().steps();
        if (scope.isEmpty() || diff.isEmpty() || steps.isEmpty()) {
            return;
        }
        for (int offset = 1; offset <= steps.size(); offset++) {
            int candidate = (pathIndex + offset) % steps.size();
            Optional<String> digest = digestOfPathStep(diff.get(), steps.get(candidate));
            if (digest.isPresent() && host.verdict(scope.get(), digest.get()).isEmpty()) {
                pathIndex = candidate;
                refreshReviewState();
                revealCurrentPathStep();
                return;
            }
        }
    }

    /**
     * Where the reader's hunk sits in a path that has just been recomputed.
     *
     * <p>Called only when the step list actually CHANGED (see the caller),
     * so a plain {@code [}/{@code ]} move -- which writes {@link #pathIndex}
     * and then refreshes against an unchanged list -- is never dragged back
     * to where it came from.</p>
     *
     * <p>Identity is the hunk id, never the position. A hunk that is no
     * longer in the path at all (a newly-arrived diff dropped it) leaves the
     * index alone for the caller's clamp to own: there is nowhere honest to
     * put a cursor whose hunk has gone.</p>
     */
    private int reanchorPathCursor(List<ReadingPath.Step> steps) {
        if (lastPathSteps.isEmpty() || steps.isEmpty()) {
            return pathIndex;
        }
        String hunkId = lastPathSteps.get(Math.clamp(pathIndex, 0, lastPathSteps.size() - 1)).hunkId();
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).hunkId().equals(hunkId)) {
                return index;
            }
        }
        return pathIndex;
    }

    /** {@code step}'s hunk id, as the single-hunk {@link ReviewIntent} the diff column filters on. */
    private static ReviewIntent pathStepAsIntent(ReadingPath.Step step) {
        return new ReviewIntent("path:" + step.hunkId(), step.sectionNumber(), step.file(),
                ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.NONE, step.reason(),
                List.of(step.hunkId()), Optional.empty(), false);
    }

    /** The content digest of {@code step}'s one hunk in {@code diff}, if it still resolves. */
    private static Optional<String> digestOfPathStep(UnifiedDiff diff, ReadingPath.Step step) {
        List<String> digests = IntentHunks.digestsOf(pathStepAsIntent(step), diff);
        return digests.isEmpty() ? Optional.empty() : Optional.of(digests.get(0));
    }

    /**
     * "Ask the agent" from the fan-in popover: posts the question as a real
     * review comment on {@code step}'s file and hands it to the scope's bound
     * session, through the two seams that already exist for exactly those
     * two things ({@link Host#addComment}, {@link Host#askAgentToFix}).
     *
     * <p>Not a new key. {@code a} is the approve gesture on this board, and
     * a popover that stole it would be Task 18's "acted on something the
     * reader could not see" defect again; this is a button in the popover
     * and nothing else.</p>
     *
     * <p>The question names the symbols and the file they are declared in --
     * the fan-in list is lexical and cannot say whether a caller breaks, so
     * what this surface can honestly do is point the party that can answer
     * at the right file rather than leaving the reader to retype it.</p>
     */
    private boolean askAboutFanIn(ReadingPath.Step step) {
        Optional<ReviewScope> scope = selectedScope();
        Map<String, List<OutOfDiffFanIn.Occurrence>> bySymbol = fanInOccurrences(step.file());
        Optional<String> lineKey = lineKeyOfPathStep(step);
        if (scope.isEmpty() || bySymbol.isEmpty() || lineKey.isEmpty()) {
            return false;
        }
        int total = bySymbol.values().stream().mapToInt(List::size).sum();
        String question = "This change alters " + String.join(", ", bySymbol.keySet())
                + " in " + step.file() + ", and " + total
                + (total == 1 ? " place" : " places") + " outside the change reference "
                + (bySymbol.size() == 1 ? "it" : "them")
                + ". Do any of those callers break, and which ones should I read?";
        ReviewAnnotation asked = ReviewAnnotation.human(scope.get().id(), step.file(),
                lineKey.get(), lineKey.get(),
                new ReviewAnnotation.Message("You", Instant.now(), question));
        // Stamped with the intent that owns the file, exactly as the gutter
        // composer's comments are -- a comment outside the grouping is one
        // the margin has to fall back to matching by file.
        Optional<String> intentId = intents().stream()
                .filter(intent -> intent.touches(step.file()))
                .findFirst()
                .map(ReviewIntent::id);
        ReviewAnnotation stamped = asked.withIntentId(intentId);
        host.addComment(scope.get(), stamped);
        boolean handedOff = host.askAgentToFix(scope.get(), pathStepAsIntent(step), List.of(stamped));
        refreshReviewState();
        diffColumn.refreshPins();
        // Returned, not swallowed: with no bound session the comment is
        // filed and NOTHING is sent, and a popover that closed on that would
        // leave the reviewer waiting for an answer nobody was asked for.
        return handedOff;
    }

    /**
     * The line key {@link #askAboutFanIn}'s comment is anchored to: the first
     * line of {@code step}'s own hunk. Walked with the same {@link
     * ReviewIntent#containsHunk} test {@link IntentHunks} uses, so the
     * comment lands on the hunk the row is about rather than on the file's
     * first one.
     */
    private Optional<String> lineKeyOfPathStep(ReadingPath.Step step) {
        ReviewIntent synthetic = pathStepAsIntent(step);
        return loadedDiff().flatMap(diff -> {
            for (UnifiedDiff.FileDiff file : diff.files()) {
                if (!file.path().equals(step.file())) {
                    continue;
                }
                for (int index = 0; index < file.hunks().size(); index++) {
                    UnifiedDiff.Hunk hunk = file.hunks().get(index);
                    if (synthetic.containsHunk(file.path(), index) && !hunk.lines().isEmpty()) {
                        return Optional.of(hunk.lines().get(0).lineKey());
                    }
                }
            }
            return Optional.<String>empty();
        });
    }

    /** The row PATH mode is currently showing, if any -- empty exactly when {@link #currentPath()} has no steps. */
    private Optional<ReadingPath.Step> currentPathStep() {
        List<ReadingPath.Step> steps = currentPath().steps();
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(steps.get(Math.clamp(pathIndex, 0, steps.size() - 1)));
    }

    /**
     * The REAL intents (sections overlap, so possibly several) that
     * actually cover {@code step}'s one hunk -- what {@link
     * #blockingFindingOpenForPathStep} and {@link #openFindingsForPathStep}
     * both resolve a step through, since neither a blocking finding nor an
     * agent hand-off can be asked about a throwaway synthetic id ({@link
     * #pathStepAsIntent}) a finding could never actually name.
     */
    private List<ReviewIntent> intentsCoveringPathStep(ReadingPath.Step step) {
        Optional<ReviewIntent.Anchor> anchor = pathStepAsIntent(step).anchor();
        if (anchor.isEmpty()) {
            return List.of();
        }
        return intents().stream()
                .filter(intent -> intent.containsHunk(anchor.get().file(), anchor.get().hunkIndex()))
                .toList();
    }

    /**
     * The still-open findings {@code step} hands to the agent (spec's own
     * "ask the agent to fix" gesture) -- resolved through {@code step}'s
     * REAL covering intents so a finding naming one of them is included the
     * same way {@link #belongsToCurrentIntent} would from that intent's own
     * card, with an unnamed/unresolvable finding falling back to the file
     * when no real intent claims this hunk at all.
     */
    private List<ReviewAnnotation> openFindingsForPathStep(ReviewScope scope, ReadingPath.Step step) {
        List<ReviewIntent> covering = intentsCoveringPathStep(step);
        return host.findings(scope).stream()
                .filter(finding -> !finding.resolved())
                .filter(finding -> covering.isEmpty()
                        ? finding.file().equals(step.file())
                        : covering.stream().anyMatch(intent -> belongsToIntent(finding, intent)))
                .toList();
    }

    /**
     * Test-only: the row {@code [} / {@code ]} / {@code n} last selected in
     * PATH mode. Routed through {@link ReviewDiagFxThread} like every other
     * {@code diag*}-shaped accessor: {@link #pathIndex} is written only on
     * the FX thread, by the same keypress handling a test drives via a
     * TestFX robot.
     */
    int selectedPathStepForTest() {
        return ReviewDiagFxThread.call(() -> pathIndex);
    }

    /**
     * Test-only: PATH mode's rendered row texts, in rendered order. Routed
     * through {@link ReviewDiagFxThread} for the same reason every other
     * {@code diag*} accessor is: it reads the rail's {@code ObservableList}
     * of rows, which the FX thread rebuilds wholesale on every render.
     */
    /**
     * Diagnostic-only: enters PATH mode if it is not already showing, then
     * opens the first fan-in popover. The visual pass over that popover has
     * no other way in -- it is a separate {@code Popup} window, and Robot
     * input never reaches a diag run.
     */
    public String diagOpenFanIn() {
        return ReviewDiagFxThread.call(() -> {
            if (!pathMode) {
                togglePathMode();
            }
            return intentRail.diagOpenFanIn();
        });
    }

    /** See {@link #fanInScanThread} -- the thread the last fan-in scan ran on. */
    String diagFanInScanThread() {
        return fanInScanThread;
    }

    List<String> pathRowTextsForTest() {
        return ReviewDiagFxThread.call(intentRail::diagPathRowTexts);
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
            for (ReviewAnnotation finding : host.findings(scope.get())) {
                if (!finding.file().equals(file)) {
                    continue;
                }
                if (!finding.startKey().equals(lineKey) && !finding.endKey().equals(lineKey)) {
                    continue;
                }
                Integer number = numbers.get(finding.key());
                // number == null means the margin is not showing this finding
                // (the `open` filter, or another intent). The pin dims and
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
    }

    /** The verdict bar's window onto the host, with the scope filled in. */
    private final class VerdictHost implements ReviewVerdictBar.Host {
        @Override
        public void approve(ReviewIntent intent, SettleUnit unit) {
            // The verdict bar's own Approve button, not just the keyboard:
            // AGENTS.md requires a shortcut to have a working button
            // equivalent, and vice versa, so a click here must settle
            // exactly what `a` does -- the selected PATH row, never
            // whatever `intent`/`unit` the bar's own (intents-cursor-driven)
            // render happened to capture.
            if (pathMode) {
                pathVerdictAction(ReviewVerdict.Decision.APPROVED, false);
                return;
            }
            selectedScope().ifPresent(scope -> host.setVerdict(scope, intent,
                    digestsForAction(intent, unit, false), Optional.of(ReviewVerdict.Decision.APPROVED),
                    blockingFindingOpen(scope, intent)));
        }

        @Override
        public void requestChanges(ReviewIntent intent, SettleUnit unit) {
            if (pathMode) {
                pathVerdictAction(ReviewVerdict.Decision.CHANGES, false);
                return;
            }
            selectedScope().ifPresent(scope -> host.setVerdict(scope, intent,
                    digestsForAction(intent, unit, false), Optional.of(ReviewVerdict.Decision.CHANGES),
                    blockingFindingOpen(scope, intent)));
        }

        @Override
        public boolean askAgentToFix(ReviewIntent intent) {
            // Routed through the SELECTED ROW in PATH mode, not the intent
            // the bar happened to be handed (see the class-level javadoc on
            // renderVerdictBarForPathStep for why that intent no longer
            // reflects what is on screen).
            //
            // The answer is RETURNED, not swallowed: with no session bound
            // (or nothing open to send) this hands over nothing at all, and
            // a button that then looks exactly as though it worked is the
            // silent failure ruling 1 legislated against -- already fixed
            // once on the fan-in popover, and this is the same defect one
            // surface over.
            if (pathMode) {
                return currentPathStep().flatMap(step -> selectedScope().map(scope ->
                                host.askAgentToFix(scope, pathStepAsIntent(step),
                                        openFindingsForPathStep(scope, step))))
                        .orElse(false);
            }
            return selectedScope().map(scope -> host.askAgentToFix(scope, intent,
                            host.findings(scope).stream()
                                    .filter(finding -> !finding.resolved())
                                    .filter(SessionReviewView.this::belongsToCurrentIntent)
                                    .toList()))
                    .orElse(false);
        }

        @Override
        public void undo(ReviewIntent intent) {
            // Re-review, too (spec §9.2): a stale section's banner button and
            // the plain undo button both just clear what is recorded. An
            // undo is never refused, so the flag here is inert -- passed
            // for the sole reason that host.setVerdict has one parameter,
            // not two overloads to keep in sync.
            //
            // PATH mode clears exactly the SELECTED ROW's one hunk, never
            // digestsOf(intent) over the (invisible) intents cursor's whole
            // section -- a screenshot proved that click cleared two hunks
            // nowhere near the row on screen and left the visible one alone.
            if (pathMode) {
                currentPathStep().ifPresent(step -> selectedScope().ifPresent(scope ->
                        loadedDiff().flatMap(diff -> digestOfPathStep(diff, step)).ifPresent(digest ->
                                host.setVerdict(scope, pathStepAsIntent(step), List.of(digest),
                                        Optional.empty(), false))));
                return;
            }
            selectedScope().ifPresent(scope ->
                    host.setVerdict(scope, intent, digestsOf(intent), Optional.empty(), false));
        }

        @Override
        public void confirmStillGood(ReviewIntent intent) {
            if (pathMode) {
                currentPathStep().ifPresent(step -> selectedScope().ifPresent(scope ->
                        loadedDiff().flatMap(diff -> digestOfPathStep(diff, step)).ifPresent(digest -> {
                            host.confirmStillGood(scope, List.of(digest));
                            refreshReviewState();
                        })));
                return;
            }
            selectedScope().ifPresent(scope -> {
                host.confirmStillGood(scope, digestsOf(intent));
                refreshReviewState();
            });
        }

        @Override
        public void nextUnsettled() {
            // Not nextUnsettledIntent() directly: this overrides an
            // interface method of the SAME name, so an unqualified call
            // here would recurse into itself rather than reaching the
            // outer class's dispatcher.
            SessionReviewView.this.nextUnsettled();
        }

        @Override
        public void submit() {
            submitReview();
        }

        @Override
        public void previousIntent() {
            moveSelection(-1);
        }

        @Override
        public void nextIntent() {
            moveSelection(1);
        }
    }

    /**
     * Submit (spec §4.6): with anything unsettled this jumps to the first
     * such intent rather than posting a partial review; once everything is
     * settled it posts ONE review.
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
                verdictBar.showSubmitRefused(failed
                        ? "the diff failed to load; nothing to submit"
                        : "the diff is still loading; try again in a moment");
                return;
            }
        }
        List<ReviewIntent> counted = countedSections();
        List<ReviewVerdict.Decision> decisions = new ArrayList<>();
        for (int i = 0; i < counted.size(); i++) {
            Optional<ReviewVerdict.Decision> decision = decisionOf(counted.get(i));
            if (decision.isEmpty()) {
                intentIndex = intents().indexOf(counted.get(i));
                refreshReviewState();
                revealCurrentIntent();
                verdictBar.showSubmitRefused(
                        "an intent still needs a verdict (approve or request changes); jumped to it");
                return;
            }
            // A stale verdict does not count toward "everything settled"
            // (spec §9.2): it was given against a base that has since moved,
            // so posting it is a decision the reader has not actually made
            // about the code as it stands now.
            if (sectionState(counted.get(i)).staleness() == SectionStates.Staleness.MOVED) {
                intentIndex = intents().indexOf(counted.get(i));
                refreshReviewState();
                revealCurrentIntent();
                verdictBar.showSubmitRefused("approvals were given against an older base");
                return;
            }
            decisions.add(decision.get());
        }
        host.submit(scope.get(), buildDiffIndex(diffColumn.displayedDiff()), decisions);
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
     * The rail and the margin auto-collapse as the window narrows so the code
     * column is never crushed (spec §4.9). A manual collapse is remembered
     * separately: a user who collapsed the intents keeps them collapsed when
     * the window grows back, and one who did not gets them back.
     *
     * <p>Three columns rather than the destination's four, which is what
     * removes the two-page drill-in the destination needed below 980px: with
     * the queue rail gone {@link RailLayout} has enough to trade at every
     * width the window can actually be.</p>
     */
    private void applyResponsiveLayout(double width) {
        if (noScope) {
            applyEmptySurface();
            return;
        }
        showEveryRegion();
        intentRail.setVisible(true);
        intentRail.setManaged(true);
        RailLayout.Layout layout =
                RailLayout.solve(width, intentsCollapsedByUser, marginCollapsedByUser);
        intentRail.setNarrow(layout.narrow());
        intentRail.setCollapsed(layout.intentsCollapsed());
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
        show(intentRail, false);
        show(margin, false);
        show(verdictBar, false);
        show(itemHeader, false);
        mcpPanel.ifPresent(panel -> show(panel, false));
    }

    /** Undoes {@link #applyEmptySurface}; the responsive rules take it from here. */
    private void showEveryRegion() {
        show(margin, true);
        show(verdictBar, true);
        show(itemHeader, true);
        if (getLeft() == null) {
            setLeft(intentRail);
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

    /** {@code i}: collapses or expands the intent rail. */
    private void setIntentsCollapsed(boolean collapsed) {
        intentsCollapsedByUser = collapsed;
        applyResponsiveLayout(getWidth());
    }

    /**
     * {@code f}: collapses every rail so code and findings own the window --
     * the "review mode" behaviour without a separate mode. A toggle, not a
     * one-way collapse, or the second press would be a dead key.
     */
    private void setFocusMode(boolean on) {
        intentsCollapsedByUser = on;
        marginCollapsedByUser = on;
        applyResponsiveLayout(getWidth());
    }

    /**
     * {@code a} / {@code r}: records a verdict over {@link #settleUnit()}'s
     * digests -- one hunk, one file, or the whole section -- and, once it
     * actually took (the host still refuses APPROVED over a blocking
     * finding -- see {@code MainWorkspace}'s {@code Host#setVerdict} -- so
     * recording is not guaranteed), remembers those exact digests as what
     * {@code u} should undo (see {@link #undoVerdict}). Whether the WHOLE
     * section is now settled is asked separately -- a single hunk of a
     * multi-hunk section applying must still let {@code u} undo it, even
     * though the section itself has not merged to a decision yet -- and only
     * that separate question decides whether to advance to the next
     * unsettled intent, via the same walk {@code n} uses.
     *
     * @param wholeFile {@code ⇧A}/{@code ⇧R}: every hunk of the current file,
     *                  regardless of what has focus
     */
    private void verdictAction(ReviewVerdict.Decision decision, boolean wholeFile) {
        if (pathMode) {
            // PATH mode must settle what it shows, never whatever the
            // intents-mode cursor happens to be sitting on -- the CRITICAL
            // fix this branch carries. digestsForAction/SectionStates are
            // deliberately not reached here: they derive digests from an
            // INTENT, and the whole point is that a PATH row is not one.
            pathVerdictAction(decision, wholeFile);
            return;
        }
        Optional<ReviewScope> scope = selectedScope();
        Optional<ReviewIntent> intent = currentIntent();
        if (scope.isEmpty() || intent.isEmpty()) {
            return;
        }
        List<String> digests = digestsForAction(intent.get(), settleUnit(), wholeFile);
        if (digests.isEmpty()) {
            return;
        }
        host.setVerdict(scope.get(), intent.get(), digests, Optional.of(decision),
                blockingFindingOpen(scope.get(), intent.get()));
        boolean applied = digests.stream().allMatch(digest -> host.verdict(scope.get(), digest)
                .filter(v -> v.decision() == decision).isPresent());
        if (!applied) {
            return;
        }
        lastSettledWasPath = false;
        lastSettledIntentId = Optional.of(intent.get().id());
        lastSettledDigests = digests;
        if (decisionOf(intent.get()).filter(decision::equals).isPresent()) {
            nextUnsettledIntent();
        }
    }

    /**
     * PATH mode's {@code a}/{@code r} (and the verdict bar's own buttons,
     * routed here the same way -- see {@link VerdictHost}): settles exactly
     * the selected row's one hunk, or every hunk of its file for {@code
     * wholeFile} ({@code ⇧A}/{@code ⇧R}). {@code host.setVerdict} takes an
     * intent purely as a label/blocking-check key (verdicts themselves are
     * keyed {@code (scopeId, hunkDigest)}, never by intent), so a throwaway
     * single-hunk {@link ReviewIntent} is exactly as valid a key as a real
     * one -- see {@link #pathStepAsIntent}.
     */
    private void pathVerdictAction(ReviewVerdict.Decision decision, boolean wholeFile) {
        Optional<ReviewScope> scope = selectedScope();
        Optional<UnifiedDiff> diff = loadedDiff();
        List<ReadingPath.Step> steps = currentPath().steps();
        if (scope.isEmpty() || diff.isEmpty() || steps.isEmpty()) {
            return;
        }
        ReadingPath.Step step = steps.get(Math.clamp(pathIndex, 0, steps.size() - 1));
        ReviewIntent synthetic = pathStepAsIntent(step);
        List<String> digests = wholeFile
                ? digestsOfFileInDiff(diff.get(), step.file())
                : digestOfPathStep(diff.get(), step).map(List::of).orElse(List.of());
        if (digests.isEmpty()) {
            return;
        }
        host.setVerdict(scope.get(), synthetic, digests, Optional.of(decision),
                blockingFindingOpenForPathStep(scope.get(), step, wholeFile));
        boolean applied = digests.stream().allMatch(digest -> host.verdict(scope.get(), digest)
                .filter(v -> v.decision() == decision).isPresent());
        if (!applied) {
            return;
        }
        lastSettledWasPath = true;
        lastSettledPathHunkId = Optional.of(step.hunkId());
        lastSettledDigests = digests;
        // Every digest just written now reads as `decision` (that is what
        // `applied` just confirmed), so this row is as settled as it is
        // ever going to be from this one keypress -- advance the same way
        // verdictAction's intents-mode branch does.
        nextUnsettledPathStep();
    }

    /**
     * Whether a still-open finding blocks approving PATH mode's current
     * settle target -- {@code step}'s own hunk, or, for {@code wholeFile},
     * every hunk of its file (spec §4.6).
     *
     * <p>PATH mode has no real intent of its own to hand {@link
     * #blockingFindingOpen}: {@link #pathStepAsIntent}'s synthetic {@code
     * "path:" + hunkId} can never equal a finding's named {@code intentId},
     * so asking about it directly answered "not blocked" for every
     * agent-attributed finding -- the common case, and the whole reason
     * {@code review_finding} carries an id at all. This asks the SAME
     * question {@link #belongsToIntent} already answers for INTENTS mode,
     * but resolved through whichever REAL section(s) actually cover the
     * hunk(s) about to be settled, so a finding naming one of them still
     * refuses exactly as it would from that section's own card.</p>
     */
    private boolean blockingFindingOpenForPathStep(ReviewScope scope, ReadingPath.Step step,
                                                   boolean wholeFile) {
        Optional<ReviewIntent.Anchor> anchor = pathStepAsIntent(step).anchor();
        if (anchor.isEmpty()) {
            return false;
        }
        String file = anchor.get().file();
        List<ReviewIntent> covering = wholeFile
                ? intents().stream().filter(intent -> intent.touches(file)).toList()
                : intentsCoveringPathStep(step);
        if (!covering.isEmpty()) {
            return covering.stream().anyMatch(intent -> blockingFindingOpen(scope, intent));
        }
        // No real intent claims this hunk/file at all (a grouping that has
        // drifted, or an empty rail) -- fall back to whether any finding on
        // the file blocks, the same fallback belongsToIntent itself uses for
        // a finding naming nothing resolvable.
        return host.findings(scope).stream()
                .filter(finding -> finding.file().equals(file))
                .anyMatch(ReviewAnnotation::blocksApproval);
    }

    /** Every hunk digest of {@code file}, across the whole {@code diff} -- what {@code ⇧A}/{@code ⇧R} settle in PATH mode. */
    private static List<String> digestsOfFileInDiff(UnifiedDiff diff, String file) {
        for (UnifiedDiff.FileDiff candidate : diff.files()) {
            if (candidate.path().equals(file)) {
                List<String> digests = new ArrayList<>();
                for (UnifiedDiff.Hunk hunk : candidate.hunks()) {
                    digests.add(HunkDigest.of(file, hunk));
                }
                return digests;
            }
        }
        return List.of();
    }

    /**
     * {@code u}: undoes exactly the digests {@code a}/{@code r} last
     * recorded -- NOT the whole intent the cursor currently sits on, and NOT
     * whatever {@link #settleUnit()} says right now. A human who presses
     * {@code r}, realises they misread the diff, and presses {@code u}
     * expects the verdict they just placed to disappear; since {@code r}
     * itself advances the cursor (see {@link #verdictAction}), undoing
     * "the current intent" would clear whatever {@code r} advanced TO
     * instead -- the wrong one, and silently, since nothing before this
     * looked wrong on screen. Snaps the cursor back to the undone intent
     * too, so the human sees what changed rather than having to hunt for
     * it. A second {@code u} with nothing left to undo does nothing, rather
     * than reaching for an unrelated intent's verdict.
     */
    private void undoVerdict() {
        if (lastSettledWasPath) {
            undoPathVerdict();
            return;
        }
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty() || lastSettledIntentId.isEmpty() || lastSettledDigests.isEmpty()) {
            return;
        }
        List<ReviewIntent> current = intents();
        int index = -1;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).id().equals(lastSettledIntentId.get())) {
                index = i;
                break;
            }
        }
        List<String> digests = lastSettledDigests;
        lastSettledIntentId = Optional.empty();
        lastSettledDigests = List.of();
        if (index < 0) {
            // The grouping changed under us (a reviewer re-ran, say) and the
            // intent this would have undone no longer exists -- nothing
            // sane to undo or jump to.
            return;
        }
        // An undo is never refused (see the VerdictHost#undo javadoc); false
        // is inert here, not a claim that nothing is blocking.
        host.setVerdict(scope.get(), current.get(index), digests, Optional.empty(), false);
        intentIndex = index;
        refreshReviewState();
        revealCurrentIntent();
    }

    /**
     * PATH mode's {@code u}: the counterpart to {@link #undoVerdict}'s
     * intents-mode body, keyed by {@link #lastSettledPathHunkId} rather than
     * an intent id -- a step has no id of its own, only its (stable) hunk
     * id.
     */
    private void undoPathVerdict() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty() || lastSettledPathHunkId.isEmpty() || lastSettledDigests.isEmpty()) {
            return;
        }
        List<ReadingPath.Step> steps = currentPath().steps();
        int index = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).hunkId().equals(lastSettledPathHunkId.get())) {
                index = i;
                break;
            }
        }
        List<String> digests = lastSettledDigests;
        ReadingPath.Step target = index >= 0 ? steps.get(index) : null;
        lastSettledPathHunkId = Optional.empty();
        lastSettledDigests = List.of();
        lastSettledWasPath = false;
        if (index < 0) {
            // The path changed under us (a re-diff landed a new graph) and
            // the step this would have undone no longer exists.
            return;
        }
        host.setVerdict(scope.get(), pathStepAsIntent(target), digests, Optional.empty(), false);
        pathIndex = index;
        refreshReviewState();
        revealCurrentPathStep();
    }

    /**
     * Asks the selected scope's agent for a review. Refuses the same case
     * {@code host.runReview} refuses -- a scope with no session has no agent
     * to ask -- but says so on the button beforehand rather than by doing
     * nothing when clicked; see {@link #updateRunReviewButton}.
     */
    private void runReviewOnSelection() {
        selectedScope().ifPresent(scope -> {
            if (host.runReview(scope)) {
                runReviewButton.setText("▶  Review running…");
                // Only the label, and only until the next selection or state
                // refresh: what the agent then does shows up as intents and
                // findings, which are the real progress indication.
            }
        });
    }

    /** Enables "Run review" only where there is an agent to run it, and says why not. */
    private void updateRunReviewButton() {
        boolean runnable = selectedScope().flatMap(ReviewScope::sessionId).isPresent();
        runReviewButton.setText("▶  Run review");
        runReviewButton.setDisable(!runnable);
        runReviewButton.setTooltip(new Tooltip(runnable
                ? "Ask this session's agent to group the changes into intents and post findings"
                : "Needs a session — start one for this checkout first"));
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
        boolean handled = switch (event.getCode()) {
            case D -> { cycleDensity(); yield true; }
            case C -> { diffColumn.toggleContext(); yield true; }
            case M -> { setMarginCollapsed(!margin.collapsed()); yield true; }
            case I -> { setIntentsCollapsed(!intentRail.collapsed()); yield true; }
            case BACK_SLASH -> { toggleMcpPanel(); yield true; }
            case P -> { togglePathMode(); yield true; }
            // [ and ] step whatever the rail is currently listing (spec
            // §7.1): sections in INTENTS mode, hunks in PATH mode -- one key
            // rather than a parallel set for the second mode.
            case OPEN_BRACKET -> { moveSelection(-1); yield true; }
            case CLOSE_BRACKET -> { moveSelection(1); yield true; }
            // n keeps meaning "next unsettled", a property of hunks
            // regardless of which grouping the rail is showing.
            case N -> { nextUnsettled(); yield true; }
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
                    setFocusMode(!(intentsCollapsedByUser && marginCollapsedByUser));
                }
                yield true;
            }
            default -> false;
        };
        return handled;
    }

    /**
     * Escape's unwind, topmost-first (spec §5): the symbol-lens popover, the
     * gutter composer, then the MCP activity panel. Returns whether something
     * was closed, so the scene filter knows whether to keep unwinding.
     */
    public boolean unwindOne() {
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
     * <p>The intent rail's collapse/expand {@code Timeline} is finite (it
     * self-stops at the end of its keyframe), not {@code INDEFINITE}, so it
     * is not the leak the panel is -- but stopping it too
     * means a view closed mid-animation never runs a timeline against a
     * detached node.</p>
     *
     * <p>{@link #focusOwnerListener} is the same shape of leak as the MCP
     * panel: it is added to the app-lifetime Scene's {@code
     * focusOwnerProperty}, so an un-removed one keeps this view reachable
     * for the process's life AND re-renders its verdict bar on every focus
     * change anywhere in the app, for every session's board ever closed.
     * The {@link #sceneProperty()} listener already removes it on a genuine
     * re-parent, but this Scene is never actually swapped in practice (one
     * Scene for the whole app -- see {@code AppShell}), so this explicit
     * removal is the one that actually runs.</p>
     *
     * <p>Call before dropping the last reference to this view -- see {@code
     * OpenSessionTab.disposeNativeResources}.</p>
     */
    public void close() {
        closed = true;
        mcpPanel.ifPresent(ReviewMcpActivityPanel::detach);
        intentRail.stopWidthAnimation();
        if (getScene() != null) {
            getScene().focusOwnerProperty().removeListener(focusOwnerListener);
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

    /**
     * Diagnostic-only: the derived state of the {@code index}-th section.
     * Routed through {@link ReviewDiagFxThread} like every other {@code diag*}
     * accessor -- it reads the store and the rail's own grouping, both of
     * which the FX thread mutates.
     */
    SectionStates.SectionState diagSectionState(int index) {
        return ReviewDiagFxThread.call(() -> {
            List<ReviewIntent> current = intents();
            return index >= 0 && index < current.size()
                    ? sectionState(current.get(index))
                    : SectionStates.SectionState.unknown();
        });
    }

    /** Diagnostic-only: the current rail's intent ids, in rendered order. */
    List<String> diagIntentIds() {
        return ReviewDiagFxThread.call(() -> intents().stream().map(ReviewIntent::id).toList());
    }

    /**
     * Diagnostic-only: {@link #intents()}'s own return value, unmapped --
     * so a test can compare it BY REFERENCE across two calls to tell a
     * cache hit (the same {@link List} instance) from a recomputation (a
     * new, if equal-content, one). {@link #diagIntentIds} maps to a fresh
     * {@code List<String>} on every call regardless, so it cannot make that
     * distinction.
     */
    List<ReviewIntent> diagIntents() {
        return ReviewDiagFxThread.call(this::intents);
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
}
