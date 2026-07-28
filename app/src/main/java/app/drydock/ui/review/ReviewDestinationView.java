package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
    }

    private final Host host;
    private final ReviewQueueRail queue = new ReviewQueueRail();
    private final ReviewDiffColumn diffColumn;

    private final Label countsLabel = new Label();
    private final Label headerIcon = new Label();
    private final Label headerTitle = new Label();
    private final Label headerContext = new Label();
    private final Region sessionDot = new Region();
    private final Label sessionLine = new Label();
    private final Button openSessionButton = new Button("Open session");
    private final Label returnHint = new Label("⌘4 from that session returns here");
    private final Button densityButton = new Button();
    private final VBox body = new VBox();

    private ReviewDensity density = ReviewDensity.COZY;

    /** Set by {@code q}/{@code f}; remembered independently of the responsive collapse (spec §4.9). */
    private boolean queueCollapsedByUser;

    public ReviewDestinationView(Host host, app.drydock.git.DiffService diffService) {
        this.host = host;
        this.diffColumn = new ReviewDiffColumn(diffService, host::openInExplorer);
        getStyleClass().add("review-destination");

        setTop(buildTitleBar());
        setLeft(queue);
        setCenter(buildCenter());

        queue.setOnSelected(this::showItem);
        queue.setOnToggleCollapse(() -> setQueueCollapsed(!queue.collapsed()));
        queue.setFindingCount(item -> host.openFindings(item.scope()));
        queue.setSessionDot(item -> host.sessionState(item.scope()));

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

        densityButton.getStyleClass().add("review-chip-button");
        densityButton.setTooltip(new Tooltip("Density: cozy · compact · dense (d)"));
        densityButton.setOnAction(e -> cycleDensity());
        applyDensity(density);

        Button shortcuts = new Button("?");
        shortcuts.getStyleClass().add("review-chip-button");
        shortcuts.setTooltip(new Tooltip("Shortcuts (?)"));
        shortcuts.setOnAction(e -> host.showShortcuts());

        HBox bar = new HBox(8, glyph, title, countsLabel, spacer, densityButton, shortcuts);
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
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox centre = new VBox(header, body);
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
        queue.select(scopeId);
    }

    /** The scope currently being reviewed, if the queue is not empty. */
    private Optional<ReviewScope> selectedScope() {
        return queue.selected().map(ReviewItem::scope);
    }

    /** Re-renders the queue rows (finding counts or session states changed). */
    public void refreshCounts() {
        queue.refreshRows();
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
        headerContext.setText(item.subtitle() + "  ·  vs " + scope.base());
        setSessionRow(scope, sessionLineFor(scope), scope.sessionId().isPresent());
        body.getChildren().setAll(bodyFor(item));
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
            return checkoutGate(item.scope());
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
     * The checkout gate (spec §6). A PR with no worktree has no session, and
     * therefore no agent -- which is why this is a gate rather than a
     * convenience. The buttons arrive with the checkout flow.
     */
    private Region checkoutGate(ReviewScope scope) {
        String title = scope.pr()
                .map(pr -> "PR #" + pr.number() + " has no session yet")
                .orElse("This scope has no checkout yet");
        String command = scope.pr()
                .map(pr -> "gh pr checkout " + pr.number() + " --worktree")
                .orElse("git worktree add");
        return placeholder(title,
                "Reviewing it starts a worktree and a session, so an agent can read the diff "
                        + "and answer questions about it.",
                command);
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
    }

    private void setQueueCollapsed(boolean collapsed) {
        queueCollapsedByUser = collapsed;
        applyResponsiveLayout(getWidth());
    }

    private void cycleDensity() {
        applyDensity(density.next());
    }

    private void applyDensity(ReviewDensity newDensity) {
        density = newDensity;
        densityButton.setText(newDensity.label());
        diffColumn.setDensity(newDensity);
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
            // Focus mode: collapse every rail, or restore them all if
            // everything is already collapsed. A one-way "collapse" would
            // make f a dead key on its second press.
            case F -> { setQueueCollapsed(!queueCollapsedByUser); yield true; }
            case O -> { openBoundSession(); yield true; }
            case D -> { cycleDensity(); yield true; }
            case C -> { diffColumn.toggleContext(); yield true; }
            default -> false;
        };
        if (handled) {
            event.consume();
        }
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

    /** Diagnostic-only: the queue rows currently rendered (visual verification harness). */
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
