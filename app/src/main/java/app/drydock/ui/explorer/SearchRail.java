package app.drydock.ui.explorer;

import app.drydock.search.SessionSearchService;
import app.drydock.search.SessionSearchService.FileMatches;
import app.drydock.search.SessionSearchService.TextMatch;
import javafx.animation.PauseTransition;
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
import javafx.scene.control.Tooltip;
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
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Explorer's file rail (design handoff section A; Explorer delta part 3,
 * "scope funnel: repo → diff").
 *
 * <p>The rail is a list of files narrowed two ways at once: a
 * <strong>scope</strong> -- this change, or the whole worktree -- and a
 * <strong>query</strong> matching names, paths and file <em>content</em>. The
 * rule the delta sets is that it must always show HOW the list was narrowed
 * and never dead-end, so the footer is the funnel's escape hatch: in diff
 * scope with a query it becomes the button that widens to the repo, and repo
 * scope dims out-of-change files rather than hiding them.</p>
 *
 * <p>Content matches stay expandable under their file, and checking rows
 * still opens several files at once -- both predate the delta and neither is
 * something it asked to remove.</p>
 */
final class SearchRail extends VBox {

    private static final Logger LOG = System.getLogger(SearchRail.class.getName());

    /** Debounce for live search-as-you-type. */
    private static final Duration SEARCH_DEBOUNCE = Duration.millis(150);

    /** Opens a file in the viewer: absolute path, relative path, optional 1-based line, query for match highlight. */
    interface FileOpener {
        void open(Path file, Path relativePath, OptionalInt line, String highlightQuery);
    }

    private final Path searchRoot;
    private final SessionSearchService searchService;
    private final FileOpener opener;

    private final TextField searchField = new TextField();
    private final Button scopeDiff = new Button("diff");
    private final Button scopeRepo = new Button("repo");
    private final Button sortButton = new Button();
    private final VBox resultsBox = new VBox(3);
    private final ScrollPane resultsScroll = new ScrollPane(resultsBox);
    private final Label selectedCount = new Label();
    private final Button openSelected = new Button();
    private final HBox footer = new HBox(8);
    private final Button funnelFooter = new Button();

    private final VBox expandedContent = new VBox(8);
    private final VBox collapsedContent = new VBox(10);

    /**
     * Groups the reader has collapsed, by session-relative path. Held here
     * rather than on the row because rebuild() destroys every row -- and it
     * rebuilds on opening a file, on a findings or overlay refresh, and on
     * every debounced keystroke, so row-local state survives almost nothing.
     */
    private final Set<Path> collapsedGroups = new LinkedHashSet<>();

    /** Checked file rows (multi-select), independent of row expansion. */
    private final ObservableSet<Path> checkedFiles = FXCollections.observableSet(new LinkedHashSet<>());
    /** Absolute path -> relative path for everything currently in the result list (for "Open N files"). */
    private final Map<Path, Path> knownRelative = new LinkedHashMap<>();

    /** Drops stale async results: only the newest query generation may render. */
    private final AtomicLong generation = new AtomicLong();

    private final PauseTransition debounce = new PauseTransition(SEARCH_DEBOUNCE);

    private FileRailModel.Scope scope = FileRailModel.Scope.DIFF;
    private FileRailModel.Sort sort = FileRailModel.Sort.CHURN;

    /** The most rows the rail will build at once; past this it says what it is holding back. */
    private static final int MAX_ROWS = 200;

    /** Every file in the worktree, loaded once and refreshed on demand. */
    private List<Path> repoFiles = List.of();
    private boolean repoFilesLoading;
    /** Whether the listing failed, so repo scope says so instead of reading as an empty worktree. */
    private boolean repoListingFailed;

    /**
     * Findings counts per file, memoised across rebuilds.
     *
     * <p>The provider behind {@link #findings} scans every review scope's
     * annotations linearly, and {@code entries()} asks it about every file in
     * the worktree -- so without this a single keystroke costs thousands of
     * full annotation scans on the FX thread. Cleared wherever findings can
     * actually have changed ({@link #setFindings}, {@link #refresh},
     * {@link #setChangedLines}), never merely because the query moved.</p>
     */
    private final Map<Path, Integer> findingsCounts = new java.util.HashMap<>();

    /** The last query's content matches, by relative path. */
    private Map<Path, List<TextMatch>> contentHits = Map.of();
    private String query = "";

    private Runnable onCollapseRequested = () -> { };
    private Runnable onExpandRequested = () -> { };
    private Consumer<String> onQueryChanged = text -> { };
    /** Whether a file (by relative path) carries diff lines in the active overlay scope. */
    private Predicate<Path> diffFileTest = relativePath -> false;
    /** Changed lines per file for the overlay's current scope: the diff half of the funnel. */
    private Supplier<Map<Path, Set<Integer>>> changedLines = Map::of;
    /** Open findings per file (the ◆N chip). */
    private Function<Path, List<ExplorerFinding>> findings = path -> List.of();
    /** The file the viewer is showing, which diff scope never hides. */
    private Path openFile;

    /** A {@code /} that arrived while collapsed, waiting for the field to exist again. */
    private boolean focusSearchOnExpand;

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
        loadRepoFiles();
        rebuild();
    }

    void setOnCollapseRequested(Runnable handler) {
        this.onCollapseRequested = handler == null ? () -> { } : handler;
    }

    void setOnExpandRequested(Runnable handler) {
        this.onExpandRequested = handler == null ? () -> { } : handler;
    }

    /** Wires the listener that keeps the viewer's search-hit ticks in step with this rail. */
    void setOnQueryChanged(Consumer<String> handler) {
        this.onQueryChanged = handler == null ? text -> { } : handler;
    }

    /** Wires the diff-overlay test tagging files that carry diff lines (handoff section C). */
    void setDiffFileTest(Predicate<Path> test) {
        this.diffFileTest = test == null ? relativePath -> false : test;
    }

    /** The diff half of the funnel: which files the current review scope touches, and by how much. */
    void setChangedLines(Supplier<Map<Path, Set<Integer>>> supplier) {
        this.changedLines = supplier == null ? Map::of : supplier;
        findingsCounts.clear();
        rebuild();
    }

    /** Open findings per file, for the ◆N chip and the findings sort. */
    void setFindings(Function<Path, List<ExplorerFinding>> provider) {
        this.findings = provider == null ? path -> List.of() : provider;
        findingsCounts.clear();
        rebuild();
    }

    /** The file the viewer is showing: diff scope keeps its row even when it is out of the change. */
    void setOpenFile(Path relativePath) {
        this.openFile = relativePath;
        rebuild();
    }

    /** Programmatic search (the Review tab's "Search in Explorer" chip). */
    void setSearch(String text) {
        searchField.setText(text);
        searchField.requestFocus();
        searchField.positionCaret(text == null ? 0 : text.length());
    }

    /** {@code /}: focuses the query field. */
    void focusSearch() {
        searchField.requestFocus();
        searchField.selectAll();
    }

    /** {@code d}: this change ⇄ the whole worktree. */
    void toggleScope() {
        setScope(scope == FileRailModel.Scope.DIFF ? FileRailModel.Scope.REPO : FileRailModel.Scope.DIFF);
    }

    /** {@code s}: churn → findings → a-z. */
    void cycleSort() {
        sort = sort.next();
        sortButton.setText("⇅ " + sort.label());
        rebuild();
    }

    /** Repaints against a changed diff overlay or a new finding. */
    void refresh() {
        // This is the "a finding may have changed" signal, so it is also
        // where the memoised counts have to go.
        findingsCounts.clear();
        // A worktree listing that failed earlier gets another chance here,
        // rather than leaving repo scope empty for the rest of the session.
        if (repoListingFailed) {
            loadRepoFiles();
        }
        rebuild();
    }

    /** Swaps to the full rail content (SessionExplorerView animates the width). */
    void showExpanded() {
        getChildren().setAll(expandedContent);
        if (focusSearchOnExpand) {
            focusSearchOnExpand = false;
            focusSearch();
        }
    }

    /**
     * Focuses the query field once the rail is actually expanded. {@code /}
     * pressed against a collapsed rail has nothing to focus -- the field is
     * not in the scene graph until {@link #showExpanded} puts it back -- so
     * the request is held until then rather than silently dropped.
     */
    void focusSearchWhenExpanded() {
        focusSearchOnExpand = true;
    }

    /** Swaps to the 46px collapsed strip: a ⌕ expand button + vertical FILES label. */
    void showCollapsed() {
        getChildren().setAll(collapsedContent);
    }

    private void setScope(FileRailModel.Scope newScope) {
        if (scope == newScope) {
            return;
        }
        scope = newScope;
        scopeDiff.pseudoClassStateChanged(SELECTED, scope == FileRailModel.Scope.DIFF);
        scopeRepo.pseudoClassStateChanged(SELECTED, scope == FileRailModel.Scope.REPO);
        rebuild();
    }

    private static final javafx.css.PseudoClass SELECTED =
            javafx.css.PseudoClass.getPseudoClass("selected");
    private static final javafx.css.PseudoClass ACTIONABLE =
            javafx.css.PseudoClass.getPseudoClass("actionable");
    private static final javafx.css.PseudoClass OUT_OF_CHANGE =
            javafx.css.PseudoClass.getPseudoClass("out-of-change");

    private void buildExpandedContent() {
        Label header = new Label("FILES");
        header.getStyleClass().add("search-rail-title");

        scopeDiff.getStyleClass().add("scope-toggle-button");
        scopeRepo.getStyleClass().add("scope-toggle-button");
        scopeDiff.setTooltip(new Tooltip("Only files in the current change (d)"));
        scopeRepo.setTooltip(new Tooltip("Every file in the worktree (d)"));
        scopeDiff.setOnAction(e -> setScope(FileRailModel.Scope.DIFF));
        scopeRepo.setOnAction(e -> setScope(FileRailModel.Scope.REPO));
        scopeDiff.pseudoClassStateChanged(SELECTED, true);
        HBox scopeSegment = new HBox(0, scopeDiff, scopeRepo);
        scopeSegment.getStyleClass().add("scope-toggle");
        scopeSegment.setAlignment(Pos.CENTER_LEFT);

        Label scopeKey = new Label("d");
        scopeKey.getStyleClass().add("rail-key-hint");

        sortButton.setText("⇅ " + sort.label());
        sortButton.getStyleClass().add("rail-sort-button");
        sortButton.setTooltip(new Tooltip("Sort: churn · findings · a-z (s)"));
        sortButton.setOnAction(e -> cycleSort());

        Label sortKey = new Label("s");
        sortKey.getStyleClass().add("rail-key-hint");

        Button collapse = new Button("«");
        collapse.getStyleClass().add("rail-collapse-button");
        collapse.setFocusTraversable(false);
        collapse.setOnAction(e -> onCollapseRequested.run());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(6, header, scopeSegment, scopeKey, headerSpacer,
                sortButton, sortKey, collapse);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("search-rail-header");

        Label magnifier = new Label("⌕");
        magnifier.getStyleClass().add("search-field-icon");
        searchField.setPromptText("files · symbols · text   /");
        searchField.getStyleClass().add("explorer-search-field");
        // Enter hands the keyboard back to the shortcuts (delta: "⏎ blurs
        // back to shortcuts"), so d or s work straight after typing without
        // reaching for the mouse.
        // requestFocus() works on a non-traversable node -- focusTraversable
        // only governs Tab traversal -- so focus really does leave the field
        // here and d/s/z fire instead of being typed. Pinned by
        // enterHandsTheKeyboardBackOutOfTheQueryField.
        searchField.setOnAction(e -> resultsBox.requestFocus());
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox fieldRow = new HBox(6, magnifier, searchField);
        fieldRow.setAlignment(Pos.CENTER_LEFT);
        fieldRow.getStyleClass().add("explorer-search-row");

        resultsBox.getStyleClass().add("search-results");
        resultsScroll.setFitToWidth(true);
        resultsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        resultsScroll.getStyleClass().add("search-results-scroll");
        VBox.setVgrow(resultsScroll, Priority.ALWAYS);

        selectedCount.getStyleClass().add("open-selected-count");
        openSelected.getStyleClass().add("open-selected-button");
        openSelected.setFocusTraversable(false);
        openSelected.setOnAction(e -> openCheckedFiles());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer.getChildren().setAll(selectedCount, footerSpacer, openSelected);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("open-selected-bar");

        funnelFooter.getStyleClass().add("rail-funnel-footer");
        funnelFooter.setMaxWidth(Double.MAX_VALUE);
        funnelFooter.setWrapText(true);
        // A long query result expands into enough match lines that the rail's
        // children want more height than it has, and the VBox then shrinks
        // this button to a Labeled's default single-line MINIMUM. wrapText
        // still wraps, but only the first line has room, so the text renders
        // ellipsized and the "— show" that IS the escape hatch falls off the
        // end. Pinning min to pref makes the scroll pane above give up the
        // pixels instead. Invisible to any assertion on the model's string.
        funnelFooter.setMinHeight(Region.USE_PREF_SIZE);
        funnelFooter.setAlignment(Pos.CENTER_LEFT);
        funnelFooter.setOnAction(e -> setScope(FileRailModel.Scope.REPO));

        expandedContent.getChildren().setAll(headerRow, fieldRow, resultsScroll, footer, funnelFooter);
        expandedContent.getStyleClass().add("search-rail-content");
        VBox.setVgrow(expandedContent, Priority.ALWAYS);
        VBox.setVgrow(resultsScroll, Priority.ALWAYS);
    }

    private void buildCollapsedContent() {
        Button expand = new Button("⌕");
        expand.getStyleClass().add("rail-expand-button");
        expand.setFocusTraversable(false);
        expand.setOnAction(e -> onExpandRequested.run());

        Label vertical = new Label("FILES");
        vertical.getStyleClass().add("rail-vertical-label");
        vertical.setRotate(90);
        Group rotated = new Group(vertical);

        collapsedContent.getChildren().setAll(expand, rotated);
        collapsedContent.setAlignment(Pos.TOP_CENTER);
        collapsedContent.getStyleClass().add("search-rail-collapsed");
    }

    // ---- Searching ----------------------------------------------------------

    /** Loads the worktree's file list once, so repo scope has something to show. */
    private void loadRepoFiles() {
        if (repoFilesLoading) {
            return;
        }
        repoFilesLoading = true;
        searchService.listFiles(searchRoot).whenComplete((files, failure) -> Platform.runLater(() -> {
            repoFilesLoading = false;
            if (failure != null) {
                // Not silent: repo scope would otherwise look like an empty
                // worktree, which is a very different statement. Held as
                // state rather than written straight into the footer, because
                // the very next rebuild() overwrites the footer's text -- so
                // a message put there directly survives only until the next
                // keystroke and the rail then quietly claims 0 files.
                LOG.log(Level.WARNING, "Could not list the session's files", failure);
                repoListingFailed = true;
                rebuild();
                return;
            }
            repoListingFailed = false;
            repoFiles = files;
            rebuild();
        }));
    }

    private void runSearch() {
        String newQuery = searchField.getText() == null ? "" : searchField.getText().strip();
        query = newQuery;
        onQueryChanged.accept(newQuery);
        long myGeneration = generation.incrementAndGet();
        if (newQuery.isEmpty()) {
            contentHits = Map.of();
            rebuild();
            return;
        }
        searchService.searchText(searchRoot, newQuery)
                .whenComplete((matches, ex) -> Platform.runLater(() -> {
                    if (generation.get() != myGeneration) {
                        return;
                    }
                    if (ex != null) {
                        LOG.log(Level.WARNING, "Text search failed", ex);
                        contentHits = Map.of();
                        rebuild();
                        return;
                    }
                    Map<Path, List<TextMatch>> hits = new LinkedHashMap<>();
                    for (FileMatches file : matches) {
                        hits.put(file.relativePath(), file.matches());
                    }
                    contentHits = hits;
                    rebuild();
                }));
        // The name/path half of the query needs no subprocess: show it now,
        // rather than leaving the rail on the previous query's rows while git
        // grep runs.
        rebuild();
    }

    private int findingsCount(Path relative) {
        return findingsCounts.computeIfAbsent(relative, path -> findings.apply(path).size());
    }

    /** Every candidate row: the change's files, the worktree's files, and whatever the query matched. */
    private List<FileRailModel.Entry> entries() {
        Map<Path, Set<Integer>> changed = changedLines.get();
        Set<Path> all = new LinkedHashSet<>(changed.keySet());
        all.addAll(repoFiles);
        all.addAll(contentHits.keySet());
        if (openFile != null) {
            all.add(openFile);
        }
        List<FileRailModel.Entry> entries = new ArrayList<>(all.size());
        for (Path relative : all) {
            entries.add(new FileRailModel.Entry(relative,
                    changed.getOrDefault(relative, Set.of()).size(),
                    findingsCount(relative),
                    contentHits.containsKey(relative)));
        }
        return entries;
    }

    private void rebuild() {
        // Captured before the children go, restored after they are back: the
        // reader's place in a 200-row list is not something a keystroke or a
        // background findings refresh gets to discard.
        double scrollPosition = resultsScroll.getVvalue();
        List<FileRailModel.Entry> all = entries();
        List<FileRailModel.Entry> shown = FileRailModel.visible(all, scope, sort, query, openFile);

        resultsBox.getChildren().clear();
        knownRelative.clear();
        // Repo scope with no query matches the whole worktree -- up to
        // SessionSearchService.MAX_FILES of it. Each row is a Button wrapping
        // the better part of a dozen nodes, and this is a plain ScrollPane
        // with no virtualisation, so building them all would freeze the FX
        // thread for seconds every time `d` is pressed. Cap it and SAY so:
        // a silently truncated list reads as "this is the whole worktree".
        List<FileRailModel.Entry> rendered = shown.size() > MAX_ROWS ? shown.subList(0, MAX_ROWS) : shown;
        for (FileRailModel.Entry entry : rendered) {
            Path absolute = searchRoot.resolve(entry.relative()).normalize();
            knownRelative.put(absolute, entry.relative());
            resultsBox.getChildren().add(buildFileRow(absolute, entry,
                    contentHits.getOrDefault(entry.relative(), List.of())));
        }
        // A row that is gone can no longer be unchecked, and openCheckedFiles
        // falls back to the BARE FILENAME as the relative path for anything
        // it cannot resolve -- which then misses on changed lines, waypoints
        // and findings alike. So the selection only keeps what is on screen.
        checkedFiles.retainAll(knownRelative.keySet());
        if (shown.size() > rendered.size()) {
            Label more = new Label("+" + (shown.size() - rendered.size())
                    + " more not shown — narrow with a query");
            more.getStyleClass().add("rail-empty");
            more.setWrapText(true);
            resultsBox.getChildren().add(more);
        }
        if (shown.isEmpty()) {
            Label empty = new Label(query.isBlank()
                    ? "nothing in this scope yet"
                    : "no file here matches \"" + query + "\"");
            empty.getStyleClass().add("rail-empty");
            empty.setWrapText(true);
            resultsBox.getChildren().add(empty);
        }

        // Deferred one pulse: the ScrollPane clamps vvalue against a content
        // height that is still zero until the new rows have been laid out.
        // Nothing to put back when the reader was already at the top, which
        // is the common case on a keystroke -- and this runs on every one of
        // them, so the queued no-op is worth not enqueueing.
        if (scrollPosition > 0) {
            Platform.runLater(() -> {
                // Only if the rebuild's own clear is what left it here:
                // emptying the rows collapses the content height and clamps
                // vvalue to 0, so a non-zero value one pulse later is someone
                // else's scroll and outranks the position we captured.
                if (resultsScroll.getVvalue() == 0) {
                    resultsScroll.setVvalue(scrollPosition);
                }
            });
        }

        int repoCount = Math.max(repoFiles.size(), shown.size());
        // A failed listing must not read as a small repo: "repo has 0 files"
        // and "we could not look" are very different statements, and only one
        // of them is true.
        funnelFooter.setText(repoListingFailed
                ? "could not list this worktree's files — search still works"
                : FileRailModel.footer(all, scope, sort, query, openFile, repoCount));
        boolean action = !repoListingFailed && FileRailModel.footerIsAction(scope, query, all, openFile);
        funnelFooter.pseudoClassStateChanged(ACTIONABLE, action);
        // Not an action means the footer is a statement about what the reader
        // is looking at: still readable, just not a button that goes anywhere.
        funnelFooter.setFocusTraversable(action);
        funnelFooter.setMouseTransparent(!action);
    }

    /**
     * One rail row: the change dot, the name, its findings chip, the module
     * and stat line, and the churn heat bar -- with the query's content
     * matches as expandable children.
     */
    private Region buildFileRow(Path file, FileRailModel.Entry entry, List<TextMatch> matches) {
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
        boolean expanded = !collapsedGroups.contains(entry.relative());
        ToggleButton caret = new ToggleButton(expanded ? "▾" : "▸");
        caret.getStyleClass().add("result-caret");
        caret.setFocusTraversable(false);
        caret.setSelected(expanded);
        caret.setVisible(hasChildren);
        caret.setManaged(hasChildren);

        Region dot = new Region();
        dot.getStyleClass().add("rail-change-dot");
        dot.setVisible(entry.inDiff());

        Label name = new Label(entry.name());
        name.getStyleClass().add("result-file-name");
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);

        HBox titleRow = new HBox(6, check, caret, dot, name);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (entry.findings() > 0) {
            Label findingChip = new Label("◆" + entry.findings());
            findingChip.getStyleClass().add("rail-finding-chip");
            titleRow.getChildren().add(findingChip);
        }
        if (!entry.inDiff() && diffFileTest.test(entry.relative())) {
            // The overlay knows about a file the current scope's changed-line
            // map does not carry: say so rather than showing it as untouched.
            Label diffChip = new Label("diff");
            diffChip.getStyleClass().add("diff-chip");
            titleRow.getChildren().add(diffChip);
        }

        Label module = new Label(entry.module());
        module.getStyleClass().add("result-file-path");
        HBox.setHgrow(module, Priority.ALWAYS);
        module.setMaxWidth(Double.MAX_VALUE);
        Label stat = new Label(entry.stat());
        stat.getStyleClass().add("rail-stat");
        HBox metaRow = new HBox(6, module, stat);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        Region heat = new Region();
        heat.getStyleClass().addAll("rail-heat",
                "churn-" + entry.churn().name().toLowerCase(Locale.ROOT));

        VBox rowContent = new VBox(2, titleRow, metaRow, heat);
        rowContent.setFillWidth(true);
        Button row = new Button();
        row.setGraphic(rowContent);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("rail-file-row");
        // Out-of-change files are DIMMED, never hidden (delta part 3).
        row.pseudoClassStateChanged(OUT_OF_CHANGE, !entry.inDiff());
        row.pseudoClassStateChanged(SELECTED, entry.relative().equals(openFile));
        row.setOnAction(e -> opener.open(file, entry.relative(), OptionalInt.empty(),
                query.isBlank() ? null : query));

        // setTooltip, not Tooltip.install: the row IS a Control, and
        // install() hangs the tooltip off the node's property map where
        // nothing (including a test) can find it through getTooltip().
        Tooltip pathTip = new Tooltip(entry.relative().toString());
        pathTip.setShowDelay(Duration.millis(400));
        row.setTooltip(pathTip);

        VBox group = new VBox(1, row);
        group.getStyleClass().add("result-file-group");

        if (hasChildren) {
            VBox lines = new VBox(1);
            lines.getStyleClass().add("result-match-lines");
            for (TextMatch match : matches) {
                lines.getChildren().add(buildMatchLine(file, entry.relative(), match, query));
            }
            lines.setVisible(expanded);
            lines.setManaged(expanded);
            group.getChildren().add(lines);

            caret.selectedProperty().addListener((obs, was, nowExpanded) -> {
                caret.setText(nowExpanded ? "▾" : "▸");
                lines.setVisible(nowExpanded);
                lines.setManaged(nowExpanded);
                if (nowExpanded) {
                    collapsedGroups.remove(entry.relative());
                } else {
                    collapsedGroups.add(entry.relative());
                }
            });
        }
        return group;
    }

    private Region buildMatchLine(Path file, Path relativePath, TextMatch match, String matchQuery) {
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
                opener.open(file, relativePath, OptionalInt.of(match.lineNumber()), matchQuery));
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
            opener.open(file, relative, OptionalInt.empty(), query.isBlank() ? null : query);
        }
    }
}
