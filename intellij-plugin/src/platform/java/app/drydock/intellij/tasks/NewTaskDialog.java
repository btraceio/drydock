package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.config.UserConfig;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.settings.DrydockConfigurable;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.review.ReviewInstructions;

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
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * New task: a description, an agent and a mode; the task name is inferred
 * from the description (a preset's name wins when the entry point supplies
 * one). Everything else — branch, start point, folder, window — is generated
 * and folded into one summary line; {@code Change…} unfolds it for the rare
 * edit.
 *
 * <p>The decisions are {@link NewTaskPlan}'s; this class only lays them
 * out and keeps the generated values following the inferred name until the
 * user edits them.</p>
 */
final class NewTaskDialog extends DialogWrapper {

    private static final int WIDTH = 540;

    /** The summary line wraps here, leaving room for its Change… link. */
    private static final int SUMMARY_WIDTH = 420;

    private final Project project;
    private final NewTaskPlan.Inputs inputs;
    private final Optional<Supplier<Optional<String>>> refChooser;
    private final NewTaskPreset preset;

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

    /** Where generated folders go; starts as the inputs' and follows "Change base…". */
    private Path worktreeBase;

    /** True once the user edits the branch or the folder; then the name stops rewriting it. */
    private boolean branchEdited;
    private boolean folderEdited;
    /** Programmatic updates must not count as the user's edits. */
    private boolean updating;

    /**
     * The exact values validated by the successful Start click. DialogWrapper
     * disposes its peer before {@code showAndGet()} returns, so the caller must
     * not reconstruct a choice from Swing fields after the modal has closed.
     */
    private Choice acceptedChoice;

    /**
     * {@code refChooser} opens Git's own branch/tag picker when the
     * project's repository is open in some window; empty otherwise.
     */
    NewTaskDialog(Project project, NewTaskPlan.Inputs inputs, Optional<Supplier<Optional<String>>> refChooser) {
        this(project, inputs, refChooser, NewTaskPreset.empty());
    }

    NewTaskDialog(Project project, NewTaskPlan.Inputs inputs, Optional<Supplier<Optional<String>>> refChooser,
                  NewTaskPreset preset) {
        super(project, true);
        this.project = project;
        this.inputs = inputs;
        this.refChooser = refChooser;
        this.preset = preset;
        this.worktreeBase = inputs.worktreeBase();
        instructions.setText(preset.instructions().orElse(""));
        if (preset.fixedMode().orElse(null) == SessionMode.READ_ONLY) {
            readOnly.setSelected(true);
            mainCheckout.setSelected(true);
        }
        if (preset.fixedWorkIn().orElse(null) == WorkIn.MAIN_CHECKOUT) {
            mainCheckout.setSelected(true);
        }
        setTitle("New task in " + inputs.repoName());
        ownWindow.setSelected(preset.openIn() == OpenIn.OWN_WINDOW);
        thisWindow.setSelected(preset.openIn() == OpenIn.THIS_WINDOW);
        init();
        setResizable(true);
        regenerate();
        refresh();
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
            button.setEnabled(agent.isAvailable());
            if (!agent.isAvailable()) {
                button.setToolTipText("Not found on PATH: " + agent.describeSearched());
            }
            button.setSelected(agent.kind() == inputs.preselected() && agent.isAvailable());
            agentGroup.add(button);
            agentButtons.put(agent.kind(), button);
            agents.add(button);
            agents.add(Box.createHorizontalStrut(JBUI.scale(12)));
        }
        if (agentButtons.values().stream().noneMatch(JBRadioButton::isSelected)) {
            agentButtons.values().stream().filter(JBRadioButton::isEnabled).findFirst()
                    .ifPresent(button -> button.setSelected(true));
        }
        // The model picker follows the selected harness: switching agents and
        // back restores what the editor held for each (seeded from the
        // repository's last-used model), and each harness's suggestion load
        // runs once.
        modelField = new ModelField(inputs.modelSuggestions());
        modelField.setAgent(selectedAgent(), inputs.lastUsedModel());
        agentButtons.forEach((kind, button) -> button.addActionListener(
                e -> modelField.setAgent(kind, inputs.lastUsedModel())));

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
                regenerate();
                refresh();
            }
        });

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("What should the agent do?", ScrollPaneFactory.createScrollPane(instructions),
                        true)
                .addLabeledComponent("Agent:", agents)
                .addLabeledComponent("Model:", modelField)
                .addComponent(tierRows, JBUI.scale(4))
                .addLabeledComponent("Mode:", modes)
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

    private JPanel detailsForm() {
        group(newWorktree, mainCheckout);
        group(thisWindow, ownWindow);
        newWorktree.addActionListener(e -> refresh());
        mainCheckout.addActionListener(e -> refresh());

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
        if (inputs.fixedWorktree().isEmpty() && preset.fixedWorkIn().isEmpty()) {
            builder.addLabeledComponent("Work in:", row(newWorktree, mainCheckout))
                    .addLabeledComponent("Branch:", branch)
                    .addLabeledComponent("From:", startRow)
                    .addLabeledComponent("Folder:", folder)
                    .addComponentToRightColumn(baseRow);
        }
        return builder.addLabeledComponent("Open in:", row(thisWindow, ownWindow)).getPanel();
    }

    /**
     * Branch and folder follow the inferred name until the user edits them.
     * The name itself is never shown here: it labels the task in the tree and
     * on its tab, and reaches this dialog's output only through {@link #choice()}.
     */
    private void regenerate() {
        updating = true;
        try {
            if (!branchEdited) {
                branch.setText(NewTaskPlan.branchFor(taskName(), inputs.takenBranches()));
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
        boolean fixed = inputs.fixedWorktree().isPresent();
        newWorktree.setEnabled(preset.fixedWorkIn().isEmpty()
                && preset.mode(readOnly.isSelected()) != SessionMode.READ_ONLY
                && !fixed);
        mainCheckout.setEnabled(preset.fixedWorkIn().isEmpty() && !fixed);
        boolean worktree = workIn() == WorkIn.NEW_WORKTREE;
        branch.setEnabled(worktree);
        start.setEnabled(worktree);
        folder.setEnabled(worktree);
        Choice choice = choice();
        // Wrapped at a fixed width: as plain text the line grew with the
        // branch and folder names and widened the dialog on every keystroke.
        int before = summary.getPreferredSize().height;
        summary.setText("<html><div style=\"width:" + JBUI.scale(SUMMARY_WIDTH) + "px\">"
                + StringUtil.escapeXmlEntities(NewTaskPlan.summary(inputs, choice)) + "</div></html>");
        boolean resized = summary.getPreferredSize().height != before;
        Optional<String> caution = NewTaskPlan.warning(inputs, choice);
        warning.setText(caution.orElse(""));
        resized |= warning.isVisible() != caution.isPresent();
        warning.setVisible(caution.isPresent());
        setOKButtonText(NewTaskPlan.primaryLabel(caution.isPresent()));
        if (resized && summary.isShowing()) {
            // The summary wrapped onto another line (or back), or the warning
            // came or went: without a re-pack the dialog kept its height and
            // cut off its last row.
            pack();
        }
    }

    private WorkIn workIn() {
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
                modelField.selected(), tiers, tiers.flatMap(this::subagentModelRule));
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

    /** The immutable submission captured before the dialog closed. */
    Choice acceptedChoice() {
        if (acceptedChoice == null) {
            throw new IllegalStateException("The task dialog has not accepted a choice");
        }
        return acceptedChoice;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (preset.name().isEmpty() && instructions.getText().isBlank()) {
            // Without a description there is nothing to infer a name from.
            return new ValidationInfo("Describe what the agent should do.", instructions);
        }
        // Folder occupancy is a disk read; it is checked on OK only, not on
        // every keystroke.
        return NewTaskPlan.refusal(inputs, choice(), ignored -> false)
                .map(message -> new ValidationInfo(message, branch))
                .orElse(null);
    }

    @Override
    protected void doOKAction() {
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
                    acceptedChoice = choice;
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
