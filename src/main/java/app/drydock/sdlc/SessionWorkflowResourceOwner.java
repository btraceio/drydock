package app.drydock.sdlc;

import app.drydock.sdlc.ResourceManifestJournal.Debt;
import app.drydock.sdlc.ResourceManifestJournal.Lease;
import app.drydock.sdlc.ResourceManifestJournal.Recovery;
import app.drydock.sdlc.ResourceManifestJournal.State;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Application-owned owner of retained workflow resources, local and remote copies accounted jointly. Leases
 * arrive from an invocation only on accepted or ambiguous delivery; a refused lease is released by the
 * owner, never traded for an older retained one (no eviction). Detach, dialog closure and the next invocation
 * have no entry point here: only {@link #deleteSession} releases retained resources, and only its namespace.
 * All durable state goes through the plugin {@link ResourceManifestJournal}, never ApplicationState.
 */
public final class SessionWorkflowResourceOwner {

    public enum Outcome { ACCEPTED, REFUSED_CAP, REFUSED_JOURNAL, REFUSED_DUPLICATE }

    /** Cleanup for resources without an in-memory lease (after restart) and for remote copies; may block off-UI. */
    public interface Cleaner {
        CompletableFuture<Void> deleteLocal(Lease lease);

        CompletableFuture<Void> deleteRemote(Debt target);
    }

    private final ResourceManifestJournal journal;
    private final Executor executor;
    private final Cleaner cleaner;
    private final Map<String, WorkflowResourceLease> live = new HashMap<>();
    private boolean dirty;

    public SessionWorkflowResourceOwner(ResourceManifestJournal journal, Executor executor, Cleaner cleaner) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner");
    }

    /**
     * Adopts retained leases and debts from {@code recovery}; provably abandoned pending leases and a corrupt
     * journal's unknown content are cleaned by the caller-supplied cleaner (pending only; corrupt deletes nothing).
     */
    public synchronized CompletableFuture<Void> recover(Recovery recovery) {
        journal.adopt(recovery);
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (Lease abandoned : recovery.abandonedPending()) {
            work.add(CompletableFuture.runAsync(() -> { }, executor)
                    .thenCompose(v -> cleaner.deleteLocal(abandoned)));
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    /**
     * Takes cleanup responsibility for an accepted or ambiguously consumed bundle. Counts local and remote
     * bytes together; a refusal releases the lease (the pending invocation's artifacts) and keeps every
     * retained resource.
     */
    public synchronized Outcome accept(String session, String bundleId, long localBytes, long remoteBytes,
                                       WorkflowResourceLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (journal.leases().stream().anyMatch(l -> l.session().equals(session) && l.bundleId().equals(bundleId))) {
            return refuse(lease, Outcome.REFUSED_DUPLICATE);
        }
        long size = localBytes + remoteBytes;
        int count = 0;
        long sessionBytes = 0;
        long total = 0;
        for (Lease l : journal.leases()) {
            long b = l.localBytes() + l.remoteBytes();
            total += b;
            if (l.session().equals(session)) {
                count++;
                sessionBytes += b;
            }
        }
        if (localBytes < 0 || remoteBytes < 0 || count + 1 > WorkflowLimits.RETAINED_BUNDLES_PER_SESSION
                || sessionBytes + size > WorkflowLimits.RETAINED_SESSION_BYTES
                || total + size > WorkflowLimits.RETAINED_AGGREGATE_BYTES) {
            return refuse(lease, Outcome.REFUSED_CAP);
        }
        journal.putPending(session, bundleId, localBytes, remoteBytes);
        try {
            journal.flush();
        } catch (IOException e) {
            journal.remove(session, bundleId);
            return refuse(lease, Outcome.REFUSED_JOURNAL);
        }
        if (!lease.transferToSession()) {
            journal.remove(session, bundleId);
            return Outcome.REFUSED_DUPLICATE;
        }
        journal.retain(session, bundleId);
        live.put(session + "\n" + bundleId, lease);
        try {
            journal.flush();
        } catch (IOException e) {
            dirty = true;
        }
        return Outcome.ACCEPTED;
    }

    private Outcome refuse(WorkflowResourceLease lease, Outcome outcome) {
        CompletableFuture.runAsync(lease::release, executor);
        return outcome;
    }

    /** Resume precondition: the conversation's retained bundles; throws if none (never recreates or evicts). */
    public synchronized List<Lease> requireRetained(String session) {
        List<Lease> out = new ArrayList<>();
        boolean debt = journal.debts().stream().anyMatch(d -> d.session().equals(session));
        for (Lease l : journal.leases()) {
            if (l.session().equals(session) && l.state() == State.RETAINED && !debt) {
                out.add(l);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("no retained workflow resources for session " + session);
        }
        return out;
    }

    /**
     * Session deletion: releases only this session's bundles, asynchronously. A failed remote cleanup keeps
     * its remote bytes accounted and records debt for {@link #retryDebts}; it is never treated as deleted.
     */
    public synchronized CompletableFuture<Void> deleteSession(String session) {
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (Lease l : journal.leases()) {
            if (!l.session().equals(session)) {
                continue;
            }
            WorkflowResourceLease mem = live.remove(session + "\n" + l.bundleId());
            journal.remove(session, l.bundleId());
            CompletableFuture<Void> local = CompletableFuture.supplyAsync(() -> mem, executor)
                    .thenCompose(m -> m != null ? m.release() : cleaner.deleteLocal(l));
            if (l.remoteBytes() > 0) {
                Debt target = new Debt(session, l.bundleId(), "remote cleanup pending");
                journal.putPending(session, l.bundleId(), 0, l.remoteBytes());
                journal.retain(session, l.bundleId());
                journal.addDebt(target);
                work.add(local.thenCompose(v -> remote(target)));
            } else {
                work.add(local);
            }
        }
        flushQuietly();
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    /** Retries every outstanding remote cleanup, e.g. on the next authorized connection. */
    public synchronized CompletableFuture<Void> retryDebts() {
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (Debt d : journal.debts()) {
            work.add(remote(d));
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> remote(Debt target) {
        return CompletableFuture.supplyAsync(() -> target, executor).thenCompose(cleaner::deleteRemote)
                .handle((v, err) -> {
                    if (err == null) {
                        clearDebt(target);
                    }
                    return (Void) null;
                });
    }

    private synchronized void clearDebt(Debt target) {
        List<Debt> rest = new ArrayList<>(journal.debts());
        rest.removeIf(d -> d.equals(target));
        journal.clearDebts(target.session());
        rest.stream().filter(d -> d.session().equals(target.session())).forEach(journal::addDebt);
        journal.remove(target.session(), target.target());
        flushQuietly();
    }

    /** Persists pending journal changes; callers use it at shutdown and tests use it to observe state. */
    public synchronized void flush() throws IOException {
        journal.flush();
        dirty = false;
    }

    public synchronized boolean hasUnflushedChanges() {
        return dirty;
    }

    private void flushQuietly() {
        try {
            journal.flush();
        } catch (IOException e) {
            dirty = true;
        }
    }
}
