package app.drydock.app;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import app.drydock.mcp.McpConfigWriter;
import app.drydock.mcp.McpSessionRegistry;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fourth session-ending path: {@code claude} exits on its own -- the user
 * types {@code exit}, or it finishes -- and the surface deliberately stays
 * open so the final output can be read. {@code onSurfaceClosed} therefore
 * never runs, so {@link SessionManager#markSessionExited} is where the MCP
 * token and its config file have to go.
 *
 * <p>Without that, the file under {@code <base>/mcp/} keeps a live bearer
 * token on disk for as long as the tab stays open, and that token still
 * authorises the whole tool surface -- {@code worktree_create}, {@code
 * session_start} and the session's remaining budget included.</p>
 */
class SessionManagerExitReleasesMcpTest {

    private static final String ENDPOINT = "http://127.0.0.1:1/mcp";

    private ExecutorService backgroundExecutor;
    private McpSessionRegistry registry;
    private McpConfigWriter configWriter;

    @BeforeEach
    void setUp(@TempDir Path base) {
        backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();
        registry = new McpSessionRegistry();
        configWriter = new McpConfigWriter(base);
    }

    @AfterEach
    void tearDown() {
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
        }
    }

    @Test
    void aSelfExitedSessionLosesItsTokenAndItsConfigFile() throws Exception {
        ManagedAgentSession session = SessionManagerTest.newSessionFixture();
        SessionManager manager = managerWithRunning(session);
        String token = registry.mint(session.id(), Spawn.ALLOWED);
        Path config = configWriter.writeFor(session.id(), ENDPOINT, token);
        manager.useMcpConfig(configWriter, registry, ENDPOINT);
        assertTrue(Files.exists(config));
        assertTrue(registry.resolve(token).isPresent());

        Optional<ManagedAgentSession> exited = manager.markSessionExited(session.id());

        assertTrue(exited.isPresent(), "the fixture must actually have been RUNNING");
        awaitDeleted(config);
        assertTrue(registry.resolve(token).isEmpty(), "the token must stop resolving");
    }

    /**
     * Release lives in the success branch, so the release is not re-run for a
     * session that was already EXITED -- which is what makes the exit watcher's
     * repeated ticks harmless, and what stops a later resume's freshly minted
     * token from being deleted out from under it.
     */
    @Test
    void aSessionThatWasNotRunningReleasesNothing() throws Exception {
        ManagedAgentSession session = SessionManagerTest.newSessionFixture()
                .withStatus(SessionStatus.EXITED);
        SessionManager manager = managerFor(session);
        String token = registry.mint(session.id(), Spawn.ALLOWED);
        Path config = configWriter.writeFor(session.id(), ENDPOINT, token);
        manager.useMcpConfig(configWriter, registry, ENDPOINT);

        assertTrue(manager.markSessionExited(session.id()).isEmpty());

        // Nothing to wait for: assert the file is still there after a window in
        // which an unconditional release would have removed it.
        Thread.sleep(200);
        assertTrue(Files.exists(config), "an already-EXITED session must not re-release");
        assertTrue(registry.resolve(token).isPresent());
    }

    /** MCP not wired up at all: the exit path must still work. */
    @Test
    void anExitWithoutMcpWiringIsHarmless() {
        ManagedAgentSession session = SessionManagerTest.newSessionFixture();
        SessionManager manager = managerWithRunning(session);

        assertTrue(manager.markSessionExited(session.id()).isPresent());
    }

    private SessionManager managerFor(ManagedAgentSession session) {
        return new SessionManager(new StateRepository(List.of(session)), newRegistry(), backgroundExecutor);
    }

    /** A registry whose one provider can never find its executable, so nothing spawns. */
    private AgentRegistry newRegistry() {
        AgentContext ctx = new AgentContext(Path.of("/tmp/drydock-test"), Path.of("/tmp/drydock-test/activity"),
                backgroundExecutor);
        return new AgentRegistry(
                List.of(new ClaudeAgentProvider(new ClaudeExecutableLocator(Path.of("/nonexistent/claude")))), ctx);
    }

    /**
     * A manager whose session is RUNNING. It cannot simply be seeded that way:
     * the constructor normalizes a persisted RUNNING to INACTIVE, since no
     * terminal process survives a restart. The status is therefore set through
     * the store afterwards, exactly as a real launch does.
     */
    private SessionManager managerWithRunning(ManagedAgentSession session) {
        StateRepository stateRepository = new StateRepository(List.of(session));
        SessionManager manager = new SessionManager(stateRepository, newRegistry(), backgroundExecutor);
        ApplicationStateStore.forRepository(stateRepository).update(state -> state.withSessions(
                state.sessions().stream()
                        .map(candidate -> candidate.id().equals(session.id())
                                ? candidate.withStatus(SessionStatus.RUNNING)
                                : candidate)
                        .toList()));
        return manager;
    }

    /** The delete runs on the background executor (it is I/O), so this polls. */
    private static void awaitDeleted(Path file) throws InterruptedException {
        for (int i = 0; i < 250 && Files.exists(file); i++) {
            Thread.sleep(20);
        }
        assertFalse(Files.exists(file), "the mcp config file was never deleted: " + file);
    }

    /** Minimal in-memory state repository; the shared one is private to {@link SessionManagerTest}. */
    private static final class StateRepository implements ApplicationStateRepository {

        private volatile ApplicationState state;

        StateRepository(List<ManagedAgentSession> sessions) {
            this.state = ApplicationState.empty().withSessions(sessions);
        }

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState newState) {
            state = newState;
        }
    }
}
