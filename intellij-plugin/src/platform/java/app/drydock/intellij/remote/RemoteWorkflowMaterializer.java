package app.drydock.intellij.remote;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.remote.RemoteWorkflowPublication.Failed;
import app.drydock.intellij.remote.RemoteWorkflowPublication.Published;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.SshCommandBuilder;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.MaterializedWorkflow;
import app.drydock.sdlc.MaterializedWorkflow.Reference;
import app.drydock.sdlc.MaterializedWorkflow.RemoteReference;
import app.drydock.sdlc.MaterializedWorkflow.Role;
import app.drydock.sdlc.ResourceManifestJournal;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.sdlc.WorkflowLimits;
import app.drydock.sdlc.WorkflowMaterializationRequest;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer;
import app.drydock.sdlc.WorkflowResourceLease;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.CommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * {@link WorkflowMaterializer} for WORKSPACE_BOX: the same {@link WorkflowMaterializationRequest} as the local
 * materializer, published through {@link RemoteWorkflowPublication}. Remote bytes are recorded as a PENDING
 * lease in the {@link ResourceManifestJournal} before any transfer and ceilings count local plus remote bytes of
 * every journal lease. The returned lease is the pending invocation's; the session owner takes it only on
 * accepted/ambiguous delivery. A cleanup that cannot reach the box keeps its lease accounted and becomes
 * journal debt (target = bundle name) which the owner retries on reconnect. The result holds remote path text only.
 */
public final class RemoteWorkflowMaterializer implements WorkflowMaterializer {
    private static final String UNKNOWN_OUTCOME = "remote staging outcome unknown";

    private final RemoteWorkflowPublication publication;
    private final CommandRunner runner;
    private final String host;
    private final String installationId;
    private final ManagedSessionId session;
    private final ResourceManifestJournal journal;
    private final LongSupplier currentGeneration;
    private final Executor executor;

    public RemoteWorkflowMaterializer(RemoteWorkflowPublication publication, CommandRunner runner, String host,
            String installationId, ManagedSessionId session, ResourceManifestJournal journal,
            LongSupplier currentGeneration, Executor executor) {
        this.publication = Objects.requireNonNull(publication, "publication");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.host = Objects.requireNonNull(host, "host");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        this.session = Objects.requireNonNull(session, "session");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.currentGeneration = Objects.requireNonNull(currentGeneration, "currentGeneration");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<Result> materialize(WorkflowMaterializationRequest request, Cancellation cancellation) {
        return CompletableFuture.supplyAsync(() -> run(request, cancellation), executor)
                .exceptionally(e -> new Failure(Reason.IO_FAILURE, String.valueOf(e.getMessage())));
    }

    private Result run(WorkflowMaterializationRequest request, Cancellation cancellation) {
        if (request.destination() != Destination.WORKSPACE_BOX) {
            return new Failure(Reason.UNSUPPORTED_DESTINATION, "remote materializer serves only a workspace box");
        }
        if (stale(request, cancellation)) {
            return Cancelled.INSTANCE;
        }
        WorkflowBundle bundle = request.workflow().bundle();
        String sessionKey = session.toString();
        String name = "wf-" + WorkflowBundle.sha256Hex(request.invocationId().getBytes(StandardCharsets.UTF_8))
                .substring(0, 24);
        synchronized (journal) {
            if (overCeiling(sessionKey, bundle.totalBytes())) {
                return new Failure(Reason.LIMIT_EXCEEDED, "retained workflow resource ceiling reached");
            }
            journal.putPending(sessionKey, name, 0, bundle.totalBytes());
        }
        RemoteWorkflowPublication.Outcome outcome;
        try {
            journal.flush();
            outcome = publication.publish(host, installationId, session, name, bundle, cancellation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            journal.remove(sessionKey, name);
            return Cancelled.INSTANCE;
        } catch (IOException | RuntimeException e) {
            journal.remove(sessionKey, name);
            return new Failure(Reason.IO_FAILURE, String.valueOf(e.getMessage()));
        }
        if (outcome instanceof Failed f) {
            if (UNKNOWN_OUTCOME.equals(f.reason())) {
                // a tree may exist and the publisher's cleanup was best-effort: keep the bytes accounted as debt
                journal.addDebt(new ResourceManifestJournal.Debt(sessionKey, name, f.reason()));
            } else {
                journal.remove(sessionKey, name);
            }
            flushQuietly();
            return new Failure(Reason.IO_FAILURE, f.reason());
        }
        if (!(outcome instanceof Published p)) {
            journal.remove(sessionKey, name);
            flushQuietly();
            return Cancelled.INSTANCE;
        }
        WorkflowResourceLease lease = new WorkflowResourceLease(() -> cleanup(p.remoteRoot(), name));
        if (stale(request, cancellation)) {
            lease.release().exceptionally(e -> null).join();
            return Cancelled.INSTANCE;
        }
        List<Reference> refs = new ArrayList<>(p.references());
        request.workflow().source().relativeSource()
                .ifPresent(rel -> refs.add(new RemoteReference(Role.SOURCE, p.remoteRoot() + "/" + rel)));
        return new Success(new MaterializedWorkflow(request.invocationId(), request.generation(),
                request.workflow().digest(), request.destination(), request.workflow().target(), request.mode(),
                refs, lease));
    }

    private boolean stale(WorkflowMaterializationRequest request, Cancellation cancellation) {
        return cancellation.isCancelled() || request.generation() != currentGeneration.getAsLong();
    }

    /** Under the journal monitor; every lease counts local + remote bytes, nothing is evicted. */
    private boolean overCeiling(String sessionKey, long bytes) {
        long sessionBytes = 0;
        long aggregate = 0;
        int count = 0;
        for (ResourceManifestJournal.Lease l : journal.leases()) {
            long b = l.localBytes() + l.remoteBytes();
            aggregate += b;
            if (l.session().equals(sessionKey)) {
                sessionBytes += b;
                count++;
            }
        }
        return count >= WorkflowLimits.RETAINED_BUNDLES_PER_SESSION
                || sessionBytes + bytes > WorkflowLimits.RETAINED_SESSION_BYTES
                || aggregate + bytes > WorkflowLimits.RETAINED_AGGREGATE_BYTES;
    }

    /** Deletes the remote tree; an unreachable box keeps the lease's bytes accounted and records debt. */
    private CompletableFuture<Void> cleanup(String remoteRoot, String name) {
        String sessionKey = session.toString();
        try {
            ProcessResult r = runner.run(SshCommandBuilder.remoteCommand(host, List.of("rm", "-rf", "--", remoteRoot)),
                    new ProcessRunner.Options(null, BoxShell.SHORT_COMMAND_TIMEOUT, true, Map.of()), 4096);
            if (r.exitCode() != 0) {
                throw new IOException("remote delete exited " + r.exitCode());
            }
            journal.remove(sessionKey, name);
            flushQuietly();
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return debt(sessionKey, name, e);
        } catch (IOException | RuntimeException e) {
            return debt(sessionKey, name, e);
        }
    }

    private CompletableFuture<Void> debt(String sessionKey, String name, Exception e) {
        journal.addDebt(new ResourceManifestJournal.Debt(sessionKey, name, String.valueOf(e)));
        flushQuietly();
        return CompletableFuture.failedFuture(e);
    }

    private void flushQuietly() {
        try {
            journal.flush();
        } catch (IOException e) {
            // in-memory journal still holds the state; the owner's flush retries
        }
    }
}
