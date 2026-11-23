package app.drydock.intellij.tasks;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The batch triage's one question: how many triage sessions to run at once.
 * Shown after the untriaged issues are counted, before anything is queued —
 * Escape or Cancel queues nothing. The value is remembered across runs and
 * pre-fills the next dialog; the remembered value is shared by the GitHub
 * and Jira nodes because the trade-off it tunes (how many agents hammer the
 * checkout and the tracker at once) is the same.
 */
final class TriageParallelismDialog extends DialogWrapper {

    private static final int MIN_PARALLELISM = 1;
    private static final int MAX_PARALLELISM = 16;

    private final JSpinner parallelism;

    private TriageParallelismDialog(Project project, String projectDisplayName, int count, String system,
                                    int remembered) {
        super(project, true);
        setTitle("Triage Untriaged — " + system + " (" + projectDisplayName + ")");
        parallelism = new JSpinner(new SpinnerNumberModel(
                clamp(remembered), MIN_PARALLELISM, MAX_PARALLELISM, 1));
        setOKButtonText("Triage " + count + " " + (count == 1 ? "issue" : "issues"));
        init();
    }

    private static int clamp(int value) {
        return Math.max(MIN_PARALLELISM, Math.min(MAX_PARALLELISM, value));
    }

    /**
     * Asks on the EDT and completes with the confirmed parallelism, or empty
     * on Cancel/Escape/disposal. Safe to call from any thread: the EDT hop
     * is internal, and the disposal case completes empty.
     */
    static Optional<Integer> ask(Project project, String projectDisplayName, int count, String system) {
        int remembered = PropertiesComponent.getInstance()
                .getInt(BatchTriageService.PARALLELISM_PROPERTY, BatchTriageService.DEFAULT_PARALLELISM);
        CompletableFuture<Integer> pending = new CompletableFuture<>();
        Runnable show = () -> {
            if (project.isDisposed() || pending.isDone()) {
                settle(pending, -1);
                return;
            }
            TriageParallelismDialog dialog = new TriageParallelismDialog(project, projectDisplayName, count,
                    system, remembered);
            dialog.show();
            if (dialog.isOK()) {
                int value = (Integer) dialog.parallelism.getValue();
                PropertiesComponent.getInstance().setValue(BatchTriageService.PARALLELISM_PROPERTY, value,
                        BatchTriageService.DEFAULT_PARALLELISM);
                pending.complete(value);
            } else {
                settle(pending, -1);
            }
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            show.run();
        } else {
            ApplicationManager.getApplication().invokeLater(show);
        }
        try {
            int value = pending.get();
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        }
    }

    /** Completes {@code pending} with {@code value} unless something already did. */
    private static void settle(CompletableFuture<Integer> pending, int value) {
        if (!pending.isDone()) {
            pending.complete(value);
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel hint = new JBLabel("How many triage sessions run at once; the rest are queued and"
                + " start as sessions end.");
        hint.setForeground(UIUtil.getContextHelpForeground());
        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Parallel sessions:", parallelism)
                .addComponentToRightColumn(hint)
                .getPanel();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        return null; // the spinner's model bounds the value already
    }
}
