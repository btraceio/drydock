package app.drydock.ui.explorer;

import app.drydock.ui.code.SyntaxHighlighter;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
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
import java.util.Optional;
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

    /**
     * How many layout passes a reveal may spend trying to make its scroll
     * stick. Generous enough for the several passes a rebuild can trigger,
     * finite so an unreachable target cannot re-apply forever.
     */
    private static final int MAX_SCROLL_ATTEMPTS = 8;

    /** The row a reveal is still trying to put at the top, or -1 for none. */
    private int pendingTopRow = -1;
    private int pendingAttempts;

    private Consumer<Integer> onMemberRead = line -> { };
    private BiConsumer<CodeArea, Integer> onBodyBuilt = (area, startLine) -> { };

    SkimView() {
        getStyleClass().add("explorer-skim");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        rows.getStyleClass().add("skim-rows");
        setContent(rows);
        // Every layout is a chance for a pending reveal to land: the pass
        // that overwrites the scroll and the pass that finally reports real
        // geometry are both layouts, and which one comes when is exactly what
        // this class cannot predict.
        rows.layoutBoundsProperty().addListener((obs, was, is) -> applyPendingTopRow());
        viewportBoundsProperty().addListener((obs, was, is) -> applyPendingTopRow());
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
        // expansion is keyed by line number, not by member identity: a stale
        // TRUE surviving from the previous document would pull open whatever
        // member happens to start at that line number in THIS one, and
        // rebuild() consults expansion to override folding -- so this is not
        // cosmetic, it decides what a fresh document looks like on arrival.
        expansion.clear();
        helpersExpanded = false;
        // New content: there is no reader's place in this document to
        // preserve, and a vvalue measured against the previous document
        // would be meaningless here. The caller decides where to land --
        // scrollToTop() or a revealLine() -- right after this returns; a
        // restore queued behind it would land a pulse later and undo it.
        rebuild(false);
    }

    /**
     * Repaints against a new changed set / finding set without touching
     * expansion state. The same document repainted, so the reader's place in
     * it is worth keeping -- which is why this restores the scroll and
     * {@link #show} does not.
     */
    void refresh(Set<Integer> changed, Map<Integer, String> findingLabels) {
        this.changed = Set.copyOf(changed);
        this.findingLabels = Map.copyOf(findingLabels);
        rebuild(true);
    }

    /**
     * How far the content can scroll, in pixels: the conversion between a
     * vvalue fraction and an absolute offset. Floored at 1 so it is always
     * safe to divide by; callers that care whether there is anything to
     * scroll at all check the real overflow themselves.
     */
    private double scrollableSpan() {
        return Math.max(1, rows.getHeight() - getViewportBounds().getHeight());
    }

    /**
     * The line at the top of the skim viewport, so {@code z} can hand it to
     * the full-text area.
     *
     * <p>Strictly greater than the offset: a row whose bottom edge lands
     * exactly on the viewport's top edge shows no pixels and is not what the
     * reader is looking at. Not covered by a test -- the rows carry 1px of
     * spacing, so in this layout two edges never actually coincide, and a
     * test for it could only pass by construction.</p>
     */
    int topLine() {
        double offset = getVvalue() * scrollableSpan();
        for (Node node : rows.getChildren()) {
            if (node.getBoundsInParent().getMaxY() > offset
                    && node.getProperties().get("drydock.line") instanceof Integer line) {
                return line;
            }
        }
        return outline.members().isEmpty() ? 1 : outline.members().get(0).startLine();
    }

    /**
     * Applies a wheel event that landed on a member body to this scroller.
     * Deltas are in pixels, so they are converted against the same
     * scrollable span {@link #topLine()} uses.
     */
    private void redispatchWheel(ScrollEvent event) {
        double overflow = rows.getHeight() - getViewportBounds().getHeight();
        if (overflow <= 0) {
            // Nothing to scroll: unlike topLine()'s multiplier, this value is
            // a divisor, so flooring it at 1 would turn a ~120px wheel delta
            // into a snap to vvalue 0 or 1 instead of a no-op. Left
            // unconsumed so the event can still reach an enclosing scroller.
            return;
        }
        event.consume();
        cancelPendingTopRow();
        setVvalue(Math.max(0, Math.min(1, getVvalue() - event.getDeltaY() / overflow)));
    }

    /**
     * Expands the member {@code line} falls in and scrolls it into view (a
     * minimap click, a search hit, or {@code z} back).
     *
     * <p>A line between members -- a blank line, an import, the class header
     * -- resolves forward to the next member down rather than doing nothing:
     * this used to be a silent no-op, which made "go to line N" a dead
     * gesture for every such N, and left the file-open path sitting on
     * whatever anchor {@code setSkim} had computed from a CodeArea that was
     * not laid out yet. Past the last member there is nothing to reveal, so
     * the honest answer is the top of the file.</p>
     *
     * <p>Resolving forward SCROLLS but does not open: expanding a member the
     * reader never pointed at -- and counting it as read, which the trail and
     * the dwell sampler both believe -- is the view deciding something on
     * their behalf. Opening a file at line 1 would otherwise expand whatever
     * declaration happens to come first. Only a line genuinely inside a
     * member opens it.</p>
     */
    void revealLine(int line) {
        Optional<SourceOutline.Member> resolved = outline.memberAtOrAfter(line);
        if (resolved.isEmpty()) {
            scrollToTop();
            return;
        }
        SourceOutline.Member member = resolved.get();
        if (member.covers(line)) {
            expansion.put(member.startLine(), true);
            onMemberRead.accept(member.startLine());
        }
        rebuild(false);
        // rebuild() replaced every row, so until a layout pass runs they all
        // report bounds of zero -- and the target below would come out as 0,
        // i.e. "scroll to the top", every single time.
        applyCss();
        layout();
        rows.applyCss();
        rows.layout();
        scrollRowToTop(rowLineFor(member));
    }

    /**
     * The line whose row actually carries {@code member} on screen.
     *
     * <p>A member the reader only resolved forward onto is left unexpanded,
     * and an unexpanded untouched private helper has no row of its own -- it
     * is inside the folded group, which is keyed by the FIRST folded member's
     * line. Scrolling to the group is the closest the view can get to a
     * member it is deliberately not opening.</p>
     *
     */
    private int rowLineFor(SourceOutline.Member member) {
        for (Node node : rows.getChildren()) {
            if (Integer.valueOf(member.startLine()).equals(node.getProperties().get("drydock.line"))) {
                return member.startLine();
            }
        }
        return outline.members().stream()
                .filter(this::isFoldedAway)
                .findFirst()
                .map(SourceOutline.Member::startLine)
                .orElse(member.startLine());
    }

    /**
     * Asks for the row carrying {@code rowLine} to sit at the top of the
     * viewport, and keeps asking until it does.
     *
     * <p>One write does not hold. When the rebuild above changed the
     * content's height -- which revealing usually does, since it opens a body
     * -- {@code ScrollPaneSkin}'s next layout re-derives vvalue to preserve
     * the previous ABSOLUTE offset, overwriting whatever was set here.
     * Measured: a target of 0.41 came back as 0.05, the old offset expressed
     * against the taller content.</p>
     *
     * <p>How many layout passes that takes is not something this code can
     * know -- a fixed "and again next pulse" landed when a test ran alone and
     * missed when the same test ran inside the full suite. So the request is
     * held and re-applied on each layout until the value sticks, rather than
     * guessed at. It is bounded by {@link #MAX_SCROLL_ATTEMPTS} so an
     * unreachable target cannot re-apply forever, and any deliberate scroll
     * -- the wheel, {@code scrollToTop} -- drops it, so it can never fight
     * the reader.</p>
     */
    private void scrollRowToTop(int rowLine) {
        pendingTopRow = rowLine;
        pendingAttempts = MAX_SCROLL_ATTEMPTS;
        applyPendingTopRow();
    }

    /**
     * Called on every layout of the rows, until the pending request lands.
     *
     * <p>Success is only ever concluded from a LATER pass: reading vvalue
     * back immediately after setting it always agrees, so treating that as
     * "it held" cleared the request before the overwrite it exists to
     * survive. Here, finding the value still on target at the start of a
     * subsequent layout is what proves it stuck.</p>
     */
    private void applyPendingTopRow() {
        if (pendingTopRow < 0) {
            return;
        }
        Node row = null;
        for (Node node : rows.getChildren()) {
            if (Integer.valueOf(pendingTopRow).equals(node.getProperties().get("drydock.line"))) {
                row = node;
                break;
            }
        }
        if (row == null || rows.getHeight() - getViewportBounds().getHeight() <= 0) {
            // No row yet, not laid out yet, or nothing to scroll: this pass
            // cannot answer, so keep the request for one that can. Attempts
            // are not spent on a pass that was never able to try.
            return;
        }
        double target = Math.max(0, Math.min(1, row.getBoundsInParent().getMinY() / scrollableSpan()));
        if (Math.abs(getVvalue() - target) < 0.001) {
            pendingTopRow = -1;
            return;
        }
        if (pendingAttempts-- <= 0) {
            pendingTopRow = -1;
            return;
        }
        setVvalue(target);
    }

    /** Drops a pending reveal, so a deliberate scroll is never argued with. */
    private void cancelPendingTopRow() {
        pendingTopRow = -1;
    }

    /**
     * Puts the top of the file at the top of the viewport. Called when a
     * file opens in skim mode with no line to jump to: {@code setSkim}'s
     * anchor is read from a CodeArea that has not been laid out yet, and an
     * arbitrary anchor leaves members above the viewport with nothing
     * saying they are there.
     */
    void scrollToTop() {
        cancelPendingTopRow();
        applyCss();
        layout();
        setVvalue(0);
    }

    private boolean isExpanded(SourceOutline.Member member) {
        Boolean explicit = expansion.get(member.startLine());
        // Default: changed members are open, everything else is folded.
        return explicit != null ? explicit : member.isChanged(changed);
    }

    /**
     * @param restoreScroll whether to put the reader back where they were.
     *                      False for callers that have already decided the
     *                      position themselves -- {@link #show} loading a new
     *                      document, {@link #revealLine} driving an anchor --
     *                      where a restore queued behind them would land a
     *                      pulse later and undo it.
     */
    /**
     * Whether {@code member} loses its own row to the folded-helpers group.
     * An explicit expansion beats the fold: {@link #revealLine} puts one there
     * when a search hit or a minimap click lands inside an untouched helper,
     * and leaving it folded would make that click do nothing at all.
     */
    private boolean isFoldedAway(SourceOutline.Member member) {
        return member.privateHelper()
                && !member.isChanged(changed)
                && findingIn(member) == null
                && !Boolean.TRUE.equals(expansion.get(member.startLine()));
    }

    private void rebuild(boolean restoreScroll) {
        // Expanding one member must not move every other one under the
        // reader -- so what is preserved is the PIXEL offset, not the vvalue
        // fraction. Opening a body makes the content taller, and the same
        // fraction of a taller document is a different place: restoring 0.4
        // of 150px where it was 0.4 of 100px moves the reader 20px down,
        // which is exactly what this claims not to do.
        //
        // Not covered by a test: one written against refresh() restored
        // reliably on its own and timed out inside the full suite, the same
        // FX-timing sensitivity this class keeps hitting. The arithmetic is
        // the honest form of what the line above always claimed; verifying it
        // wants a harness that can settle layout deterministically.
        double scrollOffset = getVvalue() * scrollableSpan();
        rows.getChildren().clear();
        List<SourceOutline.Member> folded = new ArrayList<>();
        for (SourceOutline.Member member : outline.members()) {
            if (isFoldedAway(member)) {
                folded.add(member);
                continue;
            }
            rows.getChildren().add(buildRow(member));
        }
        if (!folded.isEmpty()) {
            rows.getChildren().add(buildHelperGroup(folded));
        }
        // Deferred: the ScrollPane clamps vvalue against a content height
        // that is still zero until the new rows have been laid out.
        if (restoreScroll) {
            // Same rule SearchRail.rebuild() uses for the identical race: a
            // revealLine (minimap click, `z` back) can land in the pulse
            // between this rebuild and its own deferred restore -- findings
            // and diff-overlay refreshes arrive over MCP while the reader is
            // reading, so refresh() and a reveal really are asynchronous
            // with respect to each other. Clearing the rows just above
            // collapses the content height and clamps vvalue to 0, so a
            // non-zero value one pulse later is someone else's write and
            // outranks the position captured here.
            Platform.runLater(() -> {
                if (getVvalue() == 0) {
                    double span = scrollableSpan();
                    setVvalue(Math.max(0, Math.min(1, scrollOffset / span)));
                }
            });
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
            rebuild(true);
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
            rebuild(true);
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
        int start = Math.max(1, member.startLine());
        int to = Math.min(lines.size(), member.endLine());
        // The header row already shows the signature. Repeating it as the
        // body's first line costs a row per open member and reads like a
        // rendering fault -- so it is dropped, but only when line `start`
        // really is the signature (a wrapped or annotated declaration is
        // not) and only when something is left underneath it. Compared
        // through stripComment because that is how the signature was built:
        // `void run() { // see below` would not match its own line otherwise.
        boolean repeatsSignature = to > start && start <= lines.size()
                && SourceOutline.stripComment(lines.get(start - 1)).strip().equals(member.signature());
        int from = repeatsSignature ? start + 1 : start;
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
        // Flowless's VirtualFlow handles ScrollEvent.ANY and consumes it
        // unconditionally -- even here, where the body is sized to its
        // content and has nothing to scroll. Without this filter the wheel
        // dies wherever the cursor sits over open code. A filter, not a
        // handler: it has to win before the event reaches the flow.
        scroll.addEventFilter(ScrollEvent.SCROLL, this::redispatchWheel);
        return scroll;
    }
}
