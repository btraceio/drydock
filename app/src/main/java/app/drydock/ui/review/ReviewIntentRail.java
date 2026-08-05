package app.drydock.ui.review;

import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;
import app.drydock.ui.PanelHeader;

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
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The intent rail (spec §4.2): one card per intent, with its number, title,
 * kind tag, subtitle and a risk heat bar.
 *
 * <p>Settled intents dim and show their verdict; collapsed, the rail shows a
 * status dot instead of a clipped label, because a clipped label is worse
 * than no label -- it invites the reader to guess.</p>
 */
final class ReviewIntentRail extends VBox {

    static final double EXPANDED_WIDTH = 232;
    static final double NARROW_WIDTH = 196;
    static final double COLLAPSED_WIDTH = 40;
    private static final Duration COLLAPSE_ANIMATION = Duration.millis(160);

    /**
     * How much narrower a card's content is than the cards column: the
     * column's 6px side padding, the card's 8px side padding and its 1px
     * border, both sides. Matches {@code .review-intent-cards} and
     * {@code .review-intent-card} in {@code app.css}.
     */
    private static final double CARD_WIDTH_INSET = 2 * (6 + 8 + 1);

    // The handler is read at click time, so it can be installed after construction.
    private final PanelHeader header = PanelHeader.left(
            "INTENTS", "", "Collapse or expand the intents (i)",
            () -> this.onToggleCollapse.run());
    private final VBox cards = new VBox();
    private final ScrollPane scroll = new ScrollPane(cards);

    private final Map<String, Button> buttonsByIntentId = new LinkedHashMap<>();

    private List<ReviewIntent> intents = List.of();
    private java.util.function.Function<ReviewIntent, Optional<ReviewVerdict>> verdictLookup =
            intent -> Optional.empty();
    private Consumer<ReviewIntent> onSelected = intent -> { };
    private Runnable onToggleCollapse = () -> { };
    private String selectedId;
    private boolean collapsed;
    private boolean narrow;

    /** Non-zero while the narrow Browse page sizes this rail; see {@link #setSpanWidth}. */
    private double spanWidth;

    /** The collapse/expand animation currently running, so a span set can cancel it. */
    private Timeline widthAnimation;

    ReviewIntentRail() {
        getStyleClass().add("review-intent-rail");
        setMinWidth(EXPANDED_WIDTH);
        setPrefWidth(EXPANDED_WIDTH);
        setMaxWidth(EXPANDED_WIDTH);

        cards.getStyleClass().add("review-intent-cards");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("review-intent-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().setAll(header.node(), scroll);
    }

    void setOnSelected(Consumer<ReviewIntent> handler) {
        this.onSelected = handler == null ? intent -> { } : handler;
    }

    void setOnToggleCollapse(Runnable handler) {
        this.onToggleCollapse = handler == null ? () -> { } : handler;
    }

    void setVerdictLookup(java.util.function.Function<ReviewIntent, Optional<ReviewVerdict>> lookup) {
        this.verdictLookup = lookup == null ? intent -> Optional.empty() : lookup;
    }

    /**
     * Why a rail is showing no intents. Four situations, four sentences: the
     * rail is told which one it is in rather than inferring it from an
     * absence, because a failed diff and an in-flight one are both "no
     * entry" and mean opposite things to a reader.
     */
    enum Empty {
        NONE(""),
        DIFFING("Diffing…"),
        NOT_CHECKED_OUT("Not checked out — check out to group changes"),
        DIFF_FAILED("Could not diff — see the message beside this"),
        NO_CHANGES("No changes");

        private final String message;

        Empty(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private Empty emptyReason = Empty.NONE;

    /** Replaces the rail's contents and marks {@code selectedIntentId} as current. */
    void setIntents(List<ReviewIntent> newIntents, String selectedIntentId, Empty reason) {
        this.intents = List.copyOf(newIntents);
        this.selectedId = selectedIntentId;
        this.emptyReason = reason == null ? Empty.NONE : reason;
        rebuild();
    }

    boolean collapsed() {
        return collapsed;
    }

    void setCollapsed(boolean newCollapsed) {
        if (collapsed == newCollapsed) {
            return;
        }
        collapsed = newCollapsed;
        resize(true);
        rebuild();
    }

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
     * width. {@code 0} hands sizing back to {@link #targetWidth()}. Never
     * animated -- see {@code ReviewQueueRail.setSpanWidth}.
     */
    void setSpanWidth(double width) {
        if (spanWidth == width) {
            return;
        }
        spanWidth = width;
        resize(false);
    }

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

    private void rebuild() {
        header.showCollapsed(collapsed);
        header.setTitleVisible(!collapsed);
        header.setHintVisible(!collapsed);

        long counted = intents.stream().filter(ReviewIntent::countsTowardProgress).count();
        long settled = intents.stream()
                .filter(ReviewIntent::countsTowardProgress)
                .filter(intent -> verdictLookup.apply(intent).isPresent())
                .count();
        header.setHint(settled + "/" + counted + " · i");

        buttonsByIntentId.clear();
        List<Node> nodes = new ArrayList<>();
        for (ReviewIntent intent : intents) {
            Button card = buildCard(intent);
            buttonsByIntentId.put(intent.id(), card);
            nodes.add(card);
        }
        // A collapsed rail has no width for prose, so the message is only for
        // the expanded one; `nodes` is necessarily empty here, since the
        // message exists precisely when there are no cards to show.
        if (nodes.isEmpty() && emptyReason != Empty.NONE && !collapsed) {
            Label message = new Label(emptyReason.message());
            message.getStyleClass().add("review-intent-empty");
            message.setWrapText(true);
            nodes.add(message);
        }
        cards.getChildren().setAll(nodes);
        applySelection();
    }

    private Button buildCard(ReviewIntent intent) {
        Button card = new Button();
        card.getStyleClass().add("review-intent-card");
        card.setMaxWidth(Double.MAX_VALUE);
        card.setTooltip(new Tooltip(intent.number() + " · " + intent.title()
                + (intent.rationale().isBlank() ? "" : " — " + intent.rationale())));
        card.setOnAction(e -> onSelected.accept(intent));

        Label number = new Label(String.valueOf(intent.number()));
        number.getStyleClass().add("review-intent-number");

        Optional<ReviewVerdict> verdict = verdictLookup.apply(intent);
        boolean settled = verdict.isPresent() || intent.autoApprove();
        if (settled) {
            card.getStyleClass().add("settled");
        }

        Region heat = new Region();
        heat.getStyleClass().addAll("review-intent-heat", intent.risk().styleClass());

        if (collapsed) {
            VBox content = new VBox(4, number);
            content.setAlignment(Pos.CENTER);
            if (settled) {
                // A status dot, not a clipped label: a clipped label invites
                // the reader to guess what it said.
                Region dot = new Region();
                dot.getStyleClass().addAll("review-intent-dot",
                        decisionStyleClass(verdict, intent));
                content.getChildren().add(dot);
            }
            card.setGraphic(content);
            card.getStyleClass().add("collapsed");
            return card;
        }

        Label title = new Label(intent.title());
        title.getStyleClass().add("review-intent-title");
        // Wraps rather than clipping. Every fallback title used to be a full
        // path, and at this rail's width they all clipped to the same
        // "app/src/main/java/app/dry…" -- 45 cards that could not be told
        // apart. Titles are short now, but a long one must degrade into two
        // lines, not into an ellipsis that hides what makes it distinct.
        title.setWrapText(true);
        HBox.setHgrow(title, Priority.ALWAYS);
        Label tag = new Label(intent.kind().wireName());
        tag.getStyleClass().addAll("review-intent-tag", "kind-" + intent.kind().wireName());
        tag.setMinWidth(Region.USE_PREF_SIZE);
        HBox titleRow = new HBox(6, number, title, tag);
        titleRow.setAlignment(Pos.TOP_LEFT);

        VBox content = new VBox(4, titleRow) {
            @Override
            protected double computePrefHeight(double width) {
                // The Button sizes its graphic by asking for prefHeight(-1),
                // and a wrapping Label answers that with the height it needs
                // at its MINIMUM width -- one word per line, hundreds of
                // pixels. The card's width is known and pinned below, so
                // answer at that width instead of at no width at all.
                return super.computePrefHeight(width < 0 ? getPrefWidth() : width);
            }
        };
        if (!intent.rationale().isBlank()) {
            Label rationale = new Label(intent.rationale());
            rationale.getStyleClass().add("review-intent-rationale");
            rationale.setWrapText(true);
            content.getChildren().add(rationale);
        }
        intent.collapse().ifPresent(collapse -> {
            Label note = new Label(collapse.hunkCount() + " hunks across " + collapse.fileCount()
                    + " files — collapsed");
            note.getStyleClass().add("review-intent-collapsed-note");
            content.getChildren().add(note);
        });
        content.getChildren().add(heat);
        if (settled) {
            Label label = new Label(verdict.map(v -> v.decision().label())
                    .orElse(ReviewVerdict.Decision.AUTO_APPROVED.label()));
            label.getStyleClass().addAll("review-intent-settled", decisionStyleClass(verdict, intent));
            content.getChildren().add(label);
        }
        // Bound to the CARDS COLUMN, never to the card. A Button takes its
        // width from its graphic, so a graphic bound back to the button is a
        // cycle: on the pass that fixes the height the card is still 0 wide,
        // both wrapping labels above wrap at 0, and each reports the height of
        // a column of single characters -- an 1100px card, one per rail. The
        // column's width is handed down by the ScrollPane's viewport
        // (setFitToWidth) and cannot depend on what is inside it.
        content.prefWidthProperty().bind(cards.widthProperty().subtract(CARD_WIDTH_INSET));
        content.maxWidthProperty().bind(content.prefWidthProperty());
        card.setGraphic(content);
        return card;
    }

    private static String decisionStyleClass(Optional<ReviewVerdict> verdict, ReviewIntent intent) {
        ReviewVerdict.Decision decision = verdict.map(ReviewVerdict::decision)
                .orElse(intent.autoApprove() ? ReviewVerdict.Decision.AUTO_APPROVED
                        : ReviewVerdict.Decision.APPROVED);
        return "decision-" + decision.wireName();
    }

    /** Diagnostic-only: how many cards the rail drew, and how tall each one is. */
    String diagCards() {
        StringBuilder sb = new StringBuilder(intents.size() + " intents · "
                + cards.getChildren().size() + " cards h=[");
        for (Node node : cards.getChildren()) {
            sb.append((int) node.getBoundsInParent().getHeight()).append(' ');
        }
        return sb.append("] cardsH=").append((int) cards.getHeight())
                .append(" scrollH=").append((int) scroll.getHeight()).toString();
    }

    private void applySelection() {
        for (Map.Entry<String, Button> entry : buttonsByIntentId.entrySet()) {
            entry.getValue().pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"),
                    entry.getKey().equals(selectedId));
        }
    }
}
