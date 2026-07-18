package app.cpm.ui.explorer;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
    private final Label emptyState = new Label("Open a file from search to view it.");
    private final StackPane centerStack = new StackPane();
    private final Button gutterToggle = new Button("#");

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

        centerStack.getChildren().setAll(fileTabs, emptyState);
        setCenter(centerStack);

        fileTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            updateBreadcrumb(newTab);
            updateEmptyState();
        });
        updateBreadcrumb(null);
        updateEmptyState();
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
        applyGutter(area);

        Tab tab = new Tab();
        tab.setGraphic(buildFileTabGraphic(file));
        tab.setContent(new VirtualizedScrollPane<>(area));
        tab.getProperties().put("cpm.file", file);
        tab.getProperties().put("cpm.relative", relativePath);
        tab.setOnClosed(e -> {
            openFiles.remove(file);
            updateEmptyState();
        });

        openFiles.put(file, tab);
        fileTabs.getTabs().add(tab);
        fileTabs.getSelectionModel().select(tab);
        updateEmptyState();

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
        Path relative = (Path) tab.getProperties().get("cpm.relative");
        Path shown = relative != null ? relative : (Path) tab.getProperties().get("cpm.file");
        if (shown == null) {
            return;
        }
        int i = 0;
        for (Path segment : shown) {
            if (i++ > 0) {
                Label sep = new Label("›");
                sep.getStyleClass().add("breadcrumb-separator");
                breadcrumb.getChildren().add(sep);
            }
            Label part = new Label(segment.toString());
            part.getStyleClass().add("breadcrumb-segment");
            breadcrumb.getChildren().add(part);
        }
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
            setTop(breadcrumb);
        }
    }

    private void toggleGutter() {
        gutterVisible = !gutterVisible;
        for (Tab tab : fileTabs.getTabs()) {
            if (tab.getContent() instanceof VirtualizedScrollPane<?> pane
                    && pane.getContent() instanceof CodeArea area) {
                applyGutter(area);
            }
        }
    }

    private void applyGutter(CodeArea area) {
        area.setParagraphGraphicFactory(gutterVisible ? LineNumberFactory.get(area) : null);
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
