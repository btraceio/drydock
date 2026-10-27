package app.drydock.intellij.review;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpDelivery;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.review.ReviewRequest.Failed;
import app.drydock.intellij.review.ReviewRequest.NoTools;
import app.drydock.intellij.review.ReviewRequest.NotReady;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReviewRequest#forBackend} against a real {@link ReviewBackend}: the wiring Review Changes uses. */
class ReviewRequestBackendTest {

    @TempDir
    Path base;

    private ExecutorService executor;
    private ReviewBackend backend;
    private final List<String> prompts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        AgentRegistry registry = new AgentRegistry(List.of(),
                new AgentContext(base, base.resolve("activity"), executor));
        backend = new ReviewBackend(base, List::of, List::of, registry);
    }

    @AfterEach
    void tearDown() {
        backend.close();
        executor.shutdownNow();
    }

    private ReviewRequest request(Optional<ReviewBackend> served) {
        return ReviewRequest.forBackend(CompletableFuture.completedFuture(null), () -> served,
                id -> true, kind -> true, (id, prompt) -> {
                    prompts.add(prompt);
                    return PromptOutcome.DELIVERED;
                });
    }

    private ManagedAgentSession session(ManagedSessionId id) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "Task",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(base), Instant.EPOCH)
                .status(SessionStatus.RUNNING).build();
    }

    @Test
    void noBackendIsNotReady() {
        assertInstanceOf(NotReady.class, request(Optional.empty()).request(session(ManagedSessionId.newId()), true));
    }

    @Test
    void aBackendThatIsNotStartedIsNotReady() {
        assertInstanceOf(NotReady.class, request(Optional.of(backend)).request(session(ManagedSessionId.newId()), true));
    }

    @Test
    void aSessionTheBackendNeverMintedForGetsNoTools() {
        backend.start();
        assertInstanceOf(NoTools.class, request(Optional.of(backend)).request(session(ManagedSessionId.newId()), true));
        assertTrue(prompts.isEmpty());
    }

    @Test
    void aSessionWhoseAccessWasReleasedGetsNoTools() {
        backend.start();
        ManagedSessionId id = ManagedSessionId.newId();
        backend.accessFor(id, McpDelivery.CONFIG_FILE).orElseThrow();
        backend.release(id);

        assertInstanceOf(NoTools.class, request(Optional.of(backend)).request(session(id), true));
    }

    @Test
    void aSessionHoldingAccessPassesTheGateToScopeResolution() {
        backend.start();
        ManagedSessionId id = ManagedSessionId.newId();
        backend.accessFor(id, McpDelivery.CONFIG_FILE).orElseThrow();

        // The temporary directory is not a git checkout, so the scope fails to
        // resolve: the request got past the access check.
        assertInstanceOf(Failed.class, request(Optional.of(backend)).request(session(id), true));
        assertTrue(prompts.isEmpty());
    }
}
