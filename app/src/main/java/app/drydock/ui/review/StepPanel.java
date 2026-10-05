package app.drydock.ui.review;

import app.drydock.review.Provenance;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;
import app.drydock.review.UsageProvider;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.ImpactNote;
import app.drydock.review.tour.StepGate;
import app.drydock.review.tour.StepImpact;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.ui.UiFormats;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableStringValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The right-hand panel in tour mode: the current step's narrative, anchors
 * and active check, the agent's findings on the step awaiting triage, and
 * the blocker banner that stands in front of the tour, and the step's
 * impact. View-only: every action goes to {@link Host}.
 */
final class StepPanel extends VBox {

    static final double EXPANDED_WIDTH = ReviewFindingsMargin.EXPANDED_WIDTH;
    static final double NARROW_WIDTH = ReviewFindingsMargin.NARROW_WIDTH;
    static final double COLLAPSED_WIDTH = ReviewFindingsMargin.COLLAPSED_WIDTH;
    /** The narrowest the reader can drag it: the narrow-mode width, below which the panel's controls crowd. */
    static final double MIN_WIDTH = NARROW_WIDTH;
    /** The widest, whatever the window: past this the lines of prose are longer than they are easy to read. */
    static final double MAX_WIDTH = 900;
    /** Stands where the narrative would be while the step's PREDICT is still open. */
    static final String WITHHELD_NARRATIVE =
            "Read the code first. The agent's explanation, and its note on each range, unlock when you answer.";
    /** Shown on a step whose code moved under it once a refresh was actually sent to the agent. */
    static final String STALE_NOTICE = "This step's code changed; the agent is re-writing it.";
    /** Shown on a step whose code moved under it while no refresh has been sent for this diff. */
    static final String STALE_WAITING = "This step's code changed; waiting for the agent.";

    interface Host {
        void answerChoice(String checkId, int choiceIndex);
        void submitRisk(String checkId, String answer);
        void override(String reason);
        void goToAnchor(int anchorIndex);
        void retryRisk(String checkId);
        void triage(ReviewAnnotation finding, Triage triage, Optional<String> reason);
        void revealFinding(ReviewAnnotation finding);
        void sendBack(List<ReviewAnnotation> confirmedBlockers);
        void reviewAnyway();
        void backToStep();
        /** Peeks at {@code file}:{@code line} in place, over the diff column. */
        void openLocation(String file, int line);
        void selectStep(String stepId);
        /** "Ask the agent to refresh": a human's request, sent whatever the automatic gating says. */
        void requestRefresh();
        /** Not sure's reply: appended to {@code finding}'s thread, the way the margin's Reply is. */
        void postMessage(ReviewAnnotation finding, String body);
    }

    /**
     * What the impact section shows for the current step (spec §6).
     *
     * @param claimed             the agent's impact notes, pinned on top
     * @param measured            the step's measured impact; while {@code
     *                            pending} its callers and signature flags are
     *                            not yet measured and are not shown
     * @param callees             resolutions of {@code measured}'s callees so
     *                            far: absent means still resolving, an empty
     *                            {@code Optional} means nothing was found
     * @param pending             whether the out-of-diff caller scan is still
     *                            running
     * @param calleesUnavailable  why no callee can be resolved at all (no
     *                            checkout to search), instead of "resolving…"
     *                            forever
     * @param declaresSymbols     whether the step's rows declare anything a
     *                            caller could use; false omits the callers
     *                            and the signature flags, there being nobody
     *                            to find
     */
    record ImpactView(List<ImpactNote> claimed, StepImpact measured,
                      Map<String, Optional<UsageProvider.Usage>> callees, boolean pending,
                      Optional<String> calleesUnavailable, boolean declaresSymbols) {
        ImpactView {
            claimed = List.copyOf(claimed);
            callees = Map.copyOf(callees);
        }
    }

    private final Host host;
    private final VBox content = new VBox(10);
    private final VBox extraSections = new VBox(10);
    private final VBox triageSection = new VBox(8);
    private final VBox impactSection = new VBox(6);
    private final ScrollPane scroll;
    private final Button backPill = new Button();
    private final List<Button> choiceButtons = new ArrayList<>();
    private Optional<TextArea> riskBox = Optional.empty();
    private Optional<TextField> overrideReason = Optional.empty();
    private Optional<Button> refreshButton = Optional.empty();
    private final Button expandButton = new Button("‹");
    private final VBox collapsedStrip = new VBox(expandButton);
    private Runnable onExpand = () -> { };
    private boolean collapsed;
    private boolean narrow;
    /** The width when neither collapsed nor narrow: the default, or what the reader dragged it to. */
    private double expandedWidth = EXPANDED_WIDTH;

    StepPanel(Host host) {
        this.host = host;
        getStyleClass().add("step-panel");
        triageSection.getStyleClass().add("step-triage");
        impactSection.getStyleClass().add("step-impact");
        content.getStyleClass().add("step-panel-content");
        extraSections.getStyleClass().add("step-panel-extra");
        VBox body = new VBox(14, content, extraSections);
        body.getStyleClass().add("step-panel-body");
        scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("step-panel-scroll");
        // The column's full height: the panel's content scrolls inside it,
        // rather than the scroll pane stopping at its preferred height and
        // clipping whatever impact entry falls below.
        VBox.setVgrow(scroll, Priority.ALWAYS);
        // Collapsed, the panel keeps a dark strip with a real Button to
        // bring it back (m does the same).
        expandButton.getStyleClass().addAll("panel-header-chevron-button", "step-panel-expand");
        expandButton.setTooltip(new Tooltip("Expand the step panel (m)"));
        expandButton.setOnAction(event -> onExpand.run());
        collapsedStrip.setAlignment(Pos.TOP_CENTER);
        collapsedStrip.getStyleClass().add("step-panel-collapsed");
        collapsedStrip.setVisible(false);
        collapsedStrip.setManaged(false);
        VBox.setVgrow(collapsedStrip, Priority.ALWAYS);
        // The "↩ back to step N" pill sits above the scroll, outside the
        // content show() rebuilds, so a redraw of the step does not drop it;
        // the view decides when it shows (after a navigation away from the
        // step) and when it goes (b, a step change, an anchor chip).
        backPill.getStyleClass().add("step-back-pill");
        backPill.setMaxWidth(Double.MAX_VALUE);
        backPill.setOnAction(event -> host.backToStep());
        backPill.setVisible(false);
        backPill.setManaged(false);
        getChildren().addAll(backPill, scroll, collapsedStrip);
        applyWidth();
    }

    void setOnExpand(Runnable action) {
        onExpand = action == null ? () -> { } : action;
    }

    /** Shows "↩ back to step {@code number}"; see the constructor for who hides it. */
    void showBackPill(int number) {
        if (collapsed) {
            // No room for it on the strip; expanding shows the step itself.
            return;
        }
        backPill.setText("↩ back to step " + number);
        backPill.setVisible(true);
        backPill.setManaged(true);
    }

    void hideBackPill() {
        backPill.setVisible(false);
        backPill.setManaged(false);
    }

    boolean backPillShown() {
        return backPill.isVisible();
    }

    void show(StepView view) {
        choiceButtons.clear();
        riskBox = Optional.empty();
        overrideReason = Optional.empty();
        refreshButton = Optional.empty();
        content.getChildren().clear();
        Label header = new Label("Step " + view.number() + " of " + view.total() + " · " + view.step().title());
        header.getStyleClass().add("step-panel-header");
        if (TourMarks.predictPending(view.step(), view.progress())) {
            // Predict first: the narrative states what the added lines do,
            // which is exactly what the open PREDICT asks, so it stays back
            // until the reader has committed to an answer. What replaces it
            // comes BEFORE the question -- "read the code first", with the
            // links to the ranges -- because a question about code the reader
            // has not been pointed at is a question about nothing.
            Label withheld = new Label(WITHHELD_NARRATIVE);
            withheld.setWrapText(true);
            withheld.getStyleClass().addAll("step-panel-narrative", "step-panel-withheld");
            content.getChildren().addAll(header, withheld, anchorChips(view));
            if (view.progress().stale()) {
                content.getChildren().add(staleNotice(view.refreshDispatched()));
            }
            content.getChildren().add(checkSection(view));
            return;
        }
        Label narrative = new Label(view.step().narrative());
        narrative.setWrapText(true);
        narrative.getStyleClass().add("step-panel-narrative");
        content.getChildren().addAll(header, narrative, anchorChips(view));
        if (view.progress().stale()) {
            content.getChildren().add(staleNotice(view.refreshDispatched()));
        }
        content.getChildren().add(checkSection(view));
    }

    /**
     * Says the step's code changed, and only claims the agent is re-writing
     * it when a refresh was actually sent for this diff -- an inline harness
     * or a busy agent is never asked automatically, and "re-writing" would
     * then be a promise nobody is keeping. Until then a real Button asks.
     */
    private VBox staleNotice(boolean refreshDispatched) {
        Label stale = new Label(refreshDispatched ? STALE_NOTICE : STALE_WAITING);
        stale.setWrapText(true);
        stale.getStyleClass().add("step-panel-stale");
        VBox notice = new VBox(6, stale);
        notice.getStyleClass().add("step-stale-notice");
        if (!refreshDispatched) {
            Button ask = new Button("Ask the agent to refresh");
            ask.getStyleClass().add("step-refresh");
            ask.setOnAction(event -> host.requestRefresh());
            refreshButton = Optional.of(ask);
            notice.getChildren().add(ask);
        }
        return notice;
    }

    void showMessage(String message) {
        choiceButtons.clear();
        riskBox = Optional.empty();
        overrideReason = Optional.empty();
        refreshButton = Optional.empty();
        content.getChildren().setAll(new Label(message));
        extraSections.getChildren().clear();
    }

    /** A one-line notice above the content, replacing the previous one; the next {@link #show} clears it. */
    void showTransient(String message) {
        content.getChildren().removeIf(node -> node.getStyleClass().contains("step-panel-transient"));
        Label notice = new Label(message);
        notice.getStyleClass().add("step-panel-transient");
        notice.setWrapText(true);
        content.getChildren().add(0, notice);
    }

    /** Presses choice {@code oneBased} of the active choice check; false when there is none. */
    boolean answerByKey(int oneBased) {
        int index = oneBased - 1;
        if (index < 0 || index >= choiceButtons.size()) {
            return false;
        }
        choiceButtons.get(index).fire();
        return true;
    }

    /**
     * The agent's findings on the step that await triage, in the triage
     * section; empty clears it. A finding revealed by its check says so.
     */
    void showTriage(List<ReviewAnnotation> proposals) {
        triageSection.getChildren().clear();
        if (!extraSections.getChildren().contains(triageSection)) {
            extraSections.getChildren().addFirst(triageSection);
        }
        if (proposals.isEmpty()) {
            return;
        }
        Label heading = new Label(proposals.size() == 1
                ? "1 finding to triage"
                : proposals.size() + " findings to triage");
        heading.getStyleClass().add("step-triage-header");
        triageSection.getChildren().add(heading);
        for (ReviewAnnotation finding : proposals) {
            triageSection.getChildren().add(findingRow(finding, true));
        }
    }

    /**
     * The step's impact, after the triage section: agent notes, signature
     * flags, callers outside the change by file, edges to other steps, and
     * callees outside the change. Every entry is a {@link Button}: a
     * location peeks in place through {@link Host#openLocation}, an edge
     * selects its step.
     */
    void showImpact(ImpactView view) {
        impactSection.getChildren().clear();
        if (!extraSections.getChildren().contains(impactSection)) {
            int afterTriage = extraSections.getChildren().indexOf(triageSection) + 1;
            extraSections.getChildren().add(afterTriage, impactSection);
        }
        StepImpact measured = view.measured();
        if (!view.claimed().isEmpty()) {
            impactSection.getChildren().add(heading("Agent notes", Provenance.CLAIMED));
            for (ImpactNote note : view.claimed()) {
                impactSection.getChildren().add(locationEntry(note.file() + ":" + note.line() + " — " + note.text(),
                        note.file(), note.line(), "step-impact-note"));
            }
        }
        if (!view.declaresSymbols()) {
            // Nothing declared, so no callers to find and no signature to flag.
        } else if (view.pending()) {
            impactSection.getChildren().add(impactLabel("Finding callers…", "step-impact-pending"));
        } else if (measured.unavailableReason().isPresent()) {
            impactSection.getChildren().add(impactLabel("callers unavailable: " + measured.unavailableReason().get(),
                    "step-impact-unavailable"));
        } else {
            showSignatureFlags(measured);
            showCallers(measured);
        }
        showInChange(measured);
        showCallees(view);
    }

    private void showSignatureFlags(StepImpact measured) {
        if (measured.signatureFlags().isEmpty()) {
            return;
        }
        impactSection.getChildren().add(heading("Signature changed", Provenance.MEASURED));
        for (StepImpact.SignatureFlag flag : measured.signatureFlags()) {
            int count = flag.uneditedCallSites();
            impactSection.getChildren().add(impactLabel(flag.symbol() + " in " + flag.file(), "step-impact-flag-name"));
            impactSection.getChildren().add(impactLabel("declaration changed · " + count
                    + (count == 1 ? " call site was not edited" : " call sites were not edited"),
                    "step-impact-flag"));
        }
    }

    private void showCallers(StepImpact measured) {
        impactSection.getChildren().add(heading("Called from outside the change", null));
        impactSection.getChildren().add(impactLabel("occurrences, not resolved references", "step-impact-tag"));
        if (measured.calledFromOutside().isEmpty()) {
            impactSection.getChildren().add(impactLabel("No callers outside the change", "step-impact-empty"));
            return;
        }
        Map<String, List<StepImpact.Caller>> byFile = new LinkedHashMap<>();
        for (StepImpact.Caller caller : measured.calledFromOutside()) {
            byFile.computeIfAbsent(caller.file(), file -> new ArrayList<>()).add(caller);
        }
        byFile.forEach((file, callers) -> {
            impactSection.getChildren().add(impactLabel(file, "step-impact-file"));
            for (StepImpact.Caller caller : callers) {
                String text = ":" + caller.line() + "  " + caller.text().strip()
                        + (caller.inChangedFile() ? " · in a changed file" : "");
                impactSection.getChildren().add(locationEntry(text, caller.file(), caller.line(),
                        "step-impact-caller"));
            }
        });
    }

    private void showInChange(StepImpact measured) {
        if (measured.inChange().isEmpty()) {
            return;
        }
        impactSection.getChildren().add(heading("In this change", null));
        for (StepImpact.InChange edge : measured.inChange()) {
            String arrow = edge.direction() == StepImpact.Direction.CALLS ? "→ " : "← ";
            Button entry = entry(arrow + edge.symbol() + " · step " + edge.otherStepNumber(), "step-impact-edge");
            entry.setOnAction(event -> host.selectStep(edge.otherStepId()));
            impactSection.getChildren().add(entry);
        }
    }

    private void showCallees(ImpactView view) {
        List<String> names = view.measured().calleesToResolve();
        if (names.isEmpty()) {
            return;
        }
        impactSection.getChildren().add(heading("Calls outside the change", Provenance.MEASURED));
        for (String name : names) {
            Optional<UsageProvider.Usage> resolution = view.callees().get(name);
            if (resolution != null && resolution.isPresent()) {
                UsageProvider.Usage usage = resolution.get();
                String text = name + " → " + usage.file() + ":" + usage.line()
                        + (usage.resolvedDeclaration() ? "" : " (first occurrence)");
                Button entry = locationEntry(text, usage.file(), usage.line(), "step-impact-callee");
                if (!usage.provenance().styleClass().isEmpty()) {
                    entry.getStyleClass().add(usage.provenance().styleClass());
                }
                impactSection.getChildren().add(entry);
                continue;
            }
            String state = resolution != null ? "no declaration found"
                    : view.calleesUnavailable().map(reason -> "not resolved: " + reason).orElse("resolving…");
            // A Button even without a target, so the list keeps one focus
            // order; there is nowhere to go, so it does nothing.
            Button entry = entry(name + " → " + state, "step-impact-callee");
            entry.setDisable(true);
            impactSection.getChildren().add(entry);
        }
    }

    /** A section heading, with the provenance tag beside it when it has one. */
    private static Node heading(String text, Provenance provenance) {
        Label title = impactLabel(text, "step-impact-header");
        if (provenance == null) {
            return title;
        }
        Label tag = impactLabel(provenance.label(), "step-impact-tag");
        if (!provenance.styleClass().isEmpty()) {
            tag.getStyleClass().add(provenance.styleClass());
        }
        HBox row = new HBox(6, title, tag);
        row.setAlignment(Pos.BASELINE_LEFT);
        return row;
    }

    private static Label impactLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Button entry(String text, String styleClass) {
        Button button = UiFormats.literal(new Button(text));
        button.getStyleClass().addAll("step-impact-entry", styleClass);
        button.setWrapText(true);
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private Button locationEntry(String text, String file, int line, String styleClass) {
        Button button = entry(text, styleClass);
        button.setOnAction(event -> host.openLocation(file, line));
        return button;
    }

    /**
     * The banner that stands instead of the step while the agent proposes
     * blocking problems (spec §4): one row per blocker, then send the
     * confirmed ones back or review anyway.
     */
    void showBanner(List<ReviewAnnotation> blockers, boolean shelved) {
        choiceButtons.clear();
        riskBox = Optional.empty();
        overrideReason = Optional.empty();
        refreshButton = Optional.empty();
        triageSection.getChildren().clear();
        extraSections.getChildren().remove(impactSection);
        VBox banner = new VBox(8);
        banner.getStyleClass().add("step-blocker-banner");
        int count = blockers.size();
        Label title = new Label("The agent proposes " + count
                + (count == 1 ? " blocking problem." : " blocking problems."));
        title.setWrapText(true);
        title.getStyleClass().add("step-panel-header");
        banner.getChildren().add(title);
        for (ReviewAnnotation blocker : blockers) {
            banner.getChildren().add(findingRow(blocker, false));
        }
        List<ReviewAnnotation> confirmed = blockers.stream()
                .filter(blocker -> blocker.triage() == Triage.CONFIRMED)
                .toList();
        if (shelved) {
            // Already sent: a second send would push the same prompt again.
            Label sent = new Label("Sent to the author — waiting for their changes");
            sent.setWrapText(true);
            banner.getChildren().add(sent);
        } else {
            Button send = new Button("Send back to the author");
            send.getStyleClass().add("primary");
            send.setDisable(confirmed.isEmpty());
            send.setOnAction(event -> host.sendBack(confirmed));
            banner.getChildren().add(send);
        }
        Button anyway = new Button("Review anyway");
        anyway.setOnAction(event -> host.reviewAnyway());
        banner.getChildren().add(anyway);
        content.getChildren().setAll(banner);
    }

    /** {@code a} while the banner is up: focus its first triage action, else its first enabled button. */
    void focusBanner() {
        List<Button> buttons = content.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> !button.isDisabled() && button.isVisible())
                .toList();
        buttons.stream()
                .filter(button -> button.getStyleClass().contains("step-finding-confirm"))
                .findFirst()
                .or(() -> buttons.stream()
                        .filter(button -> !button.getStyleClass().contains("step-finding-reveal"))
                        .findFirst())
                .ifPresent(Button::requestFocus);
    }

    void focusUnmet(StepGate.Unmet unmet) {
        if (unmet.kind() == StepGate.Kind.STALE) {
            // Says why, then lands on the way out: ask for the refresh, or
            // approve without passing.
            showTransient(unmet.message());
            refreshButton.<Node>map(button -> button)
                    .or(() -> overrideReason.map(field -> field))
                    .ifPresent(Node::requestFocus);
            return;
        }
        if (unmet.kind() == StepGate.Kind.BLOCKER) {
            showTransient(unmet.message());
            overrideReason.ifPresent(TextField::requestFocus);
            return;
        }
        if (unmet.kind() == StepGate.Kind.TRIAGE && !triageSection.getChildren().isEmpty()) {
            scrollTo(triageSection);
            triageSection.lookupAll(".button").stream().findFirst().ifPresent(Node::requestFocus);
            return;
        }
        if (!choiceButtons.isEmpty()) {
            choiceButtons.getFirst().requestFocus();
        } else {
            riskBox.ifPresentOrElse(TextArea::requestFocus, () -> overrideReason.ifPresent(TextField::requestFocus));
        }
    }

    VBox extraSections() {
        return extraSections;
    }

    boolean collapsed() {
        return collapsed;
    }

    void setCollapsed(boolean value) {
        collapsed = value;
        scroll.setVisible(!value);
        scroll.setManaged(!value);
        collapsedStrip.setVisible(value);
        collapsedStrip.setManaged(value);
        if (value) {
            backPill.setVisible(false);
            backPill.setManaged(false);
        }
        applyWidth();
    }

    void setNarrow(boolean value) {
        narrow = value;
        applyWidth();
    }

    /**
     * The width this panel takes while expanded and not narrow, clamped to
     * {@link #MIN_WIDTH}..{@link #MAX_WIDTH}. Narrow mode still caps it at
     * {@link #NARROW_WIDTH}, matching {@link RailLayout}, which charges the
     * same width.
     */
    void setExpandedWidth(double width) {
        expandedWidth = clampWidth(width);
        applyWidth();
    }

    static double clampWidth(double width) {
        return Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
    }

    private void applyWidth() {
        double width = collapsed ? COLLAPSED_WIDTH : narrow ? Math.min(NARROW_WIDTH, expandedWidth) : expandedWidth;
        setMinWidth(width);
        setPrefWidth(width);
        setMaxWidth(width);
    }

    private void scrollTo(Node node) {
        Node scrolled = scroll.getContent();
        double contentHeight = scrolled.getBoundsInLocal().getHeight();
        double viewportHeight = scroll.getViewportBounds().getHeight();
        if (contentHeight <= viewportHeight) {
            return;
        }
        double top = scrolled.sceneToLocal(node.localToScene(0, 0)).getY();
        scroll.setVvalue(Math.clamp(top / (contentHeight - viewportHeight), 0, 1));
    }

    /** One finding: what and where, a button to its line, and its triage while it is proposed. */
    private VBox findingRow(ReviewAnnotation finding, boolean offerNotSure) {
        VBox row = new VBox(6);
        row.getStyleClass().add("step-finding");
        if (finding.withheldBy().isPresent()) {
            row.getChildren().add(new Label("The agent found this here:"));
        }
        Label title = new Label(finding.displayTitle());
        title.setWrapText(true);
        title.getStyleClass().add("step-finding-title");
        Label meta = new Label(finding.effectiveSeverity().wireName() + " · " + finding.file() + ":"
                + startLineOf(finding.startKey())
                + (finding.triage() == Triage.CONFIRMED ? " · confirmed" : ""));
        meta.getStyleClass().add("step-finding-meta");
        Button reveal = new Button("Show line");
        reveal.getStyleClass().add("step-finding-reveal");
        reveal.setOnAction(event -> host.revealFinding(finding));
        row.getChildren().addAll(title, meta, reveal);
        if (finding.triage() == Triage.PROPOSED) {
            row.getChildren().add(triageButtons(finding, offerNotSure));
        }
        return row;
    }

    private VBox triageButtons(ReviewAnnotation finding, boolean offerNotSure) {
        Button confirm = new Button("Confirm");
        confirm.getStyleClass().addAll("primary", "step-finding-confirm");
        confirm.setOnAction(event -> host.triage(finding, Triage.CONFIRMED, Optional.empty()));
        Button dismissStart = new Button("Dismiss…");
        HBox buttons = new HBox(6, confirm, dismissStart);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox replyRow = new VBox(6);
        if (offerNotSure) {
            // Leaves it proposed (spec §4): the question goes to the finding's
            // thread, where the agent answers it.
            Button notSure = new Button("Not sure");
            buttons.getChildren().add(notSure);
            TextField reply = new TextField();
            reply.setPromptText("Ask the agent about this finding");
            reply.getStyleClass().add("step-finding-reply");
            Button send = new Button("Send");
            send.getStyleClass().add("step-finding-send");
            send.disableProperty().bind(blank(reply.textProperty()));
            send.setOnAction(event -> {
                String body = reply.getText().strip();
                reply.clear();
                host.postMessage(finding, body);
                showTransient("Asked in the finding's thread; it stays proposed until you decide.");
            });
            HBox field = new HBox(6, reply, send);
            field.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(reply, Priority.ALWAYS);
            replyRow.getChildren().add(field);
            replyRow.setVisible(false);
            replyRow.setManaged(false);
            notSure.setOnAction(event -> {
                replyRow.setVisible(true);
                replyRow.setManaged(true);
                reply.requestFocus();
            });
        }
        TextField reason = new TextField();
        reason.setPromptText("Why is it wrong?");
        reason.getStyleClass().add("step-dismiss-reason");
        Button dismiss = new Button("Dismiss");
        dismiss.disableProperty().bind(blank(reason.textProperty()));
        dismiss.setOnAction(event -> host.triage(finding, Triage.DISMISSED, Optional.of(reason.getText().strip())));
        HBox dismissRow = new HBox(6, reason, dismiss);
        dismissRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(reason, Priority.ALWAYS);
        dismissRow.setVisible(false);
        dismissRow.setManaged(false);
        dismissStart.setOnAction(event -> {
            dismissRow.setVisible(true);
            dismissRow.setManaged(true);
            reason.requestFocus();
        });
        return new VBox(6, buttons, dismissRow, replyRow);
    }

    private static BooleanBinding blank(ObservableStringValue text) {
        return Bindings.createBooleanBinding(() -> text.get().isBlank(), text);
    }

    private FlowPane anchorChips(StepView view) {
        FlowPane chips = new FlowPane(6, 6);
        List<TourAnchor> anchors = view.step().anchors();
        for (int i = 0; i < anchors.size(); i++) {
            int index = i;
            TourAnchor anchor = anchors.get(i);
            Button chip = UiFormats.literal(new Button(anchor.file() + ":" + startLineOf(anchor)));
            chip.getStyleClass().add("step-anchor-chip");
            chip.setOnAction(event -> host.goToAnchor(index));
            chips.getChildren().add(chip);
        }
        return chips;
    }

    private static String startLineOf(TourAnchor anchor) {
        return startLineOf(anchor.startKey());
    }

    private static String startLineOf(String key) {
        return key.substring(1);
    }

    /**
     * The step's check, or its decision. "Approve without passing" is
     * offered wherever the gate cannot be met by answering (spec §4, §8): a
     * stale step, a confirmed blocker on the step's lines, an agent that did
     * not grade a RISK answer, and a check out of alternates.
     */
    private VBox checkSection(StepView view) {
        VBox box = new VBox(8);
        box.getStyleClass().add("step-check");
        StepProgress progress = view.progress();
        Optional<StepGate.Kind> gate = view.unmet().map(StepGate.Unmet::kind);
        boolean overridable = gate.filter(kind -> kind == StepGate.Kind.STALE || kind == StepGate.Kind.BLOCKER)
                .isPresent();
        if (progress.decision() != StepProgress.Decision.NONE) {
            Label decision = new Label(switch (progress.decision()) {
                case PASSED -> "Approved.";
                case CHANGES -> "Changes requested.";
                case OVERRIDDEN -> "Approved without passing: " + progress.overrideReason().orElse("");
                case NONE -> "";
            });
            decision.setWrapText(true);
            decision.getStyleClass().add("step-check-decision");
            box.getChildren().add(decision);
            if (overridable) {
                // A decision on code that has since changed no longer counts.
                addOverride(box);
            }
            return box;
        }
        for (TourCheck check : view.step().checks()) {
            CheckProgress p = progress.check(check.id());
            if (p.settled()) {
                continue;
            }
            renderActiveCheck(box, check, p);
            if (overridable || p.status() == CheckProgress.Status.AGENT_UNAVAILABLE
                    || p.status() == CheckProgress.Status.EXHAUSTED) {
                addOverride(box);
            }
            return box;
        }
        String settled = switch (gate.orElse(null)) {
            case null -> "All checks passed — press a to approve this step.";
            case BLOCKER -> view.unmet().get().message();
            case TRIAGE -> "All checks passed — triage the agent's findings below, then press a.";
            case STALE, CHECK -> "All checks passed.";
        };
        Label done = new Label(settled);
        done.setWrapText(true);
        done.getStyleClass().add(gate.filter(kind -> kind == StepGate.Kind.BLOCKER).isPresent()
                ? "step-check-blocker" : "step-check-done");
        box.getChildren().add(done);
        if (overridable) {
            addOverride(box);
        }
        return box;
    }

    private void renderActiveCheck(VBox box, TourCheck check, CheckProgress p) {
        TourCheck offered = check.version(p.attempt());
        p.lastExplanation().ifPresent(text -> {
            Label explanation = new Label("Not quite: " + text);
            explanation.setWrapText(true);
            explanation.getStyleClass().add("step-check-explanation");
            box.getChildren().add(explanation);
        });
        switch (p.status()) {
            case AWAITING_AGENT -> box.getChildren().add(statusLabel("Checking with the agent…"));
            case AGENT_UNAVAILABLE -> {
                Button retry = new Button("Retry");
                retry.getStyleClass().add("step-retry");
                retry.setOnAction(event -> host.retryRisk(check.id()));
                box.getChildren().addAll(statusLabel("The agent did not answer."), retry);
            }
            case EXHAUSTED -> box.getChildren().add(statusLabel("Out of alternates."));
            default -> renderPrompt(box, check, offered);
        }
    }

    private static Label statusLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("step-check-status");
        return label;
    }

    /** The reason field and "Approve without passing", which stays disabled until a reason is given. */
    private void addOverride(VBox box) {
        TextField reason = new TextField();
        reason.setPromptText("Why approve without passing?");
        reason.getStyleClass().add("step-override-reason");
        Button override = new Button("Approve without passing");
        override.getStyleClass().add("step-override");
        override.disableProperty().bind(blank(reason.textProperty()));
        override.setOnAction(event -> host.override(reason.getText().strip()));
        overrideReason = Optional.of(reason);
        box.getChildren().addAll(reason, override);
    }

    private void renderPrompt(VBox box, TourCheck check, TourCheck offered) {
        Label prompt = new Label(offered.prompt());
        prompt.setWrapText(true);
        prompt.getStyleClass().add("step-check-prompt");
        box.getChildren().add(prompt);
        if (offered.kind() == TourCheck.Kind.RISK) {
            TextArea answer = new TextArea();
            answer.setWrapText(true);
            answer.setPrefRowCount(3);
            Button send = new Button("Send answer");
            send.disableProperty().bind(blank(answer.textProperty()));
            send.setOnAction(event -> host.submitRisk(check.id(), answer.getText().strip()));
            answer.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
                if (event.isShortcutDown() && event.getCode() == KeyCode.ENTER && !answer.getText().isBlank()) {
                    send.fire();
                    event.consume();
                }
            });
            riskBox = Optional.of(answer);
            box.getChildren().addAll(answer, send);
            return;
        }
        for (int i = 0; i < offered.choices().size(); i++) {
            int index = i;
            Button choice = UiFormats.literal(new Button((i + 1) + "  " + offered.choices().get(i).text()));
            choice.getStyleClass().add("step-choice");
            choice.setWrapText(true);
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setOnAction(event -> host.answerChoice(check.id(), index));
            choiceButtons.add(choice);
            Optional<TourCheck.Location> at = offered.choices().get(i).at();
            if (offered.kind() == TourCheck.Kind.TRACE && at.isPresent()) {
                Button peek = new Button("peek");
                peek.getStyleClass().add("step-choice-peek");
                peek.setOnAction(event -> host.openLocation(at.get().file(), at.get().line()));
                HBox.setHgrow(choice, Priority.ALWAYS);
                HBox row = new HBox(6, choice, peek);
                row.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().add(row);
            } else {
                box.getChildren().add(choice);
            }
        }
    }
}
