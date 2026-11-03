package app.drydock.intellij.delayedtasks;

import app.drydock.delayedtasks.*;
import app.drydock.domain.*;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.mcp.McpSessionRegistry;
import app.drydock.mcp.McpToolRouter;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.tasks.*;

import com.intellij.notification.Notification;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The T43b integrated matrix, the final task of the delayed-tasks plan, on
 * the shared booted-services harness DelayedTasksEndToEndFixture (a
 * supervisor-granted two-file split, the CrossProjectStartTest/T38b
 * precedent): every matrix item below lives wholly in this file, over REAL
 * services -- real task files and tools, the real watcher, the real
 * notification wiring, the real plan recovery, the real production backend
 * -- with only the launcher faked through the service's caller-supplied
 * SegmentLauncher seam (never a real agent spawn), the MCP round-trip made
 * in-process through the backend's own router (no socket is dialed), and no
 * home store: the plugin's task root is the build-redirected test sandbox,
 * asserted in matrix 4, every other root a temp directory. Pump-park waits
 * only; nothing is ever shown. Cross-process contention stays with T43a's
 * process matrix; the real spawn, on-screen dialogs and the production
 * launcher composition stay with the platform fixtures and smokes. The
 * known residuals are pinned, not fixed: the startup Continue balloon stays
 * text-only (T39b), TaskReopened has no production dispatcher (the review
 * blocking here rides the watcher's DONE observations), and
 * worktreeRemovalPending has no auto-proceed consumer (matrix 3 reads the
 * receipts, never the marker).
 */
public class DelayedTasksEndToEndTest extends DelayedTasksEndToEndFixture {

    // Matrix 1: a source file edited on disk flows through the watcher refresh into the
    // tree-facing snapshot, and a conflicting unsaved buffer still refuses the reload (D6/P5).
    public void testASourceFileEditFlowsIntoTheTreeSnapshotAndAnUnsavedBufferRefusesTheReload()
            throws Exception {
        root = Files.createTempDirectory("drydock-t43b-watch");
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), root.toString());
        service = new DelayedTaskService(root, threads, List::of, List::of);
        awaitWatched(c -> c.snapshot().documents().isEmpty() && c.saveConflicts().isEmpty()
                && c.scanError() == null, "the initial empty composition", PERIODIC_SECONDS);
        RepositoryId repository = RepositoryId.newId();
        TaskId edited = create("Watcher flows the edit", repository, List.of());
        awaitWatched(c -> c.snapshot().byId().containsKey(edited), "the created task settling",
                EVENT_SECONDS);
        Files.writeString(file(repository, edited), stored("Flowed through the watcher", List.of(), List.of()));
        TaskStoreWatcher.WatchedSnapshot flowed = awaitWatched(
                c -> "Flowed through the watcher".equals(titleOf(c, edited)), "the external edit refreshing",
                PERIODIC_SECONDS);
        TaskNode.DelayedTaskRow row = DelayedTaskRows.rows(flowed, Set.of(), any -> "t43b-project",
                Instant.now()).stream().filter(r -> r.id().equals(edited)).findFirst().orElseThrow();
        assertEquals("the refreshed composition reaches the tree-facing row", "Flowed through the watcher",
                row.title());
        assertSame("the row the tree derives carries the refreshed view", TaskState.Presentation.READY,
                row.presentation());
        Document conflicted = open(file(repository, edited));
        String unsaved = stored("Unsaved local edit", List.of(), List.of());
        ApplicationManager.getApplication().runWriteAction(() -> conflicted.setText(unsaved));
        TaskId sibling = TaskId.of("external-sibling-t43b");
        Files.writeString(file(repository, sibling), stored("External sibling on disk", List.of(), List.of()));
        TaskStoreWatcher.WatchedSnapshot conflict = awaitWatched(c -> !c.saveConflicts().isEmpty(),
                "the refused reload surfacing its conflict", PERIODIC_SECONDS);
        assertEquals("the conflict names the unsaved buffer's file", file(repository, edited),
                conflict.saveConflicts().get(0).file());
        assertFalse("the external sibling stays unpublished while the reload is refused",
                conflict.snapshot().byId().containsKey(sibling));
        assertEquals("the unsaved editor buffer is not clobbered", unsaved, conflicted.getText());
        FileDocumentManager.getInstance().saveDocument(conflicted);
        TaskStoreWatcher.WatchedSnapshot cleared = awaitWatched(c -> c.saveConflicts().isEmpty()
                        && "Unsaved local edit".equals(titleOf(c, edited))
                        && c.snapshot().byId().containsKey(sibling),
                "the re-read after the save cleared the conflict", PERIODIC_SECONDS);
        assertTrue("the cleared composition scans clean", cleared.scanError() == null);
    }

    // Matrix 2: an agent's done parks in REVIEW, blocking the plan's advancement -- the
    // reducer waits, the balloon surfaces, only the human's Confirm proceeds (D16).
    public void testAnAgentDoneBlocksAdvancementUntilTheHumansConfirm() throws Exception {
        Driven plan = drivePlan(false);
        update(plan.gate(), TaskState.Actor.AGENT, TaskStatus.DONE); // the agent's done
        assertEquals("an agent's done on a human-validated task parks in review (D16)", TaskStatus.REVIEW,
                status(plan.gate()));
        publish();
        Notification review = awaitBalloon("Task review: 'Gate work'", "the review balloon surfacing");
        await(() -> plan.launcher().effects.stream()
                        .anyMatch(e -> e instanceof PlanProgress.AwaitingConfirmation wait
                                && wait.task().equals(plan.gate())),
                "the reducer to answer the wait, not a launch");
        assertEquals("stage 2 never launches while the review holds", 1,
                plan.launcher().launched.size());
        dispatch(review, "Confirm Done"); // the human's answer, through the balloon's own action
        await(() -> status(plan.gate()) == TaskStatus.DONE, "the balloon's Confirm committing done");
        publish();
        await(() -> plan.launcher().launched.size() == 2, "stage 2 to launch only after the Confirm");
        assertEquals("stage 2 is the target's own", List.of(plan.ship()),
                plan.launcher().launched.get(1).tasks());
        assertEquals(2, plan.launcher().launched.get(1).stage());
    }

    // Matrix 3: after the prerequisite's FORGET, a dependent session's already-resolved
    // worktree context survives per the accepted retained semantics (D13/P4).
    public void testRetainedWorktreeContextSurvivesThePrerequisiteForget() throws Exception {
        root = Files.createTempDirectory("drydock-t43b-retained");
        Path store = Files.createDirectories(root.resolve("store"));
        Path prereqWt = Files.createDirectories(root.resolve("prereq-wt"));
        Path owner = Files.createDirectories(store.resolve(RepositoryId.newId().toString()));
        RepositoryId repo = RepositoryId.newId();
        ManagedSessionId dependentSession = ManagedSessionId.newId();
        String dependent = dependentSession.toString();
        TaskId prereq;
        TaskId task;
        try (TaskStore tasks = new TaskStore(store)) { // real task files under the temp root,
            prereq = tasks.create(repo, "Prerequisite work", List.of()).id(); // ids the receipts name
            task = tasks.create(repo, "Dependent work", List.of(dependent)).id();
        }
        TaskLaunchResources resources = new TaskLaunchResources(store, new TaskGuidance(store), () -> false);
        try (TaskRuns runs = new TaskRuns(store)) {
            runs.reserve(prereq, dependent + "0", prereqWt, owner, List.of()); // the prerequisite's own
            runs.reserve(task, dependent, root.resolve("dependent-wt"), owner, List.of(prereqWt));
            runs.launch(task, dependent); // the dependent holds the context as a live claim
        }
        assertEquals("the dependent's launch resolves the prerequisite worktree (T29a)", List.of(prereqWt),
                resources.resolve(dependentSession, Optional.empty()).orElseThrow().extraDirectories());
        try (TaskRuns runs = new TaskRuns(store)) {
            runs.forget(prereq); // the prerequisite session's done-forget releases ITS rows only (P4)
        }
        assertEquals("the already-resolved context survives the prerequisite's forget (D13)", List.of(prereqWt),
                resources.resolve(dependentSession, Optional.empty()).orElseThrow().extraDirectories());
        TaskWorktreeUse.Use use = TaskWorktreeUse.resolve(store, prereqWt, Set.of(dependent));
        assertEquals("the dependent's surviving claim is the only one on the context: the forgotten"
                + " prerequisite's own row claims no consumer path (T41b)", List.of(task),
                use.live().stream().map(TaskWorktreeUse.Consumer::task).toList());
        assertTrue("the removal stays refused while the dependent holds its context",
                TaskWorktreeUse.guard(store, prereqWt, Set.of(dependent)).isPresent());
        assertTrue("no released consumer history stays on the context: the prerequisite's own"
                + " row claims no consumer path, only the dependent's live claim does",
                use.released().isEmpty());
    }

    // Matrix 4: an authenticated in-process MCP round-trip (get/update) through the real
    // backend's router over the real store (T14a's delegate; no network is dialed).
    public void testAnAuthenticatedMcpRoundTripThroughTheRealBackendRouter() throws Exception {
        IsolatedCatalog.assertSandboxed();
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        plugin.initialization().get(60, TimeUnit.SECONDS);
        Path storeRoot = plugin.taskService().root().toAbsolutePath().normalize();
        assertTrue("the plugin's task root must be the redirected sandbox, never the home store: " + storeRoot,
                System.getProperty(DelayedTaskService.OVERRIDE_PROPERTY) != null
                        && !storeRoot.startsWith(Path.of(System.getProperty("user.home"), ".drydock")));
        ReviewBackend backend = plugin.reviewBackend().orElseThrow(() -> new AssertionError("no backend"));
        assertTrue("the production backend serves the four task tools (T14a)", backend.servesTaskTools());
        McpToolRouter router = field(backend, "router");
        McpSessionRegistry registry = field(backend, "mcpRegistry");
        ManagedSessionId caller = ManagedSessionId.newId();
        registry.mint(caller, McpSessionRegistry.Spawn.ALLOWED); // the handshake's own authentication mint
        RepositoryId repository = RepositoryId.newId();
        Path dir = storeRoot.resolve(repository.toString());
        cleanup.add(dir);
        Files.createDirectories(dir);
        TaskId id = TaskId.of("mcp-round-trip-t43b");
        Files.writeString(dir.resolve(id.text() + ".md"), stored("Round trip through the backend",
                List.of(caller.toString()), List.of())); // the assigned session rides the document
        assertEquals("task_get reads the real store through the backend router", "Round trip through"
                + " the backend", str(router.call(caller, "task_get", args("id", id.text())), "title"));
        assertEquals("an agent's done through the router parks in review (D16)", "review",
                str(router.call(caller, "task_update", args("id", id.text(), "status", "done")), "status"));
        assertEquals("task_get round-trips the committed status", "review",
                str(router.call(caller, "task_get", args("id", id.text())), "status"));
        assertEquals("the real store the delegate serves carries the same end state", TaskStatus.REVIEW,
                plugin.taskService().snapshot().get(SECONDS, TimeUnit.SECONDS).byId().get(id).status());
    }

    // Matrix 5: the tree-facing published plan views and task rows reflect the end state
    // after a driven stage completes -- the target's annotation and its placement (P4.12).
    public void testThePublishedTreeStateReflectsTheEndStateAfterADrivenStage() throws Exception {
        Driven plan = drivePlan(true);
        update(plan.gate(), TaskState.Actor.AGENT, TaskStatus.DONE); // stage 1's review
        publish();
        dispatch(awaitBalloon("Task review: 'Gate work'", "the gate's review balloon"), "Confirm Done");
        await(() -> status(plan.gate()) == TaskStatus.DONE, "the human's Confirm committing done");
        publish();
        await(() -> plan.launcher().launched.size() == 2, "stage 2 to launch the target");
        update(plan.ship(), TaskState.Actor.AGENT, TaskStatus.DONE); // stage 2's review
        publish();
        dispatch(awaitBalloon("Task review: 'Ship it'", "the target's review balloon"), "Confirm Done");
        await(() -> status(plan.ship()) == TaskStatus.DONE, "the target's Confirm committing done");
        publish();
        await(() -> { // the published views refresh per publication pass, never by a render path
            publish();
            return DelayedTaskRows.planOf(service.publishedPlans(), plan.ship())
                    .map(r -> r.plan().status() == RunningPlan.Status.COMPLETED).orElse(false);
        }, "the published views to carry the completed plan");
        PlanStore.PlanRecord record = DelayedTaskRows.planOf(service.publishedPlans(), plan.ship())
                .orElseThrow();
        assertTrue("every stage of the driven plan finished", record.plan().stages().stream()
                .allMatch(RunningPlan.StageState::finished));
        assertEquals("the target's end-state annotation", "stage 2/2 completed", DelayedTaskRows
                .planProgress(record, service.snapshot().get(SECONDS, TimeUnit.SECONDS),
                        service.liveSessionIds()).orElseThrow().text());
        assertTrue("the annotation lands on the plan's target only (P4.12)",
                DelayedTaskRows.planOf(service.publishedPlans(), plan.gate()).isEmpty());
        TaskNode.DelayedTaskRow target = DelayedTaskRows.rows(service.watchedSnapshot(),
                service.liveSessionIds(), any -> "t43b-project", Instant.now()).stream()
                .filter(r -> r.id().equals(plan.ship())).findFirst().orElseThrow();
        assertEquals("the end state reaches the task row", TaskState.Presentation.TERMINAL,
                target.presentation());
        assertEquals("the target keeps its session row placement (D10)", plan.shipSession(),
                DelayedTaskRows.attachedSession(target, service.liveSessionIds()).orElse(null));
    }

    // ---- this file's own fixtures (single-matrix uses) ----------------------------

    private static final long EVENT_SECONDS = 8; // the watcher's debounce plus one refresh
    private static final long PERIODIC_SECONDS = 15; // the watcher re-reads every 5 s

    /** Spins (never sleeps bare) until the published composition satisfies the condition. */
    private TaskStoreWatcher.WatchedSnapshot awaitWatched(
            Predicate<TaskStoreWatcher.WatchedSnapshot> condition, String what, long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        TaskStoreWatcher.WatchedSnapshot composition = service.watchedSnapshot();
        while (composition == null || !condition.test(composition)) {
            assertTrue("the watcher did not publish " + what + " within " + seconds + " s; last: "
                    + composition, System.nanoTime() < deadline);
            pump();
            composition = service.watchedSnapshot();
        }
        return composition;
    }

    private Path file(RepositoryId repository, TaskId id) {
        return root.resolve(repository.toString()).resolve(id.text() + ".md");
    }

    private static String titleOf(TaskStoreWatcher.WatchedSnapshot composition, TaskId id) {
        TaskDocument document = composition.snapshot().byId().get(id);
        return document == null ? null : document.title();
    }

    /** A complete task file as the store's own fixtures write them (T38b/T39b shape). */
    private static String stored(String title, List<String> sessions, List<String> after) {
        return "---\ntitle: \"" + title + "\"\nstatus: open\ndone_by: human\ncreated: " + Instant.now()
                + "\nafter: [" + quoted(after) + "]\nsessions: [" + quoted(sessions) + "]\n---\n\nBody.\n";
    }

    private static String quoted(List<String> refs) {
        return String.join(", ", refs.stream().map(ref -> "'" + ref + "'").toList());
    }

    /** Adopts the file into the VFS (a real platform refresh) and opens its document. */
    private Document open(Path file) {
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull("the task file is not visible in the VFS: " + file, virtual);
        Document document = FileDocumentManager.getInstance().getDocument(virtual);
        assertNotNull("no document is opened for " + file, document);
        return document;
    }

    private static JsonObject args(Object... pairs) {
        JsonObject out = JsonObject.empty();
        for (int i = 0; i < pairs.length; i += 2)
            out.put((String) pairs[i], new JsonString((String) pairs[i + 1]));
        return out;
    }

    private static String str(JsonValue value, String key) {
        return ((JsonString) ((JsonObject) value).get(key)).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field found = target.getClass().getDeclaredField(name);
            found.setAccessible(true);
            return (T) found.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + name + " seam", e);
        }
    }
}
