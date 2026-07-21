package app.drydock.ui.explorer;

import app.drydock.ui.UiFormats;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * The Session Explorer's read-only code viewer (design handoff section A):
 * its own file-tab strip (independent of the session tabs), a breadcrumb
 * row with a {@code read-only} chip and a line-number-gutter toggle, and a
 * syntax-highlighted RichTextFX {@link CodeArea} per file. Editing is
 * disabled everywhere; files load off the FX thread.
 */
final class FileViewer extends BorderPane {

    private static final Logger LOG = System.getLogger(FileViewer.class.getName());

    /** Truncate very large files rather than stalling the viewer. */
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    private final TabPane fileTabs = new TabPane();
    private final HBox breadcrumb = new HBox(4);
    private final VBox topBox = new VBox(0);
    private final Label emptyState = new Label("Open a file from search to view it.");
    private final StackPane centerStack = new StackPane();
    private final Button gutterToggle = new Button("#");

    // -- Diff overlay (design handoff section C "Explorer integration") ----
    private final HBox diffBanner = new HBox(8);
    private final Label diffBannerLabel = new Label();
    private final Button diffBaseSwitch = new Button("switch base");
    private DiffOverlay diffOverlay;
    /** Latest changed-line map for the overlay's current scope (relative path → 1-based new-file lines). */
    private Map<Path, Set<Integer>> changedLines = Map.of();

    /** Open files, keyed by absolute path. */
    private final Map<Path, Tab> openFiles = new LinkedHashMap<>();
    private boolean gutterVisible = true;

    FileViewer() {
        getStyleClass().add("file-viewer");

        fileTabs.getStyleClass().add("viewer-tabs");
        fileTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        breadcrumb.getStyleClass().add("viewer-breadcrumb");
        breadcrumb.setAlignment(Pos.CENTER_LEFT);

        gutterToggle.getStyleClass().add("viewer-gutter-toggle");
        gutterToggle.setFocusTraversable(false);
        gutterToggle.setTooltip(new Tooltip("Toggle line numbers"));
        gutterToggle.setOnAction(e -> toggleGutter());

        emptyState.getStyleClass().add("viewer-empty");

        diffBannerLabel.getStyleClass().add("viewer-diff-banner-label");
        diffBaseSwitch.getStyleClass().add("viewer-diff-base-switch");
        diffBaseSwitch.setFocusTraversable(false);
        diffBaseSwitch.setTooltip(new Tooltip("Switch the diff overlay's base"));
        diffBaseSwitch.setOnAction(e -> {
            if (diffOverlay != null) {
                diffOverlay.cycleScope();
                refreshDiffOverlay();
            }
        });
        diffBanner.getChildren().setAll(diffBannerLabel, diffBaseSwitch);
        diffBanner.setAlignment(Pos.CENTER_LEFT);
        diffBanner.getStyleClass().add("viewer-diff-banner");
        diffBanner.setVisible(false);
        diffBanner.setManaged(false);

        centerStack.getChildren().setAll(fileTabs, emptyState);
        setCenter(centerStack);

        fileTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            updateBreadcrumb(newTab);
            updateEmptyState();
            updateDiffBanner();
        });
        updateBreadcrumb(null);
        updateEmptyState();
    }

    // ---- Diff overlay -------------------------------------------------------

    /** Wires the shared changed-line overlay (null-safe: never set for sessions without a repository). */
    void setDiffOverlay(DiffOverlay overlay) {
        this.diffOverlay = overlay;
        refreshDiffOverlay();
    }

    /** Whether the overlay's current scope touches {@code relativePath} (drives the rail's {@code diff} chip). */
    boolean hasDiffLines(Path relativePath) {
        return changedLines.containsKey(relativePath);
    }

    /** Re-fetches the changed-line map for the overlay's current scope and restyles every open tab. */
    private void refreshDiffOverlay() {
        if (diffOverlay == null) {
            return;
        }
        diffOverlay.changedSet().whenComplete((map, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                LOG.log(Level.DEBUG, "Changed-line overlay failed", failure);
                changedLines = Map.of();
            } else {
                changedLines = map;
            }
            for (Tab tab : fileTabs.getTabs()) {
                if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
                        && pane.getContent() instanceof CodeArea area) {
                    applyGutter(area, changedLinesFor(tab));
                }
            }
            updateDiffBanner();
        }));
    }

    private Set<Integer> changedLinesFor(Tab tab) {
        Path relative = (Path) tab.getProperties().get("drydock.relative");
        return relative == null ? Set.of() : changedLines.getOrDefault(relative, Set.of());
    }

    /** Banner: "N lines here are part of the diff vs <base>", with the base switchable in place. */
    private void updateDiffBanner() {
        Tab selected = fileTabs.getSelectionModel().getSelectedItem();
        int count = selected == null ? 0 : changedLinesFor(selected).size();
        boolean show = diffOverlay != null && count > 0;
        if (show) {
            diffBannerLabel.setText(count + (count == 1 ? " line here is part of " : " lines here are part of ")
                    + diffOverlay.scopeLabel());
        }
        diffBanner.setVisible(show);
        diffBanner.setManaged(show);
    }

    /** Opens {@code file} in a viewer tab (or focuses the existing one), optionally jumping to a 1-based line. */
    void openFile(Path file, Path relativePath, OptionalInt jumpToLine, String highlightQuery) {
        Tab existing = openFiles.get(file);
        if (existing != null) {
            fileTabs.getSelectionModel().select(existing);
            jumpToLine.ifPresent(line -> scrollTo(existing, line));
            return;
        }

        CodeArea area = new CodeArea();
        area.getStyleClass().add("code-area");
        area.setEditable(false);
        applyGutter(area, changedLines.getOrDefault(relativePath, Set.of()));

        Tab tab = new Tab();
        tab.setGraphic(buildFileTabGraphic(file));
        tab.setContent(new VirtualizedScrollPane<>(area));
        tab.getProperties().put("drydock.file", file);
        tab.getProperties().put("drydock.relative", relativePath);
        tab.setOnClosed(e -> {
            openFiles.remove(file);
            updateEmptyState();
        });

        openFiles.put(file, tab);
        fileTabs.getTabs().add(tab);
        fileTabs.getSelectionModel().select(tab);
        updateEmptyState();
        updateDiffBanner();

        Thread.ofVirtual().start(() -> {
            String text = readFile(file);
            SyntaxHighlighter.Language language =
                    SyntaxHighlighter.Language.fromFileName(file.getFileName().toString());
            var spans = SyntaxHighlighter.computeHighlighting(text, language);
            // Search-match highlighting is a SECOND style layer on top of
            // the lexer spans (handoff: "match highlight as a second style
            // layer"), merged by union so the token color survives.
            if (highlightQuery != null && !highlightQuery.isBlank()) {
                spans = spans.overlay(matchSpans(text, highlightQuery), (base, match) -> {
                    if (match.isEmpty()) {
                        return base;
                    }
                    List<String> merged = new ArrayList<>(base);
                    merged.addAll(match);
                    return merged;
                });
            }
            var styled = spans;
            Platform.runLater(() -> {
                area.replaceText(text);
                if (text.length() > 0) {
                    area.setStyleSpans(0, styled);
                }
                jumpToLine.ifPresent(line -> scrollTo(tab, line));
            });
        });
    }

    private static StyleSpans<Collection<String>> matchSpans(String text, String query) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        int lastEnd = 0;
        int from = 0;
        int at;
        while ((at = lowerText.indexOf(lowerQuery, from)) >= 0) {
            builder.add(List.of(), at - lastEnd);
            builder.add(List.of("code-match"), lowerQuery.length());
            lastEnd = at + lowerQuery.length();
            from = lastEnd;
        }
        builder.add(List.of(), text.length() - lastEnd);
        return builder.create();
    }

    private String readFile(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                try (var in = Files.newInputStream(file)) {
                    byte[] head = in.readNBytes((int) MAX_FILE_BYTES);
                    return new String(head, StandardCharsets.UTF_8)
                            + "\n\n… (truncated: file exceeds " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB)\n";
                }
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not read " + file, e);
            return "Could not read " + file + ": " + e.getMessage();
        }
    }

    private void scrollTo(Tab tab, int oneBasedLine) {
        if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
                && pane.getContent() instanceof CodeArea area) {
            int paragraph = Math.max(0, Math.min(oneBasedLine - 1, area.getParagraphs().size() - 1));
            area.moveTo(paragraph, 0);
            area.showParagraphAtCenter(paragraph);
        }
    }

    private HBox buildFileTabGraphic(Path file) {
        String name = file.getFileName().toString();
        Label badge = new Label(FileTypeBadges.badgeFor(name));
        badge.getStyleClass().add("filetype-badge");
        Label label = new Label(name);
        label.getStyleClass().add("viewer-tab-label");
        HBox graphic = new HBox(6, badge, label);
        graphic.setAlignment(Pos.CENTER_LEFT);
        return graphic;
    }

    private void updateBreadcrumb(Tab tab) {
        breadcrumb.getChildren().clear();
        if (tab == null) {
            return;
        }
        Path relative = (Path) tab.getProperties().get("drydock.relative");
        Path shown = relative != null ? relative : (Path) tab.getProperties().get("drydock.file");
        if (shown == null) {
            return;
        }
        breadcrumb.getChildren().addAll(UiFormats.breadcrumbSegments(shown));
        Label readOnly = new Label("read-only");
        readOnly.getStyleClass().add("read-only-chip");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        breadcrumb.getChildren().addAll(spacer, readOnly, gutterToggle);
    }

    private void updateEmptyState() {
        boolean empty = fileTabs.getTabs().isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        if (empty) {
            updateBreadcrumb(null);
            setTop(null);
        } else {
            topBox.getChildren().setAll(breadcrumb, diffBanner);
            setTop(topBox);
        }
    }

    private void toggleGutter() {
        gutterVisible = !gutterVisible;
        for (Tab tab : fileTabs.getTabs()) {
            if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
                    && pane.getContent() instanceof CodeArea area) {
                applyGutter(area, changedLinesFor(tab));
            }
        }
    }

    /**
     * Paragraph gutter: line numbers (toggleable) plus the GREEN
     * changed-line marker for lines the current diff scope touches
     * (design handoff section C: the Explorer viewer marks the scope's
     * changed lines). The marker column is always present so line starts
     * stay aligned whether or not a given line changed.
     */
    private void applyGutter(CodeArea area, Set<Integer> changed) {
        if (!gutterVisible && changed.isEmpty()) {
            area.setParagraphGraphicFactory(null);
            return;
        }
        IntFunction<Node> numbers = gutterVisible ? LineNumberFactory.get(area) : null;
        area.setParagraphGraphicFactory(line -> {
            HBox box = new HBox(2);
            box.setAlignment(Pos.CENTER_LEFT);
            Region marker = new Region();
            marker.getStyleClass().add("changed-line-marker");
            if (changed.contains(line + 1)) {
                marker.getStyleClass().add("on");
            }
            box.getChildren().add(marker);
            if (numbers != null) {
                box.getChildren().add(numbers.apply(line));
            }
            return box;
        });
    }

    /** Filetype badge letters shared by the search rail and the viewer tabs. */
    static final class FileTypeBadges {
        private FileTypeBadges() {
        }

        static String badgeFor(String fileName) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".java")) {
                return "J";
            }
            if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
                return "K";
            }
            if (lower.endsWith(".gradle")) {
                return "G";
            }
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return "M";
            }
            if (lower.endsWith(".css")) {
                return "#";
            }
            if (lower.endsWith(".json")) {
                return "{}";
            }
            if (lower.endsWith(".xml") || lower.endsWith(".html")) {
                return "<>";
            }
            if (lower.endsWith(".sh") || lower.endsWith(".zsh") || lower.endsWith(".bash")) {
                return "$";
            }
            int dot = lower.lastIndexOf('.');
            if (dot >= 0 && dot < lower.length() - 1) {
                return lower.substring(dot + 1, dot + 2).toUpperCase(Locale.ROOT);
            }
            return "·";
        }
    }
}
