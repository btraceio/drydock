package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.StartPlan.Segment;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
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
import java.util.concurrent.CompletableFuture;

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

    private static final System.Logger LOG = System.getLogger(StartPlanDialog.class.getName());

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

    /**
     * The Configure seam's ASYNC shape (T38, a coordinator-granted widening):
     * the production seam runs the New Session dialog's git preparation OFF
     * the EDT before its dialog can open, so the answer arrives as a future
     * that completes on every path -- empty when the configuration was
     * canceled, refused, or the parent was disposed mid-preparation. As with
     * the synchronous seam, the answer is a {@link Choice} ONLY, never a
     * started session, and a completed-empty future changes nothing.
     */
    @FunctionalInterface
    public interface AsyncSegmentConfigurator {

        /** One segment's launch choice when it arrives, over the given parent
          * dialog (the modality its nested dialog opens in); empty when it did
          * not land. Must complete on every path. */
        CompletableFuture<Optional<Choice>> configure(Segment segment, DialogWrapper parent);
    }

    private final StartPlan plan;
    private final AsyncSegmentConfigurator configurator;
    private final Staging staging;
    /** The Configure answers still outstanding: an outstanding answer keeps OK
      * blocked, and a canceled parent completes them empty -- dispose-safe, no write. */
    private final List<CompletableFuture<Optional<Choice>>> outstanding = new ArrayList<>();
    private final Map<RepositoryId, JBLabel> choiceLabels = new LinkedHashMap<>();
    private JButton firstFocus;
    /** The exact choices a confirmed OK accepted, captured before the peers go. */
    private PlanLaunchChoices accepted;

    /** One dialog over one staged plan; the caller built the plan off the
      * EDT (one scan) and owns the Configure seam. */
    public StartPlanDialog(StartPlan plan, SegmentConfigurator configurator) {
        this(plan, (segment, parent) -> CompletableFuture.completedFuture(
                Objects.requireNonNull(configurator, "configurator").configure(segment)));
    }

    /** One dialog over one staged plan and the ASYNC Configure seam (T38's
      * production wiring): the accepted choice is STAGED when its future
      * completes -- still written only on a confirmed OK, exactly the capture
      * discipline of the synchronous seam. */
    private StartPlanDialog(StartPlan plan, AsyncSegmentConfigurator configurator) {
        super(true);
        this.plan = Objects.requireNonNull(plan, "plan");
        this.configurator = Objects.requireNonNull(configurator, "configurator");
        this.staging = new Staging(plan);
        setTitle("Confirm plan for " + plan.target().text());
        init();
        setResizable(true);
        setOKButtonText("Start stage 1");
    }

    /** The dialog over the ASYNC Configure seam (T38): the production seam's
      * git preparation runs off the EDT, so its answer arrives as a future. */
    public static StartPlanDialog overAsync(StartPlan plan, AsyncSegmentConfigurator configurator) {
        return new StartPlanDialog(plan, configurator);
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

    /** OK stays blocked while anything is unconfigured (P4.5) or while a Configure
      * answer is still outstanding (T38: the frozen state is only what has landed). */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (!outstanding.isEmpty()) {
            return new ValidationInfo("A segment's configuration is still running");
        }
        return staging.refusal().map(ValidationInfo::new).orElse(null);
    }

    /** Freezes the staged choices BEFORE the wrapper disposes its Swing peers. */
    @Override
    protected void doOKAction() {
        accepted = staging.capture();
        super.doOKAction();
    }

    /** A canceled parent never writes and never strands an outstanding Configure:
      * its answer completes empty, so the seam never opens its dialog (T38). */
    @Override
    public void doCancelAction() {
        for (CompletableFuture<Optional<Choice>> answer : List.copyOf(outstanding)) {
            answer.complete(Optional.empty());
        }
        super.doCancelAction();
    }

    /** The accepted choices; refuses before a confirmed OK. */
    public PlanLaunchChoices choices() {
        if (accepted == null) {
            throw new IllegalStateException("The plan dialog has not accepted its choices");
        }
        return accepted;
    }

    /** The one code path the Configure button drives: the seam is asked, the
      * answer is staged when it lands -- immediately for a synchronous seam,
      * on the EDT in this dialog's own modality for an async one -- and the
      * summary follows. A canceled or refused Configure -- an empty answer --
      * leaves every staged value exactly as it was, and an outstanding answer keeps
      * OK blocked until it lands. */
    private void configure(Segment segment) {
        CompletableFuture<Optional<Choice>> answer;
        try {
            answer = configurator.configure(segment, this);
        } catch (RuntimeException seamFailure) { // a throwing seam is a refused Configure
            LOG.log(System.Logger.Level.WARNING, "The Configure seam of a segment threw", seamFailure);
            refreshChoice(segment);
            return;
        }
        outstanding.add(answer);
        if (answer.isDone()) { // a synchronous seam lands immediately, byte-identically
            landed(answer, segment, completedOf(answer));
            return;
        }
        answer.whenComplete((choice, failure) -> ApplicationManager.getApplication().invokeLater(
                () -> landed(answer, segment, failure == null ? choice : null),
                ModalityState.stateForComponent(getContentPanel())));
    }

    /** One landed Configure answer, on the EDT: the OK block clears, an accepted
      * choice is staged, the summary follows. A null answer -- canceled, refused or
      * failed -- changes nothing, exactly a canceled Configure. */
    private void landed(CompletableFuture<Optional<Choice>> answer, Segment segment,
            Optional<Choice> choice) {
        outstanding.remove(answer);
        if (choice != null) {
            choice.ifPresent(accepted -> staging.configure(segment, accepted));
        }
        refreshChoice(segment);
    }

    /** A completed future's answer, or null when it completed exceptionally. */
    private static Optional<Choice> completedOf(CompletableFuture<Optional<Choice>> answer) {
        try {
            return answer.join();
        } catch (RuntimeException failed) { // a failed answer is a refused Configure
            return null;
        }
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
