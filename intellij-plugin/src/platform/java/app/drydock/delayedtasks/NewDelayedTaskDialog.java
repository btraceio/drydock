package app.drydock.delayedtasks;

import app.drydock.tasks.DoneBy;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.UIUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.FlowLayout;
import java.awt.event.ActionEvent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.swing.event.DocumentEvent;

/**
 * The new delayed-task dialog (T18a): a required title, an optional body,
 * cross-project prerequisites and the done_by policy. Creating a definition
 * PREPARES NOTHING (P5): no worktree, no agent, no store write, no
 * session - a confirmed OK returns one immutable {@link Result} and the
 * dialog itself performs no I/O at all. There is therefore no async
 * completion path to clear: everything on screen is derived from the one
 * indexed snapshot the caller built (off the EDT, one scan), and prerequisite
 * refusals come from the store's own production resolution, composed over
 * {@link app.drydock.tasks.TaskGraph} inside the picker's index.
 *
 * <p>The done_by control is honest (D1/D16): it defaults HUMAN, and picking
 * AGENT changes only who may set this task done under the accepted T07 rules
 * - an agent's done becomes review for a human-done_by task and done for an
 * agent-done_by one, while drop stays human-only either way. The control
 * never bypasses review and never grants anything the rules do not.</p>
 *
 * <p>Keyboard: real focusable OK/Cancel buttons (Enter/Space press them),
 * Esc cancels (DialogWrapper), the title field gets the initial focus, and
 * every refusal is shown before the dialog can confirm - an empty title
 * blocks OK with a visible reason both live under the field and on OK.</p>
 */
public final class NewDelayedTaskDialog extends DialogWrapper {

    private final TaskPrerequisitePicker prerequisites;
    private final JBTextField title = new JBTextField();
    private final JBTextArea body = new JBTextArea(4, 40);
    private final JBRadioButton doneByHuman = new JBRadioButton("Human confirms done", true);
    private final JBRadioButton doneByAgent = new JBRadioButton("Agent may set done");
    /** The honest policy sentence under the done_by radio row (D16). */
    private final JBLabel policy = new JBLabel();
    /** The live visible reason while the title is empty. */
    private final JBLabel titleReason = new JBLabel("A task needs a title.");
    /** The exact values a confirmed OK accepted, captured before the dialog closes. */
    private Result accepted;

    /**
     * One dialog over one indexed snapshot; the index is built by the caller
     * (one scan, off the EDT) - this dialog and its picker never rescan.
     */
    public NewDelayedTaskDialog(TaskPrerequisitePicker.Index index) {
        super(true);
        this.prerequisites = new TaskPrerequisitePicker(Objects.requireNonNull(index, "index"));
        setTitle("New delayed task");
        init();
        setResizable(true);
        setOKButtonText("Create task");
        titleReason.setForeground(UIUtil.getErrorForeground());
        titleReason.setVisible(false);
        policy.setForeground(UIUtil.getContextHelpForeground());
        refreshPolicy();
    }

    /**
     * The immutable creation result (P5): what a confirmed OK returns. The
     * record owns its invariants - the title is required (empty text is
     * refused with a visible reason), the body defaults empty, the refs are
     * defensively copied, and a missing done_by defaults HUMAN (D1).
     */
    public record Result(String title, String body, List<String> after, DoneBy doneBy) {

        /** A record cannot hold a null list, a null body or an absent policy. */
        public Result {
            title = title == null ? "" : title.strip();
            body = body == null ? "" : body;
            after = after == null ? List.of() : List.copyOf(after);
            doneBy = doneBy == null ? DoneBy.HUMAN : doneBy;
        }

        /** The visible reason a candidate title is refused; empty when it names a task. */
        public static Optional<String> titleRefusal(String candidate) {
            return candidate == null || candidate.strip().isEmpty()
                    ? Optional.of("Give the task a title.") : Optional.empty();
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        // A task body is prose: the UI font, not the text area's monospace.
        body.setFont(JBFont.label());

        ButtonGroup doneByGroup = new ButtonGroup();
        doneByGroup.add(doneByHuman);
        doneByGroup.add(doneByAgent);
        doneByHuman.addActionListener(this::policyChanged);
        doneByAgent.addActionListener(this::policyChanged);

        title.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                refreshTitleReason();
            }
        });

        JPanel doneByRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        doneByRow.add(doneByHuman);
        doneByRow.add(Box.createHorizontalStrut(12));
        doneByRow.add(doneByAgent);

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Title:", title)
                .addComponent(titleReason)
                .addLabeledComponent("Body (optional):", ScrollPaneFactory.createScrollPane(body), true)
                .addLabeledComponent("Prerequisites:", prerequisites.component(), true)
                .addLabeledComponent("Done by:", doneByRow)
                .addComponent(policy)
                .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return title;
    }

    /** A blank title blocks OK with its reason on the refusing field; every
     * prerequisite refusal follows, as the production resolution states it. */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        Optional<String> refusal = Result.titleRefusal(title.getText());
        if (refusal.isPresent()) {
            return new ValidationInfo(refusal.get(), title);
        }
        List<String> refusals = prerequisites.refusals();
        if (!refusals.isEmpty()) {
            return new ValidationInfo(String.join("  ", refusals), prerequisites.component());
        }
        return null;
    }

    /** Captures the accepted values BEFORE the wrapper disposes its Swing peers. */
    @Override
    protected void doOKAction() {
        accepted = new Result(title.getText(), body.getText(), prerequisites.references(),
                doneByAgent.isSelected() ? DoneBy.AGENT : DoneBy.HUMAN);
        super.doOKAction();
    }

    /** The accepted creation values; refuses before a confirmed OK. */
    public Result result() {
        if (accepted == null) {
            throw new IllegalStateException("The new-task dialog has not accepted a result");
        }
        return accepted;
    }

    /** The done_by label follows the selection; it always states the real T07 rule. */
    private void policyChanged(ActionEvent event) {
        refreshPolicy();
    }

    private void refreshPolicy() {
        policy.setText(doneByAgent.isSelected()
                ? "An agent may set this task done; dropping or reopening it stays human-only."
                : "Done needs a human: an agent's done lands in review for you to confirm.");
    }

    /** Shows the visible title reason exactly while the title is empty. */
    private void refreshTitleReason() {
        titleReason.setVisible(Result.titleRefusal(title.getText()).isPresent());
    }
}
