package app.drydock.intellij.review;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.review.ReviewInstructions;
import app.drydock.review.ReviewScope;

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
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Asks a session's agent to review its own changes.
 *
 * <p>{@link #request} blocks while it waits for the review service to
 * initialize and for the scope to resolve, so callers run it off the EDT. It
 * sends {@link ReviewInstructions#forScope} unchanged, with or without the
 * guided tour as the caller asked. A session whose agent
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
    public sealed interface Outcome permits Sent, NotReady, NoTools, Refused, Failed {}

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
     * released. {@code withTour} asks the agent for a guided tour beside
     * its findings. Logs the outcome, never the prompt. Blocks; never throws.
     */
    public Outcome request(ManagedAgentSession session, boolean withTour) {
        Outcome outcome = outcome(session, withTour);
        LOG.log(Level.INFO, "Review Changes ({0}) for session {1}: {2}",
                withTour ? "with tour" : "findings only", session.id(), outcome);
        return outcome;
    }

    private Outcome outcome(ManagedAgentSession session, boolean withTour) {
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
            String prompt = ReviewInstructions.forScope(scope.id(), supportsSubagents.test(session.agentKind()),
                    ModelTiers.EMPTY, Optional.empty(), withTour);
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
