package app.drydock.sdlc;

import app.drydock.sdlc.MaterializedWorkflow.LocalReference;
import app.drydock.sdlc.MaterializedWorkflow.Reference;
import app.drydock.sdlc.MaterializedWorkflow.Role;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * {@link WorkflowMaterializer} for LOCAL and LOCAL_EVAL. It allocates one namespace per invocation under the
 * plugin-owned per-session staging directory, publishes through {@link WorkflowStagingLayout}, and records a
 * PENDING lease in the {@link ResourceManifestJournal}. LOCAL gets host paths; LOCAL_EVAL gets the same layout
 * rooted at the container-visible read-only mount point. Retained-count/byte ceilings are checked against the
 * journal before any bytes are written. A cancelled or superseded invocation never hands out its lease: whatever
 * was published is released. Promoting the journal entry to RETAINED is the session owner's job on transfer.
 */
public final class LocalWorkflowMaterializer implements WorkflowMaterializer {

    private final Path stagingRoot;
    private final String session;
    private final ResourceManifestJournal journal;
    private final Path evalMount;
    private final LongSupplier currentGeneration;
    private final Executor executor;

    public LocalWorkflowMaterializer(Path stagingRoot, String session, ResourceManifestJournal journal,
            Path evalMount, LongSupplier currentGeneration, Executor executor) {
        this.stagingRoot = Objects.requireNonNull(stagingRoot, "stagingRoot");
        this.session = Objects.requireNonNull(session, "session");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.evalMount = Objects.requireNonNull(evalMount, "evalMount");
        this.currentGeneration = Objects.requireNonNull(currentGeneration, "currentGeneration");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** The staged bundle name for an invocation: shared with the launcher's hand-off (TaskLauncher). */
    public static String bundleName(String invocationId) {
        return "wf-" + WorkflowBundle.sha256Hex(invocationId.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    @Override
    public CompletableFuture<Result> materialize(WorkflowMaterializationRequest request, Cancellation cancellation) {
        return CompletableFuture.supplyAsync(() -> run(request, cancellation), executor)
                .exceptionally(e -> new Failure(Reason.IO_FAILURE, String.valueOf(e.getMessage())));
    }

    private Result run(WorkflowMaterializationRequest request, Cancellation cancellation) {
        if (request.destination() == Destination.WORKSPACE_BOX) {
            return new Failure(Reason.UNSUPPORTED_DESTINATION, "local materializer cannot serve a workspace box");
        }
        if (cancellation.isCancelled() || request.generation() != currentGeneration.getAsLong()) {
            return Cancelled.INSTANCE;
        }
        WorkflowBundle bundle = request.workflow().bundle();
        String name = bundleName(request.invocationId());
        Path sessionDir = stagingRoot.resolve(URLEncoder.encode(session, StandardCharsets.UTF_8));
        synchronized (journal) {
            Failure refused = checkCeilings(bundle.totalBytes());
            if (refused != null) {
                return refused;
            }
            journal.putPending(session, name, bundle.totalBytes(), 0);
        }
        WorkflowStagingLayout.Published published;
        try {
            journal.flush();
            published = WorkflowStagingLayout.publish(sessionDir, name, bundle, request.workflow().promptText());
        } catch (IOException | RuntimeException e) {
            cleanup(sessionDir.resolve(name), name).join();
            return new Failure(Reason.IO_FAILURE, String.valueOf(e.getMessage()));
        }
        WorkflowResourceLease lease = new WorkflowResourceLease(() -> cleanup(published.root(), name));
        if (cancellation.isCancelled() || request.generation() != currentGeneration.getAsLong()) {
            lease.release().join();
            return Cancelled.INSTANCE;
        }
        boolean eval = request.destination() == Destination.LOCAL_EVAL;
        Path root = eval ? evalMount.resolve(name) : published.root();
        List<Reference> refs = new ArrayList<>();
        refs.add(new LocalReference(Role.RESOURCE, root.resolve(WorkflowStagingLayout.TREE_DIR)));
        request.workflow().source().relativeSource().ifPresent(rel -> refs.add(
                new LocalReference(Role.SOURCE, root.resolve(WorkflowStagingLayout.TREE_DIR).resolve(rel))));
        refs.add(new LocalReference(Role.INSTRUCTION_FILE, root.resolve(WorkflowStagingLayout.INSTRUCTION_FILE)));
        return new Success(new MaterializedWorkflow(request.invocationId(), request.generation(),
                request.workflow().digest(), request.destination(), request.workflow().target(), request.mode(),
                refs, lease));
    }

    /** Called under the journal monitor; counts only this materializer's recorded bytes, never evicts. */
    private Failure checkCeilings(long bytes) {
        long sessionBytes = 0;
        long aggregate = 0;
        int count = 0;
        for (ResourceManifestJournal.Lease l : journal.leases()) {
            aggregate += l.localBytes();
            if (l.session().equals(session)) {
                sessionBytes += l.localBytes();
                count++;
            }
        }
        if (count >= WorkflowLimits.RETAINED_BUNDLES_PER_SESSION
                || sessionBytes + bytes > WorkflowLimits.RETAINED_SESSION_BYTES
                || aggregate + bytes > WorkflowLimits.RETAINED_AGGREGATE_BYTES) {
            return new Failure(Reason.LIMIT_EXCEEDED, "retained workflow resource ceiling reached");
        }
        return null;
    }

    private CompletableFuture<Void> cleanup(Path root, String name) {
        try {
            WorkflowStagingLayout.remove(root);
            journal.remove(session, name);
            journal.flush();
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException e) {
            journal.addDebt(new ResourceManifestJournal.Debt(session, root.toString(), String.valueOf(e)));
            return CompletableFuture.failedFuture(e);
        }
    }
}
