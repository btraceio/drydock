package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.*;
import app.drydock.mcp.*;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.tasks.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The T12b committed matrix: the accepted DelayedTaskService (T12a) over real
 * files under TempDir roots, no mocking, per the six contract items.
 */
class DelayedTaskServiceTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // 1. Init happy path: the root is created with the right layout, and a create round-trip commits.
    @Test
    void initCreatesTheStoreAndARoundTripCommits(@TempDir Path root) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(service.initialize());
            assertTrue(Files.isDirectory(root) && await(service.snapshot()).documents().isEmpty(),
                    "initialization creates the store root and it scans empty");
            TaskId prerequisite = await(service.create("Refresh the token", List.of(), repository))
                    .document().id();
            TaskToolContext.Outcome created = await(service.create("Fix the login redirect",
                    List.of(prerequisite.text()), repository));
            assertFalse(created.refused(), created.refusal());
            TaskId id = created.document().id();
            assertTrue(id.text().startsWith("fix-the-login-redirect-"), id.text());
            assertTrue(Files.exists(committedFile(root, repository, id)),
                    "the layout is <root>/<repository-id>/<id>.md");
            TaskDocument stored = await(service.snapshot()).byId().get(id);
            assertNotNull(stored, "the committed write is what the next scan sees");
            assertEquals(List.of(prerequisite.text()), stored.after());
            assertTrue(stored.sessions().isEmpty() && stored.doneBy() == DoneBy.HUMAN,
                    "a created task carries no sessions and defaults to human validation");
        }
    }

    // 1a. A prerequisite that names no task refuses the create, and nothing is written.
    @Test
    void unresolvedPrerequisiteRefusesTheCreate(@TempDir Path root) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        try (DelayedTaskService service = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(service.initialize());
            TaskToolContext.Outcome refused = await(service.create("Follow up",
                    List.of("<this-pr-110-task-id>"), repository));
            assertTrue(refused.refused(), "an unresolved reference is refused");
            assertTrue(refused.refusal().contains("'<this-pr-110-task-id>'"), refused.refusal());
            assertTrue(await(service.snapshot()).documents().isEmpty(), "nothing was created");
        }
    }

    // 2. Init failure: an unwritable root degrades visibly; init re-attempts safely.
    @Test
    void unwritableRootDegradesAndInitIsReattemptable(@TempDir Path dir) throws Exception {
        Path locked = Files.createDirectories(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            try (DelayedTaskService service = new DelayedTaskService(locked.resolve("tasks"),
                    executor, List::of, List::of)) {
                assertTrue(failureOf(service.initialize()).length() > 0, "init fails visibly, never hangs");
                assertTrue(failureOf(service.snapshot()).contains("failed to initialize"),
                        "reads refuse visibly with the reason");
                assertTrue(failureOf(service.create("Never lands", List.of(), RepositoryId.newId()))
                        .contains("failed to initialize"), "mutations fail visibly, never silently");
                assertTrue(failureOf(service.initialize()).length() > 0, "a second attempt does not crash");
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
                await(service.initialize());
                assertTrue(await(service.snapshot()).documents().isEmpty(), "the same service recovers");
            }
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // 3. Early dispose is safe, and a dispose persists what committed writes wrote.
    @Test
    void earlyDisposeIsSafeAndPersistsCommittedWrites(@TempDir Path root) throws Exception {
        ParkingExecutor parking = new ParkingExecutor();
        try (DelayedTaskService neverInitialized = new DelayedTaskService(root, parking, List::of, List::of)) {
            assertDoesNotThrow(neverInitialized::close, "dispose before init or use never throws");
            parking.release(); // the parked init lands after close and is discarded
            assertTrue(failureOf(neverInitialized.snapshot()).contains("is disposed"));
        }
        TaskId written;
        try (DelayedTaskService writer = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(writer.initialize());
            TaskToolContext.Outcome created = await(writer.create("Flushed by dispose", List.of(), RepositoryId.newId()));
            assertFalse(created.refused(), created.refusal());
            written = created.document().id();
            writer.close();
            assertDoesNotThrow(writer::close, "close is idempotent");
        }
        try (DelayedTaskService reopened = new DelayedTaskService(root, executor, List::of, List::of)) {
            await(reopened.initialize());
            TaskDocument stored = await(reopened.snapshot()).byId().get(written);
            assertNotNull(stored, "the write committed before dispose survives a reopen");
            assertEquals("Flushed by dispose", stored.title());
        }
    }

    // 4. Every MCP caller maps to the AGENT actor; pre-init and disposed tool use fail visibly.
    @Test
    void agentActorMappingAndVisibleToolFailures(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        RepositoryId repository = RepositoryId.newId();
        ManagedSessionId caller = ManagedSessionId.newId();
        ManagedAgentSession session = session(caller, repository, worktree, Optional.empty());
        Repository alpha = repository(repository, worktree, "Project Alpha");
        try (DelayedTaskService service = new DelayedTaskService(store, executor,
                () -> List.of(session), () -> List.of(alpha)); TaskStore direct = new TaskStore(store)) {
            await(service.initialize());
            TaskDocument assigned = direct.create(repository, "Assigned follow up", List.of(caller.toString()));
            JsonValue review = service.taskTools().call(caller, "task_update",
                    args("id", assigned.id().text(), "status", "done"));
            assertEquals("review", textOf(review, "status"),
                    "an agent's done on a done_by: human task commits review");
            String onDisk = Files.readString(
                    store.resolve(repository.toString()).resolve(assigned.id().text() + ".md"));
            assertTrue(onDisk.contains("status: review") && onDisk.contains("done_by: human"),
                    "review is the stored status; done_by stays human");
        }
        try (DelayedTaskService notReady = new DelayedTaskService(dir.resolve("parked"),
                command -> { }, () -> List.of(session), () -> List.of(alpha))) { // init never runs
            assertTrue(assertThrows(McpToolException.class, () -> notReady.taskTools().call(caller,
                    "task_list", JsonObject.empty())).getMessage().contains("has not initialized yet"),
                    "pre-init tool use fails visibly");
            notReady.close();
            assertTrue(assertThrows(McpToolException.class, () -> notReady.taskTools().call(caller,
                    "task_list", JsonObject.empty())).getMessage().contains("is disposed"),
                    "disposed-state tool use fails visibly");
        }
    }

    // 5. No host-home writes: the override redirects the root, and no root writes outside its temp dir.
    @Test
    void overrideRedirectsTheRootAndNothingEscapesTempRoots(@TempDir Path injected,
            @TempDir Path override) throws Exception {
        Path realHome = Path.of(System.getProperty("user.home"), ".drydock", "tasks");
        String homeBefore = stateOf(realHome);
        String original = System.getProperty(DelayedTaskService.OVERRIDE_PROPERTY);
        System.setProperty(DelayedTaskService.OVERRIDE_PROPERTY, override.toString());
        try (DelayedTaskService redirected = new DelayedTaskService(executor, List::of, List::of);
                DelayedTaskService atInjectedRoot = new DelayedTaskService(injected, executor,
                        List::of, List::of)) {
            assertEquals(override.toAbsolutePath().normalize(), redirected.root(),
                    "the production root resolves the override, never the host home");
            await(redirected.initialize());
            RepositoryId repository = RepositoryId.newId();
            TaskToolContext.Outcome created = await(redirected.create("Redirected task", List.of(), repository));
            assertTrue(Files.exists(committedFile(override, repository, created.document().id())),
                    "the override root's write lands under it");
            await(atInjectedRoot.initialize());
            TaskToolContext.Outcome other = await(atInjectedRoot.create("Injected task", List.of(), repository));
            assertTrue(Files.exists(committedFile(injected, repository, other.document().id())),
                    "an injected root's writes stay inside it");
            assertEquals(homeBefore, stateOf(realHome), "nothing was created under the real home");
        } finally {
            if (original == null) System.clearProperty(DelayedTaskService.OVERRIDE_PROPERTY);
            else System.setProperty(DelayedTaskService.OVERRIDE_PROPERTY, original);
        }
    }

    // 6. Caller repo resolution: registered roots resolve (worktree fallback included),
    // unresolvable roots yield empty, and the create defaults and refusal paths follow.
    @Test
    void callerRepositoryResolutionResolvesAndRefuses(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        RepositoryId alphaId = RepositoryId.newId();
        RepositoryId betaId = RepositoryId.newId();
        ManagedSessionId resident = ManagedSessionId.newId();
        ManagedSessionId visitor = ManagedSessionId.newId();
        ManagedSessionId nested = ManagedSessionId.newId();
        List<ManagedAgentSession> sessions = List.of(
                session(resident, alphaId, worktree, Optional.empty()),
                session(visitor, alphaId, elsewhere, Optional.empty()),
                session(nested, alphaId, worktree.resolve("nested"), Optional.of(worktree)));
        List<Repository> repositories = List.of(repository(alphaId, worktree, "Project Alpha"),
                repository(betaId, dir.resolve("beta"), "Beta Project"),
                repository(RepositoryId.newId(), dir.resolve("duplicate"), "Project Alpha!"));
        try (DelayedTaskService service = new DelayedTaskService(store, executor,
                () -> sessions, () -> repositories)) {
            await(service.initialize());
            assertEquals(Optional.of(alphaId), await(service.defaultRepository(resident)),
                    "a working root that is a registered repository resolves its id");
            assertEquals(Optional.of(alphaId), await(service.defaultRepository(nested)),
                    "an unregistered working root falls back to its registered worktree root");
            assertTrue(await(service.defaultRepository(visitor)).isEmpty(),
                    "an unresolvable working root yields empty");
            assertTrue(await(service.repositoryBySlug("project-alpha")).isEmpty(),
                    "an ambiguous slug yields empty (accepted deviation)");
            String created = textOf(service.taskTools().call(resident, "task_create",
                    args("title", "Resident default")), "id");
            assertTrue(Files.exists(committedFile(store, alphaId, TaskId.of(created))),
                    "a create without a project lands in the caller's resolved repository");
            assertTrue(assertThrows(McpToolException.class, () -> service.taskTools().call(visitor,
                    "task_create", args("title", "Visitor task"))).getMessage()
                    .contains("no default project"), "an unresolvable caller has no create default");
            assertTrue(assertThrows(McpToolException.class, () -> service.taskTools().call(resident,
                    "task_create", args("title", "Ambiguous", "project", "project-alpha")))
                    .getMessage().contains("No registered project 'project-alpha'"),
                    "an ambiguous slug refuses the create, never picks a winner");
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Awaits a future that must complete exceptionally; returns the cause's message. */
    private static String failureOf(CompletableFuture<?> future) {
        try {
            fail("the future completed normally: " + future.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException c && c.getCause() != null ? c.getCause() : e;
            return String.valueOf(cause.getMessage());
        }
        return null;
    }

    /** One catalog session at the given paths, idle in the live catalog. */
    private static ManagedAgentSession session(ManagedSessionId id, RepositoryId repository,
            Path workingDirectory, Optional<Path> worktreeRoot) {
        return new ManagedAgentSession(id, repository, "session " + id, AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(workingDirectory, worktreeRoot, false), SessionStatus.INACTIVE,
                Instant.now(), Instant.now(), Optional.empty(), PrLink.none(), false, Optional.empty());
    }

    private static Repository repository(RepositoryId id, Path root, String displayName) {
        return new Repository(id, root, displayName, Instant.now(), Instant.now(), RepositorySettings.DEFAULT);
    }

    /** The committed file path of one task: <root>/<repository-id>/<id>.md. */
    private static Path committedFile(Path root, RepositoryId repository, TaskId id) {
        return root.resolve(repository.toString()).resolve(id.text() + ".md");
    }

    /** The scoped home-untouched input: existence plus the sorted entry names. */
    private static String stateOf(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return "true:" + entries.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return "false:[]"; // a missing home directory cannot have been written to
        }
    }

    /** An executor that parks every command until released, so init runs late. */
    private static final class ParkingExecutor implements Executor {
        private final List<Runnable> parked = new ArrayList<>();
        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void execute(Runnable command) {
            if (released.get()) command.run();
            else parked.add(command);
        }

        /** Lets the parked commands run; the parked initialization completes. */
        void release() {
            released.set(true);
            parked.forEach(Runnable::run);
        }
    }

    private static JsonObject args(Object... pairs) {
        JsonObject out = JsonObject.empty();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1] instanceof JsonValue json ? json
                    : new JsonString((String) pairs[i + 1]));
        }
        return out;
    }

    private static String textOf(JsonValue value, String key) {
        JsonValue member = value instanceof JsonObject object ? object.get(key) : null;
        return member instanceof JsonString string ? string.value()
                : member instanceof JsonValue.JsonBoolean bool ? Boolean.toString(bool.value()) : null;
    }
}
