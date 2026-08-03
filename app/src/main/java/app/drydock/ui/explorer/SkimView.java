package app.drydock.ui.explorer;

import app.drydock.ui.code.SyntaxHighlighter;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Skim mode (Explorer delta, part 2): the file as signature rows with their
 * bodies folded, so a 500-line changed file opens showing its shape and its
 * changed members rather than its first screenful.
 *
 * <p>Which rows start open is not a preference, it is the point: members
 * carrying changed lines are pre-expanded (the reader came here for those),
 * untouched private helpers collapse into one group row, and everything else
 * is a folded signature. Expanding is per member and remembered per file, so
 * {@code z} back to full text and {@code z} again returns the same view.</p>
 */
final class SkimView extends ScrollPane {

    /** A member's row, plus the group row that stands in for the folded private helpers. */
    private final VBox rows = new VBox(1);

    private SourceOutline outline = new SourceOutline(List.of(), 1);
    private List<String> lines = List.of();
    private Set<Integer> changed = Set.of();
    private Map<Integer, String> findingLabels = Map.of();
    private Path file = Path.of("");

    /** Explicit expand/collapse per member start line; absent means "whatever the default says". */
    private final Map<Integer, Boolean> expansion = new java.util.HashMap<>();
    private boolean helpersExpanded;

    private Consumer<Integer> onMemberRead = line -> { };
    private BiConsumer<CodeArea, Integer> onBodyBuilt = (area, startLine) -> { };

    SkimView() {
        getStyleClass().add("explorer-skim");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        rows.getStyleClass().add("skim-rows");
        setContent(rows);
    }

    /** Called with the start line of a member the reader opened (it counts as read). */
    void setOnMemberRead(Consumer<Integer> handler) {
        this.onMemberRead = handler == null ? line -> { } : handler;
    }

    /**
     * Called for every expanded member's code area, so the owner can attach
     * the same symbol-peek gesture the full-text area carries -- skim mode
     * must not be a place where clicking a symbol stops working.
     */
    void setOnBodyBuilt(BiConsumer<CodeArea, Integer> handler) {
        this.onBodyBuilt = handler == null ? (area, startLine) -> { } : handler;
    }

    /** Feeds the view; {@code findingLabels} maps a 1-based line to the finding's short label. */
    void show(Path file, String text, SourceOutline outline, Set<Integer> changed,
              Map<Integer, String> findingLabels) {
        this.file = file;
        this.outline = outline;
        this.lines = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
        this.changed = Set.copyOf(changed);
        this.findingLabels = Map.copyOf(findingLabels);
        rebuild();
    }

    /** Repaints against a new changed set / finding set without touching expansion state. */
    void refresh(Set<Integer> changed, Map<Integer, String> findingLabels) {
        this.changed = Set.copyOf(changed);
        this.findingLabels = Map.copyOf(findingLabels);
        rebuild();
    }

    /** The line at the top of the skim viewport, so {@code z} can hand it to the full-text area. */
    int topLine() {
        double offset = getVvalue() * Math.max(1, rows.getHeight() - getViewportBounds().getHeight());
        for (javafx.scene.Node node : rows.getChildren()) {
            if (node.getBoundsInParent().getMaxY() >= offset
                    && node.getProperties().get("drydock.line") instanceof Integer line) {
                return line;
            }
        }
        return outline.members().isEmpty() ? 1 : outline.members().get(0).startLine();
    }

    /** Expands the member containing {@code line} and scrolls it into view (a minimap click, or {@code z} back). */
    void revealLine(int line) {
        outline.memberAt(line).ifPresent(member -> {
            expansion.put(member.startLine(), true);
            onMemberRead.accept(member.startLine());
            rebuild();
            for (javafx.scene.Node node : rows.getChildren()) {
                if (Integer.valueOf(member.startLine()).equals(node.getProperties().get("drydock.line"))) {
                    double target = node.getBoundsInParent().getMinY()
                            / Math.max(1, rows.getHeight() - getViewportBounds().getHeight());
                    setVvalue(Math.max(0, Math.min(1, target)));
                    return;
                }
            }
        });
    }

    private boolean isExpanded(SourceOutline.Member member) {
        Boolean explicit = expansion.get(member.startLine());
        // Default: changed members are open, everything else is folded.
        return explicit != null ? explicit : member.isChanged(changed);
    }

    private void rebuild() {
        rows.getChildren().clear();
        List<SourceOutline.Member> folded = new ArrayList<>();
        for (SourceOutline.Member member : outline.members()) {
            boolean untouchedHelper = member.privateHelper()
                    && !member.isChanged(changed)
                    && findingIn(member) == null;
            if (untouchedHelper) {
                folded.add(member);
                continue;
            }
            rows.getChildren().add(buildRow(member));
        }
        if (!folded.isEmpty()) {
            rows.getChildren().add(buildHelperGroup(folded));
        }
    }

    private String findingIn(SourceOutline.Member member) {
        for (Map.Entry<Integer, String> entry : findingLabels.entrySet()) {
            if (member.covers(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Region buildRow(SourceOutline.Member member) {
        boolean expanded = isExpanded(member);
        String finding = findingIn(member);

        Label number = new Label(Integer.toString(member.startLine()));
        number.getStyleClass().add("skim-lineno");
        Label caret = new Label(expanded ? "▾" : "▸");
        caret.getStyleClass().add("skim-caret");
        Label signature = new Label(member.signature());
        signature.getStyleClass().add(expanded ? "skim-signature-open" : "skim-signature");
        Label tag = new Label("· " + member.lines() + " lines");
        tag.getStyleClass().add("skim-tag");

        HBox header = new HBox(7, number, caret, signature, tag);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("skim-header-content");
        if (member.isChanged(changed)) {
            Label changedTag = new Label("· changed");
            changedTag.getStyleClass().add("skim-changed-tag");
            header.getChildren().add(changedTag);
        }
        if (finding != null) {
            Label findingTag = new Label("· ◆1 " + finding);
            findingTag.getStyleClass().add("skim-finding-tag");
            header.getChildren().add(findingTag);
        }

        Button row = new Button();
        row.setGraphic(header);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("skim-header");
        if (finding != null) {
            row.getStyleClass().add("has-finding");
        }
        row.setOnAction(e -> {
            expansion.put(member.startLine(), !isExpanded(member));
            onMemberRead.accept(member.startLine());
            rebuild();
        });

        VBox group = new VBox(row);
        group.getProperties().put("drydock.line", member.startLine());
        group.getStyleClass().add("skim-group");
        if (expanded) {
            group.getChildren().add(buildBody(member));
        }
        return group;
    }

    private Region buildHelperGroup(List<SourceOutline.Member> folded) {
        int lineTotal = folded.stream().mapToInt(SourceOutline.Member::lines).sum();
        Label number = new Label(Integer.toString(folded.get(0).startLine()));
        number.getStyleClass().add("skim-lineno");
        Label caret = new Label(helpersExpanded ? "▾" : "▸");
        caret.getStyleClass().add("skim-caret");
        Label signature = new Label("private helpers (" + folded.size() + ")");
        signature.getStyleClass().add("skim-group-signature");
        Label tag = new Label("· " + lineTotal + " lines · untouched, folded");
        tag.getStyleClass().add("skim-tag");
        HBox header = new HBox(7, number, caret, signature, tag);
        header.setAlignment(Pos.CENTER_LEFT);

        Button row = new Button();
        row.setGraphic(header);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("skim-header");
        row.setOnAction(e -> {
            helpersExpanded = !helpersExpanded;
            rebuild();
        });

        VBox group = new VBox(row);
        group.getStyleClass().add("skim-group");
        group.getProperties().put("drydock.line", folded.get(0).startLine());
        if (helpersExpanded) {
            for (SourceOutline.Member member : folded) {
                // Shown as ordinary rows once the group is open: the reader
                // asked for them, and they behave like every other member
                // from that point on.
                VBox inner = new VBox(buildRow(member));
                inner.getStyleClass().add("skim-helper");
                group.getChildren().add(inner);
            }
        }
        return group;
    }

    private Region buildBody(SourceOutline.Member member) {
        int from = Math.max(1, member.startLine());
        int to = Math.min(lines.size(), member.endLine());
        StringBuilder text = new StringBuilder();
        for (int line = from; line <= to; line++) {
            text.append(lines.get(line - 1));
            if (line < to) {
                text.append('\n');
            }
        }
        String body = text.toString();

        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("code-area", "skim-code");
        area.setEditable(false);
        area.setFocusTraversable(false);
        area.replaceText(body);
        if (!body.isEmpty()) {
            SyntaxHighlighter.Language language =
                    SyntaxHighlighter.Language.fromFileName(file.getFileName().toString());
            var spans = SyntaxHighlighter.computeHighlighting(body, language);
            Set<String> lens = SymbolLens.symbolsIn(body);
            spans = spans.overlay(SymbolLens.spans(body, lens), (base, mark) -> {
                if (mark.isEmpty()) {
                    return base;
                }
                List<String> merged = new ArrayList<>(base);
                merged.addAll(mark);
                return merged;
            });
            area.setStyleSpans(0, spans);
            area.getProperties().put("drydock.lens", new LinkedHashSet<>(lens));
        }
        area.setParagraphGraphicFactory(paragraph -> {
            int fileLine = from + paragraph;
            Region marker = new Region();
            marker.getStyleClass().add("changed-line-marker");
            if (changed.contains(fileLine)) {
                marker.getStyleClass().add("on");
            }
            Label number = new Label(Integer.toString(fileLine));
            number.getStyleClass().add("skim-body-lineno");
            HBox box = new HBox(2, marker, number);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        });
        onBodyBuilt.accept(area, from);

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
        // Sized to the member: a scrollbar inside a row inside the skim
        // scroller is a trap, so the row is as tall as its content.
        double height = 6 + Math.max(1, to - from + 1) * 17.0;
        scroll.setPrefHeight(height);
        scroll.setMinHeight(height);
        VBox.setVgrow(scroll, Priority.NEVER);
        return scroll;
    }
}
