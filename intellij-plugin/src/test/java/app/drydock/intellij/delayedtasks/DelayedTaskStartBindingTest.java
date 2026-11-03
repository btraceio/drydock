package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.DelayedTaskStart;
import app.drydock.delayedtasks.TaskLaunchBinding;
import app.drydock.domain.*;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.*;

import com.intellij.openapi.ui.TestDialogManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Path;

/**
 * The T31b matrix's store half, over the booted IDE's real Gradle-sandbox store (zero
 * mocking; pump-park waits only): the full 7-item T31a matrix maps 3-4-5-7 here and
 * 1-2-6 to DelayedTaskStartTest (a supervisor-granted two-file split, the T05c/T11b
 * precedent; the shared fixture is DelayedTaskStartFixture). (3) P6: every task id of
 * the segment binds to the one session BEFORE the spawn step fires -- the chain
 * composes the binds first, so the store read at the spawn step (where production
 * composes the console tab and the spawn) sees every task bound. (4) A duplicate
 * click and a start elsewhere over claimed work are visible no-ops, driven through
 * the real DelayedTaskStart.start() in the booted IDE. (5) The planner's cycle
 * refusal surfaces verbatim. (7) A cross-project plan refuses with the T38 delegation
 * text, writes nothing, and releases the claim. The package-private production glue
 * (the in-flight set and claim in DelayedTaskStart, the launch chain's bindingBound
 * in SessionDialogs) is reached reflectively where no seam exists (the T22b/T28b
 * precedent), pinned to the exact production branch it enters; the real agent spawn
 * stays with the T31a smoke.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DelayedTaskStartBindingTest extends DelayedTaskStartFixture {

    // 3. P6: every task id of the segment binds to the one session BEFORE the spawn
    //    step fires -- the chain composes the binds first, so the store read at the
    //    spawn step (where production composes the console tab and the spawn) sees
    //    every task bound.
    public void testEverySegmentTaskBindsBeforeTheSpawnStepFires() throws Exception {
        Chain chain = chain("t31b-p6", "Ship the feature");
        StartPlan.Segment segment = chain.plan().segments().get(0);
        List<TaskLaunchBinding> bindings = bindingsOf(segment);
        assertEquals("one binding per segment task, in the plan's order (P6: ALL of them)",
                List.of(chain.open(), chain.ship()), bindings.stream().map(TaskLaunchBinding::task).toList());
        ManagedAgentSession session = session("t31b-p6");
        CompletableFuture<ManagedAgentSession> launch = CompletableFuture.completedFuture(session);
        for (TaskLaunchBinding binding : bindings) { // exactly the launch chain's bind composition
            launch = launch.thenCompose(bound -> bindingBound(binding, bound));
        }
        final TaskSnapshot[] atSpawn = new TaskSnapshot[1];
        launch.thenApply(bound -> { // where the chain composes the console tab and the spawn
            try { atSpawn[0] = TaskFiles.scan(storeRoot); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            return bound;
        });
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            for (TaskId task : List.of(chain.open(), chain.ship())) {
                TaskDocument document = atSpawn[0].byId().get(task);
                assertTrue("at spawn time every task is started and linked to the one session",
                        document.status() == TaskStatus.STARTED
                                && document.sessions().equals(List.of(session.id().toString())));
                TaskRun receipt = runs.byTask(task).orElseThrow();
                assertTrue("both reservations are live PREPARE rows",
                        receipt.phase() == TaskRun.Phase.PREPARE && receipt.live());
            }
        }
    }

    // 4. A duplicate click and a start elsewhere over claimed work are visible no-ops.
    public void testDuplicateClickAndStartElsewhereAreVisibleNoOps() throws Exception {
        Chain chain = chain("t31b-dup", "Ship the feature");
        RepositoryId repository = chain.plan().segments().get(0).repository();
        TaskId elsewhere = write("t31b-else", "Also ship it", List.of(chain.open().text()), repository);
        List<TaskId> claimed = claim(chain.plan().segments().get(0)); // the in-flight start's claim
        assertEquals("the claim covers the whole segment", List.of(chain.open(), chain.ship()), claimed);
        List<String> shown = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> { shown.add(message); return 0; }, getTestRootDisposable());
        DelayedTaskStart.start(getProject(), row(chain.ship(), "Ship the feature", repository));
        DelayedTaskStart.start(getProject(), row(elsewhere, "Also ship it", repository));
        await(() -> shown.size() == 2, "both visible no-op refusals");
        assertTrue("the duplicate click refuses visibly", shown.stream().anyMatch(message ->
                message.contains("is already in flight") && message.contains("Ship the feature")));
        assertTrue("the start elsewhere refuses visibly", shown.stream().anyMatch(message ->
                message.contains("covers the plan") && message.contains("Also ship it")));
        assertEquals("the original claim stands: nothing more was claimed",
                new HashSet<>(claimed), inFlight());
        inFlight().removeAll(claimed);
        assertTrue("a released claim starts again", claim(chain.plan().segments().get(0)) != null);
        inFlight().removeAll(claimed);
    }

    // 5 and 7. Route refusals surface verbatim, write nothing and release the claim: the
    // planner's cycle text, and the cross-project delegation refusal (T38's scope).
    public void testCycleAndCrossProjectRefusalsAreVerbatimZeroWriteAndReleased() throws Exception {
        RepositoryId repository = RepositoryId.newId();
        TaskId a = write("t31b-cyc-a", "Cycle a", List.of("t31b-cyc-b"), repository);
        write("t31b-cyc-b", "Cycle b", List.of(a.text()), repository);
        RepositoryId here = RepositoryId.newId();
        TaskId theirs = write("t31b-foreign", "Their work", List.of(), RepositoryId.newId());
        TaskId mine = write("t31b-mine", "Ship here", List.of(theirs.text()), here);
        Map<String, String> before = settledTree();
        List<String> shown = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> { shown.add(message); return 0; }, getTestRootDisposable());
        DelayedTaskStart.start(getProject(), row(a, "Cycle a", repository));
        DelayedTaskStart.start(getProject(), row(mine, "Ship here", here));
        await(() -> shown.size() == 2, "both route refusals");
        String cycle = route(planOf(a)).orElseThrow();
        assertEquals("the cycle refusal is the planner's text, verbatim", cycle, shown.get(0));
        assertTrue("it names the cycle", cycle.contains("cycle"));
        assertEquals("the cross-project refusal is the T38 one, verbatim",
                crossProjectRefusal(), shown.get(1));
        assertEquals("nothing was written: no partial launch", before, settledTree());
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            assertTrue("no reservation receipt exists",
                    runs.byTask(mine).isEmpty() && runs.byTask(theirs).isEmpty());
        }
        assertTrue("the refused starts released their claims",
                !inFlight().contains(a) && !inFlight().contains(mine));
    }

    // ---- the store seams (no other file owns them) ----------------------------

    private Optional<String> route(StartPlan plan) {
        return (Optional<String>) seam(DelayedTaskStart.class, "route", new Class<?>[] {StartPlan.class}, plan);
    }

    /** The production bindingsOf: one pre-spawn binding per segment task (P6). */
    private List<TaskLaunchBinding> bindingsOf(StartPlan.Segment segment) {
        return (List<TaskLaunchBinding>) seam(DelayedTaskStart.class, "bindingsOf",
                new Class<?>[] {DelayedTaskService.class, StartPlan.Segment.class},
                plugin.taskService(), segment);
    }

    /** The launch chain's own bind step, composed exactly as launchSession composes it. */
    private static CompletableFuture<ManagedAgentSession> bindingBound(TaskLaunchBinding binding,
            ManagedAgentSession bound) {
        return (CompletableFuture<ManagedAgentSession>) seam(SessionDialogs.class, "bindingBound",
                new Class<?>[] {TaskLaunchBinding.class, ManagedAgentSession.class}, binding, bound);
    }

    private static String crossProjectRefusal() {
        return (String) staticField(DelayedTaskStart.class, "CROSS_PROJECT_REFUSAL");
    }

    private static DelayedTaskRow row(TaskId id, String title, RepositoryId repository) {
        return new DelayedTaskRow(new TaskState.TaskView(id, TaskState.Presentation.READY, List.of(),
                List.of()), title, repository, List.of());
    }

    /** One prepared session, headless, the way the T22b fixture builds one. */
    private static ManagedAgentSession session(String displayName) {
        Instant now = Instant.now();
        return new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), displayName,
                AgentBinding.unlaunched(AgentKind.PI),
                SessionWorkspace.inRepository(Path.of(System.getProperty("user.dir"))),
                SessionStatus.RUNNING, now, now, Optional.empty(), PrLink.none(), false, Optional.empty());
    }
}
