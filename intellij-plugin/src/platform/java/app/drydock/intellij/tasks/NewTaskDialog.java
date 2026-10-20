package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.config.UserConfig;
import app.drydock.intellij.settings.DrydockConfigurable;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * New task: a name, optional instructions, an agent and a mode. Everything
 * else — branch, start point, folder, window — is generated and folded into
 * one summary line; {@code Change…} unfolds it for the rare edit.
 *
 * <p>The decisions are {@link NewTaskPlan}'s; this class only lays them
 * out and keeps the generated values following the name until the user
 * edits them.</p>
 */
final class NewTaskDialog extends DialogWrapper {

    private static final int WIDTH = 540;

    /** The summary line wraps here, leaving room for its Change… link. */
    private static final int SUMMARY_WIDTH = 420;

    private final Project project;
    private final NewTaskPlan.Inputs inputs;
    private final Optional<Supplier<Optional<String>>> refChooser;
    private final NewTaskPreset preset;

    private final JBTextField name = new JBTextField();
    private final JBTextArea instructions = new JBTextArea(4, 40);
    private final Map<AgentKind, JBRadioButton> agentButtons = new LinkedHashMap<>();
    private final JBRadioButton writes = new JBRadioButton("Writes", true);
    private final JBRadioButton readOnly = new JBRadioButton("Read-only");
    private final JBLabel summary = new JBLabel();
    private final HyperlinkLabel change = new HyperlinkLabel("Change…");
    private final JPanel details = new JPanel(new BorderLayout());
    private final JBRadioButton newWorktree = new JBRadioButton("New worktree", true);
    private final JBRadioButton mainCheckout = new JBRadioButton("Main checkout");
    private final JBTextField branch = new JBTextField();
    private final ComboBox<String> start = new ComboBox<>();
    private final TextFieldWithBrowseButton folder = new TextFieldWithBrowseButton();
    private final JBRadioButton newWindow = new JBRadioButton("New window", true);
    private final JBRadioButton thisWindow = new JBRadioButton("This window");
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
        name.setText(preset.name());
        instructions.setText(preset.instructions().orElse(""));
        setTitle("New task in " + inputs.repoName());
        init();
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

        JPanel modes;
        if (preset.writableOnly()) {
            modes = row(new JBLabel("Writes"));
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

        name.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                regenerate();
                refresh();
            }
        });

        JPanel form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Task:", name)
                .addLabeledComponent("What should the agent do?", ScrollPaneFactory.createScrollPane(instructions),
                        true)
                .addLabeledComponent("Agent:", agents)
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
        root.add(form, BorderLayout.NORTH);
        return root;
    }

    private JPanel detailsForm() {
        group(newWorktree, mainCheckout);
        group(newWindow, thisWindow);
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
        if (inputs.fixedWorktree().isEmpty()) {
            builder.addLabeledComponent("Work in:", row(newWorktree, mainCheckout))
                    .addLabeledComponent("Branch:", branch)
                    .addLabeledComponent("From:", startRow)
                    .addLabeledComponent("Folder:", folder)
                    .addComponentToRightColumn(baseRow);
        }
        return builder.addLabeledComponent("Open in:", row(newWindow, thisWindow)).getPanel();
    }

    /** Branch and folder follow the name until the user edits them. */
    private void regenerate() {
        updating = true;
        try {
            if (!branchEdited) {
                branch.setText(NewTaskPlan.branchFor(name.getText(), inputs.takenBranches()));
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
        newWorktree.setEnabled(!readOnly.isSelected() && !fixed);
        mainCheckout.setEnabled(!fixed);
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
        if (inputs.fixedWorktree().isPresent()) {
            return WorkIn.EXISTING_WORKTREE;
        }
        return readOnly.isSelected() || mainCheckout.isSelected() ? WorkIn.MAIN_CHECKOUT : WorkIn.NEW_WORKTREE;
    }

    Choice choice() {
        WorkIn workIn = workIn();
        AgentKind agent = agentButtons.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(inputs.preselected());
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
        return new Choice(name.getText().strip(), text.isBlank() ? Optional.empty() : Optional.of(text), agent,
                preset.mode(readOnly.isSelected()), workIn, chosenBranch,
                startPoint(), chosenFolder,
                thisWindow.isSelected() ? OpenIn.THIS_WINDOW : OpenIn.NEW_WINDOW);
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
        // Folder occupancy is a disk read; it is checked on OK only, not on
        // every keystroke.
        return NewTaskPlan.refusal(inputs, choice(), ignored -> false)
                .map(message -> new ValidationInfo(message, name.getText().isBlank() ? name : branch))
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

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return name;
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
