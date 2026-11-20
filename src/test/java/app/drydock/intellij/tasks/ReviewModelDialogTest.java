package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ModelTiers;
import app.drydock.intellij.tasks.WorkflowSourceLabel.Provenance;
import app.drydock.sdlc.SelectedWorkflow.SourceDescriptor;
import app.drydock.sdlc.SelectedWorkflow.SourceKind;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComboBox;
import javax.swing.text.JTextComponent;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Platform checks for the review dialog's workflow-source line and running-session note. */
public class ReviewModelDialogTest extends BasePlatformTestCase {

    private static final SourceDescriptor FILE =
            new SourceDescriptor(SourceKind.FILE, "review", Optional.of("docs/review.md"));

    private void with(Optional<ReviewModelDialog.SourceIndicator> source, boolean running,
                      Consumer<ReviewModelDialog> body) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            ReviewModelDialog dialog = new ReviewModelDialog(getProject(), List.of(), AgentKind.CLAUDE,
                    k -> Optional.of("seed-model"), k -> new ModelTiers(Optional.of("s"), Optional.of("m"), Optional.of("l")),
                    k -> List.of(), k -> true, source, running);
            try {
                body.accept(dialog);
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    private static ReviewModelDialog.SourceIndicator indicator(Provenance p, boolean edited) {
        return new ReviewModelDialog.SourceIndicator(p, "/home/u/proj", Optional.of(FILE), edited);
    }

    public void testLabelPerSource() {
        with(Optional.of(indicator(Provenance.DEFAULT, false)), false,
                d -> assertEquals("Workflow source: Drydock default", d.sourceLabelText()));
        with(Optional.of(indicator(Provenance.PROJECT, false)), false,
                d -> assertEquals("Workflow source: Project proj: docs/review.md", d.sourceLabelText()));
        with(Optional.of(indicator(Provenance.DEFAULT_ONCE, false)), false,
                d -> assertEquals("Workflow source: Drydock default (this time only)", d.sourceLabelText()));
        with(Optional.of(indicator(Provenance.PROJECT, true)), false, d -> assertEquals(
                "Workflow source: Edited for this invocation; based on Project proj: docs/review.md",
                d.sourceLabelText()));
        with(Optional.empty(), false, d -> assertEquals("", d.sourceLabelText()));
    }

    public void testModelAndTierSelectionSurviveSourceRefresh() {
        with(Optional.of(indicator(Provenance.DEFAULT, false)), false, d -> {
            JTextComponent model = (JTextComponent) ((JComboBox<?>) d.modelField().getComponent(0))
                    .getEditor().getEditorComponent();
            model.setText("picked-model");
            d.refreshSource(Optional.of(indicator(Provenance.DEFAULT_ONCE, false)));
            d.refreshSource(Optional.empty());
            d.refreshSource(Optional.of(indicator(Provenance.PROJECT, true)));
            assertEquals("picked-model", model.getText());
            d.doOKAction();
            assertEquals(Optional.of("picked-model"), d.acceptedModel());
            assertEquals(Optional.of("s"), d.acceptedTiers().orElseThrow().strong());
            assertEquals(Optional.of("l"), d.acceptedTiers().orElseThrow().low());
        });
    }

    public void testRunningSessionSaysNewModelCannotChangeRunningAgent() {
        with(Optional.empty(), true, d -> {
            assertTrue(d.runningNoteText(), d.runningNoteText().contains("cannot change an already-running agent"));
            assertTrue(d.runText(), d.runText().contains("running agent"));
        });
        with(Optional.empty(), false, d -> assertEquals("", d.runningNoteText()));
    }
}
