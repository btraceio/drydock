package app.drydock.intellij.session;

import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitStateTransforms;
import app.drydock.babysit.BabysitWakePolicy;
import app.drydock.babysit.BabysitWakePolicy.Decision;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.agent.PromptOutcome;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Wakes a babysitter with a one-line pointer prompt, the way
 * {@link BabysitWakePolicy} decides: typed into an idle running agent, or
 * carried by a resume of a stopped one whose conversation is bound; skipped
 * otherwise, for the caller (the monitor) to retry on a later tick.
 *
 * <p>Only an active engagement is woken: a babysitter whose pairing is
 * STOPPED or gone (deleted, rolled back) gets {@link WakeOutcome.Inactive}
 * and nothing is typed or resumed. A Stop that lands after that check but
 * before a dispatched resume has registered its agent finds nothing to
 * stop; so once the resume completes (its agent registered) the engagement
 * is checked again, and an agent resumed for an engagement that has
 * stopped meanwhile is stopped here and the wake is {@link
 * WakeOutcome.Inactive}. Either the Stop's state write precedes that
 * re-check (seen here), or it follows the registration (the Stop's own
 * agent stop finds it).</p>
 *
 * <p>The decision is a snapshot, and the state can change before it is
 * acted on. Both actions refuse safely when it has: {@code submitPrompt}
 * refuses a session that is no longer running, and the resume refuses a
 * session that has become live or claimed ({@link
 * DrydockSessionService.SessionBusyException}) or is still unbound ({@link
 * DrydockSessionService.UnboundConversationException}) -- those come back as
 * skips, not failures.</p>
 *
 * <p><b>One wake at a time per babysitter, and a wake's own IDLE is not
 * trusted.</b> Decide-and-act runs under a per-babysitter lock, so two wakes
 * cannot both read IDLE and both type. After a delivered pointer (or a
 * resume) the babysitter is marked woken: the activity monitor keeps
 * reporting the IDLE it saw before the paste until the agent's hook reports
 * the turn, so while the mark stands an IDLE reading is stale and the wake is
 * {@link Decision#SKIP_BUSY}. The mark clears on the first non-IDLE sample,
 * or once {@link #WOKEN_STALE_WINDOW} has passed (an agent that finished
 * before any sample, or one that reports no turn at all). The race left is
 * the policy's own: an agent the user prompts by hand between the read and
 * the write gets the paste mid-turn.</p>
 *
 * <p>Every action runs on the executor: the PTY write blocks, and the
 * resume's first stage reads state.</p>
 */
public final class BabysitWaker {

    private static final Logger LOG = System.getLogger(BabysitWaker.class.getName());

    /** How long a wake's own IDLE stays untrusted when no non-IDLE sample arrives. */
    static final Duration WOKEN_STALE_WINDOW = Duration.ofSeconds(90);

    /** What a wake did. */
    public sealed interface WakeOutcome {

        /**
         * The pointer was typed into the running agent and the write reported
         * {@link PromptOutcome#DELIVERED}. A refused write is {@link Skipped},
         * a failed one {@link Failed}, so this always means delivered.
         */
        record Submitted() implements WakeOutcome {
        }

        /** The babysitter was resumed with the pointer as its first message. */
        record Resumed(ManagedAgentSession session) implements WakeOutcome {
            public Resumed {
                Objects.requireNonNull(session, "session");
            }
        }

        /** Nothing was done; {@code reason} is {@link Decision#SKIP_BUSY} or {@link Decision#SKIP_UNBOUND}. */
        record Skipped(Decision reason) implements WakeOutcome {
            public Skipped {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** The babysitter has no active engagement (its pairing is STOPPED or gone); nothing was done. */
        record Inactive() implements WakeOutcome {
        }

        /** The wake could not be attempted or its action failed; {@code attempted} is what was decided. */
        record Failed(Optional<Decision> attempted, String message) implements WakeOutcome {
            public Failed {
                Objects.requireNonNull(attempted, "attempted");
                Objects.requireNonNull(message, "message");
            }
        }
    }

    private final CompletableFuture<Void> initialization;
    private final Predicate<ManagedSessionId> engaged;
    private final Predicate<ManagedSessionId> running;
    private final Predicate<ManagedSessionId> startingOrClaimed;
    private final Function<ManagedSessionId, Optional<SessionActivity>> activity;
    private final Function<ManagedSessionId, Optional<ManagedAgentSession>> row;
    private final BiFunction<ManagedSessionId, String, PromptOutcome> submit;
    private final BiFunction<ManagedSessionId, Optional<String>, CompletableFuture<ManagedAgentSession>> resume;
    private final Consumer<ManagedSessionId> stop;
    private final Executor executor;
    private final LongSupplier nanoClock;

    /** Serialises decide-and-act per babysitter. */
    private final Map<ManagedSessionId, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** When each babysitter was last woken ({@link #nanoClock}), until a non-IDLE sample or the window clears it. */
    private final Map<ManagedSessionId, Long> wokenAt = new ConcurrentHashMap<>();

    /**
     * The full seam. {@code engaged}, {@code running}, {@code
     * startingOrClaimed} and {@code activity} must not block; {@code row}
     * reads the in-memory catalog; {@code submit} blocks (a PTY write) and
     * runs on {@code executor}; {@code stop} stops a babysitter's agent
     * wherever it runs and must not block; {@code nanoClock} is {@link
     * System#nanoTime} in production.
     */
    BabysitWaker(CompletableFuture<Void> initialization,
                 Predicate<ManagedSessionId> engaged,
                 Predicate<ManagedSessionId> running,
                 Predicate<ManagedSessionId> startingOrClaimed,
                 Function<ManagedSessionId, Optional<SessionActivity>> activity,
                 Function<ManagedSessionId, Optional<ManagedAgentSession>> row,
                 BiFunction<ManagedSessionId, String, PromptOutcome> submit,
                 BiFunction<ManagedSessionId, Optional<String>, CompletableFuture<ManagedAgentSession>> resume,
                 Consumer<ManagedSessionId> stop,
                 Executor executor,
                 LongSupplier nanoClock) {
        this.initialization = Objects.requireNonNull(initialization, "initialization");
        this.engaged = Objects.requireNonNull(engaged, "engaged");
        this.running = Objects.requireNonNull(running, "running");
        this.startingOrClaimed = Objects.requireNonNull(startingOrClaimed, "startingOrClaimed");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.row = Objects.requireNonNull(row, "row");
        this.submit = Objects.requireNonNull(submit, "submit");
        this.resume = Objects.requireNonNull(resume, "resume");
        this.stop = Objects.requireNonNull(stop, "stop");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * The production waker: the engagement from the plugin's state,
     * liveness, the start mark, the launch claims and {@code submitPrompt}
     * from the application-wide registry, activity from the plugin's
     * monitor, and the resume through the session service {@code
     * sessionsFor} names for the babysitter's pairing -- the project whose
     * window a resumed babysitter's console opens in. One instance must
     * serve every wake: the serialisation and the woken marks are its own.
     */
    public static BabysitWaker of(DrydockPluginService plugin, AgentExecutionRegistry executions,
                                  Function<BabysitPairing, Optional<DrydockSessionService>> sessionsFor) {
        LaunchClaims claims = executions.launchClaims();
        return new BabysitWaker(plugin.initialization(),
                id -> BabysitStateTransforms.activePairingOf(plugin.stateStore().state(), id).isPresent(),
                executions::isRunning,
                id -> executions.isStarting(id) || claims.isClaimed(id),
                id -> plugin.activityMonitor().current(id),
                id -> Optional.ofNullable(DrydockSessionService.findIn(plugin.stateStore().state(), id)),
                executions::submitPrompt,
                (id, prompt) -> BabysitStateTransforms.activePairingOf(plugin.stateStore().state(), id)
                        .flatMap(sessionsFor)
                        .map(sessions -> sessions.resumeSession(id, prompt))
                        .orElseGet(() -> CompletableFuture.failedFuture(
                                new IllegalStateException("No open project can resume the babysitter"))),
                executions::stop,
                plugin.executor(),
                System::nanoTime);
    }

    /**
     * Wakes {@code babysitterId} with {@code pointer}, which must be a
     * single-line pointer ({@link BabysitWakePolicy#isWakePointer}) such as
     * "Read /path/to/delta.md". Never completes exceptionally.
     *
     * @throws IllegalArgumentException synchronously when {@code pointer} is not a wake pointer
     */
    public CompletableFuture<WakeOutcome> wake(ManagedSessionId babysitterId, String pointer) {
        Objects.requireNonNull(babysitterId, "babysitterId");
        if (!BabysitWakePolicy.isWakePointer(pointer)) {
            throw new IllegalArgumentException("A wake prompt must be one non-blank line without control characters");
        }
        return initialization
                .thenComposeAsync(unused -> act(babysitterId, pointer), executor)
                .exceptionally(failure -> {
                    Throwable cause = unwrap(failure);
                    LOG.log(Level.WARNING, () -> "Could not wake babysitter " + babysitterId + ": " + cause);
                    return new WakeOutcome.Failed(Optional.empty(), String.valueOf(cause.getMessage()));
                });
    }

    private CompletableFuture<WakeOutcome> act(ManagedSessionId babysitterId, String pointer) {
        ReentrantLock lock = locks.computeIfAbsent(babysitterId, id -> new ReentrantLock());
        lock.lock();
        try {
            return actLocked(babysitterId, pointer);
        } finally {
            lock.unlock();
        }
    }

    /** Decide and dispatch, under the babysitter's lock; a resume's tail runs after the lock is released. */
    private CompletableFuture<WakeOutcome> actLocked(ManagedSessionId babysitterId, String pointer) {
        if (!engaged.test(babysitterId)) {
            wokenAt.remove(babysitterId);
            return CompletableFuture.completedFuture(new WakeOutcome.Inactive());
        }
        Optional<ManagedAgentSession> session = row.apply(babysitterId);
        if (session.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new WakeOutcome.Failed(Optional.empty(), "The babysitter session " + babysitterId + " is gone"));
        }
        Optional<SessionActivity> current = activity.apply(babysitterId);
        if (current.isPresent() && current.get() != SessionActivity.IDLE) {
            // The agent has reported a turn (or a prompt) since the last wake:
            // its next IDLE is a real one.
            wokenAt.remove(babysitterId);
        }
        Decision decision = BabysitWakePolicy.decide(running.test(babysitterId),
                startingOrClaimed.test(babysitterId), current, session.get().agentSessionId().isPresent());
        if (decision == Decision.WAKE_VIA_SUBMIT && staleIdle(babysitterId)) {
            decision = Decision.SKIP_BUSY;
        }
        return switch (decision) {
            case SKIP_BUSY, SKIP_UNBOUND -> CompletableFuture.completedFuture(new WakeOutcome.Skipped(decision));
            case WAKE_VIA_SUBMIT -> CompletableFuture.completedFuture(submitted(babysitterId,
                    submit.apply(babysitterId, pointer)));
            case WAKE_VIA_RESUME -> {
                // Marked before the resume runs: until it reports a turn, the
                // resumed agent's IDLE is no more trustworthy than a paste's.
                // A resume that did not happen drops the mark again.
                long mark = nanoClock.getAsLong();
                wokenAt.put(babysitterId, mark);
                CompletableFuture<ManagedAgentSession> resumed;
                try {
                    resumed = resume.apply(babysitterId, Optional.of(pointer));
                } catch (RuntimeException e) {
                    resumed = CompletableFuture.failedFuture(e);
                }
                yield resumed
                        .<WakeOutcome>thenApply(resumedRow -> afterResume(babysitterId, mark, resumedRow))
                        .exceptionally(failure -> {
                            wokenAt.remove(babysitterId, mark);
                            return resumeFailure(babysitterId, unwrap(failure));
                        });
            }
        };
    }

    /**
     * A completed resume, its agent registered: still engaged, it was a
     * wake; stopped meanwhile (a Stop that found no agent yet), the agent
     * this resume started is stopped and the wake is {@link WakeOutcome.Inactive}.
     */
    private WakeOutcome afterResume(ManagedSessionId babysitterId, long mark, ManagedAgentSession session) {
        if (engaged.test(babysitterId)) {
            return new WakeOutcome.Resumed(session);
        }
        wokenAt.remove(babysitterId, mark);
        LOG.log(Level.INFO, () -> "Babysitting of " + babysitterId + " stopped while its resume was dispatched;"
                + " the resumed agent is stopped");
        try {
            stop.accept(babysitterId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not stop babysitter " + babysitterId + " resumed after its"
                    + " engagement stopped: " + e);
        }
        return new WakeOutcome.Inactive();
    }

    /**
     * Whether the IDLE the policy just trusted is the stale one of the last
     * wake: the babysitter is still marked woken (no non-IDLE sample since)
     * and the window has not passed. The end of the window clears the mark.
     */
    private boolean staleIdle(ManagedSessionId babysitterId) {
        Long mark = wokenAt.get(babysitterId);
        if (mark == null) {
            return false;
        }
        if (nanoClock.getAsLong() - mark >= WOKEN_STALE_WINDOW.toNanos()) {
            wokenAt.remove(babysitterId, mark);
            return false;
        }
        return true;
    }

    /** A submit's outcome as a wake's: only DELIVERED is {@link WakeOutcome.Submitted}, and only it marks the wake. */
    private WakeOutcome submitted(ManagedSessionId babysitterId, PromptOutcome outcome) {
        return switch (outcome) {
            case DELIVERED -> {
                wokenAt.put(babysitterId, nanoClock.getAsLong());
                yield new WakeOutcome.Submitted();
            }
            // Nothing was written: the agent ended or lost its input since the
            // decision. The next attempt decides afresh (a resume, typically).
            case REFUSED -> new WakeOutcome.Skipped(Decision.SKIP_BUSY);
            case FAILED -> new WakeOutcome.Failed(Optional.of(Decision.WAKE_VIA_SUBMIT),
                    "The pointer could not be written to the babysitter's agent, or it ended right after");
        };
    }

    private static WakeOutcome resumeFailure(ManagedSessionId babysitterId, Throwable cause) {
        if (cause instanceof DrydockSessionService.SessionBusyException) {
            return new WakeOutcome.Skipped(Decision.SKIP_BUSY);
        }
        if (cause instanceof DrydockSessionService.UnboundConversationException) {
            return new WakeOutcome.Skipped(Decision.SKIP_UNBOUND);
        }
        LOG.log(Level.WARNING, () -> "Resuming babysitter " + babysitterId + " failed: " + cause);
        return new WakeOutcome.Failed(Optional.of(Decision.WAKE_VIA_RESUME), String.valueOf(cause.getMessage()));
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException layered && layered.getCause() != null
                ? unwrap(layered.getCause())
                : failure;
    }
}
