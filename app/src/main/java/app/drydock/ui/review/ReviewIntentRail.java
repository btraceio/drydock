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

    /** Replaces the rail's contents and marks {@code selectedIntentId} as current. */
    void setIntents(List<ReviewIntent> newIntents, String selectedIntentId) {
        this.intents = List.copyOf(newIntents);
        this.selectedId = selectedIntentId;
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
        HBox.setHgrow(title, Priority.ALWAYS);
        Label tag = new Label(intent.kind().wireName());
        tag.getStyleClass().add("review-intent-tag");
        HBox titleRow = new HBox(6, number, title, tag);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(4, titleRow);
        if (!intent.rationale().isBlank()) {
            Label rationale = new Label(intent.rationale());
            rationale.getStyleClass().add("review-intent-rationale");
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
        content.prefWidthProperty().bind(card.widthProperty().subtract(24));
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

    private void applySelection() {
        for (Map.Entry<String, Button> entry : buttonsByIntentId.entrySet()) {
            entry.getValue().pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"),
                    entry.getKey().equals(selectedId));
        }
    }
}
