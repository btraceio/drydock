package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.StartPlan.Segment;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.UIUtil;

import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The multi-stage confirmation dialog (T37a, D8/D16/P4.5). It DISPLAYS the
 * staged plan StartPlanner produced -- stages, segments, task edges,
 * context, follow-ups -- and never re-derives any of it. Per segment it
 * collects one launch choice through the EXISTING New Session dialog: the
 * {@link SegmentConfigurator} seam opens that dialog and returns the
 * accepted {@link Choice} ONLY -- never a started session, and this dialog
 * duplicates no field editor. Per task it stages an agent-may-close
 * checkbox (default HUMAN unless the task already stores agent). A
 * confirmed OK freezes everything into one immutable
 * {@link PlanLaunchChoices}; Cancel is capture-only -- no file write, no
 * reservation, no session start (this class performs no I/O at all, and
 * the writes happen only after the caller persisted the confirmation,
 * T38's business). The confirmation is informed: the explanation names the
 * parallel first stage, the automatic stage advance and the target's own
 * auto-start, and OK stays blocked until every segment is configured.
 *
 * <p>The whole decision state lives in {@link Staging}, Swing-free, so the
 * artifact smoke drives the dialog's real code paths in a plain JVM; the
 * Swing layer (rendering, keyboard, on-screen behavior) is the platform
 * fixture T37b owns. Keyboard: real focusable OK/Cancel buttons, Esc
 * cancels (DialogWrapper), the first Configure button gets initial focus.</p>
 */
public final class StartPlanDialog extends DialogWrapper {

    /**
     * The Configure seam: opens the EXISTING New Session dialog for one
     * segment and returns the accepted launch choice ONLY -- never a started
     * session (P4.5: this dialog produces choices; launching is
     * the launch chain's business at execution time, never from here). An
     * empty return (Configure canceled) changes nothing.
     */
    @FunctionalInterface
    public interface SegmentConfigurator {
        Optional<Choice> configure(Segment segment);
    }

    private final StartPlan plan;
    private final SegmentConfigurator configurator;
    private final Staging staging;
    private final Map<RepositoryId, JBLabel> choiceLabels = new LinkedHashMap<>();
    private JButton firstFocus;
    /** The exact choices a confirmed OK accepted, captured before the peers go. */
    private PlanLaunchChoices accepted;

    /** One dialog over one staged plan; the caller built the plan off the
      * EDT (one scan) and owns the Configure seam. */
    public StartPlanDialog(StartPlan plan, SegmentConfigurator configurator) {
        super(true);
        this.plan = Objects.requireNonNull(plan, "plan");
        this.configurator = Objects.requireNonNull(configurator, "configurator");
        this.staging = new Staging(plan);
        setTitle("Confirm plan for " + plan.target().text());
        init();
        setResizable(true);
        setOKButtonText("Start stage 1");
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBLabel explanation = new JBLabel("<html><div style=\"width:520px\">"
                + StringUtil.escapeXmlEntities(explanation(plan)) + "</div></html>");
        explanation.setForeground(UIUtil.getContextHelpForeground());
        FormBuilder builder = FormBuilder.createFormBuilder().addComponent(explanation);
        if (!plan.startable()) {
            JBLabel refused = new JBLabel(String.join("  ", plan.refusals().stream()
                    .map(StartPlan.Refusal::reason).toList()));
            refused.setForeground(UIUtil.getErrorForeground());
            builder.addComponent(refused);
        }
        for (StartPlan.Stage stage : plan.stages()) {
            builder.addComponent(header("Stage " + stage.number()));
            for (Segment segment : stage.segments()) {
                builder.addComponent(segmentRow(segment));
                for (TaskDocument task : segment.tasks()) {
                    builder.addComponent(taskRow(task));
                }
            }
        }
        if (!plan.context().isEmpty()) {
            builder.addComponent(header("Already satisfied (context, not work)"));
            plan.context().forEach(item -> builder.addComponent(
                    indented("- " + item.title() + "  [" + item.id().text() + "]")));
        }
        if (!plan.followUps().isEmpty()) {
            builder.addComponent(header("Follow-ups of the target (kept in mind, not started)"));
            plan.followUps().forEach(item -> builder.addComponent(
                    indented("- " + item.title() + "  [" + item.id().text() + "]")));
        }
        return builder.getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return firstFocus;
    }

    /** OK stays blocked while anything is unconfigured (P4.5). */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        return staging.refusal().map(ValidationInfo::new).orElse(null);
    }

    /** Freezes the staged choices BEFORE the wrapper disposes its Swing peers. */
    @Override
    protected void doOKAction() {
        accepted = staging.capture();
        super.doOKAction();
    }

    /** The accepted choices; refuses before a confirmed OK. */
    public PlanLaunchChoices choices() {
        if (accepted == null) {
            throw new IllegalStateException("The plan dialog has not accepted its choices");
        }
        return accepted;
    }

    /** The one code path the Configure button drives: the seam is asked,
      * the answer is staged, the summary follows. A canceled Configure
      * leaves every staged value exactly as it was. */
    private void configure(Segment segment) {
        configurator.configure(segment).ifPresent(choice -> staging.configure(segment, choice));
        refreshChoice(segment);
    }

    /** The informed-confirmation text (D8/D16/P4.5), one static truth. */
    static String explanation(StartPlan plan) {
        return "Confirming starts the plan now: every segment of stage 1 launches in parallel,"
                + " each finished stage starts the next by itself, and the target '"
                + titleOf(plan, plan.target()) + "' starts last, on its own."
                + " Cancel changes and starts nothing.";
    }

    private JComponent segmentRow(Segment segment) {
        JButton configure = new JButton("Configure...");
        if (firstFocus == null) {
            firstFocus = configure;
        }
        JBLabel choice = new JBLabel();
        choice.setForeground(UIUtil.getContextHelpForeground());
        choiceLabels.put(segment.repository(), choice);
        configure.addActionListener(e -> configure(segment));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(new JBLabel(segmentName(segment)));
        row.add(Box.createHorizontalStrut(12));
        row.add(configure);
        row.add(Box.createHorizontalStrut(12));
        row.add(choice);
        refreshChoice(segment);
        return row;
    }

    private JComponent taskRow(TaskDocument task) {
        JCheckBox close = new JCheckBox("Agent may close");
        close.setSelected(staging.agentMayClose(task.id()));
        close.setToolTipText("Done needs a human unless checked: checked, an agent's done lands"
                + " directly instead of in review; dropping stays human-only either way (D16).");
        close.addActionListener(e -> staging.setMayClose(task.id(), close.isSelected()));
        String after = task.after().isEmpty() ? "" : "  needs " + String.join(", ", task.after());
        String targetMark = task.id().equals(plan.target()) ? "  TARGET" : "";
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(Box.createHorizontalStrut(16));
        row.add(close);
        row.add(Box.createHorizontalStrut(8));
        row.add(new JBLabel("- " + task.title() + "  [" + task.id().text() + "]" + targetMark + after));
        return row;
    }

    private void refreshChoice(Segment segment) {
        Choice choice = staging.configured(segment.repository());
        choiceLabels.get(segment.repository())
                .setText(choice == null ? "Not configured yet" : describe(choice));
    }

    private static JComponent header(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(JBFont.label().asBold());
        return label;
    }

    private static JComponent indented(String text) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(Box.createHorizontalStrut(16));
        row.add(new JBLabel(text));
        return row;
    }

    private static String segmentName(Segment segment) {
        return "Project " + segment.projectName() + " (" + segment.tasks().size()
                + (segment.tasks().size() == 1 ? " task)" : " tasks)");
    }

    /** The target's title as the plan carries it; its id when not found. */
    private static String titleOf(StartPlan plan, TaskId id) {
        for (Segment segment : plan.segments()) {
            for (TaskDocument task : segment.tasks()) {
                if (task.id().equals(id)) {
                    return task.title();
                }
            }
        }
        return id.text();
    }

    /** One configured segment's summary: agent, model, tiers, mode,
      * branch/directory and window -- every field Configure fixed. */
    private static String describe(Choice choice) {
        List<String> parts = new ArrayList<>();
        parts.add(choice.agent().persistedName());
        choice.model().ifPresent(model -> parts.add("model " + model));
        choice.tiers().ifPresent(tiers -> parts.add("subagent tiers " + tiers));
        parts.add(choice.mode() == SessionMode.WRITES ? "full access" : "read-only");
        switch (choice.workIn()) {
            case NEW_WORKTREE -> parts.add("branch " + choice.branch() + " in " + choice.folder());
            case MAIN_CHECKOUT -> parts.add("main checkout");
            case EXISTING_WORKTREE -> parts.add("worktree " + choice.folder());
        }
        parts.add(choice.openIn() == OpenIn.OWN_WINDOW ? "own window" : "this window");
        return String.join(" | ", parts);
    }

    /**
     * The dialog's whole staged state, Swing-free (T37a): the per-segment
     * choices the Configure seam returned, and the per-task agent-may-close
     * staging -- every checkbox change lands HERE, in dialog state, and is
     * written nowhere until a confirmed OK freezes it into a
     * {@link PlanLaunchChoices}; a canceled dialog writes nothing at all.
     * Constructing this state machine is the artifact smoke's seam into the
     * dialog's real code paths; the Swing layer above stays T37b's fixture.
     */
    static final class Staging {

        private final StartPlan plan;
        private final Map<RepositoryId, Choice> configured = new LinkedHashMap<>();
        private final Map<TaskId, Boolean> agentMayClose = new LinkedHashMap<>();

        Staging(StartPlan plan) {
            this.plan = Objects.requireNonNull(plan, "plan");
            for (Segment segment : plan.segments()) {
                for (TaskDocument task : segment.tasks()) {
                    agentMayClose.put(task.id(), task.doneBy() == DoneBy.AGENT);
                }
            }
        }

        /** The visible reason OK is blocked; empty when the plan may confirm (P4.5). */
        Optional<String> refusal() {
            if (!plan.startable()) {
                return Optional.of("The plan cannot start: " + plan.refusals().get(0).reason());
            }
            List<String> missing = new ArrayList<>();
            for (Segment segment : plan.segments()) {
                if (!configured.containsKey(segment.repository())) {
                    missing.add(segmentName(segment));
                }
            }
            return missing.isEmpty() ? Optional.empty()
                    : Optional.of("Configure every segment before confirming - missing: "
                            + String.join(", ", missing));
        }

        /** Stages one segment's confirmed Configure answer. */
        void configure(Segment segment, Choice choice) {
            Objects.requireNonNull(segment, "segment");
            Objects.requireNonNull(choice, "choice");
            configured.put(segment.repository(), choice);
        }

        /** The staged choice of one segment; null while unconfigured. */
        Choice configured(RepositoryId repository) {
            return configured.get(Objects.requireNonNull(repository, "repository"));
        }

        /** Stages one checkbox change; it writes nowhere until a confirmed OK. */
        void setMayClose(TaskId task, boolean agent) {
            agentMayClose.put(Objects.requireNonNull(task, "task"), agent);
        }

        /** The staged box state of one task; default from the stored done_by (D16). */
        boolean agentMayClose(TaskId task) {
            return agentMayClose.getOrDefault(Objects.requireNonNull(task, "task"), false);
        }

        /** Freezes the staged state; only a confirmed OK calls this. */
        PlanLaunchChoices capture() {
            Map<TaskId, DoneBy> doneBy = new LinkedHashMap<>();
            agentMayClose.forEach((task, agent) -> doneBy.put(task,
                    agent ? DoneBy.AGENT : DoneBy.HUMAN));
            return new PlanLaunchChoices(plan.target(),
                    plan.stages().stream().map(StartPlan.Stage::number).toList(),
                    Map.copyOf(configured), doneBy);
        }
    }
}
