package app.cpm.ui.review;

import app.cpm.domain.ManagedSessionId;
import app.cpm.git.ChangedLineService;
import app.cpm.git.DiffScope;
import app.cpm.git.DiffService;
import app.cpm.git.GitBranchState;
import app.cpm.git.GitStatus;
import app.cpm.git.GitStatusService;
import app.cpm.git.UnifiedDiff;
import app.cpm.review.AnnotationStatus;
import app.cpm.review.AnnotationStore;
import app.cpm.review.ReviewAnnotation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import javafx.util.Duration;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Diff Review tab (design handoff section C, frame 1a): a scope bar
 * (working tree / upstream / base), a changed-files list, a unified diff
 * with a GitHub-style gutter-annotation interaction (click or drag the
 * {@code +} handle across lines, one composer per range), inline threaded
 * annotation cards, and a send-to-Claude validation loop that -- like
 * every worktree action -- hands the actual work to the live Claude
 * session in the terminal. Shown as the session tab's center while the
 * ◨ Review sub-tab is active (the native terminal overlay is hidden
 * meanwhile).
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
    private final VBox diffBox = new VBox(0);
    private final ScrollPane diffScroll = new ScrollPane(diffBox);
    private final Label diffMessage = new Label();

    private final Label summaryLabel = new Label();
    private final Button sendButton = new Button("Send to Claude");
    private final HBox banner = new HBox(8);
    private final Label bannerLabel = new Label();

    private DiffScope scope = DiffScope.BASE;
    private String baseBranch = "master";
    private UnifiedDiff currentDiff = new UnifiedDiff(List.of());
    private UnifiedDiff.FileDiff selectedFile;
    private boolean gutterVisible = true;

    /** Rows of the currently rendered file, in render order (for range selection). */
    private final List<DiffRow> rows = new ArrayList<>();
    private int dragAnchor = -1;
    private int dragHead = -1;
    private boolean dragging;
    private Node composer;

    /** One rendered diff line row plus its model line. */
    private record DiffRow(UnifiedDiff.Line line, HBox node) {
    }

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
        gutterToggle.setOnAction(e -> {
            gutterVisible = !gutterVisible;
            renderSelectedFile();
        });

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

        diffBox.getStyleClass().add("review-diff");
        diffScroll.setFitToWidth(true);
        diffScroll.getStyleClass().add("review-diff-scroll");
        diffMessage.getStyleClass().add("review-diff-message");
        diffMessage.setWrapText(true);
        setCenter(diffScroll);

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
                                : "Could not diff: " + rootMessage(failure));
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

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    private void showDiffMessage(String message) {
        diffMessage.setText(message);
        rows.clear();
        diffBox.getChildren().setAll(diffMessage);
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
        marker.getStyleClass().addAll("review-file-marker", switch (file.kind()) {
            case "A" -> "marker-added";
            case "D" -> "marker-deleted";
            default -> "marker-modified";
        });

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

    // ---- Unified diff -------------------------------------------------------

    private void renderSelectedFile() {
        rows.clear();
        composer = null;
        diffBox.getChildren().clear();
        if (selectedFile == null) {
            showDiffMessage(currentDiff.files().isEmpty() ? "No changes in this scope." : "Select a changed file.");
            return;
        }

        diffBox.getChildren().add(buildBreadcrumb(selectedFile.path()));

        int rendered = 0;
        for (UnifiedDiff.Hunk hunk : selectedFile.hunks()) {
            Label header = new Label(hunk.header());
            header.getStyleClass().add("review-hunk-header");
            header.setMaxWidth(Double.MAX_VALUE);
            diffBox.getChildren().add(header);
            for (UnifiedDiff.Line line : hunk.lines()) {
                if (rendered >= MAX_RENDERED_ROWS) {
                    break;
                }
                HBox rowNode = buildDiffRow(line, rows.size());
                rows.add(new DiffRow(line, rowNode));
                diffBox.getChildren().add(rowNode);
                rendered++;
            }
            if (rendered >= MAX_RENDERED_ROWS) {
                Label truncated = new Label("… diff truncated at " + MAX_RENDERED_ROWS + " lines");
                truncated.getStyleClass().add("review-diff-message");
                diffBox.getChildren().add(truncated);
                break;
            }
        }
        renderAnnotations();
    }

    private Region buildBreadcrumb(String path) {
        HBox breadcrumb = new HBox(4);
        breadcrumb.getStyleClass().add("viewer-breadcrumb");
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        int i = 0;
        for (Path segment : Path.of(path)) {
            if (i++ > 0) {
                Label sep = new Label("›");
                sep.getStyleClass().add("breadcrumb-separator");
                breadcrumb.getChildren().add(sep);
            }
            Label part = new Label(segment.toString());
            part.getStyleClass().add("breadcrumb-segment");
            breadcrumb.getChildren().add(part);
        }
        return breadcrumb;
    }

    private HBox buildDiffRow(UnifiedDiff.Line line, int rowIndex) {
        // The annotation handle: a faint accent + that brightens on hover;
        // click = single line, drag across rows (or Shift-click) = range.
        Label handle = new Label("+");
        handle.getStyleClass().add("review-annotate-handle");
        handle.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (e.isShiftDown() && dragAnchor >= 0) {
                dragHead = rowIndex;
            } else {
                dragAnchor = rowIndex;
                dragHead = rowIndex;
            }
            dragging = true;
            applySelectionStyles();
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
                tokenAt(line.text(), charIndex).ifPresent(this::showTokenChip);
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
        oldNumber.setVisible(gutterVisible);
        oldNumber.setManaged(gutterVisible);
        newNumber.setVisible(gutterVisible);
        newNumber.setManaged(gutterVisible);

        // Changed lines that exist in the new file expose ⤢ open-in-Explorer.
        if (line.kind() == UnifiedDiff.Line.Kind.ADD && line.newLine().isPresent()) {
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
                dragHead = rowIndex;
                applySelectionStyles();
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
        if (dragAnchor < 0 || dragHead < 0) {
            return;
        }
        int start = Math.min(dragAnchor, dragHead);
        int end = Math.max(dragAnchor, dragHead);
        openComposer(start, end);
    }

    private void applySelectionStyles() {
        int start = Math.min(dragAnchor, dragHead);
        int end = Math.max(dragAnchor, dragHead);
        for (int i = 0; i < rows.size(); i++) {
            boolean selected = dragAnchor >= 0 && i >= start && i <= end;
            List<String> classes = rows.get(i).node().getStyleClass();
            if (selected && !classes.contains("row-selected")) {
                classes.add("row-selected");
            } else if (!selected) {
                classes.remove("row-selected");
            }
        }
    }

    private void clearSelection() {
        dragAnchor = -1;
        dragHead = -1;
        dragging = false;
        removeComposer();
        applySelectionStyles();
    }

    private void removeComposer() {
        if (composer != null) {
            diffBox.getChildren().remove(composer);
            composer = null;
        }
    }

    /** Opens ONE composer for the whole span, under the range's last row (design: header shows La–b). */
    private void openComposer(int startRow, int endRow) {
        removeComposer();
        if (startRow < 0 || endRow >= rows.size() || selectedFile == null) {
            return;
        }

        Label rangeChip = new Label(rangeLabel(startRow, endRow));
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
            annotationStore.add(ReviewAnnotation.create(sessionId, scope, selectedFile.path(),
                    rows.get(startRow).line().lineKey(), rows.get(endRow).line().lineKey(),
                    new ReviewAnnotation.Message("You", Instant.now(), message)));
            clearSelection();
            renderFileList();
            renderSelectedFile();
            updateSummary();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, rangeChip, spacer, cancel, add);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, buttons, input);
        box.getStyleClass().add("review-composer");
        composer = box;
        int insertAt = diffBox.getChildren().indexOf(rows.get(endRow).node()) + 1;
        diffBox.getChildren().add(insertAt, box);
        input.requestFocus();
    }

    private String rangeLabel(int startRow, int endRow) {
        UnifiedDiff.Line start = rows.get(startRow).line();
        UnifiedDiff.Line end = rows.get(endRow).line();
        int a = start.newLine().orElse(start.oldLine().orElse(0));
        int b = end.newLine().orElse(end.oldLine().orElse(0));
        return startRow == endRow ? "L" + a : "L" + a + "–" + b;
    }

    // ---- Threaded annotation cards ------------------------------------------

    /** Renders every annotation of the current scope+file as an inline card under its range's last line. */
    private void renderAnnotations() {
        if (selectedFile == null) {
            return;
        }
        for (ReviewAnnotation annotation : annotationStore.forScope(sessionId, scope)) {
            if (!annotation.file().equals(selectedFile.path())) {
                continue;
            }
            int anchorRow = rowIndexForKey(annotation.endKey())
                    .orElseGet(() -> rowIndexForKey(annotation.startKey()).orElse(-1));
            if (anchorRow < 0) {
                continue; // range no longer in this diff; counts still include it
            }
            Node card = buildAnnotationCard(annotation);
            int insertAt = diffBox.getChildren().indexOf(rows.get(anchorRow).node()) + 1;
            diffBox.getChildren().add(insertAt, card);
        }
    }

    private Optional<Integer> rowIndexForKey(String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).line().lineKey().equals(key)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private Node buildAnnotationCard(ReviewAnnotation annotation) {
        Label rangeChip = new Label(annotation.startKey().equals(annotation.endKey())
                ? keyLabel(annotation.startKey())
                : keyLabel(annotation.startKey()) + "–" + keyLabel(annotation.endKey()).substring(1));
        rangeChip.getStyleClass().add("review-range-chip");

        Label status = new Label(annotation.status().name().toLowerCase(Locale.ROOT));
        status.getStyleClass().addAll("review-status-pill", switch (annotation.status()) {
            case OPEN -> "status-open";
            case RESOLVED -> "status-resolved";
            case FIXED -> "status-fixed";
        });

        Button toggle = new Button(annotation.status() == AnnotationStatus.OPEN ? "Resolve" : "Reopen");
        toggle.getStyleClass().add("review-thread-action");
        toggle.setFocusTraversable(false);
        toggle.setOnAction(e -> {
            annotationStore.update(annotation.withStatus(annotation.status() == AnnotationStatus.OPEN
                    ? AnnotationStatus.RESOLVED
                    : AnnotationStatus.OPEN));
            renderFileList();
            renderSelectedFile();
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
            annotationStore.update(annotation.withReply(
                    new ReviewAnnotation.Message("You", Instant.now(), message)));
            renderSelectedFile();
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

    // ---- Send-to-Claude validation loop -------------------------------------

    private void updateSummary() {
        List<ReviewAnnotation> annotations = annotationStore.forScope(sessionId, scope);
        long open = annotations.stream().filter(a -> a.status() == AnnotationStatus.OPEN).count();
        long fixed = annotations.stream().filter(a -> a.status() == AnnotationStatus.FIXED).count();
        summaryLabel.setText(open + " open · " + annotations.size()
                + (annotations.size() == 1 ? " annotation" : " annotations") + " · " + fixed + " fixed");
        sendButton.setDisable(open == 0);
    }

    /**
     * Posts the diff scope + every OPEN annotation into the session's live
     * Claude terminal (the same hand-off principle as the worktree Finish
     * actions -- the app does not validate anything itself). Each open
     * thread then gets a hand-off reply and flips to FIXED, and the banner
     * offers a re-diff.
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

        sendButton.setDisable(true);
        sendButton.setText("Sent — Claude is validating…");
        // The app does NOT validate -- Claude does, in the live terminal.
        // After a grace period each open thread records the hand-off and
        // flips to FIXED; the banner's "Re-run diff" shows the real result.
        PauseTransition settle = new PauseTransition(Duration.seconds(8));
        settle.setOnFinished(e -> {
            for (ReviewAnnotation annotation : open) {
                annotationStore.update(annotation
                        .withReply(new ReviewAnnotation.Message("Claude", Instant.now(),
                                "Addressed in the terminal — re-run the diff to verify."))
                        .withStatus(AnnotationStatus.FIXED));
            }
            sendButton.setText("Send to Claude");
            bannerLabel.setText(open.size() + (open.size() == 1 ? " annotation" : " annotations") + " addressed");
            banner.setVisible(true);
            banner.setManaged(true);
            renderFileList();
            renderSelectedFile();
            updateSummary();
        });
        settle.play();
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
