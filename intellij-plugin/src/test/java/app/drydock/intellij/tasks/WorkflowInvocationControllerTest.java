package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.WorkflowInvocationController.Prepared;
import app.drydock.intellij.tasks.WorkflowInvocationController.Request;
import app.drydock.intellij.tasks.WorkflowInvocationState.Input;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.MaterializedWorkflow;
import app.drydock.sdlc.ResourceManifestJournal;
import app.drydock.sdlc.ResourceManifestJournal.Debt;
import app.drydock.sdlc.ResourceManifestJournal.Lease;
import app.drydock.sdlc.SelectedWorkflow;
import app.drydock.sdlc.SessionWorkflowResourceOwner;
import app.drydock.sdlc.SessionWorkflowResourceOwner.Outcome;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.sdlc.WorkflowMaterializationRequest;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer;
import app.drydock.sdlc.WorkflowResolution;
import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowResolution.WorkflowProblem;
import app.drydock.sdlc.WorkflowResourceLease;
import app.drydock.sdlc.WorkflowTarget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowInvocationControllerTest {

    @TempDir
    Path tmp;

    private final ExecutorService background = Executors.newCachedThreadPool(r -> new Thread(r, "fake-bg"));
    private final ExecutorService uiThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-ui"));
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<WorkflowMaterializer.Result>> pendingMaterializations = new ArrayList<>();
    private final AtomicInteger released = new AtomicInteger();
    private final Input in = new Input("ISSUE_IMPLEMENT", "claude");
    private final Request request = new Request(WorkflowTarget.ISSUE_IMPLEMENT, Destination.LOCAL,
            SessionMode.WRITES, Optional.empty());
    private WorkflowResolution resolution = new WorkflowResolution.Selected(selected("project text"));
    private boolean resolverThrows;
    private ResourceManifestJournal journal;

    @AfterEach
    void stop() {
        background.shutdownNow();
        uiThread.shutdownNow();
    }

    private static SelectedWorkflow selected(String text) {
        return new SelectedWorkflow(RepositoryId.newId(), WorkflowTarget.ISSUE_IMPLEMENT,
                new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty()),
                text, WorkflowBundle.empty(), "digest");
    }

    private MaterializedWorkflow materialized(WorkflowMaterializationRequest r) {
        var lease = new WorkflowResourceLease(() -> {
            released.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        return new MaterializedWorkflow(r.invocationId(), r.generation(), "digest", r.destination(),
                WorkflowTarget.ISSUE_IMPLEMENT, r.mode(), List.of(), lease);
    }

    private WorkflowMaterializer materializer() {
        return (req, cancel) -> {
            threads.add("materialize:" + Thread.currentThread().getName());
            events.add("materialize");
            var f = new CompletableFuture<WorkflowMaterializer.Result>();
            synchronized (pendingMaterializations) {
                pendingMaterializations.add(f);
            }
            return f;
        };
    }

    private WorkflowInvocationController controller(long timeoutMillis) {
        journal = ResourceManifestJournal.open(tmp.resolve("sdlc/manifest.rmj"));
        var owner = new SessionWorkflowResourceOwner(journal, Runnable::run, new SessionWorkflowResourceOwner.Cleaner() {
            public CompletableFuture<Void> deleteLocal(Lease l) {
                return CompletableFuture.completedFuture(null);
            }

            public CompletableFuture<Void> deleteRemote(Debt d) {
                return CompletableFuture.completedFuture(null);
            }
        });
        var ui = new WorkflowInvocationController.Ui() {
            public void progress(boolean shown) {
                threads.add("progress:" + Thread.currentThread().getName());
                events.add("progress:" + shown);
            }

            public void ready(String seed, boolean edited) {
                events.add("ready:" + seed + ":" + edited);
            }

            public void invalidSource(WorkflowProblem problem) {
                events.add("invalid");
            }

            public void failed(String message) {
                events.add("failed");
            }
        };
        return new WorkflowInvocationController(background, uiThread,
                (target, cancel) -> {
                    threads.add("resolve:" + Thread.currentThread().getName());
                    events.add("resolve");
                    if (resolverThrows) {
                        throw new IllegalStateException("boom");
                    }
                    return resolution;
                }, materializer(), owner, ui, timeoutMillis, "default text", in);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "timed out; events=" + events);
    }

    private void complete(int index, WorkflowMaterializer.Result r) {
        synchronized (pendingMaterializations) {
            pendingMaterializations.get(index).complete(r);
        }
    }

    private WorkflowMaterializer.Result okFor(String text) {
        return new WorkflowMaterializer.Success(materialized(new WorkflowMaterializationRequest("inv", 1,
                selected(text), Destination.LOCAL, SessionMode.WRITES, Optional.empty())));
    }

    private void awaitMaterializations(int n) throws InterruptedException {
        await(() -> {
            synchronized (pendingMaterializations) {
                return pendingMaterializations.size() >= n;
            }
        });
    }

    @Test
    void progressShownBeforeWorkAndAllWorkOffTheCallerThread() throws Exception {
        var c = controller(5000);
        c.prepare(request, in);
        awaitMaterializations(1);
        assertEquals("progress:true", events.get(0), "progress precedes resolver and materializer");
        complete(0, okFor("project text"));
        await(() -> events.contains("ready:project text:false"));
        assertTrue(events.indexOf("progress:false") < events.indexOf("ready:project text:false"));
        assertEquals("progress:" + Thread.currentThread().getName(), threads.get(0));
        assertTrue(threads.contains("resolve:fake-bg") && threads.contains("materialize:fake-bg"), "" + threads);
        assertFalse(threads.stream().anyMatch(t -> t.endsWith(Thread.currentThread().getName()) && !t.startsWith("progress")));
        assertTrue(events.indexOf("resolve") < events.indexOf("materialize"), "resolver then materializer");
    }

    @Test
    void acceptedLaunchHandsTheLeaseToTheSessionOwner() throws Exception {
        var c = controller(5000);
        c.prepare(request, in);
        awaitMaterializations(1);
        var ok = (WorkflowMaterializer.Success) okFor("project text");
        complete(0, ok);
        await(() -> events.contains("ready:project text:false"));
        assertTrue(c.accept().isPresent());
        assertEquals(Optional.of(Outcome.ACCEPTED), c.commitLaunch("s1", "b1"));
        assertEquals(WorkflowResourceLease.Owner.RETAINED_SESSION, ok.workflow().lease().owner());
        assertEquals(1, journal.leases().size());
        assertEquals(0, released.get(), "handoff retains, never releases");
        c.dispose();
        assertEquals(0, released.get(), "dispose after handoff leaves the session's lease alone");
    }

    @Test
    void outOfOrderCompletionOnlyLastPreparationWinsAndLateLeaseIsReleased() throws Exception {
        var c = controller(5000);
        c.prepare(request, in);
        awaitMaterializations(1);
        c.prepare(request, new Input("ISSUE_IMPLEMENT", "codex"));
        awaitMaterializations(2);
        var second = (WorkflowMaterializer.Success) okFor("second");
        complete(1, second);
        await(() -> events.contains("ready:project text:false"));
        var first = (WorkflowMaterializer.Success) okFor("first");
        complete(0, first);
        await(() -> released.get() == 1);
        assertEquals(WorkflowResourceLease.Owner.RELEASED, first.workflow().lease().owner());
        assertEquals(WorkflowResourceLease.Owner.PENDING_INVOCATION, second.workflow().lease().owner());
        assertEquals(1, events.stream().filter(e -> e.startsWith("ready:")).count());
    }

    @Test
    void resolverExceptionClearsProgressAndReportsFailure() throws Exception {
        resolverThrows = true;
        var c = controller(5000);
        c.prepare(request, in);
        await(() -> events.contains("failed"));
        assertTrue(events.indexOf("progress:false") < events.indexOf("failed"));
        assertFalse(events.contains("materialize"));
        assertTrue(c.accept().isEmpty());
    }

    @Test
    void invalidSourceOffersDefaultOnceOrCancelAndNeverMaterializes() throws Exception {
        resolution = new WorkflowResolution.Invalid(new WorkflowProblem("k", Optional.empty(),
                ProblemCategory.BLANK, "blank"));
        var c = controller(5000);
        c.prepare(request, in);
        await(() -> events.contains("invalid"));
        assertEquals(1, events.stream().filter("progress:false"::equals).count());
        assertFalse(events.contains("materialize"));
        assertTrue(c.accept().isEmpty(), "no silent fallback before an explicit choice");
        c.chooseDefaultOnce();
        assertTrue(events.contains("ready:default text:false"));
        var accepted = c.accept().orElseThrow();
        assertTrue(accepted.defaultOnce());
        assertEquals(Optional.of(Outcome.ACCEPTED), c.commitLaunch("s", "b"));
        assertEquals(0, journal.leases().size(), "default-once has no resources to retain");
    }

    @Test
    void cancelWhileWaitingForChoiceForbidsLaterDefault() throws Exception {
        resolution = new WorkflowResolution.Invalid(new WorkflowProblem("k", Optional.empty(),
                ProblemCategory.BLANK, "blank"));
        var c = controller(5000);
        c.prepare(request, in);
        await(() -> events.contains("invalid"));
        c.cancel();
        c.chooseDefaultOnce();
        assertTrue(c.accept().isEmpty());
        assertTrue(events.stream().noneMatch(e -> e.startsWith("ready:")));
    }

    private WorkflowInvocationController ready(String text) throws Exception {
        int n = pendingMaterializations.size();
        long before = events.stream().filter("ready:project text:false"::equals).count();
        var c = controller(5000);
        c.prepare(request, in);
        awaitMaterializations(n + 1);
        complete(n, okFor(text));
        await(() -> events.stream().filter("ready:project text:false"::equals).count() > before);
        return c;
    }

    @Test
    void userEditSurvivesIntoTheAcceptedPromptAndHandoff() throws Exception {
        var c = ready("project text");
        assertTrue(c.edit("my edit"));
        var accepted = c.accept().orElseThrow();
        assertEquals("my edit", accepted.text());
        assertTrue(accepted.edited());
        assertEquals(Optional.of(Outcome.ACCEPTED), c.commitLaunch("s", "b"));
    }

    @Test
    void cancelReleasesThePendingLeaseAndALateResultReleasesOnlyItself() throws Exception {
        var c = controller(5000);
        c.prepare(request, in);
        awaitMaterializations(1);
        c.cancel();
        await(() -> events.contains("progress:false"));
        var late = (WorkflowMaterializer.Success) okFor("late");
        complete(0, late);
        await(() -> released.get() == 1);
        assertEquals(WorkflowResourceLease.Owner.RELEASED, late.workflow().lease().owner());
        assertTrue(events.stream().noneMatch(e -> e.startsWith("ready:")));
        var c2 = ready("project text");
        c2.cancel();
        assertEquals(2, released.get(), "cancel releases the pending lease exactly once");
        assertTrue(c2.commitLaunch("s", "b").isEmpty());
    }

    @Test
    void disposeReleasesClearsProgressAndIgnoresLaterWork() throws Exception {
        var c = ready("project text");
        c.dispose();
        assertEquals(1, released.get());
        c.prepare(request, in);
        c.chooseDefaultOnce();
        assertTrue(c.accept().isEmpty());
        assertEquals(1, events.stream().filter("progress:true"::equals).count(), "no work after dispose");
        var d = controller(5000);
        d.prepare(request, in);
        awaitMaterializations(2);
        d.dispose();
        await(() -> events.lastIndexOf("progress:false") > events.lastIndexOf("progress:true"));
        complete(1, okFor("late"));
        await(() -> released.get() == 2);
    }

    @Test
    void timeoutCancelsClearsProgressAndReleasesTheLateResult() throws Exception {
        var c = controller(50);
        c.prepare(request, in);
        await(() -> events.contains("failed"));
        assertTrue(events.indexOf("progress:false") < events.indexOf("failed"));
        var late = (WorkflowMaterializer.Success) okFor("late");
        complete(0, late);
        await(() -> released.get() == 1);
        assertTrue(c.accept().isEmpty());
    }

    @Test
    void materializerFailureClearsProgressAndDuplicateRefusalReleasesTheLease() throws Exception {
        var c = controller(5000);
        c.prepare(request, in);
        awaitMaterializations(1);
        complete(0, new WorkflowMaterializer.Failure(WorkflowMaterializer.Reason.IO_FAILURE, "disk"));
        await(() -> events.contains("failed"));
        assertTrue(events.indexOf("progress:false") < events.indexOf("failed"));
        assertTrue(c.accept().isEmpty());
        var d = ready("project text");
        journal.putPending("s", "b", 0, 0);
        assertTrue(d.accept().isPresent());
        assertEquals(Optional.of(Outcome.REFUSED_DUPLICATE), d.commitLaunch("s", "b"));
        assertEquals(1, released.get(), "refusal releases the lease");
    }

    @Test
    void staleFailureNeverReachesTheUi() throws Exception {
        var c = controller(5000);
        c.prepare(request, in);
        awaitMaterializations(1);
        c.prepare(request, new Input("ISSUE_IMPLEMENT", "codex"));
        awaitMaterializations(2);
        complete(0, new WorkflowMaterializer.Failure(WorkflowMaterializer.Reason.IO_FAILURE, "stale"));
        complete(1, okFor("second"));
        await(() -> events.contains("ready:project text:false"));
        uiThread.submit(() -> { }).get();
        assertFalse(events.contains("failed"));
        assertEquals(2, events.stream().filter("progress:true"::equals).count());
        assertEquals(1, events.stream().filter("progress:false"::equals).count());
    }
}
