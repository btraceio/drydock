package app.drydock.ui.nav;

import app.drydock.review.Provenance;
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
public final class PeekLayer extends Pane {

    /** Peek depth cap (delta part 1). Beyond this the reader is lost, not exploring. */
    public static final int MAX_DEPTH = 5;

    private static final double CARD_WIDTH = 430;
    private static final double CARD_MAX_BODY_HEIGHT = 190;
    /** Bottom inset clears the trail bar; right inset clears the minimap strip. */
    private static final double CARD_RIGHT = 44;
    private static final double CARD_BOTTOM = 18;
    /** Each card below the top peeks out by this much, so the stack is visibly a stack. */
    private static final double STACK_OFFSET = 18;

    /** Usage rows shown before the list says how many more there are. */
    static final int MAX_USAGES_SHOWN = 30;

    /** The usages list's own body cap: taller than the code's, because it is the only thing on show. */
    private static final double USAGE_MAX_BODY_HEIGHT = 300;

    /**
     * Whether the one-per-session language-server hint has shown. One app
     * run is one session (usage-resolution design 2026-10-08, §§3 and 8):
     * the hint is quiet by contract -- never an error, never a dialog -- and
     * saying it every peek would be the nagging the "quiet" is there to
     * prevent. FX-thread-only state, like every render input here.
     */
    private static boolean languageServerHintShown;

    private final List<SymbolPeek> stack = new ArrayList<>();
    private final List<Region> cards = new ArrayList<>();

    private boolean usagesOpen;
    private Consumer<SymbolPeek> onPromote = peek -> { };
    private Consumer<SymbolPeek> onAsk = peek -> { };
    /** A click on a usage row: the owner decides what jumping to that occurrence means. */
    private Consumer<SymbolPeek.Occurrence> onOpenOccurrence = occurrence -> { };
    private Runnable onStackFull = () -> { };
    private Runnable onChanged = () -> { };
    private BooleanSupplier agentAvailable = () -> false;

    public PeekLayer() {
        getStyleClass().add("peek-layer");
        // Managed, or the StackPane above never resizes it and every card
        // lays out against a 0x0 layer -- in the top-left corner, at its
        // minimum width. Max size is explicit for the same reason: a
        // Region's max defaults to its PREFERRED size, and this layer's
        // preferred size is the 0 its unmanaged children compute.
        setPickOnBounds(false);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setVisible(false);
    }

    public void setOnPromote(Consumer<SymbolPeek> handler) {
        this.onPromote = handler == null ? peek -> { } : handler;
    }

    public void setOnAsk(Consumer<SymbolPeek> handler) {
        this.onAsk = handler == null ? peek -> { } : handler;
    }

    /**
     * A click on a usage row of the top card. The card itself does not
     * guess what "going there" means: the Explorer opens the file, Review
     * peeks at the location in place.
     */
    public void setOnOpenOccurrence(Consumer<SymbolPeek.Occurrence> handler) {
        this.onOpenOccurrence = handler == null ? occurrence -> { } : handler;
    }

    /** Called when a click would exceed {@link #MAX_DEPTH} (the "esc to unwind" toast). */
    public void setOnStackFull(Runnable handler) {
        this.onStackFull = handler == null ? () -> { } : handler;
    }

    /** Called after every push/pop/clear, so the owner can repaint what depends on the stack. */
    public void setOnChanged(Runnable handler) {
        this.onChanged = handler == null ? () -> { } : handler;
    }

    /**
     * Whether the bound session can be asked anything. Agent-dependent
     * actions degrade to <em>absent</em>, never disabled-grey (delta hard
     * rules): a greyed button on a session that will never come back is an
     * invitation to keep clicking.
     */
    public void setAgentAvailable(BooleanSupplier available) {
        this.agentAvailable = available == null ? () -> false : available;
    }

    public int depth() {
        return stack.size();
    }

    public boolean isOpen() {
        return !stack.isEmpty();
    }

    public Optional<SymbolPeek> top() {
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.get(stack.size() - 1));
    }

    /** Pushes a peek; refuses (and reports) at {@link #MAX_DEPTH}. */
    public boolean push(SymbolPeek peek) {
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
    public boolean popOne() {
        if (stack.isEmpty()) {
            return false;
        }
        stack.remove(stack.size() - 1);
        usagesOpen = false;
        rebuild();
        return true;
    }

    /** Collapses the whole stack (a promote, or opening another file). */
    public void clear() {
        if (stack.isEmpty()) {
            return;
        }
        stack.clear();
        usagesOpen = false;
        rebuild();
    }

    /** {@code u}: shows/hides the top card's occurrence list. */
    public void toggleUsages() {
        if (stack.isEmpty()) {
            return;
        }
        usagesOpen = !usagesOpen;
        rebuild();
    }

    /** {@code ⏎}: opens the top peek for real. */
    public void promoteTop() {
        top().ifPresent(peek -> onPromote.accept(peek));
    }

    /** {@code a}: hands the top peek to the bound session (absent without one). */
    public void askTop() {
        if (agentAvailable.getAsBoolean()) {
            top().ifPresent(peek -> onAsk.accept(peek));
        }
    }

    private void rebuild() {
        cards.clear();
        getChildren().clear();
        boolean open = !stack.isEmpty();
        setVisible(open);
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

        VBox body = new VBox(buildCode(peek, usagesOpen));
        body.getStyleClass().add("peek-body");

        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("peek-footer");
        Button promote = new Button("⏎ open for real");
        promote.getStyleClass().addAll("peek-action", "primary");
        promote.setOnAction(e -> onPromote.accept(peek));
        Button usages = new Button("u usages · " + peek.occurrences().size());
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
        maybeAddLanguageServerHint(card, peek);
        return card;
    }

    /**
     * The one-per-session quiet footer hint (usage-resolution design
     * 2026-10-08, §§3 and 8): on the FIRST tier-3-eligible peek of the app
     * run -- a {@code .java} symbol peek, the only kind a JDT server could
     * have answered -- when no occurrence resolved. A server that answered
     * something shows its resolved rows instead; a non-Java peek is never
     * eligible; a location-only peek has no occurrences to resolve. Never
     * an error, never a dialog, never twice.
     */
    private void maybeAddLanguageServerHint(VBox card, SymbolPeek peek) {
        if (languageServerHintShown || peek.occurrences().isEmpty()) {
            return;
        }
        String fileName = peek.file().getFileName() == null ? "" : peek.file().getFileName().toString();
        if (!fileName.endsWith(".java")) {
            return;
        }
        boolean anyResolved = peek.occurrences().stream()
                .anyMatch(occurrence -> occurrence.provenance() == Provenance.RESOLVED);
        if (anyResolved) {
            return;
        }
        languageServerHintShown = true;
        Label hint = new Label("Exact references need a language server — see Settings");
        hint.getStyleClass().add("peek-lsp-hint");
        hint.setWrapText(true);
        card.getChildren().add(hint);
    }

    /** Test-only: the hint is once per app run, so a test session resets its session. */
    static void resetLanguageServerHintForTest() {
        languageServerHintShown = false;
    }

    private static Label scopedChip() {
        Label chip = new Label("scoped");
        chip.getStyleClass().add("peek-usage-chip-scoped");
        return chip;
    }

    /**
     * A language server confirmed this row (usage-resolution design
     * 2026-10-08, §7): the scoped chip's shape in the resolved tone -- one
     * chip shape, the label says the tier. Carries the provenance's own
     * modifier class ({@code provenance-resolved}) beside the peek's, the
     * same convention the step panel's resolved entries follow.
     */
    private static Label resolvedChip() {
        Label chip = new Label("resolved");
        chip.getStyleClass().addAll("peek-usage-chip-resolved", Provenance.RESOLVED.styleClass());
        return chip;
    }

    private Node buildCode(SymbolPeek peek, boolean usages) {
        if (usages) {
            // The list REPLACES the code, it is not appended under it: the
            // excerpt has its own virtualized scroll and a list below it
            // needs a second one, so a card showing both scrolls twice --
            // two nested vertical scrollbars, the bottom one stealing the
            // wheel from the top. One body, one scrollbar.
            javafx.scene.control.ScrollPane outer = new javafx.scene.control.ScrollPane(buildUsages(peek));
            outer.setFitToWidth(true);
            outer.getStyleClass().add("peek-scroll");
            outer.setPrefHeight(USAGE_MAX_BODY_HEIGHT);
            outer.setMinHeight(CARD_MAX_BODY_HEIGHT);
            outer.setMaxHeight(USAGE_MAX_BODY_HEIGHT);
            return outer;
        }
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
        double codeHeight = Math.min(CARD_MAX_BODY_HEIGHT, 20 + peek.lines().size() * 17.0);
        scroll.setPrefHeight(codeHeight);
        scroll.setMinHeight(codeHeight);
        scroll.setMaxHeight(CARD_MAX_BODY_HEIGHT);
        return scroll;
    }

    private Node buildUsages(SymbolPeek peek) {
        VBox list = new VBox(2);
        list.getStyleClass().add("peek-usages");
        long bound = peek.occurrences().stream().filter(SymbolPeek.Occurrence::bound).count();
        int total = peek.occurrences().size();
        // The counts the server line names (§7): from provenance, because
        // that is the tier each row actually carries.
        long resolved = peek.occurrences().stream()
                .filter(occurrence -> occurrence.provenance() == Provenance.RESOLVED).count();
        long scoped = peek.occurrences().stream()
                .filter(occurrence -> occurrence.provenance() == Provenance.SCOPED).count();
        String warrant = bound == 0
                ? "lexical name matches"
                : bound + " bound by scope · " + (total - bound)
                        + (total - bound == 1 ? " name match" : " name matches");
        Label heading = new Label("USAGES · " + total + " (" + warrant
                + " — click a row to go there)"
                + (peek.resolvedDeclaration() ? "" : " · no declaration found"));
        heading.getStyleClass().add("peek-usages-title");
        heading.setWrapText(true);
        list.getChildren().add(heading);
        if (resolved > 0) {
            // The server line (§7): the only reliable "a server answered"
            // signal is a row it confirmed -- the composed answer's status
            // is always ANSWERED (the lexical floor), so a server that
            // confirmed nothing keeps the ordinary headline.
            Label server = new Label(resolved + " resolved · " + scoped + " scoped · "
                    + (total - resolved - scoped) + " name matches");
            server.getStyleClass().add("peek-usages-server");
            list.getChildren().add(server);
        }
        List<SymbolPeek.Occurrence> shown = peek.occurrences().size() > MAX_USAGES_SHOWN
                ? peek.occurrences().subList(0, MAX_USAGES_SHOWN)
                : peek.occurrences();
        for (SymbolPeek.Occurrence occurrence : shown) {
            Label where = new Label(occurrence.label());
            where.getStyleClass().add("peek-usage-loc");
            HBox.setHgrow(where, Priority.ALWAYS);
            where.setMaxWidth(Double.MAX_VALUE);
            Label chip = new Label(occurrence.inDiff() ? "in diff" : "not touched");
            chip.getStyleClass().add(occurrence.inDiff() ? "peek-usage-chip-diff" : "peek-usage-chip");
            // A bound row says so: it is a real reference, not a shared
            // name, and the difference is exactly what the reader is
            // scanning the list for. A RESOLVED row says the stronger thing
            // instead -- one chip, the highest tier it earned.
            List<Node> rowChildren = new ArrayList<>(List.of(where, chip));
            if (occurrence.provenance() == Provenance.RESOLVED) {
                rowChildren.add(resolvedChip());
            } else if (occurrence.bound()) {
                rowChildren.add(scopedChip());
            }
            HBox row = new HBox(7, rowChildren.toArray(Node[]::new));
            row.setAlignment(Pos.CENTER_LEFT);
            // A real Button, not a labelled row: the row IS the navigation,
            // so it must be focusable and answer Enter/Space like every
            // other primary action in the workspace.
            Button open = new Button();
            open.setGraphic(row);
            open.getStyleClass().addAll("peek-usage-row", "peek-usage-open");
            if (!occurrence.inDiff()) {
                open.getStyleClass().add("untouched");
            }
            open.setOnAction(event -> onOpenOccurrence.accept(occurrence));
            list.getChildren().add(open);
        }
        if (shown.size() < peek.occurrences().size()) {
            // Said, not silently truncated: a capped list that does not say
            // so reads as "this is all of them".
            Label more = new Label("… " + (peek.occurrences().size() - shown.size())
                    + " more — search for it in the rail");
            more.getStyleClass().add("peek-usages-more");
            list.getChildren().add(more);
        }
        return list;
    }
}
