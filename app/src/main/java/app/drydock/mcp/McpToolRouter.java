package app.drydock.mcp;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.mcp.AnnotationLines.LineRef;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                        "Lists the open (OPEN or SENT) review threads of every review scope this session "
                                + "may address, with the base branch, decoded line number, and a "
                                + "working-tree excerpt so an agent can re-locate each comment as its own "
                                + "edits shift line numbers.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle to filter by. Omit for "
                                        + "every scope this session may address."))),
                descriptor("review_reply",
                        "Appends a Claude-authored note to a review thread. Pass addressed: true to claim "
                                + "the thread as ADDRESSED; the human still confirms with RESOLVED. "
                                + "Refused outright for threads already RESOLVED or FIXED.",
                        JsonObject.empty()
                                .put("id", schemaString("Id of the finding to reply to."))
                                .put("scopeId", schemaString("Review scope handle the finding belongs to. "
                                        + "Required only when the same id exists in more than one scope "
                                        + "this session may address."))
                                .put("note", schemaString("Reply text to append to the thread."))
                                .put("addressed", schemaBoolean("Whether to mark the thread ADDRESSED. "
                                        + "Defaults to false.")),
                        "id", "note"),
                descriptor("review_scope",
                        "Reads a review scope: its identity, the changed files, and the diff hunks with "
                                + "stable line keys. Paged -- pass the returned cursor to continue. Every "
                                + "anchor in review_finding is one of these line keys.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle to read."))
                                .put("cursor", schemaString("Resume token from a previous page. Omit to start."))
                                .put("maxBytes", schemaString("Byte budget for this page; default "
                                        + DEFAULT_SCOPE_BYTES + ".")),
                        "scopeId"),
                descriptor("review_intents",
                        "Replaces a scope's intent grouping: what the change is trying to do, at what risk, "
                                + "and which hunks belong to each intent. Optional -- with no call the UI "
                                + "groups by file.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("intents", schemaString("Array of {id, title, kind, risk, rationale, "
                                        + "hunkIds, collapse?, autoApprove?}.")),
                        "scopeId", "intents"),
                descriptor("review_finding",
                        "Records findings against a scope. Idempotent on finding id: a re-run upserts, so "
                                + "existing threads, human severity overrides and resolutions survive. A "
                                + "patch is a PROPOSAL -- drydock never applies one; the human clicks Apply.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("findings", schemaString("Array of {id, intentId?, anchor{file, "
                                        + "startKey, endKey?}, severity, confidence, title?, body, "
                                        + "evidence?, patch?, deviatesFrom?, asks?}.")),
                        "scopeId", "findings"),
                descriptor("review_answer",
                        "Answers a human message in a finding's thread. proposeSeverity and proposeResolve "
                                + "are SUGGESTIONS: the store changes only when the human accepts.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("findingId", schemaString("Finding whose thread to answer."))
                                .put("body", schemaString("The answer."))
                                .put("proposeSeverity", schemaString("Severity to suggest: blocking, "
                                        + "question, deviation or nit."))
                                .put("proposeResolve", schemaBoolean("Suggest that the human resolve it.")),
                        "scopeId", "findingId", "body"),
                descriptor("review_state",
                        "What the human has done so far on a scope: per-intent verdicts, per-finding "
                                + "severity/resolution/threads, and whether the review was submitted. Read "
                                + "this before a re-run so settled findings are not re-flagged.",
                        JsonObject.empty().put("scopeId", schemaString("Review scope handle.")),
                        "scopeId"),
                descriptor("worktree_create",
                        "Creates a worktree in the caller's repository: a new branch by default, or a "
                                + "checkout of a branch that already exists when 'existing' is true. An existing "
                                + "branch must not be checked out in another worktree.",
                        JsonObject.empty()
                                .put("branch", schemaString("Branch name. With existing=true, names a branch that "
                                        + "already exists -- local, or remote-tracking, which is adopted as a local "
                                        + "tracking branch."))
                                .put("existing", schemaBoolean("Check out an existing branch instead of creating one. "
                                        + "Defaults to false. Cannot be combined with start_point."))
                                .put("start_point", schemaString("Optional start point (commit-ish) for the new "
                                        + "branch. Only valid when existing is false.")),
                        "branch"),
                descriptor("session_start",
                        "Starts a new managed session in a worktree of the caller's repository. The "
                                + "started session may not itself start further sessions.",
                        JsonObject.empty()
                                .put("worktree_path", schemaString("Path of the worktree to open the session in; "
                                        + "must be a worktree of the caller's repository."))
                                .put("prompt", schemaString("Optional prompt to seed the new session with.")),
                        "worktree_path"),
                descriptor("session_rename",
                        "Renames this session's own tab, which the human is watching. Call it as soon as "
                                + "you know what the work actually is -- a short title naming the work, not "
                                + "the branch -- and again if the work turns out to be something else. "
                                + "Refused if the human named this session, or if another session in this "
                                + "repository already has that title.",
                        JsonObject.empty()
                                .put("title", schemaString("Short title naming the work; at most 60 "
                                        + "characters, one line.")),
                        "title"),
                descriptor("session_handoff",
                        "Records what this session would tell a successor, so the human can hand the work "
                                + "to a different agent at any moment. Keep it current as you work -- you "
                                + "are writing for whoever picks this up, not for the human. Every call "
                                + "REPLACES the whole brief: an omitted optional slot is cleared, not kept.",
                        JsonObject.empty()
                                .put("goal", schemaString("What this session is trying to achieve."))
                                .put("nextStep", schemaString("What the successor should do first."))
                                .put("approach", schemaString("The shape of the current solution."))
                                .put("decisions", schemaString("Choices made, and why."))
                                .put("ruledOut", schemaString("What was tried or considered and rejected, "
                                        + "with the reason -- the part a successor cannot reconstruct "
                                        + "from the code."))
                                .put("corrections", schemaString("What the human pushed back on.")),
                        "goal", "nextStep"),
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
            case "review_scope" -> reviewScope(caller, arguments);
            case "review_intents" -> reviewIntents(caller, arguments);
            case "review_finding" -> reviewFinding(caller, arguments);
            case "review_answer" -> reviewAnswer(caller, arguments);
            case "review_state" -> reviewState(caller, arguments);
            case "worktree_create" -> worktreeCreate(caller, arguments);
            case "session_start" -> sessionStart(caller, arguments);
            case "session_rename" -> sessionRename(caller, arguments);
            case "session_reclaim" -> sessionReclaim(caller, arguments);
            case "session_handoff" -> sessionHandoff(caller, arguments);
            case "repos_list" -> reposList(caller);
            case "sessions_list" -> sessionsList(caller);
            default -> throw new McpToolException("Unknown tool: " + tool);
        };
    }

    /**
     * A numeric argument that may arrive as a JSON number or, from a client
     * that stringifies everything, as a numeric string. Anything else falls
     * back rather than failing the call: a malformed budget is not worth
     * refusing a whole page over.
     */
    private static int optionalIntArg(JsonObject args, String key, int fallback) {
        if (args.get(key) instanceof JsonNumber number) {
            return number.asInt();
        }
        if (args.get(key) instanceof JsonString text) {
            try {
                return Integer.parseInt(text.value().strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Default byte budget for one {@code review_scope} page (schema §1). */
    static final int DEFAULT_SCOPE_BYTES = 24_000;

    /** Ceiling on a caller-supplied budget, so one call cannot ask for the whole diff at once. */
    private static final int MAX_SCOPE_BYTES = 256_000;

    // ---- the review surface (Review MCP schema) -------------------------

    /**
     * Resolves the scope a call names, or refuses. Unknown and forbidden are
     * one message on purpose: an agent must not be able to discover that a
     * scope exists by probing handles.
     */
    private ReviewScope requireScope(ManagedSessionId caller, JsonObject args) throws McpToolException {
        String scopeId = requiredStringArg(args, "scopeId");
        return context.reviewScope(scopeId, caller)
                .orElseThrow(() -> new McpToolException(
                        "No review scope '" + scopeId + "' is addressable by this session. A scope is "
                                + "addressable only when it is bound to this session -- review is hosted "
                                + "by the session that owns the checkout."));
    }

    private JsonValue reviewScope(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);

        int maxBytes = Math.clamp(optionalIntArg(args, "maxBytes", DEFAULT_SCOPE_BYTES),
                1_000, MAX_SCOPE_BYTES);
        UnifiedDiff diff = context.reviewDiff(scope);
        ReviewToolCodec.ScopePage page = ReviewToolCodec.pageHunks(diff,
                optionalStringArg(args, "cursor"), maxBytes);

        JsonObject result = JsonObject.empty()
                .put("scope", ReviewToolCodec.scopeToJson(scope))
                .put("files", ReviewToolCodec.filesToJson(diff))
                .put("hunks", new JsonArray(page.hunks()))
                .put("cursor", page.cursor()
                        .<JsonValue>map(JsonString::new)
                        .orElse(JsonNull.INSTANCE));
        if (page.truncatedHunk()) {
            result.put("truncated", new JsonBoolean(true));
        }
        // priorThreads lets a re-run recognize its own earlier findings
        // instead of duplicating them.
        result.put("priorThreads", new JsonArray(context.findingsOf(scope.id()).stream()
                .map(ReviewToolCodec::findingStateToJson)
                .toList()));
        return result;
    }

    private JsonValue reviewIntents(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);

        List<ReviewIntent> intents = ReviewToolCodec.intentsFromJson(args.get("intents"));
        context.putIntents(scope.id(), intents);
        return JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("intents", JsonNumber.of(intents.size()));
    }

    private JsonValue reviewFinding(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);

        if (!(args.get("findings") instanceof JsonArray array)) {
            throw new McpToolException("findings must be an array");
        }
        String author = context.reviewerName(caller);
        List<ReviewAnnotation> decoded = new java.util.ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (!(element instanceof JsonObject obj)) {
                throw new McpToolException("each finding must be an object");
            }
            String id = ReviewToolCodec.requireString(obj, "id");
            Optional<ReviewAnnotation> existing = context.findingsOf(scope.id()).stream()
                    .filter(finding -> finding.id().equals(id))
                    .findFirst();
            decoded.add(ReviewToolCodec.findingFromJson(scope.id(), obj, author, existing));
        }
        // Decoded in full before anything is stored: a batch with one bad
        // entry writes nothing, rather than half a review.
        context.upsertFindings(decoded);
        return JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("findings", JsonNumber.of(decoded.size()));
    }

    /**
     * {@code review_answer}: appends the agent's reply to a thread. The
     * {@code propose*} fields are suggestions -- they are recorded in the
     * thread text and never applied, because the store changes when the human
     * accepts, not when the agent asks (schema §4).
     */
    private JsonValue reviewAnswer(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);
        String findingId = requiredStringArg(args, "findingId");
        String body = PromptSafety.checkInboundText(requiredStringArg(args, "body"), "body");

        Optional<Severity> proposeSeverity = optionalStringArg(args, "proposeSeverity")
                .flatMap(Severity::fromWire);
        boolean proposeResolve = optionalBooleanArg(args, "proposeResolve", false);
        StringBuilder text = new StringBuilder(body);
        proposeSeverity.ifPresent(severity ->
                text.append("\n\n[proposes severity: ").append(severity.wireName()).append("]"));
        if (proposeResolve) {
            text.append("\n\n[proposes resolving this finding]");
        }

        String author = context.reviewerName(caller);
        ReviewAnnotation.Key key = new ReviewAnnotation.Key(scope.id(), findingId);
        ReviewAnnotation updated = context.mutateAnnotation(key, current ->
                        current.withReply(new ReviewAnnotation.Message(author, Instant.now(),
                                text.toString())))
                .orElseThrow(() -> new McpToolException("No finding '" + findingId
                        + "' in scope '" + scope.id() + "'."));
        return JsonObject.empty()
                .put("id", new JsonString(updated.id()))
                .put("scopeId", new JsonString(updated.scopeId()))
                .put("messages", JsonNumber.of(updated.thread().size()));
    }

    /**
     * The wire {@code id} here is intent-keyed, not hunk-keyed: an agent
     * correlates it against the ids it sent to {@code review_intents}, so
     * this joins the scope's intents against their verdicts rather than
     * reporting {@link ReviewVerdict#hunkDigest()} straight through -- a
     * verdict's own storage key must not leak onto this wire, or the join
     * silently breaks the moment that key stops being intent-shaped.
     *
     * <p>The join needs a diff (to know the scope's current intents), but
     * findings and submission status do not -- so a scope whose diff cannot
     * be produced (a PR with no local checkout, or a git failure) still
     * reports those two. The {@code intents} key is omitted entirely rather
     * than emitted empty in that case: an empty array reads as "nothing is
     * settled", a false claim, whereas an absent key correctly says "cannot
     * be known right now" (the same absent-vs-zero rule the sidebar's
     * {@code ◨n} badge follows).</p>
     *
     * <p>TODO(task-6): intent.id() stands in for the hunk digest a verdict
     * is actually looked up by (same placeholder as {@code
     * MainWorkspace}/{@code FakeReviewHost}'s {@code setVerdict}); once an
     * intent's verdict is derived from its hunks' real digests, this lookup
     * becomes a real many-to-one join instead of an identity one.</p>
     */
    private JsonValue reviewState(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);

        JsonObject result = JsonObject.empty();
        try {
            result.put("intents", new JsonArray(intentsStateToJson(scope)));
        } catch (McpToolException e) {
            LOG.log(Level.WARNING, "review_state: could not compute a diff for scope "
                    + scope.id() + "; omitting intents: " + e.getMessage());
        }
        result.put("findings", new JsonArray(context.findingsOf(scope.id()).stream()
                        .map(ReviewToolCodec::findingStateToJson)
                        .toList()))
                .put("submitted", new JsonBoolean(context.reviewSubmitted(scope.id())));
        return result;
    }

    /** The scope's intents joined against their verdicts, as {@code review_state} reports them. */
    private List<JsonValue> intentsStateToJson(ReviewScope scope) throws McpToolException {
        Map<String, ReviewVerdict> verdictsByDigest = new LinkedHashMap<>();
        for (ReviewVerdict verdict : context.verdictsOf(scope.id())) {
            verdictsByDigest.put(verdict.hunkDigest(), verdict);
        }
        UnifiedDiff diff = context.reviewDiff(scope);
        List<JsonValue> intents = new ArrayList<>();
        for (ReviewIntent intent : context.intentsOf(scope.id(), diff)) {
            ReviewVerdict verdict = verdictsByDigest.get(intent.id());
            if (verdict == null) {
                continue;
            }
            intents.add(JsonObject.empty()
                    .put("id", new JsonString(intent.id()))
                    .put("verdict", new JsonString(verdict.decision().wireName()))
                    .put("note", verdict.note()
                            .<JsonValue>map(JsonString::new).orElse(JsonNull.INSTANCE)));
        }
        return intents;
    }

    // ---- review_comments -----------------------------------------------

    private JsonValue reviewComments(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);

        Optional<String> scopeFilter = optionalStringArg(args, "scopeId");

        JsonArray comments = new JsonArray(context.annotations(caller).stream()
                .filter(annotation -> annotation.status() == AnnotationStatus.OPEN
                        || annotation.status() == AnnotationStatus.SENT)
                .filter(annotation -> scopeFilter.isEmpty() || annotation.scopeId().equals(scopeFilter.get()))
                .map(annotation -> toComment(caller, annotation))
                .flatMap(Optional::stream)
                .toList());

        return JsonObject.empty()
                .put("base_branch", optionalString(context.baseBranch(caller)))
                .put("comments", comments);
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
                .put("scopeId", new JsonString(annotation.scopeId()))
                .put("severity", new JsonString(annotation.effectiveSeverity().wireName()))
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
        Optional<String> scopeId = optionalStringArg(args, "scopeId");

        // Resolves WHICH finding, not its current value. Finding ids are
        // agent-chosen and repeat across scopes, so an id alone can name two
        // different findings; that ambiguity is refused rather than guessed,
        // because guessing is precisely the bug (scoped) keying exists to
        // prevent. The store's own keyed lookup below decides on current
        // values.
        ReviewAnnotation.Key key = resolveFindingKey(caller, id, scopeId);

        ReviewAnnotation.Message reply = new ReviewAnnotation.Message("Claude", Instant.now(), note);
        Optional<ReviewAnnotation> result;
        try {
            result = context.mutateAnnotation(key, current -> {
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
                new McpToolException("No such finding '" + id + "'."));
        return JsonObject.empty()
                .put("id", new JsonString(updated.id()))
                .put("scopeId", new JsonString(updated.scopeId()))
                .put("status", new JsonString(updated.status().name()));
    }

    /**
     * Finds the one finding {@code id} names among the caller's addressable
     * scopes. An id present in two scopes is ambiguous and is refused with
     * the candidates listed, so the agent re-calls with {@code scopeId}
     * rather than having drydock pick one.
     */
    private ReviewAnnotation.Key resolveFindingKey(ManagedSessionId caller, String id,
                                                   Optional<String> scopeId) throws McpToolException {
        List<ReviewAnnotation> matches = context.annotations(caller).stream()
                .filter(candidate -> candidate.id().equals(id))
                .filter(candidate -> scopeId.isEmpty() || candidate.scopeId().equals(scopeId.get()))
                .toList();
        if (matches.isEmpty()) {
            throw new McpToolException("No such finding '" + id + "'"
                    + scopeId.map(scope -> " in scope '" + scope + "'").orElse("") + ".");
        }
        if (matches.size() > 1) {
            throw new McpToolException("Finding '" + id + "' exists in more than one review scope ("
                    + matches.stream().map(ReviewAnnotation::scopeId).distinct().sorted()
                            .collect(java.util.stream.Collectors.joining(", "))
                    + "); pass scopeId to say which.");
        }
        return matches.get(0).key();
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
        boolean existing = strictBooleanArg(args, "existing", false);

        if (existing && startPoint.isPresent()) {
            // Before the charge, so nothing is spent and nothing refunded.
            throw new McpToolException("start_point cannot be combined with existing: true; an existing "
                    + "branch already has its history.");
        }
        if (!existing) {
            // Deliberately not applied with existing: the refname rules vet a
            // name being minted, and the shadow rule would reject
            // origin/feat/x, which is a legitimate way to name a branch that
            // exists. What guards that path is the catalog -- only refs git
            // itself listed reach git -- plus a check on the derived local
            // name, which these rules would never see.
            BranchNames.validate(branch, context.remoteNames(caller));
        }

        try {
            registry.chargeWorktree(caller);
        } catch (McpBudgetExhaustedException e) {
            throw new McpToolException(e.getMessage());
        }

        try {
            if (existing) {
                McpSessionContext.ExistingBranchWorktree adopted =
                        context.createWorktreeOnExistingBranch(caller, branch);
                return JsonObject.empty()
                        .put("path", new JsonString(adopted.path().toString()))
                        // The RESOLVED local name, unlike the create path's
                        // echo of the argument: adopting origin/feat/x opens
                        // feat/x, and that is the name session_start needs.
                        .put("branch", new JsonString(adopted.branch()))
                        .put("tracking", optionalString(adopted.tracking()));
            }
            Path path = context.createWorktree(caller, branch, startPoint);
            return JsonObject.empty()
                    .put("path", new JsonString(path.toString()))
                    .put("branch", new JsonString(branch));
        } catch (McpWorktreeMayExistException e) {
            // No refund: the add may already have created the worktree, and a
            // refunded retry would hit "already exists" with nothing spent and
            // nothing said. Java enforces this arm coming first.
            throw e;
        } catch (McpToolException e) {
            registry.refundWorktree(caller);
            throw e;
        }
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

    // ---- session_handoff ----------------------------------------------------

    private JsonValue sessionHandoff(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);

        // Validate before charging: a malformed brief is the agent's mistake
        // to fix, not a spend (same rule as session_rename).
        String goal = PromptSafety.checkHandoffSlot("goal", requiredStringArg(args, "goal"));
        String nextStep = PromptSafety.checkHandoffSlot("nextStep", requiredStringArg(args, "nextStep"));
        if (goal.isBlank() || nextStep.isBlank()) {
            throw new McpToolException("goal and nextStep must not be blank: a brief with no goal or no "
                    + "next step tells a successor nothing.");
        }
        Optional<String> approach = optionalSlot(args, "approach");
        Optional<String> decisions = optionalSlot(args, "decisions");
        Optional<String> ruledOut = optionalSlot(args, "ruledOut");
        Optional<String> corrections = optionalSlot(args, "corrections");

        List<String> present = new ArrayList<>(List.of(goal, nextStep));
        approach.ifPresent(present::add);
        decisions.ifPresent(present::add);
        ruledOut.ifPresent(present::add);
        corrections.ifPresent(present::add);
        PromptSafety.checkHandoffRecordSize(present);

        try {
            registry.chargeHandoff(caller);
        } catch (McpBudgetExhaustedException e) {
            throw new McpToolException(e.getMessage());
        }

        HandoffBrief written;
        try {
            written = context.writeHandoff(caller, new McpSessionContext.HandoffDraft(
                    goal, nextStep, approach, decisions, ruledOut, corrections));
        } catch (McpToolException | RuntimeException e) {
            // Only an outright failure is refunded -- but "outright" includes
            // an unchecked one. The context reaches git and the FX thread, and
            // a charge kept for a write that never happened would let a
            // repeatable infrastructure failure burn the whole budget.
            registry.refundHandoff(caller);
            throw e;
        }

        return JsonObject.empty()
                .put("outcome", new JsonString("written"))
                .put("writtenAt", new JsonString(written.writtenAt().toString()));
    }

    /**
     * An optional slot. Absent OR blank means "clear this slot", never "keep
     * what was there": the tool replaces the whole brief, so a blank string
     * and a missing key have to mean the same thing.
     */
    private static Optional<String> optionalSlot(JsonObject args, String key) throws McpToolException {
        JsonValue value = args.get(key);
        if (value == null || value instanceof JsonNull) {
            return Optional.empty();
        }
        // A present non-string is refused rather than read as "clear this
        // slot": the tool replaces the whole brief, so silently dropping a
        // ruledOut sent as an array would answer "written" for a brief that
        // lost it.
        if (!(value instanceof JsonString text)) {
            throw new McpToolException(key + " must be a string.");
        }
        if (text.value().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PromptSafety.checkHandoffSlot(key, text.value()));
    }

    // ---- session_rename -----------------------------------------------------

    private JsonValue sessionRename(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        // Validate before charging: a malformed title is the agent's mistake
        // to fix, not a spend.
        String title = PromptSafety.checkSessionTitle(requiredStringArg(args, "title"));

        try {
            registry.chargeRename(caller);
        } catch (McpBudgetExhaustedException e) {
            throw new McpToolException(e.getMessage());
        }

        RenameOutcome outcome;
        try {
            outcome = context.renameSession(caller, title);
        } catch (McpToolException e) {
            // Only an outright failure is refunded. The refused OUTCOMES are
            // charged: each one still costs an FX hop and a turn under the
            // state lock, dispatched from an unbounded virtual-thread
            // executor, and each one says what is wrong, so twenty attempts
            // is far more than an agent needs to stop.
            registry.refundRename(caller);
            throw e;
        }

        return switch (outcome.kind()) {
            case RENAMED -> renameResult("renamed", outcome.currentName());
            case UNCHANGED -> renameResult("unchanged", outcome.currentName());
            case PINNED -> throw new McpToolException("This session was named by the human ('"
                    + outcome.currentName() + "'); drydock will not rename it.");
            case COLLIDED -> throw new McpToolException("Another session in this repository is already "
                    + "called '" + outcome.currentName() + "'. Choose a title that tells the two apart.");
        };
    }

    private static JsonValue renameResult(String outcome, String title) {
        return JsonObject.empty()
                .put("outcome", new JsonString(outcome))
                .put("title", new JsonString(title));
    }

    // ---- session_reclaim ----------------------------------------------------

    /**
     * The pi bridge's private hand-back: rebind this tab's tracked agent
     * conversation id to the one pi just minted with {@code /new}. Not
     * advertised in {@link #toolDescriptors()} -- the model never calls this,
     * only the bridge does, directly through {@code tools/call} -- so it is
     * reachable on the wire but invisible to {@code tools/list}.
     *
     * <p>Not charged against any MCP budget: it is housekeeping, not agent
     * spend, and a {@code /new} that the bridge could not rebind is already
     * punished by the stand-down that follows. Refused with a message the
     * bridge turns into a warning when another session already tracks or holds
     * open the new id; a rebind to the id already tracked is a silent success.
     */
    private JsonValue sessionReclaim(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        String newAgentSessionId = requiredStringArg(args, "agentSessionId");
        context.reclaimConversation(caller, newAgentSessionId);
        return JsonObject.empty()
                .put("outcome", new JsonString("reclaimed"))
                .put("agentSessionId", new JsonString(newAgentSessionId));
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

    /**
     * Optional boolean argument that refuses a wrong-typed value instead of
     * falling back to {@code defaultValue}, as {@link #optionalStringArg}
     * does.
     *
     * <p>Some clients stringify every argument -- that is why
     * {@link #optionalIntArg} exists -- and a flag that decides between
     * creating a branch and checking one out cannot be coerced quietly:
     * {@code {"existing":"true"}} read as {@code false} creates a brand-new
     * branch off the caller's HEAD with none of the branch's commits, and
     * reports it exactly as it reports an adoption.</p>
     */
    private static boolean strictBooleanArg(JsonObject args, String key, boolean defaultValue)
            throws McpToolException {
        if (!args.has(key)) {
            return defaultValue;
        }
        JsonValue value = args.get(key);
        if (!(value instanceof JsonBoolean bool)) {
            throw new McpToolException("Argument '" + key + "' must be a boolean (true or false).");
        }
        return bool.value();
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
