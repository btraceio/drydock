package app.drydock.sdlc;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.review.ReviewIntent;
import app.drydock.review.tour.ReviewTour;
import app.drydock.sdlc.MaterializedWorkflow.LocalReference;
import app.drydock.sdlc.MaterializedWorkflow.RemoteReference;
import app.drydock.sdlc.MaterializedWorkflow.Role;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer.Cancelled;
import app.drydock.sdlc.WorkflowMaterializer.Failure;
import app.drydock.sdlc.WorkflowMaterializer.Result;
import app.drydock.sdlc.WorkflowMaterializer.Success;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowMaterializerContractTest {

    private static final AtomicInteger CLEANUPS = new AtomicInteger();

    /** Fake that records the request it saw and answers per destination type. */
    private static final class Fake implements WorkflowMaterializer {
        WorkflowMaterializationRequest seen;

        @Override
        public CompletableFuture<Result> materialize(WorkflowMaterializationRequest r, Cancellation c) {
            seen = r;
            if (c.isCancelled()) {
                return CompletableFuture.completedFuture(Cancelled.INSTANCE);
            }
            if (r.destination() == Destination.LOCAL_EVAL && r.mode() == SessionMode.WRITES) {
                return CompletableFuture.completedFuture(
                        new Failure(Reason.UNSUPPORTED_DESTINATION, "eval is read-only"));
            }
            boolean remote = r.destination() == Destination.WORKSPACE_BOX;
            List<MaterializedWorkflow.Reference> refs = remote
                    ? List.of(new RemoteReference(Role.RESOURCE, "/home/box/.drydock/wf/1"))
                    : List.of(new LocalReference(Role.RESOURCE, Path.of("/tmp/wf-1")));
            WorkflowResourceLease lease = new WorkflowResourceLease(() -> {
                CLEANUPS.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            return CompletableFuture.completedFuture(new Success(new MaterializedWorkflow(
                    r.invocationId(), r.generation(), r.workflow().digest(), r.destination(),
                    r.workflow().target(), r.mode(), refs, lease)));
        }
    }

    private static SelectedWorkflow workflow() {
        return new SelectedWorkflow(RepositoryId.newId(), WorkflowTarget.PULL_REQUEST_REVIEW,
                new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty()),
                "prompt", WorkflowBundle.empty(), "digest-1");
    }

    private static WorkflowMaterializationRequest request(SelectedWorkflow wf, Destination d, SessionMode m) {
        return new WorkflowMaterializationRequest("inv-1", 3, wf, d, m, Optional.empty());
    }

    private static MaterializedWorkflow success(Result r) {
        return assertInstanceOf(Success.class, r).workflow();
    }

    @Test
    void identicalRequestServesBothDestinationsWithTypedReferences() {
        SelectedWorkflow wf = workflow();
        Fake fake = new Fake();
        MaterializedWorkflow local = success(fake.materialize(
                request(wf, Destination.LOCAL, SessionMode.WRITES), Cancellation.none()).join());
        MaterializedWorkflow remote = success(fake.materialize(
                request(wf, Destination.WORKSPACE_BOX, SessionMode.WRITES), Cancellation.none()).join());
        assertEquals(local.snapshotDigest(), remote.snapshotDigest());
        assertEquals(local.invocationId(), remote.invocationId());
        assertInstanceOf(LocalReference.class, local.references().get(0));
        assertInstanceOf(RemoteReference.class, remote.references().get(0));
        assertSame(wf, fake.seen.workflow());
    }

    @Test
    void remoteOutputHasNoHostPathReceiptOrGuidance() {
        for (Class<?> c : List.of(MaterializedWorkflow.class, RemoteReference.class)) {
            for (var rc : c.getRecordComponents()) {
                String n = rc.getName().toLowerCase(Locale.ROOT);
                assertFalse(n.contains("receipt") || n.contains("guidance") || n.contains("credential"), n);
                if (c == RemoteReference.class) {
                    assertFalse(Path.class.isAssignableFrom(rc.getType()), n);
                }
            }
        }
        WorkflowResourceLease lease = new WorkflowResourceLease(() -> CompletableFuture.completedFuture(null));
        assertThrows(IllegalArgumentException.class, () -> new MaterializedWorkflow("i", 1, "d",
                Destination.WORKSPACE_BOX, WorkflowTarget.ISSUE_TRIAGE, SessionMode.READ_ONLY,
                List.of(new LocalReference(Role.SOURCE, Path.of("/h"))), lease));
        assertThrows(IllegalArgumentException.class, () -> new MaterializedWorkflow("i", 1, "d",
                Destination.LOCAL, WorkflowTarget.ISSUE_TRIAGE, SessionMode.READ_ONLY,
                List.of(new RemoteReference(Role.SOURCE, "/r")), lease));
    }

    @Test
    void failureAndCancellationAreDistinctResults() {
        Fake fake = new Fake();
        assertInstanceOf(Failure.class, fake.materialize(
                request(workflow(), Destination.LOCAL_EVAL, SessionMode.WRITES), Cancellation.none()).join());
        Cancellation cancelled = Cancellation.none();
        cancelled.cancel();
        assertSame(Cancelled.INSTANCE, fake.materialize(
                request(workflow(), Destination.LOCAL, SessionMode.WRITES), cancelled).join());
    }

    @Test
    void leaseTransfersOnceAndReleasesIdempotently() {
        CLEANUPS.set(0);
        Fake fake = new Fake();
        MaterializedWorkflow m = success(fake.materialize(
                request(workflow(), Destination.WORKSPACE_BOX, SessionMode.READ_ONLY), Cancellation.none()).join());
        WorkflowResourceLease lease = m.lease();
        assertEquals(WorkflowResourceLease.Owner.PENDING_INVOCATION, lease.owner());
        assertTrue(lease.transferToSession());
        assertFalse(lease.transferToSession());
        assertEquals(WorkflowResourceLease.Owner.RETAINED_SESSION, lease.owner());
        assertEquals(0, CLEANUPS.get());
        assertSame(lease.release(), lease.release());
        assertEquals(1, CLEANUPS.get());
        assertFalse(lease.transferToSession());
    }

    @Test
    void reviewContextIsBuiltFromIntentFields() {
        ManagedSessionId session = ManagedSessionId.newId();
        ReviewIntent intent = ReviewIntent.local(RepositoryId.newId(), new ReviewIntent.Remote("box:1"),
                Optional.of(session), ReviewIntent.Origin.WORKBENCH, ReviewTour.Kind.TOUR);
        var ctx = WorkflowMaterializationRequest.ReviewContext.from(intent, "scope-1");
        assertEquals(session, ctx.session());
        assertEquals(intent.target(), ctx.target());
        assertEquals(ReviewTour.Kind.TOUR, ctx.kind());
        assertThrows(IllegalArgumentException.class, () -> WorkflowMaterializationRequest.ReviewContext.from(
                ReviewIntent.local(RepositoryId.newId(), new ReviewIntent.Remote("b"), Optional.empty(),
                        ReviewIntent.Origin.WORKBENCH, ReviewTour.Kind.TOUR), "s"));
    }
}
