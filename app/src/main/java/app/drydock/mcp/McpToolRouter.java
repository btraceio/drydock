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

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Dispatches MCP tool calls to {@link McpSessionContext} and adapts the
 * answers into {@link JsonValue}.
 *
 * <p>It owns no domain <em>resolution</em>: it never derives a path, names a
 * repository, or decides what a worktree or an annotation is -- every tool
 * resolves the caller's repository through the context first, and anything the
 * context can answer, the context answers. That boundary is load-bearing, and
 * has been enforced against this class before: an earlier revision filtered
 * annotations by session id here, duplicating a contract {@code
 * McpSessionContext.annotations(caller)} already guarantees, and the fix was to
 * make the context honour its contract rather than to re-check it here.</p>
 *
 * <p>It <em>does</em> enforce cross-cutting policy at the boundary, which is
 * deliberate and is not domain resolution: the spawn grant and creation budget
 * ({@link McpSessionRegistry}), and argument validation that must happen before
 * anything downstream runs ({@link BranchNames}, {@link PromptSafety}). These
 * live here because they gate the call itself -- a refused branch name must
 * never reach git, and a forbidden session must learn nothing from probing
 * arguments -- so pushing them into the context would mean every
 * implementation had to repeat them.</p>
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
                                        + "Defaults to false.")),
                        "id", "note"),
                descriptor("worktree_create",
                        "Creates a new worktree for a branch in the caller's repository.",
                        JsonObject.empty()
                                .put("branch", schemaString("Branch name for the new worktree."))
                                .put("start_point", schemaString("Optional start point (commit-ish) "
                                        + "for the new branch.")),
                        "branch"),
                descriptor("session_start",
                        "Starts a new managed session in a worktree of the caller's repository. The "
                                + "started session may not itself start further sessions.",
                        JsonObject.empty()
                                .put("worktree_path", schemaString("Path of the worktree to open the session in; "
                                        + "must be a worktree of the caller's repository."))
                                .put("prompt", schemaString("Optional prompt to seed the new session with.")),
                        "worktree_path"),
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
            case "worktree_create" -> worktreeCreate(caller, arguments);
            case "session_start" -> sessionStart(caller, arguments);
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
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        String id = requiredStringArg(args, "id");
        String note = requiredStringArg(args, "note");
        boolean addressed = optionalBooleanArg(args, "addressed", false);

        // Ownership only. Which session owns an annotation cannot change, so
        // unlike the status this read cannot go stale; the store's own
        // by-id lookup below is what decides on current values.
        context.annotations(caller).stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new McpToolException("No such annotation '" + id + "'."));

        ReviewAnnotation.Message reply = new ReviewAnnotation.Message("Claude", Instant.now(), note);
        Optional<ReviewAnnotation> result;
        try {
            result = context.mutateAnnotation(id, current -> {
                // Checked INSIDE the transform, against the stored value: the
                // human may have clicked Resolve between the ownership read
                // above and this write, and a refusal decided outside would
                // then overwrite their final verdict (and any note they added
                // with it).
                if (current.status() == AnnotationStatus.RESOLVED
                        || current.status() == AnnotationStatus.FIXED) {
                    throw new Refusal(new McpToolException("Annotation '" + id + "' is already "
                            + current.status() + "; the human's verdict is final."));
                }
                ReviewAnnotation replied = current.withReply(reply);
                return addressed ? replied.withStatus(AnnotationStatus.ADDRESSED) : replied;
            });
        } catch (Refusal refusal) {
            throw refusal.toolException();
        }

        ReviewAnnotation updated = result.orElseThrow(() ->
                new McpToolException("No such annotation '" + id + "'."));
        return JsonObject.empty()
                .put("id", new JsonString(updated.id()))
                .put("status", new JsonString(updated.status().name()));
    }

    /**
     * Carries an {@link McpToolException} out of an annotation transform, which
     * cannot declare a checked exception. Never escapes {@link #reviewReply},
     * which unwraps it immediately -- the alternative was widening the store's
     * transform type just so one caller could refuse.
     */
    private static final class Refusal extends RuntimeException {

        private final McpToolException toolException;

        Refusal(McpToolException toolException) {
            super(toolException.getMessage(), toolException);
            this.toolException = toolException;
        }

        McpToolException toolException() {
            return toolException;
        }
    }

    // ---- worktree_create --------------------------------------------------

    private JsonValue worktreeCreate(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);

        if (!registry.maySpawn(caller)) {
            throw new McpToolException("This session was started by an agent and may not create worktrees or "
                    + "sessions; the human can do this from the UI.");
        }

        JsonObject args = asObject(arguments);
        String branch = requiredStringArg(args, "branch");
        Optional<String> startPoint = optionalStringArg(args, "start_point");

        BranchNames.validate(branch, context.remoteNames(caller));

        try {
            registry.chargeWorktree(caller);
        } catch (McpBudgetExhaustedException e) {
            throw new McpToolException(e.getMessage());
        }

        Path path;
        try {
            path = context.createWorktree(caller, branch, startPoint);
        } catch (McpToolException e) {
            registry.refundWorktree(caller);
            throw e;
        }

        return JsonObject.empty()
                .put("path", new JsonString(path.toString()))
                .put("branch", new JsonString(branch));
    }

    // ---- session_start ------------------------------------------------------

    private JsonValue sessionStart(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);

        if (!registry.maySpawn(caller)) {
            throw new McpToolException("This session was started by an agent and may not create worktrees or "
                    + "sessions; the human can do this from the UI.");
        }

        JsonObject args = asObject(arguments);
        String rawPath = requiredStringArg(args, "worktree_path");
        Optional<String> prompt = optionalStringArg(args, "prompt");
        if (prompt.isPresent()) {
            PromptSafety.validate(prompt.get());
        }

        Path resolved;
        try {
            resolved = Path.of(rawPath).toAbsolutePath().toRealPath();
        } catch (IOException | InvalidPathException e) {
            // InvalidPathException too: a path argument with a NUL byte (or
            // anything else the platform cannot parse) is a bad argument the
            // agent can fix, not an internal error for the -32603 catch-all.
            throw new McpToolException("Worktree path '" + rawPath + "' does not exist.");
        }

        List<Path> worktrees = context.realWorktreesOf(caller);
        if (!worktrees.contains(resolved)) {
            throw new McpToolException(notAWorktreeMessage(caller, resolved));
        }

        try {
            registry.chargeSession(caller);
        } catch (McpBudgetExhaustedException e) {
            throw new McpToolException(e.getMessage());
        }

        ManagedSessionId started;
        try {
            started = context.startSession(resolved, prompt);
        } catch (McpToolException e) {
            registry.refundSession(caller);
            throw e;
        }

        return JsonObject.empty()
                .put("session_id", new JsonString(started.toString()))
                .put("worktree_path", new JsonString(resolved.toString()));
    }

    /**
     * The repository's own main checkout is not among {@code realWorktreesOf}
     * (see its contract), so it lands here like any other non-member. It gets
     * its own sentence because "not a worktree" is baffling for the path the
     * caller's own session is running in: what is refused is starting a second
     * {@code claude} in the tree the human is working in.
     */
    private String notAWorktreeMessage(ManagedSessionId caller, Path resolved) {
        boolean mainCheckout = context.repositoryRoot(caller)
                .map(root -> realPathOrSelf(root).equals(resolved))
                .orElse(false);
        if (mainCheckout) {
            return "'" + resolved + "' is this repository's main checkout, not one of its worktrees; "
                    + "create a worktree with worktree_create and start the session there.";
        }
        return "'" + resolved + "' is not a worktree of this session's repository.";
    }

    private static Path realPathOrSelf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
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

    /**
     * Every tool starts here. A token is revoked as its session ends, so this
     * is defence in depth for the window before the exit watcher notices: a
     * session whose {@code claude} has already exited keeps its tab open (so
     * the human can read the final output) and must not still be able to spend
     * budget, create worktrees or start sessions.
     */
    private void requireLiveSession(ManagedSessionId caller) throws McpToolException {
        if (context.repositoryRoot(caller).isEmpty()) {
            throw new McpToolException("Session has ended; its repository is no longer available.");
        }
        if (!context.sessionRunning(caller)) {
            throw new McpToolException("Session has ended; its claude process is no longer running.");
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

    /**
     * @param required names of the properties the tool cannot run without.
     *                 Runtime validation rejects a missing one either way, but
     *                 a schema that omits {@code required} never tells the
     *                 model what it must send -- and a tool whose value is
     *                 being called correctly first time cannot afford that.
     */
    private static JsonValue descriptor(String name, String description, JsonObject properties,
                                        String... required) {
        JsonObject schema = JsonObject.empty()
                .put("type", new JsonString("object"))
                .put("properties", properties);
        if (required.length > 0) {
            schema = schema.put("required", new JsonArray(Stream.of(required)
                    .map(argument -> (JsonValue) new JsonString(argument))
                    .toList()));
        }
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
