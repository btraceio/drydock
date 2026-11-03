package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.*;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.intellij.tasks.NewTaskPreset;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.*;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;

import javax.swing.AbstractButton;
import javax.swing.text.JTextComponent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The T31b matrix's dialog half, over the booted IDE's real Gradle-sandbox store (zero
 * mocking; pump-park waits only; no dialog ever shown): the full 7-item T31a matrix
 * maps 1-2-6 here and 3-4-5-7 to DelayedTaskStartBindingTest (a supervisor-granted
 * two-file split, the T05c/T11b precedent; the shared fixture is
 * DelayedTaskStartFixture). (1) Open chain: the target's plan is one segment and the
 * dialog the flow opens is seeded with the target's title and the composed prompt of
 * the selected harness. (2) A harness switch recomposes only UNEDITED generated text
 * while a user edit survives verbatim (the instructionsEdited gate, T09). (6) A
 * canceled dialog starts nothing: zero writes (settle-then-decode) and the claim
 * released. The driven production glue is package-private -- DelayedTaskStart in
 * app.drydock.delayedtasks, the New task dialog in app.drydock.intellij.tasks -- so it
 * is reached reflectively where no seam exists, pinned to the exact production branch
 * it enters; the dialog items drive a CONSTRUCTED dialog's own seams (the T37b
 * precedent). Boundaries, honestly: the shown-dialog OK-to-launch and cancel-to-empty
 * completions (chooseTask always shows) and the real agent spawn stay with the T31a
 * smoke and T22b's disposed branch.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DelayedTaskStartTest extends DelayedTaskStartFixture {

    // 1-2. Open chain, seeding and the switch rule: the plan is one segment; the dialog the
    // flow opens is seeded with the target's title and the composed prompt of the selected
    // harness; a switch recomposes only UNEDITED text; a user edit survives verbatim.
    public void testOpenChainSeedsTheDialogAndSwitchRecomposesOnlyUneditedText() throws Exception {
        Chain chain = chain("t31b-seed", "Ship the feature");
        assertTrue("one startable segment (P4)", chain.plan().startable()
                && chain.plan().segments().size() == 1 && chain.plan().stages().size() == 1);
        StartPlan.Segment segment = chain.plan().segments().get(0);
        assertEquals("the whole segment is the work, prerequisites first",
                List.of(chain.open(), chain.ship()),
                segment.tasks().stream().map(TaskDocument::id).toList());
        String pi = NewTaskPreset.composedInstructions(AgentKind.PI, chain.plan(), segment);
        String claude = NewTaskPreset.composedInstructions(AgentKind.CLAUDE, chain.plan(), segment);
        assertTrue("each harness composes its own shape", !pi.equals(claude));
        NewTaskPreset preset = NewTaskPreset.segmentStart("Ship the feature", chain.plan(), segment);
        final NewTaskPlan.Choice[] seeded = new NewTaskPlan.Choice[1];
        final String[] switched = new String[2];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            DialogWrapper dialog = newDialog(preset);
            JTextComponent instructions = (JTextComponent) field(dialog, "instructions");
            Map buttons = (Map) field(dialog, "agentButtons");
            seeded[0] = (NewTaskPlan.Choice) method(dialog, "choice");
            assertEquals("the text area is seeded with the composed prompt", pi, instructions.getText());
            assertNotNull("the CLAUDE harness to switch to", buttons.get(AgentKind.CLAUDE));
            ((AbstractButton) buttons.get(AgentKind.CLAUDE)).doClick();
            switched[0] = instructions.getText();
            instructions.setText("my own words"); // through the real document listener: a user edit
            ((AbstractButton) buttons.get(AgentKind.PI)).doClick();
            switched[1] = instructions.getText();
        });
        assertEquals("the target's title names the session", "Ship the feature", seeded[0].name());
        assertEquals("the instructions carry the composed prompt", Optional.of(pi),
                seeded[0].instructions());
        assertEquals("an unedited switch recomposes into the new harness's shape", claude, switched[0]);
        assertEquals("a user edit survives every switch, verbatim", "my own words", switched[1]);
    }

    // 6. A canceled dialog starts nothing: zero writes, and the claim is released.
    public void testACanceledDialogLeavesTasksUnstarted() throws Exception {
        Chain chain = chain("t31b-cancel", "Ship the feature");
        StartPlan.Segment segment = chain.plan().segments().get(0);
        Map<String, String> before = settledTree();
        List<TaskId> claimed = claim(segment); // the flow's own claim seam
        final boolean[] accepted = {true};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            DialogWrapper dialog = newDialog(
                    NewTaskPreset.segmentStart("Ship the feature", chain.plan(), segment));
            dialog.doCancelAction(); // the production cancel gesture (what Esc fires), never show()
            accepted[0] = dialog.isOK();
        });
        pump();
        assertFalse("the canceled dialog accepted nothing", accepted[0]);
        inFlight().removeAll(claimed); // the canceled path's release (the flow's finally)
        assertEquals("a canceled start writes nothing anywhere", before, settledTree());
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            assertTrue("no reservation receipt exists", runs.byTask(chain.open()).isEmpty()
                    && runs.byTask(chain.ship()).isEmpty());
        }
        assertTrue("the released claim starts again", claim(segment) != null);
        inFlight().removeAll(claimed);
    }

    // ---- the dialog seams (no other file owns them) ---------------------------

    static Object method(Object target, String name) {
        try { Method found = target.getClass().getDeclaredMethod(name); found.setAccessible(true);
            return found.invoke(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError("no method " + name, e); }
    }

    static Object field(Object target, String name) {
        try { Field found = target.getClass().getDeclaredField(name); found.setAccessible(true);
            return found.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError("no field " + name, e); }
    }

    /** The production New task dialog (package-private in the tasks package), never shown. */
    DialogWrapper newDialog(NewTaskPreset preset) {
        try {
            Constructor<?> constructor = Class.forName("app.drydock.intellij.tasks.NewTaskDialog")
                    .getDeclaredConstructor(Project.class, NewTaskPlan.Inputs.class, Optional.class,
                            NewTaskPreset.class);
            constructor.setAccessible(true);
            DialogWrapper dialog = (DialogWrapper) constructor.newInstance(getProject(), inputs(),
                    Optional.empty(), preset);
            dialogs.add(dialog.getDisposable());
            return dialog;
        } catch (ReflectiveOperationException e) { throw new AssertionError("no NewTaskDialog seam", e); }
    }

    /** The dialog's inputs, headless, as the in-package dialog fixture builds them; the agent
      * entries are input data for the harness buttons, no service mock (T22b's fixture shape). */
    private static NewTaskPlan.Inputs inputs() {
        SharedProject project = SharedProject.local(RepositoryId.newId(), Path.of("/tmp/t31b"),
                "profiler", Instant.EPOCH);
        return new NewTaskPlan.Inputs(project, "profiler", Optional.empty(),
                List.of(agent(AgentKind.PI), agent(AgentKind.CLAUDE)), AgentKind.PI, "main",
                List.of("main"), List.of("origin"), Set.of(), Path.of("/tmp"), false,
                kind -> Optional.empty(), kind -> List.of(), kind -> ModelTiers.EMPTY, kind -> false);
    }

    private static Agent agent(AgentKind kind) {
        return new Agent() {
            @Override public AgentKind kind() { return kind; }
            @Override public String displayName() { return kind.persistedName(); }
            @Override public boolean isAvailable() { return true; }
            @Override public String describeSearched() { return "fixture"; }
        };
    }
}
