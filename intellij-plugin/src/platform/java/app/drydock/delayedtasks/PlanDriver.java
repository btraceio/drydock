package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.PlanLaunch;
import app.drydock.tasks.PlanLaunch.Origin;
import app.drydock.tasks.PlanLaunch.Selection;
import app.drydock.tasks.PlanLaunch.StackedUnit;
import app.drydock.tasks.PlanProgress;
import app.drydock.tasks.PlanProgress.Effect;
import app.drydock.tasks.PlanProgress.LaunchReported;
import app.drydock.tasks.PlanProgress.LaunchStage;
import app.drydock.tasks.PlanStore;
import app.drydock.tasks.PlanStore.Claim;
import app.drydock.tasks.PlanStore.PlanRecord;
import app.drydock.tasks.RunningPlan;
import app.drydock.tasks.TaskId;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The launch effect driver of ONE confirmed staged plan (T35a, D8/D12/D16,
 * P4.4-9): it executes the effects {@link PlanProgress} (T34a) RETURNS,
 * serialized on the injected service executor -- never the EDT, never a
 * second plan. Fixed order: claim (T33a; re-acquisition durably pauses a
 * RUNNING plan, so a launch needs the explicit Continue that writes paused
 * back), then mint one launch id per segment and PERSIST the intent into
 * the plan's launch receipts -- the save IS the launch permission, a failed
 * save launches NOTHING and a duplicate effect a logged no-op -- then
 * launch the stage's segments in PARALLEL, one per repository share (D7),
 * through the injected SegmentLauncher seam (production re-prepares the
 * CURRENT inputs off the EDT per launch; the request carries STORED VALUES
 * only, so a stale inputs closure cannot travel through the driver).
 * Completions are generation-checked (T32a/P4.12): stale is a LOGGED NO-OP, never an
 * advance. A failed segment fails its stage: the FAILED report is refused visibly,
 * the driver composes the halt (only Continue resumes) and reports the started
 * siblings, NEVER killed (T34a); every non-launch effect surfaces, never swallowed.
 */
public final class PlanDriver implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PlanDriver.class.getName());
    /** One bounded wait for a store answer; the store's own internal waits are shorter. */
    private static final int STORE_SECONDS = 10;

    /** The launch-id mint: one monotonic id per segment, persisted as the launch intent. */
    private static final AtomicLong MINT = new AtomicLong();

    /** The segment launch seam (production composes TaskLauncher): prepare
      * re-reads the CURRENT inputs OFF the EDT -- a FRESH token every call,
      * never a closure captured at plan start -- and launch starts the segment
      * from exactly that token; a refused preparation answers empty. */
    public interface SegmentLauncher {

        /** Fresh inputs for one segment; empty when the preparation refused. */
        CompletableFuture<Optional<Prepared>> prepare(SegmentRequest request);

        /** Launches one segment from its OWN fresh preparation. */
        CompletableFuture<Launched> launch(Prepared prepared, SegmentRequest request);
    }

    /** One segment's fresh preparation; opaque on purpose, it only travels back to launch. */
    public interface Prepared { }

    /** One launch request: STORED VALUES only -- no live project, inputs or window.
      * {@code baseBranch} and {@code waitBranches} carry the stacked-branch shape
      * (S1/S2 of docs/StackedPrExecution.md): a unit with a base branch starts its
      * first task's branch there once every wait branch exists, a unit without
      * starts from the confirmed start point immediately. */
    public record SegmentRequest(int stage, int stageCount, List<TaskId> tasks, AgentKind agent,
            RepositoryId repository, String projectName, String originWindow, Path originWorktree,
            int cancellationGeneration, String baseBranch, List<String> waitBranches) {
        public SegmentRequest {
            if (stage < 1) throw new IllegalArgumentException("Stage numbers start at 1, got: " + stage);
            if (stageCount < stage) throw new IllegalArgumentException("The stage count covers the stage, got: "
                    + stageCount + " < " + stage);
            Objects.requireNonNull(tasks, "tasks"); Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(repository, "repository"); Objects.requireNonNull(originWindow,
                    "originWindow"); Objects.requireNonNull(originWorktree, "originWorktree");
            if (projectName == null || projectName.isBlank()) throw new IllegalArgumentException(
                    "The segment's project name is required");
            if (cancellationGeneration < 0) throw new IllegalArgumentException(
                    "Cancellation generations are never negative");
            Objects.requireNonNull(waitBranches, "waitBranches");
            if (baseBranch == null != waitBranches.isEmpty()) throw new IllegalArgumentException(
                    "A unit stacks on a branch exactly when it waits for one");
            tasks = List.copyOf(tasks);
            waitBranches = List.copyOf(waitBranches);
        }

        /** The 8-arg form of a request with NO stacked shape: the legacy
          * single-session-per-segment launch, a root unit of the whole share. */
        public SegmentRequest(int stage, List<TaskId> tasks, AgentKind agent, RepositoryId repository,
                String projectName, String originWindow, Path originWorktree, int cancellationGeneration) {
            this(stage, Math.max(stage, 1), tasks, agent, repository, projectName, originWindow,
                    originWorktree, cancellationGeneration, null, List.of());
        }
    }

    /** One launched segment: its launch id and the running session's id. */
    public record Launched(String launchId, String sessionId) {
        public Launched {
            Objects.requireNonNull(launchId, "launchId"); Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    /** One already-started sibling of a failed stage: running, and never killed (P4.12). */
    public record Started(List<TaskId> tasks, String launchId, String sessionId) {
        public Started {
            Objects.requireNonNull(tasks, "tasks"); Objects.requireNonNull(launchId, "launchId");
            Objects.requireNonNull(sessionId, "sessionId"); tasks = List.copyOf(tasks);
        }
    }

    /** The visible surface: every non-launch outcome is reported, never swallowed. */
    public interface Listener {

        /** One non-launch reducer effect (or a driver refusal): visible. */
        void surfaced(Effect effect);

        /** The launch failure halt: why no further stage starts, and the started siblings. */
        void halted(String reason, List<Started> started);
    }

    private final Executor service;
    private final PlanStore store;
    private final SegmentLauncher launcher;
    private final Listener listener;
    private final Map<Integer, Flight> flights = new ConcurrentHashMap<>();
    private CompletableFuture<Void> lane = CompletableFuture.completedFuture(null);
    private TaskId planId;
    private Claim claim;
    private PlanProgress.State state;
    private RunningPlan persisted;
    private long revision;
    private boolean paused;
    private boolean closed;

    /** One driver over one store; {@link #start} binds it to one plan and its claim. */
    public PlanDriver(Executor service, PlanStore store, SegmentLauncher launcher, Listener listener) {
        this.service = Objects.requireNonNull(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Binds this driver to ONE plan: takes its claim and loads its current record. */
    public synchronized CompletableFuture<PlanRecord> start(TaskId plan) {
        Objects.requireNonNull(plan, "plan");
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("The driver is closed"));
        if (this.planId != null) return CompletableFuture.failedFuture(new IllegalStateException(
                "One driver drives one plan: '" + this.planId.text() + "'"));
        this.planId = plan;
        return serialize(() -> {
            this.claim = join(store.acquireClaim(plan));
            PlanRecord record = join(store.load(plan)).orElseThrow(() -> refusal("Plan '" + plan.text()
                    + "' has no persisted plan; a confirmed start persists it first (T38)"));
            revision = record.revision(); paused = record.paused(); persisted = record.plan();
            state = PlanProgress.State.of(record.plan());
            return record;
        });
    }

    /** Feeds one event; the reducer and its effects run serialized on the service executor. */
    public CompletableFuture<Void> accept(PlanProgress.Event event) {
        Objects.requireNonNull(event, "event");
        return serialize(() -> { step(event); return null; });
    }

    /** Stops driving; LAUNCHED SESSIONS ARE NEVER CANCELED (P4.12); never throws. */
    @Override
    public synchronized void close() {
        closed = true; flights.clear();
        Claim held = claim; claim = null; if (held != null) held.close();
    }

    /** One step of the single lane; a failure fails that step's future only. */
    private synchronized <T> CompletableFuture<T> serialize(Supplier<T> step) {
        CompletableFuture<T> done = new CompletableFuture<>();
        lane = lane.thenComposeAsync(previous -> {
            try {
                done.complete(step.get());
            } catch (RuntimeException | Error failure) {
                done.completeExceptionally(failure);
            }
            return CompletableFuture.completedFuture(null);
        }, service);
        return done;
    }

    /** One event, on the lane: reduce, surface, persist, launch. */
    private void step(PlanProgress.Event event) {
        if (closed) throw refusal("The plan driver is closed");
        if (state == null) throw refusal("The driver has not started its plan yet; await start first");
        PlanProgress.Result result = PlanProgress.apply(state, event);
        state = result.state();
        LaunchStage launch = null;
        for (Effect effect : result.effects()) {
            if (effect instanceof LaunchStage stage) launch = stage;
            else listener.surfaced(effect);
        }
        boolean resume = event instanceof PlanProgress.ContinuePlan;
        try {
            if (launch != null) execute(launch, resume);
            else if (resume || !state.plan().equals(persisted))
                persisted = persist(state.plan(), paused && !resume);
        } catch (RuntimeException failure) {
            listener.surfaced(new PlanProgress.Refused(reasonOf(failure)));
        }
    }

    /** Reserve, persist the per-segment intent (the launch permission), then launch in parallel. */
    private void execute(LaunchStage launch, boolean resume) {
        if (paused && !resume) throw refusal("the plan is durably paused (its claim was re-taken, P4.13);"
                + " only an explicit Continue event resumes it");
        if (claim == null || !claim.valid()) throw refusal("the driver claim for plan '" + planId.text()
                + "' is not held (P4.13); no launch until re-acquired and explicitly continued");
        if (flights.containsKey(launch.stage())) {
            LOG.log(Level.WARNING, () -> "A duplicate launch of stage " + launch.stage()
                    + " is a logged no-op; the stage never starts twice");
            return;
        }
        List<Segment> segments = segments(launch);
        RunningPlan next = withLaunchIds(state.plan(), segments);
        persisted = persist(next, false); // a failed save throws: NO launch (P4.13)
        state = new PlanProgress.State(next, state.statuses(), state.halted(), state.launchAttempts());
        Flight flight = new Flight(launch.stage(), launch.cancellationGeneration(), launch.tasks(), segments);
        flights.put(flight.stage, flight);
        for (Segment segment : segments) dispatch(flight, segment);
    }

    /** One segment's launch, dispatched without blocking the lane; completions settle back on it. */
    private void dispatch(Flight flight, Segment segment) {
        try {
            Origin origin = state.plan().launch().origin();
            SegmentRequest request = new SegmentRequest(flight.stage, state.plan().stages().size(),
                    segment.tasks, segment.agent, segment.repository, segment.projectName, origin.windowId(),
                    origin.worktree(), flight.generation, segment.baseBranch(), segment.waitBranches());
            launcher.prepare(request).thenCompose(token -> token.isEmpty()
                            ? CompletableFuture.<Launched>failedFuture(refusal("the fresh preparation of"
                                    + " the segment of " + segment.projectName + " refused"))
                            : launcher.launch(token.get(), request))
                    .whenComplete((launched, failure) -> serialize(
                            () -> { settled(flight, segment, launched, failure); return null; }));
        } catch (RuntimeException dispatchFailure) {
            serialize(() -> { settled(flight, segment, null, dispatchFailure); return null; });
        }
    }

    /** One segment outcome, on the lane; the last one settles the stage. */
    private void settled(Flight flight, Segment segment, Launched launched, Throwable failure) {
        if (closed || flight.outcomes.containsKey(segment)) return;
        flight.outcomes.put(segment, new Outcome(launched, failure));
        if (flight.outcomes.size() < flight.segments.size()) return;
        flights.remove(flight.stage);
        if (state.plan().cancellationGeneration() != flight.generation) {
            LOG.log(Level.WARNING, () -> "A stale completion of stage " + flight.stage + " (generation "
                    + flight.generation + ", the plan is at " + state.plan().cancellationGeneration()
                    + ") is a logged no-op; it never advances (P4.12)");
            return;
        }
        List<Started> started = new ArrayList<>(); List<String> failures = new ArrayList<>();
        for (Segment one : flight.segments) {
            Outcome outcome = flight.outcomes.get(one);
            if (outcome.launched() != null) started.add(new Started(one.tasks, one.launchId,
                    outcome.launched().sessionId()));
            else failures.add(one.projectName + ": " + reasonOf(outcome.failure()));
        }
        String reason = failures.isEmpty() ? null : "the launch of stage " + flight.stage + " failed for "
                + failures.size() + " of " + flight.segments.size() + " segments: " + String.join("; ",
                failures) + "; no further stage starts (the started siblings keep running)";
        step(new LaunchReported(flight.stage, flight.tasks, List.of(), failures.isEmpty()
                ? LaunchReported.Outcome.COVERED : LaunchReported.Outcome.FAILED, reason,
                state.plan().launch().graphRevision(), flight.generation));
        if (reason == null) return;
        state = new PlanProgress.State(state.plan(), state.statuses(), true, state.launchAttempts());
        listener.halted(reason, started); // the started siblings keep running, never killed
    }

    /** The stage's launches: one per STACKED LAUNCH UNIT (S1 of
      * docs/StackedPrExecution.md) -- the persisted stack shape splits each
      * repository share into maximal linear chains, so a fork runs as its
      * own session behind its prerequisite's branch. A share with no
      * persisted stack (the legacy shape) launches as one unit, exactly the
      * pre-stacked one-session-per-segment behavior. First-seen order, with
      * minted ids. */
    private List<Segment> segments(LaunchStage launch) {
        PlanLaunch plan = state.plan().launch();
        Map<RepositoryId, List<TaskId>> grouped = new LinkedHashMap<>();
        for (TaskId task : launch.tasks()) {
            grouped.computeIfAbsent(plan.selections().get(task).repository(),
                    repository -> new ArrayList<>()).add(task);
        }
        List<Segment> segments = new ArrayList<>();
        for (Map.Entry<RepositoryId, List<TaskId>> share : grouped.entrySet()) {
            Selection selection = plan.selections().get(share.getValue().get(0));
            List<StackedUnit> staged = stageUnits(plan.stacks().get(share.getKey()),
                    share.getValue(), launch.stage(), share.getKey());
            List<StackedUnit> units = staged.isEmpty()
                    ? List.of(new StackedUnit(share.getValue(), null, List.of())) // the legacy shape
                    : staged;
            for (StackedUnit unit : units) {
                segments.add(new Segment(share.getKey(), selection.projectName(), selection.agent(),
                        unit.tasks(), unit.baseBranch(), unit.waitBranches(),
                        "stage-" + launch.stage() + "-" + MINT.incrementAndGet()));
            }
        }
        return segments;
    }

    /** The persisted units of ONE stage's share: units fully inside the
      * share belong to this stage (in persisted order), units fully outside
      * belong to another stage and are skipped, a root unit partially
      * overlapping is the legacy synthesized shape (one root unit per
      * repository across all stages) and is sliced order-preservingly to
      * the share, and any other partial overlap is a corrupt persisted
      * shape, refused loudly. After selection, the union of the selected
      * units' tasks must equal the share -- a share task in no selected
      * unit means a persisted stack map omitting a task of this stage and
      * is refused, never silently dropped. An empty result means no
      * persisted unit touches the share (the legacy empty shape); the
      * caller falls back to the whole share as one root unit. */
    static List<StackedUnit> stageUnits(List<StackedUnit> persisted, List<TaskId> share,
            int stage, RepositoryId repository) {
        if (persisted == null || persisted.isEmpty()) {
            return List.of();
        }
        Set<TaskId> inShare = new LinkedHashSet<>(share);
        List<StackedUnit> mine = new ArrayList<>();
        List<TaskId> selected = new ArrayList<>();
        for (StackedUnit unit : persisted) {
            boolean any = false;
            boolean all = true;
            for (TaskId task : unit.tasks()) {
                any |= inShare.contains(task);
                all &= inShare.contains(task);
            }
            if (all) {
                mine.add(unit);
                selected.addAll(unit.tasks());
                continue;
            }
            if (!any) {
                continue; // another stage's unit
            }
            if (unit.isRoot()) { // the legacy synthesized cross-stage root unit: slice to the share
                List<TaskId> sliced = unit.tasks().stream().filter(inShare::contains).toList();
                mine.add(new StackedUnit(sliced, null, List.of()));
                selected.addAll(sliced);
                continue;
            }
            throw new IllegalArgumentException("The persisted stack of '" + repository
                    + "' carries a unit that is neither inside nor outside stage " + stage
                    + "'s share: " + unit.tasks() + "; the plan's stack shape is corrupt");
        }
        List<TaskId> missing = share.stream().filter(task -> !selected.contains(task)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The persisted stack of '" + repository
                    + "' omits stage " + stage + "'s share tasks " + missing
                    + "; the plan's stack shape is corrupt");
        }
        return mine;
    }

    /** The plan with the stage's minted launch ids -- the persisted launch intent. */
    static RunningPlan withLaunchIds(RunningPlan plan, List<Segment> segments) {
        PlanLaunch launch = plan.launch();
        List<String> ids = new ArrayList<>(launch.launchIds());
        for (Segment segment : segments) ids.add(segment.launchId());
        return new RunningPlan(new PlanLaunch(launch.target(), launch.confirmedStages(),
                launch.selections(), launch.graphRevision(), ids, launch.cancellationGeneration(),
                launch.origin(), launch.stacks()), plan.stages(), plan.status());
    }

    /** The save itself (P4.13): the CAS revision advances on success; a failure throws visibly. */
    private RunningPlan persist(RunningPlan next, boolean pausedFlag) {
        PlanRecord saved = join(store.save(claim, revision, next, pausedFlag));
        revision = saved.revision(); paused = pausedFlag; return saved.plan();
    }

    /** One bounded store wait; interruption, failure and timeout are visible refusals. */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.get(STORE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw refusal("interrupted while the plan store answered");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw refusal(cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause));
        } catch (TimeoutException e) {
            throw refusal("the plan store did not answer within " + STORE_SECONDS + "s");
        }
    }

    /** One bounded failure reason. */
    private static String reasonOf(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message != null && !message.isBlank() ? message : String.valueOf(failure);
    }

    private static IllegalStateException refusal(String message) { return new IllegalStateException(message); }

    /** One stage's in-flight launches: generation, tasks, segments and their settled outcomes. */
    private static final class Flight {
        final int stage;
        final int generation;
        final List<TaskId> tasks;
        final List<Segment> segments;
        final Map<Segment, Outcome> outcomes = new ConcurrentHashMap<>();

        Flight(int stage, int generation, List<TaskId> tasks, List<Segment> segments) {
            this.stage = stage; this.generation = generation; this.tasks = List.copyOf(tasks);
            this.segments = List.copyOf(segments);
        }
    }

    /** One repository share's launch unit: its confirmed selection, its
      * stacked shape (S1) and minted launch id. */
    private record Segment(RepositoryId repository, String projectName, AgentKind agent,
            List<TaskId> tasks, String baseBranch, List<String> waitBranches, String launchId) { }

    /** One segment's settled outcome: the running session, or the failure. */
    private record Outcome(Launched launched, Throwable failure) { }
}
