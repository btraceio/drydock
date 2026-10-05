package app.drydock.mcp;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Workflow;
import app.drydock.domain.WorkflowBrief;
import app.drydock.mcp.AnnotationLines.LineRef;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ChangeGraph;
import app.drydock.review.OutOfDiffFanIn;
import app.drydock.review.ReadingPath;
import app.drydock.review.RecheckAssessment;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Sections;
import app.drydock.review.Severity;
import app.drydock.review.SymbolScan;
import app.drydock.review.tour.AnchorIndex;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.HunkOverride;
import app.drydock.review.tour.ImpactNote;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourCodec;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourMerge;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import app.drydock.review.tour.TourValidator;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
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
    private final Function<UnifiedDiff, ChangeGraph> graphBuilder;

    /**
     * One scope's change graph and out-of-diff caller scan, keyed by the diff
     * they were computed from, and shared by every {@code review_scope}
     * include built on them ({@code sections}, {@code impact}): asking for
     * both, together or in turn, builds the graph and greps once.
     *
     * <p>Without it, every {@code review_scope} call that asks for {@code
     * sections} rebuilds the whole {@link ChangeGraph} AND spawns a fresh
     * full-worktree {@code git grep} -- so an agent polling during a review
     * runs one 30s-bounded grep per poll, concurrently with the board's own.
     * One duplicate scan across the UI/MCP boundary is the price of these
     * two surfaces having no common owner; one per poll is not.</p>
     *
     * <p>Keyed on the diff INSTANCE, the same identity test the board's
     * graph cache uses: a re-read that produced a genuinely new diff gets a
     * genuinely new grouping, and a repeated read of the same one does not
     * pay twice. Concurrent because MCP calls arrive on the server's threads,
     * not on one.</p>
     *
     * <p>Unbounded, and blind to the worktree changing under a diff it has
     * already grouped -- deliberately, because both are true of the board's
     * own graph and fan-in caches too, and one entry per live scope with a
     * new diff instance on every re-read is not a leak worth a second
     * eviction policy. If that ever stops holding it stops holding in both
     * places at once, which is the point of matching them.</p>
     */
    private final Map<String, GraphCacheEntry> graphByScope = new ConcurrentHashMap<>();

    /** One completed {@link #graphFor} result, keyed by what it was computed from. */
    private record GraphCacheEntry(UnifiedDiff diff, ChangeGraph graph, OutOfDiffFanIn.Result fanIn) {
    }

    public McpToolRouter(McpSessionContext context, McpSessionRegistry registry) {
        this(context, registry, ChangeGraph::of);
    }

    /**
     * Test seam: swaps how a scope's {@link ChangeGraph} is built (mirrors
     * {@code GitStatusService}'s ssh-executable constructor). Package-private
     * -- its only reason to exist is letting a test count builds, or fail
     * them, without a mocking library; production callers always get the
     * real, blocking {@link ChangeGraph#of}.
     */
    McpToolRouter(McpSessionContext context, McpSessionRegistry registry,
                  Function<UnifiedDiff, ChangeGraph> graphBuilder) {
        this.context = context;
        this.registry = registry;
        this.graphBuilder = graphBuilder;
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
                                        + DEFAULT_SCOPE_BYTES + "."))
                                .put("include", schemaString("Optional extras, comma-separated. "
                                        + "\"sections\" returns drydock's computed grouping: "
                                        + "accept and name it, or regroup deliberately. "
                                        + "\"impact\" returns, per changed declaration, its callers "
                                        + "outside the change (name matches, not resolved references) "
                                        + "and whether its declaration changed while call sites were "
                                        + "not edited.")),
                        "scopeId"),
                descriptor("review_tour",
                        "Posts the guided tour of a scope: ordered steps a human walks in full files. Validated "
                                + "against the review diff and stored only if valid; on rejection nothing is stored "
                                + "and every problem is listed. Every changed row must lie in some step's anchor; "
                                + "each step needs a narrative and at least one check, each check at least one "
                                + "alternate. Anchor keys are line keys from review_scope (n<newLine> or "
                                + "o<oldLine>); answer is a 0-based index into choices. With onlySteps true, "
                                + "the steps sent replace the stored stale steps with the same id and the rest "
                                + "are appended; naming a step that is not stale is rejected. Unsent steps keep "
                                + "their progress, and the merged tour is validated as a whole.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("steps", schemaArray("Array of {id, title, narrative (<=1000 chars), "
                                        + "anchors[{file, startKey, endKey?, note? (<=400 chars: the one claim this "
                                        + "range supports, shown under its last row)}], "
                                        + "impactNotes?[{file, line, text}], "
                                        + "checks[{id, kind: predict|trace|risk, prompt, choices?[{text, at?{file, "
                                        + "line}}] (2-4, not for risk), answer? (0-based, not for risk), explanation, "
                                        + "alternates[{...same, no alternates}]}]}; at most 40 steps, 6 checks each."))
                                .put("onlySteps", schemaBoolean("Merge these steps into the stored tour instead "
                                        + "of replacing it: re-issue stale steps, add steps for uncovered hunks. "
                                        + "Needs a stored tour.")),
                        "scopeId", "steps"),
                descriptor("review_check",
                        "Judges the reviewer's free-text answer to a risk check, read from review_state "
                                + "tour.awaitingAgent. Verdict holds, partly or doesNotHold, with a one-line "
                                + "reason the reviewer will see.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("checkId", schemaString("The risk check being judged."))
                                .put("verdict", schemaString("holds, partly or doesNotHold."))
                                .put("reason", schemaString("One line the reviewer will read.")),
                        "scopeId", "checkId", "verdict", "reason"),
                descriptor("review_finding",
                        "Records findings against a scope. Idempotent on finding id: a re-run upserts, so "
                                + "existing threads, human severity overrides, resolutions and triage survive. A "
                                + "patch is a PROPOSAL -- drydock never applies one; the human clicks Apply. "
                                + "Findings land as proposals; the human confirms or dismisses each.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("findings", schemaArray("Array of {id, anchor{file, "
                                        + "startKey, endKey?}, severity, confidence, title?, body, "
                                        + "evidence?, patch?, deviatesFrom?, asks?, withheldBy?}.")),
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
                        "What the human has done so far on a scope: per-finding severity/resolution/threads, "
                                + "tour progress, and whether the review was submitted. Read this before a "
                                + "re-run so settled findings are not re-flagged.",
                        JsonObject.empty().put("scopeId", schemaString("Review scope handle.")),
                        "scopeId"),
                descriptor("review_recheck",
                        "Assesses whether a base move still leaves already-settled hunks valid. "
                                + "affected=true marks them stale; affected=false is ADVICE and "
                                + "never clears a human's verdict. Drydock derives which base "
                                + "move each hunk is being asked about -- the base its own verdict "
                                + "was recorded against, against the scope's base now -- so a hunk "
                                + "with no verdict has nothing to recheck and is refused.",
                        JsonObject.empty()
                                .put("scopeId", schemaString("Review scope handle."))
                                .put("assessments", schemaArray("Array of {hunkId, affected, why}. "
                                        + "hunkId is a hunk id from review_scope; affected is a "
                                        + "real boolean, not \"true\"; why is REQUIRED whenever "
                                        + "affected is true -- it is the reason a human is shown "
                                        + "for re-reading the hunk.")),
                        "scopeId", "assessments"),
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
                                + "REPLACES the whole brief: an omitted optional slot is cleared, not kept. "
                                + "Pass \"workflow\": true to write the calling session's workflow brief "
                                + "instead (requires the session to be a workflow member).",
                        JsonObject.empty()
                                .put("goal", schemaString("What this session is trying to achieve."))
                                .put("nextStep", schemaString("What the successor should do first."))
                                .put("approach", schemaString("The shape of the current solution."))
                                .put("decisions", schemaString("Choices made, and why."))
                                .put("ruledOut", schemaString("What was tried or considered and rejected, "
                                        + "with the reason -- the part a successor cannot reconstruct "
                                        + "from the code."))
                                .put("corrections", schemaString("What the human pushed back on."))
                                .put("workflow", schemaBoolean("When true, write the calling session's "
                                        + "workflow brief instead of its per-session brief.")),
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
            case "review_tour" -> reviewTour(caller, arguments);
            case "review_check" -> reviewCheck(caller, arguments);
            case "review_finding" -> reviewFinding(caller, arguments);
            case "review_answer" -> reviewAnswer(caller, arguments);
            case "review_state" -> reviewState(caller, arguments);
            case "review_recheck" -> reviewRecheck(caller, arguments);
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
        Optional<String> cursor = optionalStringArg(args, "cursor");
        UnifiedDiff diff = context.reviewDiff(scope);

        // Computed only on the FIRST page of a read (cursor absent), and only
        // when asked: ChangeGraph.of parses every changed file and can trigger
        // a first-time native grammar load, so it must never be a cost a plain
        // review_scope call pays, and a multi-page read must not pay it again
        // on every page for a payload that would not have changed anyway.
        Set<String> includes = cursor.isEmpty() ? includes(args) : Set.of();
        Optional<GraphCacheEntry> graph = includes.contains("sections") || includes.contains("impact")
                ? graphFor(scope, diff)
                : Optional.empty();
        Optional<JsonValue> sectionsJson = includes.contains("sections")
                ? graph.flatMap(entry -> computeSections(scope, diff, entry))
                : Optional.empty();
        Optional<JsonValue> impactJson = includes.contains("impact")
                ? graph.map(entry -> ImpactJson.toJson(entry.graph(), entry.fanIn()))
                : Optional.empty();
        // Charged against the SAME budget as hunks, not on top of it: sections
        // overlap by design (a shared foundation file appears in every section
        // that needs it), so their payload scales as sections x shared files,
        // not by file count the way scope/files/priorThreads do -- an
        // unaccounted addition here could dwarf a small maxBytes with no
        // signal at all.
        // Impact is charged the same way, for the same reason: it scales with
        // the number of changed declarations and their callers, not files.
        int sectionsBytes = sectionsJson.map(ReviewToolCodec::approximateBytes).orElse(0);
        int impactBytes = impactJson.map(ReviewToolCodec::approximateBytes).orElse(0);
        int hunkBudget = Math.max(0, maxBytes - sectionsBytes - impactBytes);
        ReviewToolCodec.ScopePage page = ReviewToolCodec.pageHunks(diff, cursor, hunkBudget);

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

        if (sectionsJson.isPresent()) {
            result.put("sections", sectionsJson.get());
            // The grouping is never truncated mid-array -- that would hand an
            // agent a lie it could act on -- so when it alone is bigger than
            // the whole budget, the overage is reported rather than hidden:
            // a caller that asked for this explicitly gets all of it, plus a
            // signal that maxBytes was not honoured, instead of a silently
            // blown budget.
            if (sectionsBytes > maxBytes) {
                result.put("sectionsOverBudget", new JsonBoolean(true));
            }
        }
        if (impactJson.isPresent()) {
            result.put("impact", impactJson.get());
            // Absent, not empty: a failed caller search must not read as
            // "nobody calls this".
            graph.flatMap(entry -> entry.fanIn().unavailableReason())
                    .ifPresent(reason -> result.put("impactUnavailable", new JsonString(reason)));
        }
        return result;
    }

    /**
     * The scope's change graph and caller scan, or empty if the graph could
     * not be built. {@link ChangeGraph#of} (via {@link SymbolScan}) can throw
     * unchecked on a parse edge case; that must cost the optional extras
     * built on it, never the whole call -- a caller who merely opted into
     * {@code sections} or {@code impact} must still get {@code hunks},
     * {@code scope} and {@code files}.
     *
     * <p>Scanned synchronously, unlike the board's own background scan:
     * an MCP tool call already runs off the FX thread, {@link
     * OutOfDiffFanIn#scan} bounds itself with a 30s timeout, and handing an
     * agent a first-call-always-unavailable answer it will then act on is
     * worse than making it wait.</p>
     *
     * <p>Only a SUCCESSFUL build is cached -- a parse edge case must stay
     * retryable rather than being pinned as this scope's answer.</p>
     */
    private Optional<GraphCacheEntry> graphFor(ReviewScope scope, UnifiedDiff diff) {
        GraphCacheEntry cached = graphByScope.get(scope.id());
        if (cached != null && cached.diff() == diff) {
            return Optional.of(cached);
        }
        try {
            ChangeGraph graph = graphBuilder.apply(diff);
            GraphCacheEntry entry = new GraphCacheEntry(diff, graph,
                    OutOfDiffFanIn.forScope(scope, graph, diff));
            graphByScope.put(scope.id(), entry);
            return Optional.of(entry);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "review_scope: could not build the change graph for scope "
                    + scope.id() + "; omitting sections and impact: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * {@code sections} from the scope's cached graph and scan, or empty if
     * the grouping could not be computed.
     *
     * <p>A live surface (the Review board's rail) numbers its cards off the
     * reading path's order, not {@link Sections#of}'s own grouping order --
     * see {@link ReadingPath.Path#sections()}. An agent reading {@code
     * sections} off the plain grouping would disagree with the human looking
     * at the same review over which card is ①, so this reorders the SAME
     * sections through {@link ReadingPath#of} before handing them out,
     * exactly as the rail does -- fan-in scan included, so the two agree on
     * the rank's first term as well as on the ordering.</p>
     *
     * <p>Recomputed from the cached graph per call rather than cached
     * itself: it is in-memory work over a graph already built, and the
     * expensive parts -- the parse and the grep -- are what the cache
     * holds.</p>
     */
    private Optional<JsonValue> computeSections(ReviewScope scope, UnifiedDiff diff, GraphCacheEntry entry) {
        try {
            List<Sections.Section> sections = Sections.of(diff, entry.graph());
            ReadingPath.Path path = ReadingPath.of(diff, entry.graph(), sections, entry.fanIn());
            return Optional.of(ReviewToolCodec.sectionsToJson(path.sections()));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "review_scope: could not compute sections for scope "
                    + scope.id() + "; omitting: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * The comma-separated {@code include} argument's tokens, stripped. An
     * unknown token, or a missing/blank argument, simply names nothing --
     * this is an optional read, and a typo must not fail the call.
     */
    private static Set<String> includes(JsonObject args) throws McpToolException {
        return optionalStringArg(args, "include")
                .map(value -> Stream.of(value.split(","))
                        .map(String::strip)
                        .filter(token -> !token.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
    }

    private JsonValue reviewFinding(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);

        if (!(arrayArgument(args, "findings") instanceof JsonArray array)) {
            throw new McpToolException("findings must be an array");
        }
        String author = context.reviewerName(caller);
        List<ReviewAnnotation> decoded = new ArrayList<>();
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
        rejectMisplacedWithheldFindings(scope, decoded);
        // Decoded in full before anything is stored: a batch with one bad
        // entry writes nothing, rather than half a review.
        context.upsertFindings(decoded);
        return JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("findings", JsonNumber.of(decoded.size()));
    }

    /**
     * Once a tour exists, a finding withheld behind one of its checks must
     * sit on that check's step -- the same rule {@code review_tour} applies
     * to findings already stored when a tour arrives.
     */
    private void rejectMisplacedWithheldFindings(ReviewScope scope, List<ReviewAnnotation> decoded)
            throws McpToolException {
        Optional<TourRecord> tour = context.tourOf(scope.id());
        if (tour.isEmpty() || decoded.stream().allMatch(finding -> finding.withheldBy().isEmpty())) {
            return;
        }
        List<String> errors = TourValidator.withheldFindingErrors(tour.get().tour(),
                AnchorIndex.of(context.reviewDiff(scope)), decoded);
        if (!errors.isEmpty()) {
            throw new McpToolException("review_finding rejected, nothing stored:\n- " + String.join("\n- ", errors));
        }
    }

    /**
     * {@code review_recheck}: the agent's answer to the one question neither
     * a hunk digest nor {@link app.drydock.review.BaseMove}'s intersection
     * can reach (spec §9.7).
     *
     * <p>The relevance filter is file-level and lexical and names its own
     * blind spot: a base change that alters behaviour without touching a file
     * this scope references is invisible to it. An agent has no such
     * boundary, so it can close that gap -- but only in one direction.
     * {@code affected == true} adds staleness, which costs at worst a wasted
     * re-read. {@code affected == false} is advice and clears nothing,
     * because an agent wrong THAT way would leave a human's approval standing
     * over code nobody re-read. Nothing in this method or below it touches a
     * verdict, which is what makes that true by construction rather than by
     * every reader remembering it.</p>
     *
     * <p>Which base move is being assessed is drydock's to say, not the
     * agent's: {@code fromBase} comes from each hunk's own verdict and {@code
     * toBase} from the scope's current base, so the key written here is the
     * key the board reads with. See {@link
     * ReviewToolCodec#assessmentsFromJson}, which also owns the hunkId ->
     * digest translation and the three refusals.</p>
     */
    private JsonValue reviewRecheck(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);

        String toBase = context.currentReviewBase(scope).orElseThrow(() -> new McpToolException(
                "The base of scope '" + scope.id() + "' does not resolve to a commit right now, so "
                        + "there is no base move to assess. Nothing was recorded."));
        Map<String, ReviewVerdict> verdictsByDigest = new LinkedHashMap<>();
        for (ReviewVerdict verdict : context.verdictsOf(scope.id())) {
            verdictsByDigest.put(verdict.hunkDigest(), verdict);
        }
        List<RecheckAssessment> decoded = ReviewToolCodec.assessmentsFromJson(scope.id(),
                arrayArgument(args, "assessments"), context.reviewDiff(scope), verdictsByDigest, toBase,
                Instant.now());
        // Decoded in full before anything is stored, like review_finding: a
        // batch with one bad entry writes nothing rather than half a recheck.
        context.putAssessments(decoded);
        return JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("assessments", JsonNumber.of(decoded.size()))
                // Echoed because it is the only half with an effect: an agent
                // that sent ten and marked none has changed nothing, and
                // saying so is cheaper than letting it believe otherwise.
                .put("markedStale", JsonNumber.of(
                        (int) decoded.stream().filter(RecheckAssessment::affected).count()));
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
     * What the human has settled on a scope: its findings, whether the review
     * was submitted and, when a tour exists, the tour's progress.
     *
     * <p>Findings and submission status need no diff, so a scope whose diff
     * cannot be produced (a PR with no local checkout, or a git failure)
     * still reports them. The {@code tour} key is omitted in that case rather
     * than emitted empty: an absent key says "cannot be known right now", an
     * empty one would claim nothing is settled (the same absent-vs-zero rule
     * the sidebar's {@code ◨n} badge follows).</p>
     */
    private JsonValue reviewState(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);

        JsonObject result = JsonObject.empty();
        result.put("findings", new JsonArray(context.findingsOf(scope.id()).stream()
                        .map(ReviewToolCodec::findingStateToJson)
                        .toList()))
                .put("submitted", new JsonBoolean(context.reviewSubmitted(scope.id())));
        Optional<TourRecord> tour = context.tourOf(scope.id());
        if (tour.isPresent()) {
            try {
                String current = TourFingerprint.of(context.reviewDiff(scope));
                result.put("tour", TourStateJson.of(tour.get(), current));
            } catch (McpToolException e) {
                LOG.log(Level.WARNING, "review_state: could not diff scope " + scope.id() + " for its tour", e);
            }
        }
        return result;
    }

    // ---- review_tour ---------------------------------------------------

    private JsonValue reviewTour(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);
        UnifiedDiff diff = context.reviewDiff(scope);
        List<TourStep> steps;
        try {
            steps = TourCodec.stepsFromAgent(arrayArgument(args, "steps"));
        } catch (TourCodec.InvalidTour e) {
            throw new McpToolException("review_tour rejected, nothing stored: " + e.getMessage());
        }
        checkTourText(steps);
        boolean onlySteps = optionalBooleanArg(args, "onlySteps", false);
        List<ReviewAnnotation> findings = context.findingsOf(scope.id());
        // Read before the store write: it touches the checkout, and the
        // transform runs under the store's lock.
        List<String> noteErrors = impactNoteErrors(caller, steps);
        TourRecord stored;
        try {
            if (onlySteps) {
                String missing = "review_tour rejected, nothing stored: onlySteps needs a stored tour, and scope "
                        + scope.id() + " has no tour; post the whole tour without onlySteps";
                if (context.tourOf(scope.id()).isEmpty()) {
                    throw new McpToolException(missing);
                }
                stored = context.updateTour(scope.id(), current -> {
                    // Judged on the record as it is when written: the
                    // reviewer may have overridden a stale step meanwhile.
                    List<String> live = TourMerge.notStaleReplacements(current, steps, diff);
                    if (!live.isEmpty()) {
                        throw new TourRejected(live);
                    }
                    return validated(TourMerge.replaceSteps(current, steps, diff), diff, findings, noteErrors);
                })
                        .orElseThrow(() -> new McpToolException(missing));
            } else {
                TourRecord fresh = TourRecord.fresh(new ReviewTour(scope.id(), TourFingerprint.of(diff), steps), diff);
                Optional<TourRecord> replaced = context.updateTour(scope.id(),
                        current -> validated(repost(fresh, current), diff, findings, noteErrors));
                if (replaced.isPresent()) {
                    stored = replaced.get();
                } else {
                    stored = validated(fresh, diff, findings, noteErrors);
                    context.putTour(stored);
                }
            }
        } catch (TourRejected e) {
            throw new McpToolException("review_tour rejected, nothing stored:\n- " + String.join("\n- ", e.errors));
        }
        List<TourStep> storedSteps = stored.tour().steps();
        int checks = storedSteps.stream().mapToInt(step -> step.checks().size()).sum();
        JsonObject result = JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("steps", JsonNumber.of(storedSteps.size()))
                .put("checks", JsonNumber.of(checks));
        if (onlySteps) {
            result.put("staleRemaining", JsonArray.of(storedSteps.stream()
                    .filter(step -> stored.progress(step.id()).stale())
                    .<JsonValue>map(step -> new JsonString(step.id()))
                    .toList()));
        }
        return result;
    }

    /** A tour store transform's way of refusing: carries every error, and nothing is stored. */
    private static final class TourRejected extends RuntimeException {
        private final List<String> errors;

        TourRejected(List<String> errors) {
            super(String.join("; ", errors), null, false, false);
            this.errors = List.copyOf(errors);
        }
    }

    /** {@code record} if its tour passes full validation; otherwise throws {@link TourRejected}. */
    private static TourRecord validated(TourRecord record, UnifiedDiff diff, List<ReviewAnnotation> findings,
                                        List<String> noteErrors) {
        List<String> errors = new ArrayList<>(TourValidator.validate(record.tour(), diff, findings));
        errors.addAll(noteErrors);
        if (!errors.isEmpty()) {
            throw new TourRejected(errors);
        }
        return record;
    }

    /**
     * A whole new tour over {@code previous}: the hunk diff's own decisions
     * survive, and so does whether the hunk diff's pre-tour verdicts were
     * already seeded -- once seeded, the verdicts stored are the previous
     * tour's derivations, which must not be seeded as if a human had set them.
     */
    private static TourRecord repost(TourRecord fresh, TourRecord previous) {
        TourRecord record = fresh;
        for (Map.Entry<String, HunkOverride> override : previous.hunkOverrides().entrySet()) {
            record = record.withHunkOverride(override.getKey(), Optional.of(override.getValue()));
        }
        return record.withSeeded(previous.seeded());
    }

    // ---- review_check --------------------------------------------------

    private JsonValue reviewCheck(ManagedSessionId caller, JsonValue arguments) throws McpToolException {
        requireLiveSession(caller);
        JsonObject args = asObject(arguments);
        ReviewScope scope = requireScope(caller, args);
        String checkId = requiredStringArg(args, "checkId");
        String rawVerdict = requiredStringArg(args, "verdict");
        StepGrading.RiskVerdict verdict = StepGrading.RiskVerdict.fromWire(rawVerdict).orElseThrow(() ->
                new McpToolException("verdict must be holds, partly or doesNotHold; got " + rawVerdict));
        String reason = PromptSafety.checkInboundText(requiredStringArg(args, "reason"), "review_check.reason");
        TourRecord record = context.tourOf(scope.id())
                .orElseThrow(() -> new McpToolException("scope " + scope.id() + " has no tour"));
        TourStep step = record.tour().stepOfCheck(checkId)
                .orElseThrow(() -> new McpToolException("no check " + checkId + " in the tour"));
        TourCheck check = step.check(checkId).orElseThrow();
        CheckProgress progress = record.progress(step.id()).check(check.id());
        if (progress.status() != CheckProgress.Status.AWAITING_AGENT) {
            throw new McpToolException("check " + checkId + " is not awaiting a verdict (it is "
                    + progress.status() + ")");
        }
        String current = TourFingerprint.of(context.reviewDiff(scope));
        if (!current.equals(record.tour().diffFingerprint())) {
            return JsonObject.empty()
                    .put("scopeId", new JsonString(scope.id()))
                    .put("checkId", new JsonString(checkId))
                    .put("dropped", new JsonBoolean(true))
                    .put("reason", new JsonString("the diff changed since this answer was given; verdict dropped"));
        }
        CheckProgress[] applied = new CheckProgress[1];
        try {
            context.updateTour(scope.id(), latest -> {
                // Re-checked on the record as it is now: the reviewer may
                // have retried or reset the check since the read above.
                TourStep owner = latest.tour().stepOfCheck(checkId)
                        .orElseThrow(() -> new TourRejected(List.of("no check " + checkId + " in the tour")));
                TourCheck ownCheck = owner.check(checkId).orElseThrow();
                CheckProgress now = latest.progress(owner.id()).check(ownCheck.id());
                if (now.status() != CheckProgress.Status.AWAITING_AGENT) {
                    throw new TourRejected(List.of("check " + checkId + " is not awaiting a verdict (it is "
                            + now.status() + ")"));
                }
                applied[0] = StepGrading.applyRiskVerdict(ownCheck, now, verdict, reason);
                return latest.withProgress(latest.progress(owner.id()).withCheck(applied[0]));
            }).orElseThrow(() -> new McpToolException("scope " + scope.id() + " has no tour"));
        } catch (TourRejected e) {
            throw new McpToolException(String.join("; ", e.errors));
        }
        CheckProgress next = applied[0];
        return JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("checkId", new JsonString(checkId))
                .put("status", new JsonString(next.status().name()));
    }

    private static void checkTourText(List<TourStep> steps) throws McpToolException {
        for (TourStep step : steps) {
            PromptSafety.checkInboundText(step.title(), "tour.step.title");
            PromptSafety.checkInboundText(step.narrative(), "tour.step.narrative");
            for (TourAnchor anchor : step.anchors()) {
                PromptSafety.checkInboundText(anchor.note(), "tour.anchor.note");
            }
            for (ImpactNote note : step.impactNotes()) {
                PromptSafety.checkInboundText(note.text(), "tour.impactNote.text");
            }
            for (TourCheck check : step.checks()) {
                for (int attempt = 0; attempt < check.versions(); attempt++) {
                    TourCheck version = check.version(attempt);
                    PromptSafety.checkInboundText(version.prompt(), "tour.check.prompt");
                    PromptSafety.checkInboundText(version.explanation(), "tour.check.explanation");
                    for (TourCheck.Choice choice : version.choices()) {
                        PromptSafety.checkInboundText(choice.text(), "tour.check.choice");
                    }
                }
            }
        }
    }

    /** A claimed impact note must point at a line that exists in the checkout. */
    private List<String> impactNoteErrors(ManagedSessionId caller, List<TourStep> steps) {
        List<String> errors = new ArrayList<>();
        for (TourStep step : steps) {
            for (ImpactNote note : step.impactNotes()) {
                if (note.line() >= 1 && context.excerpt(caller, note.file(), note.line(), 0).isEmpty()) {
                    errors.add("step " + step.id() + ": impact note points at " + note.file() + ":" + note.line()
                            + ", which does not exist in the checkout");
                }
            }
        }
        return errors;
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
        boolean workflowTarget = args.get("workflow") instanceof JsonBoolean wb && wb.value();

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

        McpSessionContext.HandoffDraft draft = new McpSessionContext.HandoffDraft(
                goal, nextStep, approach, decisions, ruledOut, corrections);

        try {
            if (workflowTarget) {
                Workflow written = context.writeWorkflowHandoff(caller, draft);
                return JsonObject.empty()
                        .put("outcome", new JsonString("written"))
                        .put("target", new JsonString("workflow"))
                        .put("workflowId", new JsonString(written.id().value().toString()))
                        .put("writtenAt", new JsonString(written.brief()
                                .map(WorkflowBrief::writtenAt)
                                .map(Instant::toString)
                                .orElse("")));
            }
            HandoffBrief written = context.writeHandoff(caller, draft);
            return JsonObject.empty()
                    .put("outcome", new JsonString("written"))
                    .put("writtenAt", new JsonString(written.writtenAt().toString()));
        } catch (McpToolException | RuntimeException e) {
            // Only an outright failure is refunded -- but "outright" includes
            // an unchecked one. The context reaches git and the FX thread, and
            // a charge kept for a write that never happened would let a
            // repeatable infrastructure failure burn the whole budget.
            registry.refundHandoff(caller);
            throw e;
        }
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

    private static JsonValue schemaArray(String description) {
        return JsonObject.empty()
                .put("type", new JsonString("array"))
                .put("items", JsonObject.empty().put("type", new JsonString("object")))
                .put("description", new JsonString(description));
    }

    /**
     * An array-valued argument, whichever way the client sent it. The schema
     * says array, but a client may still send the array as a JSON string
     * (the descriptors used to declare these as strings, and models
     * stringify nested JSON); a string holding an array is accepted as that
     * array. Any other value is returned unchanged for the caller's own
     * "must be an array" refusal. A string that does not hold an array is
     * refused here, naming the argument and saying which way it missed --
     * not JSON at all (with the parser's reason, which includes its nesting
     * limit), or JSON of another shape -- so the client knows what to fix.
     */
    private static JsonValue arrayArgument(JsonObject args, String name) throws McpToolException {
        JsonValue value = args.get(name);
        if (!(value instanceof JsonString text)) {
            return value;
        }
        JsonValue parsed;
        try {
            parsed = JsonParser.parse(text.value());
        } catch (JsonParseException e) {
            throw new McpToolException(name + " must be an array (or a JSON string holding one); the string is"
                    + " not valid JSON: " + e.getMessage());
        }
        if (parsed instanceof JsonArray array) {
            return array;
        }
        throw new McpToolException(name + " must be an array (or a JSON string holding one); the string is"
                + " JSON but not an array");
    }

    private static JsonValue schemaBoolean(String description) {
        return JsonObject.empty()
                .put("type", new JsonString("boolean"))
                .put("description", new JsonString(description));
    }
}
