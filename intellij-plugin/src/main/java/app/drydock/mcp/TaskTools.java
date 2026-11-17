package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskStatus;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static app.drydock.mcp.TaskToolCodec.MAX_AFTER_REFS;
import static app.drydock.mcp.TaskToolCodec.MAX_PARAGRAPH_CHARS;
import static app.drydock.mcp.TaskToolCodec.MAX_TITLE_CHARS;
import static app.drydock.mcp.TaskToolCodec.afterArg;
import static app.drydock.mcp.TaskToolCodec.descriptor;
import static app.drydock.mcp.TaskToolCodec.idArg;
import static app.drydock.mcp.TaskToolCodec.paragraphArg;
import static app.drydock.mcp.TaskToolCodec.projectArg;
import static app.drydock.mcp.TaskToolCodec.schemaString;
import static app.drydock.mcp.TaskToolCodec.schemaStringArray;
import static app.drydock.mcp.TaskToolCodec.statusArg;
import static app.drydock.mcp.TaskToolCodec.titleArg;

/**
 * The task MCP tools (D14/P7): task_get, task_list, task_update,
 * task_create and task_claim, dispatched apart from the main router (T11 routes them in)
 * over a {@link TaskToolContext}, in McpToolRouter's conventions: bounded
 * argument parsing whose failures are concrete refusals (McpToolException
 * is the visible failure channel; no unchecked exception escapes a call),
 * router-shaped descriptors, and no domain resolution here -- the context
 * resolves the caller's repository and runs every store operation
 * asynchronously, and a call answers only with state a completed future
 * committed. Argument decoding and the descriptor builders live in
 * {@link TaskToolCodec}.
 *
 * <p>Boundary policy, stated where it is decided: every MCP caller is an
 * {@link TaskState.Actor#AGENT} (T07); reads span every registered
 * repository; a status write must name a task assigned to the calling
 * session (clarification 2); a created task always defaults to human
 * validation, in the caller's repository unless an explicit project names
 * another one. MCP cannot drop a task, change done_by or touch relations --
 * those refusals come from the state rules the context applies.</p>
 */
public final class TaskTools {

    private final TaskToolContext context;

    public TaskTools(TaskToolContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** The five tool descriptors, in the router's descriptor shape. */
    public List<JsonValue> toolDescriptors() {
        return List.of(
                descriptor("task_get",
                        "Reads one task by id, from any registered repository: committed title,"+
                                " body (the full description and session notes -- a title is one"
                                + " bounded line and may be a truncation), status, done_by, created,"
                                + " after references and sessions. review"
                                + " means an agent reported done on a human-validated task and a"
                                + " human must confirm; done is final.",
                        JsonObject.empty().put("id", schemaString("Task id, as task_list or"
                                + " task_create reports it; any registered repository.")),
                        "id"),
                descriptor("task_list",
                        "Lists tasks across every registered repository, or one project's when"
                                + " project is given. Reads are never scoped to this session.",
                        JsonObject.empty().put("project", schemaString("Show only this project's"
                                + " tasks; omit for every registered repository's."))),
                descriptor("task_update",
                        "Updates one task assigned to this session: its status, and optionally a"
                                + " session note. Only status changes -- done_by, relations and other"
                                + " body bytes never change through this tool, and dropped is"
                                + " human-only. done on a done_by: human task stores review and"
                                + " returns it; a human confirms.",
                        JsonObject.empty()
                                .put("id", schemaString("Task id to update; must be assigned to"
                                        + " this session -- other tasks can be read with task_get"
                                        + " but not changed."))
                                .put("status", schemaString("New status: open, started, review"
                                        + " or done."))
                                .put("note", schemaString("One-paragraph session note appended"
                                        + " under '## Session notes', at most " + MAX_PARAGRAPH_CHARS
                                        + " characters.")),
                        "id", "status"),
                descriptor("task_create",
                        "Creates a task. done_by always defaults to human -- prose cannot grant"
                                + " agent-close -- so this session's own done reports land in"
                                + " review. Created in this session's repository, or any registered"
                                + " project named by display slug.",
                        JsonObject.empty()
                                .put("title", schemaString("Short one-line title, at most "
                                        + MAX_TITLE_CHARS + " characters."))
                                .put("body", schemaString("One-paragraph description, at most "
                                        + MAX_PARAGRAPH_CHARS + " characters, stored as the task's"
                                        + " first session note."))
                                .put("after", schemaStringArray("Prerequisite references: bare task"
                                        + " ids or '<project>:<id>', at most " + MAX_AFTER_REFS
                                        + " entries. Each must name an existing task; an"
                                        + " unresolved one refuses the create."))
                                .put("project", schemaString("Registered project to create in, by"
                                        + " display slug; omit for this session's own repository.")),
                        "title"),
                descriptor("task_claim",
                        "Claims one claimable follow-up: Drydock launches the task as its OWN"
                                + " stacked session on a worktree at its in-repository prerequisite's"
                                + " dd/<id> branch tip (the follow-up's work stacks on that branch)."
                                + " The claim launches immediately, with no human confirmation step."
                                + " launching:true means validation passed and the stacked launch"
                                + " attempt is under way; an asynchronous failure (preparation"
                                + " refusal or launch failure) is logged, launches nothing, and"
                                + " leaves the task open and claimable again -- verify the outcome"
                                + " with task_get: the new session's id appears in the task's"
                                + " sessions list once the launch succeeded."
                                + " Refused when the task has no in-repository prerequisite, the"
                                + " prerequisite's branch does not exist yet, a live session already"
                                + " owns the task, or the dependency graph is unsound.",
                        JsonObject.empty().put("id", schemaString("Follow-up task id to claim, as"
                                + " task_list or task_create reports it; any registered repository.")),
                        "id"));
    }

    /** Bounded dispatch; every refusal names what went wrong and what differs. */
    public JsonValue call(ManagedSessionId caller, String tool, JsonObject arguments)
            throws McpToolException {
        JsonObject args = arguments == null ? JsonObject.empty() : arguments;
        return switch (tool) {
            case "task_get" -> taskGet(caller, args);
            case "task_list" -> taskList(caller, args);
            case "task_update" -> taskUpdate(caller, args);
            case "task_create" -> taskCreate(caller, args);
            case "task_claim" -> taskClaim(caller, args);
            default -> throw new McpToolException("Unknown tool: " + tool);
        };
    }

    // ---- reads span projects (D14) ---------------------------------------

    private JsonValue taskGet(ManagedSessionId caller, JsonObject args) throws McpToolException {
        TaskId id = idArg(args);
        TaskDocument document = join(context.snapshot(), "reading the task store").byId().get(id);
        if (document == null) {
            throw new McpToolException("No task with id '" + id.text() + "' in the task store"
                    + " (never stored, or excluded after an id collision); task_list shows the"
                    + " tasks that exist.");
        }
        // The body rides task_get only: task_list stays lean over every task,
        // and a session needing the full description reads the one task.
        return taskJson(document).put("body", new JsonString(document.rawBody()));
    }

    private JsonValue taskList(ManagedSessionId caller, JsonObject args) throws McpToolException {
        Optional<String> project = projectArg(args);
        Stream<TaskDocument> documents = join(context.snapshot(), "reading the task store")
                .documents().stream();
        if (project.isPresent()) {
            RepositoryId filter = resolveProject(project.get());
            documents = documents.filter(d -> d.repository().equals(filter));
        }
        return JsonObject.empty()
                .put("tasks", new JsonArray(
                        documents.map(document -> (JsonValue) taskJson(document)).toList()));
    }

    /** The committed fields of one task, with the review-vs-done distinction. */
    private static JsonObject taskJson(TaskDocument document) {
        return JsonObject.empty()
                .put("id", new JsonString(document.id().text()))
                .put("title", new JsonString(document.title()))
                .put("status", new JsonString(document.status().storedName()))
                .put("done_by", new JsonString(document.doneBy().storedName()))
                .put("created", document.created()
                        .<JsonValue>map(instant -> new JsonString(instant.toString()))
                        .orElse(JsonNull.INSTANCE))
                .put("after", new JsonArray(document.after().stream()
                        .map(text -> (JsonValue) new JsonString(text)).toList()))
                .put("sessions", new JsonArray(document.sessions().stream()
                        .map(text -> (JsonValue) new JsonString(text)).toList()))
                .put("awaitingHumanConfirmation",
                        new JsonBoolean(document.status() == TaskStatus.REVIEW));
    }

    // ---- status writes only on this session's tasks (clarification 2) ---

    private JsonValue taskUpdate(ManagedSessionId caller, JsonObject args) throws McpToolException {
        TaskId id = idArg(args);
        TaskStatus status = statusArg(args);
        Optional<String> note = paragraphArg(args, "note");
        if (!join(context.assignedTaskIds(caller), "reading this session's assigned tasks")
                .contains(id)) {
            throw new McpToolException("Task '" + id.text() + "' is not assigned to this session;"
                    + " task_update changes only the tasks whose sessions list carries this"
                    + " session's id -- read any task with task_get instead.");
        }
        TaskToolContext.Outcome updated = join(
                context.updateStatus(id, TaskState.Actor.AGENT, status), "updating the task");
        if (updated.refused()) {
            throw new McpToolException(updated.refusal());
        }
        TaskDocument committed = updated.document();
        if (note.isPresent()) {
            TaskToolContext.Outcome noted = join(context.appendNote(id, note.get()),
                    "appending the note");
            if (noted.refused()) {
                throw new McpToolException("The status was committed as '"
                        + committed.status().storedName() + "' but the note could not be stored: "
                        + noted.refusal());
            }
        }
        return JsonObject.empty()
                .put("id", new JsonString(id.text()))
                .put("status", new JsonString(committed.status().storedName()))
                .put("awaitingHumanConfirmation",
                        new JsonBoolean(committed.status() == TaskStatus.REVIEW));
    }

    // ---- create: human validation, caller's repository by default (D16) -

    private JsonValue taskCreate(ManagedSessionId caller, JsonObject args) throws McpToolException {
        String title = titleArg(args);
        Optional<String> body = paragraphArg(args, "body");
        List<String> after = afterArg(args);
        RepositoryId repository;
        Optional<String> project = projectArg(args);
        if (project.isPresent()) {
            repository = resolveProject(project.get());
        } else {
            Optional<RepositoryId> resolved = join(context.defaultRepository(caller),
                    "resolving this session's repository");
            if (resolved.isEmpty()) {
                throw new McpToolException(noDefaultProjectRefusal());
            }
            repository = resolved.get();
        }
        TaskToolContext.Outcome created = join(context.create(title, after, repository),
                "creating the task");
        if (created.refused()) {
            throw new McpToolException(created.refusal());
        }
        TaskId id = created.document().id();
        if (body.isPresent()) {
            TaskToolContext.Outcome noted = join(context.appendNote(id, body.get()),
                    "storing the body");
            if (noted.refused()) {
                throw new McpToolException("Task '" + id.text() + "' was created but its body"
                        + " could not be stored: " + noted.refusal());
            }
        }
        return JsonObject.empty().put("id", new JsonString(id.text()));
    }

    /**
     * Claims one follow-up (S4 of docs/StackedPrExecution.md): the context
     * validates and launches; the answer is the task as it stood at claim
     * time plus the launching marker. Every refusal is the context's
     * concrete reason, verbatim. launching:true means validation passed and
     * the stacked launch attempt is under way; an asynchronous failure
     * (preparation refusal or launch failure) is logged, launches nothing,
     * and leaves the task open and claimable again -- verify the outcome
     * with task_get: the new session's id appears in the task's sessions
     * list once the launch succeeded.
     */
    private JsonValue taskClaim(ManagedSessionId caller, JsonObject args) throws McpToolException {
        TaskId id = idArg(args);
        TaskToolContext.Outcome claimed = join(context.claim(caller, id), "claiming the task");
        if (claimed.refused()) {
            throw new McpToolException(claimed.refusal());
        }
        TaskDocument committed = claimed.document();
        return JsonObject.empty()
                .put("id", new JsonString(id.text()))
                .put("status", new JsonString(committed.status().storedName()))
                .put("launching", new JsonBoolean(true));
    }

    /** A project argument, resolved by display slug; an unknown slug refuses. */
    private RepositoryId resolveProject(String slug) throws McpToolException {
        return join(context.repositoryBySlug(slug), "resolving the project")
                .orElseThrow(() -> new McpToolException("No registered project '" + slug + "';"
                        + " pass a project exactly as the platform names it, or omit it to use"
                        + " this session's own repository."));
    }

    /**
     * The refusal for a caller whose working root maps to no registered
     * repository, naming the registered slugs so the call can still be
     * retried with an explicit project.
     */
    private String noDefaultProjectRefusal() throws McpToolException {
        List<String> slugs = join(context.registeredProjectSlugs(),
                "listing the registered projects");
        String offer = slugs.isEmpty()
                ? "no projects are registered yet"
                : "registered projects, by slug: " + String.join(", ", slugs);
        return "This session's working root maps to no registered repository, so there is"
                + " no default project to create the task in; pass an explicit project ("
                + offer + ").";
    }

    /**
     * Waits for one context future. Domain refusals travel inside the
     * future, so only a platform failure lands here, reported as a store
     * failure -- never as an escaped exception.
     */
    private static <T> T join(CompletableFuture<T> future, String operation)
            throws McpToolException {
        try {
            return future.join();
        } catch (CompletionException | CancellationException e) {
            Throwable cause = e instanceof CompletionException c && c.getCause() != null
                    ? c.getCause() : e;
            throw new McpToolException("The task store failed while " + operation + ": "
                    + cause.getMessage() + ". Read task_list to see what committed, then retry.");
        }
    }
}
