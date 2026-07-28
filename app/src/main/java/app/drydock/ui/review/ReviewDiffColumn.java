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
import javafx.scene.layout.Region;
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

    private final DiffService diffService;
    private final ExplorerBridge explorerBridge;

    private final Label summaryLabel = new Label();
    private final Button contextToggle = new Button();
    private final ObservableList<ReviewDiffRow> rows = FXCollections.observableArrayList();
    private final ListView<ReviewDiffRow> list = new ListView<>(rows);

    private ReviewScope scope;
    private UnifiedDiff diff = new UnifiedDiff(List.of());
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
        scope = newScope;
        expandedRuns.clear();
        if (newScope == null) {
            diff = new UnifiedDiff(List.of());
            showMessage("Nothing selected.");
            return;
        }
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
                        showMessage("Could not diff: " + UiErrors.unwrap(failure).getMessage());
                        return;
                    }
                    diff = result;
                    rebuild();
                }));
    }

    // ---- presentation state -------------------------------------------------

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
        HBox.setHgrow(source, Priority.ALWAYS);

        HBox box = new HBox(oldNumber, newNumber, sign, source);
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
    private static TextFlow highlighted(String file, String text) {
        SyntaxHighlighter.Language language = SyntaxHighlighter.Language.fromFileName(file);
        List<Node> parts = new ArrayList<>();
        int last = 0;
        for (SyntaxHighlighter.Span span : SyntaxHighlighter.spans(text, language)) {
            if (span.start() > last) {
                parts.add(plain(text.substring(last, span.start())));
            }
            Text styled = plain(text.substring(span.start(), span.start() + span.length()));
            styled.getStyleClass().add(span.styleClass());
            parts.add(styled);
            last = span.start() + span.length();
        }
        if (last < text.length()) {
            parts.add(plain(text.substring(last)));
        }
        TextFlow flow = new TextFlow(parts.toArray(Node[]::new));
        flow.getStyleClass().add("review-code-text");
        return flow;
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
