package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;

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

    /** Below this width the rails take their narrow sizes; below {@link #QUEUE_COLLAPSE_WIDTH} they collapse (spec §4.9). */
    private static final double NARROW_WIDTH = 1320;
    private static final double QUEUE_COLLAPSE_WIDTH = 1180;
    private static final double INTENT_COLLAPSE_WIDTH = 1040;
    private static final double MARGIN_COLLAPSE_WIDTH = 880;

    /** What the view needs from the workspace. All calls happen on the FX thread. */
    public interface Host {
        /** Reassembles the queue (called when Review is shown). */
        void refreshQueue();

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

        /** The intents of {@code scope}: the reviewer's grouping, or the by-file fallback. */
        List<ReviewIntent> intents(ReviewScope scope);

        /** The verdict recorded on one intent, if any. */
        Optional<ReviewVerdict> verdict(ReviewScope scope, ReviewIntent intent);

        /** Records a verdict; {@code decision} empty undoes it. */
        void setVerdict(ReviewScope scope, ReviewIntent intent,
                        Optional<ReviewVerdict.Decision> decision);

        /** Resolve / Reopen one finding. */
        void setResolved(ReviewScope scope, ReviewAnnotation finding, boolean resolved);

        /** Appends a human message to a thread (Reply, and the ASK chips). */
        void postMessage(ReviewScope scope, ReviewAnnotation finding, String body);

        /** {@code Apply patch} -- a human click; drydock never applies one on its own. */
        void applyPatch(ReviewScope scope, ReviewAnnotation finding);

        /** Records the human's severity override. */
        void overrideSeverity(ReviewScope scope, ReviewAnnotation finding, Severity severity);

        /** Hands an intent's open findings to the scope's bound session. */
        void askAgentToFix(ReviewScope scope, ReviewIntent intent, List<ReviewAnnotation> findings);

        /** Posts the review once every intent is settled. */
        void submit(ReviewScope scope);

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

    /** The intent the verdict bar is settling; {@code [} / {@code ]} / {@code n} move it. */
    private int intentIndex;

    /** Set by {@code m}/{@code f}; remembered independently of the responsive collapse. */
    private boolean marginCollapsedByUser;

    /** Set by {@code i}/{@code f}; remembered independently of the responsive collapse. */
    private boolean intentsCollapsedByUser;

    private final Label countsLabel = new Label();
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

        setTop(buildTitleBar());
        // Queue then intents, left to right, exactly as the anatomy lays them
        // out; the centre carries the code, the margin and the verdict bar.
        HBox rails = new HBox(queue, intentRail);
        setLeft(rails);
        setCenter(buildCenter());

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
            }
        });
        margin.setOnFilterChanged(filter -> refreshReviewState());
        diffColumn.setPinSource(new PinSource());
        // The by-file intent fallback is derived from the diff, and the diff
        // arrives asynchronously -- so the verdict bar has to be re-rendered
        // when it lands, or it stays on the "no intent" it correctly computed
        // from an empty diff and never recovers.
        diffColumn.setOnDiffLoaded(this::refreshReviewState);

        widthProperty().addListener((obs, old, width) -> applyResponsiveLayout(width.doubleValue()));
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        setFocusTraversable(true);
        showItem(null);
    }

    // ---- title bar ----------------------------------------------------------

    private Region buildTitleBar() {
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

        HBox bar = new HBox(8, glyph, title, countsLabel, spacer, reviewerButton, densityButton,
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
        Region row1Spacer = new Region();
        HBox.setHgrow(row1Spacer, Priority.ALWAYS);
        HBox row1 = new HBox(9, headerIcon, headerTitle, headerContext, row1Spacer);
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
     * Replaces the queue's contents. The previous selection survives when
     * its scope is still present; otherwise the first item is selected, and
     * an empty queue shows the zero state.
     */
    public void setItems(List<ReviewItem> items, int repositoryCount) {
        String previous = queue.selected().map(item -> item.scope().id()).orElse(null);
        queue.setItems(items);
        countsLabel.setText(items.size() + (items.size() == 1 ? " item · " : " items · ")
                + repositoryCount + (repositoryCount == 1 ? " repo" : " repos"));
        if (items.isEmpty()) {
            showItem(null);
            return;
        }
        boolean stillThere = previous != null
                && items.stream().anyMatch(item -> item.scope().id().equals(previous));
        queue.select(stillThere ? previous : items.get(0).scope().id());
    }

    /** Selects the item for {@code scopeId} ({@code ⌘4} and the sidebar's {@code ◨n} badge). */
    public void selectScope(String scopeId) {
        queue.revealAndSelect(scopeId);
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
            return;
        }
        margin.invalidate(null);
        margin.setFindings(findingsForMargin(scope.get()));
        diffColumn.refreshPins();
        intentRail.setIntents(intents(), currentIntent().map(ReviewIntent::id).orElse(null));
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
        return all.stream()
                .filter(finding -> finding.intentId()
                        .map(id -> id.equals(current.get().id()))
                        .orElse(true))
                .toList();
    }

    private List<ReviewIntent> intents() {
        return selectedScope().map(host::intents).orElse(List.of());
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
                .filter(finding -> finding.intentId()
                        .map(id -> id.equals(current.get().id()))
                        .orElse(true))
                .anyMatch(ReviewAnnotation::blocksApproval);
        verdictBar.update(current.get(), host.verdict(scope, current.get()), blocked,
                (int) settled, counted.size());
    }

    /**
     * Brings the current intent's code into view. Selecting an intent that
     * left the diff where it was is the bug this fixes: the rail said one
     * thing and the centre showed another, so the rail read as decoration.
     */
    private void revealCurrentIntent() {
        currentIntent().flatMap(ReviewIntent::anchor)
                .ifPresent(anchor -> diffColumn.revealHunk(anchor.file(), anchor.hunkIndex()));
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
                            .filter(finding -> finding.intentId()
                                    .map(id -> id.equals(intent.id())).orElse(true))
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
    }

    /**
     * Submit (spec §4.6): with anything unsettled this jumps to the first
     * such intent rather than posting a partial review; once everything is
     * settled it posts ONE review.
     */
    private void submitReview() {
        Optional<ReviewScope> scope = selectedScope();
        if (scope.isEmpty()) {
            return;
        }
        List<ReviewIntent> counted = intents().stream()
                .filter(ReviewIntent::countsTowardProgress)
                .toList();
        for (int i = 0; i < counted.size(); i++) {
            if (host.verdict(scope.get(), counted.get(i)).isEmpty()) {
                intentIndex = intents().indexOf(counted.get(i));
                refreshReviewState();
                revealCurrentIntent();
                return;
            }
        }
        host.submit(scope.get());
    }

    // ---- item rendering -----------------------------------------------------

    private void showItem(ReviewItem item) {
        if (item == null) {
            headerIcon.setText("◨");
            headerTitle.setText("Nothing to review");
            headerContext.setText("");
            setSessionRow(null, "no items in the queue", false);
            body.getChildren().setAll(placeholder("Nothing to review",
                    "Worktrees, uncommitted changes and PRs that ask you for a review all land here.",
                    ""));
            return;
        }
        ReviewScope scope = item.scope();
        headerIcon.setText(item.icon());
        headerTitle.setText(item.title());
        headerContext.setText(contextLine(item));
        setSessionRow(scope, sessionLineFor(scope), scope.sessionId().isPresent());
        body.getChildren().setAll(bodyFor(item));
        intentIndex = 0;
        refreshReviewState();
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
        return item.subtitle().endsWith(comparison)
                ? item.subtitle()
                : item.subtitle() + "  ·  " + comparison;
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
        diffColumn.showDiff(patchOnlyDiffs.get(scope.id()));
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

    private static Region placeholder(String title, String detail, String mono) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("review-placeholder-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("review-placeholder-detail");
        detailLabel.setWrapText(true);
        detailLabel.setMaxWidth(520);
        VBox box = new VBox(8, titleLabel, detailLabel);
        if (!mono.isBlank()) {
            Label monoLabel = new Label(mono);
            monoLabel.getStyleClass().add("review-placeholder-mono");
            box.getChildren().add(monoLabel);
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
     */
    private void applyResponsiveLayout(double width) {
        queue.setNarrow(width < NARROW_WIDTH);
        queue.setCollapsed(queueCollapsedByUser || width < QUEUE_COLLAPSE_WIDTH);
        intentRail.setNarrow(width < NARROW_WIDTH);
        intentRail.setCollapsed(intentsCollapsedByUser || width < INTENT_COLLAPSE_WIDTH);
        margin.setNarrow(width < NARROW_WIDTH);
        margin.setCollapsed(marginCollapsedByUser || width < MARGIN_COLLAPSE_WIDTH);
    }

    private void setQueueCollapsed(boolean collapsed) {
        queueCollapsedByUser = collapsed;
        applyResponsiveLayout(getWidth());
    }

    private void setMarginCollapsed(boolean collapsed) {
        marginCollapsedByUser = collapsed;
        applyResponsiveLayout(getWidth());
    }

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
            case ENTER -> { submitReview(); yield true; }
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
     * then the MCP activity panel, then Review itself. Returns whether
     * something was closed, so the scene filter knows whether to keep
     * unwinding.
     */
    public boolean unwindOne() {
        if (diffColumn.lensOpen()) {
            diffColumn.hideLens();
            return true;
        }
        if (mcpPanel.filter(javafx.scene.Node::isVisible).isPresent()) {
            toggleMcpPanel();
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
     * The diff currently rendered. The intent fallback groups by file, so it
     * needs the same diff the column is showing rather than a second one -- a
     * grouping derived from a re-read would drift from what is on screen.
     */
    public app.drydock.git.UnifiedDiff currentDiff() {
        return diffColumn.currentDiff();
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
            report.add(item.title() + " [base=" + itemScope.base() + "] "
                    + host.diagDiffSummary(itemScope));
        }
        return report;
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
