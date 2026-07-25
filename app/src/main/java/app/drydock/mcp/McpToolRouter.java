package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.mcp.AnnotationLines.LineRef;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dispatches MCP tool calls to {@link McpSessionContext}. Owns no domain
 * logic itself: every tool resolves the caller's repository via the context
 * first, then adapts the context's answer into {@link JsonValue}.
 *
 * <p>{@code review_reply}, {@code worktree_create}, and {@code session_start}
 * are declared here with real descriptors but throw {@link McpToolException}
 * until Tasks 7-9 implement them.</p>
 */
public final class McpToolRouter {

    private static final Logger LOG = Logger.getLogger(McpToolRouter.class.getName());

    private final McpSessionContext context;
    private final McpSessionRegistry registry;

    public McpToolRouter(McpSessionContext context, McpSessionRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    public List<JsonValue> toolDescriptors() {
        return List.of(
                descriptor("review_comments",
                        "Lists this session's open (OPEN or SENT) review-annotation threads, with the "
                                + "base branch, decoded line number, and a working-tree excerpt so an "
                                + "agent can re-locate each comment as its own edits shift line numbers.",
                        JsonObject.empty()
                                .put("scope", schemaString("Diff scope to filter by: WORKING_TREE, "
                                        + "UPSTREAM, or BASE. Omit for every scope."))),
                descriptor("review_reply",
                        "Appends a Claude-authored note to a review-annotation thread. Pass "
                                + "addressed: true to claim the annotation as ADDRESSED; the human still "
                                + "confirms with RESOLVED. Refused outright for threads already RESOLVED "
                                + "or FIXED.",
                        JsonObject.empty()
                                .put("id", schemaString("Id of the annotation to reply to."))
                                .put("note", schemaString("Reply text to append to the thread."))
                                .put("addressed", schemaBoolean("Whether to mark the annotation ADDRESSED. "
                                        + "Defaults to false."))),
                descriptor("worktree_create",
                        "Creates a new worktree for a branch in the caller's repository.",
                        JsonObject.empty()
                                .put("branch", schemaString("Branch name for the new worktree."))
                                .put("start_point", schemaString("Optional start point (commit-ish) "
                                        + "for the new branch."))),
                descriptor("session_start",
                        "Starts a new managed session in a worktree.",
                        JsonObject.empty()
                                .put("worktree", schemaString("Path of the worktree to open the session in."))
                                .put("initial_prompt", schemaString("Optional prompt to seed the new session with."))),
                descriptor("repos_list",
                        "Lists every repository registered in Drydock, with git state for local repositories.",
                        JsonObject.empty()),
                descriptor("sessions_list",
                        "Lists every managed session across the workspace, flagging the caller's own session.",
                        JsonObject.empty())
        );
    }

    public JsonValue call(ManagedSessionId caller, String tool, JsonValue arguments) throws McpToolException {
        return switch (tool) {
            case "review_comments" -> reviewComments(caller, arguments);
            case "review_reply" -> reviewReply(caller, arguments);
            case "worktree_create" -> throw new McpToolException("not implemented yet");
            case "session_start" -> throw new McpToolException("not implemented yet");
            case "repos_list" -> reposList(caller);
            case "sessions_list" -> sessionsList(caller);
            default -> throw new McpToolException("Unknown tool: " + tool);
        };
    }

    // ---- review_comments -----------------------------------------------

    private JsonValue reviewComments(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);

        Optional<DiffScope> scopeFilter = optionalScope(args);

        JsonArray comments = new JsonArray(context.annotations(caller).stream()
                .filter(annotation -> annotation.status() == AnnotationStatus.OPEN
                        || annotation.status() == AnnotationStatus.SENT)
                .filter(annotation -> scopeFilter.isEmpty() || annotation.scope() == scopeFilter.get())
                .map(annotation -> toComment(caller, annotation))
                .flatMap(Optional::stream)
                .toList());

        return JsonObject.empty()
                .put("base_branch", optionalString(context.baseBranch(caller)))
                .put("comments", comments);
    }

    private Optional<DiffScope> optionalScope(JsonObject args) throws McpToolException {
        Optional<String> raw = optionalStringArg(args, "scope");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DiffScope.valueOf(raw.get()));
        } catch (IllegalArgumentException e) {
            throw new McpToolException("Unknown scope '" + raw.get()
                    + "'; must be one of WORKING_TREE, UPSTREAM, BASE.");
        }
    }

    private Optional<JsonValue> toComment(ManagedSessionId caller, ReviewAnnotation annotation) {
        LineRef ref;
        try {
            ref = AnnotationLines.decode(annotation.startKey());
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Skipping annotation " + annotation.id()
                    + " with undecodable line key '" + annotation.startKey() + "'", e);
            return Optional.empty();
        }

        JsonValue excerpt;
        JsonValue hint;
        if (ref.deleted()) {
            excerpt = JsonNull.INSTANCE;
            hint = new JsonString("This line was deleted; it is not in the working tree. See it with: "
                    + "git show " + context.baseBranch(caller).orElse("<base_branch>") + ":" + annotation.file());
        } else {
            excerpt = optionalString(context.excerpt(caller, annotation.file(), ref.line(), 2));
            hint = JsonNull.INSTANCE;
        }

        JsonArray thread = new JsonArray(annotation.thread().stream()
                .map(message -> (JsonValue) JsonObject.empty()
                        .put("author", new JsonString(message.author()))
                        .put("at", new JsonString(message.at().toString()))
                        .put("text", new JsonString(message.text())))
                .toList());

        return Optional.of(JsonObject.empty()
                .put("id", new JsonString(annotation.id()))
                .put("file", new JsonString(annotation.file()))
                .put("line", JsonNumber.of(ref.line()))
                .put("deleted_line", new JsonBoolean(ref.deleted()))
                .put("status", new JsonString(annotation.status().name()))
                .put("scope", new JsonString(annotation.scope().name()))
                .put("excerpt", excerpt)
                .put("hint", hint)
                .put("thread", thread));
    }

    // ---- review_reply ---------------------------------------------------

    private JsonValue reviewReply(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        JsonObject args = asObject(arguments);
        String id = requiredStringArg(args, "id");
        String note = requiredStringArg(args, "note");
        boolean addressed = optionalBooleanArg(args, "addressed", false);

        ReviewAnnotation annotation = context.annotations(caller).stream()
                .filter(candidate -> candidate.id().equals(id) && candidate.sessionId().equals(caller))
                .findFirst()
                .orElseThrow(() -> new McpToolException("No such annotation '" + id + "'."));

        if (annotation.status() == AnnotationStatus.RESOLVED || annotation.status() == AnnotationStatus.FIXED) {
            throw new McpToolException("Annotation '" + id + "' is already " + annotation.status()
                    + "; the human's verdict is final.");
        }

        ReviewAnnotation replied = annotation.withReply(new ReviewAnnotation.Message("Claude", Instant.now(), note));
        ReviewAnnotation updated = addressed ? replied.withStatus(AnnotationStatus.ADDRESSED) : replied;
        context.updateAnnotation(updated);

        return JsonObject.empty()
                .put("id", new JsonString(updated.id()))
                .put("status", new JsonString(updated.status().name()));
    }

    // ---- repos_list -------------------------------------------------------

    private JsonValue reposList(ManagedSessionId caller) throws McpToolException {
        requireLiveSession(caller);

        JsonArray repositories = new JsonArray(context.repositories().stream()
                .map(repo -> (JsonValue) JsonObject.empty()
                        .put("name", new JsonString(repo.name()))
                        .put("path", new JsonString(repo.path().toString()))
                        .put("branch", optionalString(repo.branch()))
                        .put("dirty", optionalBoolean(repo.dirty()))
                        .put("ahead", optionalInt(repo.ahead()))
                        .put("behind", optionalInt(repo.behind()))
                        .put("remote", new JsonBoolean(repo.remote())))
                .toList());

        return JsonObject.empty().put("repositories", repositories);
    }

    // ---- sessions_list ------------------------------------------------------

    private JsonValue sessionsList(ManagedSessionId caller) throws McpToolException {
        requireLiveSession(caller);

        JsonArray sessions = new JsonArray(context.sessions().stream()
                .map(session -> (JsonValue) JsonObject.empty()
                        .put("id", new JsonString(session.id().toString()))
                        .put("display_name", new JsonString(session.displayName()))
                        .put("repository_name", new JsonString(session.repositoryName()))
                        .put("branch", optionalString(session.branch()))
                        .put("worktree", new JsonString(session.worktree().toString()))
                        .put("status", new JsonString(session.status()))
                        .put("remote", new JsonBoolean(session.remote()))
                        .put("is_caller", new JsonBoolean(session.id().equals(caller))))
                .toList());

        return JsonObject.empty().put("sessions", sessions);
    }

    // ---- shared helpers -----------------------------------------------------

    private void requireLiveSession(ManagedSessionId caller) throws McpToolException {
        if (context.repositoryRoot(caller).isEmpty()) {
            throw new McpToolException("Session has ended; its repository is no longer available.");
        }
    }

    private static JsonObject asObject(JsonValue value) {
        if (value instanceof JsonObject object) {
            return object;
        }
        return JsonObject.empty();
    }

    /** Required non-blank string argument. */
    private static String requiredStringArg(JsonObject args, String key) throws McpToolException {
        Optional<String> value = optionalStringArg(args, key);
        if (value.isEmpty()) {
            throw new McpToolException("Missing required argument '" + key + "'.");
        }
        return value.get();
    }

    /**
     * Optional string argument; blank or absent is treated as absent. A
     * present-but-wrong-typed argument (e.g. a JSON number) is rejected
     * outright rather than silently treated as absent, so the agent is told
     * "must be a string" instead of the more confusing "missing".
     */
    private static Optional<String> optionalStringArg(JsonObject args, String key) throws McpToolException {
        if (!args.has(key)) {
            return Optional.empty();
        }
        JsonValue value = args.get(key);
        if (!(value instanceof JsonString string)) {
            throw new McpToolException("Argument '" + key + "' must be a string.");
        }
        String text = string.value();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** Optional boolean argument; absent defaults to {@code defaultValue}. */
    private static boolean optionalBooleanArg(JsonObject args, String key, boolean defaultValue) {
        if (!args.has(key)) {
            return defaultValue;
        }
        JsonValue value = args.get(key);
        if (!(value instanceof JsonBoolean bool)) {
            return defaultValue;
        }
        return bool.value();
    }

    private static JsonValue optionalString(Optional<String> value) {
        return value.<JsonValue>map(JsonString::new).orElse(JsonNull.INSTANCE);
    }

    private static JsonValue optionalBoolean(Optional<Boolean> value) {
        return value.<JsonValue>map(JsonBoolean::new).orElse(JsonNull.INSTANCE);
    }

    private static JsonValue optionalInt(Optional<Integer> value) {
        return value.<JsonValue>map(JsonNumber::of).orElse(JsonNull.INSTANCE);
    }

    private static JsonValue descriptor(String name, String description, JsonObject properties) {
        JsonObject schema = JsonObject.empty()
                .put("type", new JsonString("object"))
                .put("properties", properties);
        return JsonObject.empty()
                .put("name", new JsonString(name))
                .put("description", new JsonString(description))
                .put("inputSchema", schema);
    }

    private static JsonValue schemaString(String description) {
        return JsonObject.empty()
                .put("type", new JsonString("string"))
                .put("description", new JsonString(description));
    }

    private static JsonValue schemaBoolean(String description) {
        return JsonObject.empty()
                .put("type", new JsonString("boolean"))
                .put("description", new JsonString(description));
    }
}
