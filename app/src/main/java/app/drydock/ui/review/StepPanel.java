package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepGate;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableStringValue;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The right-hand panel in tour mode: the current step's narrative, anchors
 * and active check, and the containers later tasks fill with triage and
 * impact. View-only: every action goes to {@link Host}.
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
    }

    private final Host host;
    private final VBox content = new VBox(10);
    private final VBox extraSections = new VBox(10);
    private final List<Button> choiceButtons = new ArrayList<>();
    private Optional<TextArea> riskBox = Optional.empty();
    private Optional<TextField> overrideReason = Optional.empty();
    private boolean collapsed;
    private boolean narrow;

    StepPanel(Host host) {
        this.host = host;
        getStyleClass().add("step-panel");
        ScrollPane scroll = new ScrollPane(new VBox(14, content, extraSections));
        scroll.setFitToWidth(true);
        getChildren().add(scroll);
        applyWidth();
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

    /** A one-line notice above the content; the next {@link #show} clears it. */
    void showTransient(String message) {
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

    void focusUnmet(StepGate.Unmet unmet) {
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
        return anchor.startKey().substring(1);
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
