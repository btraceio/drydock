package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpActivityLog;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.Severity;
import app.drydock.review.SubmitPlan;

import javafx.application.Platform;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The review board of one session's Review sub-tab (spec §3.2): the intent
 * rail, the diff column, the findings margin and the verdict bar, showing
 * exactly ONE scope -- this checkout's local changes, or the pull request its
 * branch carries -- chosen by the two chips of a {@link ReviewScopeSwitcher}.
 *
 * <p>The board itself is the one {@code ReviewDestinationView} rendered; what
 * is gone is everything the cross-repo queue needed around it. There is no
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
         * grouping when one was supplied, otherwise one intent per changed
         * file of the diff handed in.
         *
         * <p>The diff is a parameter rather than something the host fetches,
         * because the only correct diff here is the one the caller has
         * already established belongs to {@code scope}. A host that looked it
         * up would be free to look up the wrong one, which is exactly the
         * defect this shape removes.</p>
         */
        List<ReviewIntent> intents(ReviewScope scope, UnifiedDiff diff);

        /** The verdict recorded on one intent, if any. */
        Optional<ReviewVerdict> verdict(ReviewScope scope, ReviewIntent intent);

        /** Records a verdict; {@code decision} empty undoes it. */
        void setVerdict(ReviewScope scope, ReviewIntent intent,
                        Optional<ReviewVerdict.Decision> decision);

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

        /** Hands an intent's open findings to the scope's bound session. */
        void askAgentToFix(ReviewScope scope, ReviewIntent intent, List<ReviewAnnotation> findings);

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

    private final Host host;
    private final ReviewScopeSwitcher switcher = new ReviewScopeSwitcher();
    private final ReviewDiffColumn diffColumn;
    private final ReviewIntentRail intentRail = new ReviewIntentRail();
    private final ReviewFindingsMargin margin;
    private final ReviewVerdictBar verdictBar;

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
     * The id of the intent {@code a}/{@code r} last recorded a verdict on,
     * so {@code u} can undo THAT one -- see {@link #undoVerdict}. Cleared
     * once undone, so a second {@code u} with nothing left to undo is inert
     * rather than reaching for an unrelated intent. Not touched by {@code
     * [}/{@code ]}/{@code n}: moving the cursor around must not change what
     * {@code u} targets, or "settle one, look at another, undo" would undo
     * the wrong one.
     */
    private Optional<String> lastSettledIntentId = Optional.empty();

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
        this.diffColumn = new ReviewDiffColumn(diffService, host::openInExplorer);
        this.margin = new ReviewFindingsMargin(new MarginHost());
        this.verdictBar = new ReviewVerdictBar(new VerdictHost());
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
        intentRail.setVerdictLookup(intent ->
                selectedScope().flatMap(scope -> host.verdict(scope, intent)));
        intentRail.setOnSelected(intent -> {
            List<ReviewIntent> current = intents();
            int index = current.indexOf(intent);
            if (index >= 0) {
                intentIndex = index;
                refreshReviewState();
                revealCurrentIntent();
            }
        });
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
            // Only the selected scope's arrival changes what is on screen;
            // a superseded one still records its outcome, so coming back to
            // it does not re-run git.
            if (selectedScope().map(scope -> scope.id().equals(scopeId)).orElse(false)) {
                refreshReviewState();
                revealCurrentIntent();
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
        // Fallback intent ids are NOT scope-namespaced ("auto:change:src" is
        // just (kind, directory)), so two different scopes with a similar
        // layout can mint the identical id -- leaving this set across a
        // scope switch could make u undo, and jump into, a same-named
        // intent in the WRONG scope.
        lastSettledIntentId = Optional.empty();
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
        revealCurrentIntent();
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
        Optional<ReviewScope> scope = selectedScope();
        updateRunReviewButton();
        updateCountsLabel();
        if (scope.isEmpty()) {
            margin.setFindings(List.of());
            verdictBar.update(null, Optional.empty(), false, 0, 0);
            // No scope selected means no rail: leaving the previous scope's
            // cards up here is how the rail came to list a departed item's
            // files (see the whole-branch review this fixes).
            intentRail.setIntents(List.of(), null, ReviewIntentRail.Empty.NONE);
            mcpPanel.ifPresent(panel -> panel.setScope(null));
            return;
        }
        margin.invalidate(null);
        margin.setFindings(findingsForMargin(scope.get()));
        diffColumn.refreshPins();
        intentRail.setIntents(intents(), currentIntent().map(ReviewIntent::id).orElse(null),
                emptyReason());
        mcpPanel.filter(Node::isVisible)
                .ifPresent(panel -> panel.setScope(scope.get()));
        renderVerdictBar(scope.get());
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

    /**
     * Whether a finding belongs under the intent now selected.
     *
     * <p>Matched by id when the finding names an intent the current grouping
     * actually contains, and by file otherwise. That second path is the
     * important one: a finding can name an intent that no longer exists --
     * a reviewer re-grouped, or the finding was stored under an older
     * grouping and read back. Matching on the id alone made such a finding
     * belong to no intent at all, so it silently disappeared from every
     * margin instead of being shown somewhere. A finding is a thing a human
     * or an agent went to the trouble of writing down; it must not be
     * possible for the UI to lose one by regrouping around it.</p>
     */
    private boolean belongsToCurrentIntent(ReviewAnnotation finding) {
        ReviewIntent current = currentIntent().orElse(null);
        if (current == null) {
            return true;
        }
        String named = finding.intentId().orElse(null);
        if (named != null && intents().stream().anyMatch(intent -> intent.id().equals(named))) {
            return named.equals(current.id());
        }
        // Unnamed, or naming an intent this grouping does not have: fall back
        // to where the finding actually is.
        return current.touches(finding.file());
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
        if (selectedOutcome().orElse(null) instanceof DiffOutcome.Loaded loaded) {
            return host.intents(scope.get(), loaded.diff());
        }
        return List.of();
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

    private void renderVerdictBar(ReviewScope scope) {
        List<ReviewIntent> intents = intents();
        Optional<ReviewIntent> current = currentIntent();
        if (current.isEmpty()) {
            verdictBar.update(null, Optional.empty(), false, 0, 0);
            return;
        }
        // Collapsed intents do not count toward progress: the point of the
        // collapse is that there is nothing to read.
        List<ReviewIntent> counted = intents.stream()
                .filter(ReviewIntent::countsTowardProgress)
                .toList();
        long settled = counted.stream()
                .filter(intent -> host.verdict(scope, intent).isPresent())
                .count();
        boolean blocked = host.findings(scope).stream()
                .filter(this::belongsToCurrentIntent)
                .anyMatch(ReviewAnnotation::blocksApproval);
        verdictBar.update(current.get(), host.verdict(scope, current.get()), blocked,
                (int) settled, counted.size());
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
        for (int offset = 1; offset <= intents.size(); offset++) {
            int candidate = (intentIndex + offset) % intents.size();
            ReviewIntent intent = intents.get(candidate);
            if (intent.countsTowardProgress() && host.verdict(scope.get(), intent).isEmpty()) {
                intentIndex = candidate;
                refreshReviewState();
                revealCurrentIntent();
                return;
            }
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
        public void approve(ReviewIntent intent) {
            selectedScope().ifPresent(scope ->
                    host.setVerdict(scope, intent, Optional.of(ReviewVerdict.Decision.APPROVED)));
        }

        @Override
        public void requestChanges(ReviewIntent intent) {
            selectedScope().ifPresent(scope ->
                    host.setVerdict(scope, intent, Optional.of(ReviewVerdict.Decision.CHANGES)));
        }

        @Override
        public void askAgentToFix(ReviewIntent intent) {
            selectedScope().ifPresent(scope -> host.askAgentToFix(scope, intent,
                    host.findings(scope).stream()
                            .filter(finding -> !finding.resolved())
                            .filter(SessionReviewView.this::belongsToCurrentIntent)
                            .toList()));
        }

        @Override
        public void undo(ReviewIntent intent) {
            selectedScope().ifPresent(scope -> host.setVerdict(scope, intent, Optional.empty()));
        }

        @Override
        public void nextUnsettled() {
            nextUnsettledIntent();
        }

        @Override
        public void submit() {
            submitReview();
        }

        @Override
        public void previousIntent() {
            moveIntent(-1);
        }

        @Override
        public void nextIntent() {
            moveIntent(1);
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
        List<ReviewIntent> counted = intents().stream()
                .filter(ReviewIntent::countsTowardProgress)
                .toList();
        List<ReviewVerdict.Decision> decisions = new ArrayList<>();
        for (int i = 0; i < counted.size(); i++) {
            Optional<ReviewVerdict> verdict = host.verdict(scope.get(), counted.get(i));
            if (verdict.isEmpty()) {
                intentIndex = intents().indexOf(counted.get(i));
                refreshReviewState();
                revealCurrentIntent();
                verdictBar.showSubmitRefused(
                        "an intent still needs a verdict (approve or request changes); jumped to it");
                return;
            }
            decisions.add(verdict.get().decision());
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
     * width the window can actually be. It is still asked for a four-column
     * answer with the queue forced collapsed, and handed the collapsed
     * queue's width back on top, so its arithmetic sees exactly the three
     * rails this view has -- the alternative was a second copy of the solver
     * that could drift from the one the destination still uses.</p>
     */
    private void applyResponsiveLayout(double width) {
        if (noScope) {
            applyEmptySurface();
            return;
        }
        showEveryRegion();
        intentRail.setVisible(true);
        intentRail.setManaged(true);
        RailLayout.Layout layout = RailLayout.solve(width + ReviewQueueRail.COLLAPSED_WIDTH,
                true, intentsCollapsedByUser, marginCollapsedByUser);
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
     * {@code a} / {@code r}: records a verdict and, once it actually took
     * (the host still refuses APPROVED over a blocking finding -- see
     * {@code MainWorkspace}'s {@code Host#setVerdict} -- so recording is not
     * guaranteed), advances to the next unsettled intent via the same walk
     * {@code n} uses. Also remembers this intent as the one {@code u} should
     * undo (see {@link #undoVerdict}) -- recorded here, after the advance
     * decision above, so it always names the intent a verdict was just
     * placed ON, never wherever the cursor lands next.
     */
    private void verdictAction(ReviewVerdict.Decision decision) {
        Optional<ReviewScope> scope = selectedScope();
        Optional<ReviewIntent> intent = currentIntent();
        if (scope.isPresent() && intent.isPresent()) {
            host.setVerdict(scope.get(), intent.get(), Optional.of(decision));
            if (host.verdict(scope.get(), intent.get()).map(ReviewVerdict::decision)
                    .filter(decision::equals).isPresent()) {
                lastSettledIntentId = Optional.of(intent.get().id());
                nextUnsettledIntent();
            }
        }
    }

    /**
     * {@code u}: undoes the verdict {@code a}/{@code r} last recorded --
     * NOT whatever intent the cursor currently sits on. A human who presses
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
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty() || lastSettledIntentId.isEmpty()) {
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
        lastSettledIntentId = Optional.empty();
        if (index < 0) {
            // The grouping changed under us (a reviewer re-ran, say) and the
            // intent this would have undone no longer exists -- nothing
            // sane to undo or jump to.
            return;
        }
        host.setVerdict(scope.get(), current.get(index), Optional.empty());
        intentIndex = index;
        refreshReviewState();
        revealCurrentIntent();
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
            case OPEN_BRACKET -> { moveIntent(-1); yield true; }
            case CLOSE_BRACKET -> { moveIntent(1); yield true; }
            case N -> { nextUnsettledIntent(); yield true; }
            case A -> { verdictAction(ReviewVerdict.Decision.APPROVED); yield true; }
            case R -> { verdictAction(ReviewVerdict.Decision.CHANGES); yield true; }
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
     * <p>Call before dropping the last reference to this view -- see {@code
     * OpenSessionTab.disposeNativeResources}.</p>
     */
    public void close() {
        mcpPanel.ifPresent(ReviewMcpActivityPanel::detach);
        intentRail.stopWidthAnimation();
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
