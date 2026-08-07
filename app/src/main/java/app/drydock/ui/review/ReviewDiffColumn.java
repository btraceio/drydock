package app.drydock.ui.review;

import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Severity;
import app.drydock.ui.UiErrors;
import app.drydock.ui.code.SyntaxHighlighter;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Review diff column (spec §4.4): hunk cards over a virtualized row
 * list, with a gutter, a sign column, syntax-highlighted source, collapsed
 * unchanged runs, three densities and a jump into the Explorer.
 *
 * <p>Virtualized over a {@link ListView} rather than laid out as real nested
 * card containers, because a 21-file diff is tens of thousands of lines. The
 * cards are drawn from the per-row {@link ReviewDiffRow.Edge} instead (see
 * {@link ReviewDiffRow}).</p>
 *
 * <p><strong>Rows must keep their natural height.</strong> Nothing here sets
 * a fixed cell size, and the cell graphics are never bound to the cell
 * height: a fixed row height was the bug that hid code with no scrollbar to
 * reveal it.</p>
 */
final class ReviewDiffColumn extends BorderPane {

    private static final Logger LOG = System.getLogger(ReviewDiffColumn.class.getName());

    /** Cap on rendered rows; the remainder is noted, never silently dropped. */
    private static final int MAX_RENDERED_ROWS = 4000;

    /** Where {@code ⤢} goes: the Explorer, in the session bound to the scope being reviewed. */
    @FunctionalInterface
    interface ExplorerBridge {
        /**
         * Opens {@code file} at a 1-based line in the Explorer of the session
         * bound to {@code scope}. Returns false when there is nowhere to open
         * it (no session, or its tab is not open), so the caller can say so
         * rather than appear to do nothing.
         */
        boolean openFileAtLine(ReviewScope scope, Path file, int line);
    }

    /** Where a comment minted by the gutter composer goes. */
    @FunctionalInterface
    interface CommentSink {
        /**
         * Records {@code annotation}, freshly minted by {@link #submitComposer()}.
         * The sink owns storage (id generation, intent assignment) -- the
         * column only knows the range and the text, never the store.
         */
        void addComment(ReviewAnnotation annotation);
    }

    /** Findings anchored to a line, and what happens when their pin is clicked (spec §4.4). */
    interface PinSource {
        /** The pin numbers of the findings anchored to {@code lineKey} in {@code file}, in margin order. */
        List<Pin> pinsAt(String file, String lineKey);

        /** Clicking a line or its pin focuses the matching card. */
        void focusFinding(Pin pin);
    }

    /** One {@code ◆n} marker: its number, its severity style class, and its finding's key. */
    record Pin(int number, String severityStyleClass, ReviewAnnotation.Key key,
               boolean dimmed) {
    }

    private final DiffService diffService;
    private final ExplorerBridge explorerBridge;
    private PinSource pinSource = new PinSource() {
        @Override
        public List<Pin> pinsAt(String file, String lineKey) {
            return List.of();
        }

        @Override
        public void focusFinding(Pin pin) {
        }
    };

    private final Label summaryLabel = new Label();
    private final Button contextToggle = new Button();
    private final Button untrackedToggle = new Button();

    /**
     * The escape hatch out of the intent filter (spec §4.4). Present only
     * while an intent is selected, because "show all" with nothing to show
     * all of is a control that does nothing.
     */
    private final Button scopeToggle = new Button();
    private final ObservableList<ReviewDiffRow> rows = FXCollections.observableArrayList();
    private final ListView<ReviewDiffRow> list = new ListView<>(rows);

    /**
     * The width rows are laid out at: the list's own viewport, never the cell.
     *
     * <p>Binding a row to its {@link ListCell}'s width -- which is what this
     * replaces -- is a feedback loop, and a slow one, so it looked like it
     * worked. A {@code ListCell}'s own preferred width is its graphic's plus
     * its 24px of horizontal padding, and a {@code ListView} sizes every cell
     * to the widest preferred width in the list. Bind the graphic back to the
     * cell and each layout pass adds another 24px: measured in the running
     * app, a 606px column rendering 1437px cells, with the hunk-card frames
     * off the right edge behind a horizontal scrollbar the reader had to use
     * to see the borders of cards whose content was short.</p>
     *
     * <p>The viewport is a {@code BorderPane}-style slot -- its width is
     * handed down by the skin and does not depend on the cells -- so reading
     * it here cannot feed back.</p>
     */
    private final javafx.beans.property.DoubleProperty viewportWidth =
            new javafx.beans.property.SimpleDoubleProperty();

    /** Fallback allowance for the vertical scrollbar, until the skin exists to measure. */
    private static final double VERTICAL_SCROLLBAR_ALLOWANCE = 16;

    /**
     * Notified when a diff resolves, with the scope it resolved for.
     *
     * <p>The scope id is the point: a bare "a diff landed" signal left every
     * consumer reading whatever diff happened to be current, which is how an
     * intent rail came to show one scope's files beside another's header.</p>
     */
    private java.util.function.BiConsumer<String, DiffOutcome> onDiffResolved = (scopeId, outcome) -> { };

    private ReviewScope scope;

    /**
     * The diff exactly as loaded (or supplied), before the untracked filter.
     * Kept in full so toggling the filter is a re-render, not a re-diff --
     * see {@link #publishDisplayed(String)} for why this matters beyond
     * speed.
     */
    private UnifiedDiff fullDiff = new UnifiedDiff(List.of());

    /**
     * What is actually rendered and published: {@link #fullDiff} with the
     * untracked-filter applied for the current scope. Every reader outside
     * this class -- rows, the summary count, the symbol index, and
     * {@link #onDiffResolved} -- must be built from this field and never
     * from {@link #fullDiff} directly. The two diverge exactly while a
     * toggle is off, and that is the one moment a caller reading the wrong
     * one produces the defect this toggle exists to avoid: an intent rail
     * listing files a column has filtered out of view. See
     * {@link #publishDisplayed(String)}.
     */
    private UnifiedDiff displayedDiff = new UnifiedDiff(List.of());

    /**
     * The scope id whose diff is currently displayed -- distinct from
     * {@link #scope}, which {@link #showDiff} deliberately leaves
     * {@code null} while still publishing under the scope it read for. The
     * untracked toggle needs to know whose preference to read and write
     * even in that case.
     */
    private String displayedScopeId;

    /**
     * Per-scope untracked-inclusion preference, in-memory for the session
     * only (a {@code Map<String, Boolean>}, not persisted to
     * {@code ApplicationState}). A scope with no entry defaults to
     * included -- see the class javadoc on why that default is not
     * negotiable. Keyed by scope id so walking the queue with {@code j}/
     * {@code k} does not reset a decision made on one item.
     */
    private final java.util.Map<String, Boolean> includeUntrackedByScope = new java.util.HashMap<>();

    /**
     * The symbol lens's index, rebuilt with the diff. Local, never MCP: see
     * {@link SymbolIndex}.
     */
    private SymbolIndex symbolIndex = SymbolIndex.of(new UnifiedDiff(List.of()));

    /** The one open lens popover, so a second click replaces it rather than stacking. */
    private Popup lensPopup;
    private boolean showContext = true;
    private final Set<ReviewDiffRow.RunKey> expandedRuns = new HashSet<>();

    /**
     * The intent the column is filtered to, or {@code null} for the whole
     * scope. Selecting an intent in the rail used only to scroll this column,
     * which left the rail looking like decoration on a 45-file diff: the
     * reader clicked intent 12 and got the same wall of code they were
     * already looking at.
     */
    private app.drydock.review.ReviewIntent intentFilter;

    private CommentSink commentSink = annotation -> { };

    /**
     * The open composer's anchor, or {@code null} for none. One at a time: a
     * second gutter click moves it rather than opening another, because two
     * open drafts give the reader no way to tell which one Enter would send.
     */
    private ReviewDiffRow.Composer composerRow;

    /**
     * The composer's node, owned by the column rather than by the cell.
     *
     * <p>Cells are recycled as the list scrolls, so a composer built inside
     * {@code updateItem} would lose its half-typed draft the moment it left
     * the viewport and came back. Holding the node here means the cell only
     * ever adopts it.</p>
     */
    private Node composerNode;

    /** The composer's text area, kept so Escape and submit can reach it. */
    private javafx.scene.control.TextArea composerInput;

    /**
     * The gutter selection's anchor: the row index a plain click, the start
     * of a shift-click, or the start of a drag began at. {@code -1} for none.
     *
     * <p>Resolved against the {@link #rows} {@code ObservableList}, never
     * against a {@code ListCell} index -- cell indices are reassigned as a
     * virtualized {@code ListView} recycles cells, so they do not name a
     * stable row the way a position in {@link #rows} does.</p>
     */
    private int selectionAnchorIndex = -1;

    /**
     * The gutter keys currently painted {@code review-line-selected}, as
     * {@code file + " " + lineKey}. The file is part of the key on purpose:
     * two files can share a line number, and painting by a bare line key
     * would tint that number in every file, not just the one being
     * commented on.
     */
    private Set<String> selectedKeys = Set.of();

    /**
     * Set by the {@code whole scope} toggle: the reader has asked to see past
     * the selected intent. Cleared whenever a different intent is selected,
     * so the escape hatch is per-look rather than a mode that silently
     * outlives the intent it was opened from.
     */
    private boolean showWholeScope;

    /**
     * Guards against a slow diff of a scope the user has already navigated
     * away from overwriting a newer one. Incremented on every request; a
     * completion whose token is stale is dropped.
     */
    private long requestToken;

    ReviewDiffColumn(DiffService diffService, ExplorerBridge explorerBridge) {
        this.diffService = diffService;
        this.explorerBridge = explorerBridge;
        getStyleClass().addAll("review-diff-column", ReviewDensity.COZY.styleClass());

        summaryLabel.getStyleClass().add("review-diff-summary");
        contextToggle.getStyleClass().add("review-chip-button");
        contextToggle.setTooltip(new Tooltip("Show or hide unchanged lines (c)"));
        contextToggle.setOnAction(e -> toggleContext());
        untrackedToggle.getStyleClass().add("review-chip-button");
        untrackedToggle.setTooltip(new Tooltip(
                "Show or hide files that are new and not yet git-added"));
        untrackedToggle.setOnAction(e -> toggleUntracked());
        untrackedToggle.setVisible(false);
        untrackedToggle.setManaged(false);
        scopeToggle.getStyleClass().add("review-chip-button");
        scopeToggle.setOnAction(e -> toggleWholeScope());
        scopeToggle.setVisible(false);
        scopeToggle.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(9, summaryLabel, spacer, scopeToggle, untrackedToggle, contextToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("review-diff-header");
        setTop(header);

        list.getStyleClass().add("review-diff-list");
        list.setFocusTraversable(false);
        list.setCellFactory(view -> new DiffCell());
        // Long lines wrap; the column never scrolls sideways. See
        // viewportWidth for what this replaces.
        list.skinProperty().addListener((obs, old, skin) -> bindViewportWidth());
        bindViewportWidth();
        setCenter(list);

        updateContextToggle();
    }

    /**
     * Tracks the list's real viewport once its skin exists, falling back to
     * the list's width less a scrollbar allowance until then. The fallback
     * matters: rows are laid out before the first skin pulse, and a viewport
     * width of zero there would collapse every row to nothing.
     */
    private void bindViewportWidth() {
        Node viewport = list.lookup(".viewport");
        viewportWidth.unbind();
        if (viewport instanceof Region region) {
            viewportWidth.bind(region.widthProperty());
        } else {
            viewportWidth.bind(list.widthProperty().subtract(VERTICAL_SCROLLBAR_ALLOWANCE));
        }
    }

    // ---- scope + loading ----------------------------------------------------

    /**
     * Renders {@code newScope}'s diff, off the FX thread. Re-selecting the
     * same scope is a no-op so that walking the queue with {@code j}/{@code k}
     * and coming back does not re-run git.
     */
    void setScope(ReviewScope newScope) {
        if (scope != null && newScope != null && scope.id().equals(newScope.id())) {
            return;
        }
        if (newScope != null && !newScope.diffable()) {
            throw new IllegalArgumentException(
                    "not diffable (no checkout): " + newScope.id());
        }
        scope = newScope;
        expandedRuns.clear();
        // The outgoing scope's intent must not filter the incoming scope's
        // diff: its hunk ids name files that are not in it, so the column
        // would render empty until the new selection caught up.
        intentFilter = null;
        showWholeScope = false;
        // Hidden for the duration of the load: its visibility reflects
        // whether the OUTGOING scope's diff had untracked files, which says
        // nothing about the incoming one. applyDiff() re-derives it once the
        // new diff is actually known.
        untrackedToggle.setVisible(false);
        untrackedToggle.setManaged(false);
        if (newScope == null) {
            fullDiff = new UnifiedDiff(List.of());
            displayedScopeId = null;
            displayedDiff = fullDiff;
            showMessage("Nothing selected.");
            return;
        }
        onDiffResolved.accept(newScope.id(), new DiffOutcome.Diffing());
        reload();
    }

    /** Re-runs the diff for the current scope (a new commit, or a manual refresh). */
    void reload() {
        if (scope == null) {
            return;
        }
        ReviewScope requested = scope;
        long token = ++requestToken;
        showMessage("Diffing…");
        DiffScope diffScope = requested.kind() == ReviewScope.Kind.WORKING_TREE
                ? DiffScope.WORKING_TREE
                : DiffScope.BASE;
        diffService.diff(requested.diffRoot(), diffScope, requested.base(),
                        DiffService.REVIEW_CONTEXT_LINES)
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (token != requestToken) {
                        return; // superseded by a newer scope selection
                    }
                    if (failure != null) {
                        LOG.log(Level.DEBUG, "Diff failed for " + requested.diffRoot(), failure);
                        fullDiff = new UnifiedDiff(List.of());
                        displayedScopeId = null;
                        displayedDiff = fullDiff;
                        String message = UiErrors.unwrap(failure).getMessage();
                        showMessage("Could not diff: " + message);
                        onDiffResolved.accept(requested.id(), new DiffOutcome.Failed(message));
                        return;
                    }
                    applyDiff(result, requested.id());
                }));
    }

    // ---- presentation state -------------------------------------------------

    /**
     * Notified when a diff resolves, with the scope it resolved for.
     *
     * <p>The scope id is the point: a bare "a diff landed" signal left every
     * consumer reading whatever diff happened to be current, which is how an
     * intent rail came to show one scope's files beside another's header.</p>
     */
    void setOnDiffResolved(java.util.function.BiConsumer<String, DiffOutcome> handler) {
        this.onDiffResolved = handler == null ? (scopeId, outcome) -> { } : handler;
    }

    /** Where gutter comments go; set once by the destination. */
    void setCommentSink(CommentSink sink) {
        if (sink != null) {
            this.commentSink = sink;
        }
    }

    // ---- the gutter comment composer ---------------------------------------

    /**
     * Opens a single-line composer at {@code file}:{@code lineKey}, or closes
     * it if it is already open there exactly -- a second click on the same
     * gutter is the reader changing their mind, not a request for a second
     * composer. This is the plain-click path; a range comes from
     * {@link #finalizeSelection}.
     */
    private void toggleComposer(String file, String lineKey) {
        if (composerRow != null && composerRow.file().equals(file)
                && composerRow.startKey().equals(lineKey) && composerRow.endKey().equals(lineKey)) {
            closeComposer();
            return;
        }
        openComposer(file, lineKey, lineKey);
    }

    /**
     * Opens the composer anchored to {@code startKey}..{@code endKey} in
     * {@code file}, replacing any composer already open elsewhere -- see
     * {@link #composerRow}'s javadoc for why there is never a second one.
     */
    private void openComposer(String file, String startKey, String endKey) {
        composerRow = new ReviewDiffRow.Composer(file, startKey, endKey);
        composerNode = buildComposer(composerRow);
        insertComposerRow();
        if (composerInput != null) {
            composerInput.requestFocus();
        }
    }

    /** Closes the composer and discards its draft. Part of Escape's unwind order. */
    void closeComposer() {
        if (composerRow == null) {
            return;
        }
        composerRow = null;
        composerNode = null;
        composerInput = null;
        rows.removeIf(ReviewDiffRow.Composer.class::isInstance);
        selectionAnchorIndex = -1;
        clearSelectionPaint();
    }

    /** Whether a composer is open (Escape unwinds topmost-first). */
    boolean composerOpen() {
        return composerRow != null;
    }

    /**
     * Puts the composer row directly under the range's END line -- the line
     * closest to where the reader's gesture finished, whether that was a
     * plain click, the far end of a shift-click, or the far end of a drag.
     * Any previously open composer is removed first, so the row list can
     * never carry two.
     */
    private void insertComposerRow() {
        rows.removeIf(ReviewDiffRow.Composer.class::isInstance);
        if (composerRow == null) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ReviewDiffRow.Line line
                    && line.file().equals(composerRow.file())
                    && line.lineKey().equals(composerRow.endKey())) {
                rows.add(i + 1, composerRow);
                return;
            }
        }
        // The anchor line is not rendered (a filter or the row cap moved it).
        // Dropping the composer silently would read as a dead click, so it is
        // abandoned explicitly instead.
        composerRow = null;
        composerNode = null;
        composerInput = null;
    }

    /** The composer's {@code file line N} / {@code file lines N–M} label text. */
    private static String composerWhere(ReviewDiffRow.Composer row) {
        // The file's name, not its path: the path is already on the hunk
        // header a few rows up, and spelling it out again here squeezed the
        // buttons down to "Can.." and "Comm..".
        String name = row.file().substring(row.file().lastIndexOf('/') + 1);
        if (row.startKey().equals(row.endKey())) {
            return name + " line " + lineNumber(row.endKey());
        }
        return name + " lines " + lineNumber(row.startKey()) + "–" + lineNumber(row.endKey());
    }

    /** A stable line key's human-readable number, or the raw key if it is not one of {@code n}/{@code o}. */
    private static String lineNumber(String lineKey) {
        return lineKey.startsWith("n") || lineKey.startsWith("o") ? lineKey.substring(1) : lineKey;
    }

    private Region buildComposer(ReviewDiffRow.Composer row) {
        Label where = new Label(composerWhere(row));
        where.getStyleClass().add("review-composer-where");
        // Truncates before the buttons do: the label is context, the buttons
        // are the actions.
        where.setMinWidth(0);

        javafx.scene.control.TextArea input = new javafx.scene.control.TextArea();
        input.getStyleClass().add("review-composer-input");
        input.setPromptText("Leave a comment on this line…");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        composerInput = input;

        Button save = new Button("Comment");
        save.getStyleClass().addAll("review-chip-button", "review-composer-save");
        save.setDefaultButton(false);
        save.setFocusTraversable(false);
        save.setOnAction(e -> submitComposer());

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("review-chip-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(e -> closeComposer());

        // Never shrink below their labels. A button reading "Comm.." is worse
        // than no button: the reader cannot tell what it does.
        save.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setMinWidth(Region.USE_PREF_SIZE);

        // ⌘/Ctrl+Enter sends, Escape cancels: a plain Enter has to stay a
        // newline, because a comment on a diff is usually more than one line.
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && event.isShortcutDown()) {
                submitComposer();
                event.consume();
            } else if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                closeComposer();
                event.consume();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, where, spacer, cancel, save);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, input, actions);
        box.getStyleClass().add("review-composer");
        return box;
    }

    /**
     * Sends the draft. A blank one is a no-op rather than an empty comment.
     *
     * <p>{@link #displayedScopeId} rather than {@link #scope}, for the same
     * reason {@link #toggleUntracked()} reads it: a composer can be open
     * while showing a supplied patch-only diff, which deliberately leaves
     * {@code scope} {@code null} (see {@link #showDiff}) while still
     * publishing under the scope it was read for.</p>
     */
    private void submitComposer() {
        if (composerRow == null || composerInput == null) {
            return;
        }
        String body = composerInput.getText().strip();
        if (body.isEmpty()) {
            closeComposer();
            return;
        }
        String scopeId = displayedScopeId;
        if (scopeId == null) {
            closeComposer();
            return;
        }
        // NIT: a human note on a line is not a blocking finding, and any
        // severity that blocks approval would let leaving a remark silently
        // prevent the reviewer approving their own review.
        ReviewAnnotation annotation = ReviewAnnotation.human(scopeId, composerRow.file(),
                composerRow.startKey(), composerRow.endKey(),
                new ReviewAnnotation.Message("You", Instant.now(), body), Severity.NIT);
        commentSink.addComment(annotation);
        closeComposer();
    }

    /** Supplies the {@code ◆n} pins; set once by the destination. */
    void setPinSource(PinSource source) {
        if (source != null) {
            this.pinSource = source;
        }
    }

    /**
     * Bumped by {@link #refreshPins()} to invalidate {@link DiffCell}'s
     * per-cell graphic cache. A row (a {@code record}) compares equal to
     * itself across an unrelated rebuild whenever its fields happen to
     * match, which is exactly what makes the cache safe to keep across a
     * routine layout pass -- but a pin marker is read from {@link
     * #pinSource} at build time, not stored on the row, so an unchanged row
     * can still need a rebuild when the finding set under it changes. The
     * generation counter is what tells the cache "rebuild anyway" without
     * forcing every OTHER unrelated cell to rebuild too.
     */
    private long renderGeneration;

    /** Re-renders the rows so pin markers pick up a changed finding set. */
    void refreshPins() {
        renderGeneration++;
        refreshRender();
    }

    /**
     * Re-renders the rows in place -- a full swap is one list operation, and
     * the rows themselves are unchanged data, so a virtualized {@code
     * ListView} only pays for the currently visible cells, not the whole
     * list. Used whenever something a cell reads (a pin) changed without the
     * underlying row objects changing; {@link DiffCell}'s cache (keyed on
     * {@link #renderGeneration}) is what makes this cheap even though it
     * still touches the whole {@link #rows} list.
     */
    private void refreshRender() {
        List<ReviewDiffRow> current = List.copyOf(rows);
        rows.setAll(List.of());
        rows.setAll(current);
    }

    // ---- gutter range selection ---------------------------------------------

    /**
     * Extends the in-progress selection to {@code focusIndex} against {@link
     * #selectionAnchorIndex}, resolves it through {@link DiffLineSelection}
     * (which alone owns the one-file/one-hunk clamp -- GitHub rejects a
     * cross-hunk comment and the whole atomic review with it, so that rule
     * is never re-implemented here), and repaints {@link #selectedKeys}.
     */
    private void extendSelection(int focusIndex) {
        if (selectionAnchorIndex < 0) {
            return;
        }
        DiffLineSelection.resolve(rows, selectionAnchorIndex, focusIndex)
                .ifPresentOrElse(this::paintSelection, this::clearSelectionPaint);
    }

    /** Repaints {@link #selectedKeys} to every rendered line between a resolved range's ends. */
    private void paintSelection(DiffLineSelection.Range range) {
        int start = indexOfLine(range.file(), range.startKey());
        int end = indexOfLine(range.file(), range.endKey());
        if (start < 0 || end < 0) {
            clearSelectionPaint();
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
            if (rows.get(i) instanceof ReviewDiffRow.Line line) {
                keys.add(line.file() + " " + line.lineKey());
            }
        }
        if (keys.equals(selectedKeys)) {
            return;
        }
        selectedKeys = keys;
        applySelectionPaint();
    }

    private void clearSelectionPaint() {
        if (selectedKeys.isEmpty()) {
            return;
        }
        selectedKeys = Set.of();
        applySelectionPaint();
    }

    /**
     * Toggles {@code review-line-selected} directly on the gutter {@code
     * Label}s already in the scene, keyed by the {@code file + " " +
     * lineKey} stamped into each one's {@link Node#userData} by {@link
     * #buildLine}.
     *
     * <p>This is deliberately NOT {@link #refreshRender()}: that swaps
     * {@link #rows} to empty and back, which detaches every cell's graphic
     * -- including the gutter the pointer is currently pressed on. Do that
     * from inside {@code setOnMousePressed} and the pressed {@code Label} is
     * no longer in the scene when the button comes up, so JavaFX never
     * delivers {@code MOUSE_CLICKED} to it, and a {@code DRAG_DETECTED} that
     * follows throws {@code IllegalStateException} from {@code
     * startFullDrag()} on a node that is not in the scene. Repainting live
     * cells in place keeps the gesture's target node attached throughout.</p>
     */
    private void applySelectionPaint() {
        // Deferred rather than applied synchronously: this runs from inside
        // gesture handlers (setOnMousePressed, setOnMouseDragEntered), and
        // mutating the PRESSED node's own style class while JavaFX is still
        // in the middle of dispatching that same press was observed (via a
        // real-pointer TestFX probe) to occasionally suppress the click or
        // full-drag-gesture bookkeeping the press was starting -- the
        // gesture's own target getting its CSS state (and therefore a CSS
        // layout pass) touched out from under it, mid-dispatch. One pulse
        // later, the press has already finished being handled, so the paint
        // is invisible to the gesture that triggered it.
        Platform.runLater(() -> {
            for (Node node : list.lookupAll(".review-code-gutter")) {
                boolean selected = node.getUserData() instanceof String key && selectedKeys.contains(key);
                if (selected) {
                    if (!node.getStyleClass().contains("review-line-selected")) {
                        node.getStyleClass().add("review-line-selected");
                    }
                } else {
                    node.getStyleClass().remove("review-line-selected");
                }
            }
        });
    }

    private int indexOfLine(String file, String lineKey) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ReviewDiffRow.Line line
                    && line.file().equals(file) && line.lineKey().equals(lineKey)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Ends a gutter gesture (a plain click, the release of a shift-click, or
     * the release of a drag) against {@code row}: resolves the final range
     * and opens the composer there, or closes it if the range is exactly
     * where the composer already is -- the "second click on the same gutter
     * is the reader changing their mind" rule, generalised to a range.
     *
     * <p>{@code rows.indexOf(row)} can legitimately miss: {@code row} is
     * whatever line a gutter {@code Label}'s handler closed over when it was
     * built, and a rebuild since (a filter, the context toggle, a re-diff)
     * can have dropped that exact row object from {@link #rows} before this
     * gesture's release arrives. Rather than coerce a miss to index 0 and
     * silently re-anchor the comment to the hunk's first line, it is treated
     * as nothing happened.</p>
     */
    private void finalizeSelection(ReviewDiffRow.Line row) {
        int index = rows.indexOf(row);
        if (index < 0 || selectionAnchorIndex < 0) {
            return;
        }
        DiffLineSelection.resolve(rows, selectionAnchorIndex, index).ifPresent(range -> {
            if (composerRow != null && composerRow.file().equals(range.file())
                    && composerRow.startKey().equals(range.startKey())
                    && composerRow.endKey().equals(range.endKey())) {
                closeComposer();
            } else {
                openComposer(range.file(), range.startKey(), range.endKey());
            }
        });
    }

    /** Diagnostic/test-only: the gutter keys currently painted selected. */
    Set<String> diagSelectedKeys() {
        return Set.copyOf(selectedKeys);
    }

    /**
     * Diagnostic/test-only: the current selection anchor's row index, or
     * {@code -1} for none. Exists so a stale-index guard can be proven by
     * what it PREVENTS -- a stale gesture event overwriting a valid anchor
     * -- rather than by an incidental empty result that a missing guard
     * would also happen to produce.
     */
    int diagSelectionAnchorIndex() {
        return selectionAnchorIndex;
    }

    /** Scrolls to the row anchored at {@code lineKey} in {@code file} (card → line linkage). */
    void revealLine(String file, String lineKey) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ReviewDiffRow.Line line
                    && line.file().equals(file) && line.lineKey().equals(lineKey)) {
                list.scrollTo(Math.max(0, i - 3));
                return;
            }
        }
    }

    /**
     * Scrolls to the {@code hunkIndex}-th hunk card of {@code file} -- what
     * selecting an intent brings into view. Falls back to the file's first
     * card when it has fewer hunks than that (the diff was re-read and the
     * grouping is one generation behind).
     *
     * <p>Returns whether the file was reached. It can genuinely be absent:
     * the intent rail is built from the whole diff while these rows stop at
     * {@link #MAX_RENDERED_ROWS}, so in a large diff every intent past the
     * cut has no card to scroll to. That used to return silently, which
     * read as a dead click -- selecting an intent appeared to do nothing at
     * all. Now the truncation notice is scrolled into view instead, because
     * it is the one row that explains why the file is not there.</p>
     */
    boolean revealHunk(String file, int hunkIndex) {
        int firstCard = -1;
        int seen = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof ReviewDiffRow.HunkHeader header)
                    || !header.file().equals(file)) {
                continue;
            }
            if (firstCard < 0) {
                firstCard = i;
            }
            if (seen++ == hunkIndex) {
                list.scrollTo(i);
                return true;
            }
        }
        if (firstCard >= 0) {
            list.scrollTo(firstCard);
            return true;
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i) instanceof ReviewDiffRow.Truncation) {
                list.scrollTo(i);
                return false;
            }
        }
        return false;
    }

    /**
     * Renders a diff that did not come from this column's own git call --
     * {@code gh pr diff} for the "Read the patch only" path, which has no
     * checkout to run git in. Clears the scope so a later reload cannot
     * overwrite it with a local diff of the wrong tree, and publishes under
     * the scope it was read FOR, which is not the same thing as adopting
     * that scope as the column's live one.
     */
    void showDiff(ReviewScope forScope, UnifiedDiff supplied) {
        scope = null;
        requestToken++;
        expandedRuns.clear();
        applyDiff(supplied, forScope.id());
    }

    /** {@code d}: applies a density by swapping the root's style class (spec §4.8). */
    void setDensity(ReviewDensity density) {
        for (ReviewDensity value : ReviewDensity.values()) {
            getStyleClass().remove(value.styleClass());
        }
        getStyleClass().add(density.styleClass());
    }

    /**
     * Filters the column to {@code intent}, or to the whole scope when it is
     * {@code null}. Re-selecting the same intent is a no-op so that walking
     * the rail with {@code [}/{@code ]} and coming back does not discard a
     * "whole scope" the reader asked for.
     */
    void setIntent(app.drydock.review.ReviewIntent intent) {
        String was = intentFilter == null ? null : intentFilter.id();
        String now = intent == null ? null : intent.id();
        if (java.util.Objects.equals(was, now)) {
            return;
        }
        intentFilter = intent;
        showWholeScope = false;
        expandedRuns.clear();
        rebuild();
    }

    /** The {@code whole scope} / {@code this intent} chip. */
    private void toggleWholeScope() {
        showWholeScope = !showWholeScope;
        rebuild();
    }

    /** Whether the rows are currently narrowed to one intent. */
    private boolean filtering() {
        return intentFilter != null && !showWholeScope;
    }

    private ReviewDiffRows.HunkFilter hunkFilter() {
        if (!filtering()) {
            return ReviewDiffRows.HunkFilter.ALL;
        }
        app.drydock.review.ReviewIntent intent = intentFilter;
        return intent::containsHunk;
    }

    /** {@code c}: shows or hides unchanged lines entirely. */
    void toggleContext() {
        showContext = !showContext;
        updateContextToggle();
        rebuild();
    }

    private void updateContextToggle() {
        contextToggle.setText(showContext ? "context" : "changed only");
    }

    /**
     * Called once a diff (git-run or supplied) is known, from both
     * {@link #reload()} and {@link #showDiff}. Establishes the toggle's
     * visibility -- hidden when {@code loaded} has no untracked files at all,
     * since a dead toggle on every branch review is clutter -- and hands off
     * to {@link #publishDisplayed(String)} to do the actual filter-render-
     * publish.
     */
    private void applyDiff(UnifiedDiff loaded, String scopeId) {
        fullDiff = loaded;
        boolean hasUntracked = loaded.files().stream().anyMatch(UnifiedDiff.FileDiff::untracked);
        untrackedToggle.setVisible(hasUntracked);
        untrackedToggle.setManaged(hasUntracked);
        untrackedToggle.setText(includeUntracked(scopeId) ? "untracked" : "no untracked");
        publishDisplayed(scopeId);
    }

    /**
     * {@code u}-equivalent: toggles whether untracked files are included for
     * the currently displayed scope, then re-renders and re-publishes from
     * the retained {@link #fullDiff} -- no git call. {@link #displayedScopeId}
     * rather than {@link #scope} is the key, because {@link #showDiff}
     * deliberately clears {@code scope} while still displaying a diff.
     */
    private void toggleUntracked() {
        if (displayedScopeId == null) {
            return;
        }
        boolean newValue = !includeUntracked(displayedScopeId);
        includeUntrackedByScope.put(displayedScopeId, newValue);
        untrackedToggle.setText(newValue ? "untracked" : "no untracked");
        publishDisplayed(displayedScopeId);
    }

    /** A scope with no recorded preference includes untracked files: see the field javadoc on why. */
    private boolean includeUntracked(String scopeId) {
        return includeUntrackedByScope.getOrDefault(scopeId, true);
    }

    // ---- rendering ----------------------------------------------------------

    /**
     * The one place {@link #displayedDiff} is assigned from {@link #fullDiff}
     * -- filters, renders, and publishes together so the three can never
     * drift apart. Splitting this into "filter for rendering" and "filter
     * for publishing" as two call sites was the shape of the original "rail
     * disagrees with column" defect: it is easy for one call site to keep up
     * with a toggle change and the other to be forgotten. There is only one
     * call site here on purpose.
     */
    private void publishDisplayed(String scopeId) {
        displayedScopeId = scopeId;
        displayedDiff = includeUntracked(scopeId)
                ? fullDiff
                : new UnifiedDiff(fullDiff.files().stream()
                        .filter(file -> !file.untracked())
                        .toList());
        symbolIndex = SymbolIndex.of(displayedDiff);
        rebuild();
        onDiffResolved.accept(scopeId, new DiffOutcome.Loaded(displayedDiff));
    }

    private void rebuild() {
        rows.setAll(ReviewDiffRows.build(displayedDiff, buildOptions()));
        // Re-anchored rather than dropped: a rebuild happens for reasons that
        // have nothing to do with the draft (a pin refresh, the context
        // toggle), and losing typed text to one of those is the kind of thing
        // a reader never forgives.
        insertComposerRow();
        updateScopeToggle();
        updateSummary();
        list.scrollTo(0);
    }

    private ReviewDiffRows.Options buildOptions() {
        return new ReviewDiffRows.Options(showContext, expandedRuns, MAX_RENDERED_ROWS, hunkFilter());
    }

    /**
     * The escape hatch's label. It names what clicking it WOULD show, not
     * what is showing -- a chip reading "this intent" while the intent is
     * already the only thing on screen says nothing about what it does.
     */
    private void updateScopeToggle() {
        boolean present = intentFilter != null;
        scopeToggle.setVisible(present);
        scopeToggle.setManaged(present);
        if (!present) {
            return;
        }
        scopeToggle.setText(showWholeScope
                ? "intent " + intentFilter.number() + " only"
                : "whole scope");
        scopeToggle.setTooltip(new Tooltip(showWholeScope
                ? "Narrow back to intent " + intentFilter.number() + ": " + intentFilter.title()
                : "Show every hunk in this scope, not just intent " + intentFilter.number()));
    }

    private void showMessage(String text) {
        rows.setAll(List.of(new ReviewDiffRow.Message(text)));
        updateSummary();
    }

    /**
     * The header count. While the column is filtered this counts what is
     * ACTUALLY on screen and names the intent it belongs to; the scope's own
     * totals would contradict the rows directly below them.
     */
    private void updateSummary() {
        if (filtering()) {
            // Hunks, not files: an intent's title often already carries its
            // file count ("drydock/git · 4 files"), and appending another one
            // read as "4 files · 4 files".
            long hunks = rows.stream().filter(ReviewDiffRow.HunkHeader.class::isInstance).count();
            summaryLabel.setText("intent " + intentFilter.number() + "  ·  " + intentFilter.title()
                    + "  ·  " + hunks + (hunks == 1 ? " hunk" : " hunks"));
            return;
        }
        int files = displayedDiff.files().size();
        int insertions = displayedDiff.files().stream().mapToInt(UnifiedDiff.FileDiff::insertions).sum();
        int deletions = displayedDiff.files().stream().mapToInt(UnifiedDiff.FileDiff::deletions).sum();
        summaryLabel.setText(files == 0
                ? ""
                : files + (files == 1 ? " file" : " files") + "  ·  +" + insertions + " −" + deletions);
    }

    /** Expands one collapsed run in place; a full rebuild is a single list swap. */
    private void expandRun(ReviewDiffRow.CollapsedRun run) {
        expandedRuns.add(run.key());
        // Deliberately not rebuild(): that scrolls back to the top, and
        // expanding a run is the one action whose whole point is to stay
        // where the reader already is.
        rows.setAll(ReviewDiffRows.build(displayedDiff, buildOptions()));
    }

    /**
     * Renders one {@link ReviewDiffRow}. Cheap enough to rebuild on every
     * item change: a row is a handful of labels and one {@link TextFlow}.
     */
    private final class DiffCell extends ListCell<ReviewDiffRow> {

        /**
         * The last row this cell actually built a graphic for, and that
         * graphic -- so a redundant {@code updateItem} call for the SAME row
         * can reuse it instead of rebuilding. See {@link #updateItem} for why
         * this exists: a real bug, not an optimization. {@code Composer} rows
         * are deliberately never cached here (see the switch below); {@code
         * cachedGeneration} is compared against {@link #renderGeneration} so
         * a pin refresh can still force a rebuild the cache would otherwise
         * suppress.
         */
        private ReviewDiffRow cachedRow;
        private long cachedGeneration = -1;
        private Node cachedNode;

        DiffCell() {
            getStyleClass().add("review-diff-cell");
        }

        @Override
        protected void updateItem(ReviewDiffRow row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeIf(styleClass -> styleClass.startsWith("card-"));
            if (empty || row == null) {
                setGraphic(null);
                cachedRow = null;
                cachedGeneration = -1;
                cachedNode = null;
                return;
            }
            getStyleClass().add("card-" + row.edge().name().toLowerCase(java.util.Locale.ROOT));
            // updateItem is called again for a row that has not changed at
            // all -- confirmed by a real-pointer probe, from ordinary layout
            // passes (VirtualFlow re-associating this cell, the viewport
            // width binding firing), not only on a genuine row swap.
            // Rebuilding unconditionally here discarded the exact gutter
            // Label a click was mid-press on, so any layout pass landing
            // between a real MOUSE_PRESSED and its MOUSE_RELEASED silently
            // ate the click -- on main's single-line composer too, not just
            // the range gutter added here. Row equality (a record, so this
            // is a value comparison, not identity) is what makes reuse safe:
            // a genuine content change never matches the cache and still
            // rebuilds below. Composer is excluded because it already reuses
            // the view-owned composerNode directly, and does so even across
            // a DIFFERENT row (a re-anchor to a new range).
            if (!(row instanceof ReviewDiffRow.Composer) && row.equals(cachedRow)
                    && cachedGeneration == renderGeneration && cachedNode != null) {
                setGraphic(cachedNode);
                return;
            }
            Node node = switch (row) {
                case ReviewDiffRow.HunkHeader header -> buildHunkHeader(header);
                case ReviewDiffRow.Line line -> buildLine(line);
                case ReviewDiffRow.CollapsedRun run -> buildCollapsedRun(run);
                // The view-owned node, never a fresh one: rebuilding it here
                // would discard the draft every time the cell was recycled.
                case ReviewDiffRow.Composer ignored ->
                        composerNode != null ? composerNode : new Region();
                case ReviewDiffRow.Truncation truncation ->
                        message("… diff truncated at " + truncation.limit() + " rows");
                case ReviewDiffRow.Message text -> message(text.text());
            };
            if (node instanceof Region region) {
                // Width only, and to the VIEWPORT -- never to this cell. See
                // ReviewDiffColumn.viewportWidth: binding a row back to the
                // cell that sizes itself from the row is a feedback loop that
                // grew the cards 24px per layout pass until they hung off the
                // right edge of the column.
                //
                // Height is deliberately never bound: a fixed row height was
                // the bug that hid code behind no scrollbar, and rows have to
                // grow now that long lines wrap.
                javafx.beans.binding.DoubleBinding rowWidth =
                        javafx.beans.binding.Bindings.createDoubleBinding(
                                () -> Math.max(0, viewportWidth.get()
                                        - getInsets().getLeft() - getInsets().getRight()),
                                viewportWidth, insetsProperty());
                region.prefWidthProperty().bind(rowWidth);
                // maxWidth is what makes the TextFlow wrap instead of running
                // off the side; without it prefWidth is only a suggestion a
                // wider child can overrule.
                region.maxWidthProperty().bind(rowWidth);
            }
            setGraphic(node);
            if (row instanceof ReviewDiffRow.Composer) {
                cachedRow = null;
                cachedGeneration = -1;
                cachedNode = null;
            } else {
                cachedRow = row;
                cachedGeneration = renderGeneration;
                cachedNode = node;
            }
        }
    }

    private Region buildHunkHeader(ReviewDiffRow.HunkHeader header) {
        Label file = new Label(header.file());
        file.getStyleClass().add("review-hunk-file");
        Label range = new Label(header.range());
        range.getStyleClass().add("review-hunk-range");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button explorer = new Button("⤢ Explorer");
        explorer.getStyleClass().add("review-hunk-explorer");
        explorer.setTooltip(new Tooltip("Open " + header.file() + " in the Explorer at line " + header.startLine()));
        explorer.setOnAction(e -> openInExplorer(header, explorer));

        List<Node> children = new ArrayList<>(List.of(file, range));
        // untracked wins when both are somehow true: "never committed" is
        // the more important fact to surface, and the combination should
        // not be constructible anyway (an untracked file has nothing in the
        // index to be staged).
        Label chip = header.untracked() ? chip("untracked", "review-hunk-chip-untracked")
                : header.staged() ? chip("staged", "review-hunk-chip-staged")
                : null;
        if (chip != null) {
            children.add(chip);
        }
        children.add(spacer);
        children.add(explorer);

        HBox row = new HBox(8, children.toArray(Node[]::new));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("review-hunk-header");
        return row;
    }

    private static Label chip(String text, String styleClass) {
        Label chip = new Label(text);
        chip.getStyleClass().addAll("review-hunk-chip", styleClass);
        return chip;
    }

    /**
     * The Explorer lives inside a session's tab, so a scope with no bound
     * session (or whose tab is closed) has nowhere to open the file. Says so
     * on the button rather than doing nothing when clicked.
     */
    private void openInExplorer(ReviewDiffRow.HunkHeader header, Button button) {
        if (scope == null) {
            return;
        }
        if (!explorerBridge.openFileAtLine(scope, Path.of(header.file()), header.startLine())) {
            button.setTooltip(new Tooltip("Open this scope's session first — the Explorer lives in it"));
            button.setDisable(true);
        }
    }

    private Region buildLine(ReviewDiffRow.Line row) {
        UnifiedDiff.Line line = row.line();

        Label oldNumber = new Label(line.oldLine().isPresent()
                ? String.valueOf(line.oldLine().getAsInt()) : "");
        oldNumber.getStyleClass().add("review-code-gutter");
        Label newNumber = new Label(line.newLine().isPresent()
                ? String.valueOf(line.newLine().getAsInt()) : "");
        newNumber.getStyleClass().add("review-code-gutter");
        // Clicking either gutter number opens the comment composer on this
        // line. Both, not just one: which of the two carries a number depends
        // on whether the line was added or deleted, and a reader aiming at
        // "the line numbers" should not have to know that.
        //
        // A plain click opens (or closes) a single line, exactly as before.
        // Shift-click and drag extend the anchor into a range, resolved
        // through DiffLineSelection so the one-file/one-hunk clamp is never
        // duplicated here. The click itself is self-contained (it sets the
        // anchor if this is not a shift-click, then finalizes) rather than
        // depending on the press having already run: a synthesized click with
        // no preceding press -- exactly how the existing gutter tests dispatch
        // -- still has to work.
        String selectionKey = row.file() + " " + row.lineKey();
        boolean selected = selectedKeys.contains(selectionKey);
        for (Label gutter : List.of(oldNumber, newNumber)) {
            gutter.getStyleClass().add("commentable");
            // Read back by applySelectionPaint() to repaint an already-built
            // Label in place, without touching the rows list -- see its
            // javadoc for why a live gesture cannot survive that.
            gutter.setUserData(selectionKey);
            if (selected) {
                gutter.getStyleClass().add("review-line-selected");
            }
            gutter.setOnMousePressed(e -> {
                int index = rows.indexOf(row);
                if (index < 0) {
                    return;
                }
                if (!(e.isShiftDown() && selectionAnchorIndex >= 0)) {
                    selectionAnchorIndex = index;
                }
                extendSelection(index);
            });
            // Must live in DRAG_DETECTED, never in the press handler above:
            // startFullDrag() throws IllegalStateException unless the call
            // is made from inside a DRAG_DETECTED handler, and calling it
            // eagerly on press killed the very first gutter click.
            gutter.setOnDragDetected(e -> {
                gutter.startFullDrag();
                e.consume();
            });
            gutter.setOnMouseDragEntered(e -> {
                int index = rows.indexOf(row);
                if (index < 0) {
                    return;
                }
                extendSelection(index);
            });
            gutter.setOnMouseDragReleased(e -> finalizeSelection(row));
            gutter.setOnMouseClicked(e -> {
                int index = rows.indexOf(row);
                if (index < 0) {
                    e.consume();
                    return;
                }
                if (!(e.isShiftDown() && selectionAnchorIndex >= 0)) {
                    selectionAnchorIndex = index;
                }
                extendSelection(index);
                finalizeSelection(row);
                e.consume();
            });
            gutter.setTooltip(new Tooltip("Comment on this line, or shift-click / drag to select a range"));
        }

        Label sign = new Label(switch (line.kind()) {
            case ADD -> "+";
            case DEL -> "−";
            case CONTEXT -> " ";
        });
        sign.getStyleClass().addAll("review-code-sign", switch (line.kind()) {
            case ADD -> "sign-add";
            case DEL -> "sign-del";
            case CONTEXT -> "sign-context";
        });

        TextFlow source = highlighted(row.file(), line.text());
        // Hgrow, now that a row is exactly as wide as the viewport rather
        // than as wide as the widest line in the whole diff. That is what
        // makes the TextFlow wrap a long line instead of running off the
        // side, and it puts the pin at the card's right edge where the design
        // wants it -- which was impossible while the card edge itself was
        // off-screen.
        HBox.setHgrow(source, Priority.ALWAYS);

        HBox box = new HBox(oldNumber, newNumber, sign, source);
        for (Pin pin : pinSource.pinsAt(row.file(), row.lineKey())) {
            // A filtered-out finding has no pin number -- the numbers are the
            // margin's render order, and it is not being rendered. It keeps a
            // bare diamond rather than borrowing a number it does not have:
            // the line still carries a finding, and "◆0" would be a lie.
            Button marker = new Button(pin.number() > 0 ? "◆" + pin.number() : "◆");
            marker.getStyleClass().addAll("review-line-pin", pin.severityStyleClass());
            if (pin.dimmed()) {
                marker.getStyleClass().add("dimmed");
            }
            marker.setTooltip(new Tooltip("Show finding " + pin.number() + " in the margin"));
            marker.setOnAction(e -> pinSource.focusFinding(pin));
            box.getChildren().add(marker);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("review-code-row", switch (line.kind()) {
            case ADD -> "row-add";
            case DEL -> "row-del";
            case CONTEXT -> "row-context";
        });
        return box;
    }

    /**
     * The line's source, split into styled runs by the shared lexer. Plain
     * {@link Text} nodes in a {@link TextFlow} rather than a {@code CodeArea}
     * per row: one editor control per diff line would be thousands of
     * controls, and these rows are read-only.
     */
    private TextFlow highlighted(String file, String text) {
        SyntaxHighlighter.Language language = SyntaxHighlighter.Language.fromFileName(file);
        List<Node> parts = new ArrayList<>();
        int last = 0;
        for (SyntaxHighlighter.Span span : SyntaxHighlighter.spans(text, language)) {
            if (span.start() > last) {
                parts.addAll(lensable(text.substring(last, span.start())));
            }
            Text styled = plain(text.substring(span.start(), span.start() + span.length()));
            styled.getStyleClass().add(span.styleClass());
            parts.add(styled);
            last = span.start() + span.length();
        }
        if (last < text.length()) {
            parts.addAll(lensable(text.substring(last)));
        }
        TextFlow flow = new TextFlow(parts.toArray(Node[]::new));
        flow.getStyleClass().add("review-code-text");
        return flow;
    }

    /**
     * Splits an unstyled run into identifiers the symbol index knows and the
     * text between them. Only the known ones get the dotted underline and a
     * click handler -- an underline on every word would mean nothing.
     */
    private List<Node> lensable(String text) {
        List<Node> parts = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(text);
        int last = 0;
        while (matcher.find()) {
            String word = matcher.group();
            if (!symbolIndex.hasEntry(word)) {
                continue;
            }
            if (matcher.start() > last) {
                parts.add(plain(text.substring(last, matcher.start())));
            }
            Text symbol = plain(word);
            symbol.getStyleClass().add("review-code-symbol");
            symbol.setOnMouseClicked(e -> showLens(word, symbol));
            parts.add(symbol);
            last = matcher.end();
        }
        if (last < text.length()) {
            parts.add(plain(text.substring(last)));
        }
        return parts;
    }

    /** The symbol-lens popover: kind, count, and every occurrence chipped in-diff / not touched. */
    private void showLens(String symbol, Node anchor) {
        symbolIndex.lookup(symbol).ifPresent(entry -> {
            hideLens();
            VBox content = new VBox(6);
            content.getStyleClass().add("review-lens");

            Label title = new Label(symbol);
            title.getStyleClass().add("review-lens-title");
            Label summary = new Label(entry.occurrences().size() + " occurrences · "
                    + entry.inDiffCount() + " on changed lines");
            summary.getStyleClass().add("review-lens-summary");
            Label caveat = new Label("Lexical index of this diff — occurrences, not resolved references.");
            caveat.getStyleClass().add("review-lens-caveat");
            caveat.setWrapText(true);
            content.getChildren().addAll(title, summary, caveat);

            for (SymbolIndex.Occurrence occurrence : entry.occurrences()) {
                Label chip = new Label(occurrence.inDiff() ? "in diff" : "not touched");
                chip.getStyleClass().addAll("review-lens-chip",
                        occurrence.inDiff() ? "in-diff" : "not-touched");
                Label where = new Label(occurrence.file() + ":" + occurrence.line());
                where.getStyleClass().add("review-lens-where");
                Button jump = new Button(occurrence.text().length() > 60
                        ? occurrence.text().substring(0, 59) + "…" : occurrence.text());
                jump.getStyleClass().add("review-lens-line");
                jump.setOnAction(e -> {
                    hideLens();
                    revealLine(occurrence.file(), "n" + occurrence.line());
                });
                HBox row = new HBox(6, chip, where);
                row.setAlignment(Pos.CENTER_LEFT);
                content.getChildren().addAll(row, jump);
            }

            ScrollPane scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setMaxHeight(320);
            scroll.getStyleClass().add("review-lens-scroll");

            lensPopup = new Popup();
            lensPopup.setAutoHide(true);
            lensPopup.getContent().add(scroll);
            var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
            if (bounds != null) {
                lensPopup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
            }
        });
    }

    /** Closes the lens popover; part of Escape's unwind order. */
    void hideLens() {
        if (lensPopup != null) {
            lensPopup.hide();
            lensPopup = null;
        }
    }

    /** Whether a lens popover is open (Escape unwinds topmost-first). */
    boolean lensOpen() {
        return lensPopup != null && lensPopup.isShowing();
    }

    private static Text plain(String text) {
        Text node = new Text(text);
        node.getStyleClass().add("review-code-span");
        return node;
    }

    private Region buildCollapsedRun(ReviewDiffRow.CollapsedRun run) {
        Button button = new Button("⋯ " + run.count() + " unchanged");
        button.getStyleClass().add("review-collapsed-run");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setTooltip(new Tooltip("Show these " + run.count() + " unchanged lines"));
        button.setOnAction(e -> expandRun(run));
        return button;
    }

    private static Region message(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("review-diff-message");
        label.setWrapText(true);
        HBox box = new HBox(label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Diagnostic-only: opens the composer on the first changed line rendered. */
    String diagOpenComposer() {
        for (ReviewDiffRow row : rows) {
            if (row instanceof ReviewDiffRow.Line line
                    && line.line().kind() != UnifiedDiff.Line.Kind.CONTEXT) {
                toggleComposer(line.file(), line.lineKey());
                return "composer on " + line.file() + " " + line.lineKey();
            }
        }
        return "no changed line to comment on";
    }

    /** Diagnostic/test-only: the rows currently rendered. */
    List<ReviewDiffRow> diagRows() {
        return List.copyOf(rows);
    }

    /**
     * The real diff currently shown, in full -- unlike {@link #diagRows()},
     * which is what the row-collapsing and truncation actually rendered. A
     * long run of unchanged lines folds into a single {@code CollapsedRun}
     * row and a large diff can be truncated outright, so a caller that needs
     * to know whether a given line is genuinely in the diff (Submit's {@code
     * DiffIndex}, built in {@code ReviewDestinationView}) must read this, not
     * the rows.
     */
    UnifiedDiff displayedDiff() {
        return displayedDiff;
    }

    /**
     * The scope {@link #displayedDiff()} actually belongs to -- empty while
     * nothing has resolved yet, or while a diff failed. This lags behind
     * {@link #setScope}: selecting a new scope does not clear it, so a
     * caller who reads {@link #displayedDiff()} during the "Diffing…" window
     * that follows a fresh selection would otherwise get the OUTGOING
     * scope's diff under the INCOMING scope's name. Submit must compare this
     * against the scope it thinks it is posting for before trusting the
     * diff at all -- see {@code ReviewDestinationView#submitReview()}.
     */
    Optional<String> displayedScopeId() {
        return Optional.ofNullable(displayedScopeId);
    }

    /** Diagnostic/test-only: the scope currently rendered. */
    Optional<ReviewScope> diagScope() {
        return Optional.ofNullable(scope);
    }

    /**
     * Diagnostic-only: the column's real laid-out widths.
     *
     * <p>This is what turned "the diff is too wide" into a measurement --
     * a 606px column rendering 1437px cells -- and it is the check that the
     * cards still fit: {@code maxCell} must not exceed {@code viewport}, and
     * the horizontal scrollbar must not be visible. The FX layer has no
     * headless harness inside the running app (docs/architecture.md), so
     * this is how that stays verifiable rather than eyeballed.</p>
     */
    String diagWidths() {
        Node viewport = list.lookup(".viewport");
        double viewportWidth = viewport instanceof Region region ? region.getWidth() : -1;
        double maxCell = 0;
        double maxGraphic = 0;
        for (Node cell : list.lookupAll(".review-diff-cell")) {
            if (cell instanceof Region region) {
                maxCell = Math.max(maxCell, region.getWidth());
                if (cell instanceof javafx.scene.control.Cell<?> c
                        && c.getGraphic() instanceof Region graphic) {
                    maxGraphic = Math.max(maxGraphic, graphic.prefWidth(-1));
                }
            }
        }
        String hbar = "none";
        for (Node bar : list.lookupAll(".scroll-bar")) {
            if (bar instanceof javafx.scene.control.ScrollBar sb
                    && sb.getOrientation() == javafx.geometry.Orientation.HORIZONTAL) {
                hbar = "visible=" + sb.isVisible() + " max=" + (int) sb.getMax()
                        + " visibleAmount=" + (int) sb.getVisibleAmount();
            }
        }
        return "column=" + (int) getWidth() + " list=" + (int) list.getWidth()
                + " viewport=" + (int) viewportWidth + " maxCell=" + (int) maxCell
                + " maxGraphicPref=" + (int) maxGraphic + " hbar[" + hbar + "]";
    }
}
