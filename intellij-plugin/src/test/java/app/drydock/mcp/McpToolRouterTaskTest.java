package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.tasks.*;
import app.drydock.tasks.TaskState.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The T11b committed matrix, delegate half: a router with a TaskTools
 * delegate serves the task tools over a real store-backed context
 * exactly as far as the allowed set says, hides and refuses them for a set
 * that excludes them, and widens nothing (P7: session_start and
 * worktree_create refuse exactly as without the delegate).
 */
class McpToolRouterTaskTest {

    /**
     * TaskToolsTest's StoreContext, trimmed to the paths this matrix
     * exercises: a hand-written TaskToolContext over real TaskStore and
     * TaskFiles under a TempDir root, no mocking; futures are already
     * complete, the single writer is the store's own file lock. Reused by
     * McpToolRouterSubsetTest's delegate-present case.
     */
    static final class StoreContext implements TaskToolContext {
        final TaskStore store;
        private final Path root;
        private final RepositoryId repository;

        StoreContext(Path root, RepositoryId repository) {
            this.store = new TaskStore(root);
            this.root = root;
            this.repository = repository;
        }

        private TaskSnapshot scan() {
            try {
                return TaskFiles.scan(root);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** A store write, with its checked failure surfaced as a platform failure. */
        private Outcome mutate(Callable<Outcome> mutation) {
            try {
                return mutation.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public CompletableFuture<TaskSnapshot> snapshot() {
            return CompletableFuture.completedFuture(scan());
        }
        @Override public CompletableFuture<Optional<RepositoryId>> defaultRepository(ManagedSessionId caller) {
            return CompletableFuture.completedFuture(Optional.of(repository));
        }
        @Override public CompletableFuture<List<String>> registeredProjectSlugs() {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<Set<TaskId>> assignedTaskIds(ManagedSessionId caller) {
            return CompletableFuture.completedFuture(Set.copyOf(scan().documents().stream()
                    .filter(d -> d.sessions().contains(caller.toString())).map(TaskDocument::id).toList()));
        }
        @Override public CompletableFuture<Optional<RepositoryId>> repositoryBySlug(String slug) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletableFuture<Outcome> create(String title, List<String> after, RepositoryId repo) {
            return CompletableFuture.completedFuture(
                    mutate(() -> Outcome.committed(store.create(repo, title, List.of()))));
        }
        @Override public CompletableFuture<Outcome> updateStatus(TaskId task, Actor actor, TaskStatus status) {
            TaskSnapshot s = scan();
            TaskState.Transition t = TaskState.of(s, TaskGraph.of(s, RepositoryId::toString), Set.of())
                    .validateUpdate(task, actor, status);
            if (!t.permitted()) {
                return CompletableFuture.completedFuture(Outcome.refused(t.refusalReason()));
            }
            return CompletableFuture.completedFuture(mutate(() -> Outcome.committed(store.update(task,
                    new TaskFilePatch.Updates(null, t.resulting(), null, null, null, null)))));
        }
        @Override public CompletableFuture<Outcome> appendNote(TaskId task, String note) {
            return CompletableFuture.completedFuture(
                    mutate(() -> Outcome.committed(store.appendNote(task, note))));
        }
        @Override public CompletableFuture<Outcome> claim(ManagedSessionId caller, TaskId task) {
            return CompletableFuture.completedFuture(Outcome.refused("this fixture never"
                    + " launches; the claim rules are ClaimLaunchTest's"));
        }
    }

    @TempDir Path root;
    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;
    private StoreContext tasks;
    private RepositoryId alpha;
    private McpToolRouter router;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        alpha = RepositoryId.newId();
        tasks = new StoreContext(root, alpha);
        router = new McpToolRouter(context, registry, McpToolRouter.TASK_TOOLS, new TaskTools(tasks));
    }

    @AfterEach
    void tearDown() {
        tasks.store.close();
    }

    // 1. Task-allowed session: exactly the task names advertised, all of them
    // callable, task_get round-tripping committed data end to end.
    @Test
    void taskAllowedSessionListsFourToolsAndServesThemOverTheStore() throws Exception {
        assertEquals(McpToolRouter.TASK_TOOLS, names(router.toolDescriptors()),
                "exactly the task tools are advertised");
        TaskDocument assigned = tasks.store.create(alpha, "Fix the login redirect",
                List.of(caller.toString()));
        String created = str(router.call(caller, "task_create",
                args("title", "Follow-up task")), "id");
        assertTrue(Files.exists(root.resolve(alpha.toString()).resolve(created + ".md")),
                "task_create commits a task file");
        JsonValue got = router.call(caller, "task_get", args("id", assigned.id().text()));
        assertEquals("Fix the login redirect", str(got, "title"));
        assertEquals("open", str(got, "status"));
        List<String> listed = ((JsonArray) ((JsonObject) router.call(caller, "task_list",
                JsonObject.empty())).get("tasks")).elements().stream()
                .map(t -> str(t, "id")).toList();
        assertTrue(listed.contains(assigned.id().text()) && listed.contains(created),
                "task_list reads the whole store: " + listed);
        assertEquals("review", str(router.call(caller, "task_update",
                args("id", assigned.id().text(), "status", "done")), "status"),
                "an agent's done on a human task stores review");
        assertEquals("review", str(router.call(caller, "task_get",
                args("id", assigned.id().text())), "status"),
                "task_get round-trips the committed status");
    }

    // 2. Allowlist without the task tools: none advertised, and a call by
    // name refuses on the wire exactly like any other non-allowed tool.
    @Test
    void excludedSessionHidesTaskToolsAndRefusesTheirCalls() throws Exception {
        McpToolRouter reviewOnly = new McpToolRouter(context, registry, McpToolRouter.REVIEW_TOOLS,
                new TaskTools(tasks));
        Set<String> listed = names(reviewOnly.toolDescriptors());
        for (String task : McpToolRouter.TASK_TOOLS) {
            assertFalse(listed.contains(task), task + " must not be advertised");
            assertEquals("Unknown tool: " + task,
                    refusalOf(reviewOnly, caller, task, JsonObject.empty()));
        }
        // The refusal is the same "Unknown tool: <name>" a hidden NON-task
        // tool gets on this router -- nothing about it reveals the task tools
        // exist.
        assertEquals("Unknown tool: worktree_create",
                refusalOf(reviewOnly, caller, "worktree_create", JsonObject.empty()),
                "a hidden task tool refuses with the same shape as any hidden tool");
    }

    // 3. P7: task tools widen nothing. The allowlist keeps refusing what it
    // refused, and the spawn grant keeps refusing agent-started sessions,
    // with the delegate present exactly as without it.
    @Test
    void taskToolsDoNotUnforbidSessionStartOrWorktreeCreate() throws Exception {
        Set<String> allowed = new HashSet<>(McpToolRouter.TASK_TOOLS);
        allowed.add("repos_list");
        McpToolRouter withDelegate = new McpToolRouter(context, registry, allowed,
                new TaskTools(tasks));
        McpToolRouter without = new McpToolRouter(context, registry, Set.of("repos_list"));
        for (String forbidden : new String[] {"session_start", "worktree_create"}) {
            assertEquals("Unknown tool: " + forbidden,
                    refusalOf(withDelegate, caller, forbidden, JsonObject.empty()));
            assertEquals(refusalOf(without, caller, forbidden, JsonObject.empty()),
                    refusalOf(withDelegate, caller, forbidden, JsonObject.empty()),
                    "the delegate changes the allowlist refusal not at all");
        }
        ManagedSessionId agent = ManagedSessionId.newId();
        registry.mint(agent, Spawn.FORBIDDEN);
        McpToolRouter full = new McpToolRouter(context, registry, new TaskTools(tasks));
        McpToolRouter fullWithout = new McpToolRouter(context, registry);
        String spawnRefusal = "This session was started by an agent and may not create worktrees"
                + " or sessions; the human can do this from the UI.";
        for (String forbidden : new String[] {"session_start", "worktree_create"}) {
            assertEquals(spawnRefusal, refusalOf(full, agent, forbidden, JsonObject.empty()));
            assertEquals(spawnRefusal, refusalOf(fullWithout, agent, forbidden, JsonObject.empty()),
                    "the delegate changes the spawn-grant refusal not at all");
        }
    }

    private static Set<String> names(List<JsonValue> descriptors) {
        return descriptors.stream().map(d -> str(d, "name")).collect(Collectors.toSet());
    }

    private static String refusalOf(McpToolRouter router, ManagedSessionId session, String tool,
            JsonObject arguments) throws McpToolException {
        return assertThrows(McpToolException.class, () -> router.call(session, tool, arguments))
                .getMessage();
    }

    private static JsonObject args(Object... pairs) {
        JsonObject out = JsonObject.empty();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1] instanceof JsonValue json ? json
                    : new JsonString((String) pairs[i + 1]));
        }
        return out;
    }
}
