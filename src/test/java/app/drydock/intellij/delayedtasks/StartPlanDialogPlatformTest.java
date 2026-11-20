package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.PlanLaunchChoices;
import app.drydock.delayedtasks.StartPlanDialog;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.StartPlanner;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskFiles;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskStore;

import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import org.junit.Assert;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;

import java.awt.Component;
import java.awt.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The T37b acceptance matrix for the multi-stage plan dialog in a booted IDE:
 * the wrapper the plain-JVM smoke could not construct is constructed here and
 * driven through its production seams, never show(). The real Configure
 * buttons run the dialog's configure path over the real SegmentConfigurator
 * seam; the OK button and doCancelAction are what default-button Enter and
 * root-pane Esc fire; doValidate answers through performValidateAll; the
 * frozen choices are read through choices(). The seam's production body opens
 * the real New Session dialog modally - headless, the fixture supplies the
 * accepted Choice through the seam, and the nested on-screen dialog and real
 * keystrokes stay manual. Zero-write proofs settle the EDT, then compare the
 * temp-root tree (path+mtime) and re-decode the store (the T20b fix).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class StartPlanDialogPlatformTest extends BasePlatformTestCase {

    private Path root;
    private TaskId target, first, inBeta, inGamma;
    private final Map<String, RepositoryId> repos = new LinkedHashMap<>();
    private final List<StartPlanDialog> dialogs = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("drydock-plan-dialog");
        for (String name : new String[] {"delta", "beta", "gamma", "alpha"}) {
            repos.put(name, RepositoryId.newId());
        }
        try (TaskStore store = new TaskStore(root)) {
            first = store.create(repos.get("delta"), "Lay the foundation", List.of()).id();
            inBeta = store.create(repos.get("beta"), "Build the walls", List.of()).id();
            store.update(inBeta, new TaskFilePatch.Updates(null, null, DoneBy.AGENT, null,
                    List.of("delta:" + first.text()), null));
            inGamma = store.create(repos.get("gamma"), "Wire the lights", List.of()).id();
            store.update(inGamma, new TaskFilePatch.Updates(null, null, null, null,
                    List.of("delta:" + first.text()), null));
            target = store.create(repos.get("alpha"), "Ship the house", List.of()).id();
            store.update(target, new TaskFilePatch.Updates(null, null, null, null,
                    List.of("beta:" + inBeta.text(), "gamma:" + inGamma.text()), null));
            store.flush();
        }
    }

    @Override
    protected void tearDown() throws Exception {
        dialogs.forEach(StartPlanDialog::disposeIfNeeded);
        super.tearDown();
        delete(root);
    }

    // 1. Matrix 1: every segment configures through the real Configure seam
    // into one fully-configured, confirmed choice set.
    public void testAllSegmentsConfigureThroughTheRealSeamIntoAConfirmedChoice() throws IOException {
        StartPlan plan = plan();
        assertTrue(plan.startable() && plan.segments().size() == 4 && plan.stages().size() == 3);
        StartPlanDialog dialog = newDialog(plan);
        assertTrue("the first Configure button takes the initial focus",
                dialog.getPreferredFocusedComponent() instanceof AbstractButton button
                        && "Configure...".equals(button.getText()) && button.isFocusable());
        List<AbstractButton> configure = buttons(dialog, "Configure...");
        assertEquals("one Configure button per segment", plan.segments().size(), configure.size());
        configure.forEach(AbstractButton::doClick);
        for (StartPlan.Segment segment : plan.segments()) {
            String summary = labelContaining(dialog, "branch drydock/plan-" + segment.projectName()).getText();
            assertTrue("the summary shows the accepted choice, not a duplicate editor",
                    summary.contains(choiceFor(segment).agent().persistedName()) && summary.contains(
                            choiceFor(segment).mode() == SessionMode.WRITES ? "full access" : "read-only"));
        }
        assertTrue("fully configured, the confirmation is no longer blocked", dialog.performValidateAll().isEmpty());
        assertEquals("probe: the OK button carries the post-init rename", "Start stage 1",
                ok(dialog).getText());
        ok(dialog).doClick();
        PlanLaunchChoices captured = dialog.choices();
        assertTrue(captured.target().equals(target) && List.of(1, 2, 3).equals(captured.confirmedStages()));
        Map<RepositoryId, Choice> segments = new LinkedHashMap<>();
        plan.segments().forEach(segment -> segments.put(segment.repository(), choiceFor(segment)));
        assertEquals("each segment freezes exactly the choice its Configure accepted",
                segments, captured.segments());
        Map<TaskId, DoneBy> done = new LinkedHashMap<>();
        plan.segments().forEach(s -> s.tasks().forEach(t -> done.put(t.id(), t.doneBy())));
        assertEquals("the checkbox default re-derives from each stored done_by", done, captured.doneBy());
    }

    // 2. Matrix 2: the default path needs the human confirmation step - the
    // informed text, the unconfirmed gate, and the stored done_by policy.
    public void testHumanDefaultNeedsTheConfirmationGateBeforeOk() throws IOException {
        StartPlanDialog dialog = newDialog(plan());
        String explanation = labelContaining(dialog, "Confirming starts the plan now").getText();
        assertTrue("the confirmation is informed: parallel stage 1, automatic advance, cancel changes nothing",
                explanation.contains("launches in parallel") && explanation.contains("starts the next by itself")
                        && explanation.contains("Cancel changes and starts nothing"));
        assertEquals("an unconfigured segment says so", "Not configured yet",
                labelContaining(dialog, "Not configured yet").getText());
        List<ValidationInfo> refused = dialog.performValidateAll();
        assertEquals("the unconfigured plan refuses with one visible reason", 1, refused.size());
        assertTrue(refused.get(0).message.startsWith("Configure every segment before confirming - missing:")
                && refused.get(0).message.contains("Project delta (1 task)"));
        ok(dialog).doClick();
        assertTrue("the gate blocks the unconfirmed OK", !dialog.isOK());
        Assert.assertThrows(IllegalStateException.class, dialog::choices);
        buttons(dialog, "Configure...").forEach(AbstractButton::doClick);
        ok(dialog).doClick();
        PlanLaunchChoices captured = dialog.choices();
        assertTrue("the default path keeps done behind the human gate: HUMAN unless stored agent",
                captured.doneBy().get(target) == DoneBy.HUMAN && captured.doneBy().get(first) == DoneBy.HUMAN
                        && captured.doneBy().get(inBeta) == DoneBy.AGENT);
        assertTrue("an absent task keeps its stored policy, a missing stored one defaults HUMAN",
                captured.doneByOf(TaskId.of("unplanned-x"), DoneBy.AGENT) == DoneBy.AGENT
                        && captured.doneByOf(TaskId.of("unplanned-x"), null) == DoneBy.HUMAN);
    }

    // 3. Matrix 3: the captured choices are immutable after close and equal what was staged.
    public void testTheCapturedChoicesAreImmutableAfterClose() throws IOException {
        StartPlan plan = plan();
        StartPlanDialog dialog = newDialog(plan);
        buttons(dialog, "Configure...").forEach(AbstractButton::doClick);
        boxFor(dialog, target).doClick();
        ok(dialog).doClick();
        PlanLaunchChoices captured = dialog.choices();
        Assert.assertThrows("the segments map resists mutation", UnsupportedOperationException.class,
                () -> captured.segments().put(repos.get("delta"), choiceFor(plan.segments().get(0))));
        Assert.assertThrows("the done_by map resists mutation", UnsupportedOperationException.class,
                () -> captured.doneBy().put(TaskId.of("unplanned-y"), DoneBy.AGENT));
        Assert.assertThrows("the stage list resists mutation", UnsupportedOperationException.class,
                () -> captured.confirmedStages().add(4));
        Map<RepositoryId, Choice> segments = new LinkedHashMap<>();
        plan.segments().forEach(segment -> segments.put(segment.repository(), choiceFor(segment)));
        assertEquals(segments, captured.segments());
        assertEquals("the frozen state equals what was staged, checkbox included",
                Map.of(first, DoneBy.HUMAN, inBeta, DoneBy.AGENT, inGamma, DoneBy.HUMAN, target, DoneBy.AGENT),
                captured.doneBy());
    }

    // 4. Matrix 4: a canceled configuration changes nothing - settle, then compare the
    // whole temp-root tree and re-decode the store to the baseline.
    public void testACanceledConfigurationWritesNothing() throws IOException {
        Map<Path, Long> before = tree(root);
        TaskSnapshot baseline = TaskFiles.scan(root);
        StartPlanDialog dialog = newDialog(plan());
        buttons(dialog, "Configure...").get(0).doClick();
        boxFor(dialog, first).doClick();
        dialog.doCancelAction();
        settle();
        assertTrue(!dialog.isOK());
        Assert.assertThrows(IllegalStateException.class, dialog::choices);
        assertEquals("cancel writes no file: the tree is untouched", before, tree(root));
        assertEquals("and the store decodes to the same documents", baseline.documents(),
                TaskFiles.scan(root).documents());
    }

    // 5. Matrix 5: checkbox staging lives in dialog state only - cancel writes nothing,
    // and a later capture reflects only its own confirmed staging.
    public void testCheckboxStagingStaysUnwrittenAndReachesOnlyItsOwnCapture() throws IOException {
        Map<Path, Long> before = tree(root);
        StartPlanDialog abandoned = newDialog(plan());
        boxFor(abandoned, target).doClick();
        boxFor(abandoned, inBeta).doClick();
        assertTrue("the staged toggles landed in dialog state",
                !boxFor(abandoned, inBeta).isSelected() && boxFor(abandoned, target).isSelected());
        abandoned.doCancelAction();
        settle();
        assertEquals("a canceled toggle writes nothing anywhere", before, tree(root));
        StartPlanDialog confirmed = newDialog(plan());
        assertTrue("a fresh dialog re-derives the defaults from the stored policy, nothing leaks",
                boxFor(confirmed, inBeta).isSelected() && !boxFor(confirmed, target).isSelected());
        buttons(confirmed, "Configure...").forEach(AbstractButton::doClick);
        boxFor(confirmed, target).doClick();
        ok(confirmed).doClick();
        assertEquals("staging reaches a capture only when it was confirmed",
                Map.of(first, DoneBy.HUMAN, inBeta, DoneBy.AGENT, inGamma, DoneBy.HUMAN, target, DoneBy.AGENT),
                confirmed.choices().doneBy());
        assertEquals("even a confirmed OK writes nothing here: persisting is the caller's business",
                before, tree(root));
    }

    private StartPlan plan() throws IOException {
        TaskSnapshot snapshot = TaskFiles.scan(root);
        Function<RepositoryId, String> slugs = repository -> repos.entrySet().stream()
                .filter(entry -> entry.getValue().equals(repository)).map(Map.Entry::getKey)
                .findAny().orElse(repository.value().toString());
        return StartPlanner.plan(target, snapshot, TaskGraph.of(snapshot, slugs), slugs, Set.of());
    }
    private StartPlanDialog newDialog(StartPlan plan) {
        StartPlanDialog dialog = new StartPlanDialog(plan, segment -> Optional.of(choiceFor(segment)));
        dialogs.add(dialog);
        return dialog;
    }
    private Choice choiceFor(StartPlan.Segment segment) {
        return new Choice("Plan launch", Optional.empty(),
                "beta".equals(segment.projectName()) ? AgentKind.CLAUDE : AgentKind.PI,
                "gamma".equals(segment.projectName()) ? SessionMode.READ_ONLY : SessionMode.WRITES,
                NewTaskPlan.WorkIn.NEW_WORKTREE, "drydock/plan-" + segment.projectName(), "main",
                root.getParent().resolve("drydock-" + segment.projectName()), NewTaskPlan.OpenIn.OWN_WINDOW,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static AbstractButton ok(StartPlanDialog dialog) {
        return components(dialog, AbstractButton.class).stream().filter(
                button -> "Start stage 1".equals(button.getText()) || "OK".equals(button.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("no OK button"));
    }
    private static List<AbstractButton> buttons(StartPlanDialog dialog, String text) {
        return components(dialog, AbstractButton.class).stream()
                .filter(button -> text.equals(button.getText())).toList();
    }
    private static JLabel labelContaining(StartPlanDialog dialog, String text) {
        return components(dialog, JLabel.class).stream()
                .filter(label -> label.getText().contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no label containing " + text));
    }
    private static JCheckBox boxFor(StartPlanDialog dialog, TaskId task) {
        Container row = labelContaining(dialog, "[" + task.text() + "]").getParent();
        List<JCheckBox> found = new ArrayList<>();
        collect(row, JCheckBox.class, found);
        assertEquals("exactly one may-close checkbox in the task's row", 1, found.size());
        return found.get(0);
    }
    private static <T extends Component> List<T> components(StartPlanDialog dialog, Class<T> type) {
        Container tree = dialog.getPreferredFocusedComponent();
        while (tree.getParent() != null) tree = tree.getParent();
        List<T> found = new ArrayList<>();
        collect(tree, type, found);
        return found;
    }
    private static void collect(Container parent, Class type, List found) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) collect(container, type, found);
        }
    }
    private static void settle() {
        for (int round = 0; round < 20; round++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            TimeoutUtil.sleep(20);
        }
    }
    private static Map<Path, Long> tree(Path root) throws IOException {
        Map<Path, Long> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) out.put(root.relativize(path),
                    Files.getLastModifiedTime(path).toMillis());
        }
        return out;
    }
    private static void delete(Path directory) {
        if (directory == null) return;
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
