package app.drydock.intellij.remote;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Outcome;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Request;
import app.drydock.intellij.remote.RemoteForwardOwner.ForwardException;
import app.drydock.intellij.remote.RemoteForwardOwner.Prepared;
import app.drydock.intellij.review.RemoteMcpComposition;
import app.drydock.intellij.review.RemoteMcpComposition.RemoteRenamer;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.mcp.RemoteMcpTransportPolicy;
import app.drydock.review.RemoteReviewDataSource;
import app.drydock.review.RemoteSnapshotCache;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Application-scoped facade over the remote review transport (INV-40..42): one isolated forward and one
 * credential generation per enrolled remote session, served by the remote MCP endpoint over the shared
 * snapshot cache and data source. {@link #ensure} is the single boundary that brings up a live, proven
 * forward and publishes a current-generation credential. The local task
 * endpoint is never touched here, so it never receives a remote credential. No tmux is ever killed here.
 */
public final class RemoteReviewService implements AutoCloseable {

    /** The collaborators the remote endpoint is composed from (production wiring supplies them). */
    public record Wiring(Supplier<List<ManagedAgentSession>> sessions, Supplier<List<Repository>> repositories,
                         AgentRegistry agentRegistry, RemoteRenamer renamer) { }

    /** An enrolled remote session to serve. */
    public record Session(String host, String installationId, ManagedSessionId id, String agentSessionId,
                          String launchNonce, int bridgeProtocol, String helperPath,
                          Supplier<Verdict> enrollment) { }

    /** A live, ready transport: the forward's port, the published generation and the shared data. */
    public record Ready(int remotePort, long generation, RemoteReviewDataSource dataSource,
                        RemoteSnapshotCache snapshots) { }

    private final RemoteMcpComposition composition;
    private final RemoteReviewDataSource dataSource;
    private final RemoteSnapshotCache cache;
    private final RemoteForwardOwner forwards;
    private final RemoteCredentialGenerations generations;
    private final Object lock = new Object();
    private final Map<ManagedSessionId, Prepared> live = new HashMap<>();
    private final Map<ManagedSessionId, Long> epochs = new HashMap<>();
    private boolean closed;

    public RemoteReviewService(ReviewBackend backend, Wiring wiring, RemoteReviewDataSource dataSource,
                               RemoteSnapshotCache cache, RemoteForwardOwner forwards,
                               RemoteCredentialGenerations generations) {
        this.generations = Objects.requireNonNull(generations, "generations");
        this.composition = backend.remoteComposition(wiring.sessions(), wiring.repositories(),
                wiring.agentRegistry(), wiring.renamer(), RemoteMcpTransportPolicy.REMOTE,
                new RemoteAuthorizationPermit.Seams(generations::permitFor, null));
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.forwards = Objects.requireNonNull(forwards, "forwards");
    }

    public RemoteMcpComposition composition() {
        return composition;
    }

    public void start() throws IOException {
        composition.server().start();
    }

    /** Flushes the review stores the remote endpoint writes through (shared with the backend). */
    public void flush() {
        composition.annotationStore().flushPendingSaves();
    }

    /** Blocking (call off the UI thread). Reuses a live current-generation transport; else prepares one. */
    public Ready ensure(Session s) throws ForwardException, InterruptedException {
        long epoch;
        synchronized (lock) {
            if (closed) {
                throw new ForwardException("cancelled: service closed");
            }
            Prepared existing = live.get(s.id());
            if (existing != null && existing.isReady() && generations.permitFor(s.id()).isPresent()) {
                return ready(existing, generations.permitFor(s.id()).get().generation());
            }
            epoch = epochs.merge(s.id(), 1L, Long::sum);
        }
        Prepared p = forwards.prepare(s.id().toString(), s.host(), composition.server().port(),
                () -> superseded(s.id(), epoch));
        Outcome outcome;
        try {
            outcome = generations.publish(new Request(s.host(), s.installationId(), s.id(), s.agentSessionId(),
                    s.launchNonce(), p.remotePort(), s.bridgeProtocol(), s.helperPath()), s.enrollment(),
                    () -> superseded(s.id(), epoch));
        } catch (InterruptedException | RuntimeException e) {
            p.close();
            generations.revoke(s.id());
            throw e;
        }
        boolean keep;
        synchronized (lock) {
            keep = outcome == Outcome.PUBLISHED && !superseded(s.id(), epoch);
            if (keep) {
                live.put(s.id(), p);
            }
        }
        if (keep) {
            p.onInvalidated(() -> generations.revoke(s.id()));
            return ready(p, p.generation());
        }
        p.close();
        generations.revoke(s.id());
        throw new ForwardException("remote credentials not published: " + outcome);
    }

    /** DETACH keeps the transport (the tmux session lives on); the rest tear it down for that session. */
    public enum ReleaseOutcome { DETACH, DEATH, STOP, FORGET }

    /** Closes the session's forward, revokes its credentials and drops its cache; fences an in-flight ensure. */
    public void release(Session s, ReleaseOutcome outcome) {
        if (outcome == ReleaseOutcome.DETACH) {
            return;
        }
        Prepared p;
        synchronized (lock) {
            epochs.merge(s.id(), 1L, Long::sum);
            p = live.remove(s.id());
        }
        if (p != null) {
            p.close();
        }
        dispose(s.id());
    }

    private void dispose(ManagedSessionId id) {
        generations.revoke(id);
        composition.revoke(id);
        cache.removeSession(id.toString());
    }

    private Ready ready(Prepared p, long generation) {
        return new Ready(p.remotePort(), generation, dataSource, cache);
    }

    private boolean superseded(ManagedSessionId id, long epoch) {
        synchronized (lock) {
            return closed || epochs.getOrDefault(id, 0L) != epoch;
        }
    }

    @Override
    public void close() {
        List<ManagedSessionId> ids;
        synchronized (lock) {
            closed = true;
            ids = List.copyOf(live.keySet());
            live.clear();
        }
        forwards.close();
        ids.forEach(this::dispose);
        composition.close();
    }
}
