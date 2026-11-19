package app.drydock.intellij.review;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.mcp.ReviewInvocationBindings;
import app.drydock.domain.ModelTiers;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.tour.ReviewTour;
import app.drydock.sdlc.Cancellation;
import app.drydock.sdlc.MaterializedWorkflow;
import app.drydock.sdlc.MaterializedWorkflow.LocalReference;
import app.drydock.sdlc.MaterializedWorkflow.RemoteReference;
import app.drydock.sdlc.MaterializedWorkflow.Role;
import app.drydock.sdlc.SelectedWorkflow;
import app.drydock.sdlc.WorkflowMaterializationRequest;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer;
import app.drydock.sdlc.WorkflowResourceLease;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Asks a session's agent to review its own changes.
 *
 * <p>{@link #request} blocks while it waits for the review service to
 * initialize and for the scope to resolve, so callers run it off the EDT. It
 * sends {@link ReviewInstructions#forScope} unchanged. A session whose agent
 * is not running gets {@link Refused}, and a running one that holds no
 * Drydock MCP access gets {@link NoTools}: in both cases no scope is resolved
 * and nothing is written to its agent.</p>
 */
public final class ReviewRequest {

    private static final Logger LOG = System.getLogger(ReviewRequest.class.getName());

    /**
     * Default bound, in seconds, on the wait for the initialization future.
     * Public because {@code app.drydock.intellij.tasks.PullRequestReview}, in another
     * package, waits on the same bound while it resolves a review prompt.
     */
    public static final long INIT_WAIT_SECONDS = 30;
    /**
     * Default bound, in seconds, on the wait for the scope future.
     * Public for the same reason as {@link #INIT_WAIT_SECONDS}.
     */
    public static final long SCOPE_WAIT_SECONDS = 30;

    /** Resolves the review scope of a session. */
    public interface ScopeSource {
        CompletableFuture<ReviewScope> scopeFor(ManagedAgentSession session);
    }

    /** The result of {@link #request}. */
    public sealed interface Outcome permits Sent, NotReady, NoTools, Refused, Failed, Ambiguous, NoConsole, RemoteUnavailable {}

    /** The review prompt for {@code scopeId} was written to the agent. */
    public record Sent(String scopeId) implements Outcome {}

    /** The review service did not initialize in time, failed to initialize, or is absent. */
    public record NotReady() implements Outcome {}

    /**
     * The session holds no Drydock MCP access, so its agent cannot call the
     * review tools; nothing was written.
     */
    public record NoTools() implements Outcome {}

    /** The agent took no input, so nothing was written. */
    public record Refused() implements Outcome {}

    /** The request failed for {@code cause}. */
    public record Failed(String cause) implements Outcome {}

    /**
     * The write may have reached the agent (the PTY reports no short write), so
     * the request is neither retried nor rolled back: its binding and staged
     * resources stay. Only the user can re-attach and ask again.
     */
    public record Ambiguous(String scopeId) implements Outcome {}

    /** The session's agent is running but its console is not attached; nothing was resolved or written. */
    public record NoConsole() implements Outcome {}

    /**
     * A remote session's review capability is not protocol-confirmed ready (transport, enrollment,
     * scope, data or output) or its console is detached; nothing was written. {@code reason} is a
     * safe user-facing sentence and {@code advice} the manual next step, never performed here.
     */
    public record RemoteUnavailable(String reason, RemoteReviewCapability.Advice advice) implements Outcome {}

    /** The exact-scope seam: false when the scope is foreign or another running session owns it. */
    public interface ScopeAuthorizer {
        boolean authorize(ManagedSessionId session, String scopeId);
    }

    /** One configured workflow: what to stage, where, and who keeps the staged resources once sent. */
    public record Workflow(SelectedWorkflow selected, Destination destination, String invocationId,
                           WorkflowMaterializer materializer, Cancellation cancellation,
                           BiConsumer<ManagedSessionId, MaterializedWorkflow> retain) {}

    /**
     * What an intent-driven request needs beyond the default seams. {@code generation} is checked
     * against {@code currentGeneration} before the commit, so a superseded request sends nothing.
     * An empty {@code workflow} is the default single-line request.
     */
    public record Delivery(ScopeAuthorizer authorizer, ReviewInvocationBindings bindings,
                           Predicate<ManagedSessionId> consoleAttached, long generation,
                           LongSupplier currentGeneration, Optional<Workflow> workflow,
                           Optional<Supplier<RemoteReviewCapability.Report>> remote) {
        /** A local delivery: no remote capability gate. */
        public Delivery(ScopeAuthorizer authorizer, ReviewInvocationBindings bindings,
                        Predicate<ManagedSessionId> consoleAttached, long generation,
                        LongSupplier currentGeneration, Optional<Workflow> workflow) {
            this(authorizer, bindings, consoleAttached, generation, currentGeneration, workflow, Optional.empty());
        }
    }

    private final CompletableFuture<?> initialization;
    private final Supplier<Optional<ScopeSource>> scopes;
    private final Predicate<ManagedSessionId> running;
    private final Predicate<ManagedSessionId> holdsAccess;
    private final Predicate<AgentKind> supportsSubagents;
    private final BiFunction<ManagedSessionId, String, PromptOutcome> submit;
    private final Duration initWait;
    private final Duration scopeWait;

    public ReviewRequest(CompletableFuture<?> initialization,
                         Supplier<Optional<ScopeSource>> scopes,
                         Predicate<ManagedSessionId> running,
                         Predicate<ManagedSessionId> holdsAccess,
                         Predicate<AgentKind> supportsSubagents,
                         BiFunction<ManagedSessionId, String, PromptOutcome> submit) {
        this(initialization, scopes, running, holdsAccess, supportsSubagents, submit,
                Duration.ofSeconds(INIT_WAIT_SECONDS), Duration.ofSeconds(SCOPE_WAIT_SECONDS));
    }

    /**
     * A request served by the backend {@code backend} supplies at request
     * time: scopes come from its resolver while it is {@linkplain
     * ReviewBackend#ready() ready}, and access is {@link
     * ReviewBackend#holdsAccess}. No backend means no scopes and no access.
     */
    public static ReviewRequest forBackend(CompletableFuture<?> initialization,
                                           Supplier<Optional<ReviewBackend>> backend,
                                           Predicate<ManagedSessionId> running,
                                           Predicate<AgentKind> supportsSubagents,
                                           BiFunction<ManagedSessionId, String, PromptOutcome> submit) {
        return new ReviewRequest(initialization,
                () -> backend.get().filter(ReviewBackend::ready).map(b -> b.scopeResolver()::resolve),
                running,
                id -> backend.get().map(b -> b.holdsAccess(id)).orElse(false),
                supportsSubagents, submit);
    }

    ReviewRequest(CompletableFuture<?> initialization,
                  Supplier<Optional<ScopeSource>> scopes,
                  Predicate<ManagedSessionId> running,
                  Predicate<ManagedSessionId> holdsAccess,
                  Predicate<AgentKind> supportsSubagents,
                  BiFunction<ManagedSessionId, String, PromptOutcome> submit,
                  Duration initWait, Duration scopeWait) {
        this.initialization = initialization;
        this.scopes = scopes;
        this.running = running;
        this.holdsAccess = holdsAccess;
        this.supportsSubagents = supportsSubagents;
        this.submit = submit;
        this.initWait = initWait;
        this.scopeWait = scopeWait;
    }

    /**
     * Resolves the session's scope and sends the review prompt to its agent.
     * Returns {@link NotReady} when the review service is not usable,
     * {@link Refused} when the agent is not running, and {@link NoTools} when
     * the session holds no MCP access, before it resolves a scope. The
     * running check comes first because an ended agent's access is already
     * released. Logs the outcome, never the prompt. Blocks; never throws.
     */
    public Outcome request(ManagedAgentSession session) {
        Outcome outcome = outcome(session);
        LOG.log(Level.INFO, "Review Changes for session {0}: {1}", session.id(), outcome);
        return outcome;
    }

    private Outcome outcome(ManagedAgentSession session) {
        try {
            try {
                initialization.get(initWait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException | CancellationException e) {
                return new NotReady();
            }
            Optional<ScopeSource> source = scopes.get();
            if (source.isEmpty()) {
                return new NotReady();
            }
            if (!running.test(session.id())) {
                return new Refused();
            }
            if (!holdsAccess.test(session.id())) {
                return new NoTools();
            }
            ReviewScope scope;
            try {
                scope = source.get().scopeFor(session).get(scopeWait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                return new Failed("resolving the review scope timed out");
            } catch (ExecutionException e) {
                return new Failed(describe(e.getCause() != null ? e.getCause() : e));
            }
            String prompt = ReviewInstructions.forScope(
                    scope.id(), supportsSubagents.test(session.agentKind()));
            PromptOutcome outcome = submit.apply(session.id(), prompt);
            return switch (outcome) {
                case DELIVERED -> new Sent(scope.id());
                case REFUSED -> new Refused();
                case FAILED -> new Failed("the prompt could not be written to the agent");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NotReady();
        } catch (RuntimeException e) {
            return new Failed(describe(e));
        }
    }

    /**
     * Delivers {@code intent}'s review to the session's existing agent exactly once: gates (running,
     * MCP access, console), resolves or takes the scope ({@code selectedScopeId}), authorizes that
     * exact (session, scope), materializes the workflow, installs the invocation binding immediately
     * before the commit and calls {@code submit} once. DELIVERED is {@link Sent}, a known no-send
     * ({@code REFUSED}) rolls the binding back and releases the staged lease, and anything else is
     * {@link Ambiguous} and keeps both. Never retries. Blocks; never throws.
     */
    public Outcome request(ReviewIntent intent, ManagedAgentSession session,
                           Optional<String> selectedScopeId, Delivery delivery) {
        Outcome outcome = deliver(intent, session, selectedScopeId, delivery);
        LOG.log(Level.INFO, "Review request for session {0}: {1}", session.id(), outcome);
        return outcome;
    }

    private Outcome deliver(ReviewIntent intent, ManagedAgentSession session,
                            Optional<String> selectedScopeId, Delivery delivery) {
        try {
            try {
                initialization.get(initWait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException | CancellationException e) {
                return new NotReady();
            }
            Optional<ScopeSource> source = scopes.get();
            if (source.isEmpty()) {
                return new NotReady();
            }
            if (!running.test(session.id()) || !intent.session().filter(session.id()::equals).isPresent()) {
                return new Refused();
            }
            Optional<Outcome> remoteRefusal = remoteRefusal(delivery);
            if (remoteRefusal.isPresent()) {
                return remoteRefusal.get();
            }
            if (delivery.remote().isEmpty() && !holdsAccess.test(session.id())) {
                return new NoTools();
            }
            if (!delivery.consoleAttached().test(session.id())) {
                return new NoConsole();
            }
            String scopeId;
            if (selectedScopeId.isPresent()) {
                scopeId = selectedScopeId.get();
            } else {
                try {
                    scopeId = source.get().scopeFor(session).get(scopeWait.toNanos(), TimeUnit.NANOSECONDS).id();
                } catch (TimeoutException e) {
                    return new Failed("resolving the review scope timed out");
                } catch (ExecutionException e) {
                    return new Failed(describe(e.getCause() != null ? e.getCause() : e));
                }
            }
            if (!delivery.authorizer().authorize(session.id(), scopeId)) {
                return new Refused();
            }
            return commit(intent, session, scopeId, delivery);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NotReady();
        } catch (RuntimeException e) {
            return new Failed(describe(e));
        }
    }

    private Outcome commit(ReviewIntent intent, ManagedAgentSession session, String scopeId, Delivery delivery)
            throws InterruptedException {
        boolean sections = intent.kind() == ReviewTour.Kind.SECTIONS;
        Optional<MaterializedWorkflow> staged = Optional.empty();
        String prompt;
        if (delivery.workflow().isPresent()) {
            Workflow workflow = delivery.workflow().get();
            WorkflowMaterializer.Result result;
            try {
                result = workflow.materializer().materialize(new WorkflowMaterializationRequest(
                        workflow.invocationId(), delivery.generation(), workflow.selected(),
                        workflow.destination(), session.mode(),
                        Optional.of(WorkflowMaterializationRequest.ReviewContext.from(intent, scopeId))),
                        workflow.cancellation()).get(scopeWait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException e) {
                workflow.cancellation().cancel();
                return new Failed("staging the review instructions failed");
            }
            if (!(result instanceof WorkflowMaterializer.Success success)) {
                return new Failed(result instanceof WorkflowMaterializer.Failure f
                        ? f.message() : "the review request was cancelled");
            }
            staged = Optional.of(success.workflow());
            Optional<String> directive = directive(success.workflow(), scopeId);
            if (directive.isEmpty()) {
                success.workflow().lease().release();
                return new Failed("the staged review instructions have no safe single-line reference");
            }
            prompt = directive.get();
        } else {
            boolean subagents = supportsSubagents.test(session.agentKind());
            prompt = sections
                    ? ReviewInstructions.forSections(scopeId, subagents, ModelTiers.EMPTY, Optional.empty())
                    : ReviewInstructions.forScope(scopeId, subagents);
        }
        Optional<WorkflowResourceLease> lease = staged.map(MaterializedWorkflow::lease);
        if (delivery.generation() != delivery.currentGeneration().getAsLong()) {
            lease.ifPresent(WorkflowResourceLease::release);
            return new Failed("the review request was superseded before it was sent");
        }
        Optional<Outcome> lateRefusal = remoteRefusal(delivery);
        if (lateRefusal.isPresent()) {
            lease.ifPresent(WorkflowResourceLease::release);
            return lateRefusal.get();
        }
        ReviewInvocationBindings.Ticket ticket = delivery.bindings().install(session.id(), scopeId,
                intent.pullRequestTarget().map(pr -> pr.url()).orElse("local"), intent.kind());
        PromptOutcome sent;
        try {
            sent = submit.apply(session.id(), prompt);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Review submit threw; delivery is ambiguous", e);
            sent = PromptOutcome.FAILED;
        }
        if (sent == PromptOutcome.REFUSED) {
            delivery.bindings().rollback(ticket);
            lease.ifPresent(WorkflowResourceLease::release);
            return new Refused();
        }
        if (staged.isPresent() && lease.get().transferToSession()) {
            delivery.workflow().get().retain().accept(session.id(), staged.get());
        }
        return sent == PromptOutcome.DELIVERED ? new Sent(scopeId) : new Ambiguous(scopeId);
    }

    /**
     * The hard remote gate (INV-25/58/61): empty for a local delivery or a runnable remote one; else
     * the refusal. Applies to configured and default review alike, so choosing the default never
     * repairs transport. Detached-but-enrolled keeps its tools yet needs a reattached console; an
     * unreadable capability is a refusal, never a send.
     */
    private static Optional<Outcome> remoteRefusal(Delivery delivery) {
        if (delivery.remote().isEmpty()) {
            return Optional.empty();
        }
        RemoteReviewCapability.Report report;
        try {
            report = delivery.remote().get().get();
        } catch (RuntimeException e) {
            return Optional.of(new RemoteUnavailable("the remote review capability could not be assessed",
                    RemoteReviewCapability.Advice.CHECK_EVIDENCE_AND_RETRY));
        }
        if (report.runnable()) {
            return Optional.empty();
        }
        if (report.toolsReady()) {
            return Optional.of(new NoConsole());
        }
        RemoteReviewCapability.State blocked = Stream.of(report.transport(), report.enrollment(),
                report.scope(), report.data(), report.output()).filter(st -> !st.isReady()).findFirst().orElseThrow();
        return Optional.of(new RemoteUnavailable(blocked.detail().isBlank() ? blocked.kind().name() : blocked.detail(),
                report.advice()));
    }

    /**
     * The one short line that sends the agent to the staged instruction file, or empty when that
     * path could not ride a single-line prompt (control character or quote). The project text itself
     * is never in the line.
     */
    private static Optional<String> directive(MaterializedWorkflow workflow, String scopeId) {
        for (MaterializedWorkflow.Reference reference : workflow.references()) {
            if (reference.role() != Role.INSTRUCTION_FILE) {
                continue;
            }
            String path = switch (reference) {
                case LocalReference l -> l.path().toString();
                case RemoteReference r -> r.remotePath();
            };
            if (path.indexOf('"') >= 0 || path.chars().anyMatch(Character::isISOControl)) {
                return Optional.empty();
            }
            return Optional.of("Follow the project review instructions in the file \"" + path
                    + "\", then read review_scope for handle " + scopeId
                    + " with include=instructions and post the review against that handle.");
        }
        return Optional.empty();
    }

    /**
     * The user-facing text for an outcome, or empty for {@link Sent}.
     */
    public static Optional<String> message(String taskName, Outcome outcome) {
        return switch (outcome) {
            case Sent s -> Optional.empty();
            case NotReady n -> Optional.of("Drydock's review service is not running, so "
                    + taskName + " cannot review its changes. Restart the IDE to retry.");
            case NoTools t -> Optional.of(taskName
                    + "'s agent is running without Drydock's review tools, so it cannot review its changes."
                    + " A Terminal agent never has them, nor does an agent whose launch could not load them"
                    + " (see the IDE log); otherwise deactivate and resume the task to retry.");
            case Refused r -> Optional.of(taskName
                    + "'s agent is not running, so it cannot take the review request.");
            case Failed f -> Optional.of("Drydock could not start the review for "
                    + taskName + ": " + f.cause());
            case Ambiguous a -> Optional.of("The review request for " + taskName
                    + " was handed to its agent's console but receipt is unconfirmed, and it is not retried."
                    + " Check the console; re-attach and ask again only if nothing happened.");
            case RemoteUnavailable u -> Optional.of("The review for " + taskName + " was not sent: " + u.reason()
                    + switch (u.advice()) {
                        case STOP_THEN_RESUME -> ". Stop the task, then Resume it, and retry the review.";
                        case CHECK_EVIDENCE_AND_RETRY -> ". Capability could not be verified; check, then retry.";
                        case REATTACH_THEN_RETRY -> ". Resume or re-attach the console, then retry the review.";
                        case NONE -> ".";
                    });
            case NoConsole c -> Optional.of(taskName
                    + "'s console is not attached, so the review was not sent."
                    + " Resume or re-attach the console, then retry the review.");
        };
    }

    private static String describe(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String text = cause.getMessage();
        return text != null && !text.isBlank() ? text : cause.getClass().getSimpleName();
    }
}
