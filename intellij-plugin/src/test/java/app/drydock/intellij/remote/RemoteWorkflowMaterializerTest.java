package app.drydock.intellij.remote;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.MaterializedWorkflow;
import app.drydock.sdlc.MaterializedWorkflow.RemoteReference;
import app.drydock.sdlc.ResourceManifestJournal;
import app.drydock.sdlc.SelectedWorkflow;
import app.drydock.sdlc.SessionWorkflowResourceOwner;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.sdlc.WorkflowLimits;
import app.drydock.sdlc.WorkflowMaterializationRequest;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer.Cancelled;
import app.drydock.sdlc.WorkflowMaterializer.Failure;
import app.drydock.sdlc.WorkflowMaterializer.Reason;
import app.drydock.sdlc.WorkflowMaterializer.Result;
import app.drydock.sdlc.WorkflowMaterializer.Success;
import app.drydock.sdlc.WorkflowTarget;
import app.drydock.workspace.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real publication/stager over a fake CommandRunner that maps ssh to a local sh with HOME at a temp "box". */
class RemoteWorkflowMaterializerTest {
    private static final String STAGER = Path.of("src/main/resources/drydock/remote/workflow_stage.py")
            .toAbsolutePath().toString();
    private final ManagedSessionId session = ManagedSessionId.newId();
    private final AtomicBoolean reachable = new AtomicBoolean(true);
    private final AtomicLong generation = new AtomicLong(1);
    private final Cancellation cancel = new Cancellation();
    private volatile Runnable afterStage = () -> { };
    private Path home;
    private ResourceManifestJournal journal;

    private final CommandRunner box = (argv, options, cap) -> {
        if (!reachable.get()) {
            throw new IOException("box unreachable");
        }
        if (argv.get(0).equals("scp")) {
            Files.copy(Path.of(argv.get(argv.size() - 2)),
                    Path.of(argv.get(argv.size() - 1).substring("box:".length())));
            return new ProcessResult(0, "", "");
        }
        ProcessResult r = ProcessRunner.run(List.of("sh", "-c", argv.get(argv.size() - 1)),
                new ProcessRunner.Options(null, options.timeout(), true, Map.of("HOME", home.toString())), cap);
        if (argv.get(argv.size() - 1).contains("workflow_stage.py")) {
            afterStage.run();
        }
        return r;
    };

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        home = Files.createDirectories(dir.resolve("home"));
        journal = ResourceManifestJournal.open(dir.resolve("journal.txt"));
    }

    private RemoteWorkflowMaterializer materializer() {
        return new RemoteWorkflowMaterializer(new RemoteWorkflowPublication(box, STAGER), box, "box", "inst1",
                session, journal, generation::get, Runnable::run);
    }

    private static WorkflowMaterializationRequest request(Destination d, long bytes) {
        byte[] doc = "# doc\n".getBytes(StandardCharsets.UTF_8);
        WorkflowBundle bundle = WorkflowBundle.of(List.of(new WorkflowBundle.Directory("skill"),
                new WorkflowBundle.File("skill/doc.md", doc, WorkflowBundle.sha256Hex(doc), false)));
        var wf = new SelectedWorkflow(RepositoryId.newId(), WorkflowTarget.ISSUE_IMPLEMENT,
                new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.FILE, "k", Optional.of("a/b.md")),
                "prompt", bundle, "digest-1");
        return new WorkflowMaterializationRequest("inv-1", 1, wf, d, SessionMode.WRITES, Optional.empty());
    }

    private Path workflows() {
        return home.resolve(".local/share/drydock/inst1/sessions/" + session + "/workflows");
    }

    private boolean noTree() throws Exception {
        if (!Files.exists(workflows())) {
            return true;
        }
        try (var s = Files.list(workflows())) {
            return s.findAny().isEmpty();
        }
    }

    private Result run(WorkflowMaterializationRequest r) {
        return materializer().materialize(r, cancel).join();
    }

    @Test
    void sameRequestYieldsRemoteTextReferencesOnlyAndMatchesLocalRefusal() throws Exception {
        WorkflowMaterializationRequest r = request(Destination.WORKSPACE_BOX, 0);
        MaterializedWorkflow w = assertInstanceOf(Success.class, run(r)).workflow();
        assertEquals(Destination.WORKSPACE_BOX, w.destination());
        assertEquals(r.workflow().digest(), w.snapshotDigest());
        assertTrue(w.references().stream().allMatch(x -> x instanceof RemoteReference));
        String root = ((RemoteReference) w.references().get(0)).remotePath();
        assertTrue(Files.exists(Path.of(root, "skill/doc.md")));
        assertEquals(root + "/a/b.md", ((RemoteReference) w.references().get(1)).remotePath());
        assertFalse(w.toString().contains("prompt"), "no prompt/taskGuidance in the output");
        var lease = journal.leases().get(0);
        assertEquals(0, lease.localBytes());
        assertEquals(r.workflow().bundle().totalBytes(), lease.remoteBytes());
        Failure f = assertInstanceOf(Failure.class, run(request(Destination.LOCAL, 0)));
        assertEquals(Reason.UNSUPPORTED_DESTINATION, f.reason());
    }

    @Test
    void unreachableCleanupBecomesDebtThenReconnectDeletes() throws Exception {
        MaterializedWorkflow w = assertInstanceOf(Success.class, run(request(Destination.WORKSPACE_BOX, 0))).workflow();
        String root = ((RemoteReference) w.references().get(0)).remotePath();
        String name = journal.leases().get(0).bundleId();
        reachable.set(false);
        assertTrue(w.lease().release().handle((v, e) -> e != null).join());
        assertEquals(1, journal.debts().size());
        assertEquals(name, journal.debts().get(0).target());
        assertEquals(1, journal.leases().size(), "remote bytes stay accounted while cleanup is owed");
        assertTrue(Files.exists(Path.of(root)));
        var owner = new SessionWorkflowResourceOwner(journal, Runnable::run, new SessionWorkflowResourceOwner.Cleaner() {
            public CompletableFuture<Void> deleteLocal(ResourceManifestJournal.Lease l) {
                return CompletableFuture.completedFuture(null);
            }

            public CompletableFuture<Void> deleteRemote(ResourceManifestJournal.Debt d) {
                try {
                    box.run(List.of("ssh", "box", "rm -rf '" + root + "'"), new ProcessRunner.Options(null,
                            java.time.Duration.ofSeconds(10), true, Map.of()), 4096);
                    return CompletableFuture.completedFuture(null);
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
        });
        owner.retryDebts().join();
        assertEquals(1, journal.debts().size(), "still unreachable: debt is kept, not treated as deleted");
        assertTrue(Files.exists(Path.of(root)));
        reachable.set(true);
        owner.retryDebts().join();
        assertTrue(journal.debts().isEmpty());
        assertTrue(journal.leases().isEmpty());
        assertFalse(Files.exists(Path.of(root)));
    }

    @Test
    void ceilingsCountRemoteCopiesAndRefuseBeforeAnyTransfer() throws Exception {
        journal.putPending("other", "r1", 0, WorkflowLimits.RETAINED_AGGREGATE_BYTES);
        Failure f = assertInstanceOf(Failure.class, run(request(Destination.WORKSPACE_BOX, 0)));
        assertEquals(Reason.LIMIT_EXCEEDED, f.reason());
        assertFalse(Files.exists(home.resolve(".local")), "nothing was transferred");
        journal.remove("other", "r1");
        for (int i = 0; i < WorkflowLimits.RETAINED_BUNDLES_PER_SESSION; i++) {
            journal.putPending(session.toString(), "r" + i, 0, 1);
        }
        assertEquals(Reason.LIMIT_EXCEEDED,
                assertInstanceOf(Failure.class, run(request(Destination.WORKSPACE_BOX, 0))).reason());
        assertEquals(WorkflowLimits.RETAINED_BUNDLES_PER_SESSION, journal.leases().size(), "no eviction");
    }

    @Test
    void cancellationAndSupersessionReleaseWhatWasPublished() throws Exception {
        afterStage = cancel::cancel;
        assertEquals(Cancelled.INSTANCE, run(request(Destination.WORKSPACE_BOX, 0)));
        assertTrue(noTree());
        assertTrue(journal.leases().isEmpty());
        afterStage = () -> generation.incrementAndGet();
        generation.set(1);
        assertEquals(Cancelled.INSTANCE, new RemoteWorkflowMaterializer(new RemoteWorkflowPublication(box, STAGER),
                box, "box", "inst1", session, journal, generation::get, Runnable::run)
                .materialize(request(Destination.WORKSPACE_BOX, 0), Cancellation.none()).join());
        assertTrue(noTree(), "late supersession removes the published tree");
        assertTrue(journal.leases().isEmpty());
    }
}
