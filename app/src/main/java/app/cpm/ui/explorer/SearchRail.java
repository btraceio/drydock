package app.cpm.ui.explorer;

import app.cpm.search.SessionSearchService;
import app.cpm.search.SessionSearchService.FileHit;
import app.cpm.search.SessionSearchService.FileMatches;
import app.cpm.search.SessionSearchService.TextMatch;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The Session Explorer's search rail (design handoff section A): a
 * Files/Text segmented toggle, a live search field, and results grouped by
 * file — each row a selection checkbox, disclosure caret, filetype badge
 * and name; Text mode adds an accent match-count pill and expandable
 * highlighted match lines. Checked rows drive an "Open N files" footer.
 * The rail itself collapses to a 46px strip ({@link SessionExplorerView}
 * owns the width animation; this class just swaps its content).
 */
final class SearchRail extends VBox {

    private static final Logger LOG = System.getLogger(SearchRail.class.getName());

    /** Debounce for live search-as-you-type. */
    private static final Duration SEARCH_DEBOUNCE = Duration.millis(150);

    /** Opens a file in the viewer: absolute path, relative path, optional 1-based line, query for match highlight. */
    interface FileOpener {
        void open(Path file, Path relativePath, OptionalInt line, String highlightQuery);
    }

    private enum Mode { FILES, TEXT }

    private final Path searchRoot;
    private final SessionSearchService searchService;
    private final FileOpener opener;

    private final TextField searchField = new TextField();
    private final ToggleButton filesToggle = new ToggleButton("Files");
    private final ToggleButton textToggle = new ToggleButton("Text");
    private final VBox resultsBox = new VBox(2);
    private final Label selectedCount = new Label();
    private final Button openSelected = new Button();
    private final HBox footer = new HBox(8);

    private final VBox expandedContent = new VBox(10);
    private final VBox collapsedContent = new VBox(10);

    /** Checked file rows (multi-select), independent of row expansion. */
    private final ObservableSet<Path> checkedFiles = FXCollections.observableSet(new LinkedHashSet<>());
    /** Absolute path -> relative path for everything currently in the result list (for "Open N files"). */
    private final Map<Path, Path> knownRelative = new LinkedHashMap<>();

    /** Drops stale async results: only the newest query generation may render. */
    private final AtomicLong generation = new AtomicLong();

    private final PauseTransition debounce = new PauseTransition(SEARCH_DEBOUNCE);
    private Mode mode = Mode.TEXT;
    private Runnable onCollapseRequested = () -> { };
    private Runnable onExpandRequested = () -> { };
    /** Whether a file (by relative path) carries diff lines in the active overlay scope (`diff` chip). */
    private Predicate<Path> diffFileTest = relativePath -> false;

    SearchRail(Path searchRoot, SessionSearchService searchService, FileOpener opener) {
        this.searchRoot = searchRoot;
        this.searchService = searchService;
        this.opener = opener;

        getStyleClass().add("search-rail");
        setFillWidth(true);

        buildExpandedContent();
        buildCollapsedContent();
        showExpanded();

        debounce.setOnFinished(e -> runSearch());
        searchField.textProperty().addListener((obs, oldText, newText) -> debounce.playFromStart());
        checkedFiles.addListener((SetChangeListener<Path>) change -> updateFooter());
        updateFooter();
    }

    void setOnCollapseRequested(Runnable handler) {
        this.onCollapseRequested = handler == null ? () -> { } : handler;
    }

    void setOnExpandRequested(Runnable handler) {
        this.onExpandRequested = handler == null ? () -> { } : handler;
    }

    /** Wires the diff-overlay test tagging Text-search files that carry diff lines (handoff section C). */
    void setDiffFileTest(Predicate<Path> test) {
        this.diffFileTest = test == null ? relativePath -> false : test;
    }

    /** Programmatic Text-mode search (the Review tab's "Search in Explorer" chip). */
    void setSearch(String query) {
        textToggle.setSelected(true);
        searchField.setText(query);
        searchField.requestFocus();
        searchField.positionCaret(query == null ? 0 : query.length());
    }

    /** Swaps to the full rail content (SessionExplorerView animates the width). */
    void showExpanded() {
        getChildren().setAll(expandedContent);
    }

    /** Swaps to the 46px collapsed strip: a ⌕ expand button + vertical SEARCH label. */
    void showCollapsed() {
        getChildren().setAll(collapsedContent);
    }

    private void buildExpandedContent() {
        Label header = new Label("SEARCH · THIS SESSION");
        header.getStyleClass().add("search-rail-title");
        Button collapse = new Button("«");
        collapse.getStyleClass().add("rail-collapse-button");
        collapse.setFocusTraversable(false);
        collapse.setOnAction(e -> onCollapseRequested.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(6, header, headerSpacer, collapse);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("search-rail-header");

        ToggleGroup modeGroup = new ToggleGroup();
        filesToggle.setToggleGroup(modeGroup);
        textToggle.setToggleGroup(modeGroup);
        filesToggle.getStyleClass().add("seg-toggle-button");
        textToggle.getStyleClass().add("seg-toggle-button");
        filesToggle.setFocusTraversable(false);
        textToggle.setFocusTraversable(false);
        textToggle.setSelected(true);
        modeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                // Never allow an empty selection: re-select the previous mode.
                (mode == Mode.FILES ? filesToggle : textToggle).setSelected(true);
                return;
            }
            mode = newToggle == filesToggle ? Mode.FILES : Mode.TEXT;
            searchField.setPromptText(mode == Mode.FILES
                    ? "Search files in this session…"
                    : "Search text in this session…");
            runSearch();
        });
        HBox modeRow = new HBox(0, filesToggle, textToggle);
        modeRow.getStyleClass().add("seg-toggle");
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Label magnifier = new Label("⌕");
        magnifier.getStyleClass().add("search-field-icon");
        searchField.setPromptText("Search text in this session…");
        searchField.getStyleClass().add("explorer-search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox fieldRow = new HBox(6, magnifier, searchField);
        fieldRow.setAlignment(Pos.CENTER_LEFT);
        fieldRow.getStyleClass().add("explorer-search-row");

        resultsBox.getStyleClass().add("search-results");
        ScrollPane scroll = new ScrollPane(resultsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("search-results-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        selectedCount.getStyleClass().add("open-selected-count");
        openSelected.getStyleClass().add("open-selected-button");
        openSelected.setFocusTraversable(false);
        openSelected.setOnAction(e -> openCheckedFiles());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer.getChildren().setAll(selectedCount, footerSpacer, openSelected);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("open-selected-bar");

        expandedContent.getChildren().setAll(headerRow, modeRow, fieldRow, scroll, footer);
        expandedContent.getStyleClass().add("search-rail-content");
        VBox.setVgrow(expandedContent, Priority.ALWAYS);
        VBox.setVgrow(scroll, Priority.ALWAYS);
    }

    private void buildCollapsedContent() {
        Button expand = new Button("⌕");
        expand.getStyleClass().add("rail-expand-button");
        expand.setFocusTraversable(false);
        expand.setOnAction(e -> onExpandRequested.run());

        Label vertical = new Label("SEARCH");
        vertical.getStyleClass().add("rail-vertical-label");
        vertical.setRotate(90);
        Group rotated = new Group(vertical);

        collapsedContent.getChildren().setAll(expand, rotated);
        collapsedContent.setAlignment(Pos.TOP_CENTER);
        collapsedContent.getStyleClass().add("search-rail-collapsed");
    }

    // ---- Searching ----------------------------------------------------------

    private void runSearch() {
        String query = searchField.getText() == null ? "" : searchField.getText().strip();
        long myGeneration = generation.incrementAndGet();
        if (query.isEmpty()) {
            resultsBox.getChildren().clear();
            knownRelative.clear();
            checkedFiles.clear();
            return;
        }
        if (mode == Mode.FILES) {
            searchService.searchFiles(searchRoot, query)
                    .whenComplete((hits, ex) -> Platform.runLater(() -> {
                        if (generation.get() != myGeneration) {
                            return;
                        }
                        if (ex != null) {
                            LOG.log(Level.DEBUG, "File search failed", ex);
                            return;
                        }
                        renderFileHits(hits, query);
                    }));
        } else {
            searchService.searchText(searchRoot, query)
                    .whenComplete((matches, ex) -> Platform.runLater(() -> {
                        if (generation.get() != myGeneration) {
                            return;
                        }
                        if (ex != null) {
                            LOG.log(Level.DEBUG, "Text search failed", ex);
                            return;
                        }
                        renderTextMatches(matches, query);
                    }));
        }
    }

    private void renderFileHits(List<FileHit> hits, String query) {
        resultsBox.getChildren().clear();
        knownRelative.clear();
        for (FileHit hit : hits) {
            knownRelative.put(hit.file(), hit.relativePath());
            resultsBox.getChildren().add(buildFileRow(hit.file(), hit.relativePath(), -1, List.of(), query));
        }
    }

    private void renderTextMatches(List<FileMatches> files, String query) {
        resultsBox.getChildren().clear();
        knownRelative.clear();
        for (FileMatches file : files) {
            knownRelative.put(file.file(), file.relativePath());
            resultsBox.getChildren().add(
                    buildFileRow(file.file(), file.relativePath(), file.matches().size(), file.matches(), query));
        }
    }

    /**
     * One grouped result row: checkbox + caret + badge + name (+ count pill
     * in Text mode), with the match lines as expandable children.
     */
    private Region buildFileRow(Path file, Path relativePath, int matchCount, List<TextMatch> matches, String query) {
        CheckBox check = new CheckBox();
        check.getStyleClass().add("result-check");
        check.setFocusTraversable(false);
        check.setSelected(checkedFiles.contains(file));
        check.selectedProperty().addListener((obs, was, is) -> {
            if (is) {
                checkedFiles.add(file);
            } else {
                checkedFiles.remove(file);
            }
        });

        boolean hasChildren = !matches.isEmpty();
        ToggleButton caret = new ToggleButton("▸");
        caret.getStyleClass().add("result-caret");
        caret.setFocusTraversable(false);
        caret.setSelected(true);
        caret.setVisible(hasChildren);
        caret.setManaged(hasChildren);

        Label badge = new Label(FileViewer.FileTypeBadges.badgeFor(file.getFileName().toString()));
        badge.getStyleClass().add("filetype-badge");

        Label name = new Label(file.getFileName().toString());
        name.getStyleClass().add("result-file-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(6, check, caret, badge, name, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("result-file-row");

        if (matchCount >= 0) {
            if (diffFileTest.test(relativePath)) {
                Label diffChip = new Label("diff");
                diffChip.getStyleClass().add("diff-chip");
                row.getChildren().add(diffChip);
            }
            Label pill = new Label(Integer.toString(matchCount));
            pill.getStyleClass().add("match-count-pill");
            row.getChildren().add(pill);
        } else {
            Label pathLabel = new Label(relativePath.toString());
            pathLabel.getStyleClass().add("result-file-path");
            row.getChildren().add(row.getChildren().indexOf(spacer), pathLabel);
        }

        VBox group = new VBox(1, row);
        group.getStyleClass().add("result-file-group");

        if (hasChildren) {
            VBox lines = new VBox(1);
            lines.getStyleClass().add("result-match-lines");
            for (TextMatch match : matches) {
                lines.getChildren().add(buildMatchLine(file, relativePath, match, query));
            }
            group.getChildren().add(lines);

            RotateTransition rotate = new RotateTransition(Duration.millis(120), caret);
            caret.selectedProperty().addListener((obs, was, expanded) -> {
                rotate.setToAngle(expanded ? 90 : 0);
                rotate.playFromStart();
                lines.setVisible(expanded);
                lines.setManaged(expanded);
            });
            caret.setRotate(90); // default expanded
        }

        // Clicking the row (not the checkbox/caret) opens the file.
        row.setOnMouseClicked(e -> {
            if (e.getTarget() == check || e.getTarget() == caret) {
                return;
            }
            opener.open(file, relativePath, OptionalInt.empty(), mode == Mode.TEXT ? query : null);
        });
        return group;
    }

    private Region buildMatchLine(Path file, Path relativePath, TextMatch match, String query) {
        Label lineNumber = new Label(Integer.toString(match.lineNumber()));
        lineNumber.getStyleClass().add("match-line-number");

        String text = match.lineText().stripTrailing();
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("match-line-text");
        int start = Math.min(match.matchStart(), text.length());
        int end = Math.min(match.matchEnd(), text.length());
        if (start < end) {
            List<Node> parts = new ArrayList<>();
            if (start > 0) {
                parts.add(styledText(text.substring(0, start), "match-line-plain"));
            }
            // A Label, not a Text: only Regions can paint the matchBg
            // highlight background behind the query substring.
            Label highlight = new Label(text.substring(start, end));
            highlight.getStyleClass().add("match-line-highlight");
            parts.add(highlight);
            if (end < text.length()) {
                parts.add(styledText(text.substring(end), "match-line-plain"));
            }
            flow.getChildren().setAll(parts);
        } else {
            flow.getChildren().setAll(styledText(text, "match-line-plain"));
        }

        HBox line = new HBox(8, lineNumber, flow);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("result-match-line");
        line.setOnMouseClicked(e ->
                opener.open(file, relativePath, OptionalInt.of(match.lineNumber()), query));
        return line;
    }

    private static Text styledText(String content, String styleClass) {
        Text text = new Text(content);
        text.getStyleClass().add(styleClass);
        return text;
    }

    // ---- Multi-select footer ------------------------------------------------

    private void updateFooter() {
        int count = checkedFiles.size();
        boolean any = count > 0;
        footer.setVisible(any);
        footer.setManaged(any);
        selectedCount.setText(count + " selected");
        openSelected.setText("Open " + count + (count == 1 ? " file" : " files"));
    }

    private void openCheckedFiles() {
        for (Path file : new ArrayList<>(checkedFiles)) {
            Path relative = knownRelative.getOrDefault(file, file.getFileName());
            opener.open(file, relative, OptionalInt.empty(),
                    mode == Mode.TEXT ? searchField.getText() : null);
        }
    }
}
