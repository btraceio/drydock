package app.drydock.intellij.session;

import app.drydock.app.ApplicationStateStore;
import app.drydock.babysit.BabysitDelta;
import app.drydock.babysit.BabysitEvent;
import app.drydock.babysit.BabysitInstructions;
import app.drydock.babysit.BabysitLocks;
import app.drydock.babysit.BabysitMonitorRules;
import app.drydock.babysit.BabysitMonitorRules.Observation;
import app.drydock.babysit.BabysitMonitorRules.Step;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.BabysitStateTransforms;
import app.drydock.babysit.BabysitWakePolicy;
import app.drydock.babysit.BabysitWriteGuard.Verdict;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.PrMonitorRead;
import app.drydock.git.PrMonitorRead.PrSnapshot;
import app.drydock.git.PrMonitorRead.PrSnapshot.Snapshot;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecution;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.session.BabysitWaker.WakeOutcome;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.SessionPullRequestLookup;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The babysitting monitor: polls the pull request of every active
 * engagement and wakes its babysitter with a delta file when something
 * changed. One per IDE, owned and disposed by {@link DrydockPluginService}.
 *
 * <p><b>Scheduling.</b> One daemon thread ({@code drydock-babysit-monitor})
 * runs one tick at a time; the next tick is scheduled from the END of the
 * previous one, so ticks never overlap however long a read, a fetch or a
 * wake takes. Every tick body is caught whole, so no exception ends the
 * series. The blocking work (gh, git, the resume) runs on the plugin
 * executor through the seams; the tick thread only waits for it, bounded.
 * Ticks only read the state store and submit pure transforms; nothing here
 * runs on the EDT.</p>
 *
 * <p><b>Per engagement, per tick</b> (every pairing that is not {@link
 * BabysitState#STOPPED}):</p>
 * <ul>
 *   <li>A pairing whose babysitter or watched row is missing is skipped --
 *       a start reserves its pairing before the babysitter row exists, so a
 *       missing row mid-run is a start in flight, not a dangling pairing.
 *       Dangling pairings are ended once, by {@link #start()}.</li>
 *   <li>{@link PrSnapshot.Unsupported} (no gh) pauses the engagement: logged
 *       once, nothing else changes. {@link PrSnapshot.Failed} backs it off
 *       (30 s, doubling, 10 min cap; the 60 s tick is the floor) and keeps the
 *       last good snapshot: a failed read is never "no change" and never
 *       "PR gone".</li>
 *   <li>A {@link Snapshot} records its head as the pairing's {@link
 *       BabysitPairing#knownHead()} (a push by anyone), ends the engagement
 *       when the PR is merged or closed, and is otherwise diffed against the
 *       last one by {@link BabysitMonitorRules#observe}. The first snapshot
 *       of an engagement {@link #start()} restored is a silent baseline and
 *       wakes nobody; the first snapshot of one that started later is
 *       diffed against a clean baseline ({@link Observation#clean}), so a PR
 *       already conflicting or red at the start wakes its babysitter at
 *       once. Events wait in an in-memory pending list
 *       ({@link BabysitMonitorRules#coalesce}) until delivered.</li>
 *   <li>Pending events are delivered through the write guard
 *       ({@link DrydockSessionService#babysitterMayWrite}, known base =
 *       the head just read) -- a point-in-time liveness sample plus a
 *       remote-tip comparison that the delta's push lease enforces, not an
 *       exclusion of the watched session's local work or of its next turn.
 *       Wakes the plugin holds back ({@link #holdWakes}) are skipped first:
 *       <ul>
 *         <li>A babysitter whose detached headless run still holds its run
 *             lock ({@link BabysitLocks#runLockFile}) is not woken at all: the
 *             wake is skipped and the events wait.</li>
 *         <li>{@code Allowed}: an ACT delta is written, the pairing is marked
 *             ACTING (only from WATCHING) and the babysitter is woken; when the
 *             wake completes the mark goes back to WATCHING. A delivered wake
 *             clears the pending events; a skipped one keeps them for the next
 *             tick.</li>
 *         <li>{@code WatchedBusy}: nobody is woken; the events stay pending and
 *             the guard is asked again next tick (the babysitter waits, never
 *             races the watched session).</li>
 *         <li>{@code HeadMoved}: a READ-ONLY delta is delivered once per batch of
 *             events, and the events stay pending: the next read records the
 *             new head and the guard can then allow the write.</li>
 *         <li>{@code GitFailed}: logged, backed off, events kept.</li>
 *       </ul></li>
 * </ul>
 *
 * <p><b>Ending an engagement</b> (merged or closed, or dangling at start)
 * sets the pairing STOPPED -- kept for the UI's history, inactive for every
 * gate -- publishes the change, stops the babysitter's agent best-effort (as
 * deleting the watched session does), forgets its in-memory track, and
 * deletes its delta and instructions files best-effort. The babysitter's row
 * is left alone, for the human to remove like any session.</p>
 *
 * <p><b>Ownership.</b> The production {@link PrMonitorRead} rides the plugin
 * executor it is given, so {@link #dispose()} closes nothing the plugin
 * still needs; dispose must run before the plugin's review backend closes and
 * its executor shuts down. A wake still in flight at dispose may complete
 * and revert its ACTING mark after the plugin's final state flush; the mark
 * then persists and {@link #start()} reverts it on the next run.</p>
 */
public final class BabysitMonitorService implements Disposable {

    private static final Logger LOG = System.getLogger(BabysitMonitorService.class.getName());

    static final long PERIOD_MS = 60_000;
    static final long FIRST_TICK_MS = 15_000;

    /** The waits a tick puts on the executor's work: a gh read is bounded to 15 s, a fetch to 2 min. */
    static final Duration READ_WAIT = Duration.ofSeconds(45);
    static final Duration GUARD_WAIT = Duration.ofMinutes(3);
    static final Duration WAKE_WAIT = Duration.ofMinutes(2);

    /** One gh read of a pull request. */
    @FunctionalInterface
    interface PrReader {
        CompletableFuture<PrSnapshot> read(Path root, Optional<String> repository, int prNumber);
    }

    /** The write guard for {@code pairing}; never fails. */
    @FunctionalInterface
    interface WriteGuard {
        CompletableFuture<Verdict> check(BabysitPairing pairing, String prBranch, String knownBase);
    }

    /** Wakes {@code pairing}'s babysitter with a one-line pointer; never fails. */
    @FunctionalInterface
    interface Waker {
        CompletableFuture<WakeOutcome> wake(BabysitPairing pairing, String pointer);
    }

    /** What the monitor remembers of one engagement between ticks; confined to the tick thread. */
    private static final class Track {
        Observation observation;
        List<BabysitEvent> pending = List.of();
        boolean readOnlySent;
        boolean paused;
        boolean instructionsEnsured;
        /** Restored at {@link #start()}: its first read is a silent baseline, not diffed against a clean one. */
        boolean silentBaseline;
        /** The failed-read streak's delay; a good read resets it. */
        long readBackoffMs;
        /** The failed-delivery (write check, wake) streak's delay; only a delivered wake resets it. */
        long deliveryBackoffMs;
        long retryAfterMs;
    }

    private final Supplier<ApplicationStateStore> stateStore;
    private final Path baseDir;
    private final PrReader reader;
    private final Function<Path, Optional<String>> repositoryOf;
    private final WriteGuard guard;
    private final Waker waker;
    private final Runnable publish;
    private final Consumer<ManagedSessionId> stopAgent;
    private final LongSupplier clock;
    private final long firstTickMs;
    private final long periodMs;
    private final long backoffInitialMs;
    private final AutoCloseable closeOnDispose;
    private final ScheduledExecutorService scheduler;
    private final Map<ManagedSessionId, Track> tracks = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopped;
    /** Babysitters whose wakes are held back ({@link #holdWakes}); none by default. */
    private volatile Predicate<ManagedSessionId> wakesHeld = id -> false;

    /**
     * The full seam; {@code closeOnDispose} is closed by {@link #dispose()};
     * {@code stopAgent} stops a babysitter's agent wherever it runs (a no-op
     * when none does) and must not block.
     */
    BabysitMonitorService(Supplier<ApplicationStateStore> stateStore, Path baseDir, PrReader reader,
                          Function<Path, Optional<String>> repositoryOf, WriteGuard guard, Waker waker,
                          Runnable publish, Consumer<ManagedSessionId> stopAgent, LongSupplier clock,
                          long firstTickMs, long periodMs, long backoffInitialMs, AutoCloseable closeOnDispose) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.repositoryOf = Objects.requireNonNull(repositoryOf, "repositoryOf");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.waker = Objects.requireNonNull(waker, "waker");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.stopAgent = Objects.requireNonNull(stopAgent, "stopAgent");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.firstTickMs = firstTickMs;
        this.periodMs = periodMs;
        this.backoffInitialMs = backoffInitialMs;
        this.closeOnDispose = closeOnDispose;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "drydock-babysit-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The production monitor: gh reads through a {@link PrMonitorRead} on
     * {@code executor} (injected, so never shut down here), the GitHub
     * repository setting of the project registered for the repository's
     * root, and the write guard and the wake through the session service of
     * the project hosting the babysitter, else the watched session, else any
     * open project. One {@link BabysitWaker} serves every wake (its
     * per-babysitter serialisation and woken marks are per instance), made
     * on the first wake; nothing IDE-bound is touched until a tick needs it.
     * An ended engagement's babysitter is stopped through the
     * application-wide execution registry, wherever it is shown.
     */
    public static BabysitMonitorService create(DrydockPluginService plugin, Path baseDir, ExecutorService executor,
                                               Supplier<List<SharedProject>> projects) {
        PrMonitorRead read = new PrMonitorRead(executor);
        AtomicReference<BabysitWaker> sharedWaker = new AtomicReference<>();
        return new BabysitMonitorService(plugin::stateStore, baseDir, read::read,
                root -> SessionPullRequestLookup.repositoryOf(root, projects.get()),
                (pairing, branch, base) -> sessionsFor(pairing)
                        .map(sessions -> sessions.babysitterMayWrite(pairing, branch, base))
                        .orElseGet(() -> CompletableFuture.completedFuture(new Verdict.GitFailed(
                                "No open project can check the pull request's branch"))),
                (pairing, pointer) -> sharedWaker.updateAndGet(existing -> existing != null ? existing
                                : BabysitWaker.of(plugin, AgentExecutionRegistry.getInstance(),
                                        BabysitMonitorService::sessionsFor))
                        .wake(pairing.babysitterId(), pointer),
                DrydockSessionsListener::publishChanged, id -> AgentExecutionRegistry.getInstance().stop(id),
                System::currentTimeMillis,
                FIRST_TICK_MS, PERIOD_MS, BabysitMonitorRules.BACKOFF_INITIAL_MS, read);
    }

    private static Optional<DrydockSessionService> sessionsFor(BabysitPairing pairing) {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        Optional<Project> host = hostOf(registry, pairing.babysitterId())
                .or(() -> hostOf(registry, pairing.watchedId()))
                .or(() -> Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                        .filter(project -> !project.isDisposed() && !project.isDefault())
                        .findFirst());
        return host.map(DrydockStartupActivity::sessions);
    }

    private static Optional<Project> hostOf(AgentExecutionRegistry registry, ManagedSessionId id) {
        return registry.execution(id).flatMap(AgentExecution::host).filter(project -> !project.isDisposed());
    }

    /**
     * Restores the engagements -- synchronously, on the caller's thread,
     * before any start can reserve a pairing: a pairing whose babysitter or
     * watched row is missing (an IDE that died mid-start) is ended, an
     * ACTING mark left by an interrupted wake is reverted to WATCHING, and
     * every engagement kept is marked for a silent first baseline -- then
     * schedules the first tick. Idempotent; does nothing after {@link #dispose()}.
     */
    public void start() {
        if (stopped || !started.compareAndSet(false, true)) {
            return;
        }
        try {
            restore();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Restoring the babysitting engagements failed; monitoring starts anyway", e);
        }
        scheduleNext(firstTickMs);
    }

    /**
     * Holds back the wakes of every babysitter {@code held} answers true
     * for, until the next call: such a wake is skipped like a busy one and
     * its events wait. The detach/re-attach handshake's fail-closed gate --
     * while a re-attach has not confirmed that a detached watcher can no
     * longer run a babysitter's conversation, the IDE must not run it too.
     * Any thread.
     */
    public void holdWakes(Predicate<ManagedSessionId> held) {
        wakesHeld = Objects.requireNonNull(held, "held");
    }

    @Override
    public void dispose() {
        stopped = true;
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.log(Level.WARNING, "The babysitting monitor's tick did not stop within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closeOnDispose != null) {
            try {
                closeOnDispose.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Closing the babysitting monitor's reader failed", e);
            }
        }
    }

    private void scheduleNext(long delayMs) {
        if (stopped) {
            return;
        }
        try {
            scheduler.schedule(this::loop, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.log(Level.DEBUG, "The babysitting monitor is shut down; no further ticks");
        }
    }

    private void loop() {
        try {
            tick();
        } catch (RuntimeException | Error e) {
            LOG.log(Level.WARNING, "A babysitting monitor tick failed; the next one runs on schedule", e);
        } finally {
            scheduleNext(periodMs);
        }
    }

    void restore() {
        ApplicationStateStore store = stateStore.get();
        ApplicationState state = store.state();
        for (BabysitPairing pairing : state.pairings()) {
            if (pairing.state() == BabysitState.STOPPED) {
                continue;
            }
            if (!hasRow(state, pairing.babysitterId()) || !hasRow(state, pairing.watchedId())) {
                end(store, pairing, "a session of the engagement no longer exists");
                continue;
            }
            if (pairing.state() == BabysitState.ACTING) {
                setStateIf(store, pairing, BabysitState.ACTING, BabysitState.WATCHING);
            }
            // What a restored engagement's PR shows now predates this run:
            // its first read is a silent baseline.
            tracks.computeIfAbsent(pairing.babysitterId(), id -> new Track()).silentBaseline = true;
        }
    }

    /** One pass over the active engagements. Package-private: tests drive ticks directly. */
    void tick() {
        if (stopped) {
            return;
        }
        ApplicationStateStore store = stateStore.get();
        ApplicationState state = store.state();
        Set<ManagedSessionId> active = new HashSet<>();
        for (BabysitPairing pairing : state.pairings()) {
            if (pairing.state() != BabysitState.STOPPED) {
                active.add(pairing.babysitterId());
            }
        }
        tracks.keySet().retainAll(active);
        for (BabysitPairing pairing : state.pairings()) {
            if (stopped || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (pairing.state() == BabysitState.STOPPED) {
                continue;
            }
            try {
                tickOne(store, state, pairing);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Monitoring PR #" + pairing.prNumber() + " of " + pairing.watchedId()
                        + " failed this tick", e);
            }
        }
    }

    private void tickOne(ApplicationStateStore store, ApplicationState state, BabysitPairing pairing) {
        if (!hasRow(state, pairing.babysitterId()) || !hasRow(state, pairing.watchedId())) {
            LOG.log(Level.DEBUG, () -> "Babysitter " + pairing.babysitterId() + " has no row yet; skipped");
            return;
        }
        Track track = tracks.computeIfAbsent(pairing.babysitterId(), id -> new Track());
        if (clock.getAsLong() < track.retryAfterMs) {
            return;
        }
        Optional<Path> root = state.repositories().stream()
                .filter(repository -> repository.id().equals(pairing.repositoryId()))
                .map(Repository::root)
                .findFirst();
        if (root.isEmpty()) {
            LOG.log(Level.WARNING, "The repository of babysat PR #" + pairing.prNumber() + " is no longer known");
            backOffRead(track);
            return;
        }
        Optional<String> repository;
        try {
            repository = repositoryOf.apply(root.get());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read the GitHub repository setting of " + root.get(), e);
            repository = Optional.empty();
        }
        PrSnapshot outcome = await(reader.read(root.get(), repository, pairing.prNumber()), READ_WAIT)
                .orElseGet(() -> new PrSnapshot.Failed("The pull request read did not complete"));
        switch (outcome) {
            case PrSnapshot.Unsupported unsupported -> {
                if (!track.paused) {
                    LOG.log(Level.INFO, "gh is unavailable: babysitting PR #" + pairing.prNumber()
                            + " is paused until it is");
                    track.paused = true;
                }
            }
            case PrSnapshot.Failed failed -> {
                track.paused = false;
                backOffRead(track);
                LOG.log(Level.DEBUG, () -> "Reading babysat PR #" + pairing.prNumber() + " failed ("
                        + failed.message() + "); retry in " + track.readBackoffMs + " ms");
            }
            case Snapshot snapshot -> {
                track.paused = false;
                track.readBackoffMs = 0;
                track.retryAfterMs = 0;
                observed(store, pairing, track, snapshot);
            }
        }
    }

    private void observed(ApplicationStateStore store, BabysitPairing pairing, Track track, Snapshot snapshot) {
        if (BabysitMonitorRules.ended(snapshot)) {
            end(store, pairing, "PR #" + pairing.prNumber() + " is " + snapshot.lifecycle());
            return;
        }
        if (!track.instructionsEnsured) {
            ensureInstructions(pairing, snapshot.headRefName());
            track.instructionsEnsured = true;
        }
        if (!pairing.knownHead().equals(Optional.of(snapshot.headRefOid()))) {
            store.update(state -> BabysitStateTransforms.withPairingHead(state, pairing.babysitterId(),
                    snapshot.headRefOid()));
        }
        BabysitPairing current = pairing.withKnownHead(snapshot.headRefOid());
        if (track.observation == null && track.silentBaseline) {
            track.observation = Observation.first(snapshot);
            return;
        }
        Observation previous = track.observation != null ? track.observation : Observation.clean(snapshot);
        Step step = BabysitMonitorRules.observe(previous, snapshot);
        track.observation = step.observation();
        if (!step.events().isEmpty()) {
            track.pending = BabysitMonitorRules.coalesce(track.pending, step.events());
            track.readOnlySent = false;
        }
        if (!track.pending.isEmpty()) {
            deliver(store, current, track, snapshot);
        }
    }

    private void deliver(ApplicationStateStore store, BabysitPairing pairing, Track track, Snapshot snapshot) {
        Verdict verdict = await(guard.check(pairing, snapshot.headRefName(), snapshot.headRefOid()), GUARD_WAIT)
                .orElseGet(() -> new Verdict.GitFailed("The write check did not complete"));
        switch (verdict) {
            case Verdict.WatchedBusy busy -> LOG.log(Level.DEBUG, () -> "The session watched for PR #"
                    + pairing.prNumber() + " is busy; " + track.pending.size() + " event(s) wait");
            case Verdict.GitFailed failed -> {
                LOG.log(Level.WARNING, "The write check of babysat PR #" + pairing.prNumber() + " failed: "
                        + failed.message());
                backOffDelivery(track);
            }
            case Verdict.HeadMoved moved -> {
                if (!track.readOnlySent) {
                    Optional<WakeOutcome> outcome = wakeWith(store, pairing, track, snapshot, verdict);
                    if (delivered(outcome)) {
                        track.readOnlySent = true;
                        track.deliveryBackoffMs = 0;
                    } else if (failed(outcome)) {
                        backOffDelivery(track);
                    }
                }
            }
            case Verdict.Allowed allowed -> {
                Optional<WakeOutcome> outcome = wakeWith(store, pairing, track, snapshot, verdict);
                if (delivered(outcome)) {
                    track.pending = List.of();
                    track.readOnlySent = false;
                    track.deliveryBackoffMs = 0;
                } else if (failed(outcome)) {
                    backOffDelivery(track);
                }
            }
        }
    }

    /** Writes the delta for {@code verdict} and wakes the babysitter with it; empty when no wake was attempted or it did not finish. */
    private Optional<WakeOutcome> wakeWith(ApplicationStateStore store, BabysitPairing pairing, Track track,
                                           Snapshot snapshot, Verdict verdict) {
        if (wakesHeld.test(pairing.babysitterId())) {
            // A re-attach has not confirmed that the detached watcher can no
            // longer run this conversation: fail closed, the events wait.
            LOG.log(Level.DEBUG, () -> "Babysitter " + pairing.babysitterId() + " may still belong to a detached"
                    + " watcher; not woken");
            return Optional.of(new WakeOutcome.Skipped(BabysitWakePolicy.Decision.SKIP_BUSY));
        }
        if (BabysitLocks.isHeld(BabysitLocks.runLockFile(baseDir, pairing.babysitterId()))) {
            // A detached watcher's headless run of this very conversation is
            // still in flight (the IDE restarted under it): never run it twice.
            LOG.log(Level.INFO, "Babysitter " + pairing.babysitterId() + " is still running headlessly in the"
                    + " detached watcher; not woken");
            return Optional.of(new WakeOutcome.Skipped(BabysitWakePolicy.Decision.SKIP_BUSY));
        }
        Path instructions = BabysitInstructions.fileFor(baseDir, pairing.watchedId(), pairing.prNumber());
        Instant at = Instant.ofEpochMilli(clock.getAsLong());
        Path delta;
        try {
            delta = BabysitDelta.write(baseDir, pairing.watchedId(), pairing.prNumber(), at,
                    BabysitDelta.render(pairing, snapshot, track.pending, verdict, instructions, at));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the babysitting delta for PR #" + pairing.prNumber(), e);
            return Optional.empty();
        }
        try {
            BabysitDelta.prune(baseDir, pairing.watchedId(), pairing.prNumber(), BabysitDelta.KEEP);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not prune old babysitting deltas: " + e);
        }
        String pointer = BabysitDelta.pointer(pairing.prNumber(), delta);
        if (!BabysitWakePolicy.isWakePointer(pointer)) {
            LOG.log(Level.WARNING, "The delta path " + delta + " cannot be a one-line wake prompt");
            return Optional.empty();
        }
        boolean marked = BabysitDelta.grantsWrite(verdict)
                && setStateIf(store, pairing, BabysitState.WATCHING, BabysitState.ACTING);
        CompletableFuture<WakeOutcome> wake;
        try {
            wake = waker.wake(pairing, pointer);
        } catch (RuntimeException e) {
            wake = CompletableFuture.completedFuture(new WakeOutcome.Failed(Optional.empty(), String.valueOf(e)));
        }
        if (marked) {
            // After the wake, whatever it did and however long after this tick
            // stopped waiting: the mark spans the delivery, not the agent's turn.
            wake.whenComplete((outcome, failure) -> setStateIf(store, pairing, BabysitState.ACTING,
                    BabysitState.WATCHING));
        }
        Optional<WakeOutcome> outcome = await(wake, WAKE_WAIT);
        outcome.ifPresent(result -> LOG.log(Level.DEBUG, () -> "Woke babysitter " + pairing.babysitterId()
                + " for " + track.pending + ": " + result));
        return outcome;
    }

    private static boolean failed(Optional<WakeOutcome> outcome) {
        return outcome.isPresent() && outcome.get() instanceof WakeOutcome.Failed;
    }

    private static boolean delivered(Optional<WakeOutcome> outcome) {
        return outcome.map(result -> switch (result) {
            case WakeOutcome.Resumed resumed -> true;
            case WakeOutcome.Submitted submitted -> true;
            case WakeOutcome.Skipped skipped -> false;
            case WakeOutcome.Inactive inactive -> false;
            case WakeOutcome.Failed failed -> false;
        }).orElse(false);
    }

    private void ensureInstructions(BabysitPairing pairing, String prBranch) {
        Path file = BabysitInstructions.fileFor(baseDir, pairing.watchedId(), pairing.prNumber());
        try {
            BabysitInstructions.writeIfAbsent(file, BabysitInstructions.baseline(pairing.prNumber(), prBranch));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the babysitter instructions " + file, e);
        }
    }

    private void end(ApplicationStateStore store, BabysitPairing pairing, String why) {
        AtomicBoolean changed = new AtomicBoolean();
        ApplicationState after = store.update(state -> {
            ApplicationState updated = BabysitStateTransforms.withPairingState(state, pairing.babysitterId(),
                    BabysitState.STOPPED);
            changed.set(updated != state);
            return updated;
        });
        if (changed.get()) {
            publish.run();
            // Nothing is left to watch: stop the babysitter's agent, as
            // deleting the watched session does. Its row stays.
            try {
                stopAgent.accept(pairing.babysitterId());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not stop babysitter " + pairing.babysitterId() + " of the ended"
                        + " engagement for PR #" + pairing.prNumber() + ": " + e);
            }
        }
        tracks.remove(pairing.babysitterId());
        LOG.log(Level.INFO, "Babysitting PR #" + pairing.prNumber() + " of " + pairing.watchedId() + " ended: " + why);
        boolean filesShared = after.pairings().stream().anyMatch(other -> other.state() != BabysitState.STOPPED
                && other.watchedId().equals(pairing.watchedId()) && other.prNumber() == pairing.prNumber());
        if (!filesShared) {
            try {
                BabysitDelta.deleteAll(baseDir, pairing.watchedId(), pairing.prNumber());
            } catch (IOException e) {
                LOG.log(Level.DEBUG, () -> "Could not clean up the babysitting files of PR #" + pairing.prNumber()
                        + ": " + e);
            }
        }
    }

    /** Compare-and-set of the pairing's state; publishes and answers true when it changed. */
    private boolean setStateIf(ApplicationStateStore store, BabysitPairing pairing, BabysitState expected,
                               BabysitState next) {
        AtomicBoolean changed = new AtomicBoolean();
        store.update(state -> {
            ApplicationState updated = BabysitStateTransforms.withPairingStateIf(state, pairing.babysitterId(),
                    expected, next);
            changed.set(updated != state);
            return updated;
        });
        if (changed.get()) {
            publish.run();
        }
        return changed.get();
    }

    private void backOffRead(Track track) {
        track.readBackoffMs = BabysitMonitorRules.nextBackoffDelayMs(track.readBackoffMs, backoffInitialMs);
        track.retryAfterMs = clock.getAsLong() + track.readBackoffMs;
    }

    /** One more failed delivery: its own streak backs off exponentially, as failed reads do. */
    private void backOffDelivery(Track track) {
        track.deliveryBackoffMs = BabysitMonitorRules.nextBackoffDelayMs(track.deliveryBackoffMs, backoffInitialMs);
        track.retryAfterMs = clock.getAsLong() + track.deliveryBackoffMs;
    }

    private static boolean hasRow(ApplicationState state, ManagedSessionId id) {
        return DrydockSessionService.findIn(state, id) != null;
    }

    /** {@code future}'s value within {@code wait}; empty on timeout or failure, and on interrupt (flag restored). */
    private static <T> Optional<T> await(CompletableFuture<T> future, Duration wait) {
        try {
            return Optional.ofNullable(future.get(wait.toNanos(), TimeUnit.NANOSECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.DEBUG, () -> "A babysitting step did not complete: " + e);
            return Optional.empty();
        }
    }
}
