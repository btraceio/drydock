package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.NewTaskLauncherResult;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskStatus;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import javax.swing.Action;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.text.JTextComponent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The T19b platform matrix for the accepted save-later mode (T19a, D5/P5).
 * The dialog is constructible only in the booted IDE, so this fixture drives the real
 * checkbox wiring, doValidate/doOKAction, the OK label and the real save dispatch through
 * TaskLauncher.saveTask against the plugin's booted services. The dialog is never shown;
 * waits pump the EDT queue and poll a deadline; every dialog is parked and disposed in
 * tearDown. Boundary: the launch OK capture needs the shown peer, which this contract
 * forbids, so the launch item pins the values via choice() and the clean-exit mechanics.
 */
public class SaveTaskLaterPlatformTest extends BasePlatformTestCase {

    /** Every start control the save mode must switch off (P5), by field name. */
    private static final List<String> START_FIELDS = List.of("newWorktree", "mainCheckout", "branch", "start",
            "folder", "thisWindow", "ownWindow", "writes", "readOnly", "modelField", "change");

    private DrydockPluginService plugin;
    private Path storeRoot;
    private final List<NewTaskDialog> dialogs = new ArrayList<>();
    private final List<RepositoryId> registrations = new ArrayList<>();
    private final List<Path> storeDirs = new ArrayList<>();
    private final List<Path> roots = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        plugin = DrydockPluginService.getInstance();
        try { plugin.initialization().get(60, TimeUnit.SECONDS); } catch (Exception e) { fail("init: " + e); }
        storeRoot = plugin.taskService().root().toAbsolutePath().normalize();
        assertTrue("the store root is the Gradle test sandbox: " + storeRoot,
                System.getProperty(DelayedTaskService.OVERRIDE_PROPERTY) != null
                        && !storeRoot.startsWith(Path.of(System.getProperty("user.home"), ".drydock")));
    }

    @Override
    protected void tearDown() throws Exception {
        for (NewTaskDialog dialog : dialogs) {
            try { Disposer.dispose(dialog.getDisposable()); } catch (RuntimeException ignored) { }
        }
        for (RepositoryId id : registrations) {
            try { plugin.sharedProjects().remove(id).get(30, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        for (Path dir : storeDirs) { delete(dir); }
        for (Path root : roots) { delete(root); }
        super.tearDown();
    }

    // 1+2. The toggle switches the exact start field set, the Start validation and the OK label.
    public void testTheSaveLaterSwitchTogglesTheExactStartFieldsValidationAndOkLabel() {
        onEdt(() -> {
            NewTaskDialog plain = dialog(inputs(Path.of("/tmp/profiler"), List.of(pi()), false, null),
                    NewTaskPreset.empty());
            plain.diagSetInstructions("Fix the parser");
            OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(),
                    List.of(), List.of(), Optional.empty());
            NewTaskDialog triage = dialog(inputs(Path.of("/tmp/profiler"), List.of(pi()), true, null),
                    NewTaskPreset.triage(issue));
            var summary = control(plain, "summary", JComponent.class);
            var tiers = control(triage, "tierRows", JComponent.class);
            assertEquals("launch mode labels OK Start task", "Start task", okLabel(plain));
            control(plain, "branch", JTextComponent.class).setText("");
            assertNotNull("launch mode polices the branch", plain.doValidate());
            assertTrue("the empty branch refuses", plain.doValidate().message.contains("Give the branch a name"));
            assertTrue("launch mode offers the agent, the summary and the tier rows",
                    agentButton(plain).isEnabled() && summary.isVisible() && tiers.isVisible());
            startFields(plain, true);
            saveBox(plain).doClick();
            assertEquals("save mode renames OK Save Task (P5)", "Save Task", okLabel(plain));
            assertNull("save mode switches the Start validation off", plain.doValidate());
            startFields(plain, false);
            assertFalse("save mode disables the agent picker too", agentButton(plain).isEnabled());
            assertFalse("save mode hides the summary and the tier rows", !summary.isVisible() && !tiers.isVisible());
            assertTrue("the description stays editable: it is what gets saved",
                    control(plain, "instructions", JTextComponent.class).isEnabled());
            saveBox(plain).doClick();
            startFields(plain, true);
            assertEquals("launch mode is restored", "Start task", okLabel(plain));
            assertNotNull("the Start validation is back", plain.doValidate());
            assertTrue("the agent and summary come back", agentButton(plain).isEnabled() && summary.isVisible());
        });
    }

    // 3. An agentless save through the real service: task created, project registered, nothing started.
    public void testAnAgentlessSaveCreatesTheTaskThroughTheRealServiceWithoutStartingAnything() throws Exception {
        Path repoRoot = Files.createTempDirectory("drydock-save-later");
        roots.add(repoRoot);
        List<?> sessionsBefore = List.copyOf(plugin.stateStore().state().sessions());
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        var runningBefore = List.copyOf(registry.runningSessions());
        var startingBefore = Set.copyOf(registry.starting());
        AtomicReference<NewTaskLauncherResult> accepted = new AtomicReference<>();
        onEdt(() -> {
            NewTaskDialog dialog = dialog(inputs(repoRoot, List.of(), false, null), NewTaskPreset.empty());
            dialog.diagSetInstructions("Fix allocation accounting\nThen add a regression test.");
            assertNull("zero agents never block a save (P5)", dialog.doValidate());
            saveBox(dialog).doClick();
            dialog.doOKAction();
            accepted.set(dialog.launcherResult());
        });
        assertFalse("the dialog accepted a save, not a launch", accepted.get().isLaunch());
        TaskLauncher.saveTask(getProject(), accepted.get().saved());
        await(() -> byRoot(repoRoot) != null, "the project's registration");
        SharedProject registered = byRoot(repoRoot);
        registrations.add(registered.id());
        Path repoDir = storeRoot.resolve(registered.id().toString());
        storeDirs.add(repoDir);
        await(() -> taskTitled("Fix allocation accounting") != null, "the saved task reaching the store");
        TaskDocument task = taskTitled("Fix allocation accounting");
        assertEquals("the task carries the registered project's stable id", registered.id(), task.repository());
        assertTrue("the task is created open and starts nothing", task.status() == TaskStatus.OPEN
                && task.sessions().isEmpty() && task.after().isEmpty());
        String stored = Files.readString(repoDir.resolve(task.id().text() + ".md"));
        assertTrue("the body is stored as session notes", stored.contains("## Session notes")
                && stored.contains("Then add a regression test."));
        TaskLauncher.saveTask(getProject(), new app.drydock.tasks.NewTaskPlan("Write the release notes",
                "Summarize the changes.", null, null, repoRoot, "save-later"));
        await(() -> taskTitled("Write the release notes") != null, "the second save reaching the store");
        assertEquals("a second save reuses the SAME stable id", registered.id(),
                taskTitled("Write the release notes").repository());
        assertTrue("nothing started anywhere: no session, no worktree, no agent",
                repoRoot.toFile().list().length == 0 && plugin.stateStore().state().sessions().equals(sessionsBefore)
                        && registry.runningSessions().equals(runningBefore)
                        && registry.starting().equals(startingBefore));
    }

    // 4. A failed save surfaces visibly, ends with the progress reset, and a retry completes it.
    public void testAFailedSaveSurfacesVisiblyAndARetryCompletesIt() throws Exception {
        Path repoRoot = Files.createTempDirectory("drydock-save-failed");
        roots.add(repoRoot);
        SharedProject registered = plugin.sharedProjects().register(repoRoot, "save-later-failed")
                .get(30, TimeUnit.SECONDS);
        registrations.add(registered.id());
        Path repoDir = storeRoot.resolve(registered.id().toString());
        storeDirs.add(repoDir);
        Files.createDirectories(repoDir);
        Files.setPosixFilePermissions(repoDir, PosixFilePermissions.fromString("r-xr-x---"));
        try {
            List<String> shown = new ArrayList<>();
            TestDialogManager.setTestDialog(message -> { shown.add(message); return Messages.OK; },
                    getTestRootDisposable());
            app.drydock.tasks.NewTaskPlan saved = new app.drydock.tasks.NewTaskPlan("Retry after a failed save",
                    "Summarize the changes.", null, null, repoRoot, "save-later-failed");
            TaskLauncher.saveTask(getProject(), saved);
            await(() -> !shown.isEmpty(), "the failed save's visible report");
            assertTrue("the failure names the save", shown.get(0).contains("Drydock could not save the task"));
            assertNull("the failed write stored nothing", taskTitled("Retry after a failed save"));
            Files.setPosixFilePermissions(repoDir, PosixFilePermissions.fromString("rwxr-x---"));
            TaskLauncher.saveTask(getProject(), saved);
            await(() -> taskTitled("Retry after a failed save") != null, "the retried save reaching the store");
            TaskDocument task = taskTitled("Retry after a failed save");
            assertEquals("the retry landed under the same project", registered.id(), task.repository());
            assertTrue("the retry started nothing either", task.sessions().isEmpty());
            assertEquals("the retry surfaced no further failure", 1, shown.size());
        } finally {
            Files.setPosixFilePermissions(repoDir, PosixFilePermissions.fromString("rwxr-x---"));
        }
    }

    // 5. Ordinary launch: the excursion leaves the choice values, the field state and the
    // wording untouched, and the additive result carries the launch choice unchanged. The
    // OK action itself needs the shown peer (isShowing() and a non-null content panel),
    // which this contract forbids showing; the values it would capture are dialog.choice().
    public void testOrdinaryLaunchKeepsItsChoiceValuesAndWording() {
        onEdt(() -> {
            NewTaskDialog dialog = dialog(inputs(Path.of("/tmp/profiler"), List.of(pi()), false, null),
                    NewTaskPreset.empty());
            dialog.diagSetInstructions("Fix the parser");
            var launch = dialog.choice();
            assertEquals(AgentKind.PI, launch.agent());
            assertEquals(NewTaskPlan.WorkIn.NEW_WORKTREE, launch.workIn());
            assertEquals("Start task", okLabel(dialog));
            assertNull("a valid launch passes the Start validation", dialog.doValidate());
            assertEquals("the additive result carries the launch choice unchanged",
                    launch, NewTaskLauncherResult.launched(launch).choice());
            saveBox(dialog).doClick();
            assertEquals("Save Task", okLabel(dialog));
            saveBox(dialog).doClick();
            startFields(dialog, true);
            assertEquals("the save excursion leaves the launch choice untouched", launch, dialog.choice());
            assertEquals("Start task", okLabel(dialog));
        });
    }

    private NewTaskDialog dialog(NewTaskPlan.Inputs inputs, NewTaskPreset preset) {
        NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs, Optional.empty(), preset);
        dialogs.add(dialog);
        return dialog;
    }
    private static NewTaskPlan.Inputs inputs(Path projectRoot, List<Agent> agents, boolean supportsSubagents,
                                              Path worktreeBase) {
        return new NewTaskPlan.Inputs(SharedProject.local(RepositoryId.newId(), projectRoot, "profiler",
                Instant.EPOCH), "profiler", Optional.empty(), agents, AgentKind.PI, "origin/main",
                List.of("origin/main"), List.of("origin"), Set.of(),
                worktreeBase == null ? Path.of("/tmp") : worktreeBase, false,
                kind -> Optional.empty(), kind -> List.of(), kind -> ModelTiers.EMPTY, kind -> supportsSubagents);
    }
    private static Agent pi() {
        return new Agent() {
            @Override public AgentKind kind() { return AgentKind.PI; }
            @Override public String displayName() { return "Pi"; }
            @Override public boolean isAvailable() { return true; }
            @Override public String describeSearched() { return "test"; }
        };
    }
    private static void startFields(NewTaskDialog dialog, boolean expected) {
        for (String name : START_FIELDS) {
            assertTrue(name + (expected ? " stays usable in launch mode" : " is disabled in save mode (P5)"),
                    control(dialog, name, JComponent.class).isEnabled() == expected);
        }
    }
    private SharedProject byRoot(Path root) { return plugin.sharedProjects().byRoot(root).orElse(null); }
    private TaskDocument taskTitled(String title) {
        try {
            return plugin.taskService().snapshot().get(30, TimeUnit.SECONDS).documents().stream()
                    .filter(d -> d.title().equals(title)).findFirst().orElse(null);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static JCheckBox saveBox(NewTaskDialog d) { return control(d, "saveForLater", JCheckBox.class); }
    private static JComponent agentButton(NewTaskDialog dialog) {
        return (JComponent) ((Map<?, ?>) field(NewTaskDialog.class, dialog, "agentButtons")).get(AgentKind.PI);
    }
    private static Action okAction(NewTaskDialog d) { return (Action) field(DialogWrapper.class, d, "myOKAction"); }
    private static String okLabel(NewTaskDialog dialog) { return (String) okAction(dialog).getValue(Action.NAME); }
    private static <T> T control(NewTaskDialog dialog, String name, Class<T> type) {
        return type.cast(field(NewTaskDialog.class, dialog, name));
    }
    private static Object field(Class<?> owner, Object target, String name) {
        try {
            Field found = owner.getDeclaredField(name);
            found.setAccessible(true);
            return found.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void onEdt(Runnable runnable) { ApplicationManager.getApplication().invokeAndWait(runnable); }

    /** Spins (never sleeps) until the condition holds, draining the EDT queue between polls. */
    private void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) {
            assertTrue("timed out waiting for " + what, System.nanoTime() < deadline);
            for (int i = 0; i < 3; i++) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); TimeoutUtil.sleep(20);
            }
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(
                    p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
