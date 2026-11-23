package app.drydock.sdlc;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.sdlc.MaterializedWorkflow.LocalReference;
import app.drydock.sdlc.MaterializedWorkflow.Role;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer.Cancelled;
import app.drydock.sdlc.WorkflowMaterializer.Failure;
import app.drydock.sdlc.WorkflowMaterializer.Result;
import app.drydock.sdlc.WorkflowMaterializer.Success;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkflowMaterializerTest {

    @TempDir
    Path tmp;

    private final AtomicLong generation = new AtomicLong(3);

    private ResourceManifestJournal journal() {
        return ResourceManifestJournal.open(tmp.resolve("journal"));
    }

    private LocalWorkflowMaterializer materializer(ResourceManifestJournal j) {
        return new LocalWorkflowMaterializer(tmp.resolve("staging"), "s1", j, Path.of("/drydock/wf"),
                generation::get, Runnable::run);
    }

    private static WorkflowMaterializationRequest request(Destination d, long gen) {
        byte[] body = "hi".getBytes(StandardCharsets.UTF_8);
        WorkflowBundle bundle = WorkflowBundle.of(List.of(
                new WorkflowBundle.File("skill/a.md", body, WorkflowBundle.sha256Hex(body), false)));
        SelectedWorkflow wf = new SelectedWorkflow(RepositoryId.newId(), WorkflowTarget.ISSUE_IMPLEMENT,
                new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.FILE, "k",
                        Optional.of("skill/a.md")),
                "method", bundle, "d1");
        return new WorkflowMaterializationRequest("inv-1", gen, wf, d, SessionMode.READ_ONLY, Optional.empty());
    }

    private static long entries(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void localGetsHostPathsAndEvalGetsMountReferencesOverTheSameStaging() throws Exception {
        ResourceManifestJournal j = journal();
        MaterializedWorkflow local = assertInstanceOf(Success.class,
                materializer(j).materialize(request(Destination.LOCAL, 3), Cancellation.none()).join()).workflow();
        LocalReference tree = (LocalReference) local.references().get(0);
        assertTrue(tree.path().startsWith(tmp.resolve("staging")));
        assertTrue(Files.isRegularFile(tree.path().resolve("skill/a.md")));
        assertEquals(WorkflowResourceLease.Owner.PENDING_INVOCATION, local.lease().owner());
        assertEquals(ResourceManifestJournal.State.PENDING, j.leases().get(0).state());
        assertEquals(1, ResourceManifestJournal.recover(tmp.resolve("journal")).abandonedPending().size());

        ResourceManifestJournal j2 = journal();
        MaterializedWorkflow eval = assertInstanceOf(Success.class, new LocalWorkflowMaterializer(
                tmp.resolve("staging2"), "s1", j2, Path.of("/drydock/wf"), generation::get, Runnable::run)
                .materialize(request(Destination.LOCAL_EVAL, 3), Cancellation.none()).join()).workflow();
        assertEquals(Role.RESOURCE, eval.references().get(0).role());
        for (MaterializedWorkflow.Reference r : eval.references()) {
            assertTrue(((LocalReference) r).path().startsWith("/drydock/wf"), r.toString());
        }
        assertTrue(((LocalReference) eval.references().get(1)).path().endsWith("tree/skill/a.md"));
        assertTrue(Files.exists(tmp.resolve("staging2")));
    }

    @Test
    void cancelledBeforeStartTouchesNothing() throws Exception {
        Cancellation c = Cancellation.none();
        c.cancel();
        assertSame(Cancelled.INSTANCE, materializer(journal()).materialize(request(Destination.LOCAL, 3), c).join());
        assertFalse(Files.exists(tmp.resolve("staging")));
    }

    @Test
    void lateCompletionOfSupersededGenerationOnlyReleases() throws Exception {
        ResourceManifestJournal j = journal();
        List<Runnable> queue = new java.util.ArrayList<>();
        LocalWorkflowMaterializer m = new LocalWorkflowMaterializer(tmp.resolve("staging"), "s1", j,
                Path.of("/e"), generation::get, queue::add);
        var future = m.materialize(request(Destination.LOCAL, 3), Cancellation.none());
        generation.set(4);
        queue.get(0).run();
        assertSame(Cancelled.INSTANCE, future.join());
        assertEquals(0, entries(tmp.resolve("staging").resolve("s1")));
        assertTrue(j.leases().isEmpty());
        assertTrue(ResourceManifestJournal.recover(tmp.resolve("journal")).abandonedPending().isEmpty());
    }

    @Test
    void cancelDuringPublishReleasesPublishedNamespace() throws Exception {
        Cancellation c = Cancellation.none();
        ResourceManifestJournal j = journal();
        AtomicLong calls = new AtomicLong();
        // The generation supplier is consulted before and after publication; the cancel lands after the pre-check read.
        LocalWorkflowMaterializer m = new LocalWorkflowMaterializer(tmp.resolve("staging"), "s1", j, Path.of("/e"),
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        c.cancel();
                    }
                    return 3;
                }, Runnable::run);
        assertSame(Cancelled.INSTANCE, m.materialize(request(Destination.LOCAL, 3), c).join());
        assertEquals(1, calls.get());
        assertEquals(0, entries(tmp.resolve("staging").resolve("s1")));
        assertTrue(j.leases().isEmpty());
    }

    @Test
    void retainedCeilingRefusesBeforeAnyStagingAndLeaseReleaseCleans() throws Exception {
        ResourceManifestJournal j = journal();
        j.putPending("s1", "old", WorkflowLimits.RETAINED_SESSION_BYTES, 0);
        j.retain("s1", "old");
        Result r = materializer(j).materialize(request(Destination.LOCAL, 3), Cancellation.none()).join();
        assertEquals(WorkflowMaterializer.Reason.LIMIT_EXCEEDED, assertInstanceOf(Failure.class, r).reason());
        assertFalse(Files.exists(tmp.resolve("staging")));
        assertEquals(1, j.leases().size());

        ResourceManifestJournal full = journal();
        for (int i = 0; i < WorkflowLimits.RETAINED_BUNDLES_PER_SESSION; i++) {
            full.putPending("s1", "b" + i, 1, 0);
        }
        assertInstanceOf(Failure.class,
                materializer(full).materialize(request(Destination.LOCAL, 3), Cancellation.none()).join());
        assertFalse(Files.exists(tmp.resolve("staging")));

        ResourceManifestJournal ok = journal();
        MaterializedWorkflow w = assertInstanceOf(Success.class,
                materializer(ok).materialize(request(Destination.LOCAL, 3), Cancellation.none()).join()).workflow();
        w.lease().release().join();
        assertEquals(0, entries(tmp.resolve("staging").resolve("s1")));
        assertTrue(ok.leases().isEmpty());
    }

    @Test
    void workspaceBoxIsUnsupported() {
        Result r = materializer(journal()).materialize(request(Destination.WORKSPACE_BOX, 3), Cancellation.none())
                .join();
        assertEquals(WorkflowMaterializer.Reason.UNSUPPORTED_DESTINATION, assertInstanceOf(Failure.class, r).reason());
    }
}
