package app.drydock.ui.review;

import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkIds;
import app.drydock.review.ReadingPath;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Severity;
import app.drydock.ui.UiErrors;
import app.drydock.ui.UiFormats;
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
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

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
         * The sink owns storage (id generation, scope stamping) -- the
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
    private Predicate<String> symbolClickHandler = symbol -> false;

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
     * consumer reading whatever diff happened to be current, which is how the
     * board came to show one scope's files beside another's header.</p>
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
     * one produces the defect this toggle exists to avoid: a board walking
     * files a column has filtered out of view. See
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
     * The same thing as {@link #displayedScopeId}, as the scope itself: what
     * the rendered rows are rows OF.
     *
     * <p>Tracks {@link #scope} on the git-run path and {@code forScope} on
     * the supplied-diff path, so unlike {@code scope} it survives {@link
     * #showDiff}. Every reader that needs to answer "whose file is this?"
     * about something already on screen -- {@link #openInExplorer(ReviewDiffRow.HunkHeader, Button)}
     * is the only one today -- must use this and not {@code scope}: a diff
     * that arrived through {@code showDiff} has no live scope at all, and a
     * reader keyed on {@code scope} silently does nothing there. That is
     * exactly what the Explorer jump did once the session board began
     * restoring cached diffs through {@code showDiff}.</p>
     */
    private ReviewScope displayedScope;

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

    /**
     * Whole-file display mode: {@link #wholeFileDiff} (an unlimited-context
     * diff) is what renders, while {@link #displayedDiff} stays the review
     * diff everything else depends on.
     */
    private boolean wholeFiles;
    /** The whole-file diff as fetched; {@link #wholeFileDiff} is it after the untracked filter. */
    private UnifiedDiff wholeFileFull;
    private UnifiedDiff wholeFileDiff;
    private boolean wholeFileUnavailable;
    /** Tour mode's header, naming the current step; see {@link #setStepHeader}. */
    private Optional<String> stepHeader = Optional.empty();

    /**
     * The file the hunk diff's cursor is on, or null outside the hunk diff.
     * Only read when the whole scope would not fit under {@link
     * #MAX_RENDERED_ROWS}: then the column renders this file alone rather
     * than truncating, so {@code [}/{@code ]} reach every file and no file
     * the reader is settling is cut off the end (see {@link #buildRows}).
     */
    private String cursorFile;

    /** Whether the last build fell back to one file at a time; see {@link #buildRows}. */
    private boolean oneFileAtATime;

    /** The header's note while {@link #oneFileAtATime} is on. */
    static final String ONE_FILE_AT_A_TIME = "Large change — one file at a time; [ / ] moves between files";
    private long wholeRequestToken;
    /** {@code c} in whole-file mode: fold every long unchanged run again. */
    private boolean foldAll;
    private final Set<ReviewDiffRow.RunKey> expandedRuns = new HashSet<>();

    /**
     * Each hunk's {@link ReadingPath.Link}s, keyed by {@link HunkIds#hunkId}
     * -- see {@link #setLinks}. Empty until the host has a {@link
     * app.drydock.review.ChangeGraph} to compute them from, which is fine: a
     * hunk absent from this map simply gets no footer row (spec §7.2), not a
     * wrong one.
     */
    private Map<String, List<ReadingPath.Link>> linksByHunk = Map.of();

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
     * The gutter selection's anchor: the file and line key a plain click, the
     * start of a shift-click, or the start of a drag began at. {@code null}
     * for none.
     *
     * <p>Identified by {@code (file, lineKey)} rather than cached as a row
     * index into {@link #rows}: {@link #rebuild()} and {@link
     * #expandRun(ReviewDiffRow.CollapsedRun)} both replace {@link #rows}
     * wholesale, and expanding a collapsed run above the anchor shifts every
     * index after it -- a cached index would then resolve to whatever line
     * happens to sit there now, ranging a selection over rows the human never
     * clicked. Resolving the key fresh against the current {@link #rows} on
     * every use (see {@link #selectionAnchorIndex()}) means a rebuild that
     * drops the anchor's line entirely resolves to {@code -1} -- "selection
     * gone," the same outcome {@link #finalizeSelection} already gives a
     * stale {@code rows.indexOf(row)} miss -- instead of silently moving.</p>
     */
    private String selectionAnchorFile;

    /** @see #selectionAnchorFile */
    private String selectionAnchorLineKey;

    /** The anchor's current position in {@link #rows}, or {@code -1} when unset or no longer present. */
    private int selectionAnchorIndex() {
        return selectionAnchorFile == null ? -1 : indexOfLine(selectionAnchorFile, selectionAnchorLineKey);
    }

    /** Anchors the selection at {@code rows.get(index)}, or clears it when {@code index} is not a line. */
    private void setSelectionAnchor(int index) {
        if (index >= 0 && index < rows.size() && rows.get(index) instanceof ReviewDiffRow.Line line) {
            selectionAnchorFile = line.file();
            selectionAnchorLineKey = line.lineKey();
        } else {
            clearSelectionAnchor();
        }
    }

    private void clearSelectionAnchor() {
        selectionAnchorFile = null;
        selectionAnchorLineKey = null;
    }

    /**
     * The gutter keys currently painted {@code review-line-selected}, as
     * {@code file + " " + lineKey}. The file is part of the key on purpose:
     * two files can share a line number, and painting by a bare line key
     * would tint that number in every file, not just the one being
     * commented on.
     */
    private Set<String> selectedKeys = Set.of();

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
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(9, summaryLabel, spacer, untrackedToggle, contextToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("review-diff-header");
        setTop(header);

        list.getStyleClass().add("review-diff-list");
        list.setFocusTraversable(false);
        // Not Tab-traversable (above), but a click still plants real Scene
        // focus here, so focus stays inside the board (and Review's keys
        // keep reaching it) after a reader clicks into the code. Node.
        // requestFocus() does not require
        // focusTraversable -- that flag only gates the Tab engine -- so this
        // does not reopen Tab-key traversal into the list.
        list.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> list.requestFocus());
        list.setCellFactory(view -> new DiffCell());
        // Long lines wrap; the column never scrolls sideways. See
        // viewportWidth for what this replaces.
        list.skinProperty().addListener((obs, old, skin) -> bindViewportWidth());
        bindViewportWidth();
        findBar = buildFindBar();
        // The bar floats over the list's top-right corner: it is a mode the
        // reader enters and leaves, not a permanent strip taking a row of the
        // code away.
        StackPane stack = new StackPane(list, findBar);
        StackPane.setAlignment(findBar, Pos.TOP_RIGHT);
        setCenter(stack);

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
        displayedScope = newScope;
        expandedRuns.clear();
        resetWholeFiles();
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
        DiffScope diffScope = requested.diffScope();
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
     * consumer reading whatever diff happened to be current, which is how the
     * board came to show one scope's files beside another's header.</p>
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
        clearSelectionAnchor();
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

    /**
     * Who gets a click on an underlined symbol first. When {@code handler}
     * returns true it took the click (the tour opens a peek over the column)
     * and the diff-local lens stays closed; false, or no handler, opens the
     * lens as before.
     */
    void setSymbolClickHandler(Predicate<String> handler) {
        this.symbolClickHandler = handler == null ? symbol -> false : handler;
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

    // ---- find (⌘F): text search over the rendered diff ---------------------
    /**
     * The walk's targets as {@code "<file> <lineKey>"} keys, in diff order:
     * keys, not row indices, because a match can sit inside a folded run
     * (no row until the walk expands it) and a run opening above shifts
     * every index -- while a line key is what everything else in this
     * class already addresses rows by, and survives both.
     */
    private final List<String> findMatchKeys = new ArrayList<>();
    private final Set<String> findHitKeys = new HashSet<>();
    private String findCurrentKey;
    private int findCursor = -1;
    private boolean findOpen;
    private final TextField findField = new TextField();
    private final Label findCount = new Label("no matches");
    private final HBox findBar;

    /** Re-renders the rows so pin markers pick up a changed finding set. */
    @FunctionalInterface
    interface StepMarkSource {
        Optional<StepMark> markAt(String file, String lineKey);
    }

    private StepMarkSource stepMarks = (file, key) -> Optional.empty();

    void setStepMarkSource(StepMarkSource source) {
        stepMarks = source == null ? (file, key) -> Optional.empty() : source;
        refreshMarks();
    }

    private IntConsumer onClaimSelected = index -> { };

    /** What clicking a claim's callout does: the controller makes that anchor the active one. */
    void setOnClaimSelected(IntConsumer handler) {
        onClaimSelected = handler == null ? index -> { } : handler;
    }

    /** Repaints the step marks through the same cheap render refresh as the pins. */
    void refreshMarks() {
        renderGeneration++;
        refreshRender();
    }

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
     * #selectionAnchorIndex()}, resolves it through {@link DiffLineSelection}
     * (which alone owns the one-file/one-hunk clamp -- GitHub rejects a
     * cross-hunk comment and the whole atomic review with it, so that rule
     * is never re-implemented here), and repaints {@link #selectedKeys}.
     */
    private void extendSelection(int focusIndex) {
        int anchorIndex = selectionAnchorIndex();
        if (anchorIndex < 0) {
            return;
        }
        DiffLineSelection.resolve(rows, anchorIndex, focusIndex)
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
        int anchorIndex = selectionAnchorIndex();
        if (index < 0 || anchorIndex < 0) {
            return;
        }
        DiffLineSelection.resolve(rows, anchorIndex, index).ifPresent(range -> {
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
     * One key of the gutter selection -- {@code "<file> <lineKey>"}, the
     * same shape every key in this class already uses -- so {@link
     * SessionReviewView} can resolve which hunk a/r/u act on in HUNK mode
     * (spec §9.6). Any one key of the range answers this: {@link
     * DiffLineSelection} clamps a selection to a single hunk, so every key
     * in it names the same one. Empty while nothing is selected -- a
     * selection lives only as long as its composer does (see {@link
     * #closeComposer}), so this is naturally empty once the reader has
     * moved on from a comment.
     */
    Optional<String> currentLineSelection() {
        return selectedKeys.stream().findFirst();
    }

    /**
     * Diagnostic/test-only: the current selection anchor's row index, or
     * {@code -1} for none. Exists so a stale-index guard can be proven by
     * what it PREVENTS -- a stale gesture event overwriting a valid anchor
     * -- rather than by an incidental empty result that a missing guard
     * would also happen to produce.
     */
    int diagSelectionAnchorIndex() {
        return selectionAnchorIndex();
    }

    // ---- find (⌘F) --------------------------------------------------------

    /**
     * The bar itself: a field, a count, the walk's two buttons, and a close.
     * All of its keys are handled inside the field (Enter steps, shift-Enter
     * steps back, Esc closes), so none of them reach the board's key filter
     * or the app-wide chain while the reader is typing.
     */
    private HBox buildFindBar() {
        findField.setPromptText("Find in the diff");
        findField.setPrefColumnCount(16);
        findField.getStyleClass().add("review-find-field");
        findField.textProperty().addListener((obs, was, now) -> runFind());
        findField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case ENTER -> {
                    stepFind(event.isShiftDown() ? -1 : 1);
                    event.consume();
                }
                case ESCAPE -> {
                    closeFind();
                    event.consume();
                }
                default -> { }
            }
        });
        Button previous = new Button("‹");
        Button next = new Button("›");
        Button close = new Button("✕");
        for (Button button : List.of(previous, next, close)) {
            button.getStyleClass().add("review-find-action");
        }
        previous.setOnAction(event -> stepFind(-1));
        next.setOnAction(event -> stepFind(1));
        close.setOnAction(event -> closeFind());
        findCount.getStyleClass().add("review-find-count");
        HBox bar = new HBox(6, findField, findCount, previous, next, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("review-find-bar");
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    /** {@code ⌘F}: opens the bar over the rendered rows and takes the focus. */
    void openFind() {
        if (!findOpen) {
            findOpen = true;
            findBar.setVisible(true);
            findBar.setManaged(true);
        }
        // Not yet laid out on the first open: focus on the next pulse, the
        // same reason {@code onShown} defers -- an immediate requestFocus
        // against an unrendered node is dropped.
        Platform.runLater(() -> {
            findField.requestFocus();
            findField.selectAll();
        });
        if (findField.getText() != null && !findField.getText().isBlank()) {
            runFind();
        }
    }

    boolean findOpen() {
        return findOpen;
    }

    /**
     * Esc from anywhere (the field's own filter, or the board's unwind chain
     * when focus has drifted): closes the bar and clears every hit mark.
     * False when there was nothing to close, so the chain can move on.
     */
    boolean closeFind() {
        if (!findOpen) {
            return false;
        }
        findOpen = false;
        findBar.setVisible(false);
        findBar.setManaged(false);
        findMatchKeys.clear();
        findHitKeys.clear();
        findCurrentKey = null;
        findCursor = -1;
        findCount.setText("no matches");
        renderGeneration++;
        refreshRender();
        list.requestFocus();
        return true;
    }

    /**
     * Recomputes the walk for the field's current text and lands on the
     * first match, keeping the reader's position when the previous match
     * still matches (typing more of the same word narrows in place, the way
     * every find field a reader has used behaves).
     *
     * <p>The matches come from the rendered DIFF's lines, not the rendered
     * rows: a line inside a folded run is text the reader asked to find,
     * and pretending it does not exist because it is folded would answer
     * "no matches" to a question the diff can answer. Landing on such a
     * key goes through {@link #revealLine}, which opens the fold that hid
     * it. A one-character query is noise (nearly every line matches some
     * letter), so the walk starts at two. Cheap enough per keystroke: one
     * pass over the diff's lines, and the repaint is visible cells only
     * (see {@link #refreshRender}).</p>
     */
    private void runFind() {
        String previous = findCurrentKey;
        computeFind();
        if (findMatchKeys.isEmpty()) {
            findCursor = -1;
            findCurrentKey = null;
        } else {
            findCursor = Math.max(0, previous == null ? -1 : findMatchKeys.indexOf(previous));
            findCurrentKey = findMatchKeys.get(findCursor);
            landOnFindCursor();
        }
        updateFindCount();
        renderGeneration++;
        refreshRender();
    }

    /**
     * The rows' layout changed under the walk (a fold opened, a rebuild):
     * recompute against what the diff still says, keeping the walk on the
     * key it was on -- which is exactly why the walk is keyed, not indexed.
     */
    private void refreshFind() {
        if (!findOpen) {
            return;
        }
        String current = findCurrentKey;
        computeFind();
        findCursor = current == null ? -1 : findMatchKeys.indexOf(current);
        if (findCursor < 0 && !findMatchKeys.isEmpty()) {
            findCursor = 0;
        }
        findCurrentKey = findCursor >= 0 ? findMatchKeys.get(findCursor) : null;
        updateFindCount();
        renderGeneration++;
        refreshRender();
    }

    private void stepFind(int direction) {
        if (findMatchKeys.isEmpty()) {
            return;
        }
        findCursor = Math.floorMod(findCursor + direction, findMatchKeys.size());
        findCurrentKey = findMatchKeys.get(findCursor);
        landOnFindCursor();
        updateFindCount();
        renderGeneration++;
        refreshRender();
    }

    /** Marks and reveals the key the walk is on: scrolls to its row, opening the fold that hid it. */
    private void landOnFindCursor() {
        findCurrentKey = findMatchKeys.get(findCursor);
        // The key's last space splits it: line keys carry no spaces, file
        // paths may.
        int split = findCurrentKey.lastIndexOf(' ');
        revealLine(findCurrentKey.substring(0, split), findCurrentKey.substring(split + 1));
    }

    private void updateFindCount() {
        findCount.setText(findMatchKeys.isEmpty()
                ? "no matches"
                : (findCursor + 1) + " / " + findMatchKeys.size());
    }

    /** The walk's keys for the field's text, from the rendered diff's own lines. */
    private void computeFind() {
        findMatchKeys.clear();
        findHitKeys.clear();
        findCurrentKey = null;
        findCursor = -1;
        String query = findField.getText() == null ? "" : findField.getText().strip();
        if (query.length() < 2) {
            return;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        UnifiedDiff rendered = renderedDiff();
        if (rendered == null) {
            return;
        }
        for (UnifiedDiff.FileDiff file : rendered.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    if (line.text().toLowerCase(Locale.ROOT).contains(needle)) {
                        String key = file.path() + " " + line.lineKey();
                        findMatchKeys.add(key);
                        findHitKeys.add(key);
                    }
                }
            }
        }
    }

    /**
     * Scrolls to the row anchored at {@code lineKey} in {@code file} (card →
     * line linkage), opening whatever hid it. A reveal that lands nowhere is
     * indistinguishable from a dead button -- a jump to a location inside a
     * folded run used to do nothing at all -- so a target the exact scan
     * misses gets fallbacks: a line inside a collapsed run opens that hunk's
     * folds first, a line past the row cap reaches its hunk header, and a
     * line the rendered diff does not carry returns false for the caller to
     * say so rather than stay silent.
     */
    boolean revealLine(String file, String lineKey) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof ReviewDiffRow.Line line
                    && line.file().equals(file) && line.lineKey().equals(lineKey)) {
                list.scrollTo(Math.max(0, i - 3));
                return true;
            }
        }
        return revealHidden(file, lineKey);
    }

    /**
     * The fallbacks of {@link #revealLine}: find the hunk of the rendered
     * diff whose lines carry {@code lineKey}, open every fold of that hunk
     * (a jump is a navigation intent; the reader asked to land THERE, and
     * {@code c} re-folds), and try the exact row again. Still missing (the
     * row cap cut the hunk short) scrolls to the hunk's header. A key no
     * hunk carries -- a file the column does not show, or a key from another
     * diff -- is not this column's to reveal.
     */
    private boolean revealHidden(String file, String lineKey) {
        UnifiedDiff rendered = renderedDiff();
        if (rendered == null) {
            return false;
        }
        for (UnifiedDiff.FileDiff fileDiff : rendered.files()) {
            if (!fileDiff.path().equals(file)) {
                continue;
            }
            for (int h = 0; h < fileDiff.hunks().size(); h++) {
                boolean carries = fileDiff.hunks().get(h).lines().stream()
                        .anyMatch(line -> line.lineKey().equals(lineKey));
                if (!carries) {
                    continue;
                }
                boolean openedAFold = false;
                for (ReviewDiffRow row : rows) {
                    if (row instanceof ReviewDiffRow.CollapsedRun run
                            && run.file().equals(file) && run.hunkIndex() == h) {
                        expandedRuns.add(run.key());
                        openedAFold = true;
                    }
                }
                if (openedAFold) {
                    // Not rebuild(): that scrolls to the top, and the whole
                    // point is to land on the target.
                    rows.setAll(buildRows());
                    refreshFind();
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i) instanceof ReviewDiffRow.Line line
                                && line.file().equals(file) && line.lineKey().equals(lineKey)) {
                            list.scrollTo(Math.max(0, i - 3));
                            return true;
                        }
                    }
                }
                return revealHunk(file, h);
            }
            return false;
        }
        return false;
    }

    /**
     * Scrolls to {@code file}'s hunk whose REAL index (into its own
     * {@code UnifiedDiff.FileDiff.hunks()}) is {@code hunkIndex} -- what
     * moving between files ({@code [}/{@code ]}, {@code n}) or a link footer
     * brings into view. Falls back to the file's first rendered card when
     * that exact hunk is not among them (a hunk whose every line is hidden
     * renders no card).
     *
     * <p>Matched by {@link ReviewDiffRow.HunkHeader#hunkIndex()} rather than
     * by counting rendered headers in order, so a hunk that renders no card
     * cannot make the Nth RENDERED header stand in for hunk N.</p>
     *
     * <p>Returns whether the file was reached. It can genuinely be absent:
     * the board walks the whole diff while these rows stop at {@link
     * #MAX_RENDERED_ROWS}, so in a large diff every file past the cut has no
     * card to scroll to. The truncation notice is scrolled into view
     * instead, because it is the one row that explains why the file is not
     * there.</p>
     */
    boolean revealHunk(String file, int hunkIndex) {
        int firstCard = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof ReviewDiffRow.HunkHeader header)
                    || !header.file().equals(file)) {
                continue;
            }
            if (firstCard < 0) {
                firstCard = i;
            }
            if (header.hunkIndex() == hunkIndex) {
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
        // Cleared as the column's LIVE scope, kept as the one the rows
        // describe: see the field javadoc on why the two must not be the
        // same field.
        displayedScope = forScope;
        requestToken++;
        expandedRuns.clear();
        resetWholeFiles();
        applyDiff(supplied, forScope.id());
    }

    /** {@code d}: applies a density by swapping the root's style class (spec §4.8). */
    void setDensity(ReviewDensity density) {
        for (ReviewDensity value : ReviewDensity.values()) {
            getStyleClass().remove(value.styleClass());
        }
        getStyleClass().add(density.styleClass());
    }

    /** {@code c}: shows or hides unchanged lines entirely. */
    void toggleContext() {
        if (wholeFiles) {
            foldAll = !foldAll;
        } else {
            showContext = !showContext;
        }
        updateContextToggle();
        rebuild();
    }

    private void updateContextToggle() {
        boolean on = wholeFiles ? !foldAll : showContext;
        contextToggle.setText(on ? "context" : wholeFiles ? "folded" : "changed only");
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

    private void resetWholeFiles() {
        wholeFileFull = null;
        wholeFileDiff = null;
        wholeFileUnavailable = false;
        wholeRequestToken++;
    }

    /** Whether the column is set to show whole files (the display diff may still be loading). */
    boolean wholeFiles() {
        return wholeFiles;
    }

    /** Whether the whole-file fetch failed and the review diff is shown instead. */
    boolean wholeFileUnavailable() {
        return wholeFileUnavailable;
    }

    /** What the rows are built from: the whole-file diff when it is on and loaded, else the review diff. */
    UnifiedDiff renderedDiff() {
        return wholeFiles && wholeFileDiff != null ? wholeFileDiff : displayedDiff;
    }

    /**
     * Shows whole files (display only; approvals, symbol index and submit stay
     * on the review diff). The unlimited-context diff is fetched off the FX
     * thread; until it lands, or if it fails, the review diff renders.
     */
    void setWholeFiles(boolean on) {
        wholeFiles = on;
        updateContextToggle();
        if (on && wholeFileDiff == null) {
            fetchWholeFiles();
        }
        rebuild();
        updateSummary();
    }

    private void fetchWholeFiles() {
        ReviewScope requested = scope;
        if (requested == null || !requested.diffable()) {
            return; // diag/test path: diagShowWholeFileDiff supplies it
        }
        long token = ++wholeRequestToken;
        diffService.diff(requested.diffRoot(), requested.diffScope(), requested.base(),
                        DiffService.WHOLE_FILE_CONTEXT_LINES)
                .whenComplete((result, failure) ->
                        Platform.runLater(() -> applyWholeFileResult(token, result, failure)));
    }

    /**
     * Applies a whole-file fetch outcome on the FX thread. A stale token (the
     * scope or diff changed since the request) or a turned-off mode discards
     * it; a failure falls back to the review diff and says so in the summary.
     */
    void applyWholeFileResult(long token, UnifiedDiff result, Throwable failure) {
        if (token != wholeRequestToken || !wholeFiles) {
            return;
        }
        if (failure != null) {
            LOG.log(Level.WARNING, "Whole-file diff failed for scope " + displayedScopeId + ": "
                    + UiErrors.unwrap(failure).getMessage());
            wholeFileUnavailable = true;
            wholeFileFull = null;
            wholeFileDiff = null;
        } else {
            wholeFileUnavailable = false;
            wholeFileFull = result;
            wholeFileDiff = withoutHiddenUntracked(result, displayedScopeId);
        }
        rebuild();
        updateSummary();
    }

    /** Test-only: the token a whole-file result must carry to be applied. */
    long wholeRequestToken() {
        return wholeRequestToken;
    }

    /** Diagnostic/test-only: supplies the whole-file display diff without running git. */
    void diagShowWholeFileDiff(UnifiedDiff diff) {
        wholeFileFull = diff;
        wholeFileDiff = diff;
        wholeFileUnavailable = false;
        rebuild();
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

    /**
     * Whether the diff last published for {@code scopeId} left untracked
     * files out -- so it is not the review diff an agent reads, and a tour
     * must not be migrated onto it. False when there were none to hide.
     */
    boolean hidesUntracked(String scopeId) {
        return scopeId.equals(displayedScopeId) && !includeUntracked(scopeId)
                && fullDiff.files().stream().anyMatch(UnifiedDiff.FileDiff::untracked);
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
        displayedDiff = withoutHiddenUntracked(fullDiff, scopeId);
        if (wholeFileFull != null) {
            wholeFileDiff = withoutHiddenUntracked(wholeFileFull, scopeId);
        }
        symbolIndex = SymbolIndex.of(displayedDiff);
        rebuild();
        onDiffResolved.accept(scopeId, new DiffOutcome.Loaded(displayedDiff));
        if (wholeFiles) {
            fetchWholeFiles();
        }
    }

    /** Drops untracked files when the scope's untracked toggle is off; applied to both diffs. */
    private UnifiedDiff withoutHiddenUntracked(UnifiedDiff diff, String scopeId) {
        return includeUntracked(scopeId)
                ? diff
                : new UnifiedDiff(diff.files().stream()
                        .filter(file -> !file.untracked())
                        .toList());
    }

    private void rebuild() {
        rows.setAll(buildRows());
        refreshFind();
        // Re-anchored rather than dropped: a rebuild happens for reasons that
        // have nothing to do with the draft (a pin refresh, the context
        // toggle), and losing typed text to one of those is the kind of thing
        // a reader never forgives.
        insertComposerRow();
        updateSummary();
        list.scrollTo(0);
    }

    /**
     * The rows to show. The whole scope while it fits under {@link
     * #MAX_RENDERED_ROWS}; past that, in the hunk diff, just {@link
     * #cursorFile} -- a truncated whole scope would put every file past the
     * cut out of reach while its hunks could still be settled unseen. Whole
     * files (the tour) keep their own folding and are never narrowed.
     */
    private List<ReviewDiffRow> buildRows() {
        List<ReviewDiffRow> built = ReviewDiffRows.build(renderedDiff(), buildOptions());
        boolean truncated = built.stream().anyMatch(ReviewDiffRow.Truncation.class::isInstance);
        oneFileAtATime = truncated && !wholeFiles && cursorFile != null;
        if (!oneFileAtATime) {
            return built;
        }
        UnifiedDiff oneFile = new UnifiedDiff(renderedDiff().files().stream()
                .filter(file -> file.path().equals(cursorFile))
                .toList());
        return ReviewDiffRows.build(oneFile, buildOptions());
    }

    /**
     * Tells the column which file the hunk diff's cursor is on (null outside
     * the hunk diff). Rebuilds only when that changes what is rendered: in
     * one-file-at-a-time mode, or when the scope may not have fit before a
     * cursor existed.
     */
    void setCursorFile(String file) {
        if (Objects.equals(file, cursorFile)) {
            return;
        }
        String previous = cursorFile;
        cursorFile = file;
        if (oneFileAtATime || (previous == null && rows.stream().anyMatch(ReviewDiffRow.Truncation.class::isInstance))) {
            rebuild();
        }
    }

    /**
     * Whether every one of {@code lineKeys} -- each {@code "<file>
     * <lineKey>"} -- has a row on screen: what an approval requires, since a
     * row past the row cap, or in a file not rendered, is code the reader
     * has not been shown. Keyed by line rather than by hunk header so the
     * one rule holds in both views: the whole-file diff numbers its hunks
     * differently from the review diff, and a hunk whose header renders can
     * still be cut off partway by the cap. Only unchanged runs ever fold,
     * so asking about changed rows never trips over a fold.
     */
    boolean rendersLines(Collection<String> lineKeys) {
        Set<String> missing = new HashSet<>(lineKeys);
        for (ReviewDiffRow row : rows) {
            if (missing.isEmpty()) {
                break;
            }
            if (row instanceof ReviewDiffRow.Line line) {
                missing.remove(line.file() + " " + line.lineKey());
            }
        }
        return missing.isEmpty();
    }

    private ReviewDiffRows.Options buildOptions() {
        // Hunk ids (links) are review-diff coordinates; the whole-file diff
        // has different hunks, so it ignores them.
        boolean whole = wholeFiles && wholeFileDiff != null;
        return new ReviewDiffRows.Options(showContext, expandedRuns, MAX_RENDERED_ROWS,
                whole ? Map.of() : linksByHunk,
                whole && !foldAll);
    }

    /**
     * What each hunk has to do with the rest of the diff (spec §7.2), keyed
     * by {@link HunkIds#hunkId}. The host calls this whenever its
     * {@link ReadingPath.Path} changes -- most often once its {@link
     * app.drydock.review.ChangeGraph} finishes building, well after the diff
     * itself rendered.
     *
     * <p>Deliberately not {@link #rebuild()}: that scrolls back to the top,
     * and the host calls this on state changes that have nothing to do with
     * where the reader is scrolled to (the same reason {@link #expandRun}
     * avoids it). A no-op re-publish of the same map -- the common case,
     * since most refreshes have nothing new to say about links -- skips the
     * rebuild entirely rather than re-computing identical rows.</p>
     */
    void setLinks(Map<String, List<ReadingPath.Link>> byHunkId) {
        Map<String, List<ReadingPath.Link>> copy = Map.copyOf(byHunkId);
        if (copy.equals(linksByHunk)) {
            return;
        }
        linksByHunk = copy;
        rows.setAll(buildRows());
        // The graph this map is computed from lands asynchronously, well
        // after a reader may have already opened the gutter composer -- a
        // rebuild that dropped it here would lose an in-progress comment to
        // a background refresh the reader never asked for.
        insertComposerRow();
    }

    private void showMessage(String text) {
        rows.setAll(List.of(new ReviewDiffRow.Message(text)));
        updateSummary();
    }

    /** The header count: the scope's files and line totals, or the tour step's header. */
    private void updateSummary() {
        if (wholeFiles && stepHeader.isPresent()) {
            summaryLabel.setText(stepHeader.get() + (wholeFileUnavailable ? "  ·  whole file unavailable" : ""));
            return;
        }
        int files = displayedDiff.files().size();
        int insertions = displayedDiff.files().stream().mapToInt(UnifiedDiff.FileDiff::insertions).sum();
        int deletions = displayedDiff.files().stream().mapToInt(UnifiedDiff.FileDiff::deletions).sum();
        summaryLabel.setText(files == 0
                ? ""
                : files + (files == 1 ? " file" : " files") + "  ·  +" + insertions + " −" + deletions
                        + (wholeFileUnavailable ? "  ·  whole file unavailable" : wholeFiles ? "  ·  whole files" : "")
                        + (oneFileAtATime ? "  ·  " + ONE_FILE_AT_A_TIME : ""));
    }

    /**
     * The header in tour mode: the current step ("step 1 of 3 · title")
     * rather than the file totals, so the column says what it is showing
     * the reader through. Empty restores the totals.
     */
    void setStepHeader(Optional<String> header) {
        if (header.equals(stepHeader)) {
            return;
        }
        stepHeader = header;
        updateSummary();
    }

    /** Expands one collapsed run in place; a full rebuild is a single list swap. */
    private void expandRun(ReviewDiffRow.CollapsedRun run) {
        expandedRuns.add(run.key());
        // Deliberately not rebuild(): that scrolls back to the top, and
        // expanding a run is the one action whose whole point is to stay
        // where the reader already is.
        rows.setAll(buildRows());
        refreshFind();
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
                case ReviewDiffRow.LinkRow linkRow -> buildLinkRow(linkRow);
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
        ReviewScope owner = displayedScope;
        if (owner == null) {
            return;
        }
        if (!explorerBridge.openFileAtLine(owner, Path.of(header.file()), header.startLine())) {
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
                if (!(e.isShiftDown() && selectionAnchorIndex() >= 0)) {
                    setSelectionAnchor(index);
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
                if (!(e.isShiftDown() && selectionAnchorIndex() >= 0)) {
                    setSelectionAnchor(index);
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
        if (findOpen) {
            // The find walk's signaling: every hit row reads as one, the
            // row the walk is on reads as the one -- the reader has to see
            // WHERE the matches are, not just be scrolled between them.
            String key = row.file() + " " + row.lineKey();
            if (key.equals(findCurrentKey)) {
                box.getStyleClass().add("find-hit-current");
            } else if (findHitKeys.contains(key)) {
                box.getStyleClass().add("find-hit");
            }
        }
        Optional<StepMark> mark = stepMarks.markAt(row.file(), row.lineKey());
        if (mark.isPresent()) {
            StepMark m = mark.get();
            box.getStyleClass().add(m.strength() == StepMark.Strength.CURRENT
                    ? "tour-step-current" : "tour-step-other");
            if (m.strength() == StepMark.Strength.OTHER && m.tagged() && !m.hidden()) {
                Label tag = new Label("step " + m.stepNumber());
                tag.getStyleClass().add("tour-step-tag");
                box.getChildren().add(tag);
            }
            if (m.hidden()) {
                // The band replaces only the source text, so the gutters keep
                // their line numbers; the label sits on the first hidden row,
                // and names the step when it is another step's (whose own
                // "step N" tag it then stands in for).
                Label band = new Label(m.bandStart() ? m.bandLabel() : "");
                band.getStyleClass().add("tour-predict-band");
                band.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(band, Priority.ALWAYS);
                box.getChildren().set(box.getChildren().indexOf(source), band);
            }
            if (m.claim().isPresent()) {
                StepMark.Claim claim = m.claim().get();
                box.getStyleClass().add(claim.active() ? "tour-claim-active" : "tour-claim-dim");
                if (claim.first()) {
                    // At the row's right edge, like the "step N" tag: the
                    // gutters on the left keep their width on every row.
                    box.getChildren().add(claimBadge(claim.number()));
                }
            }
            if (!m.callouts().isEmpty()) {
                VBox stacked = new VBox(box);
                stacked.getStyleClass().add("tour-claim-stack");
                for (StepMark.Callout callout : m.callouts()) {
                    stacked.getChildren().add(claimCallout(callout));
                }
                return stacked;
            }
        }
        return box;
    }

    private static Label claimBadge(int number) {
        Label badge = new Label(Integer.toString(number));
        badge.getStyleClass().add("tour-claim-badge");
        return badge;
    }

    /**
     * The claim under the last row of its range: a badge and the agent's
     * sentence. The active claim shows in full; the others collapse to one
     * line so the code, not the commentary, is what the column mostly shows.
     * A real {@link Button}, so the keyboard reaches it, and clicking makes
     * it the active claim without moving the viewport: the callout is
     * already on screen.
     */
    private Region claimCallout(StepMark.Callout callout) {
        Button text = UiFormats.literal(new Button(callout.text()));
        text.getStyleClass().add("tour-claim-text");
        text.setWrapText(callout.active());
        text.setMaxWidth(Double.MAX_VALUE);
        text.setOnAction(event -> onClaimSelected.accept(callout.index()));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(8, claimBadge(callout.number()), text);
        box.setAlignment(Pos.TOP_LEFT);
        box.getStyleClass().addAll("tour-claim-callout", callout.active() ? "active" : "collapsed");
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
            symbol.setOnMouseClicked(e -> {
                if (!symbolClickHandler.test(word)) {
                    showLens(word, symbol);
                }
            });
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
                Button jump = UiFormats.literal(new Button(occurrence.text().length() > 60
                        ? occurrence.text().substring(0, 59) + "…" : occurrence.text()));
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

    /**
     * A hunk's footer row: what it has to do with a hunk in another file
     * (spec §7.2). {@code link.label()} already names a file and a symbol --
     * never {@link ReadingPath.Link#targetHunkId()} -- so the button's own
     * text is exactly that label with a glyph naming the relationship in
     * front of it.
     *
     * <p>{@code .review-link-row} carries its OWN {@code -fx-text-fill} in
     * {@code app.css}, the same fix {@code .review-collapsed-run} already
     * needed: a plain {@code Button.setText} has no fill of its own here --
     * only some review labels do -- so it
     * falls back to modena's light-button default against this column's dark
     * background (Task 18's 1.13:1 defect, on a different row).</p>
     */
    private Region buildLinkRow(ReviewDiffRow.LinkRow row) {
        ReadingPath.Link link = row.link();
        Button button = UiFormats.literal(new Button(glyphFor(link.kind()) + "  " + link.label()));
        button.getStyleClass().add("review-link-row");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setTooltip(new Tooltip("Jump to " + link.label()));
        button.setOnAction(e -> selectLinkTarget(link.targetHunkId()));
        return button;
    }

    /** The arrow a link row opens with, naming the relationship {@link ReadingPath.Link#label()} does not. */
    private static String glyphFor(String kind) {
        if (ReadingPath.CALLS.equals(kind)) {
            return "↳ calls";
        }
        if (ReadingPath.CALLED_BY.equals(kind)) {
            return "↳ called by";
        }
        return "↔";
    }

    /**
     * Resolves a raw hunk id -- exactly what a link's own label never shows
     * -- back to the (file, index) {@link #revealHunk} already knows how to
     * scroll to -- the same scroll-into-view path {@code [}/{@code ]} use.
     */
    private void selectLinkTarget(String hunkId) {
        HunkIds.parseHunkId(hunkId).ifPresent(anchor -> {
            boolean reached = revealHunk(anchor.file(), anchor.hunkIndex());
            if (!reached) {
                // Not swallowed: a link whose target could not be reached
                // (past the row cap, most likely) must not look identical to
                // one that worked -- the same display/action divergence this
                // whole row exists to avoid.
                LOG.log(Level.WARNING, "Link footer could not reach its target hunk: " + hunkId);
            }
        });
    }

    private static Region message(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("review-diff-message");
        label.setWrapText(true);
        HBox box = new HBox(label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * Diagnostic-only: drives the gutter's real selection path for {@code
     * [file:startKey, file:endKey]} and opens the composer exactly as {@link
     * #finalizeSelection} does on a gesture release. {@code rows} is
     * virtualization's data model, not its rendered cells, so this reaches a
     * key outside the viewport just as reliably as one already on screen --
     * the real gesture handlers could not be reused directly because they
     * are wired to a specific rendered hit box, which a key with no cell yet
     * does not have.
     *
     * @return false when either key is not in the currently loaded diff, so
     *         the visual pass can say so instead of silently selecting
     *         nothing
     */
    boolean diagSelectRange(String file, String startKey, String endKey) {
        // Mutates gutter selection state and the ListView's scroll position
        // -- FX state that only the FX Application Thread may touch. Routed
        // through ReviewDiagFxThread so a caller off that thread (a test,
        // the diag driver) is safe rather than merely usually-fine.
        return ReviewDiagFxThread.call(() -> {
            int startIndex = indexOfLine(file, startKey);
            int endIndex = indexOfLine(file, endKey);
            if (startIndex < 0 || endIndex < 0) {
                return false;
            }
            setSelectionAnchor(startIndex);
            extendSelection(endIndex);
            list.scrollTo(Math.max(0, Math.min(startIndex, endIndex) - 3));
            return DiffLineSelection.resolve(rows, startIndex, endIndex).map(range -> {
                if (composerRow != null && composerRow.file().equals(range.file())
                        && composerRow.startKey().equals(range.startKey())
                        && composerRow.endKey().equals(range.endKey())) {
                    closeComposer();
                } else {
                    openComposer(range.file(), range.startKey(), range.endKey());
                }
                return true;
            }).orElse(false);
        });
    }

    /**
     * Diagnostic-only: opens the composer on the first changed line
     * rendered.
     *
     * <p>Iterates {@code rows} directly and mutates composer state
     * ({@link #toggleComposer}, which touches {@link #rows} itself via
     * {@link #insertComposerRow}) -- the same FX-owned {@link ObservableList}
     * {@link #diagRows} guards against reading off-thread. Routed through
     * {@link ReviewDiagFxThread} for the same reason: a caller off the FX
     * thread iterating {@code rows} while it is mid-{@code setAll} throws
     * {@code ConcurrentModificationException}.</p>
     */
    String diagOpenComposer() {
        return ReviewDiagFxThread.call(() -> {
            for (ReviewDiffRow row : rows) {
                if (row instanceof ReviewDiffRow.Line line
                        && line.line().kind() != UnifiedDiff.Line.Kind.CONTEXT) {
                    toggleComposer(line.file(), line.lineKey());
                    return "composer on " + line.file() + " " + line.lineKey();
                }
            }
            return "no changed line to comment on";
        });
    }

    /**
     * Diagnostic/test-only: the rows currently rendered.
     *
     * <p>{@code rows} is the {@link ObservableList} the FX Application
     * Thread mutates with {@code setAll(...)} (see {@link #refreshRender},
     * {@link #insertComposerRow}, {@link #closeComposer}) whenever an async
     * diff load lands or the composer opens/closes. A caller off the FX
     * thread (a test polling for the diff, or the diag driver) that called
     * {@code List.copyOf(rows)} directly used to iterate {@code rows} while
     * a concurrent {@code setAll} was in flight, which invalidates that
     * iteration mid-copy and throws {@code ConcurrentModificationException}
     * -- the exact failure that turned a full-suite run red on the source
     * branch. {@link ReviewDiagFxThread#call} takes the snapshot ON the FX
     * thread instead, so the copy and any concurrent mutation are strictly
     * ordered. Do not simplify this back to a bare {@code
     * List.copyOf(rows)}.</p>
     */
    List<ReviewDiffRow> diagRows() {
        return ReviewDiagFxThread.call(() -> List.copyOf(rows));
    }

    /**
     * The real diff currently shown, in full -- unlike {@link #diagRows()},
     * which is what the row-collapsing and truncation actually rendered. A
     * long run of unchanged lines folds into a single {@code CollapsedRun}
     * row and a large diff can be truncated outright, so a caller that needs
     * to know whether a given line is genuinely in the diff (Submit's {@code
     * DiffIndex}, built in {@code SessionReviewView}) must read this, not
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
     * diff at all -- see {@code SessionReviewView#submitReview()}.
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
