package app.drydock.ui.review;

import app.drydock.review.ReviewItem;
import app.drydock.ui.PanelHeader;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Review queue rail (spec §4.1): items grouped MINE · AGENTS ·
 * REQUESTED · STACK, each row an icon column with its open-finding count
 * beneath it, a session status dot, a mono title and a dim subtitle.
 *
 * <p>Collapses to 44px, animated the way {@code SessionExplorerView}
 * animates its search rail. Collapsed, a row keeps its icon and count and
 * moves everything else into the tooltip -- the rail never removes an
 * action, it only stops showing its description.</p>
 *
 * <p>Every row is a real {@link Button}: focus-traversable, activated by
 * Enter/Space, with the focus ring {@code app.css} gives {@code
 * .review-queue-item:focused}. The rail is a plain scrolled {@link VBox}
 * rather than a {@code ListView} for exactly that reason -- a queue is
 * tens of rows, not thousands, so nothing is paid for the virtualization
 * that would otherwise cost every row its own focus stop.</p>
 */
final class ReviewQueueRail extends VBox {

    static final double EXPANDED_WIDTH = 236;
    static final double NARROW_WIDTH = 206;
    static final double COLLAPSED_WIDTH = 44;
    private static final Duration COLLAPSE_ANIMATION = Duration.millis(160);

    /** How many open findings an item has; empty when no reviewer has run (spec §4.1). */
    @FunctionalInterface
    interface FindingCount {
        Optional<Integer> openFindings(ReviewItem item);
    }

    /** Session liveness for the row's status dot; absent = no session bound. */
    @FunctionalInterface
    interface SessionDot {
        /** One of {@code running} / {@code idle}, or empty for "no session". */
        Optional<String> stateOf(ReviewItem item);
    }

    // The handler is read at click time, so it can be installed after construction.
    private final PanelHeader header = PanelHeader.left(
            "QUEUE", "j k · q", "Collapse or expand the queue (q)",
            () -> this.onToggleCollapse.run());
    private final VBox rows = new VBox();
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Label footer = new Label();
    private final TextField filterField = new TextField();

    private final Map<String, Button> buttonsByScopeId = new LinkedHashMap<>();
    private final List<ReviewItem> items = new ArrayList<>();

    private FindingCount findingCount = item -> Optional.empty();
    private SessionDot sessionDot = item -> Optional.empty();
    private Consumer<ReviewItem> onSelected = item -> { };
    private Consumer<ReviewItem> onOpened = item -> { };
    private Consumer<ReviewItem> onOpenSession = item -> { };
    private Consumer<ReviewItem> onRunReview = item -> { };
    private Runnable onToggleCollapse = () -> { };

    /**
     * Whether opening an item is a separate step from selecting it -- true on
     * the narrow drill-in's Browse page, where the diff is a page away rather
     * than beside the rail. Drives the rows' {@code ›} chevron and the
     * header's {@code ↵} hint: the gesture is only worth advertising where it
     * actually goes somewhere.
     */
    private boolean opensSeparately;

    private boolean collapsed;
    private boolean narrow;

    /** Non-zero while the narrow Browse page sizes this rail; see {@link #setSpanWidth}. */
    private double spanWidth;

    /** The collapse/expand animation currently running, so a span set can cancel it. */
    private Timeline widthAnimation;

    private String selectedScopeId;

    /**
     * Set when {@code /} focuses this field. Consuming that key's {@code
     * KEY_PRESSED} in Review's table does not stop the separate {@code
     * KEY_TYPED} from arriving, and by the time it does, this field owns
     * focus -- so the key that opens the filter would otherwise type itself
     * into it. Never set for {@code ⌘F}: a shortcut-modified press produces
     * no character, and swallowing there would eat a real keystroke.
     */
    private boolean swallowNextTypedSlash;

    ReviewQueueRail() {
        getStyleClass().add("review-queue-rail");
        setMinWidth(EXPANDED_WIDTH);
        setPrefWidth(EXPANDED_WIDTH);
        setMaxWidth(EXPANDED_WIDTH);

        rows.getStyleClass().add("review-queue-items");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("review-queue-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        footer.getStyleClass().add("review-rail-footer");
        footer.setMaxWidth(Double.MAX_VALUE);

        filterField.getStyleClass().addAll("filter-field", "review-queue-filter");
        filterField.setPromptText("⌕  Filter the queue…");
        // No debounce: this rebuild is tens of buttons over in-memory
        // lookups, so a timer would only add latency (spec §Rendering).
        filterField.textProperty().addListener((observable, old, text) -> rebuild());
        filterField.setOnKeyPressed(this::onFilterKeyPressed);
        filterField.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (swallowNextTypedSlash) {
                swallowNextTypedSlash = false;
                if ("/".equals(event.getCharacter())) {
                    event.consume();
                }
            }
        });
        VBox.setMargin(filterField, new Insets(0, 8, 6, 8));

        getChildren().setAll(header.node(), filterField, scroll, footer);
    }

    void setOnSelected(Consumer<ReviewItem> handler) {
        this.onSelected = handler == null ? item -> { } : handler;
    }

    /**
     * "Open this item", as distinct from {@link #setOnSelected}'s "make this
     * the current item": a double-click, the context menu's first entry, and
     * -- on the narrow Browse page -- {@code Enter}. Selecting is what loads
     * the diff; opening is what puts the reader in front of it.
     */
    void setOnOpened(Consumer<ReviewItem> handler) {
        this.onOpened = handler == null ? item -> { } : handler;
    }

    /** The context menu's "Open the bound session" ({@code o} elsewhere in Review). */
    void setOnOpenSession(Consumer<ReviewItem> handler) {
        this.onOpenSession = handler == null ? item -> { } : handler;
    }

    /** The context menu's "Run the review on this item". */
    void setOnRunReview(Consumer<ReviewItem> handler) {
        this.onRunReview = handler == null ? item -> { } : handler;
    }

    /** See {@link #opensSeparately}. */
    void setOpensSeparately(boolean separately) {
        if (opensSeparately == separately) {
            return;
        }
        opensSeparately = separately;
        header.setHint(separately ? "j k · ↵ open" : "j k · q");
        rebuild();
    }

    void setOnToggleCollapse(Runnable handler) {
        this.onToggleCollapse = handler == null ? () -> { } : handler;
    }

    void setFindingCount(FindingCount counts) {
        this.findingCount = counts == null ? item -> Optional.empty() : counts;
    }

    void setSessionDot(SessionDot dots) {
        this.sessionDot = dots == null ? item -> Optional.empty() : dots;
    }

    List<ReviewItem> items() {
        return List.copyOf(items);
    }

    /** Replaces the rail's contents, preserving the selection when its scope is still present. */
    void setItems(List<ReviewItem> newItems) {
        items.clear();
        items.addAll(newItems);
        rebuild();
    }

    /** Re-renders every row from the current counts/dots without changing the item list. */
    void refreshRows() {
        rebuild();
    }

    Optional<ReviewItem> selected() {
        return items.stream().filter(item -> item.scope().id().equals(selectedScopeId)).findFirst();
    }

    /**
     * The first item the query is actually showing -- what a reassembly that
     * drops the current selection should fall back to. Falling back to
     * {@code items.get(0)} instead can select a row the query hides, leaving
     * the rail reading "no match" while the centre panel renders something
     * unrelated.
     */
    Optional<ReviewItem> firstVisible() {
        return visibleItems().stream().findFirst();
    }

    /**
     * Selects {@code scopeId} and fires the selection callback, scrolling the
     * row into view. Selecting an id the rail does not hold is a no-op:
     * the queue is reassembled asynchronously, so a stale id from a keyboard
     * repeat or a just-removed worktree must not clear the selection.
     */
    void select(String scopeId) {
        Optional<ReviewItem> match =
                items.stream().filter(item -> item.scope().id().equals(scopeId)).findFirst();
        if (match.isEmpty()) {
            return;
        }
        selectedScopeId = scopeId;
        applySelectionStyles();
        Button button = buttonsByScopeId.get(scopeId);
        if (button != null) {
            scrollIntoView(button);
        }
        onSelected.accept(match.get());
    }

    /**
     * Clears any query, then selects -- for a navigation arriving from
     * outside the rail ({@code ⌘4}, the sidebar's {@code ◨n} badge), which
     * must never land on a row the query is hiding: a badge that appears to
     * do nothing is worse than a cleared query.
     *
     * <p>Deliberately not folded into {@link #select}. That method is also
     * what {@code j}/{@code k}, a row's own click handler, and the
     * reassembly that restores the previous selection all call, and none of
     * those may touch what the user typed.</p>
     */
    void revealAndSelect(String scopeId) {
        boolean present = items.stream().anyMatch(item -> item.scope().id().equals(scopeId));
        // A stale id -- a worktree removed since the navigation was queued --
        // must not clear what the user typed either. select() already no-ops
        // on it; clearing the query too would be strictly worse than doing
        // neither.
        if (present && !query().isEmpty()) {
            filterField.clear();
        }
        select(scopeId);
    }

    /**
     * {@code j} / {@code k}: moves the selection by {@code delta} through the
     * rows the rail is actually showing. A selection the query has hidden is
     * not in that list, so it reports {@code -1} and {@link #nextIndex}'s
     * existing "nothing selected" branch enters the visible list from
     * whichever end the key came from.
     */
    void moveSelection(int delta) {
        List<ReviewItem> visible = visibleItems();
        int next = nextIndex(indexOfSelection(visible), visible.size(), delta);
        if (next >= 0) {
            select(visible.get(next).scope().id());
        }
    }

    private int indexOfSelection(List<ReviewItem> visible) {
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).scope().id().equals(selectedScopeId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Where {@code j}/{@code k} land: clamped rather than wrapped, so
     * holding a key cannot silently teleport from the last REQUESTED PR back
     * to the first MINE item. With nothing selected yet the first press
     * enters the list from whichever end it came from; an empty queue
     * returns {@code -1}.
     *
     * <p>Package-private and static so the arithmetic is testable without a
     * running JavaFX toolkit -- this repository has no headless FX harness
     * (docs/architecture.md).</p>
     */
    static int nextIndex(int current, int size, int delta) {
        if (size == 0) {
            return -1;
        }
        if (current < 0) {
            return delta > 0 ? 0 : size - 1;
        }
        return (int) Math.clamp((long) current + delta, 0, size - 1);
    }

    /**
     * Whether {@code item} survives the quick-search {@code query}.
     *
     * <p>The haystack is the row's whole visible text: the group label the
     * rail prints at the head of the second line, then the title, then the
     * subtitle. They are joined by separators so a query cannot match the
     * artifact where one field's tail meets the next one's head -- what you
     * can read in the row is what you can search for, and nothing else. A
     * blank query matches everything.</p>
     *
     * <p>Package-private and static for the same reason {@link #nextIndex}
     * is: the rule is then testable without a running toolkit.</p>
     */
    static boolean matches(ReviewItem item, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        String haystack = item.group().label() + " " + item.title() + " " + item.subtitle();
        return haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    boolean collapsed() {
        return collapsed;
    }

    /** Collapse/expand, animating width the way the Explorer's search rail does. */
    void setCollapsed(boolean newCollapsed) {
        if (collapsed == newCollapsed) {
            return;
        }
        collapsed = newCollapsed;
        resize(true);
        rebuild();
    }

    /**
     * Focuses the quick-search field and selects what is in it ({@code ⌘F}).
     * A no-op while collapsed: the field is hidden and unmanaged there, so it
     * cannot take focus, and neither key expands the rail -- {@code q} owns
     * this rail's width.
     */
    void focusFilter() {
        focusFilter(false);
    }

    /**
     * As {@link #focusFilter()}, but discards the one typed slash still in
     * flight -- so {@code /} opens the field rather than pre-loading it with
     * a {@code "/"}.
     */
    void focusFilter(boolean swallowTypedSlash) {
        if (collapsed) {
            return;
        }
        swallowNextTypedSlash = swallowTypedSlash;
        filterField.requestFocus();
        filterField.selectAll();
    }

    /**
     * The field's own {@code Enter} and {@code Esc}.
     *
     * <p>Esc has to live here rather than in Review's unwind: the
     * scene-level Escape branch gates that unwind behind "no text input has
     * focus", so it never reaches Review while this field is focused -- but
     * it does not consume the event either, so the key arrives at the
     * focused node. A blank query is not ours to swallow; leaving it
     * unconsumed lets the ordinary unwind resume once focus is off the
     * field.</p>
     */
    private void onFilterKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER -> {
                // One selection, one git diff -- Enter is what commits the
                // filter, never a keystroke. Where opening is a separate
                // step it opens too: having narrowed the queue to the item
                // they want, the reader means to go to it.
                visibleItems().stream().findFirst().ifPresent(item -> {
                    select(item.scope().id());
                    if (opensSeparately) {
                        onOpened.accept(item);
                    }
                });
                event.consume();
            }
            case ESCAPE -> {
                if (!query().isEmpty()) {
                    filterField.clear();
                    returnFocusToRail();
                    event.consume();
                }
            }
            default -> { }
        }
    }

    /** Moves focus off the field so Review's key table stops returning early. */
    private void returnFocusToRail() {
        Button selected = buttonsByScopeId.get(selectedScopeId);
        if (selected != null) {
            selected.requestFocus();
        } else {
            scroll.requestFocus();
        }
    }

    /**
     * Switches between the 236px and 206px expanded widths (spec §4.9's
     * narrow band). Ignored while collapsed -- the collapsed width is the
     * same in both bands, and re-animating to it would fight the collapse.
     */
    void setNarrow(boolean newNarrow) {
        if (narrow == newNarrow) {
            return;
        }
        narrow = newNarrow;
        if (!collapsed) {
            resize(true);
        }
    }

    /**
     * The narrow drill-in's Browse page (spec §4.9) sizes the rails as
     * fractions of the window rather than letting them keep their own fixed
     * width. {@code 0} hands sizing back to {@link #targetWidth()}.
     *
     * <p>Never animated: this follows the window's width listener, so a
     * {@link Timeline} per resize tick would queue dozens of overlapping
     * animations and the rail would lag the drag by a visible fraction of a
     * second.</p>
     */
    void setSpanWidth(double width) {
        if (spanWidth == width) {
            return;
        }
        spanWidth = width;
        resize(false);
    }

    /** The width the rail should currently occupy: a span override, else its own. */
    private void resize(boolean animate) {
        if (spanWidth > 0) {
            applyWidth(spanWidth);
        } else if (animate) {
            animateTo(targetWidth());
        } else {
            applyWidth(targetWidth());
        }
    }

    /**
     * Pins the width now, cancelling any collapse animation still in flight.
     * Without that cancel a span set moments after a collapse/expand would be
     * silently animated back over the following 160ms -- the rail would take
     * its span for one frame and then return to its own width.
     */
    private void applyWidth(double target) {
        stopWidthAnimation();
        setMinWidth(target);
        setPrefWidth(target);
        setMaxWidth(target);
    }

    private void stopWidthAnimation() {
        if (widthAnimation != null) {
            widthAnimation.stop();
            widthAnimation = null;
        }
    }

    private double targetWidth() {
        if (collapsed) {
            return COLLAPSED_WIDTH;
        }
        return narrow ? NARROW_WIDTH : EXPANDED_WIDTH;
    }

    private void animateTo(double target) {
        stopWidthAnimation();
        widthAnimation = new Timeline(new KeyFrame(COLLAPSE_ANIMATION,
                new KeyValue(minWidthProperty(), target),
                new KeyValue(prefWidthProperty(), target),
                new KeyValue(maxWidthProperty(), target)));
        widthAnimation.play();
    }

    /**
     * What the rail is actually rendering: the query's survivors while
     * expanded, and every item while collapsed.
     *
     * <p>A collapse suppresses the filtering as well as the field. The 44px
     * rail still draws one row per item, so a collapsed rail that kept
     * filtering would show three icons where thirteen exist -- with no
     * field, no footer count and nothing on screen to explain the gap. The
     * query is kept rather than cleared, because a collapse can come from a
     * window resize rather than from the user.</p>
     */
    private List<ReviewItem> visibleItems() {
        if (collapsed) {
            return List.copyOf(items);
        }
        return items.stream().filter(item -> matches(item, query())).toList();
    }

    private String query() {
        return filterField.getText() == null ? "" : filterField.getText();
    }

    private void rebuild() {
        header.showCollapsed(collapsed);
        header.setTitleVisible(!collapsed);
        header.setHintVisible(!collapsed);
        filterField.setVisible(!collapsed);
        filterField.setManaged(!collapsed);
        footer.setVisible(!collapsed);
        footer.setManaged(!collapsed);

        List<ReviewItem> visible = visibleItems();
        buttonsByScopeId.clear();
        List<Node> children = new ArrayList<>();
        ReviewItem.Group lastGroup = null;
        for (ReviewItem item : visible) {
            if (item.group() != lastGroup) {
                lastGroup = item.group();
                if (!collapsed) {
                    Label group = new Label(lastGroup.label());
                    group.getStyleClass().add("review-queue-group");
                    children.add(group);
                }
            }
            Button row = buildRow(item);
            buttonsByScopeId.put(item.scope().id(), row);
            children.add(row);
        }
        // An empty rail reads as a broken queue. Say what actually happened:
        // the queue is fine and the query is too narrow.
        if (visible.isEmpty() && !items.isEmpty() && !collapsed) {
            Label noMatch = new Label("No queue item matches \"" + query().strip() + "\"");
            noMatch.getStyleClass().add("review-queue-no-match");
            noMatch.setWrapText(true);
            children.add(noMatch);
        }
        rows.getChildren().setAll(children);
        applySelectionStyles();
        footer.setText(footerText(visible.size(), items.size()));
    }

    /** {@code 13 items}, or {@code 3 of 13 items} while a query is narrowing the rail. */
    private static String footerText(int shown, int total) {
        String noun = total == 1 ? " item" : " items";
        return shown == total ? total + noun : shown + " of " + total + noun;
    }

    private Button buildRow(ReviewItem item) {
        Label icon = new Label(item.icon());
        icon.getStyleClass().add("review-queue-icon");
        VBox iconColumn = new VBox(2, icon);
        iconColumn.setAlignment(Pos.CENTER);
        iconColumn.getStyleClass().add("review-queue-icon-column");
        // Derived, never stored: an item whose reviewer has never run shows
        // no count at all rather than a confident zero (spec §4.1).
        findingCount.openFindings(item).filter(count -> count > 0).ifPresent(count -> {
            Label badge = new Label(String.valueOf(count));
            badge.getStyleClass().addAll("review-queue-count", "count-open");
            iconColumn.getChildren().add(badge);
        });

        Button row = new Button();
        row.getStyleClass().add("review-queue-item");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setTooltip(new Tooltip(item.tooltip()
                + (opensSeparately ? "\nDouble-click or Enter to open it" : "")));
        row.setOnAction(e -> select(item.scope().id()));
        // A double-click opens; the first of its two clicks has already
        // selected through setOnAction, so this only ever adds the second
        // step. Right-click puts the same action, and the two that used to be
        // keyboard-only, where a reader looks for them.
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                select(item.scope().id());
                onOpened.accept(item);
                e.consume();
            }
        });
        row.setContextMenu(contextMenuFor(item));

        if (collapsed) {
            row.setGraphic(iconColumn);
            row.getStyleClass().add("collapsed");
            return row;
        }

        Region dot = new Region();
        dot.getStyleClass().add("review-queue-dot");
        sessionDot.stateOf(item).ifPresentOrElse(
                state -> dot.getStyleClass().add("dot-" + state),
                () -> dot.getStyleClass().add("dot-none"));

        Label title = new Label(item.title());
        title.getStyleClass().add("review-queue-title");
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox titleRow = new HBox(5, dot, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label(item.group().label().toLowerCase(Locale.ROOT)
                + " · " + item.subtitle());
        subtitle.getStyleClass().add("review-queue-subtitle");

        VBox text = new VBox(2, titleRow, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox content = new HBox(7, iconColumn, text);
        // The chevron says the row goes somewhere -- the missing half of
        // "press Enter to open it", which nothing on this page used to say.
        if (opensSeparately) {
            Label chevron = new Label("›");
            chevron.getStyleClass().add("review-queue-chevron");
            content.getChildren().add(chevron);
        }
        content.setAlignment(Pos.CENTER_LEFT);
        row.setGraphic(content);
        // The graphic must track the button's own width or the ellipsized
        // labels inside it size to their (unbounded) preferred text width.
        content.prefWidthProperty().bind(row.widthProperty().subtract(20));
        content.maxWidthProperty().bind(content.prefWidthProperty());
        return row;
    }

    /**
     * A row's right-click menu. Every entry is an action Review already has
     * a key for; the menu exists because a key nobody has been told about is
     * not an affordance. Actions that need a bound session say so by being
     * disabled rather than by silently doing nothing.
     */
    private ContextMenu contextMenuFor(ReviewItem item) {
        MenuItem open = new MenuItem("Open  ↵");
        open.setOnAction(e -> {
            select(item.scope().id());
            onOpened.accept(item);
        });
        MenuItem session = new MenuItem("Open the bound session  o");
        session.setDisable(item.scope().sessionId().isEmpty());
        session.setOnAction(e -> {
            select(item.scope().id());
            onOpenSession.accept(item);
        });
        MenuItem review = new MenuItem("Run the review");
        review.setDisable(item.scope().sessionId().isEmpty());
        review.setOnAction(e -> {
            select(item.scope().id());
            onRunReview.accept(item);
        });
        return new ContextMenu(open, new SeparatorMenuItem(), session, review);
    }

    private void applySelectionStyles() {
        for (Map.Entry<String, Button> entry : buttonsByScopeId.entrySet()) {
            entry.getValue().pseudoClassStateChanged(
                    PseudoClass.getPseudoClass("selected"),
                    entry.getKey().equals(selectedScopeId));
        }
    }

    /** Scrolls {@code row} into the viewport without disturbing the horizontal position. */
    private void scrollIntoView(Button row) {
        double contentHeight = rows.getHeight();
        double viewportHeight = scroll.getViewportBounds().getHeight();
        if (contentHeight <= viewportHeight) {
            return;
        }
        double rowTop = row.getBoundsInParent().getMinY();
        scroll.setVvalue(Math.clamp(rowTop / (contentHeight - viewportHeight), 0, 1));
    }
}
