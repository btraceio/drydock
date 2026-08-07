package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.ReviewBase;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.SubmitPlan;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Review destination (Review handoff §1): one surface for local
 * changes, agent-authored worktrees and remote PRs, reached from the
 * sidebar or with {@code ⌘4} from any session.
 *
 * <p>Not a session sub-tab. Review is a scene-graph view, so the workspace
 * hides every native terminal while it is showing, exactly as the Explorer
 * swap does; this class owns none of that -- it is handed a {@link Host}
 * and stays a pure view.</p>
 *
 * <p>M1 renders the title bar, the queue rail and the item header. The
 * centre body comes from {@link Host#bodyFor}, which is where the diff
 * column lands.</p>
 */
public final class ReviewDestinationView extends BorderPane {

    /**
     * Below this width the three-column layout is replaced by two alternating
     * pages (spec §4.9). {@link RailLayout} only ever shrinks a rail; below
     * 980px shrinking them further produced 44/40px slivers that could not be
     * expanded, so the whole tab became unusable. Two full-width pages is the
     * answer, not one more threshold.
     */
    private static final double DRILL_IN_WIDTH = 980;

    /** The share of the Browse page each rail takes; the rest is the intent rail. */
    private static final double BROWSE_QUEUE_FRACTION = 0.44;

    /** Which of the two narrow pages is showing. Meaningless at or above {@link #DRILL_IN_WIDTH}. */
    private enum NarrowPage {
        /** Queue and intents side by side, full width. */
        BROWSE,
        /** The diff column and findings margin own the window. */
        DETAIL
    }

    /** What the view needs from the workspace. All calls happen on the FX thread. */
    public interface Host {
        /** Reassembles the queue (called when Review is shown). */
        void refreshQueue();

        /** The empty surface's Retry button -- asks for the scan to run again. */
        void retryQueueScan();

        /** {@code o} -- brings the scope's bound session's tab to the front. */
        void openSession(ManagedSessionId sessionId);

        /**
         * The centre body for {@code scope}, or empty for the built-in
         * placeholder. This is the seam the diff column arrives through.
         */
        Optional<Region> bodyFor(ReviewScope scope);

        /** Open findings for {@code scope}; empty when no reviewer has run (spec §4.1). */
        Optional<Integer> openFindings(ReviewScope scope);

        /** {@code running} / {@code idle} for the queue's session dot; empty for no session. */
        Optional<String> sessionState(ReviewScope scope);

        /** The {@code ?} button -- shows the shared shortcuts overlay. */
        void showShortcuts();

        /**
         * {@code ⤢} -- opens {@code file} at a 1-based line in the Explorer of
         * the session bound to {@code scope}. False when there is nowhere to
         * open it (no session, or its tab is closed).
         */
        boolean openInExplorer(ReviewScope scope, java.nio.file.Path file, int line);

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
        List<ReviewIntent> intents(ReviewScope scope, app.drydock.git.UnifiedDiff diff);

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

        /** Diagnostic-only: runs {@code scope}'s diff and describes the result. */
        String diagDiffSummary(ReviewScope scope);

        /**
         * The reviewers configured for this workspace, and which one Review
         * would run. Empty when none is configured -- Review then works as a
         * plain diff, which it must always be able to do.
         */
        List<String> reviewers();

        /** The reviewer currently selected, if any. */
        Optional<String> selectedReviewer();

        /** Chooses which reviewer "Run review" would use. */
        void selectReviewer(String reviewer);

        /**
         * Runs the selected reviewer against {@code scope}: grants it the
         * scope handle and asks it to review. False when it cannot run (no
         * reviewer, or no session to run it in).
         */
        boolean runReview(ReviewScope scope);

        /**
         * The checkout gate's primary action: worktree, {@code gh pr
         * checkout}, session, scope grant. Reports back through
         * {@code onCheckoutFailed} rather than throwing -- it is a long
         * network operation the gate is already showing progress for.
         */
        void startSessionAndReview(ReviewScope scope, Consumer<String> onCheckoutFailed);

        /**
         * The gate's second action: the PR's patch with no worktree. Empty
         * when it cannot be read (no {@code gh}, or no access).
         */
        void readPatchOnly(ReviewScope scope,
                           Consumer<Optional<app.drydock.git.UnifiedDiff>> onComplete);

        /**
         * The agent-launch command the gate previews for {@code scope} -- the
         * repository's own agent, so the preview cannot claim an agent the
         * launch will not run. Answers on the FX thread.
         */
        void launchCommandPreview(ReviewScope scope, Consumer<String> onReady);
    }

    private final Host host;
    private final ReviewQueueRail queue = new ReviewQueueRail();
    private final ReviewDiffColumn diffColumn;
    private final ReviewIntentRail intentRail = new ReviewIntentRail();
    private final ReviewFindingsMargin margin;
    private final ReviewVerdictBar verdictBar;
    private final ReviewCheckoutGate checkoutGate;

    /** The MCP activity panel; absent when no server is running (tests, headless). */
    private final Optional<ReviewMcpActivityPanel> mcpPanel;

    /** Scopes the human chose to read without a checkout ({@code Read the patch only}). */
    private final java.util.Map<String, app.drydock.git.UnifiedDiff> patchOnlyDiffs = new java.util.HashMap<>();

    /**
     * What each scope's diff attempt produced, keyed by scope id. A scope
     * absent from this map has no diff -- which is a state, not a reason to
     * reach for someone else's.
     */
    private final java.util.Map<String, DiffOutcome> outcomeByScope = new java.util.HashMap<>();

    /** The intent the verdict bar is settling; {@code [} / {@code ]} / {@code n} move it. */
    private int intentIndex;

    /** Set by {@code m}/{@code f}; remembered independently of the responsive collapse. */
    private boolean marginCollapsedByUser;

    /**
     * The findings margin's state on the narrow Detail page, remembered
     * independently of {@link #marginCollapsedByUser} the same way every
     * other rail's manual state is. Starts collapsed: Detail exists to give
     * the code the window.
     */
    private boolean narrowMarginCollapsed = true;

    /** Set by {@code i}/{@code f}; remembered independently of the responsive collapse. */
    private boolean intentsCollapsedByUser;

    private final Label countsLabel = new Label();

    /** The item header (icon, title, context, session row); hidden when there is no item. */
    private VBox itemHeader;

    /**
     * True while the queue has no items at all.
     *
     * <p>Everything except the title bar and the centred state is hidden in
     * that case. It used to all stay on screen: an empty queue rail with a
     * live filter field, an empty intent rail reading {@code 0/0}, a findings
     * margin claiming "Nothing flagged in this intent" when there was no
     * intent, and a verdict bar with dead arrows, a {@code 0/0} progress bar
     * and a disabled Submit -- five regions describing an item that did not
     * exist, framing one sentence that did. A surface with nothing in it
     * should be one thing, not the full chrome with the content removed.</p>
     */
    private boolean queueEmpty;

    /**
     * The repositories the last scan covered, so an empty surface can say what
     * it looked at. Held rather than passed through {@link #showEmpty}: the
     * scanning state is raised before there is any assembly to carry them.
     */
    private List<String> repositoryNames = List.of();

    /**
     * The {@code ‹} affordance naming the tab Review was entered from
     * (nav §3). Review is a pinned tab beside the sessions, so this is a
     * convenience, not the only way back -- clicking the session's own tab
     * works too. Hidden when nothing set an origin.
     */
    private final Button backButton = new Button();
    private Runnable onBack = () -> { };

    /** The narrow Detail page's {@code ‹ queue} chip (spec §4.9). */
    private final Button queueBackChip = new Button("‹ queue");

    private final Label headerIcon = new Label();
    private final Label headerTitle = new Label();
    private final Label headerContext = new Label();
    private final Region sessionDot = new Region();
    private final Label sessionLine = new Label();
    private final Button openSessionButton = new Button("Open session");
    private final Label returnHint = new Label("⌘4 from that session returns here");
    private final Button densityButton = new Button();
    private final Button reviewerButton = new Button();
    private final ContextMenu reviewerMenu = new ContextMenu();
    private final VBox body = new VBox();

    private ReviewDensity density = ReviewDensity.COZY;

    /** Set by {@code q}/{@code f}; remembered independently of the responsive collapse (spec §4.9). */
    private boolean queueCollapsedByUser;

    /** The two halves of the layout, swapped between {@code left}/{@code centre} by the drill-in. */
    private final HBox rails = new HBox(queue, intentRail);
    private Region centre;

    /** True while the window is below {@link #DRILL_IN_WIDTH}. */
    private boolean drilledIn;

    /**
     * Which narrow page to show. Remembered across resizes so shrinking the
     * window mid-review lands on the diff being read rather than back at the
     * queue (spec §4.9).
     */
    private NarrowPage narrowPage = NarrowPage.BROWSE;

    public ReviewDestinationView(Host host, app.drydock.git.DiffService diffService) {
        this(host, diffService, null);
    }

    /**
     * @param activityLog the MCP traffic log the {@code \} panel renders, or
     *                    {@code null} when no server is running -- Review must
     *                    work with no agent at all, so the panel is optional
     */
    public ReviewDestinationView(Host host, app.drydock.git.DiffService diffService,
                                 app.drydock.mcp.McpActivityLog activityLog) {
        this.mcpPanel = ReviewMcpActivityPanel.createIfAvailable(activityLog);
        this.host = host;
        this.diffColumn = new ReviewDiffColumn(diffService, host::openInExplorer);
        this.margin = new ReviewFindingsMargin(new MarginHost());
        this.checkoutGate = new ReviewCheckoutGate(new GateHost());
        this.verdictBar = new ReviewVerdictBar(new VerdictHost());
        getStyleClass().add("review-destination");
        // Review must never hold the window open. Its computed minimum is the
        // sum of the rails' and the margin's own minimums (236 + 232 + 336 +
        // the code column), around 900px -- so below that the content pane
        // stopped shrinking and simply overflowed the right edge of the
        // window, taking the intent rail off-screen with it. That is the
        // "unusable at narrow widths" this drill-in exists to fix, and no
        // amount of re-splitting the rails would have fixed it: the view has
        // to be allowed to be as narrow as the slot it is given.
        setMinWidth(0);

        setTop(buildTitleBar());
        // Queue then intents, left to right, exactly as the anatomy lays them
        // out; the centre carries the code, the margin and the verdict bar.
        centre = buildCenter();
        setLeft(rails);
        setCenter(centre);

        queue.setOnSelected(this::showItem);
        queue.setOnToggleCollapse(() -> setQueueCollapsed(!queue.collapsed()));
        queue.setFindingCount(item -> host.openFindings(item.scope()));
        queue.setSessionDot(item -> host.sessionState(item.scope()));
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
                // Picking an intent IS the drill-in gesture (spec §4.9), and
                // recording it wide is what makes a later shrink land on the
                // diff being read rather than back at the queue.
                showNarrowPage(NarrowPage.DETAIL);
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
        // The Browse split follows the rails' own width on every layout pass,
        // not just on the pass that crossed the threshold -- see applyBrowseSpans.
        rails.widthProperty().addListener((obs, old, width) -> applyBrowseSpans());
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        setFocusTraversable(true);
        showItem(null);
    }

    // ---- title bar ----------------------------------------------------------

    private Region buildTitleBar() {
        backButton.getStyleClass().add("review-back-button");
        backButton.setOnAction(e -> onBack.run());
        setBackTarget(Optional.empty(), null);

        Label glyph = new Label("◨");
        glyph.getStyleClass().add("review-title-glyph");
        Label title = new Label("Review");
        title.getStyleClass().add("review-title");
        countsLabel.getStyleClass().add("review-title-counts");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        reviewerButton.getStyleClass().add("review-chip-button");
        reviewerButton.setTooltip(new Tooltip("Reviewer, and re-run the review on this scope"));
        reviewerButton.setOnAction(e -> showReviewerMenu());

        densityButton.getStyleClass().add("review-chip-button");
        densityButton.setTooltip(new Tooltip("Density: cozy · compact · dense (d)"));
        densityButton.setOnAction(e -> cycleDensity());
        applyDensity(density);
        renderReviewerButton();

        Button shortcuts = new Button("?");
        shortcuts.getStyleClass().add("review-chip-button");
        shortcuts.setTooltip(new Tooltip("Shortcuts (?)"));
        shortcuts.setOnAction(e -> host.showShortcuts());

        HBox bar = new HBox(8, backButton, glyph, title, countsLabel, spacer, reviewerButton,
                densityButton, shortcuts);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("review-title-bar");
        return bar;
    }

    /**
     * Names the tab Review was entered from, and what clicking {@code ‹}
     * should do (nav §3). An empty label hides the affordance outright: a
     * back button that goes nowhere is worse than no back button.
     */
    public void setBackTarget(Optional<String> label, Runnable handler) {
        this.onBack = handler == null ? () -> { } : handler;
        boolean present = label.isPresent() && handler != null;
        backButton.setText(label.map(text -> "‹  " + text).orElse("‹"));
        backButton.setTooltip(label.map(text -> new Tooltip("Back to " + text + " (⌘4)")).orElse(null));
        backButton.setVisible(present);
        backButton.setManaged(present);
    }

    // ---- centre -------------------------------------------------------------

    private Region buildCenter() {
        headerIcon.getStyleClass().add("review-item-icon");
        headerTitle.getStyleClass().add("review-item-title");
        headerContext.getStyleClass().add("review-item-context");
        queueBackChip.getStyleClass().add("review-chip-button");
        queueBackChip.setTooltip(new Tooltip("Back to the queue (esc)"));
        queueBackChip.setOnAction(e -> showNarrowPage(NarrowPage.BROWSE));
        queueBackChip.setVisible(false);
        queueBackChip.setManaged(false);

        Region row1Spacer = new Region();
        HBox.setHgrow(row1Spacer, Priority.ALWAYS);
        HBox row1 = new HBox(9, queueBackChip, headerIcon, headerTitle, headerContext, row1Spacer);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.getStyleClass().add("review-item-header-row");

        sessionDot.getStyleClass().add("review-session-dot");
        sessionLine.getStyleClass().add("review-session-line");
        openSessionButton.getStyleClass().add("review-chip-button");
        openSessionButton.setTooltip(new Tooltip("Open the bound session (o)"));
        openSessionButton.setOnAction(e -> openBoundSession());
        returnHint.getStyleClass().add("review-session-hint");
        Region row2Spacer = new Region();
        HBox.setHgrow(row2Spacer, Priority.ALWAYS);
        HBox row2 = new HBox(8, sessionDot, sessionLine, openSessionButton, returnHint, row2Spacer);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.getStyleClass().add("review-item-header-row");

        VBox header = new VBox(row1, row2);
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

    // ---- queue --------------------------------------------------------------

    /**
     * Replaces the queue's contents. The previous selection survives when its
     * scope is still present; otherwise the first item the rail is actually
     * showing is selected. An assembly with no items shows whichever empty
     * state the assembly's own completeness implies.
     */
    public void setItems(QueueAssembly assembly, List<String> repositoryNames) {
        List<ReviewItem> items = assembly.items();
        this.repositoryNames = List.copyOf(repositoryNames);
        int repositoryCount = this.repositoryNames.size();
        String previous = queue.selected().map(item -> item.scope().id()).orElse(null);
        queue.setItems(items);
        outcomeByScope.keySet().retainAll(items.stream().map(item -> item.scope().id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        countsLabel.setText(items.size() + (items.size() == 1 ? " item · " : " items · ")
                + repositoryCount + (repositoryCount == 1 ? " repo" : " repos"));
        if (items.isEmpty()) {
            showEmpty(repositoryCount == 0
                    ? ReviewEmptyState.NO_REPOSITORIES
                    : assembly.complete()
                            ? ReviewEmptyState.NOTHING_REVIEWABLE
                            : ReviewEmptyState.SCAN_INCOMPLETE);
            return;
        }
        queueEmpty = false;
        applyResponsiveLayout(getWidth());
        boolean stillThere = previous != null
                && items.stream().anyMatch(item -> item.scope().id().equals(previous));
        if (stillThere) {
            queue.select(previous);
            return;
        }
        // The fallback must be a row the query still shows -- items.get(0)
        // can be filtered out, which would select something the rail is not
        // even rendering. Only an over-narrow query (nothing visible at all)
        // falls back to items.get(0), so a reassembly still leaves something
        // selected rather than nothing.
        queue.select(queue.firstVisible().map(item -> item.scope().id()).orElse(items.get(0).scope().id()));
    }

    /** Called when Review is shown and a scan is in flight. */
    public void showScanning(List<String> repositoryNames) {
        this.repositoryNames = List.copyOf(repositoryNames);
        showEmpty(ReviewEmptyState.SCANNING);
    }

    /**
     * The empty surface. The session row is not rendered at all here: with no
     * item there is no session to describe, and a row reading "no items in
     * the queue" beside a session dot read as a claim about the session
     * Review was opened from.
     */
    private void showEmpty(ReviewEmptyState state) {
        queueEmpty = true;
        headerIcon.setText("◨");
        headerTitle.setText(state.title());
        headerContext.setText("");
        hideSessionRow();
        Region placeholder = placeholder(state.title(), state.detail(),
                state.scanned(repositoryNames));
        if (state == ReviewEmptyState.SCAN_INCOMPLETE) {
            Button retry = new Button("Retry the scan");
            retry.getStyleClass().addAll("review-chip-button", "review-empty-retry");
            retry.setOnAction(e -> {
                retry.setDisable(true);
                retry.setText("Scanning…");
                host.retryQueueScan();
            });
            ((VBox) placeholder).getChildren().add(retry);
        }
        body.getChildren().setAll(placeholder);
        refreshReviewState();
        applyResponsiveLayout(getWidth());
    }

    /** Hides the whole session row -- dot, line, button and hint. */
    /**
     * Hides the whole session row -- dot, line, button and hint. With no item
     * selected there is no session to describe, and a row reading "no items in
     * the queue" beside a session dot read as a claim about the session Review
     * was opened from.
     */
    private void hideSessionRow() {
        sessionDot.setVisible(false);
        sessionDot.setManaged(false);
        sessionLine.setVisible(false);
        sessionLine.setManaged(false);
        openSessionButton.setVisible(false);
        openSessionButton.setManaged(false);
        returnHint.setVisible(false);
        returnHint.setManaged(false);
    }

    /**
     * Shows the dot and line. The button and hint are deliberately left alone:
     * {@link #setSessionRow} owns them, because whether they belong on screen
     * depends on the item having a bound session at all.
     */
    private void showSessionRow() {
        sessionDot.setVisible(true);
        sessionDot.setManaged(true);
        sessionLine.setVisible(true);
        sessionLine.setManaged(true);
    }

    /** Selects the item for {@code scopeId} ({@code ⌘4} and the sidebar's {@code ◨n} badge). */
    public void selectScope(String scopeId) {
        queue.revealAndSelect(scopeId);
    }

    /**
     * Whether {@code ⌘F} should reach the queue's quick-search field. False
     * while the rail is collapsed, where the field does not exist -- the
     * scene filter then keeps its existing route to the sidebar's filter
     * rather than focusing something invisible.
     */
    public boolean queueFilterAvailable() {
        return !queue.collapsed();
    }

    /** Focuses the queue's quick-search field ({@code ⌘F}). */
    public void focusQueueFilter() {
        queue.focusFilter();
    }

    /** The scope currently being reviewed, if the queue is not empty. */
    private Optional<ReviewScope> selectedScope() {
        return queue.selected().map(ReviewItem::scope);
    }

    /** Re-renders the queue rows (finding counts or session states changed). */
    public void refreshCounts() {
        queue.refreshRows();
    }

    /**
     * Re-reads findings, intents and verdicts from the store. Called on every
     * store change, including the MCP router's, because a view that renders
     * from a cached value silently discards the other writer's work.
     */
    public void refreshReviewState() {
        Optional<ReviewScope> scope = selectedScope();
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
        mcpPanel.filter(javafx.scene.Node::isVisible)
                .ifPresent(panel -> panel.setScope(scope.get()));
        renderVerdictBar(scope.get());
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
     * The selected scope's intents. A scope whose diff has not loaded -- or
     * never will, because it has no checkout -- has none, and says so
     * through {@link #emptyReason()} rather than borrowing another's.
     */
    /**
     * What the selected scope's diff attempt produced, if there is a selected
     * scope at all. Empty covers both "nothing selected" and "selected, but no
     * diff has resolved for it yet" -- the callers below distinguish those.
     */
    private Optional<DiffOutcome> selectedOutcome() {
        return selectedScope().map(scope -> outcomeByScope.get(scope.id()));
    }

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
            List<ReviewDiffColumn.Pin> pins = new java.util.ArrayList<>();
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
                            .filter(ReviewDestinationView.this::belongsToCurrentIntent)
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
     * <p>Refuses outright while {@link ReviewDiffColumn#displayedDiff()}
     * belongs to a different scope than the one selected -- the window
     * between selecting a scope and its diff actually landing. {@code
     * displayedScopeId} lags {@code setScope}: it is left pointing at
     * whatever scope's diff last finished loading until the new one
     * resolves, so a Submit pressed during that "Diffing…" window would
     * otherwise build the {@code DiffIndex} from the OUTGOING scope's diff
     * under the INCOMING scope's id. A comment whose real anchor is not in
     * that stale index gets refused as "not in this diff" even though it
     * is; worse, one that happens to share a key by coincidence could be
     * admitted with an anchor GitHub rejects, and since the whole review
     * posts as one atomic call, a single bad anchor 422s every other
     * comment in it. Nothing visible happens on this path: the diff column
     * is already showing "Diffing…" for exactly this reason, and Submit
     * simply does not fire until the id catches up -- pressing it again
     * once the diff lands succeeds normally.</p>
     */
    private void submitReview() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return;
        }
        if (!diffColumn.displayedScopeId().map(id -> id.equals(scope.get().id())).orElse(false)) {
            return;
        }
        List<ReviewIntent> counted = intents().stream()
                .filter(ReviewIntent::countsTowardProgress)
                .toList();
        List<ReviewVerdict.Decision> decisions = new java.util.ArrayList<>();
        for (int i = 0; i < counted.size(); i++) {
            Optional<ReviewVerdict> verdict = host.verdict(scope.get(), counted.get(i));
            if (verdict.isEmpty()) {
                intentIndex = intents().indexOf(counted.get(i));
                refreshReviewState();
                revealCurrentIntent();
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
     * per-{@link app.drydock.git.UnifiedDiff.Hunk} ordinal, which is what
     * lets {@link SubmitPlan#of} refuse a comment whose start and end land in
     * different hunks.
     */
    private static SubmitPlan.DiffIndex buildDiffIndex(app.drydock.git.UnifiedDiff diff) {
        java.util.Map<String, Integer> positionOfKey = new java.util.HashMap<>();
        java.util.Map<String, Integer> hunkOfKey = new java.util.HashMap<>();
        int position = 0;
        for (app.drydock.git.UnifiedDiff.FileDiff file : diff.files()) {
            int hunkIndex = 0;
            for (app.drydock.git.UnifiedDiff.Hunk hunk : file.hunks()) {
                for (app.drydock.git.UnifiedDiff.Line line : hunk.lines()) {
                    String key = file.path() + " " + line.lineKey();
                    positionOfKey.put(key, position++);
                    hunkOfKey.put(key, hunkIndex);
                }
                hunkIndex++;
            }
        }
        return new SubmitPlan.DiffIndex(positionOfKey, hunkOfKey);
    }

    // ---- item rendering -----------------------------------------------------

    private void showItem(ReviewItem item) {
        if (item == null) {
            // Reached only before the first queue assembly lands (the
            // constructor's initial call); once a scan has run, setItems
            // renders the empty surface itself and never routes through here.
            showEmpty(ReviewEmptyState.NOTHING_REVIEWABLE);
            return;
        }
        ReviewScope scope = item.scope();
        headerIcon.setText(item.icon());
        headerTitle.setText(item.title());
        headerContext.setText(contextLine(item));
        showSessionRow();
        setSessionRow(scope, sessionLineFor(scope), scope.sessionId().isPresent());
        body.getChildren().setAll(bodyFor(item));
        intentIndex = 0;
        refreshReviewState();
        // The diff usually has not arrived yet, in which case there is nothing
        // to reveal and the setOnDiffResolved handler does it when it lands.
        // Revealing here too covers the case where it already has -- coming
        // back to an item whose diff is still cached.
        revealCurrentIntent();
    }

    /**
     * The item header's second line: what this is, and what it is compared
     * against.
     *
     * <p>A working tree is diffed against its own {@code HEAD}, never against
     * the base branch, so naming the branch there claimed a comparison the
     * column was not making. And the comparison is stated once: a worktree
     * item's subtitle already carries it for the queue rail, and repeating it
     * here read as "vs develop · vs develop".</p>
     */
    private static String contextLine(ReviewItem item) {
        String against = item.scope().kind() == ReviewScope.Kind.WORKING_TREE
                ? "HEAD"
                : item.scope().base();
        String comparison = "vs " + against;
        String provenance = item.scope().baseOrigin()
                .filter(origin -> origin == ReviewBase.Origin.DEFAULT_UNMEASURED)
                .map(origin -> "  ·  " + origin.description())
                .orElse("");
        return (item.subtitle().endsWith(comparison)
                ? item.subtitle()
                : item.subtitle() + "  ·  " + comparison) + provenance;
    }

    /**
     * The centre body: the diff column for anything with a checkout, the
     * checkout gate for a PR that has none (spec §6), and whatever the host
     * supplies ahead of both -- that override is the seam the findings
     * margin and intent rail arrive through.
     */
    private Region bodyFor(ReviewItem item) {
        Optional<Region> supplied = host.bodyFor(item.scope());
        if (supplied.isPresent()) {
            return supplied.get();
        }
        if (item.scope().worktree().isEmpty()) {
            if (patchOnlyDiffs.containsKey(item.scope().id())) {
                return patchOnlyBody(item.scope());
            }
            checkoutGate.setScope(item.scope());
            return checkoutGate;
        }
        diffColumn.setScope(item.scope());
        VBox.setVgrow(diffColumn, Priority.ALWAYS);
        return diffColumn;
    }

    private String sessionLineFor(ReviewScope scope) {
        return scope.sessionId()
                .map(id -> "session " + shortId(id) + " · " + host.sessionState(scope).orElse("idle")
                        + " · " + scope.id())
                .orElse("no session bound — review starts one · " + scope.id());
    }

    private static String shortId(ManagedSessionId id) {
        String text = id.toString();
        return text.length() > 8 ? text.substring(0, 8) : text;
    }

    private void setSessionRow(ReviewScope scope, String text, boolean hasSession) {
        sessionLine.setText(text);
        sessionDot.getStyleClass().removeIf(styleClass -> styleClass.startsWith("dot-"));
        sessionDot.getStyleClass().add("dot-" + (scope == null
                ? "none"
                : host.sessionState(scope).orElse("none")));
        openSessionButton.setVisible(hasSession);
        openSessionButton.setManaged(hasSession);
        returnHint.setVisible(hasSession);
        returnHint.setManaged(hasSession);
    }

    /**
     * {@code Read the patch only}: the PR's own diff with no worktree behind
     * it. A banner says so, because everything that needs a session -- the
     * reviewer, the ASK chips, Apply patch -- is unavailable here and the
     * reader must not be left guessing why.
     */
    private Region patchOnlyBody(ReviewScope scope) {
        diffColumn.showDiff(scope, patchOnlyDiffs.get(scope.id()));
        VBox.setVgrow(diffColumn, Priority.ALWAYS);
        VBox box = new VBox(ReviewCheckoutGate.readOnlyBanner(
                scope.pr().map(ReviewScope.PullRequestRef::number).orElse(0)), diffColumn);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** The checkout gate's window onto the host. */
    private final class GateHost implements ReviewCheckoutGate.Host {
        @Override
        public void startSessionAndReview(ReviewScope scope) {
            host.startSessionAndReview(scope, checkoutGate::showFailure);
        }

        @Override
        public void launchCommandPreview(ReviewScope scope, Consumer<String> onReady) {
            host.launchCommandPreview(scope, onReady);
        }

        @Override
        public void readPatchOnly(ReviewScope scope) {
            host.readPatchOnly(scope, patch -> {
                if (!checkoutGate.isShowing(scope)) {
                    return;
                }
                if (patch.isEmpty()) {
                    checkoutGate.showFailure("The GitHub CLI could not return this pull request's diff. "
                            + "Check that gh is installed and authenticated, or start a session to review it locally.");
                    return;
                }
                patchOnlyDiffs.put(scope.id(), patch.get());
                queue.selected().filter(item -> item.scope().id().equals(scope.id()))
                        .ifPresent(item -> body.getChildren().setAll(bodyFor(item)));
            });
        }
    }

    /**
     * @param scope what the state is talking about -- the repositories a scan
     *              covered. Blank renders no line: an empty one would read as
     *              a scope that came back empty.
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
     * Rails auto-collapse as the window narrows so the code column is never
     * crushed (spec §4.9). A manual collapse is remembered separately: a
     * user who collapsed the queue keeps it collapsed when the window grows
     * back, and one who did not gets it back.
     *
     * <p>Below {@link #DRILL_IN_WIDTH} that trade stops working -- there is
     * no width left to trade -- and the layout becomes two alternating
     * full-width pages instead. The user's collapse flags are read, never
     * written, here, so crossing the threshold in either direction restores
     * exactly what they chose.</p>
     */
    private void applyResponsiveLayout(double width) {
        if (queueEmpty) {
            applyEmptySurface();
            return;
        }
        showEveryRegion();
        // A width of 0 is the pre-layout state, not a narrow window; drilling
        // in there would flash the Browse page on every first show.
        boolean narrowNow = width > 0 && width < DRILL_IN_WIDTH;
        if (narrowNow) {
            applyDrillInLayout(width);
            return;
        }
        if (drilledIn) {
            drilledIn = false;
            queue.setSpanWidth(0);
            intentRail.setSpanWidth(0);
            queueBackChip.setVisible(false);
            queueBackChip.setManaged(false);
            // Centre first: coming back from Browse the rails ARE the centre,
            // and a BorderPane rejects the same node in two slots at once.
            setCenter(centre);
            setLeft(rails);
        }
        rails.setVisible(true);
        rails.setManaged(true);
        // Rails give up their width, margin first and queue last, until the
        // code column clears its floor. A manual collapse is remembered
        // separately: a user who collapsed the queue keeps it collapsed when
        // the window grows back, and one who did not gets it back.
        RailLayout.Layout layout = RailLayout.solve(width, queueCollapsedByUser,
                intentsCollapsedByUser, marginCollapsedByUser);
        queue.setNarrow(layout.narrow());
        queue.setCollapsed(layout.queueCollapsed());
        intentRail.setNarrow(layout.narrow());
        intentRail.setCollapsed(layout.intentsCollapsed());
        margin.setNarrow(layout.narrow());
        margin.setCollapsed(layout.marginCollapsed());
    }

    /**
     * The empty surface: the title bar, and one centred state in the middle
     * of the window. Every region that describes an item is hidden rather
     * than left empty -- see {@link #queueEmpty}.
     *
     * <p>The drill-in is unwound here too. Its whole job is to trade width
     * between rails that are not on screen, and leaving the view in a narrow
     * page meant the centred state could land in a slot the layout had
     * already given to a hidden rail.</p>
     */
    private void applyEmptySurface() {
        if (drilledIn) {
            drilledIn = false;
            queue.setSpanWidth(0);
            intentRail.setSpanWidth(0);
            setCenter(centre);
        }
        setLeft(null);
        setCenter(centre);
        show(rails, false);
        show(margin, false);
        show(verdictBar, false);
        show(itemHeader, false);
        show(queueBackChip, false);
        mcpPanel.ifPresent(panel -> show(panel, false));
    }

    /** Undoes {@link #applyEmptySurface}; the responsive rules take it from here. */
    private void showEveryRegion() {
        show(margin, true);
        show(verdictBar, true);
        show(itemHeader, true);
        if (getLeft() == null && !drilledIn) {
            setLeft(rails);
        }
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * The narrow drill-in (spec §4.9). Browse gives the two rails the whole
     * window; Detail gives it to the diff and its margin and hides the rails
     * outright -- hidden, not collapsed, because a collapsed rail here is the
     * unusable sliver the drill-in exists to remove.
     */
    private void applyDrillInLayout(double width) {
        drilledIn = true;
        boolean browsing = narrowPage == NarrowPage.BROWSE;

        // Both rails must be readable on Browse regardless of what the user
        // collapsed at a wider size; those flags are left untouched so the
        // wide layout gets them back.
        queue.setCollapsed(false);
        intentRail.setCollapsed(false);
        queue.setNarrow(true);
        intentRail.setNarrow(true);
        margin.setNarrow(true);
        // The Detail page's margin has its own remembered state, defaulting
        // to collapsed: at 610px an expanded 286px margin leaves the code
        // column ~300px, which is the crushed diff this drill-in exists to
        // prevent. Reusing the wide layout's auto-collapse threshold instead
        // would have made `m` a dead key here -- the threshold would just
        // re-collapse whatever the user expanded.
        margin.setCollapsed(narrowMarginCollapsed);

        rails.setVisible(browsing);
        rails.setManaged(browsing);
        queueBackChip.setVisible(!browsing);
        queueBackChip.setManaged(!browsing);

        if (browsing) {
            setLeft(null);
            setCenter(rails);
            applyBrowseSpans();
        } else {
            queue.setSpanWidth(0);
            intentRail.setSpanWidth(0);
            setLeft(null);
            setCenter(centre);
        }
    }

    /**
     * Splits the Browse page between the two rails, from the width the rails
     * container ACTUALLY has.
     *
     * <p>Deriving it from this view's width instead was wrong in the way only
     * the real app shows: after a window resize the view keeps its old width
     * for several layout passes (measured: 896px inside a 610px slot, with
     * the sidebar still to be subtracted), so the intent rail was sized from
     * the whole window and ran off the right edge. The rails container is a
     * BorderPane slot -- its width is handed down by the parent and never
     * depends on these children -- so reading it here cannot feed back.</p>
     */
    private void applyBrowseSpans() {
        if (!drilledIn || narrowPage != NarrowPage.BROWSE) {
            return;
        }
        double available = rails.getWidth();
        if (available <= 0) {
            return;
        }
        double queueWidth = Math.floor(available * BROWSE_QUEUE_FRACTION);
        queue.setSpanWidth(queueWidth);
        intentRail.setSpanWidth(Math.max(0, available - queueWidth));
    }

    /**
     * Switches narrow pages. A no-op wide, where both halves are already on
     * screen -- but the page is still recorded, so shrinking afterwards lands
     * where the user last was.
     */
    private void showNarrowPage(NarrowPage page) {
        narrowPage = page;
        applyResponsiveLayout(getWidth());
    }

    /**
     * {@code q} / {@code i} collapse a rail -- and are inert while drilled
     * in, where the rails are whole pages rather than columns. Flipping the
     * flag there would have looked like a dead key and then taken effect
     * minutes later, when the window was widened again.
     */
    private void setQueueCollapsed(boolean collapsed) {
        if (drilledIn) {
            return;
        }
        queueCollapsedByUser = collapsed;
        applyResponsiveLayout(getWidth());
    }

    /** {@code m}: writes whichever remembered state the current layout reads. */
    private void setMarginCollapsed(boolean collapsed) {
        if (drilledIn) {
            narrowMarginCollapsed = collapsed;
        } else {
            marginCollapsedByUser = collapsed;
        }
        applyResponsiveLayout(getWidth());
    }

    /** See {@link #setQueueCollapsed} -- inert while drilled in, for the same reason. */
    private void setIntentsCollapsed(boolean collapsed) {
        if (drilledIn) {
            return;
        }
        intentsCollapsedByUser = collapsed;
        applyResponsiveLayout(getWidth());
    }

    /**
     * {@code f}: collapses every rail so code and findings own the window --
     * the "review mode" behaviour without a separate mode. A toggle, not a
     * one-way collapse, or the second press would be a dead key.
     */
    private void setFocusMode(boolean on) {
        if (drilledIn) {
            // Detail already IS focus mode -- code and findings own the whole
            // window there -- and Browse has no code to focus on. Writing the
            // flags anyway would ambush the user the next time they widened.
            return;
        }
        queueCollapsedByUser = on;
        intentsCollapsedByUser = on;
        marginCollapsedByUser = on;
        applyResponsiveLayout(getWidth());
    }

    private void verdictAction(ReviewVerdict.Decision decision) {
        Optional<ReviewScope> scope = selectedScope();
        Optional<ReviewIntent> intent = currentIntent();
        if (scope.isPresent() && intent.isPresent()) {
            host.setVerdict(scope.get(), intent.get(), Optional.of(decision));
        }
    }

    private void undoVerdict() {
        Optional<ReviewScope> scope = selectedScope();
        Optional<ReviewIntent> intent = currentIntent();
        if (scope.isPresent() && intent.isPresent()) {
            host.setVerdict(scope.get(), intent.get(), Optional.empty());
        }
    }

    /**
     * The reviewer selector and "Re-run review on this scope". A menu rather
     * than a cycle: which reviewer runs is a choice, and re-running is an
     * action, so they must not share a click.
     */
    private void showReviewerMenu() {
        reviewerMenu.getItems().clear();
        List<String> reviewers = host.reviewers();
        if (reviewers.isEmpty()) {
            MenuItem none = new MenuItem("No reviewer configured");
            none.setDisable(true);
            reviewerMenu.getItems().add(none);
        } else {
            for (String reviewer : reviewers) {
                MenuItem item = new MenuItem(reviewer);
                item.setOnAction(e -> {
                    host.selectReviewer(reviewer);
                    renderReviewerButton();
                });
                reviewerMenu.getItems().add(item);
            }
            MenuItem rerun = new MenuItem("Re-run review on this scope");
            rerun.setDisable(selectedScope().isEmpty());
            rerun.setOnAction(e -> selectedScope().ifPresent(host::runReview));
            reviewerMenu.getItems().addAll(new SeparatorMenuItem(), rerun);
        }
        reviewerMenu.show(reviewerButton, Side.BOTTOM, 0, 4);
    }

    private void renderReviewerButton() {
        reviewerButton.setText(host.selectedReviewer().orElse("no reviewer"));
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

    private void openBoundSession() {
        selectedScope().flatMap(ReviewScope::sessionId).ifPresent(host::openSession);
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
     */
    private void onKeyPressed(KeyEvent event) {
        if (event.isShortcutDown() || event.isAltDown()
                || event.getTarget() instanceof TextInputControl) {
            return;
        }
        boolean handled = switch (event.getCode()) {
            case J -> { queue.moveSelection(1); yield true; }
            case K -> { queue.moveSelection(-1); yield true; }
            case Q -> { setQueueCollapsed(!queue.collapsed()); yield true; }
            case SLASH -> { queue.focusFilter(true); yield true; }
            case O -> { openBoundSession(); yield true; }
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
            // Narrow Browse has no verdict bar and nothing to submit, so
            // Enter is the drill-in there instead (spec §4.9); everywhere
            // else it stays Submit.
            case ENTER -> {
                if (drilledIn && narrowPage == NarrowPage.BROWSE) {
                    showNarrowPage(NarrowPage.DETAIL);
                } else {
                    submitReview();
                }
                yield true;
            }
            // Shift+F is the whole-review filter; plain f is focus mode.
            case F -> {
                if (event.isShiftDown()) {
                    margin.setWholeReview(!margin.wholeReview());
                } else {
                    setFocusMode(!(queueCollapsedByUser && intentsCollapsedByUser
                            && marginCollapsedByUser));
                }
                yield true;
            }
            default -> false;
        };
        if (handled) {
            event.consume();
        }
    }

    /**
     * Escape's unwind, topmost-first (spec §5): the symbol-lens popover,
     * then the MCP activity panel, then the narrow Detail page, then Review
     * itself. Returns whether something was closed, so the scene filter knows
     * whether to keep unwinding.
     *
     * <p>The Detail page is the last step before leaving: only from Browse
     * does Escape return to the origin tab (spec §4.9), so a reader deep in a
     * diff cannot lose the whole surface to one keystroke.</p>
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
        if (mcpPanel.filter(javafx.scene.Node::isVisible).isPresent()) {
            toggleMcpPanel();
            return true;
        }
        if (drilledIn && narrowPage == NarrowPage.DETAIL) {
            showNarrowPage(NarrowPage.BROWSE);
            return true;
        }
        return false;
    }

    /**
     * Called by the workspace when Review becomes visible. Focus is taken on
     * the next pulse: this runs the moment the node is made visible, before
     * the layout pass that makes it focusable, so an immediate {@code
     * requestFocus} would be dropped and the keyboard table would be dead
     * until the user clicked something.
     */
    public void onShown() {
        host.refreshQueue();
        Platform.runLater(this::requestFocus);
    }

    /**
     * Diagnostic-only: walks EVERY queue item and reports what its diff
     * produced. The base a review diffs against is derived, so "the queue
     * assembled" is not evidence that each item can actually resolve it --
     * this is (see docs/architecture.md: no headless harness inside the
     * running app).
     */
    public List<String> diagAllItemDiffs() {
        List<String> report = new java.util.ArrayList<>();
        for (ReviewItem item : queue.items()) {
            ReviewScope itemScope = item.scope();
            // A scope that is not diffable (no checkout: a PR the human has
            // not started a session for) must never reach diffService.diff --
            // this diagnostic exists to prove each item resolves its base,
            // and running it anyway reports a fabricated file count for
            // exactly the scopes the branch declares wrong-by-construction.
            String summary = itemScope.diffable()
                    ? host.diagDiffSummary(itemScope)
                    : "not diffable (no checkout)";
            report.add(item.title() + " [base=" + itemScope.base() + "] " + summary);
        }
        return report;
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
    void diagShowDiff(ReviewScope forScope, app.drydock.git.UnifiedDiff diff) {
        diffColumn.showDiff(forScope, diff);
    }

    /**
     * Diagnostic-only: opens the gutter comment composer on the first
     * rendered changed line, and says where it landed.
     *
     * <p>The composer is opened by a mouse click on a 34px label inside a
     * virtualized cell, which the screenshot harness cannot aim at. This is
     * how the one part of it that no unit test can show -- what it actually
     * looks like sitting in the diff -- gets photographed.</p>
     */
    public String diagOpenComposer() {
        return diffColumn.diagOpenComposer();
    }

    /** Diagnostic-only: selects the {@code index}-th queue item, for the visual pass. */
    public void diagSelectItem(int index) {
        List<ReviewItem> items = queue.items();
        if (index >= 0 && index < items.size()) {
            queue.select(items.get(index).scope().id());
        }
    }

    /** Diagnostic-only: the scope the queue has selected. */
    public Optional<String> diagSelectedScopeId() {
        return selectedScope().map(ReviewScope::id);
    }

    /**
     * Diagnostic-only: the first {@code count} changed lines currently
     * rendered, as {@code [file, lineKey]}. Lets the visual pass seed
     * findings anchored to lines that genuinely exist, so the pins land on
     * real rows instead of nowhere.
     */
    public List<String[]> diagAnchors(int count) {
        List<String[]> anchors = new java.util.ArrayList<>();
        for (ReviewDiffRow row : diffColumn.diagRows()) {
            if (anchors.size() >= count) {
                break;
            }
            if (row instanceof ReviewDiffRow.Line line
                    && line.line().kind() != app.drydock.git.UnifiedDiff.Line.Kind.CONTEXT) {
                anchors.add(new String[] { line.file(), line.lineKey() });
            }
        }
        return anchors;
    }

    /**
     * Diagnostic-only: which narrow page is showing, or {@code "wide"} above
     * {@link #DRILL_IN_WIDTH}. The drill-in has no other observable output --
     * it only moves nodes between the layout's slots -- so this is what the
     * tests and the visual pass assert against.
     */
    public String diagNarrowPage() {
        return drilledIn ? narrowPage.name().toLowerCase(java.util.Locale.ROOT) : "wide";
    }

    /** Diagnostic-only: the widths the drill-in actually resolved to, for the visual pass. */
    public String diagLayoutWidths() {
        return diagNarrowPage() + " view=" + (int) getWidth() + " rails=" + (int) rails.getWidth()
                + " queue=" + (int) queue.getWidth() + " intents=" + (int) intentRail.getWidth()
                + " | diff " + diffColumn.diagWidths()
                + " | rail " + intentRail.diagCards();
    }

    /** Diagnostic-only: every queue item, filtered or not (visual verification harness). */
    public List<ReviewItem> diagItems() {
        return queue.items();
    }

    /**
     * Diagnostic-only: a one-line summary of what the diff column rendered
     * for the selected item. The FX layer has no headless harness inside the
     * running app, so this is the machine-checkable evidence that a real
     * scope produced a real diff (see docs/architecture.md).
     */
    public String diagDiffSummary() {
        List<ReviewDiffRow> rows = diffColumn.diagRows();
        long cards = rows.stream().filter(ReviewDiffRow.HunkHeader.class::isInstance).count();
        long lines = rows.stream().filter(ReviewDiffRow.Line.class::isInstance).count();
        long folded = rows.stream().filter(ReviewDiffRow.CollapsedRun.class::isInstance).count();
        return rows.size() + " rows · " + cards + " hunk cards · " + lines + " lines · "
                + folded + " folded runs";
    }
}
