package app.drydock.ui.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.ChangedLineService;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewAnnotation;
import app.drydock.ui.UiErrors;
import app.drydock.ui.UiFormats;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Diff Review tab (design handoff section C, frame 1a): a scope bar
 * (working tree / upstream / base), a changed-files list, a unified diff
 * with a GitHub-style gutter-annotation interaction (click or drag the
 * {@code +} handle across lines, one composer per range), inline threaded
 * annotation cards, and a send-to-Claude hand-off that -- like every
 * worktree action -- hands the actual work to the live Claude session in
 * the terminal. Shown as the session tab's center while the ◨ Review
 * sub-tab is active (the native terminal overlay is hidden meanwhile).
 *
 * <p>The diff pane is virtualized: a {@link ListView} over the pure
 * {@link ReviewRow} model built by {@link ReviewRowModels}, so only the
 * visible cells exist. Gutter visibility and range-selection styling are
 * property-driven cell updates; composer and annotation-card changes are
 * index operations on the items list -- never scene-graph rebuilds of the
 * whole diff (see docs/plans/review-virtualization-design.md).</p>
 */
public final class ReviewView extends BorderPane {

    private static final Logger LOG = System.getLogger(ReviewView.class.getName());

    /** Cap on rendered diff rows per file; the remainder is noted, not silently dropped. */
    private static final int MAX_RENDERED_ROWS = 3000;

    /** Longest identifier the select-to-search chip accepts (design: ≤64 chars, no whitespace). */
    private static final int MAX_TOKEN_LENGTH = 64;

    /** Jump-outs into the Session Explorer (OpenSessionTab implements both). */
    public interface ExplorerBridge {
        /** Opens {@code relativeFile} in the Explorer viewer at a 1-based line. */
        void openFileAtLine(Path relativeFile, int line);

        /** Runs a text search for {@code token} in the Explorer rail. */
        void searchText(String token);
    }

    private final ManagedSessionId sessionId;
    private final Path checkoutRoot;
    private final DiffService diffService;
    private final ChangedLineService changedLineService;
    private final AnnotationStore annotationStore;
    private final Consumer<String> promptSender;
    private final ExplorerBridge explorerBridge;

    private final ToggleButton workingScope;
    private final ToggleButton upstreamScope;
    private final ToggleButton baseScope;
    private final Label selectedTokenLabel = new Label();
    private final Button searchTokenButton = new Button("Search in Explorer");
    private final HBox tokenChip = new HBox(6);
    private final Button gutterToggle = new Button("#");

    private final VBox filesBox = new VBox(2);
    private final ObservableList<ReviewRow> diffRows = FXCollections.observableArrayList();
    private final ListView<ReviewRow> diffList = new ListView<>(diffRows);

    private final Label summaryLabel = new Label();
    private final Button sendButton = new Button("Send to Claude");
    private final HBox banner = new HBox(8);
    private final Label bannerLabel = new Label();

    private DiffScope scope = DiffScope.BASE;
    private String baseBranch = "master";
    private UnifiedDiff currentDiff = new UnifiedDiff(List.of());
    private UnifiedDiff.FileDiff selectedFile;

    /** Cell-observed presentation state: line-number gutter visibility. */
    private final BooleanProperty gutterVisible = new SimpleBooleanProperty(true);

    /**
     * Cell-observed range selection, as diff-line ordinals ({@code -1} =
     * none). Cells restyle themselves when these change; dragging the
     * {@code +} handle only updates these properties.
     */
    private final IntegerProperty selectionAnchor = new SimpleIntegerProperty(-1);
    private final IntegerProperty selectionHead = new SimpleIntegerProperty(-1);
    private boolean dragging;

    /** The rendered diff lines by ordinal (for range labels and line keys). */
    private List<UnifiedDiff.Line> lineByOrdinal = List.of();
    /** Items-list index of each diff-line ordinal; adjusted on extra-row insert/remove. */
    private int[] itemIndexByOrdinal = new int[0];

    /** The single open composer: its model row plus its view-owned node (survives cell recycling). */
    private ReviewRow.Composer composerRow;
    private Node composerNode;
    private int composerItemIndex = -1;

    /** View-owned annotation-card nodes by annotation id, so reply drafts survive cell recycling. */
    private final Map<String, Node> cardNodes = new HashMap<>();

    public ReviewView(ManagedSessionId sessionId, Path checkoutRoot, Path repositoryRoot,
                      DiffService diffService, ChangedLineService changedLineService,
                      GitStatusService gitStatusService, AnnotationStore annotationStore,
                      Consumer<String> promptSender, ExplorerBridge explorerBridge) {
        this.sessionId = sessionId;
        this.checkoutRoot = checkoutRoot;
        this.diffService = diffService;
        this.changedLineService = changedLineService;
        this.annotationStore = annotationStore;
        this.promptSender = promptSender;
        this.explorerBridge = explorerBridge;

        getStyleClass().add("review-root");

        workingScope = scopePill("Working tree", "unstaged + staged", DiffScope.WORKING_TREE);
        upstreamScope = scopePill("origin", "vs upstream", DiffScope.UPSTREAM);
        baseScope = scopePill("master (base)", "feature branch", DiffScope.BASE);
        baseScope.setSelected(true);

        gutterToggle.getStyleClass().add("viewer-gutter-toggle");
        gutterToggle.setFocusTraversable(false);
        gutterToggle.setTooltip(new Tooltip("Toggle line numbers"));
        gutterToggle.setOnAction(e -> gutterVisible.set(!gutterVisible.get()));

        selectedTokenLabel.getStyleClass().add("review-token-label");
        searchTokenButton.getStyleClass().add("review-token-search");
        searchTokenButton.setFocusTraversable(false);
        tokenChip.getStyleClass().add("review-token-chip");
        tokenChip.setAlignment(Pos.CENTER_LEFT);
        tokenChip.getChildren().setAll(selectedTokenLabel, searchTokenButton);
        tokenChip.setVisible(false);
        tokenChip.setManaged(false);

        Region scopeSpacer = new Region();
        HBox.setHgrow(scopeSpacer, Priority.ALWAYS);
        HBox scopeBar = new HBox(6, workingScope, upstreamScope, baseScope, scopeSpacer, tokenChip, gutterToggle);
        scopeBar.setAlignment(Pos.CENTER_LEFT);
        scopeBar.getStyleClass().add("review-scope-bar");
        setTop(scopeBar);

        filesBox.getStyleClass().add("review-files");
        ScrollPane filesScroll = new ScrollPane(filesBox);
        filesScroll.setFitToWidth(true);
        filesScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        filesScroll.getStyleClass().add("review-files-scroll");
        filesScroll.setPrefWidth(260);
        filesScroll.setMinWidth(220);
        setLeft(filesScroll);

        diffList.getStyleClass().add("review-diff-list");
        diffList.setFocusTraversable(false);
        diffList.setCellFactory(v -> new ReviewDiffCell());
        setCenter(diffList);

        summaryLabel.getStyleClass().add("review-summary");
        sendButton.getStyleClass().add("review-send-button");
        sendButton.setFocusTraversable(false);
        sendButton.setOnAction(e -> sendToClaude());
        bannerLabel.getStyleClass().add("review-banner-label");
        Button rerun = new Button("Re-run diff →");
        rerun.getStyleClass().add("review-rerun-button");
        rerun.setFocusTraversable(false);
        rerun.setOnAction(e -> {
            hideBanner();
            reDiff();
        });
        banner.getChildren().setAll(bannerLabel, rerun);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("review-banner");
        banner.setVisible(false);
        banner.setManaged(false);

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, summaryLabel, banner, footerSpacer, sendButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("review-footer");
        setBottom(footer);

        // End a gutter drag wherever the mouse is released.
        setOnMouseReleased(e -> endDrag());

        // The base branch is the repository main checkout's current branch;
        // resolved async, then the default (BASE) scope diffs against it.
        gitStatusService.getStatus(repositoryRoot).whenComplete((status, failure) ->
                Platform.runLater(() -> {
                    if (failure == null) {
                        applyBaseStatus(status);
                    }
                    reDiff();
                }));
        gitStatusService.getStatus(checkoutRoot).whenComplete((status, failure) ->
                Platform.runLater(() -> {
                    if (failure == null && status.upstream().isPresent()) {
                        GitStatus.UpstreamStatus upstream = status.upstream().get();
                        upstreamScope.setGraphic(pillGraphic(upstream.ref(),
                                upstream.ahead() + (upstream.ahead() == 1 ? " commit ahead" : " commits ahead")));
                    }
                }));
        updateSummary();
    }

    private void applyBaseStatus(GitStatus status) {
        if (status.branch() instanceof GitBranchState.OnBranch onBranch) {
            baseBranch = onBranch.name();
            baseScope.setGraphic(pillGraphic(baseBranch + " (base)", "feature branch"));
        }
    }

    private ToggleButton scopePill(String label, String sub, DiffScope pillScope) {
        ToggleButton pill = new ToggleButton();
        pill.setGraphic(pillGraphic(label, sub));
        pill.getStyleClass().add("review-scope-pill");
        pill.setFocusTraversable(false);
        pill.setOnAction(e -> {
            // Behaves as a radio group without allowing an empty selection.
            if (!pill.isSelected()) {
                pill.setSelected(true);
                return;
            }
            selectScope(pillScope);
        });
        return pill;
    }

    private static VBox pillGraphic(String label, String sub) {
        Label title = new Label(label);
        title.getStyleClass().add("review-scope-title");
        Label caption = new Label(sub);
        caption.getStyleClass().add("review-scope-sub");
        return new VBox(0, title, caption);
    }

    /** Switching scope re-diffs and resets the current selection/composer (design section C). */
    private void selectScope(DiffScope newScope) {
        scope = newScope;
        workingScope.setSelected(newScope == DiffScope.WORKING_TREE);
        upstreamScope.setSelected(newScope == DiffScope.UPSTREAM);
        baseScope.setSelected(newScope == DiffScope.BASE);
        clearSelection();
        hideBanner();
        reDiff();
    }

    /** Re-runs the diff for the current scope off-thread and re-renders. */
    public void reDiff() {
        showDiffMessage("Diffing…");
        changedLineService.invalidate(checkoutRoot);
        diffService.diff(checkoutRoot, scope, baseBranch)
                .whenComplete((diff, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        LOG.log(Level.DEBUG, "Diff failed for " + checkoutRoot + " scope " + scope, failure);
                        currentDiff = new UnifiedDiff(List.of());
                        selectedFile = null;
                        renderFileList();
                        showDiffMessage(scope == DiffScope.UPSTREAM
                                ? "No upstream is configured for this branch."
                                : "Could not diff: " + UiErrors.unwrap(failure).getMessage());
                        updateSummary();
                        return;
                    }
                    currentDiff = diff;
                    selectedFile = diff.files().stream()
                            .filter(file -> selectedFile != null && file.path().equals(selectedFile.path()))
                            .findFirst()
                            .orElse(diff.files().isEmpty() ? null : diff.files().get(0));
                    renderFileList();
                    renderSelectedFile();
                    updateSummary();
                }));
    }

    private void showDiffMessage(String message) {
        resetRowState();
        diffRows.setAll(List.of(new ReviewRow.Message(message)));
    }

    // ---- Changed-files list -------------------------------------------------

    private void renderFileList() {
        filesBox.getChildren().clear();
        if (currentDiff.files().isEmpty()) {
            Label empty = new Label("No changes in this scope.");
            empty.getStyleClass().add("review-files-empty");
            filesBox.getChildren().add(empty);
            return;
        }
        for (UnifiedDiff.FileDiff file : currentDiff.files()) {
            filesBox.getChildren().add(buildFileRow(file));
        }
    }

    private Region buildFileRow(UnifiedDiff.FileDiff file) {
        Label marker = new Label(file.kind());
        marker.getStyleClass().addAll("review-file-marker", UiFormats.markerStyleClass(file.kind()));

        Label name = new Label(Path.of(file.path()).getFileName().toString());
        name.getStyleClass().add("review-file-name");
        Tooltip.install(name, new Tooltip(file.path()));

        Label stats = new Label("+" + file.insertions() + " −" + file.deletions());
        stats.getStyleClass().add("review-file-stats");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(7, marker, name, spacer, stats);
        if (scope == DiffScope.WORKING_TREE) {
            Label staged = new Label(file.staged() ? "staged" : "unstaged");
            staged.getStyleClass().add(file.staged() ? "review-staged-chip" : "review-unstaged-chip");
            row.getChildren().add(row.getChildren().indexOf(stats), staged);
        }
        long annotationCount = annotationStore.forScope(sessionId, scope).stream()
                .filter(a -> a.file().equals(file.path()))
                .count();
        if (annotationCount > 0) {
            Label count = new Label("✎ " + annotationCount);
            count.getStyleClass().add("review-annotation-count");
            row.getChildren().add(count);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("review-file-row");
        if (selectedFile != null && selectedFile.path().equals(file.path())) {
            row.getStyleClass().add("active");
        }
        row.setOnMouseClicked(e -> {
            selectedFile = file;
            clearSelection();
            renderFileList();
            renderSelectedFile();
        });
        return row;
    }

    // ---- Unified diff (virtualized) -----------------------------------------

    /**
     * Rebuilds the diff list model for the selected file. This is the only
     * "rebuild the model" path -- and it runs only when the underlying data
     * changed (new diff or file selection); all other interactions are
     * cell-level or single-index updates.
     */
    private void renderSelectedFile() {
        resetRowState();
        if (selectedFile == null) {
            showDiffMessage(currentDiff.files().isEmpty() ? "No changes in this scope." : "Select a changed file.");
            return;
        }
        diffRows.setAll(ReviewRowModels.build(
                selectedFile, annotationStore.forScope(sessionId, scope), MAX_RENDERED_ROWS));
        reindexRows();
        diffList.scrollTo(0);
    }

    /** Drops per-file row state: selection, ordinal indexes, cached card/composer nodes. */
    private void resetRowState() {
        // Stale ordinals must not re-highlight rows of a rebuilt model.
        dragging = false;
        selectionAnchor.set(-1);
        selectionHead.set(-1);
        lineByOrdinal = List.of();
        itemIndexByOrdinal = new int[0];
        composerRow = null;
        composerNode = null;
        composerItemIndex = -1;
        cardNodes.clear();
    }

    /** Recomputes the ordinal → items-index map after a full model build. */
    private void reindexRows() {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < diffRows.size(); i++) {
            if (diffRows.get(i) instanceof ReviewRow.DiffLine diffLine) {
                lines.add(diffLine.line());
                indexes.add(i);
            }
        }
        lineByOrdinal = List.copyOf(lines);
        itemIndexByOrdinal = indexes.stream().mapToInt(Integer::intValue).toArray();
    }

    private int lineCount() {
        return itemIndexByOrdinal.length;
    }

    /** Adjusts the ordinal index map after inserting/removing one extra row at {@code itemsIndex}. */
    private void shiftOrdinalIndexesFrom(int itemsIndex, int delta) {
        for (int i = 0; i < itemIndexByOrdinal.length; i++) {
            if (itemIndexByOrdinal[i] >= itemsIndex) {
                itemIndexByOrdinal[i] += delta;
            }
        }
        if (composerItemIndex >= itemsIndex) {
            composerItemIndex += delta;
        }
    }

    // ---- Cells --------------------------------------------------------------

    /**
     * Renders one {@link ReviewRow}. Diff-line content is cheap and rebuilt
     * on item change; annotation cards and the composer mount view-owned
     * nodes so typed text survives cell recycling. The cell observes the
     * selection-range and gutter properties and restyles itself in place.
     */
    private final class ReviewDiffCell extends ListCell<ReviewRow> {

        private HBox lineRowNode;
        private int ordinal = -1;

        ReviewDiffCell() {
            getStyleClass().add("review-diff-cell");
            InvalidationListener onSelectionChange = obs -> applySelectionStyle();
            // The cell lives exactly as long as the ListView (and the view),
            // so plain listeners on the view's properties cannot leak.
            selectionAnchor.addListener(onSelectionChange);
            selectionHead.addListener(onSelectionChange);
        }

        @Override
        protected void updateItem(ReviewRow row, boolean empty) {
            super.updateItem(row, empty);
            lineRowNode = null;
            ordinal = -1;
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            Node node = switch (row) {
                case ReviewRow.Message message -> messageLabel(message.text());
                case ReviewRow.Truncation truncation ->
                        messageLabel("… diff truncated at " + truncation.limit() + " lines");
                case ReviewRow.Breadcrumb breadcrumb -> buildBreadcrumb(breadcrumb.path());
                case ReviewRow.HunkHeader header -> hunkHeaderLabel(header.text());
                case ReviewRow.DiffLine diffLine -> {
                    lineRowNode = buildDiffRow(diffLine.line(), diffLine.ordinal());
                    ordinal = diffLine.ordinal();
                    applySelectionStyle();
                    yield lineRowNode;
                }
                case ReviewRow.AnnotationCard card -> cardNode(card.annotation());
                // A stale composer row (node already discarded) renders empty.
                case ReviewRow.Composer c -> composerNode != null ? composerNode : new Region();
            };
            if (node instanceof Region region) {
                // Cells do not stretch their graphic; full-width row
                // backgrounds (row-add/del, hunk headers) need the bind.
                region.prefWidthProperty().bind(widthProperty());
            }
            setGraphic(node);
        }

        private void applySelectionStyle() {
            if (lineRowNode == null) {
                return;
            }
            int anchor = selectionAnchor.get();
            int head = selectionHead.get();
            boolean selected = anchor >= 0 && head >= 0
                    && ordinal >= Math.min(anchor, head) && ordinal <= Math.max(anchor, head);
            List<String> classes = lineRowNode.getStyleClass();
            if (selected && !classes.contains("row-selected")) {
                classes.add("row-selected");
            } else if (!selected) {
                classes.remove("row-selected");
            }
        }
    }

    private static Label messageLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("review-diff-message");
        label.setWrapText(true);
        return label;
    }

    private static Label hunkHeaderLabel(String text) {
        Label header = new Label(text);
        header.getStyleClass().add("review-hunk-header");
        header.setMaxWidth(Double.MAX_VALUE);
        return header;
    }

    private static Region buildBreadcrumb(String path) {
        HBox breadcrumb = new HBox(4);
        breadcrumb.getStyleClass().add("viewer-breadcrumb");
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        breadcrumb.getChildren().setAll(UiFormats.breadcrumbSegments(Path.of(path)));
        return breadcrumb;
    }

    private HBox buildDiffRow(UnifiedDiff.Line line, int rowOrdinal) {
        // The annotation handle: a faint accent + that brightens on hover;
        // click = single line, drag across rows (or Shift-click) = range.
        Label handle = new Label("+");
        handle.getStyleClass().add("review-annotate-handle");
        handle.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (e.isShiftDown() && selectionAnchor.get() >= 0) {
                selectionHead.set(rowOrdinal);
            } else {
                selectionAnchor.set(rowOrdinal);
                selectionHead.set(rowOrdinal);
            }
            dragging = true;
            e.consume();
        });
        // startFullDrag() may only be called from a DRAG_DETECTED handler
        // (an IllegalStateException otherwise); the press handler above only
        // records the anchor.
        handle.setOnDragDetected(e -> {
            if (dragging) {
                handle.startFullDrag();
                e.consume();
            }
        });

        Label oldNumber = new Label(line.oldLine().isPresent() ? String.valueOf(line.oldLine().getAsInt()) : "");
        oldNumber.getStyleClass().add("review-line-number");
        Label newNumber = new Label(line.newLine().isPresent() ? String.valueOf(line.newLine().getAsInt()) : "");
        newNumber.getStyleClass().add("review-line-number");
        // The gutter toggle is a pure cell-level update: no rebuild.
        oldNumber.visibleProperty().bind(gutterVisible);
        oldNumber.managedProperty().bind(gutterVisible);
        newNumber.visibleProperty().bind(gutterVisible);
        newNumber.managedProperty().bind(gutterVisible);

        Label sign = new Label(switch (line.kind()) {
            case ADD -> "+";
            case DEL -> "−";
            case CONTEXT -> " ";
        });
        sign.getStyleClass().addAll("review-line-sign", switch (line.kind()) {
            case ADD -> "sign-add";
            case DEL -> "sign-del";
            case CONTEXT -> "sign-context";
        });

        Text text = new Text(line.text());
        text.getStyleClass().add("review-line-text");
        // Select-to-search: double-clicking an identifier inside the diff
        // surfaces the "Selected <token> · Search in Explorer" chip.
        text.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                int charIndex = text.hitTest(new Point2D(e.getX(), e.getY())).getCharIndex();
                tokenAt(line.text(), charIndex).ifPresent(ReviewView.this::showTokenChip);
                e.consume();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(0, handle, oldNumber, newNumber, sign, text, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("review-diff-row", switch (line.kind()) {
            case ADD -> "row-add";
            case DEL -> "row-del";
            case CONTEXT -> "row-context";
        });

        // Changed lines that exist in the new file expose ⤢ open-in-Explorer.
        if (line.kind() == UnifiedDiff.Line.Kind.ADD && line.newLine().isPresent() && selectedFile != null) {
            Button openInExplorer = new Button("⤢");
            openInExplorer.getStyleClass().add("review-open-in-explorer");
            openInExplorer.setFocusTraversable(false);
            openInExplorer.setTooltip(new Tooltip("Open in Explorer at this line"));
            openInExplorer.visibleProperty().bind(row.hoverProperty());
            String filePath = selectedFile.path();
            int lineNumber = line.newLine().getAsInt();
            openInExplorer.setOnAction(e -> explorerBridge.openFileAtLine(Path.of(filePath), lineNumber));
            row.getChildren().add(openInExplorer);
        }

        // Range selection: dragging the + across rows extends the head.
        row.setOnMouseDragEntered(e -> {
            if (dragging) {
                selectionHead.set(rowOrdinal);
            }
        });
        row.setOnMouseReleased(e -> endDrag());

        // Clicking a row (single click, not on the handle) dismisses the
        // select-to-search chip when the selection empties.
        row.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 && e.getTarget() != text) {
                hideTokenChip();
            }
        });
        return row;
    }

    /** Expands {@code charIndex} to the surrounding identifier ({@code [A-Za-z0-9_$.]+}), if usable. */
    static Optional<String> tokenAt(String lineText, int charIndex) {
        if (lineText.isEmpty() || charIndex < 0 || charIndex >= lineText.length()
                || !isTokenChar(lineText.charAt(charIndex))) {
            return Optional.empty();
        }
        int start = charIndex;
        while (start > 0 && isTokenChar(lineText.charAt(start - 1))) {
            start--;
        }
        int end = charIndex + 1;
        while (end < lineText.length() && isTokenChar(lineText.charAt(end))) {
            end++;
        }
        String token = lineText.substring(start, end);
        if (token.isBlank() || token.length() > MAX_TOKEN_LENGTH || token.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }

    private void showTokenChip(String token) {
        selectedTokenLabel.setText("Selected " + token);
        searchTokenButton.setOnAction(e -> {
            explorerBridge.searchText(token);
            hideTokenChip();
        });
        tokenChip.setVisible(true);
        tokenChip.setManaged(true);
    }

    private void hideTokenChip() {
        tokenChip.setVisible(false);
        tokenChip.setManaged(false);
    }

    // ---- Range selection + composer -----------------------------------------

    private void endDrag() {
        if (!dragging) {
            return;
        }
        dragging = false;
        int anchor = selectionAnchor.get();
        int head = selectionHead.get();
        if (anchor < 0 || head < 0) {
            return;
        }
        openComposer(Math.min(anchor, head), Math.max(anchor, head));
    }

    private void clearSelection() {
        dragging = false;
        selectionAnchor.set(-1);
        selectionHead.set(-1);
        removeComposer();
    }

    /** Removes the composer's model row (a single index operation) and drops its node. */
    private void removeComposer() {
        if (composerRow == null) {
            return;
        }
        int index = composerItemIndex >= 0 && composerItemIndex < diffRows.size()
                && diffRows.get(composerItemIndex) == composerRow
                ? composerItemIndex
                : diffRows.indexOf(composerRow);
        composerRow = null;
        composerNode = null;
        composerItemIndex = -1;
        if (index >= 0) {
            diffRows.remove(index);
            shiftOrdinalIndexesFrom(index, -1);
        }
    }

    /** Opens ONE composer for the whole span, under the range's last row (design: header shows La–b). */
    private void openComposer(int startOrdinal, int endOrdinal) {
        removeComposer();
        if (startOrdinal < 0 || endOrdinal >= lineCount() || selectedFile == null) {
            return;
        }

        Label rangeChip = new Label(rangeLabel(startOrdinal, endOrdinal));
        rangeChip.getStyleClass().add("review-range-chip");

        TextArea input = new TextArea();
        input.getStyleClass().add("review-composer-input");
        input.setPromptText("Describe the concern or the change you want validated…");
        input.setPrefRowCount(2);
        input.setWrapText(true);

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("worktree-cancel-button");
        cancel.setFocusTraversable(false);
        cancel.setOnAction(e -> clearSelection());
        Button add = new Button("Add annotation");
        add.getStyleClass().add("review-add-annotation");
        add.setFocusTraversable(false);
        add.setOnAction(e -> {
            String message = input.getText() == null ? "" : input.getText().strip();
            if (message.isEmpty()) {
                return;
            }
            ReviewAnnotation annotation = ReviewAnnotation.create(sessionId, scope, selectedFile.path(),
                    lineByOrdinal.get(startOrdinal).lineKey(), lineByOrdinal.get(endOrdinal).lineKey(),
                    new ReviewAnnotation.Message("You", Instant.now(), message));
            annotationStore.add(annotation);
            clearSelection();
            insertCardRow(annotation, endOrdinal);
            renderFileList();
            updateSummary();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, rangeChip, spacer, cancel, add);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, buttons, input);
        box.getStyleClass().add("review-composer");
        composerNode = box;
        composerRow = new ReviewRow.Composer(startOrdinal, endOrdinal);
        int insertAt = itemIndexByOrdinal[endOrdinal] + 1;
        diffRows.add(insertAt, composerRow);
        // composerItemIndex is still -1 here, so the shift cannot touch it.
        shiftOrdinalIndexesFrom(insertAt, +1);
        composerItemIndex = insertAt;
        diffList.scrollTo(insertAt);
        // The cell mounts the node on the next pulse; focus after that.
        Platform.runLater(input::requestFocus);
    }

    private String rangeLabel(int startOrdinal, int endOrdinal) {
        UnifiedDiff.Line start = lineByOrdinal.get(startOrdinal);
        UnifiedDiff.Line end = lineByOrdinal.get(endOrdinal);
        int a = start.newLine().orElse(start.oldLine().orElse(0));
        int b = end.newLine().orElse(end.oldLine().orElse(0));
        return startOrdinal == endOrdinal ? "L" + a : "L" + a + "–" + b;
    }

    // ---- Threaded annotation cards ------------------------------------------

    /** Inserts one card row after {@code endOrdinal}'s line (behind any cards already anchored there). */
    private void insertCardRow(ReviewAnnotation annotation, int endOrdinal) {
        if (endOrdinal < 0 || endOrdinal >= lineCount()) {
            return;
        }
        int at = itemIndexByOrdinal[endOrdinal] + 1;
        while (at < diffRows.size() && diffRows.get(at) instanceof ReviewRow.AnnotationCard) {
            at++;
        }
        diffRows.add(at, new ReviewRow.AnnotationCard(annotation));
        shiftOrdinalIndexesFrom(at, +1);
    }

    /** Swaps the card row (and drops the cached node) of {@code updated}; single-index model update. */
    private void replaceCardRow(ReviewAnnotation updated) {
        cardNodes.remove(updated.id());
        for (int i = 0; i < diffRows.size(); i++) {
            if (diffRows.get(i) instanceof ReviewRow.AnnotationCard card
                    && card.annotation().id().equals(updated.id())) {
                diffRows.set(i, new ReviewRow.AnnotationCard(updated));
                return;
            }
        }
    }

    /** The view-owned card node for {@code annotation} (built once per annotation version). */
    private Node cardNode(ReviewAnnotation annotation) {
        return cardNodes.computeIfAbsent(annotation.id(), id -> buildAnnotationCard(annotation));
    }

    private Node buildAnnotationCard(ReviewAnnotation annotation) {
        Label rangeChip = new Label(annotation.startKey().equals(annotation.endKey())
                ? keyLabel(annotation.startKey())
                : keyLabel(annotation.startKey()) + "–" + keyLabel(annotation.endKey()).substring(1));
        rangeChip.getStyleClass().add("review-range-chip");

        Label status = new Label(annotation.status().name().toLowerCase(Locale.ROOT));
        status.getStyleClass().addAll("review-status-pill", switch (annotation.status()) {
            case OPEN -> "status-open";
            case SENT -> "status-sent";
            case RESOLVED -> "status-resolved";
            case FIXED -> "status-fixed";
        });

        Button toggle = new Button(annotation.status() == AnnotationStatus.OPEN ? "Resolve" : "Reopen");
        toggle.getStyleClass().add("review-thread-action");
        toggle.setFocusTraversable(false);
        toggle.setOnAction(e -> {
            ReviewAnnotation updated = annotation.withStatus(annotation.status() == AnnotationStatus.OPEN
                    ? AnnotationStatus.RESOLVED
                    : AnnotationStatus.OPEN);
            annotationStore.update(updated);
            replaceCardRow(updated);
            updateSummary();
        });

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, rangeChip, status, headerSpacer, toggle);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, header);
        card.getStyleClass().addAll("review-thread-card",
                annotation.status() == AnnotationStatus.FIXED ? "thread-fixed" : "thread-normal");

        for (ReviewAnnotation.Message message : annotation.thread()) {
            Label author = new Label(message.author());
            author.getStyleClass().addAll("review-thread-author",
                    "Claude".equals(message.author()) ? "author-claude" : "author-you");
            Label text = new Label(message.text());
            text.getStyleClass().add("review-thread-text");
            text.setWrapText(true);
            card.getChildren().add(new VBox(2, author, text));
        }

        TextArea reply = new TextArea();
        reply.getStyleClass().add("review-composer-input");
        reply.setPromptText("Reply…");
        reply.setPrefRowCount(1);
        reply.setWrapText(true);
        Button replyButton = new Button("Reply");
        replyButton.getStyleClass().add("review-thread-action");
        replyButton.setFocusTraversable(false);
        replyButton.setOnAction(e -> {
            String message = reply.getText() == null ? "" : reply.getText().strip();
            if (message.isEmpty()) {
                return;
            }
            ReviewAnnotation updated = annotation.withReply(
                    new ReviewAnnotation.Message("You", Instant.now(), message));
            annotationStore.update(updated);
            replaceCardRow(updated);
        });
        HBox replyRow = new HBox(8, reply, replyButton);
        HBox.setHgrow(reply, Priority.ALWAYS);
        replyRow.setAlignment(Pos.CENTER);
        card.getChildren().add(replyRow);
        return card;
    }

    private static String keyLabel(String key) {
        return "L" + (key.length() > 1 ? key.substring(1) : key);
    }

    // ---- Send-to-Claude hand-off --------------------------------------------

    private void updateSummary() {
        List<ReviewAnnotation> annotations = annotationStore.forScope(sessionId, scope);
        long open = annotations.stream().filter(a -> a.status() == AnnotationStatus.OPEN).count();
        long sent = annotations.stream().filter(a -> a.status() == AnnotationStatus.SENT).count();
        summaryLabel.setText(open + " open · " + annotations.size()
                + (annotations.size() == 1 ? " annotation" : " annotations") + " · " + sent + " sent");
        sendButton.setDisable(open == 0);
    }

    /**
     * Posts the diff scope + every OPEN annotation into the session's live
     * Claude terminal (the same hand-off principle as the worktree Finish
     * actions -- the app does not validate anything itself). Each sent
     * thread is marked {@link AnnotationStatus#SENT} immediately -- the app
     * records only what it knows (the hand-off happened), never a fabricated
     * outcome -- and the banner offers a re-diff to see the real result.
     */
    private void sendToClaude() {
        List<ReviewAnnotation> open = annotationStore.forScope(sessionId, scope).stream()
                .filter(a -> a.status() == AnnotationStatus.OPEN)
                .toList();
        if (open.isEmpty()) {
            return;
        }
        StringBuilder prompt = new StringBuilder("Review annotations on the ").append(scopeDescription())
                .append(" diff of this checkout — address each one, then summarize what you changed: ");
        int i = 1;
        for (ReviewAnnotation annotation : open) {
            prompt.append('[').append(i++).append("] ").append(annotation.file())
                    .append(' ').append(keyLabel(annotation.startKey()));
            if (!annotation.startKey().equals(annotation.endKey())) {
                prompt.append('–').append(keyLabel(annotation.endKey()).substring(1));
            }
            prompt.append(": ").append(annotation.thread().isEmpty() ? ""
                    : annotation.thread().get(0).text().replaceAll("\\s+", " ").strip()).append(". ");
        }
        promptSender.accept(prompt.toString().strip());

        // The app does NOT validate -- Claude does, in the live terminal.
        // Record only the hand-off (SENT, no fabricated reply, no timer);
        // the banner's "Re-run diff" shows the real result.
        for (ReviewAnnotation annotation : open) {
            ReviewAnnotation updated = annotation.withStatus(AnnotationStatus.SENT);
            annotationStore.update(updated);
            replaceCardRow(updated);
        }
        bannerLabel.setText(open.size() + (open.size() == 1 ? " annotation" : " annotations")
                + " sent to Claude");
        banner.setVisible(true);
        banner.setManaged(true);
        updateSummary();
    }

    private String scopeDescription() {
        return switch (scope) {
            case WORKING_TREE -> "working-tree";
            case UPSTREAM -> "upstream";
            case BASE -> baseBranch + "...HEAD";
        };
    }

    private void hideBanner() {
        banner.setVisible(false);
        banner.setManaged(false);
    }
}
