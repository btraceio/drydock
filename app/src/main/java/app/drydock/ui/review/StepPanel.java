package app.drydock.ui.review;

import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepGate;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The right-hand panel in tour mode: the current step's narrative, anchors
 * and active check, the agent's findings on the step awaiting triage, and
 * the blocker banner that stands in front of the tour. View-only: every
 * action goes to {@link Host}.
 */
final class StepPanel extends VBox {

    static final double EXPANDED_WIDTH = ReviewFindingsMargin.EXPANDED_WIDTH;
    static final double NARROW_WIDTH = ReviewFindingsMargin.NARROW_WIDTH;
    static final double COLLAPSED_WIDTH = ReviewFindingsMargin.COLLAPSED_WIDTH;

    interface Host {
        void answerChoice(String checkId, int choiceIndex);
        void submitRisk(String checkId, String answer);
        void override(String reason);
        void askAgent(String checkId);
        void goToAnchor(int anchorIndex);
        void retryRisk(String checkId);
        void triage(ReviewAnnotation finding, Triage triage, Optional<String> reason);
        void revealFinding(ReviewAnnotation finding);
        void sendBack(List<ReviewAnnotation> confirmedBlockers);
        void reviewAnyway();
        void backToStep();
    }

    private final Host host;
    private final VBox content = new VBox(10);
    private final VBox extraSections = new VBox(10);
    private final VBox triageSection = new VBox(8);
    private final ScrollPane scroll;
    private final Button backPill = new Button();
    private final List<Button> choiceButtons = new ArrayList<>();
    private Optional<TextArea> riskBox = Optional.empty();
    private Optional<TextField> overrideReason = Optional.empty();
    private boolean collapsed;
    private boolean narrow;

    StepPanel(Host host) {
        this.host = host;
        getStyleClass().add("step-panel");
        triageSection.getStyleClass().add("step-triage");
        scroll = new ScrollPane(new VBox(14, content, extraSections));
        scroll.setFitToWidth(true);
        // The "↩ back to step N" pill sits above the scroll, outside the
        // content show() rebuilds, so a redraw of the step does not drop it;
        // the view decides when it shows (after a navigation away from the
        // step) and when it goes (b, a step change, an anchor chip).
        backPill.getStyleClass().add("step-back-pill");
        backPill.setOnAction(event -> host.backToStep());
        backPill.setVisible(false);
        backPill.setManaged(false);
        getChildren().addAll(backPill, scroll);
        applyWidth();
    }

    /** Shows "↩ back to step {@code number}"; see the constructor for who hides it. */
    void showBackPill(int number) {
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
        content.getChildren().clear();
        Label header = new Label("Step " + view.number() + " of " + view.total() + " · " + view.step().title());
        header.getStyleClass().add("step-panel-header");
        Label narrative = new Label(view.step().narrative());
        narrative.setWrapText(true);
        narrative.getStyleClass().add("step-panel-narrative");
        content.getChildren().addAll(header, narrative, anchorChips(view));
        content.getChildren().add(checkSection(view));
    }

    void showMessage(String message) {
        choiceButtons.clear();
        riskBox = Optional.empty();
        overrideReason = Optional.empty();
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
     * The banner that stands instead of the step while the agent proposes
     * blocking problems (spec §4): one row per blocker, then send the
     * confirmed ones back or review anyway.
     */
    void showBanner(List<ReviewAnnotation> blockers, boolean shelved) {
        choiceButtons.clear();
        riskBox = Optional.empty();
        overrideReason = Optional.empty();
        triageSection.getChildren().clear();
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
        if (unmet.kind() == StepGate.Kind.BLOCKER) {
            showTransient(unmet.message());
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
        content.setVisible(!value);
        extraSections.setVisible(!value);
        applyWidth();
    }

    void setNarrow(boolean value) {
        narrow = value;
        applyWidth();
    }

    private void applyWidth() {
        double width = collapsed ? COLLAPSED_WIDTH : narrow ? NARROW_WIDTH : EXPANDED_WIDTH;
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
        if (offerNotSure) {
            Button notSure = new Button("Not sure");
            // Leaves it proposed; its line is where the question lives.
            notSure.setOnAction(event -> host.revealFinding(finding));
            buttons.getChildren().add(notSure);
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
        return new VBox(6, buttons, dismissRow);
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
            Button chip = new Button(anchor.file() + ":" + startLineOf(anchor));
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

    private VBox checkSection(StepView view) {
        VBox box = new VBox(8);
        box.getStyleClass().add("step-check");
        StepProgress progress = view.progress();
        if (progress.decision() != StepProgress.Decision.NONE) {
            box.getChildren().add(new Label(switch (progress.decision()) {
                case PASSED -> "Approved.";
                case CHANGES -> "Changes requested.";
                case OVERRIDDEN -> "Approved without passing: " + progress.overrideReason().orElse("");
                case NONE -> "";
            }));
            return box;
        }
        for (TourCheck check : view.step().checks()) {
            CheckProgress p = progress.check(check.id());
            if (p.settled()) {
                continue;
            }
            renderActiveCheck(box, check, p);
            return box;
        }
        box.getChildren().add(new Label("All checks passed — press a to approve this step."));
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
            case AWAITING_AGENT -> box.getChildren().add(new Label("Checking with the agent…"));
            case AGENT_UNAVAILABLE -> {
                Button retry = new Button("Retry");
                retry.setOnAction(event -> host.retryRisk(check.id()));
                box.getChildren().addAll(new Label("The agent did not answer."), retry);
            }
            case EXHAUSTED -> {
                TextField reason = new TextField();
                reason.setPromptText("Why approve without passing?");
                reason.getStyleClass().add("step-override-reason");
                Button override = new Button("Approve without passing");
                override.disableProperty().bind(blank(reason.textProperty()));
                override.setOnAction(event -> host.override(reason.getText().strip()));
                Button ask = new Button("Ask the agent");
                ask.setOnAction(event -> host.askAgent(check.id()));
                overrideReason = Optional.of(reason);
                box.getChildren().addAll(new Label("Out of alternates."), reason, override, ask);
            }
            default -> renderPrompt(box, check, offered);
        }
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
            Button choice = new Button((i + 1) + "  " + offered.choices().get(i).text());
            choice.getStyleClass().add("step-choice");
            choice.setWrapText(true);
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setOnAction(event -> host.answerChoice(check.id(), index));
            choiceButtons.add(choice);
            box.getChildren().add(choice);
        }
    }
}
