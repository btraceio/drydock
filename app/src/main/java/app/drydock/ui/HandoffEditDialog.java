package app.drydock.ui;

import app.drydock.domain.HandoffBrief;
import app.drydock.mcp.PromptSafety;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.List;
import java.util.Optional;

/**
 * Lets the human write the handoff brief themselves.
 *
 * <p>Expected to carry most of the load in practice: the human usually knows
 * exactly what a successor needs -- "the parser rewrite is a dead end, don't
 * retry it" -- and can type it faster than any agent round-trip, including
 * when the agent is the thing that died.</p>
 *
 * <p>A human edit is never charged against the session's MCP budget. That
 * budget exists to stop an agent looping; it has nothing to say about a person
 * correcting a brief.</p>
 */
public final class HandoffEditDialog extends Dialog<HandoffEditDialog.Result> {

    /** What the human typed. Slot semantics match {@code session_handoff}: blank clears. */
    public record Result(String goal, String nextStep, Optional<String> approach, Optional<String> decisions,
                         Optional<String> ruledOut, Optional<String> corrections) {
    }

    private final TextArea goal = slotField("What this session is trying to achieve");
    private final TextArea nextStep = slotField("What the successor should do first");
    private final TextArea approach = slotField("The shape of the current solution");
    private final TextArea decisions = slotField("Choices made, and why");
    private final TextArea ruledOut = slotField("What was tried and rejected, and why");
    private final TextArea corrections = slotField("What you pushed back on");

    public HandoffEditDialog(String sessionName, Optional<HandoffBrief> existing) {
        setTitle("Handoff brief");
        setHeaderText("What should the next agent working on \"" + sessionName + "\" know?");
        setResizable(true);

        existing.ifPresent(brief -> {
            goal.setText(brief.goal());
            nextStep.setText(brief.nextStep());
            brief.approach().ifPresent(approach::setText);
            brief.decisions().ifPresent(decisions::setText);
            brief.ruledOut().ifPresent(ruledOut::setText);
            brief.corrections().ifPresent(corrections::setText);
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        row = addRow(grid, row, "Goal *", goal);
        row = addRow(grid, row, "Next step *", nextStep);
        row = addRow(grid, row, "Approach", approach);
        row = addRow(grid, row, "Decisions", decisions);
        row = addRow(grid, row, "Ruled out", ruledOut);
        addRow(grid, row, "Corrections", corrections);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Required slots really are required, and an over-long one is refused
        // by the same rule the MCP tool applies -- so OK cannot produce a
        // brief that the store would then reject.
        BooleanBinding incomplete = Bindings.createBooleanBinding(
                () -> goal.getText().isBlank() || nextStep.getText().isBlank() || anySlotTooLong(),
                goal.textProperty(), nextStep.textProperty(), approach.textProperty(),
                decisions.textProperty(), ruledOut.textProperty(), corrections.textProperty());
        getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(incomplete);

        setResultConverter(button -> {
            if (button == null || button.getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            return new Result(goal.getText().strip(), nextStep.getText().strip(),
                    optional(approach), optional(decisions), optional(ruledOut), optional(corrections));
        });
    }

    /** Package-private so the dialog's gating can be tested without driving a modal. */
    boolean anySlotTooLong() {
        for (TextArea field : List.of(goal, nextStep, approach, decisions, ruledOut, corrections)) {
            String text = field.getText();
            if (text.codePointCount(0, text.length()) > PromptSafety.MAX_HANDOFF_SLOT_CHARS) {
                return true;
            }
        }
        return false;
    }

    TextArea goalField() {
        return goal;
    }

    TextArea nextStepField() {
        return nextStep;
    }

    TextArea ruledOutField() {
        return ruledOut;
    }

    private static Optional<String> optional(TextArea field) {
        String text = field.getText().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private static int addRow(GridPane grid, int row, String label, TextArea field) {
        Label caption = new Label(label);
        caption.getStyleClass().add("handoff-edit-label");
        grid.add(caption, 0, row);
        grid.add(field, 1, row);
        return row + 1;
    }

    private static TextArea slotField(String prompt) {
        TextArea field = new TextArea();
        field.setPromptText(prompt);
        field.setPrefRowCount(2);
        field.setWrapText(true);
        field.getStyleClass().add("handoff-edit-field");
        return field;
    }
}
