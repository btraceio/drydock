package app.drydock.ui.review;

import app.drydock.review.ReviewItem;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
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

    private final Button headerButton = new Button();
    private final Label headerChevron = new Label("‹");
    private final Label headerTitle = new Label("QUEUE");
    private final Label headerHint = new Label("j k · q");
    private final VBox rows = new VBox();
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Label footer = new Label();

    private final Map<String, Button> buttonsByScopeId = new LinkedHashMap<>();
    private final List<ReviewItem> items = new ArrayList<>();

    private FindingCount findingCount = item -> Optional.empty();
    private SessionDot sessionDot = item -> Optional.empty();
    private Consumer<ReviewItem> onSelected = item -> { };
    private Runnable onToggleCollapse = () -> { };

    private boolean collapsed;
    private boolean narrow;
    private String selectedScopeId;

    ReviewQueueRail() {
        getStyleClass().add("review-queue-rail");
        setMinWidth(EXPANDED_WIDTH);
        setPrefWidth(EXPANDED_WIDTH);
        setMaxWidth(EXPANDED_WIDTH);

        headerChevron.getStyleClass().add("review-rail-chevron");
        headerTitle.getStyleClass().add("review-rail-title");
        headerHint.getStyleClass().add("review-rail-hint");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(6, headerChevron, headerTitle, headerSpacer, headerHint);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerButton.setGraphic(headerRow);
        headerButton.getStyleClass().add("review-rail-header");
        headerButton.setMaxWidth(Double.MAX_VALUE);
        headerButton.setTooltip(new Tooltip("Collapse or expand the queue (q)"));
        headerButton.setOnAction(e -> onToggleCollapse.run());

        rows.getStyleClass().add("review-queue-items");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("review-queue-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        footer.getStyleClass().add("review-rail-footer");
        footer.setMaxWidth(Double.MAX_VALUE);

        getChildren().setAll(headerButton, scroll, footer);
    }

    void setOnSelected(Consumer<ReviewItem> handler) {
        this.onSelected = handler == null ? item -> { } : handler;
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

    /** {@code j} / {@code k}: moves the selection by {@code delta} through the flat item list. */
    void moveSelection(int delta) {
        int next = nextIndex(indexOfSelection(), items.size(), delta);
        if (next >= 0) {
            select(items.get(next).scope().id());
        }
    }

    private int indexOfSelection() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).scope().id().equals(selectedScopeId)) {
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

    boolean collapsed() {
        return collapsed;
    }

    /** Collapse/expand, animating width the way the Explorer's search rail does. */
    void setCollapsed(boolean newCollapsed) {
        if (collapsed == newCollapsed) {
            return;
        }
        collapsed = newCollapsed;
        animateTo(targetWidth());
        rebuild();
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
            animateTo(targetWidth());
        }
    }

    private double targetWidth() {
        if (collapsed) {
            return COLLAPSED_WIDTH;
        }
        return narrow ? NARROW_WIDTH : EXPANDED_WIDTH;
    }

    private void animateTo(double target) {
        new Timeline(new KeyFrame(COLLAPSE_ANIMATION,
                new KeyValue(minWidthProperty(), target),
                new KeyValue(prefWidthProperty(), target),
                new KeyValue(maxWidthProperty(), target))).play();
    }

    private void rebuild() {
        headerChevron.setText(collapsed ? "›" : "‹");
        headerTitle.setVisible(!collapsed);
        headerTitle.setManaged(!collapsed);
        headerHint.setVisible(!collapsed);
        headerHint.setManaged(!collapsed);
        footer.setVisible(!collapsed);
        footer.setManaged(!collapsed);

        buttonsByScopeId.clear();
        List<Node> children = new ArrayList<>();
        ReviewItem.Group lastGroup = null;
        for (ReviewItem item : items) {
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
        rows.getChildren().setAll(children);
        applySelectionStyles();
        footer.setText(items.size() + (items.size() == 1 ? " item" : " items"));
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
        row.setTooltip(new Tooltip(item.tooltip()));
        row.setOnAction(e -> select(item.scope().id()));

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
        content.setAlignment(Pos.CENTER_LEFT);
        row.setGraphic(content);
        // The graphic must track the button's own width or the ellipsized
        // labels inside it size to their (unbounded) preferred text width.
        content.prefWidthProperty().bind(row.widthProperty().subtract(20));
        content.maxWidthProperty().bind(content.prefWidthProperty());
        return row;
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
