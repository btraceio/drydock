package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpActivityLog;
import app.drydock.mcp.McpServer;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.McpSessionRegistry;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.mcp.McpToolRouter;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.mcp.RemoteAuthorizationPermit.StaleAuthorizationException;
import app.drydock.mcp.RemoteMcpTransportPolicy;
import app.drydock.mcp.WorkspaceMcpSessionContext;
import app.drydock.review.AnnotationStore;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.tour.TourStore;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * The remote MCP endpoint of a {@link ReviewBackend}: its own session
 * registry, router and {@link McpServer}, serving exactly
 * {@link McpToolRouter#REVIEW_TOOLS_AND_RENAME} with {@link Spawn#FORBIDDEN}
 * credentials -- no task tool, no task instructions or guidance, no
 * worktree/session spawning. It exposes the backend's SAME stores and scope
 * registry (one writer), never copies of them. The stores stay owned by the
 * backend, so {@link #close()} stops only this endpoint.
 *
 * <p>Renames go through a {@link RemoteRenamer}, never the local renamer. The
 * adapter reads the request's {@link RemoteAuthorizationPermit} on the
 * dispatch thread, before the renamer's asynchronous boundary, and hands it
 * over as an ordinary argument. Starting a rename is not a commit point: the
 * guarded commit is the renamer's, and a response lost after that commit is an
 * unknown outcome for the caller, never a reason to replay.</p>
 */
public final class RemoteMcpComposition implements AutoCloseable {

    /** The remote rename write, supplied by production wiring; commits only through the permit's fence. */
    @FunctionalInterface
    public interface RemoteRenamer {
        CompletableFuture<RenameOutcome> rename(ManagedSessionId session, String validatedTitle,
                                                RemoteAuthorizationPermit permit);
    }

    private final AnnotationStore annotationStore;
    private final TourStore tourStore;
    private final PendingQuestions pendingQuestions;
    private final SelectionAsks selectionAsks;
    private final ReviewScopeRegistry scopeRegistry;
    private final McpSessionRegistry registry = new McpSessionRegistry();
    private final McpToolRouter router;
    private final McpServer server;

    RemoteMcpComposition(AnnotationStore annotationStore, TourStore tourStore, PendingQuestions pendingQuestions,
                         SelectionAsks selectionAsks, ReviewScopeRegistry scopeRegistry,
                         WorkspaceMcpSessionContext context, RemoteMcpTransportPolicy policy,
                         RemoteAuthorizationPermit.Seams seams) {
        this.annotationStore = annotationStore;
        this.tourStore = tourStore;
        this.pendingQuestions = pendingQuestions;
        this.selectionAsks = selectionAsks;
        this.scopeRegistry = scopeRegistry;
        this.router = new McpToolRouter(context, registry, McpToolRouter.REVIEW_TOOLS_AND_RENAME);
        this.server = new McpServer(registry, router, new McpActivityLog(), policy, seams);
    }

    /**
     * The adapter to the context's rename function. The permit is read here,
     * when the router dispatches on the request's own thread; it is bound to
     * the authenticated session or the rename fails closed -- there is no
     * unguarded fallback.
     */
    static BiFunction<ManagedSessionId, String, CompletableFuture<RenameOutcome>> renameAdapter(
            RemoteRenamer renamer) {
        Objects.requireNonNull(renamer, "renamer");
        return (session, title) -> {
            Optional<RemoteAuthorizationPermit> permit = RemoteAuthorizationPermit.Bound.current();
            if (permit.isEmpty() || !permit.get().sessionId().equals(session)) {
                return CompletableFuture.failedFuture(new StaleAuthorizationException());
            }
            return renamer.rename(session, title, permit.get());
        };
    }

    public AnnotationStore annotationStore() {
        return annotationStore;
    }

    public TourStore tourStore() {
        return tourStore;
    }

    public PendingQuestions pendingQuestions() {
        return pendingQuestions;
    }

    public SelectionAsks selectionAsks() {
        return selectionAsks;
    }

    public ReviewScopeRegistry scopeRegistry() {
        return scopeRegistry;
    }

    public McpServer server() {
        return server;
    }

    /** This endpoint's token for a session; always {@link Spawn#FORBIDDEN}. */
    public String mint(ManagedSessionId session) {
        return registry.mint(session, Spawn.FORBIDDEN);
    }

    public void revoke(ManagedSessionId session) {
        registry.revoke(session);
    }

    /** Whether the router serves {@code tool} on this endpoint. */
    public boolean serves(String tool) {
        return router.serves(tool);
    }

    @Override
    public void close() {
        server.close();
    }
}
