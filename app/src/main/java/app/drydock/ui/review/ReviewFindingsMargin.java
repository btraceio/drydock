package app.drydock.ui.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The findings margin (spec §4.5): comments live <em>beside</em> the code,
 * never inline, so the diff stays continuous.
 *
 * <p>Collapses to a 30px strip showing the count coloured by the worst
 * severity -- collapsing hides the descriptions, never the fact that
 * findings exist.</p>
 *
 * <p><strong>Reply drafts are held by the margin, keyed by {@code (scopeId,
 * id)}, not by the card node.</strong> A card is rebuilt whenever its
 * finding changes -- an agent appending a reply, say -- so a draft living
 * inside the node would go with it; and a node reused for a different
 * finding would carry the draft across to it. The scoped key is what keeps a
 * draft out of an identically-named finding in another worktree.</p>
 */
final class ReviewFindingsMargin extends VBox {

    static final double EXPANDED_WIDTH = 336;
    static final double NARROW_WIDTH = 286;
    static final double COLLAPSED_WIDTH = 30;
    private static final Duration COLLAPSE_ANIMATION = Duration.millis(160);

    /** What the margin needs from its host. All calls happen on the FX thread. */
    interface Host {
        /** Resolve / Reopen. */
        void setResolved(ReviewAnnotation finding, boolean resolved);

        /** Reply, and the ASK chips, which post a question as the human. */
        void postMessage(ReviewAnnotation finding, String body);

        /** {@code Apply patch} -- a human click, never an agent's doing. */
        void applyPatch(ReviewAnnotation finding);

        /** The human accepting a proposed severity change. */
        void overrideSeverity(ReviewAnnotation finding, Severity severity);

        /** Selecting a card focuses its line in the diff (two-way linkage, spec §4.4). */
        void focusLine(ReviewAnnotation finding);
    }

    /** {@code open} hides resolved findings; {@code all} widens to the whole review (F). */
    enum Filter { OPEN, ALL }

    private final Host host;

    private final Button collapseButton = new Button();
    private final Label headerCount = new Label();
    private final Button allFilter = new Button("all");
    private final Button openFilter = new Button("open");
    private final VBox cards = new VBox();
    private final ScrollPane scroll = new ScrollPane(cards);
    private final Label collapsedCount = new Label();
    private final VBox collapsedStrip = new VBox();
    private final HBox header = new HBox(6);

    /** View-owned card nodes, so a rebuild does not churn the scene graph. */
    private final Map<ReviewAnnotation.Key, Node> cardNodes = new HashMap<>();

    /**
     * Reply drafts by finding key, held by the margin rather than by the card
     * node. A card is rebuilt whenever its finding changes -- the agent
     * appending a reply, say -- and a draft living only inside the discarded
     * node would go with it. Keyed by {@code (scopeId, id)} so a draft can
     * never surface in a different scope's identically-named finding.
     */
    private final Map<ReviewAnnotation.Key, String> drafts = new HashMap<>();

    /** The pin number each finding was given, in render order (the {@code ◆n} the diff shows). */
    private final Map<ReviewAnnotation.Key, Integer> pinNumbers = new LinkedHashMap<>();

    private List<ReviewAnnotation> findings = List.of();
    private Filter filter = Filter.OPEN;
    private boolean wholeReview;
    private boolean collapsed;
    private boolean narrow;
    private ReviewAnnotation.Key focusedKey;
    private Runnable onToggleCollapse = () -> { };
    private Consumer<Filter> onFilterChanged = f -> { };

    ReviewFindingsMargin(Host host) {
        this.host = host;
        getStyleClass().add("review-findings-margin");
        setMinWidth(EXPANDED_WIDTH);
        setPrefWidth(EXPANDED_WIDTH);
        setMaxWidth(EXPANDED_WIDTH);

        // On the RIGHT edge, because this panel folds right: every collapse
        // control in the app sits on the side its panel moves toward, so the
        // glyph is the instruction rather than something to be learnt.
        collapseButton.setText("›");
        collapseButton.getStyleClass().add("panel-header-chevron-button");
        collapseButton.setTooltip(new Tooltip("Collapse or expand the findings margin (m)"));
        collapseButton.setOnAction(e -> onToggleCollapse.run());

        Label title = new Label("FINDINGS");
        title.getStyleClass().add("panel-header-title");
        headerCount.getStyleClass().add("panel-header-hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        allFilter.getStyleClass().add("review-filter-button");
        allFilter.setTooltip(new Tooltip("Findings from the whole review (F)"));
        allFilter.setOnAction(e -> setWholeReview(!wholeReview));
        openFilter.getStyleClass().add("review-filter-button");
        openFilter.setTooltip(new Tooltip("Hide resolved findings"));
        openFilter.setOnAction(e -> setFilter(filter == Filter.OPEN ? Filter.ALL : Filter.OPEN));

        header.getChildren().setAll(title, headerCount, spacer, allFilter, openFilter, collapseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("review-margin-header");

        cards.getStyleClass().add("review-findings-cards");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("review-findings-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        collapsedCount.getStyleClass().add("review-margin-collapsed-count");
        collapsedStrip.getChildren().setAll(collapsedCount);
        collapsedStrip.setAlignment(Pos.TOP_CENTER);
        collapsedStrip.getStyleClass().add("review-margin-collapsed");
        collapsedStrip.setVisible(false);
        collapsedStrip.setManaged(false);
        VBox.setVgrow(collapsedStrip, Priority.ALWAYS);

        getChildren().setAll(header, scroll, collapsedStrip);
        renderFilters();
    }

    void setOnToggleCollapse(Runnable handler) {
        this.onToggleCollapse = handler == null ? () -> { } : handler;
    }

    void setOnFilterChanged(Consumer<Filter> handler) {
        this.onFilterChanged = handler == null ? f -> { } : handler;
    }

    /**
     * Replaces the findings shown. Card nodes for findings that are still
     * present are reused, so an in-progress reply draft survives; nodes for
     * findings that are gone (or whose stored value changed) are dropped.
     */
    void setFindings(List<ReviewAnnotation> newFindings) {
        this.findings = List.copyOf(newFindings);
        rebuild();
    }

    /** Pin numbers by finding key, for the diff column's {@code ◆n} markers. */
    Map<ReviewAnnotation.Key, Integer> pinNumbers() {
        return Map.copyOf(pinNumbers);
    }

    /** Focuses a finding's card (clicking a line or its pin, spec §4.4). */
    void focus(ReviewAnnotation.Key key) {
        focusedKey = key;
        rebuild();
        Node card = cardNodes.get(key);
        if (card != null) {
            scrollIntoView(card);
            card.requestFocus();
        }
    }

    void setFilter(Filter newFilter) {
        filter = newFilter;
        renderFilters();
        rebuild();
        onFilterChanged.accept(newFilter);
    }

    boolean wholeReview() {
        return wholeReview;
    }

    /** {@code F}: findings from the whole review rather than the current intent. */
    void setWholeReview(boolean newWholeReview) {
        wholeReview = newWholeReview;
        renderFilters();
        onFilterChanged.accept(filter);
    }

    boolean collapsed() {
        return collapsed;
    }

    /** Collapses to the 30px strip; the count stays visible, only the descriptions go. */
    void setCollapsed(boolean newCollapsed) {
        if (collapsed == newCollapsed) {
            return;
        }
        collapsed = newCollapsed;
        header.setVisible(!newCollapsed);
        header.setManaged(!newCollapsed);
        scroll.setVisible(!newCollapsed);
        scroll.setManaged(!newCollapsed);
        collapsedStrip.setVisible(newCollapsed);
        collapsedStrip.setManaged(newCollapsed);
        animateTo(targetWidth());
        renderCollapsedStrip();
    }

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

    // ---- rendering ----------------------------------------------------------

    private List<ReviewAnnotation> visible() {
        return findings.stream()
                .filter(finding -> filter == Filter.ALL || !finding.resolved())
                .sorted(Comparator.comparingInt(finding -> finding.effectiveSeverity().ordinal()))
                .toList();
    }

    private void rebuild() {
        List<ReviewAnnotation> shown = visible();
        pinNumbers.clear();
        int pin = 1;
        for (ReviewAnnotation finding : shown) {
            pinNumbers.put(finding.key(), pin++);
        }

        // Drop cached nodes for findings that are gone or whose stored value
        // changed; keep the rest so their reply drafts survive.
        Map<ReviewAnnotation.Key, ReviewAnnotation> byKey = new HashMap<>();
        findings.forEach(finding -> byKey.put(finding.key(), finding));
        cardNodes.keySet().removeIf(key -> !byKey.containsKey(key));
        drafts.keySet().removeIf(key -> !byKey.containsKey(key));

        List<Node> nodes = new ArrayList<>();
        for (ReviewAnnotation finding : shown) {
            nodes.add(cardNodes.computeIfAbsent(finding.key(), key -> buildCard(finding)));
        }
        if (nodes.isEmpty()) {
            nodes.add(emptyState());
        }
        cards.getChildren().setAll(nodes);

        long open = findings.stream().filter(finding -> !finding.resolved()).count();
        headerCount.setText(findings.size() + (findings.size() == 1 ? " thread" : " threads")
                + (open == findings.size() ? "" : " · " + open + " open"));
        renderCollapsedStrip();
    }

    /** The stored value a cached card was built from may be stale; drop it so it rebuilds. */
    void invalidate(ReviewAnnotation.Key key) {
        if (key == null) {
            cardNodes.clear();
        } else {
            cardNodes.remove(key);
        }
    }

    private void renderFilters() {
        allFilter.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), wholeReview);
        openFilter.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"),
                filter == Filter.OPEN);
    }

    private void renderCollapsedStrip() {
        long open = findings.stream().filter(finding -> !finding.resolved()).count();
        collapsedCount.setText(open == 0 ? "" : String.valueOf(open));
        collapsedCount.getStyleClass().removeIf(styleClass -> styleClass.startsWith("severity-"));
        worstSeverity().ifPresent(severity -> collapsedCount.getStyleClass().add(severity.styleClass()));
        collapseButton.setText(collapsed ? "‹" : "›");
    }

    /** The worst severity among unresolved findings; the collapsed strip's colour. */
    private Optional<Severity> worstSeverity() {
        return findings.stream()
                .filter(finding -> !finding.resolved())
                .map(ReviewAnnotation::effectiveSeverity)
                .min(Comparator.comparingInt(Enum::ordinal));
    }

    /** Spec §6's three empty states, distinguished so the reader knows which one they are in. */
    private Region emptyState() {
        String text;
        if (findings.isEmpty() && wholeReview) {
            text = "Nothing flagged anywhere in this review.";
        } else if (findings.isEmpty()) {
            text = "Nothing flagged in this intent. Press F for the whole review.";
        } else {
            text = "No open findings left here.";
        }
        Label label = new Label(text);
        label.getStyleClass().add("review-findings-empty");
        label.setWrapText(true);
        VBox box = new VBox(label);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    // ---- one card -----------------------------------------------------------

    private Node buildCard(ReviewAnnotation finding) {
        VBox card = new VBox(8);
        card.getStyleClass().addAll("review-finding-card", finding.effectiveSeverity().styleClass());
        card.setFocusTraversable(true);
        card.setOnMouseClicked(e -> host.focusLine(finding));
        card.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                host.focusLine(finding);
            }
        });
        if (finding.key().equals(focusedKey)) {
            card.getStyleClass().add("focused");
        }
        if (finding.resolved()) {
            card.getStyleClass().add("resolved");
        }

        card.getChildren().add(cardHeader(finding));
        card.getChildren().add(cardBody(finding));
        card.getChildren().add(anchorLine(finding));
        finding.patch().ifPresent(patch -> card.getChildren().add(patchBlock(finding, patch)));
        finding.deviatesFrom().ifPresent(deviation ->
                card.getChildren().add(deviationBlock(deviation)));
        for (ReviewAnnotation.Evidence evidence : finding.evidence()) {
            card.getChildren().add(evidenceBlock(evidence));
        }
        threadBlock(finding).ifPresent(card.getChildren()::add);
        askChips(finding).ifPresent(card.getChildren()::add);
        card.getChildren().add(actions(finding));
        return card;
    }

    private Region cardHeader(ReviewAnnotation finding) {
        Label severity = new Label(finding.effectiveSeverity().wireName());
        severity.getStyleClass().addAll("review-severity-pill", finding.effectiveSeverity().styleClass());
        Label author = new Label(finding.author());
        author.getStyleClass().add("review-finding-author");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label pin = new Label("◆" + pinNumbers.getOrDefault(finding.key(), 0));
        pin.getStyleClass().addAll("review-finding-pin", finding.effectiveSeverity().styleClass());
        HBox row = new HBox(6, severity, author, spacer, pin);
        row.setAlignment(Pos.CENTER_LEFT);
        // The human's override is recorded, and so is what the reviewer said.
        finding.severityOverride().ifPresent(override -> {
            Label original = new Label("was " + finding.severity().wireName());
            original.getStyleClass().add("review-finding-meta");
            row.getChildren().add(row.getChildren().indexOf(spacer), original);
        });
        return row;
    }

    private Region cardBody(ReviewAnnotation finding) {
        // Findings render as TEXT, never as markup: the diff is untrusted
        // input and a finding body can quote it verbatim.
        Label body = new Label(finding.thread().isEmpty() ? finding.displayTitle()
                : finding.thread().get(0).text());
        body.getStyleClass().add("review-finding-body");
        body.setWrapText(true);
        return new VBox(body);
    }

    private Region anchorLine(ReviewAnnotation finding) {
        String anchor = finding.file() + " " + keyLabel(finding.startKey());
        Label label = new Label(anchor + " · " + finding.confidence().wireName() + " confidence");
        label.getStyleClass().add("review-finding-meta");
        return label;
    }

    private Region patchBlock(ReviewAnnotation finding, ReviewAnnotation.Patch patch) {
        Label summary = new Label(patch.summary());
        summary.getStyleClass().add("review-finding-meta");
        Label body = new Label(patch.unified());
        body.getStyleClass().add("review-patch-body");
        VBox box = new VBox(4, summary, body);
        box.getStyleClass().add("review-patch-block");
        return box;
    }

    private Region deviationBlock(ReviewAnnotation.DeviatesFrom deviation) {
        Label label = new Label("deviates from: " + deviation.prompt()
                + deviation.step().map(step -> " (step " + step + ")").orElse(""));
        label.getStyleClass().addAll("review-finding-meta", "deviation");
        label.setWrapText(true);
        return label;
    }

    private Region evidenceBlock(ReviewAnnotation.Evidence evidence) {
        Label label = new Label(evidence.label());
        label.getStyleClass().add("review-finding-meta");
        Label code = new Label(evidence.code());
        code.getStyleClass().add("review-patch-body");
        VBox box = new VBox(4, label, code);
        box.getStyleClass().add("review-patch-block");
        return box;
    }

    private Optional<Region> threadBlock(ReviewAnnotation finding) {
        if (finding.thread().size() <= 1) {
            return Optional.empty();
        }
        VBox thread = new VBox(6);
        thread.getStyleClass().add("review-thread");
        for (ReviewAnnotation.Message message : finding.thread().subList(1, finding.thread().size())) {
            Label author = new Label(message.author());
            author.getStyleClass().add("review-thread-author");
            Label text = new Label(message.text());
            text.getStyleClass().add("review-thread-text");
            text.setWrapText(true);
            VBox entry = new VBox(2, author, text);
            message.code().ifPresent(code -> {
                Label block = new Label(code.code());
                block.getStyleClass().add("review-patch-body");
                entry.getChildren().add(block);
            });
            thread.getChildren().add(entry);
        }
        return Optional.of(thread);
    }

    /**
     * The ASK chips: prebaked follow-ups the reviewer supplied, fired with one
     * click instead of typing. Posting one is a human message like any other.
     */
    private Optional<Region> askChips(ReviewAnnotation finding) {
        if (finding.asks().isEmpty()) {
            return Optional.empty();
        }
        FlowPane chips = new FlowPane(6, 6);
        chips.getStyleClass().add("review-ask-chips");
        Label caption = new Label("ASK");
        caption.getStyleClass().add("review-ask-caption");
        chips.getChildren().add(caption);
        for (ReviewAnnotation.Ask ask : finding.asks()) {
            Button chip = new Button(ask.label());
            chip.getStyleClass().add("review-ask-chip");
            chip.setTooltip(new Tooltip(ask.question()));
            chip.setOnAction(e -> host.postMessage(finding, ask.question()));
            chips.getChildren().add(chip);
        }
        return Optional.of(chips);
    }

    /**
     * The card's actions, plus its own reply box. The box belongs to this
     * node, which is cached per finding key -- that is what keeps one card's
     * draft out of another's.
     */
    private Region actions(ReviewAnnotation finding) {
        TextArea reply = new TextArea(drafts.getOrDefault(finding.key(), ""));
        reply.getStyleClass().add("review-reply-input");
        reply.setPromptText("Reply…");
        reply.setPrefRowCount(1);
        reply.setWrapText(true);
        // Written back on every keystroke so the draft outlives this node.
        reply.textProperty().addListener((obs, was, now) -> drafts.put(finding.key(), now));

        Button send = new Button("Reply");
        send.getStyleClass().add("review-card-action");
        send.setOnAction(e -> {
            String text = reply.getText() == null ? "" : reply.getText().strip();
            if (!text.isEmpty()) {
                host.postMessage(finding, text);
                drafts.remove(finding.key());
                reply.clear();
            }
        });

        Button resolve = new Button(finding.resolved() ? "Reopen" : "Resolve");
        resolve.getStyleClass().add("review-card-action");
        resolve.setOnAction(e -> host.setResolved(finding, !finding.resolved()));

        HBox buttons = new HBox(6);
        buttons.setAlignment(Pos.CENTER_LEFT);
        finding.patch().ifPresent(patch -> {
            // Accent, and only when a patch exists. Applying is a HUMAN click:
            // drydock never applies an agent's proposal on its own.
            Button apply = new Button("Apply patch");
            apply.getStyleClass().addAll("review-card-action", "primary");
            apply.setTooltip(new Tooltip(patch.summary()));
            apply.setOnAction(e -> host.applyPatch(finding));
            buttons.getChildren().add(apply);
        });
        if (finding.severityOverride().isEmpty() && finding.effectiveSeverity() == Severity.BLOCKING) {
            Button downgrade = new Button("Downgrade");
            downgrade.getStyleClass().add("review-card-action");
            downgrade.setTooltip(new Tooltip("Record that this is a question, not a blocker"));
            downgrade.setOnAction(e -> host.overrideSeverity(finding, Severity.QUESTION));
            buttons.getChildren().add(downgrade);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttons.getChildren().addAll(spacer, send, resolve);

        VBox box = new VBox(6, reply, buttons);
        box.getStyleClass().add("review-card-actions");
        return box;
    }

    private static String keyLabel(String key) {
        return "L" + (key.length() > 1 ? key.substring(1) : key);
    }

    private void scrollIntoView(Node card) {
        double contentHeight = cards.getHeight();
        double viewportHeight = scroll.getViewportBounds().getHeight();
        if (contentHeight <= viewportHeight) {
            return;
        }
        double top = card.getBoundsInParent().getMinY();
        scroll.setVvalue(Math.clamp(top / (contentHeight - viewportHeight), 0, 1));
    }
}
