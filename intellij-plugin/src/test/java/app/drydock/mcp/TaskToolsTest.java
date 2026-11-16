package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T10b committed matrix: the accepted TaskTools over a hand-written
 * TaskToolContext backed by real TaskStore/TaskFiles under a TempDir
 * root -- no mocking, no faked state.
 */
class TaskToolsTest {
    private static final class StoreContext implements TaskToolContext {

        final TaskStore store;
        final Path root;
        final Map<String, RepositoryId> slugs = new LinkedHashMap<>();
        final Map<ManagedSessionId, RepositoryId> home = new LinkedHashMap<>();
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        StoreContext(Path root) {
            this.root = root;
            this.store = new TaskStore(root);
        }

        private TaskSnapshot scan() {
            try {
                return TaskFiles.scan(root);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        private Outcome mutate(Callable<Outcome> mutation) {
            try {
                return mutation.call();
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        @Override
        public CompletableFuture<TaskSnapshot> snapshot() {
            return CompletableFuture.supplyAsync(this::scan, executor);
        }
        @Override
        public CompletableFuture<Optional<RepositoryId>> defaultRepository(ManagedSessionId caller) {
            return CompletableFuture.completedFuture(Optional.ofNullable(home.get(caller)));
        }
        @Override
        public CompletableFuture<List<String>> registeredProjectSlugs() {
            return CompletableFuture.completedFuture(List.copyOf(slugs.keySet()));
        }
        @Override
        public CompletableFuture<Set<TaskId>> assignedTaskIds(ManagedSessionId caller) {
            return CompletableFuture.supplyAsync(() -> Set.copyOf(scan().documents().stream()
                    .filter(d -> d.sessions().contains(caller.toString()))
                    .map(TaskDocument::id).toList()), executor);
        }
        @Override
        public CompletableFuture<Optional<RepositoryId>> repositoryBySlug(String slug) {
            return CompletableFuture.completedFuture(Optional.ofNullable(slugs.get(slug)));
        }
        @Override
        public CompletableFuture<Outcome> create(String title, List<String> after,
                RepositoryId repository) {
            return CompletableFuture.supplyAsync(() -> mutate(() -> {
                TaskDocument document = store.create(repository, title, List.of());
                return Outcome.committed(after.isEmpty() ? document : store.update(document.id(),
                        new TaskFilePatch.Updates(null, null, null, null, after, null)));
            }), executor);
        }
        @Override
        public CompletableFuture<Outcome> updateStatus(TaskId task, Actor actor, TaskStatus status) {
            return CompletableFuture.supplyAsync(() -> {
                TaskSnapshot snapshot = scan();
                TaskState.Transition transition = TaskState.of(snapshot,
                        TaskGraph.of(snapshot, RepositoryId::toString), Set.of())
                        .validateUpdate(task, actor, status);
                if (!transition.permitted()) {
                    return Outcome.refused(transition.refusalReason());
                }
                return mutate(() -> Outcome.committed(store.update(task, new TaskFilePatch.Updates(
                        null, transition.resulting(), null, null, null, null))));
            }, executor);
        }
        @Override
        public CompletableFuture<Outcome> appendNote(TaskId task, String note) {
            return CompletableFuture.supplyAsync(
                    () -> mutate(() -> Outcome.committed(store.appendNote(task, note))), executor);
        }
        /** The claim seam's test answer: a refusal when one is staged, else the
          * committed document; the claimed id is recorded for assertions. */
        TaskId claimed;
        String claimRefusal;
        @Override
        public CompletableFuture<Outcome> claim(ManagedSessionId caller, TaskId task) {
            return CompletableFuture.supplyAsync(() -> {
                if (claimRefusal != null) {
                    return Outcome.refused(claimRefusal);
                }
                claimed = task;
                return Outcome.committed(scan().byId().get(task));
            }, executor);
        }
    }

    @TempDir Path root;
    private StoreContext context;
    private TaskTools tools;
    private ManagedSessionId caller;
    private RepositoryId alpha;
    private RepositoryId beta;

    @BeforeEach
    void setUp() {
        context = new StoreContext(root);
        caller = ManagedSessionId.newId();
        context.slugs.put("alpha", alpha = RepositoryId.newId());
        context.slugs.put("beta", beta = RepositoryId.newId());
        context.home.put(caller, alpha);
        tools = new TaskTools(context);
    }

    @AfterEach
    void tearDown() {
        context.store.close();
        context.executor.shutdown();
    }

    @Test
    void taskClaimDispatchesToTheContextAndAnswersTheLaunchingMarker() throws Exception {
        TaskDocument followUp = seed(alpha, "Stacked follow-up", List.of(), caller.toString());
        JsonValue claimed = tools.call(caller, "task_claim", args("id", followUp.id().text()));
        assertEquals(followUp.id(), context.claimed, "the context received the claimed id");
        assertEquals(followUp.id().text(), textOf(claimed, "id"));
        assertEquals("open", textOf(claimed, "status"));
        assertEquals("true", textOf(claimed, "launching"), "the answer marks the launch");
        context.claimRefusal = "The prerequisite branch 'dd/x' does not exist in the repository;"
                + " the claim is refused (S4).";
        McpToolException refused = assertThrows(McpToolException.class,
                () -> tools.call(caller, "task_claim", args("id", followUp.id().text())));
        assertTrue(refused.getMessage().contains("refused (S4)"),
                "the context's refusal travels verbatim: " + refused.getMessage());
        assertTrue(refusalOf("task_claim", JsonObject.empty())
                        .contains("Missing required argument 'id'."),
                "the id argument is required");
    }

    @Test
    void taskClaimDescriptorStatesTheLaunchingSemantics() {
        String description = tools.toolDescriptors().stream()
                .filter(d -> "task_claim".equals(textOf(d, "name")))
                .map(d -> textOf(d, "description"))
                .findFirst().orElseThrow();
        assertTrue(description.contains("launching:true means validation passed"),
                description);
        assertTrue(description.contains("the stacked launch attempt is under way"),
                description);
        assertTrue(description.contains("leaves the task open and claimable again"),
                description);
        assertTrue(description.contains("verify the outcome with task_get"), description);
    }

    @Test
    void taskGetReportsCommittedFieldsAndRefusesAMissingId() throws Exception {
        TaskDocument seeded = seed(alpha, "Cover the login fix",
                List.of("alpha:some-old-task"), caller.toString());
        String id = seeded.id().text();
        JsonValue got = tools.call(caller, "task_get", args("id", id));
        assertEquals(id, textOf(got, "id"));
        assertEquals("Cover the login fix", textOf(got, "title"));
        assertEquals("open", textOf(got, "status"));
        assertEquals("human", textOf(got, "done_by"));
        assertEquals(List.of("alpha:some-old-task"), textsOf(got, "after"));
        assertEquals(List.of(caller.toString()), textsOf(got, "sessions"));
        tools.call(caller, "task_update", args("id", id, "status", "done"));
        assertEquals("review", textOf(tools.call(caller, "task_get", args("id", id)), "status"));
        assertEquals("true", textOf(tools.call(caller, "task_get", args("id", id)),
                "awaitingHumanConfirmation"));
        context.updateStatus(seeded.id(), Actor.HUMAN, TaskStatus.DONE).join();
        assertEquals("done", textOf(tools.call(caller, "task_get", args("id", id)), "status"));
        assertEquals("No task with id 'no-such-task-w1w2' in the task store (never stored, or"
                        + " excluded after an id collision); task_list shows the tasks that exist.",
                refusalOf("task_get", args("id", "no-such-task-w1w2")));
    }

    /** task_get returns the stored body -- description and session notes --
      * so a session can read the full work after launch; task_list stays
      * lean and carries no body at all. */
    @Test
    void taskGetCarriesTheBodyAndTaskListStaysLean() throws Exception {
        TaskDocument seeded = seed(alpha, "Cover the login fix", List.of(), caller.toString());
        context.store.appendNote(seeded.id(), "The full requirement, far longer than the title.");
        JsonValue got = tools.call(caller, "task_get", args("id", seeded.id().text()));
        assertTrue(textOf(got, "body").contains("The full requirement, far longer than the title."),
                "the body carries the description and the appended session note");
        JsonObject listed = (JsonObject) tools.call(caller, "task_list", args());
        for (JsonValue task : ((JsonArray) listed.get("tasks")).elements()) {
            assertTrue(!((JsonObject) task).has("body"), "task_list carries no body");
        }
    }

    // 2. task_list spans repositories; 6. create defaults, slugs, after refs, title.
    @Test
    void createDefaultsSlugResolutionAndListSpansRepositories() throws Exception {
        String defaultId = createdId("Default project task", null);
        assertTrue(Files.exists(root.resolve(alpha.toString()).resolve(defaultId + ".md")));
        assertEquals("human", textOf(tools.call(caller, "task_get", args("id", defaultId)),
                "done_by"), "done_by always defaults to human");
        String betaId = createdId("Beta-side task", "beta");
        assertTrue(Files.exists(root.resolve(beta.toString()).resolve(betaId + ".md")));
        List<String> listed = idsOf(tools.call(caller, "task_list", JsonObject.empty()));
        assertTrue(listed.contains(defaultId) && listed.contains(betaId),
                "task_list spans repositories: " + listed);
        String arrayId = textOf(tools.call(caller, "task_create", args("title", "After via array",
                "after", JsonArray.of(List.of(new JsonString(defaultId))))), "id");
        assertEquals(List.of(defaultId), textsOf(tools.call(caller, "task_get",
                args("id", arrayId)), "after"), "a real array is recorded");
        String stringified = textOf(tools.call(caller, "task_create", args("title",
                "After via stringified array", "after", "[\"" + defaultId + "\"]")), "id");
        assertEquals(List.of(defaultId), textsOf(tools.call(caller, "task_get",
                args("id", stringified)), "after"), "a stringified array is tolerated");
        assertTrue(refusalOf("task_create", args("title", "X", "project", "gamma"))
                .contains("No registered project 'gamma'"));
        assertEquals("Missing required argument 'title'.",
                refusalOf("task_create", JsonObject.empty()));
        context.home.clear();
        String refusal = refusalOf("task_create", args("title", "No default"));
        assertTrue(refusal.contains("no default project") && refusal.contains("alpha, beta"),
                "the no-default refusal names the registered slugs: " + refusal);
    }

    @Test
    void agentDoneCommitsReviewOnHumanTaskAndDoneOnAgentTask() throws Exception {
        TaskDocument humanTask = seed(alpha, "Human-validated fix", List.of(), caller.toString());
        JsonValue review = tools.call(caller, "task_update",
                args("id", humanTask.id().text(), "status", "done"));
        assertEquals("review", textOf(review, "status"));
        assertEquals("true", textOf(review, "awaitingHumanConfirmation"));
        String text = fileText(alpha, humanTask.id().text());
        assertTrue(text.contains("status: review") && text.contains("done_by: human"),
                "review is the stored status, done_by stays human");
        TaskDocument agentTask = seed(alpha, "Agent-validated fix", List.of(), caller.toString());
        context.store.update(agentTask.id(),
                new TaskFilePatch.Updates(null, null, DoneBy.AGENT, null, null, null));
        JsonValue done = tools.call(caller, "task_update",
                args("id", agentTask.id().text(), "status", "done"));
        assertEquals("done", textOf(done, "status"));
        assertEquals("false", textOf(done, "awaitingHumanConfirmation"));
        assertTrue(fileText(alpha, agentTask.id().text()).contains("status: done"));
    }

    // 4. An unassigned update and 5. an agent drop refuse with the exact reasons.
    @Test
    void updateScopingAndDropRefuseWithExactReasons() throws Exception {
        TaskDocument foreign = seed(beta, "Foreign task", List.of(), "some-other-session");
        assertEquals("Task '" + foreign.id().text() + "' is not assigned to this session;"
                        + " task_update changes only the tasks whose sessions list carries this"
                        + " session's id -- read any task with task_get instead.",
                refusalOf("task_update", args("id", foreign.id().text(), "status", "done")));
        TaskDocument assigned = seed(alpha, "No agent drop", List.of(), caller.toString());
        assertEquals("Drop is human-only: an agent may not set task '" + assigned.id().text()
                        + "' to dropped",
                refusalOf("task_update", args("id", assigned.id().text(), "status", "dropped")));
    }

    // 7. A created body and 8. an update note land under '## Session notes'.
    @Test
    void bodyAndNoteLandUnderTheSessionNotesHeading() throws Exception {
        String body = "The login redirect drops the return parameter on refresh.";
        String id = textOf(tools.call(caller, "task_create",
                args("title", "Body", "body", body)), "id");
        assertTrue(fileText(alpha, id).contains("## Session notes\n" + body),
                "the body is committed as the first session note");
        TaskDocument assigned = seed(alpha, "Note target", List.of(), caller.toString());
        tools.call(caller, "task_update", args("id", assigned.id().text(),
                "status", "started", "note", "Redirect fixed; tests green."));
        assertTrue(fileText(alpha, assigned.id().text())
                .contains("## Session notes\nRedirect fixed; tests green."),
                "the note is appended under the heading");
    }

    @Test
    void closedStoreSurfacesAsAVisibleRefusal() throws Exception {
        TaskDocument assigned = seed(alpha, "Never started", List.of(), caller.toString());
        context.store.close();
        String refusal = refusalOf("task_update", args("id", assigned.id().text(), "status",
                "started"));
        assertTrue(refusal.startsWith("The task store failed while updating the task:")
                && refusal.contains("is closed"), refusal);
    }

    @Test
    void wrongTypedArgumentsAreRefusedNotReadAsAbsent() {
        assertEquals("Argument 'title' must be a string.",
                refusalOf("task_create", args("title", new JsonBoolean(true))));
        assertEquals("Argument 'status' must be a string.", refusalOf("task_update",
                args("id", "any-task-a1b2", "status", new JsonBoolean(true))));
        assertEquals("Argument 'after' must be an array of task reference texts (bare ids or"
                        + " '<project>:<id>').",
                refusalOf("task_create", args("title", "T", "after", new JsonBoolean(true))));
    }

    private TaskDocument seed(RepositoryId repository, String title, List<String> after,
            String... sessions) throws Exception {
        TaskDocument document = context.store.create(repository, title, List.of(sessions));
        return after.isEmpty() ? document : context.store.update(document.id(),
                new TaskFilePatch.Updates(null, null, null, null, after, null));
    }

    private String createdId(String title, String project) throws Exception {
        return textOf(tools.call(caller, "task_create", project == null ? args("title", title)
                : args("title", title, "project", project)), "id");
    }

    private static JsonObject args(Object... pairs) {
        JsonObject out = JsonObject.empty();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1] instanceof JsonValue json ? json
                    : new JsonString((String) pairs[i + 1]));
        }
        return out;
    }

    private String refusalOf(String tool, JsonObject arguments) {
        return assertThrows(McpToolException.class, () -> tools.call(caller, tool, arguments))
                .getMessage();
    }

    private static String textOf(JsonValue value, String key) {
        JsonValue member = value instanceof JsonObject object ? object.get(key) : null;
        return member instanceof JsonString string ? string.value()
                : member instanceof JsonBoolean bool ? Boolean.toString(bool.value()) : null;
    }

    private static List<String> textsOf(JsonValue value, String key) {
        if (value instanceof JsonObject object && object.get(key) instanceof JsonArray array) {
            return array.elements().stream().map(e -> ((JsonString) e).value()).toList();
        }
        return List.of();
    }

    private static List<String> idsOf(JsonValue listing) {
        return ((JsonArray) ((JsonObject) listing).get("tasks")).elements().stream()
                .map(t -> ((JsonObject) t).get("id")).map(id -> ((JsonString) id).value()).toList();
    }

    private String fileText(RepositoryId repository, String id) throws Exception {
        return Files.readString(root.resolve(repository.toString()).resolve(id + ".md"));
    }
}
