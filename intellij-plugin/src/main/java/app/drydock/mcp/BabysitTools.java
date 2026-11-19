package app.drydock.mcp;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitStateFile;
import app.drydock.babysit.BabysitStateFile.RerunCharge;
import app.drydock.babysit.BabysitStateFileException;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCiRerunService;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static app.drydock.mcp.TaskToolCodec.descriptor;
import static app.drydock.mcp.TaskToolCodec.schemaString;

/**
 * The babysitting MCP tools (docs/BabysittingSessions.md), dispatched apart
 * from the main router like {@link TaskTools}, over a {@link
 * BabysitToolContext}: {@code babysit_start}, {@code ci_rerun} and {@code
 * babysit_escalate}. Failures are {@link McpToolException}s naming what went
 * wrong; no unchecked exception escapes a call.
 *
 * <p><b>Identity, not listing, gates them.</b> The plugin lists one tool set
 * to every session, so "only the babysitter may rerun or escalate" is
 * decided here from the caller's id and the persisted pairings: the caller
 * must be the babysitter of a pairing that is not STOPPED, and the watched
 * session and PR are what that pairing names -- no argument can name them.
 * {@code babysit_start} is the reverse: the CALLER is the session to be
 * watched, and its PR is resolved from its own branch, never taken from an
 * argument (an optional {@code prNumber} only cross-checks the join).</p>
 *
 * <p><b>A rerun must be the babysat PR's own.</b> Before anything is
 * charged, {@code ci_rerun} reads the run and requires its head commit to be
 * the PR's head -- the pairing's {@link BabysitPairing#knownHead()} when the
 * monitor has recorded one, else the head gh reports now. A run it cannot
 * read, or one that ran on another commit, is refused with nothing counted
 * and nothing rerun.</p>
 *
 * <p><b>The rerun cap is persisted</b> ({@link BabysitStateFile}), not an
 * in-memory budget: at most {@link BabysitStateFile#MAX_RERUNS_PER_RUN}
 * reruns per workflow run, surviving restarts and shared with a detached
 * watcher. The count is taken and flushed in one locked transaction BEFORE
 * gh runs; it is released only when the request definitely did not reach a
 * rerun (refused by GitHub, gh missing, unauthenticated, not launchable),
 * never when the outcome is unknown. An escalation charge is released only
 * for an explicit refusal or a synchronous failure before dispatch returns a
 * future; timeout and other post-dispatch failures keep the charge because
 * the write outcome is unknown. {@code session_handoff} keeps its separate
 * refund policy.</p>
 *
 * <p>Every method blocks the calling MCP handler thread (bounded joins,
 * state-file I/O); never call it on the EDT.</p>
 */
public final class BabysitTools {

    private static final Logger LOG = Logger.getLogger(BabysitTools.class.getName());

    /** The three tool names, in descriptor order. */
    public static final List<String> NAMES = List.of("babysit_start", "ci_rerun", "babysit_escalate");

    /** Longest optional check name echoed by {@code ci_rerun}, in code points. */
    static final int MAX_CHECK_NAME_CHARS = 200;

    /** Covers the PR join (git probe plus a 15 s gh listing) and the session service's pairing reservation. */
    static final Duration START_TIMEOUT = Duration.ofSeconds(45);

    /** Above {@link GhCiRerunService#PROCESS_TIMEOUT}, so gh's own timeout answers first. */
    static final Duration RERUN_TIMEOUT = GhCiRerunService.PROCESS_TIMEOUT.plusSeconds(15);

    /** A memory-only lookup or one state-store update. */
    static final Duration STATE_TIMEOUT = Duration.ofSeconds(20);

    /** Above {@link GhCiRerunService#READ_TIMEOUT}, so gh's own timeout answers first. */
    static final Duration RUN_READ_TIMEOUT = GhCiRerunService.READ_TIMEOUT.plusSeconds(15);

    /** Covers the repository-setting lookup and one 15 s gh read of the PR. */
    static final Duration HEAD_READ_TIMEOUT = Duration.ofSeconds(30);

    private final BabysitToolContext context;
    private final McpSessionRegistry registry;
    private final BabysitStateFile stateFile;

    public BabysitTools(BabysitToolContext context, McpSessionRegistry registry, BabysitStateFile stateFile) {
        this.context = Objects.requireNonNull(context, "context");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
    }

    /** The three tool descriptors, in the router's descriptor shape. */
    public List<JsonValue> toolDescriptors() {
        return List.of(
                descriptor("babysit_start",
                        "Starts a Drydock babysitter for THIS session's own open pull request: a separate"
                                + " agent session, in its own detached worktree at the PR branch's remote tip,"
                                + " that watches the PR while this session is idle or away -- merge conflicts"
                                + " and CI breakages -- fixes trivial cases, retriggers flaky CI (at most "
                                + BabysitStateFile.MAX_RERUNS_PER_RUN + " reruns per run) and escalates"
                                + " anything wider back to this session. The PR is resolved from this"
                                + " session's branch; no argument can point it at another session."
                                + " launching:true means the pairing is recorded and the babysitter's launch"
                                + " is under way; a launch failure afterwards is logged and undoes the"
                                + " pairing. Refused when this session's branch resolves to no open PR, it"
                                + " already has an active babysitter, it is itself a babysitter, or it runs"
                                + " on a workspace box.",
                        JsonObject.empty().put("prNumber", schemaInteger("Optional cross-check: the PR"
                                + " number you expect this session's branch to resolve to. Refused when the"
                                + " branch resolves to a different PR; it never selects the PR."))),
                descriptor("ci_rerun",
                        "Babysitter only: reruns the failed jobs of one GitHub Actions workflow run of"
                                + " the pull request this session babysits, after you judged the failure an"
                                + " environment issue or a flake. At most " + BabysitStateFile.MAX_RERUNS_PER_RUN
                                + " reruns per run id, counted across restarts; when a run has had them all,"
                                + " the tool refuses -- escalate with babysit_escalate instead. A rerun whose"
                                + " outcome is unknown (timed out) still counts. Refused, and not counted, for"
                                + " a run that did not run on the babysat PR's current head commit, and for a"
                                + " session that is not an active babysitter.",
                        JsonObject.empty()
                                .put("runId", schemaInteger("The workflow run id, as in a check's details"
                                        + " URL .../actions/runs/<runId>/job/...; a positive integer."))
                                .put("checkName", schemaString("Optional: the failing check's name, echoed"
                                        + " back for your own bookkeeping, at most " + MAX_CHECK_NAME_CHARS
                                        + " characters.")),
                        "runId"),
                descriptor("babysit_escalate",
                        "Babysitter only: records an escalation for the session you babysit -- something"
                                + " you will not fix yourself (a design decision, a non-mechanical conflict,"
                                + " a real test failure, a CI failure that survived its reruns). The human"
                                + " sees it on the watched session; nothing is typed into that session's"
                                + " conversation. Say what happened, what you already did, and what the"
                                + " main session needs to decide or do. At most "
                                + McpSessionRegistry.MAX_ESCALATIONS_PER_SESSION + " per session.",
                        JsonObject.empty()
                                .put("message", schemaString("What needs the main session, at most "
                                        + PromptSafety.MAX_HANDOFF_SLOT_CHARS + " characters; not blank."))
                                .put("detail", schemaString("Optional supporting detail (log excerpt,"
                                        + " what you tried), at most " + PromptSafety.MAX_HANDOFF_SLOT_CHARS
                                        + " characters.")),
                        "message"));
    }

    /** Bounded dispatch; every refusal names what went wrong. */
    public JsonValue call(ManagedSessionId caller, String tool, JsonObject arguments) throws McpToolException {
        JsonObject args = arguments == null ? JsonObject.empty() : arguments;
        return switch (tool) {
            case "babysit_start" -> babysitStart(caller, args);
            case "ci_rerun" -> ciRerun(caller, args);
            case "babysit_escalate" -> babysitEscalate(caller, args);
            default -> throw new McpToolException("Unknown tool: " + tool);
        };
    }

    // ---- babysit_start ----------------------------------------------------------

    private JsonValue babysitStart(ManagedSessionId caller, JsonObject args) throws McpToolException {
        OptionalInt expected = optionalPositiveInt(args, "prNumber");
        BabysitToolContext.PullRequestLookup lookup = await(() -> context.openPullRequestOf(caller), START_TIMEOUT,
                "resolving this session's pull request");
        if (lookup instanceof BabysitToolContext.PullRequestLookup.NotFound notFound) {
            throw new McpToolException(notFound.reason());
        }
        BabysitToolContext.PullRequestLookup.Open open = (BabysitToolContext.PullRequestLookup.Open) lookup;
        if (expected.isPresent() && expected.getAsInt() != open.number()) {
            throw new McpToolException("This session's branch resolves to PR #" + open.number() + ", not #"
                    + expected.getAsInt() + "; a babysitter watches only this session's own PR.");
        }
        BabysitToolContext.StartOutcome started = await(
                () -> context.startBabysitter(caller, open.number(), open.headBranch()), START_TIMEOUT,
                "starting the babysitter");
        if (started instanceof BabysitToolContext.StartOutcome.Refused refused) {
            throw new McpToolException(refused.reason());
        }
        BabysitPairing pairing = ((BabysitToolContext.StartOutcome.Launching) started).pairing();
        return JsonObject.empty()
                .put("babysitterSessionId", new JsonString(pairing.babysitterId().toString()))
                .put("watchedSessionId", new JsonString(pairing.watchedId().toString()))
                .put("prNumber", JsonNumber.of((long) pairing.prNumber()))
                .put("prBranch", new JsonString(open.headBranch()))
                .put("launching", new JsonBoolean(true));
    }

    // ---- ci_rerun ---------------------------------------------------------------

    private JsonValue ciRerun(ManagedSessionId caller, JsonObject args) throws McpToolException {
        long runId = requiredPositiveLong(args, "runId");
        Optional<String> checkName = optionalCheckName(args);
        BabysitPairing pairing = requireBabysitter(caller, "ci_rerun");
        requireRunOfPullRequest(pairing, runId);
        // The reads above take seconds: an engagement stopped meanwhile must not
        // still POST a rerun.
        requireBabysitter(caller, "ci_rerun");

        RerunCharge charge;
        try {
            charge = stateFile.chargeRerun(runId, BabysitStateFile.MAX_RERUNS_PER_RUN);
        } catch (BabysitStateFileException e) {
            throw new McpToolException("The rerun could not be counted, so nothing was rerun: " + e.getMessage());
        }
        if (charge instanceof RerunCharge.AtCap atCap) {
            throw new McpToolException("Run " + runId + " has already been rerun " + atCap.count() + " times, the"
                    + " limit of " + BabysitStateFile.MAX_RERUNS_PER_RUN + " per run. Do not retry: escalate"
                    + " it to the main session with babysit_escalate, saying what failed and what the reruns"
                    + " showed.");
        }
        int count = ((RerunCharge.Charged) charge).count();
        LOG.log(Level.INFO, () -> "Babysitter " + caller + " reruns workflow run " + runId + " of PR #"
                + pairing.prNumber() + " (attempt " + count + " of " + BabysitStateFile.MAX_RERUNS_PER_RUN + ")");

        GhCiRerunService.Outcome outcome = rerunOutcome(pairing, runId);
        return switch (outcome) {
            case GhCiRerunService.Rerun rerun -> JsonObject.empty()
                    .put("outcome", new JsonString("rerun"))
                    .put("runId", JsonNumber.of(runId))
                    .put("checkName", checkName.<JsonValue>map(JsonString::new).orElse(JsonNull.INSTANCE))
                    .put("prNumber", JsonNumber.of((long) pairing.prNumber()))
                    .put("rerunCount", JsonNumber.of((long) count))
                    .put("rerunsLeft", JsonNumber.of((long) (BabysitStateFile.MAX_RERUNS_PER_RUN - count)));
            case GhCiRerunService.Unknown unknown -> throw new McpToolException(unknown.message()
                    + " This attempt still counts: run " + runId + " has used " + count + " of its "
                    + BabysitStateFile.MAX_RERUNS_PER_RUN + " reruns.");
            case GhCiRerunService.Rejected rejected -> throw new McpToolException("GitHub did not rerun run "
                    + runId + ": " + rejected.message() + " (" + refund(runId, count) + ").");
            case GhCiRerunService.Unavailable unavailable -> throw new McpToolException("gh cannot rerun CI here ("
                    + unavailable.message() + "); nothing was rerun (" + refund(runId, count) + "). Escalate with"
                    + " babysit_escalate if the failure needs a rerun.");
        };
    }

    /**
     * Refuses, before anything is charged, a run that cannot be tied to the
     * pairing's PR: the run must be readable, and its head commit and head
     * branch must be the PR's head commit and head branch as gh reports them
     * now -- a same-commit run of another branch is not this PR's.
     */
    private void requireRunOfPullRequest(BabysitPairing pairing, long runId) throws McpToolException {
        GhCiRerunService.RunRead read = await(() -> context.readRun(pairing, runId), RUN_READ_TIMEOUT,
                "reading workflow run " + runId);
        if (read instanceof GhCiRerunService.RunUnreadable unreadable) {
            throw new McpToolException("Drydock could not read workflow run " + runId + " (" + unreadable.message()
                    + "), so it cannot tie it to PR #" + pairing.prNumber() + "; nothing was rerun or counted.");
        }
        GhCiRerunService.RunHead run = (GhCiRerunService.RunHead) read;
        BabysitToolContext.PullRequestHead head = await(() -> context.pullRequestHeadOf(pairing),
                HEAD_READ_TIMEOUT, "reading PR #" + pairing.prNumber() + "'s head");
        if (head instanceof BabysitToolContext.PullRequestHead.Unresolved unresolved) {
            throw new McpToolException("Drydock could not read PR #" + pairing.prNumber() + "'s head ("
                    + unresolved.reason() + "), so it cannot tie run " + runId + " to it; nothing was rerun"
                    + " or counted.");
        }
        BabysitToolContext.PullRequestHead.Known known = (BabysitToolContext.PullRequestHead.Known) head;
        if (!run.headSha().equals(known.sha())) {
            throw new McpToolException("Workflow run " + runId + " ran on commit " + run.headSha() + ", but PR #"
                    + pairing.prNumber() + "'s head is " + known.sha() + ": ci_rerun reruns only the runs of the"
                    + " pull request you babysit, at its current head. Nothing was rerun or counted.");
        }
        if (!run.headBranch().equals(known.branch())) {
            throw new McpToolException("Workflow run " + runId + " ran for branch '" + run.headBranch()
                    + "', but PR #" + pairing.prNumber() + "'s head branch is '" + known.branch() + "': ci_rerun"
                    + " reruns only the runs of the pull request you babysit. Nothing was rerun or counted.");
        }
    }

    /**
     * The rerun's outcome. The context's own future should never fail; if it
     * does, or the bounded wait expires, whether gh already ran is unknown,
     * so the attempt is reported as {@link GhCiRerunService.Unknown} and the
     * count stands.
     */
    private GhCiRerunService.Outcome rerunOutcome(BabysitPairing pairing, long runId) {
        CompletableFuture<GhCiRerunService.Outcome> future;
        try {
            future = context.rerunFailedJobs(pairing, runId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The rerun of run " + runId + " could not be dispatched", e);
            return new GhCiRerunService.Unknown("The rerun request of run " + runId + " failed to dispatch ("
                    + e.getMessage() + "); whether it reached GitHub is unknown.");
        }
        try {
            return future.get(RERUN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GhCiRerunService.Unknown("Waiting for the rerun of run " + runId + " was interrupted;"
                    + " whether GitHub reran it is unknown -- check the run's attempts before asking again.");
        } catch (TimeoutException e) {
            return new GhCiRerunService.Unknown("Drydock did not hear back on the rerun of run " + runId
                    + " within " + RERUN_TIMEOUT.toSeconds() + "s; whether GitHub reran it is unknown -- check"
                    + " the run's attempts before asking again.");
        } catch (ExecutionException | CancellationException e) {
            LOG.log(Level.WARNING, "The rerun of run " + runId + " failed", e);
            return new GhCiRerunService.Unknown("The rerun request of run " + runId + " failed ("
                    + causeOf(e).getMessage() + "); whether it reached GitHub is unknown.");
        }
    }

    /** Refunds a rerun that did not reach GitHub; answers, honestly, whether it still counts. */
    private String refund(long runId, int count) {
        try {
            stateFile.refundRerun(runId);
            return "not counted against the run's reruns";
        } catch (BabysitStateFileException e) {
            // Erring toward the cap: a lost refund costs one rerun, never an extra one.
            LOG.log(Level.WARNING, "Could not release the rerun count of run " + runId + ": " + e.getMessage());
            return "the refund could not be recorded, so this attempt still counts: run " + runId + " has used "
                    + count + " of its " + BabysitStateFile.MAX_RERUNS_PER_RUN + " reruns";
        }
    }

    // ---- babysit_escalate -------------------------------------------------------

    private JsonValue babysitEscalate(ManagedSessionId caller, JsonObject args) throws McpToolException {
        // Validate before charging: a malformed escalation is the agent's to fix, not a spend.
        String message = PromptSafety.checkHandoffSlot("message", TaskToolCodec.requiredStringArg(args, "message"));
        Optional<String> detail = TaskToolCodec.optionalStringArg(args, "detail");
        if (detail.isPresent()) {
            PromptSafety.checkHandoffSlot("detail", detail.get());
        }
        String text = detail.map(d -> message.strip() + "\n\n" + d.strip()).orElse(message.strip());
        requireBabysitter(caller, "babysit_escalate");

        try {
            registry.chargeEscalation(caller);
        } catch (McpBudgetExhaustedException e) {
            throw new McpToolException(e.getMessage());
        }
        BabysitToolContext.EscalateOutcome outcome;
        try {
            outcome = await(() -> context.escalate(caller, text), STATE_TIMEOUT,
                    "recording the escalation");
        } catch (DispatchFailedException e) {
            // The context never returned a future, so no escalation write was dispatched.
            registry.refundEscalation(caller);
            throw e;
        }
        if (outcome instanceof BabysitToolContext.EscalateOutcome.Refused refused) {
            // Refused explicitly guarantees that the context did not write anything.
            registry.refundEscalation(caller);
            throw new McpToolException(refused.reason());
        }
        Escalation escalation = ((BabysitToolContext.EscalateOutcome.Escalated) outcome).escalation();
        return JsonObject.empty()
                .put("outcome", new JsonString("escalated"))
                .put("escalationId", new JsonString(escalation.id().toString()))
                .put("watchedSessionId", new JsonString(escalation.watchedId().toString()))
                .put("prNumber", JsonNumber.of((long) escalation.prNumber()))
                .put("createdAt", new JsonString(escalation.createdAt().toString()));
    }

    // ---- shared -----------------------------------------------------------------

    /** The identity gate: the caller's own active pairing, or a refusal. */
    private BabysitPairing requireBabysitter(ManagedSessionId caller, String tool) throws McpToolException {
        return await(() -> context.activePairingOf(caller), STATE_TIMEOUT,
                "reading this session's babysitting pairing")
                .orElseThrow(() -> new McpToolException(tool + " is only for an active Drydock babysitter"
                        + " session, and this session babysits no pull request (or its engagement has"
                        + " stopped)."));
    }

    /**
     * A positive long given as an integral JSON number or, from a client
     * that stringifies arguments, as a decimal string.
     */
    private static long requiredPositiveLong(JsonObject args, String key) throws McpToolException {
        JsonValue value = args.get(key);
        if (value == null || value instanceof JsonNull) {
            throw new McpToolException("Missing required argument '" + key + "'.");
        }
        String literal = value instanceof JsonNumber number ? number.literal()
                : value instanceof JsonString text ? text.value().strip() : null;
        if (literal == null || !literal.matches("[0-9]{1,19}")) {
            throw new McpToolException("Argument '" + key + "' must be a positive integer.");
        }
        try {
            long parsed = Long.parseLong(literal);
            if (parsed <= 0) {
                throw new McpToolException("Argument '" + key + "' must be a positive integer.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new McpToolException("Argument '" + key + "' is too large.");
        }
    }

    private static OptionalInt optionalPositiveInt(JsonObject args, String key) throws McpToolException {
        if (!args.has(key) || args.get(key) instanceof JsonNull) {
            return OptionalInt.empty();
        }
        long value = requiredPositiveLong(args, key);
        if (value > Integer.MAX_VALUE) {
            throw new McpToolException("Argument '" + key + "' is too large.");
        }
        return OptionalInt.of((int) value);
    }

    private static Optional<String> optionalCheckName(JsonObject args) throws McpToolException {
        Optional<String> name = TaskToolCodec.optionalStringArg(args, "checkName");
        if (name.isEmpty()) {
            return name;
        }
        PromptSafety.checkInboundText(name.get(), "checkName");
        String text = name.get().strip();
        if (text.codePointCount(0, text.length()) > MAX_CHECK_NAME_CHARS || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            throw new McpToolException("checkName must be one line of at most " + MAX_CHECK_NAME_CHARS
                    + " characters.");
        }
        return Optional.of(text);
    }

    private static JsonValue schemaInteger(String description) {
        return JsonObject.empty()
                .put("type", new JsonString("integer"))
                .put("description", new JsonString(description));
    }

    /** An await dispatch failure known to occur before a context future is returned. */
    private static final class DispatchFailedException extends McpToolException {
        private DispatchFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Dispatches one context call and waits for it ({@link #join}). A call
     * that throws instead of returning a future is reported as a bounded tool
     * failure and marked separately so callers can identify a known
     * pre-dispatch failure.
     */
    private static <T> T await(Supplier<CompletableFuture<T>> dispatch, Duration timeout, String operation)
            throws McpToolException {
        CompletableFuture<T> future;
        try {
            future = Objects.requireNonNull(dispatch.get(), "the context answered no future");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Drydock failed before " + operation + " began", e);
            throw new DispatchFailedException("Drydock failed before " + operation + " began: " + e.getMessage(), e);
        }
        return join(future, timeout, operation);
    }

    /**
     * Waits a bounded time for one context future. Domain refusals travel
     * inside the future, so only a platform failure or an expired wait
     * lands here, reported as a refusal -- never an escaped exception.
     */
    private static <T> T join(CompletableFuture<T> future, Duration timeout, String operation)
            throws McpToolException {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Interrupted while " + operation + "; try again.");
        } catch (TimeoutException e) {
            throw new McpToolException("Drydock did not finish " + operation + " within " + timeout.toSeconds()
                    + "s; its outcome is unknown -- the human can see whether it happened.");
        } catch (ExecutionException | CancellationException e) {
            throw new McpToolException("Drydock failed while " + operation + ": " + causeOf(e).getMessage());
        }
    }

    private static Throwable causeOf(Exception e) {
        return e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    }
}
