package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.config.UserConfig;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.settings.DrydockConfigurable;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.intellij.tasks.WorkflowInvocationController.Prepared;
import app.drydock.intellij.tasks.WorkflowInvocationController.Request;
import app.drydock.intellij.tasks.WorkflowInvocationState.Accepted;
import app.drydock.intellij.tasks.WorkflowInvocationState.Input;
import app.drydock.intellij.tasks.WorkflowSourceLabel.Provenance;
import app.drydock.intellij.workspace.WorkspaceSettings;
import app.drydock.review.ReviewInstructions;
import app.drydock.sdlc.SelectedWorkflow.SourceDescriptor;
import app.drydock.sdlc.WorkflowResolution.WorkflowProblem;
import app.drydock.sdlc.WorkflowTarget;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.NewTaskLauncherResult;

import static app.drydock.tasks.NewTaskPlan.SAVE_OK_LABEL;
import static app.drydock.tasks.NewTaskPlan.startEnabled;
import static app.drydock.tasks.NewTaskPlan.validatesStart;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * New task: an optional description, an agent and a mode; the task name is
 * inferred from the description (a preset's name wins when the entry point
 * supplies one), and a blank description starts a chat-like task named
 * "New task" that tells the agent nothing. Everything else — branch, start point, folder, window — is generated
 * and folded into one summary line; {@code Change…} unfolds it for the rare
 * edit.
 *
 * <p>The decisions are {@link NewTaskPlan}'s; this class only lays them
 * out. The generated branch is independent of the description and stays fixed
 * for this dialog; the folder follows the branch until the user edits it.</p>
 *
 * <p>Save-later mode (P5, D5): the "Save as task for later" box turns the
 * OK button into "Save Task", switches off every start field and the Start
 * validation, and makes OK return saved-task values instead of a launch
 * choice ({@link NewTaskLauncherResult}); nothing is started, prepared or
 * written until the dialog has closed. Launch mode keeps its wording and
 * behavior unchanged.</p>
 */
final class NewTaskDialog extends DialogWrapper {

    private static final int WIDTH = 540;

    /**
     * How a launch site wires one invocation's workflow preparation: the controller factory receives the
     * dialog's UI callbacks; {@code request} describes the preparation for a harness; {@code source} is the
     * selected source, queried when a preparation completes (empty: the Drydock default applies).
     */
    record Workflow(Function<WorkflowInvocationController.Ui, WorkflowInvocationController> controller,
                    Function<AgentKind, Request> request, String project,
                    Supplier<Optional<SourceDescriptor>> source) {
    }

    /** The summary line wraps here, leaving room for its Change… link. */
    private static final int SUMMARY_WIDTH = 420;

    private final Project project;
    private final NewTaskPlan.Inputs inputs;
    private final Optional<Supplier<Optional<String>>> refChooser;
    private final NewTaskPreset preset;
    private final String generatedBranch;

    private final JBTextArea instructions = new JBTextArea(4, 40);
    /** Created in {@code createCenterPanel}, where {@code inputs} is at hand. */
    private ModelField modelField;
    /**
     * The STRONG/MID/LOW subagent tier pickers, shown only when the preset
     * carries a role mapping and the selected harness dispatches subagents
     * (the rules would otherwise bind nobody). Created in {@code
     * createCenterPanel}, where {@code inputs} is at hand.
     */
    private ModelField strongField;
    private ModelField midField;
    private ModelField lowField;
    private JPanel tierRows;
    private final Map<AgentKind, JBRadioButton> agentButtons = new LinkedHashMap<>();
    private final JBRadioButton writes = new JBRadioButton("Full access", true);
    private final JBRadioButton readOnly = new JBRadioButton("Read-only");
    private final JBLabel summary = new JBLabel();
    private final HyperlinkLabel change = new HyperlinkLabel("Change…");
    private final JPanel details = new JPanel(new BorderLayout());
    private final JBRadioButton newWorktree = new JBRadioButton("New worktree", true);
    private final JBRadioButton mainCheckout = new JBRadioButton("Main checkout");
    private final JBTextField branch = new JBTextField();
    private final ComboBox<String> start = new ComboBox<>();
    private final TextFieldWithBrowseButton folder = new TextFieldWithBrowseButton();
    private final JBRadioButton thisWindow = new JBRadioButton("This window", true);
    private final JBRadioButton ownWindow = new JBRadioButton("Its own window");
    private final JBLabel warning = new JBLabel();
    private final JBLabel base = new JBLabel();

    private final Optional<Workflow> workflow;
    private WorkflowInvocationController workflowController;
    /** The source indicator, or the bypass notice while the free-form text is blank or a native command. */
    private final JBLabel sourceLabel = new JBLabel();
    private final JBLabel workflowNote = new JBLabel();
    private boolean preparing;
    private boolean workflowReady;
    /** The invalid-source/failed actions: the first is the fallback or Retry, the second always cancels. */
    private final JButton primaryAction = new JButton();
    private final JButton secondaryAction = new JButton("Cancel");
    private Runnable primaryHandler = () -> { };
    /** Set by the invalid-source fallback; labels the invocation DEFAULT_ONCE. */
    private boolean defaultOnceChosen;
    private boolean preparationStarted;
    private boolean seeding;
    private Optional<Accepted<Prepared>> acceptedWorkflow = Optional.empty();

    /** The save-later switch (P5): checked, the dialog saves a task file and starts nothing. */
    private final JCheckBox saveForLater = new JCheckBox("Save as task for later");

    /**
     * The per-launch remote offer (workspace boxes): visible only while the
     * opt-in flag is on, off by default, and enableable only for a Claude
     * launch the remote arm can serve -- a new worktree or the main
     * checkout, no fixed worktree, no binding-carrying preset, a non-fish
     * login shell, and a project that is not itself an SSH remote. A gate
     * that fails clears the selection, so the value can never ride a choice
     * whose conditions no longer hold.
     */
    private final JCheckBox workspaceBox = new JCheckBox("Run on workspace box");

    /**
     * The per-launch eval offer: checked, the session's model traffic is
     * routed to the eval account ({@code x-target-account: eval}) so
     * plugin/extension testing is not charged to ordinary usage. Visible
     * only while the selected harness can actually route eval traffic (the
     * provider's {@code evalAvailable}: pi injects it through the launch
     * env, Claude needs its docker container, Codex has no path), enabled
     * only additionally while a launch is composed and the remote arm is
     * not selected — a workspace box runs Claude only, and eval-on-a-box is
     * not offered. A gate that fails clears the selection, so a checked box
     * can never outlive the conditions that permitted it. The choice is not
     * recorded in a saved task: a task saved for later starts as an
     * ordinary session.
     */
    private final JCheckBox evalMode = new JCheckBox("Eval mode");

    /**
     * The login shell that would run the launch ({@code $SHELL}), read once
     * at construction: the environment does not change mid-run.
     */
    private String loginShell = System.getenv("SHELL");

    /** Where generated folders go; starts as the inputs' and follows "Change base…". */
    private Path worktreeBase;

    /**
     * True once the user edits a field: regeneration must preserve that value.
     * The folder follows branch edits only until it has been edited itself.
     */
    private boolean branchEdited;
    /** True once the user edits the composed instructions (T31a): a harness switch then never recomposes (T09). */
    private boolean instructionsEdited;
    private boolean folderEdited;
    /** True once the user picks Work in; then it stops following the description's blankness. */
    private boolean workInEdited;
    /** Programmatic updates must not count as the user's edits. */
    private boolean updating;

    /**
     * The exact values validated by the successful Start click. DialogWrapper
     * disposes its peer before {@code showAndGet()} returns, so the caller must
     * not reconstruct a choice from Swing fields after the modal has closed.
     */
    private Choice acceptedChoice;

    /**
     * The accepted submission -- launch choice or saved-task values (T19a) --
     * captured before the wrapper disposes its peers, like
     * {@link #acceptedChoice}.
     */
    private NewTaskLauncherResult acceptedResult;

    /**
     * {@code refChooser} opens Git's own branch/tag picker when the
     * project's repository is open in some window; empty otherwise.
     */
    NewTaskDialog(Project project, NewTaskPlan.Inputs inputs, Optional<Supplier<Optional<String>>> refChooser) {
        this(project, inputs, refChooser, NewTaskPreset.empty());
    }

    NewTaskDialog(Project project, NewTaskPlan.Inputs inputs, Optional<Supplier<Optional<String>>> refChooser,
                  NewTaskPreset preset) {
        this(project, inputs, refChooser, preset, Optional.empty());
    }

    NewTaskDialog(Project project, NewTaskPlan.Inputs inputs, Optional<Supplier<Optional<String>>> refChooser,
                  NewTaskPreset preset, Optional<Workflow> workflow) {
        super(project, true);
        this.workflow = workflow.filter(w -> preset.workflowTarget().isPresent());
        this.project = project;
        this.inputs = inputs;
        this.refChooser = refChooser;
        this.preset = preset;
        this.generatedBranch = TaskBranchNames.generate(inputs.takenBranches(), inputs.branchAttribution());
        this.worktreeBase = inputs.worktreeBase();
        instructions.setText(preset.instructions().orElse(""));
        if (preset.fixedMode().orElse(null) == SessionMode.READ_ONLY) {
            readOnly.setSelected(true);
            mainCheckout.setSelected(true);
        }
        if (preset.fixedWorkIn().orElse(null) == WorkIn.MAIN_CHECKOUT) {
            mainCheckout.setSelected(true);
        }
        // A remote (SSH) target has no worktrees and no branch picker: the
        // repository itself is the only place a session can run.
        if (remoteTarget()) {
            mainCheckout.setSelected(true);
        }
        setTitle("New task in " + inputs.repoName());
        ownWindow.setSelected(preset.openIn() == OpenIn.OWN_WINDOW);
        thisWindow.setSelected(preset.openIn() == OpenIn.THIS_WINDOW);
        saveForLater.addActionListener(e -> refresh());
        // Checking the box re-runs the eval gate: eval is not offered on a
        // workspace box, and the gate clears the selection it just disabled.
        workspaceBox.addItemListener(e -> refresh());
        init();
        setResizable(true);
        defaultWorkIn();
        regenerate();
        refresh();
        this.workflow.ifPresent(w -> {
            workflowController = w.controller().apply(new WorkflowUi());
            primaryAction.addActionListener(e -> primaryHandler.run());
            secondaryAction.addActionListener(e -> doCancelAction());
            // A hand-off to the caller (acceptedWorkflow) transfers ownership; otherwise the dialog disposes it.
            Disposer.register(getDisposable(), () -> {
                if (acceptedWorkflow.isEmpty()) {
                    workflowController.dispose();
                }
            });
            syncWorkflow();
        });
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        // A task is prose: the UI font, not the text area's monospace.
        instructions.setFont(JBFont.label());

        JPanel agents = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ButtonGroup agentGroup = new ButtonGroup();
        for (Agent agent : inputs.agents()) {
            if (!NewTaskPlan.offersAgent(agent.kind())) {
                continue;
            }
            JBRadioButton button = new JBRadioButton(agent.displayName());
            // A remote target runs the agent on another host over ssh; a
            // provider without remote support cannot serve it
            // (requireRemoteCapability).
            boolean remoteOk = !remoteTarget()
                    || DrydockPluginService.getInstance().registry().supportsRemote(agent.kind());
            if (!remoteOk) {
                button.setEnabled(false);
                button.setToolTipText("Doesn't support remote sessions");
            } else {
                button.setEnabled(agent.isAvailable());
                if (!agent.isAvailable()) {
                    button.setToolTipText("Not found on PATH: " + agent.describeSearched());
                }
            }
            button.setSelected(agent.kind() == inputs.preselected() && agent.isAvailable() && remoteOk);
            agentGroup.add(button);
            agentButtons.put(agent.kind(), button);
            agents.add(button);
            agents.add(Box.createHorizontalStrut(JBUI.scale(12)));
        }
        if (agentButtons.values().stream().noneMatch(JBRadioButton::isSelected)) {
            agentButtons.values().stream().filter(JBRadioButton::isEnabled).findFirst()
                    .ifPresent(button -> button.setSelected(true));
        }
        // The single-project start (T31a): the composed segment prompt seeds
        // the instructions for the selected harness, and a harness switch
        // recomposes only while the user has not edited the text (T09's
        // contract; the seed runs before the listener below is attached, so
        // seeding is never an edit).
        if (preset.composed().isPresent()) {
            instructions.setText(NewTaskPreset.composedInstructions(selectedAgent(),
                    preset.composed().get().plan(), preset.composed().get().segment()));
            agentButtons.forEach((kind, button) -> button.addActionListener(e -> recomposeInstructions()));
        }
        // The model picker follows the selected harness: switching agents and
        // back restores what the editor held for each (seeded from the
        // repository's last-used model), and each harness's suggestion load
        // runs once.
        modelField = new ModelField(inputs.modelSuggestions());
        modelField.setAgent(selectedAgent(), inputs.lastUsedModel());
        agentButtons.forEach((kind, button) -> button.addActionListener(
                e -> modelField.setAgent(kind, inputs.lastUsedModel())));
        // The remote offer follows the selected harness: a non-Claude agent
        // is no remote launch, so the offer's gate re-runs on every switch
        // and clears a checked box the moment it stops holding.
        agentButtons.forEach((kind, button) -> button.addActionListener(e -> refresh()));
        // A harness switch re-prepares for the new harness; edited text and model/tier picks are untouched.
        agentButtons.forEach((kind, button) -> button.addActionListener(e -> restartWorkflow()));

        // The tier pickers share the suggestion pool with the starting model
        // (one harness, one model space) and seed from the repository's
        // last-used tiers. Visible only where the rules can bind: the preset
        // names subagent roles AND the harness dispatches subagents. A hidden
        // picker is never pointed at a harness, so it starts no suggestion
        // probe (for pi a subprocess).
        strongField = new ModelField(inputs.modelSuggestions());
        midField = new ModelField(inputs.modelSuggestions());
        lowField = new ModelField(inputs.modelSuggestions());
        tierRows = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(4)));
        tierRows.add(tierRow("STRONG model:", strongField));
        tierRows.add(tierRow("MID model:", midField));
        tierRows.add(tierRow("LOW model:", lowField));
        tierRows.setVisible(false);
        if (preset.tierRoles().isPresent()) {
            if (tiersShown(selectedAgent())) {
                setTierFields(selectedAgent());
            }
            tierRows.setVisible(tiersShown(selectedAgent()));
            agentButtons.forEach((kind, button) -> button.addActionListener(e -> {
                if (tiersShown(kind)) {
                    setTierFields(kind);
                }
                boolean wasShowing = tierRows.isShowing();
                tierRows.setVisible(tiersShown(kind));
                if (wasShowing || tierRows.isShowing()) {
                    pack();
                }
            }));
        }

        JPanel modes;
        if (preset.fixedMode().isPresent()) {
            modes = row(new JBLabel(preset.fixedMode().get() == SessionMode.WRITES
                    ? "Full access" : "Read-only"));
        } else {
            modes = row(writes, readOnly);
            group(writes, readOnly);
            writes.addActionListener(e -> refresh());
            readOnly.addActionListener(e -> refresh());
        }

        JPanel summaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        summary.setForeground(UIUtil.getContextHelpForeground());
        summaryRow.add(summary);
        summaryRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        summaryRow.add(change);
        change.addHyperlinkListener(e -> {
            details.setVisible(!details.isVisible());
            change.setHyperlinkText(details.isVisible() ? "Hide" : "Change…");
            pack();
        });

        details.add(detailsForm(), BorderLayout.CENTER);
        details.setVisible(false);
        warning.setIcon(AllIcons.General.Warning);
        warning.setVisible(false);

        instructions.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (!updating && !seeding) {
                    instructionsEdited = true;
                    // The free-form text is the user's task, not the methodology: only a generated seed is edited.
                    if (workflowController != null && !freeForm()) {
                        workflowController.edit(instructions.getText());
                    }
                }
                defaultWorkIn();
                regenerate();
                refresh();
                syncWorkflow();
            }
        });

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("What should the agent do?", ScrollPaneFactory.createScrollPane(instructions),
                        true)
                .addLabeledComponent("Agent:", agents)
                .addLabeledComponent("Model:", modelField)
                .addComponent(tierRows, JBUI.scale(4))
                .addLabeledComponent("Mode:", modes)
                .addComponent(evalMode, JBUI.scale(4))
                .addComponent(workspaceBox, JBUI.scale(4))
                .addComponent(saveForLater, JBUI.scale(4))
                .addComponent(workflowRow(), JBUI.scale(4))
                .addComponent(summaryRow, JBUI.scale(8))
                .addComponent(details)
                .addComponent(warning, JBUI.scale(8))
                .getPanel();
        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension natural = super.getPreferredSize();
                return new Dimension(Math.max(natural.width, JBUI.scale(WIDTH)), natural.height);
            }
        };
        root.add(form, BorderLayout.CENTER);
        return root;
    }

    private JPanel workflowRow() {
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, JBUI.scale(2)));
        sourceLabel.setForeground(UIUtil.getContextHelpForeground());
        rows.add(sourceLabel);
        rows.add(workflowNote);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        actions.add(primaryAction);
        actions.add(secondaryAction);
        primaryAction.setVisible(false);
        secondaryAction.setVisible(false);
        rows.add(actions);
        rows.setVisible(workflow.isPresent());
        return rows;
    }

    private boolean freeForm() {
        return preset.workflowTarget().orElse(null) == WorkflowTarget.FREE_FORM_PRE_PROMPT;
    }

    /** The bypass notice: blank chat and a native one-line command resolve and validate nothing. */
    private Optional<String> bypassNotice() {
        if (workflow.isEmpty() || !freeForm()) {
            return Optional.empty();
        }
        String text = instructions.getText();
        if (text.isBlank()) {
            return Optional.of("Pre-prompt not applied: blank chat");
        }
        return NewTaskPlan.isSlashOneLiner(text) ? Optional.of("Pre-prompt not applied: harness command")
                : Optional.empty();
    }

    /** Whether Start must wait for, and launch with, a prepared workflow (never for save-later or a bypass). */
    private boolean needsWorkflow() {
        return workflow.isPresent() && !saveForLater.isSelected() && bypassNotice().isEmpty();
    }

    /**
     * Reconciles the preparation with the text: leaving a bypass for prose prepares (and so validates) normally;
     * entering a bypass releases whatever was staged. Completions that arrive later are ignored while bypassed.
     */
    private void syncWorkflow() {
        if (workflowController == null) {
            return;
        }
        if (bypassNotice().isPresent()) {
            if (preparationStarted) {
                workflowController.launchFailed();
                preparationStarted = false;
                preparing = false;
                workflowReady = false;
            }
        } else if (!preparationStarted) {
            restartWorkflow();
            return;
        }
        refreshWorkflowLabel();
    }

    private void restartWorkflow() {
        if (workflowController == null || bypassNotice().isPresent()) {
            return;
        }
        Workflow w = workflow.orElseThrow();
        AgentKind kind = selectedAgent();
        preparationStarted = true;
        workflowReady = false;
        defaultOnceChosen = false;
        workflowNote.setText("");
        showActions(null, () -> { });
        workflowController.prepare(w.request().apply(kind),
                new Input(preset.workflowTarget().orElseThrow().name(), kind.name()));
        refreshWorkflowLabel();
    }

    private void refreshWorkflowLabel() {
        if (workflow.isEmpty()) {
            return;
        }
        Workflow w = workflow.get();
        Optional<String> notice = bypassNotice();
        if (notice.isPresent()) {
            sourceLabel.setText(notice.get());
            workflowNote.setText("");
        } else {
            Optional<SourceDescriptor> source = w.source().get();
            Provenance base = defaultOnceChosen ? Provenance.DEFAULT_ONCE
                    : source.isPresent() ? Provenance.PROJECT : Provenance.DEFAULT;
            boolean edited = instructionsEdited && !freeForm();
            SourceDescriptor descriptor = source.orElse(null);
            sourceLabel.setText(edited ? WorkflowSourceLabel.edited(base, w.project(), descriptor)
                    : WorkflowSourceLabel.of(base, w.project(), descriptor));
        }
        updateOk();
    }

    /** Shows the invalid/failed actions ({@code primary} text, then Cancel), or hides them for a null text. */
    private void showActions(String primary, Runnable handler) {
        primaryHandler = handler;
        primaryAction.setText(primary);
        primaryAction.setVisible(primary != null);
        secondaryAction.setVisible(primary != null);
    }

    private void updateOk() {
        boolean saving = saveForLater.isSelected();
        setOKActionEnabled(saving || bypassNotice().isPresent() || (workflowReady && !preparing));
    }

    /** The controller's callbacks; always on the UI executor the launch site supplied. */
    private final class WorkflowUi implements WorkflowInvocationController.Ui {
        @Override
        public ModalityState modality() {
            // Same state the disk check uses (doOKAction): the dialog is modal, so completions
            // must carry the dialog's own modality to run at all while it is showing.
            return ModalityState.stateForComponent(getContentPanel());
        }

        @Override
        public void progress(boolean shown) {
            preparing = shown;
            if (shown) {
                workflowNote.setText("Preparing workflow…");
            } else if (workflowNote.getText().equals("Preparing workflow…")) {
                workflowNote.setText("");
            }
            updateOk();
        }

        @Override
        public void ready(String seedText, boolean edited) {
            if (bypassNotice().isPresent()) {
                return;
            }
            workflowReady = true;
            workflowNote.setText("");
            showActions(null, () -> { });
            if (!freeForm() && !edited && !instructionsEdited && !seedText.isEmpty()
                    && !seedText.equals(instructions.getText())) {
                // `updating` is not reentrant (regenerate() resets it inside the document event), so the seed
                // write has its own flag: a late seed is never a user edit and is never forwarded as one.
                seeding = true;
                try {
                    instructions.setText(seedText);
                } finally {
                    seeding = false;
                }
            }
            refreshWorkflowLabel();
        }

        @Override
        public void invalidSource(WorkflowProblem problem) {
            workflowReady = false;
            // Start stays disabled until the user falls back to the default for this invocation, or cancels.
            workflowNote.setText(problem.configKey() + ": " + problem.message());
            showActions("Use Drydock default for this invocation", () -> {
                defaultOnceChosen = true;
                workflowController.chooseDefaultOnce();
            });
            updateOk();
        }

        @Override
        public void failed(String message) {
            workflowReady = false;
            workflowNote.setText("Workflow preparation failed: " + message);
            showActions("Retry", NewTaskDialog.this::restartWorkflow);
            updateOk();
        }
    }

    private JPanel detailsForm() {
        group(newWorktree, mainCheckout);
        group(thisWindow, ownWindow);
        newWorktree.addActionListener(e -> {
            workInEdited = true;
            refresh();
        });
        mainCheckout.addActionListener(e -> {
            workInEdited = true;
            refresh();
        });

        inputs.startPoints().forEach(start::addItem);
        start.setEditable(true);
        start.setSelectedItem(inputs.defaultStart());
        // No speed search: on an editable combo it moved the selection while
        // the user typed ("old-experiment" jumped to add-farewell-method).
        // Typing is free entry; Choose… is Git's own searchable picker.
        start.addActionListener(e -> refresh());
        // The summary follows the text as it is typed, not only once the
        // combo commits it on Enter or focus loss.
        if (start.getEditor().getEditorComponent() instanceof JTextComponent typed) {
            typed.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent e) {
                    refresh();
                }
            });
        }
        JPanel startRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        startRow.add(start, BorderLayout.CENTER);
        refChooser.ifPresent(chooser -> {
            JButton choose = new JButton("Choose…");
            choose.addActionListener(e -> chooser.get().ifPresent(ref -> {
                start.setSelectedItem(ref);
                refresh();
            }));
            startRow.add(choose, BorderLayout.EAST);
        });

        branch.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (!updating) {
                    branchEdited = true;
                    regenerate();
                }
                refresh();
            }
        });
        folder.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Worktree Folder"));
        folder.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (!updating) {
                    folderEdited = true;
                }
                refresh();
            }
        });

        base.setText("Base " + worktreeBase + " from Settings › Tools › Drydock");
        base.setForeground(UIUtil.getContextHelpForeground());
        HyperlinkLabel changeBase = new HyperlinkLabel("Change base…");
        changeBase.addHyperlinkListener(e -> {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DrydockConfigurable.class);
            // The folder follows the base the user just set, not the one the
            // dialog opened with.
            UserConfig.loadAsync().thenAccept(config -> ApplicationManager.getApplication().invokeLater(() -> {
                worktreeBase = DrydockConfigurable.effectiveBase(config.worktreesDirectory(),
                        Path.of(System.getProperty("user.home")));
                base.setText("Base " + worktreeBase + " from Settings › Tools › Drydock");
                regenerate();
                refresh();
            }, ModalityState.any()));
        });
        JPanel baseRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        baseRow.add(base);
        baseRow.add(Box.createHorizontalStrut(JBUI.scale(6)));
        baseRow.add(changeBase);

        FormBuilder builder = FormBuilder.createFormBuilder();
        if (inputs.fixedWorktree().isEmpty() && preset.fixedWorkIn().isEmpty() && !remoteTarget()) {
            builder.addLabeledComponent("Work in:", row(newWorktree, mainCheckout))
                    .addLabeledComponent("Branch:", branch)
                    .addLabeledComponent("From:", startRow)
                    .addLabeledComponent("Folder:", folder)
                    .addComponentToRightColumn(baseRow);
        }
        return builder.addLabeledComponent("Open in:", row(thisWindow, ownWindow)).getPanel();
    }

    /**
     * A harness switch recomposes the segment prompt only while the user has
     * not edited it (T31a, T09's contract): an unedited generated text moves
     * to the new harness's shape; a user edit survives every switch.
     */
    private void recomposeInstructions() {
        if (preset.composed().isEmpty()) {
            return;
        }
        NewTaskPreset.Composed composed = preset.composed().get();
        String recomposed = NewTaskPreset.switchedInstructions(instructionsEdited, instructions.getText(),
                NewTaskPreset.composedInstructions(selectedAgent(), composed.plan(), composed.segment()));
        if (recomposed.equals(instructions.getText())) {
            return;
        }
        updating = true;
        try {
            instructions.setText(recomposed);
        } finally {
            updating = false;
        }
    }

    /**
     * A blank description is a chat-like task, which needs no branch: Work in
     * defaults to the main checkout, and back to a new worktree once the
     * description returns -- until the user picks explicitly, which stops the
     * following (like the branch and folder fields). A read-only task or a
     * fixed target is pinned to its work-in regardless of the description.
     */
    private void defaultWorkIn() {
        if (workInEdited || preset.fixedWorkIn().isPresent() || inputs.fixedWorktree().isPresent()
                || preset.mode(readOnly.isSelected()) == SessionMode.READ_ONLY || remoteTarget()) {
            return;
        }
        boolean blank = instructions.getText().isBlank();
        mainCheckout.setSelected(blank);
        newWorktree.setSelected(!blank);
    }

    /**
     * Reuse the dialog's generated branch and derive the folder from the current branch.
     * Never overwrite a manual field edit or regenerate the branch when the description changes.
     * The task name labels the tree and tab only, reaching the output through {@link #choice()}.
     */
    private void regenerate() {
        updating = true;
        try {
            if (!branchEdited) {
                branch.setText(generatedBranch);
            }
            if (!folderEdited) {
                folder.setText(NewTaskPlan.folderFor(worktreeBase, inputs.repoName(),
                        branch.getText().strip()).toString());
            }
        } finally {
            updating = false;
        }
    }

    /** Summary, warning, OK label, and the controls the mode allows. */
    private void refresh() {
        boolean saving = saveForLater.isSelected();
        boolean fixed = inputs.fixedWorktree().isPresent();
        newWorktree.setEnabled(startEnabled(saving, !remoteTarget() && preset.fixedWorkIn().isEmpty()
                && preset.mode(readOnly.isSelected()) != SessionMode.READ_ONLY
                && !fixed));
        mainCheckout.setEnabled(startEnabled(saving, preset.fixedWorkIn().isEmpty() && !fixed));
        boolean worktree = workIn() == WorkIn.NEW_WORKTREE;
        branch.setEnabled(startEnabled(saving, worktree));
        start.setEnabled(startEnabled(saving, worktree));
        folder.setEnabled(startEnabled(saving, worktree));
        thisWindow.setEnabled(!saving);
        ownWindow.setEnabled(!saving);
        writes.setEnabled(startEnabled(saving, preset.fixedMode().isEmpty()));
        readOnly.setEnabled(startEnabled(saving, preset.fixedMode().isEmpty()));
        for (Agent agent : inputs.agents()) {
            JBRadioButton button = agentButtons.get(agent.kind());
            if (button != null) {
                button.setEnabled(startEnabled(saving, agent.isAvailable()));
            }
        }
        modelField.setEnabled(!saving);
        refreshWorkspaceBox(saving);
        // After the box gate: if that gate just cleared the box, the eval
        // gate must see it cleared in this same pass.
        boolean evalWasVisible = evalMode.isVisible();
        refreshEvalMode(saving);
        boolean evalVisibilityChanged = evalWasVisible != evalMode.isVisible();
        boolean tiersVisible = !saving && tiersShown(selectedAgent());
        boolean tiersWere = tierRows.isVisible();
        tierRows.setVisible(tiersVisible);
        change.setEnabled(!saving);
        summary.setVisible(!saving);
        Choice choice = choice();
        // Wrapped at a fixed width: as plain text the line grew with the
        // branch and folder names and widened the dialog on every keystroke.
        int before = summary.getPreferredSize().height;
        summary.setText(saving ? ""
                : "<html><div style=\"width:" + JBUI.scale(SUMMARY_WIDTH) + "px\">"
                        + StringUtil.escapeXmlEntities(NewTaskPlan.summary(inputs, choice)) + "</div></html>");
        boolean resized = summary.getPreferredSize().height != before || evalVisibilityChanged;
        Optional<String> caution = NewTaskPlan.warning(inputs, choice);
        if (saving) {
            // The caution renames a warned Start; a Save Task button carries no launch warning.
            caution = Optional.empty();
        }
        warning.setText(caution.orElse(""));
        resized |= warning.isVisible() != caution.isPresent();
        warning.setVisible(caution.isPresent());
        setOKButtonText(saving ? SAVE_OK_LABEL : NewTaskPlan.primaryLabel(caution.isPresent()));
        if ((resized || tiersWere != tiersVisible) && summary.isShowing()) {
            // The summary wrapped onto another line (or back), the warning
            // came or went, or the save-later box hid the launch-only rows:
            // without a re-pack the dialog kept its height and cut off its
            // last row.
            pack();
        }
    }

    /**
     * The eval offer's visibility and enablement. Visible only while the
     * selected harness can actually route eval traffic — a checkbox for a
     * permanently dead offer (e.g. Codex) is a dead control, the same gate
     * pattern the tier rows follow — and enabled only additionally while a
     * launch (not a save-for-later) is being composed and the remote arm is
     * not selected. Runs on every refresh — the agent switch and the
     * workspace-box toggle both come through here — and a gate that fails
     * clears the selection, so a checked box can never outlive the
     * conditions that permitted it.
     */
    private void refreshEvalMode(boolean saving) {
        boolean offered = inputs.evalAvailable().test(selectedAgent()) && !remoteTarget();
        boolean evalOk = offered && !saving && !workspaceBox.isSelected();
        evalMode.setVisible(offered);
        evalMode.setEnabled(evalOk);
        if (!evalOk) {
            evalMode.setSelected(false);
        }
    }

    /**
     * The remote offer's visibility and enablement (T19). Visible only
     * while the opt-in flag is on; enabled only when every condition the
     * remote arm requires holds: a Claude agent, Work in a new worktree or
     * the main checkout, no fixed worktree, no binding-carrying preset, a
     * non-fish login shell, and a project that is not itself an SSH remote.
     * A gate that fails clears the selection, so a checked box can never
     * outlive the conditions that permitted it.
     */
    private void refreshWorkspaceBox(boolean saving) {
        boolean flagOn = WorkspaceSettings.enabled();
        workspaceBox.setVisible(flagOn);
        // A composed preset is the single-project start, whose confirmed
        // choice launches through the binding-carrying staged chain -- the
        // dialog's only path whose launch runs pre-spawn task bindings, and
        // the remote arm refuses bindings outright.
        boolean gatesHold = flagOn && preset.composed().isEmpty()
                && inputs.fixedWorktree().isEmpty() && !fishLoginShell(loginShell)
                && inputs.project().remote().isEmpty();
        boolean selectable = gatesHold && selectedAgent() == AgentKind.CLAUDE
                && (workIn() == WorkIn.NEW_WORKTREE || workIn() == WorkIn.MAIN_CHECKOUT);
        workspaceBox.setEnabled(startEnabled(saving, selectable));
        if (!selectable) {
            workspaceBox.setSelected(false);
        }
    }

    /**
     * Whether {@code shell} is fish: fish sets variables with {@code set},
     * so it cannot carry the remote plan's nested POSIX quoting, and v1
     * offers no remote launch under it. Mirrors {@code ShellCommandAdapter}'s
     * own check of the same {@code $SHELL} value.
     */
    private static boolean fishLoginShell(String shell) {
        return shell != null && shell.substring(shell.lastIndexOf('/') + 1).equals("fish");
    }

    /** Whether the dialog's target project lives on a remote host over SSH. */
    private boolean remoteTarget() {
        return inputs.project().isRemote();
    }

    private WorkIn workIn() {
        if (remoteTarget()) {
            return WorkIn.MAIN_CHECKOUT;
        }
        if (preset.fixedWorkIn().isPresent()) {
            return preset.fixedWorkIn().get();
        }
        if (inputs.fixedWorktree().isPresent()) {
            return WorkIn.EXISTING_WORKTREE;
        }
        return preset.mode(readOnly.isSelected()) == SessionMode.READ_ONLY
                || mainCheckout.isSelected() ? WorkIn.MAIN_CHECKOUT : WorkIn.NEW_WORKTREE;
    }

    Choice choice() {
        WorkIn workIn = workIn();
        AgentKind agent = selectedAgent();
        String chosenBranch = switch (workIn) {
            case EXISTING_WORKTREE -> inputs.fixedWorktree().orElseThrow().branch();
            case MAIN_CHECKOUT -> "";
            case NEW_WORKTREE -> branch.getText().strip();
        };
        Path chosenFolder = switch (workIn) {
            case EXISTING_WORKTREE -> inputs.fixedWorktree().orElseThrow().dir();
            case MAIN_CHECKOUT -> inputs.project().root();
            case NEW_WORKTREE -> Path.of(folder.getText().strip().isEmpty() ? "." : folder.getText().strip());
        };
        String text = instructions.getText();
        Optional<ModelTiers> tiers = tiersShown(agent)
                ? Optional.of(new ModelTiers(strongField.selected(), midField.selected(), lowField.selected()))
                : Optional.empty();
        return new Choice(taskName(), text.isBlank() ? Optional.empty() : Optional.of(text), agent,
                preset.mode(readOnly.isSelected()), workIn, chosenBranch,
                startPoint(), chosenFolder,
                ownWindow.isSelected() ? OpenIn.OWN_WINDOW : OpenIn.THIS_WINDOW,
                modelField.selected(), tiers, tiers.flatMap(this::subagentModelRule),
                workspaceBox.isSelected(), preset.targetPullRequest(), evalMode.isSelected());
    }

    /**
     * Whether the tier rows show for {@code kind}: the preset names subagent
     * roles and the harness dispatches subagents.
     */
    private boolean tiersShown(AgentKind kind) {
        return preset.tierRoles().isPresent() && inputs.supportsSubagents().test(kind);
    }

    /**
     * The launch's subagent model rules, composed while every ingredient is
     * at hand: the preset's role mapping (with its {STRONG}/{MID}/{LOW}
     * placeholders), the tiers, the harness's subagent capability, and the
     * starting model an empty tier resolves to. Empty when the preset names
     * no roles, the harness cannot dispatch subagents, or no tier was set --
     * rules that bind nobody are noise in the prompt.
     */
    private Optional<String> subagentModelRule(ModelTiers tiers) {
        if (preset.tierRoles().isEmpty() || tiers.isEmpty()
                || !inputs.supportsSubagents().test(selectedAgent())) {
            return Optional.empty();
        }
        Optional<String> starting = modelField.selected();
        String assignments = preset.tierRoles().get()
                .replace("{STRONG}", ReviewInstructions.tierModel(tiers.resolved(ModelTiers.Tier.STRONG, starting)))
                .replace("{MID}", ReviewInstructions.tierModel(tiers.resolved(ModelTiers.Tier.MID, starting)))
                .replace("{LOW}", ReviewInstructions.tierModel(tiers.resolved(ModelTiers.Tier.LOW, starting)));
        return Optional.of(ReviewInstructions.subagentModelRules(assignments));
    }

    /** Points the three tier pickers at {@code kind}, seeded from the repository's last-used tiers. */
    private void setTierFields(AgentKind kind) {
        strongField.setAgent(kind, k -> inputs.lastUsedTiers().apply(k).strong());
        midField.setAgent(kind, k -> inputs.lastUsedTiers().apply(k).mid());
        lowField.setAgent(kind, k -> inputs.lastUsedTiers().apply(k).low());
    }

    /** One tier picker with its label; the label rides the row so hiding the panel hides both. */
    private static JPanel tierRow(String label, ModelField field) {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        JBLabel name = new JBLabel(label);
        name.setPreferredSize(new Dimension(JBUI.scale(110), name.getPreferredSize().height));
        panel.add(name, BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    /** The selected harness, falling back to the preselection like the launch would. */
    private AgentKind selectedAgent() {
        return agentButtons.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(inputs.preselected());
    }

    /** Diagnostic-only: types into the description, through the real document listener. */
    void diagSetInstructions(String text) {
        instructions.setText(text);
    }

    /** Diagnostic-only: types into the model picker, through the real document listener. */
    void diagSetModel(String text) {
        modelField.diagType(text);
    }

    /** Diagnostic-only: replaces the login shell and refreshes, as a shell change would. */
    void diagSetLoginShell(String shell) {
        loginShell = shell;
        refresh();
    }

    /** The immutable submission captured before the dialog closed. */
    Choice acceptedChoice() {
        if (acceptedChoice == null) {
            throw new IllegalStateException("The task dialog has not accepted a choice");
        }
        return acceptedChoice;
    }

    /**
     * The accepted submission of either mode -- a launch choice or a saved
     * task's values (T19a); refuses before a confirmed OK.
     */
    NewTaskLauncherResult launcherResult() {
        if (acceptedResult == null) {
            throw new IllegalStateException("The task dialog has not accepted a result");
        }
        return acceptedResult;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (!validatesStart(saveForLater.isSelected())) {
            // Start validation polices a launch -- the agent, the branch, the
            // folder; a saved task prepares none of that, so nothing blocks
            // Save Task, not even with zero agents installed.
            return null;
        }
        // A blank description no longer refuses: it names the task "New task"
        // and starts the agent at its own prompt -- a chat, not a task.
        // Folder occupancy is a disk read; it is checked on OK only, not on
        // every keystroke.
        return NewTaskPlan.refusal(inputs, choice(), ignored -> false)
                .map(message -> new ValidationInfo(message, branch))
                .orElse(null);
    }

    /**
     * Freezes the prepared workflow for Start: false (nothing launches) while a needed preparation is not ready. A bypass or save-later accepts nothing and releases any staged lease.
     */
    boolean acceptWorkflowForStart() {
        if (workflowController == null) {
            return true;
        }
        if (!needsWorkflow()) {
            workflowController.launchFailed();
            return true;
        }
        acceptedWorkflow = workflowController.accept();
        return acceptedWorkflow.isPresent();
    }

    /** The frozen workflow of the accepted Start; the caller owns {@link #workflowController()} from then on. */
    Optional<Accepted<Prepared>> acceptedWorkflow() {
        return acceptedWorkflow;
    }

    WorkflowInvocationController workflowController() {
        return workflowController;
    }

    String sourceLabelText() {
        return sourceLabel.getText();
    }

    /** Diagnostic-only: whether Start is currently enabled. */
    boolean diagStartEnabled() {
        return getOKAction().isEnabled();
    }

    @Override
    public void doCancelAction() {
        if (workflowController != null) {
            workflowController.cancel();
        }
        super.doCancelAction();
    }

    @Override
    protected void doOKAction() {
        if (saveForLater.isSelected()) {
            // The save writes asynchronously after the dialog closes, through
            // the task service (TaskLauncher.saveTask); OK only captures what
            // was accepted. This dialog has no prerequisite picker and no
            // done_by control, so the save carries no refs and the default
            // HUMAN policy.
            acceptedResult = NewTaskLauncherResult.savedTask(
                    new app.drydock.tasks.NewTaskPlan(taskName(), instructions.getText(), List.of(),
                            DoneBy.HUMAN, inputs.project().root(), inputs.repoName()));
            close(OK_EXIT_CODE);
            return;
        }
        // The folder check reads the disk, so it runs off the EDT; OK stays
        // disabled meanwhile, and the dialog closes (or says why not) when
        // the answer comes back.
        Choice choice = choice();
        getOKAction().setEnabled(false);
        ModalityState modality = ModalityState.stateForComponent(getContentPanel());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Optional<String> refusal = NewTaskPlan.refusal(inputs, choice, NewTaskDialog::occupied);
            if (refusal.isEmpty() && preset.instructionsRequired() && choice.instructions().isEmpty()) {
                refusal = Optional.of("Give the agent implementation instructions.");
            }
            Optional<String> finalRefusal = refusal;
            ApplicationManager.getApplication().invokeLater(() -> {
                getOKAction().setEnabled(true);
                if (finalRefusal.isPresent()) {
                    setErrorText(finalRefusal.get(),
                            preset.instructionsRequired() && choice.instructions().isEmpty() ? instructions : folder);
                } else if (isShowing()) {
                    // Accept only now, after the disk refusal: the controller's cancel is terminal and begin()
                    // throws once ACCEPTED, so an earlier accept would strand a refused Start.
                    if (!acceptWorkflowForStart()) {
                        setErrorText("The workflow is not ready; wait for the preparation or cancel.");
                        return;
                    }
                    acceptedChoice = choice;
                    acceptedResult = NewTaskLauncherResult.launched(choice);
                    close(OK_EXIT_CODE);
                }
            }, modality);
        });
    }

    /**
     * The start point as shown: the editor's text, since a value typed into
     * the editable combo is only committed to its selection on Enter or
     * focus loss, and Start pressed straight after typing would miss it.
     */
    private String startPoint() {
        Object typed = start.getEditor().getItem();
        return String.valueOf(typed != null ? typed : start.getSelectedItem()).strip();
    }

    /** The preset's name when the entry point supplies one; otherwise the name inferred from the description. */
    private String taskName() {
        return preset.name().isEmpty() ? NewTaskPlan.nameFor(instructions.getText()) : preset.name();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return instructions;
    }

    private static boolean occupied(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Files.exists(directory);
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            return true;
        }
    }

    private static JPanel row(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (JComponent component : components) {
            row.add(component);
            row.add(Box.createHorizontalStrut(JBUI.scale(12)));
        }
        return row;
    }

    private static void group(JBRadioButton... buttons) {
        ButtonGroup group = new ButtonGroup();
        for (JBRadioButton button : buttons) {
            group.add(button);
        }
    }
}
