package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.FormBuilder;

import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

import java.util.Optional;
import java.util.function.Function;

/**
 * The small dialog behind {@code Review …}: one starting-model picker,
 * seeded from the repository's last-used model for the review's harness.
 * Confirming hands the picked model (empty = the harness's own default) to
 * the launch, which records it as that repository's last-used model for the
 * harness; cancelling cancels the review. The agent itself is not asked
 * here: the review flow picks it, as it always has.
 */
final class ReviewModelDialog extends DialogWrapper {

    private final ModelField modelField;

    /** The immutable submission captured before the dialog closed. */
    private Optional<String> acceptedModel;

    ReviewModelDialog(Project project, AgentKind agent, Optional<String> lastUsedModel,
                      Function<AgentKind, java.util.List<String>> suggestions) {
        super(project, true);
        setTitle("Review with model");
        modelField = new ModelField(suggestions);
        modelField.setAgent(agent, kind -> lastUsedModel);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Starting model:", modelField)
                .getPanel();
    }

    @Override
    protected void doOKAction() {
        acceptedModel = modelField.selected();
        super.doOKAction();
    }

    /** The picked model; empty means the harness's own default. Only valid after {@code showAndGet()}. */
    Optional<String> acceptedModel() {
        if (acceptedModel == null) {
            throw new IllegalStateException("The review model dialog has not been accepted");
        }
        return acceptedModel;
    }
}
