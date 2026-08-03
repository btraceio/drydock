package app.drydock.ui.review;

import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
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

    /** Findings anchored to a line, and what happens when their pin is clicked (spec §4.4). */
    interface PinSource {
        /** The pin numbers of the findings anchored to {@code lineKey} in {@code file}, in margin order. */
        List<Pin> pinsAt(String file, String lineKey);

        /** Clicking a line or its pin focuses the matching card. */
        void focusFinding(Pin pin);
    }

    /** One {@code ◆n} marker: its number, its severity style class, and its finding's key. */
    record Pin(int number, String severityStyleClass, app.drydock.review.ReviewAnnotation.Key key,
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
    private final ObservableList<ReviewDiffRow> rows = FXCollections.observableArrayList();
    private final ListView<ReviewDiffRow> list = new ListView<>(rows);

    /**
     * Notified when a diff resolves, with the scope it resolved for.
     *
     * <p>The scope id is the point: a bare "a diff landed" signal left every
     * consumer reading whatever diff happened to be current, which is how an
     * intent rail came to show one scope's files beside another's header.</p>
     */
    private java.util.function.BiConsumer<String, DiffOutcome> onDiffResolved = (scopeId, outcome) -> { };

    private ReviewScope scope;
    private UnifiedDiff diff = new UnifiedDiff(List.of());

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
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(9, summaryLabel, spacer, contextToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("review-diff-header");
        setTop(header);

        list.getStyleClass().add("review-diff-list");
        list.setFocusTraversable(false);
        list.setCellFactory(view -> new DiffCell());
        setCenter(list);

        updateContextToggle();
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
        if (newScope == null) {
            diff = new UnifiedDiff(List.of());
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
                        diff = new UnifiedDiff(List.of());
                        String message = UiErrors.unwrap(failure).getMessage();
                        showMessage("Could not diff: " + message);
                        onDiffResolved.accept(requested.id(), new DiffOutcome.Failed(message));
                        return;
                    }
                    diff = result;
                    symbolIndex = SymbolIndex.of(diff);
                    rebuild();
                    onDiffResolved.accept(requested.id(), new DiffOutcome.Loaded(result));
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

    /** Supplies the {@code ◆n} pins; set once by the destination. */
    void setPinSource(PinSource source) {
        if (source != null) {
            this.pinSource = source;
        }
    }

    /** Re-renders the rows so pin markers pick up a changed finding set. */
    void refreshPins() {
        // A full row swap is one list operation; the rows themselves are
        // unchanged data, so this costs a re-render of the visible cells only.
        List<ReviewDiffRow> current = List.copyOf(rows);
        rows.setAll(List.of());
        rows.setAll(current);
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
        diff = supplied;
        symbolIndex = SymbolIndex.of(diff);
        expandedRuns.clear();
        rebuild();
        onDiffResolved.accept(forScope.id(), new DiffOutcome.Loaded(supplied));
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
        showContext = !showContext;
        updateContextToggle();
        rebuild();
    }

    private void updateContextToggle() {
        contextToggle.setText(showContext ? "context" : "changed only");
    }

    // ---- rendering ----------------------------------------------------------

    private void rebuild() {
        rows.setAll(ReviewDiffRows.build(diff,
                new ReviewDiffRows.Options(showContext, expandedRuns, MAX_RENDERED_ROWS)));
        updateSummary();
        list.scrollTo(0);
    }

    private void showMessage(String text) {
        rows.setAll(List.of(new ReviewDiffRow.Message(text)));
        updateSummary();
    }

    private void updateSummary() {
        int files = diff.files().size();
        int insertions = diff.files().stream().mapToInt(UnifiedDiff.FileDiff::insertions).sum();
        int deletions = diff.files().stream().mapToInt(UnifiedDiff.FileDiff::deletions).sum();
        summaryLabel.setText(files == 0
                ? ""
                : files + (files == 1 ? " file" : " files") + "  ·  +" + insertions + " −" + deletions);
    }

    /** Expands one collapsed run in place; a full rebuild is a single list swap. */
    private void expandRun(ReviewDiffRow.CollapsedRun run) {
        expandedRuns.add(run.key());
        rows.setAll(ReviewDiffRows.build(diff,
                new ReviewDiffRows.Options(showContext, expandedRuns, MAX_RENDERED_ROWS)));
    }

    /**
     * Renders one {@link ReviewDiffRow}. Cheap enough to rebuild on every
     * item change: a row is a handful of labels and one {@link TextFlow}.
     */
    private final class DiffCell extends ListCell<ReviewDiffRow> {

        DiffCell() {
            getStyleClass().add("review-diff-cell");
        }

        @Override
        protected void updateItem(ReviewDiffRow row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeIf(styleClass -> styleClass.startsWith("card-"));
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            getStyleClass().add("card-" + row.edge().name().toLowerCase(java.util.Locale.ROOT));
            Node node = switch (row) {
                case ReviewDiffRow.HunkHeader header -> buildHunkHeader(header);
                case ReviewDiffRow.Line line -> buildLine(line);
                case ReviewDiffRow.CollapsedRun run -> buildCollapsedRun(run);
                case ReviewDiffRow.Truncation truncation ->
                        message("… diff truncated at " + truncation.limit() + " rows");
                case ReviewDiffRow.Message text -> message(text.text());
            };
            if (node instanceof Region region) {
                // Width only. Binding height would fix the row height, which
                // is exactly the bug that hid code behind no scrollbar.
                region.prefWidthProperty().bind(widthProperty());
            }
            setGraphic(node);
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

        HBox row = new HBox(8, file, range, spacer, explorer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("review-hunk-header");
        return row;
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
        // Deliberately NOT Hgrow. A growing source column pushes the pin to
        // the right edge of the CONTENT, which on a wide diff is far outside
        // the viewport -- the pins were rendering correctly and were simply
        // never on screen. Sitting the pin immediately after the code keeps
        // it visible without horizontal scrolling; the design right-aligns it
        // to the card edge instead, which a virtualized row whose width is
        // the widest line in the whole diff cannot do.

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

    /** Diagnostic/test-only: the rows currently rendered. */
    List<ReviewDiffRow> diagRows() {
        return List.copyOf(rows);
    }

    /** Diagnostic/test-only: the scope currently rendered. */
    Optional<ReviewScope> diagScope() {
        return Optional.ofNullable(scope);
    }
}
