package app.drydock.intellij.review;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.DiffService;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.mcp.McpConfigWriter;
import app.drydock.mcp.McpServer;
import app.drydock.mcp.McpSessionRegistry;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.mcp.McpToolRouter;
import app.drydock.mcp.WorkspaceMcpSessionContext;
import app.drydock.review.AnnotationStore;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.tour.TourStore;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The review stack of the IntelliJ plugin: the annotation and tour stores,
 * the git services, and a loopback MCP server that serves only the review
 * tools ({@link McpToolRouter#REVIEW_TOOLS}).
 *
 * <p>Construction is blocking, because the store constructors read their
 * files; call it on the plugin executor. {@link #start()} binds the server
 * and never throws. Until it succeeds {@link #ready()} is false and {@link
 * #accessFor} returns empty. After {@link #close()} the backend stays
 * not ready for good: {@link #start()} does nothing and {@link #accessFor}
 * returns empty. Never logs a token, the endpoint URL or the
 * port.</p>
 */
public final class ReviewBackend implements SessionMcpAccess, AutoCloseable {

    private static final Logger LOG = System.getLogger(ReviewBackend.class.getName());

    private final AnnotationStore annotationStore;
    private final TourStore tourStore;
    private final DiffService diffService;
    private final GitStatusService gitStatusService;
    private final WorktreeService worktreeService;
    private final McpSessionRegistry mcpRegistry;
    private final McpConfigWriter configWriter;
    private final McpServer server;
    private final SessionReviewScopeResolver scopeResolver;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Package-private so tests can replace it; production uses {@link McpConfigWriter}. */
    CredentialWriter credentialWriter;
    private volatile boolean ready;

    public ReviewBackend(Path baseDir, Supplier<List<ManagedAgentSession>> sessions,
                         Supplier<List<Repository>> repositories, AgentRegistry agentRegistry) {
        Path stateFile = baseDir.resolve("state.json");
        annotationStore = new AnnotationStore(AnnotationStore.siblingOf(stateFile));
        tourStore = new TourStore(TourStore.siblingOf(stateFile));
        ReviewScopeRegistry scopeRegistry = new ReviewScopeRegistry(annotationStore.scopeIdSecret());
        diffService = new DiffService();
        gitStatusService = new GitStatusService();
        worktreeService = new WorktreeService();
        // Nothing in the plugin mints asks yet, so the callback has no work.
        PendingQuestions pendingQuestions = new PendingQuestions(answered -> { });
        mcpRegistry = new McpSessionRegistry();
        configWriter = new McpConfigWriter(baseDir);
        // Both deliveries get a file so a credential is never a command-line
        // argument; COMMAND_LINE gets the bare token.
        credentialWriter = (id, delivery, endpointUrl, token) -> delivery == McpDelivery.COMMAND_LINE
                ? configWriter.writeTokenFor(id, token)
                : configWriter.writeFor(id, endpointUrl, token);
        // The router built below serves only REVIEW_TOOLS, so it never calls
        // the session-management callbacks; each one fails if it is reached.
        WorkspaceMcpSessionContext context = new WorkspaceMcpSessionContext(
                sessions,
                repositories,
                annotationStore,
                tourStore,
                scopeRegistry,
                agentRegistry,
                diffService,
                gitStatusService,
                worktreeService,
                UserConfig::load,
                (worktree, prompt) -> notServed("session_start"),
                (session, name) -> notServed("session_rename"),
                (session, conversation) -> notServed("session_reclaim"),
                (session, draft) -> notServed("session_handoff"),
                (session, draft) -> notServed("session_handoff"),
                pendingQuestions);
        McpToolRouter router = new McpToolRouter(context, mcpRegistry, McpToolRouter.REVIEW_TOOLS);
        server = new McpServer(mcpRegistry, router);
        scopeResolver = new SessionReviewScopeResolver(worktreeService, gitStatusService,
                scopeRegistry, sessions);
    }

    private static <T> CompletableFuture<T> notServed(String tool) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(tool + " is not served by the IntelliJ plugin"));
    }

    /**
     * Purges credential files left by a previous run, then binds the server.
     * On failure the server is closed, {@link #ready()} stays false and the
     * failure is logged. After {@link #close()} this does nothing and {@link
     * #ready()} stays false. Blocking; never throws.
     */
    public void start() {
        if (closed.get()) {
            return;
        }
        try {
            configWriter.purgeStale();
            server.start();
            // close() may have run while the server was binding.
            ready = !closed.get();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Drydock MCP server unavailable; sessions launch without Drydock tools", e);
            server.close();
        }
    }

    public boolean ready() {
        return ready;
    }

    @Override
    public Optional<McpAccess> accessFor(ManagedSessionId sessionId, McpDelivery delivery) {
        if (closed.get() || !ready || delivery == McpDelivery.NONE) {
            return Optional.empty();
        }
        String token = mcpRegistry.mint(sessionId, Spawn.FORBIDDEN);
        try {
            // Both deliveries get a file so a credential is never a
            // command-line argument; COMMAND_LINE gets the bare token.
            Path credentialFile = credentialWriter.write(sessionId, delivery, server.endpointUrl(), token);
            return Optional.of(new McpAccess(server.endpointUrl(), token, Optional.of(credentialFile)));
        } catch (IOException | RuntimeException e) {
            // Never log the token or the endpoint URL (it carries the port),
            // nor the exception itself: a message could echo either.
            mcpRegistry.revoke(sessionId);
            configWriter.delete(sessionId);
            LOG.log(Level.WARNING, "Could not write MCP config for session " + sessionId
                    + "; launching without Drydock tools: " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Writes the credential file for one delivery; a seam so tests can force a failed write. */
    @FunctionalInterface
    interface CredentialWriter {
        Path write(ManagedSessionId sessionId, McpDelivery delivery, String endpointUrl, String token)
                throws IOException;
    }

    /**
     * Whether {@code sessionId} holds a token from this backend: minted by
     * {@link #accessFor} and not revoked since, by a failed credential write
     * or by {@link #release}. A failed credential write (an {@link IOException}
     * or a {@link RuntimeException} in {@link #accessFor}) revokes the token
     * and deletes the session's credential files. False after {@link #close()} and while not
     * {@linkplain #ready() ready}. A map lookup; never blocks, never logs.
     */
    public boolean holdsAccess(ManagedSessionId sessionId) {
        return !closed.get() && ready && mcpRegistry.tokenFor(sessionId).isPresent();
    }

    @Override
    public void release(ManagedSessionId sessionId) {
        mcpRegistry.revoke(sessionId);
        configWriter.delete(sessionId);
    }

    public SessionReviewScopeResolver scopeResolver() {
        return scopeResolver;
    }

    public void flushPendingSaves() {
        annotationStore.flushPendingSaves();
        tourStore.flushPendingSaves();
    }

    /**
     * Marks the backend not ready, then closes the server first and the stores
     * and git services after it; one failing step does not skip the rest.
     * Only the first call does anything; later calls return silently.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ready = false;
        closeEach(List.of(server, annotationStore, tourStore, diffService, gitStatusService, worktreeService));
    }

    static void closeEach(List<AutoCloseable> steps) {
        for (AutoCloseable step : steps) {
            try {
                step.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Closing " + step.getClass().getSimpleName() + " failed", e);
            }
        }
    }

    /** For tests: the annotation store. */
    AnnotationStore annotationStore() {
        return annotationStore;
    }

    /** For tests: the socket the server is bound to. */
    InetSocketAddress boundAddress() {
        return server.boundAddress();
    }
}
