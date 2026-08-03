package app.drydock.ui.explorer;

import app.drydock.ui.code.SyntaxHighlighter;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The stack of peek cards over the viewer (Explorer delta, part 1).
 *
 * <p>Peeking is deliberately not jumping: the card sits over the file the
 * reader is already in, so ten hops deep still costs one {@code esc} each to
 * come back and the trail never grows a waypoint the reader did not ask
 * for. The stack is capped at {@link #MAX_DEPTH}; the cap is reported
 * through {@link #setOnStackFull} rather than silently dropping the click.</p>
 *
 * <p>A {@link Pane} with {@code pickOnBounds} off: everywhere there is no
 * card the viewer underneath must still take the mouse, or the whole file
 * would go dead the moment one peek opened.</p>
 */
final class PeekLayer extends Pane {

    /** Peek depth cap (delta part 1). Beyond this the reader is lost, not exploring. */
    static final int MAX_DEPTH = 5;

    private static final double CARD_WIDTH = 430;
    private static final double CARD_MAX_BODY_HEIGHT = 190;
    /** Bottom inset clears the trail bar; right inset clears the minimap strip. */
    private static final double CARD_RIGHT = 44;
    private static final double CARD_BOTTOM = 18;
    /** Each card below the top peeks out by this much, so the stack is visibly a stack. */
    private static final double STACK_OFFSET = 18;

    private final List<SymbolPeek> stack = new ArrayList<>();
    private final List<Region> cards = new ArrayList<>();

    private boolean usagesOpen;
    private Consumer<SymbolPeek> onPromote = peek -> { };
    private Consumer<SymbolPeek> onAsk = peek -> { };
    private Runnable onStackFull = () -> { };
    private Runnable onChanged = () -> { };
    private BooleanSupplier agentAvailable = () -> false;

    PeekLayer() {
        getStyleClass().add("peek-layer");
        setPickOnBounds(false);
        setVisible(false);
        setManaged(false);
    }

    void setOnPromote(Consumer<SymbolPeek> handler) {
        this.onPromote = handler == null ? peek -> { } : handler;
    }

    void setOnAsk(Consumer<SymbolPeek> handler) {
        this.onAsk = handler == null ? peek -> { } : handler;
    }

    /** Called when a click would exceed {@link #MAX_DEPTH} (the "esc to unwind" toast). */
    void setOnStackFull(Runnable handler) {
        this.onStackFull = handler == null ? () -> { } : handler;
    }

    /** Called after every push/pop/clear, so the owner can repaint what depends on the stack. */
    void setOnChanged(Runnable handler) {
        this.onChanged = handler == null ? () -> { } : handler;
    }

    /**
     * Whether the bound session can be asked anything. Agent-dependent
     * actions degrade to <em>absent</em>, never disabled-grey (delta hard
     * rules): a greyed button on a session that will never come back is an
     * invitation to keep clicking.
     */
    void setAgentAvailable(BooleanSupplier available) {
        this.agentAvailable = available == null ? () -> false : available;
    }

    int depth() {
        return stack.size();
    }

    boolean isOpen() {
        return !stack.isEmpty();
    }

    Optional<SymbolPeek> top() {
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.get(stack.size() - 1));
    }

    /** Pushes a peek; refuses (and reports) at {@link #MAX_DEPTH}. */
    boolean push(SymbolPeek peek) {
        if (stack.size() >= MAX_DEPTH) {
            onStackFull.run();
            return false;
        }
        stack.add(peek);
        usagesOpen = false;
        rebuild();
        return true;
    }

    /** {@code esc}: closes exactly one card. */
    boolean popOne() {
        if (stack.isEmpty()) {
            return false;
        }
        stack.remove(stack.size() - 1);
        usagesOpen = false;
        rebuild();
        return true;
    }

    /** Collapses the whole stack (a promote, or opening another file). */
    void clear() {
        if (stack.isEmpty()) {
            return;
        }
        stack.clear();
        usagesOpen = false;
        rebuild();
    }

    /** {@code u}: shows/hides the top card's occurrence list. */
    void toggleUsages() {
        if (stack.isEmpty()) {
            return;
        }
        usagesOpen = !usagesOpen;
        rebuild();
    }

    /** {@code ⏎}: opens the top peek for real. */
    void promoteTop() {
        top().ifPresent(peek -> onPromote.accept(peek));
    }

    /** {@code a}: hands the top peek to the bound session (absent without one). */
    void askTop() {
        if (agentAvailable.getAsBoolean()) {
            top().ifPresent(peek -> onAsk.accept(peek));
        }
    }

    private void rebuild() {
        cards.clear();
        getChildren().clear();
        boolean open = !stack.isEmpty();
        setVisible(open);
        setManaged(false);
        if (open) {
            // Only the top card is built in full: the ones below are ghosts,
            // there to say "there is a stack", and building five live
            // CodeAreas to show 3px of each would be pure waste.
            int ghosts = Math.min(stack.size() - 1, 2);
            for (int i = ghosts; i >= 1; i--) {
                Region ghost = new Region();
                ghost.getStyleClass().add("peek-card-ghost");
                ghost.setPrefWidth(CARD_WIDTH);
                ghost.setPrefHeight(140);
                cards.add(ghost);
                getChildren().add(ghost);
            }
            Region card = buildCard(stack.get(stack.size() - 1), stack.size());
            cards.add(card);
            getChildren().add(card);
        }
        requestLayout();
        onChanged.run();
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        // Bottom-right, each card stepped up-and-left from the one on top of
        // it, matching the prototype's stacked-card affordance.
        for (int i = 0; i < cards.size(); i++) {
            Region card = cards.get(i);
            int fromTop = cards.size() - 1 - i;
            double cardWidth = Math.min(CARD_WIDTH, Math.max(220, width - 24));
            double cardHeight = card.prefHeight(cardWidth);
            double x = width - cardWidth - CARD_RIGHT + fromTop * STACK_OFFSET;
            double y = height - cardHeight - CARD_BOTTOM - fromTop * STACK_OFFSET;
            card.resizeRelocate(Math.max(0, x), Math.max(0, y), cardWidth, cardHeight);
        }
    }

    private Region buildCard(SymbolPeek peek, int depth) {
        Label title = new Label(peek.title());
        title.getStyleClass().add("peek-title");
        Label meta = new Label("peek " + depth + " of " + depth + " · esc closes one");
        meta.getStyleClass().add("peek-meta");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("peek-close");
        close.setOnAction(e -> popOne());
        HBox header = new HBox(7, title, meta, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("peek-header");

        VBox body = new VBox(buildCode(peek));
        body.getStyleClass().add("peek-body");
        if (usagesOpen) {
            body.getChildren().add(buildUsages(peek));
        }

        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("peek-footer");
        Button promote = new Button("⏎ open for real");
        promote.getStyleClass().addAll("peek-action", "primary");
        promote.setOnAction(e -> onPromote.accept(peek));
        Button usages = new Button("u occurrences · " + peek.occurrences().size());
        usages.getStyleClass().add("peek-action");
        usages.setOnAction(e -> toggleUsages());
        footer.getChildren().setAll(promote, usages);
        if (agentAvailable.getAsBoolean()) {
            Button ask = new Button("a ask the agent");
            ask.getStyleClass().add("peek-action");
            ask.setOnAction(e -> onAsk.accept(peek));
            footer.getChildren().add(ask);
        }

        VBox card = new VBox(header, body, footer);
        card.getStyleClass().add("peek-card");
        card.setPrefWidth(CARD_WIDTH);
        return card;
    }

    private Node buildCode(SymbolPeek peek) {
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("code-area", "peek-code");
        area.setEditable(false);
        area.setFocusTraversable(false);
        area.replaceText(peek.text());
        SyntaxHighlighter.Language language =
                SyntaxHighlighter.Language.fromFileName(peek.file().getFileName().toString());
        if (!peek.text().isEmpty()) {
            area.setStyleSpans(0, SyntaxHighlighter.computeHighlighting(peek.text(), language));
        }
        int firstLine = peek.startLine();
        area.setParagraphGraphicFactory(paragraph -> {
            int fileLine = firstLine + paragraph;
            Label number = new Label(Integer.toString(fileLine));
            number.getStyleClass().add("peek-lineno");
            Region marker = new Region();
            marker.getStyleClass().add("changed-line-marker");
            if (peek.changedLines().contains(fileLine)) {
                marker.getStyleClass().add("on");
            }
            HBox box = new HBox(2, marker, number);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        });
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
        scroll.setMaxHeight(CARD_MAX_BODY_HEIGHT);
        scroll.setPrefHeight(Math.min(CARD_MAX_BODY_HEIGHT, 20 + peek.lines().size() * 17.0));
        return scroll;
    }

    private Node buildUsages(SymbolPeek peek) {
        VBox list = new VBox(2);
        list.getStyleClass().add("peek-usages");
        Label heading = new Label("OCCURRENCES · " + peek.occurrences().size()
                + (peek.resolvedDeclaration() ? "" : " · no declaration found"));
        heading.getStyleClass().add("peek-usages-title");
        list.getChildren().add(heading);
        for (SymbolPeek.Occurrence occurrence : peek.occurrences()) {
            Label where = new Label(occurrence.label());
            where.getStyleClass().add("peek-usage-loc");
            HBox.setHgrow(where, Priority.ALWAYS);
            where.setMaxWidth(Double.MAX_VALUE);
            Label chip = new Label(occurrence.inDiff() ? "in diff" : "not touched");
            chip.getStyleClass().add(occurrence.inDiff() ? "peek-usage-chip-diff" : "peek-usage-chip");
            HBox row = new HBox(7, where, chip);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("peek-usage-row");
            if (!occurrence.inDiff()) {
                row.getStyleClass().add("untouched");
            }
            list.getChildren().add(row);
        }
        return list;
    }
}
