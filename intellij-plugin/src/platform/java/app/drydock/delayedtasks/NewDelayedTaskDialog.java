package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskId;

import com.intellij.openapi.ui.ComboBox;
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

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
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
    /** The live visible reason while the title is empty. */
    private final JBLabel titleReason = new JBLabel("A task needs a title.");
    /** The project the task is attached to: the store directory's project identity (D2). */
    private final ComboBox<ProjectChoice> project = new ComboBox<>();
    /** The honest policy sentence under the done_by radio row (D16). */
    private final JBLabel policy = new JBLabel();
    /** The exact values a confirmed OK accepted, captured before the dialog closes. */
    private Result accepted;

    /**
     * One registered project as the attachment picker offers it. {@code id} is
     * the shared-registry (or install-catalog) identity the store directory is
     * named by; it is empty only for a NOT-yet-registered default -- the dialog
     * opened from an IDE project the shared list does not carry yet, which the
     * save registers when it commits (the task then stores that stable id).
     * {@code root} is the main checkout the save/start resolves through; a
     * remote project carries its placeholder root.
     */
    public record ProjectChoice(Optional<RepositoryId> id, Path root, String name) {

        public ProjectChoice {
            id = Objects.requireNonNull(id, "id");
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            name = Objects.requireNonNull(name, "name").strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A project choice needs a display name");
            }
        }

        /** The registered project as a choice. */
        public static ProjectChoice of(RepositoryId id, Path root, String name) {
            return new ProjectChoice(Optional.of(id), root, name);
        }

        @Override
        public String toString() {
            return name;
        }

        /** The picker's one-line label: the name; an unregistered default says what its
          * save will do. The renderer and the tests read the same rule. */
        public String displayLabel() {
            return id.isPresent() ? name : name + "  (will be registered)";
        }
    }

    /**
     * The values the dialog opens with. {@link #none} is the creation form; a
     * prefill with a task id is the edit form -- every field starts at the
     * task's stored value, the picker excludes the task itself from the
     * prerequisite hits, and the OK button saves instead of creating.
     */
    public record Prefill(TaskId task, String title, String body, List<String> after,
                          DoneBy doneBy, ProjectChoice project) {

        /** The task's stored values as the edit form's starting point. */
        public static Prefill edit(TaskDocument document, ProjectChoice project) {
            return new Prefill(document.id(), document.title(),
                    String.join("\n", TaskFilePatch.noteParagraphs(document.rawBody())),
                    document.after(), document.doneBy(), project);
        }

        /** The creation form: everything blank, the attachment defaulting to the
         * project the dialog was opened from. */
        public static Prefill none(ProjectChoice project) {
            return new Prefill(null, null, null, null, null, project);
        }
    }

    /**
     * The immutable accepted result (P5): what a confirmed OK returns. The
     * record owns its invariants -- the title is required (empty text is
     * refused with a visible reason), the body defaults empty, the refs are
     * defensively copied, a missing done_by defaults HUMAN (D1), and the
     * project is required (the picker's selection; an empty picker is a visible
     * refusal on OK).
     */
    public record Result(String title, String body, List<String> after, DoneBy doneBy,
                         ProjectChoice project) {

        /** A record cannot hold a null list, a null body or an absent policy. */
        public Result {
            title = title == null ? "" : title.strip();
            body = body == null ? "" : body;
            after = after == null ? List.of() : List.copyOf(after);
            doneBy = doneBy == null ? DoneBy.HUMAN : doneBy;
            project = Objects.requireNonNull(project, "project");
        }

        /** The visible reason a candidate title is refused; empty when it names a task. */
        public static Optional<String> titleRefusal(String candidate) {
            return candidate == null || candidate.strip().isEmpty()
                    ? Optional.of("Give the task a title.") : Optional.empty();
        }
    }

    /** Whether this dialog edits an existing task (prefill carries a task id). */
    private final boolean editing;

    /**
     * One dialog over one indexed snapshot and one attachment list; the index
     * and the choices are built by the caller (one scan, off the EDT) -- this
     * dialog and its picker never rescan.
     */
    public NewDelayedTaskDialog(TaskPrerequisitePicker.Index index, List<ProjectChoice> projects,
            Prefill prefill) {
        super(true);
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(prefill, "prefill");
        this.prerequisites = new TaskPrerequisitePicker(index);
        this.editing = prefill.task() != null;
        setTitle(editing ? "Edit task" : "New delayed task");
        init();
        setResizable(true);
        setOKButtonText(editing ? "Save changes" : "Create task");
        title.setText(prefill.title() == null ? "" : prefill.title());
        if (prefill.body() != null) {
            body.setText(prefill.body());
        }
        prerequisites.setReferences(prefill.after());
        if (prefill.doneBy() == DoneBy.AGENT) {
            doneByAgent.setSelected(true);
        }
        for (ProjectChoice choice : projects) {
            project.addItem(choice);
        }
        if (prefill.project() != null) {
            project.setSelectedItem(prefill.project());
        }
        titleReason.setForeground(UIUtil.getErrorForeground());
        titleReason.setVisible(Result.titleRefusal(title.getText()).isPresent());
        policy.setForeground(UIUtil.getContextHelpForeground());
        refreshPolicy();
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

        project.setRenderer(new ProjectChoiceRenderer());

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Title:", title)
                .addComponent(titleReason)
                .addLabeledComponent("Body (optional):", ScrollPaneFactory.createScrollPane(body), true)
                .addLabeledComponent("Project:", project)
                .addLabeledComponent("Prerequisites:", prerequisites.component(), true)
                .addLabeledComponent("Done by:", doneByRow)
                .addComponent(policy)
                .getPanel();
    }

    /** The picker's one-line entry: the project's display name; an unregistered default
      * says so, since its save will register it. */
    private static final class ProjectChoiceRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof ProjectChoice choice) {
                setText(choice.displayLabel());
            }
            return this;
        }
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return title;
    }

    /** A blank title blocks OK with its reason on the refusing field; every
     * prerequisite refusal follows, as the production resolution states it, and the
     * attachment must name a project. */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        Optional<String> refusal = Result.titleRefusal(title.getText());
        if (refusal.isPresent()) {
            return new ValidationInfo(refusal.get(), title);
        }
        if (!(project.getSelectedItem() instanceof ProjectChoice)) {
            return new ValidationInfo("Pick the project the task belongs to.", project);
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
                doneByAgent.isSelected() ? DoneBy.AGENT : DoneBy.HUMAN,
                (ProjectChoice) project.getSelectedItem());
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
