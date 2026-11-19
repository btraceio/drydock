package app.drydock.babysit;

import app.drydock.babysit.BabysitMonitorRules.Observation;
import app.drydock.babysit.BabysitMonitorRules.Step;
import app.drydock.babysit.BabysitStateFile.Contents;
import app.drydock.babysit.BabysitStateFile.DetachedEngagement;
import app.drydock.babysit.BabysitStateFile.RerunCharge;
import app.drydock.babysit.BabysitWriteGuard.Verdict;
import app.drydock.git.GhCiRerunService;
import app.drydock.git.GhCiRerunService.RunRead;
import app.drydock.git.GitStatusService.RemoteBranchTip;
import app.drydock.git.PrMonitorRead.PrSnapshot;
import app.drydock.git.PrMonitorRead.PrSnapshot.Snapshot;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The detached babysitting watcher's loop: while the IDE is closed, it polls
 * the pull request of every engagement the plugin handed over ({@link
 * BabysitStateFile.Detachment}) and resumes the babysitter's conversation
 * headlessly with a delta when something changed. Run by {@link
 * BabysitWatcherMain} in its own process; every collaborator that spawns
 * anything is a seam, so the decisions are tested without gh, git or an
 * agent.
 *
 * <p><b>The same monitor core as in the IDE.</b> {@link BabysitMonitorRules}
 * diffs reads and coalesces events, {@link BabysitWriteGuard} decides
 * whether a delta grants a write, {@link BabysitDelta} renders and writes it
 * into the same directory. Differences: the first read of every engagement
 * is a silent baseline (the IDE's in-memory pending events do not cross the
 * handover); no managed agent runs while the IDE is closed, so the guard's
 * liveness half is moot and only the remote tip is checked; the delta names
 * the drop box ({@link BabysitDropBox}) instead of the MCP tools.</p>
 *
 * <p><b>The wake is a headless run.</b> Only for a bound conversation (an
 * unbound one is skipped and logged: an unbound resume is a picker); only
 * under the babysitter's run lock ({@link BabysitLocks#runLockFile}), which
 * the IDE checks before it resumes the same babysitter; and only while this
 * watcher's generation is still the state file's (re-checked under the
 * lock, so a re-attach that cleared it never races a fresh run). A run that
 * exits 0 or times out delivered its delta; a failed one keeps the events
 * and backs off. After every run the drop box is drained: escalations are
 * queued in the state file for the plugin to ingest, and rerun requests are
 * MEDIATED here -- the run must have run on the PR's head commit AND for
 * its head branch (a same-commit run of another branch is refused), {@link
 * BabysitStateFile#chargeRerun} enforces the cap of {@link
 * BabysitStateFile#MAX_RERUNS_PER_RUN} across both processes, and a request
 * at the cap becomes an escalation. Their results ride the next delta.</p>
 *
 * <p><b>Ending.</b> A merged or closed PR ends the engagement: the end is
 * recorded in the state file (the watcher never writes {@code state.json};
 * the plugin stops the pairing at re-attach). {@link #tick()} answers false
 * once this watcher's generation is gone (the plugin re-attached, or a newer
 * detach replaced it) or nothing is left to watch. Confined to one thread;
 * every method blocks.</p>
 */
public final class BabysitWatcher {

    private static final Logger LOG = System.getLogger(BabysitWatcher.class.getName());

    /** The poll cadence, as in the IDE. */
    public static final long PERIOD_MS = 60_000;

    /** The first poll waits for the IDE's agents to be gone. */
    public static final long FIRST_TICK_MS = 15_000;

    /** Escalations one engagement may raise through its drop box per watcher life (the MCP budget's size). */
    static final int MAX_ESCALATIONS = 40;

    /** Headless run logs kept per engagement. */
    static final int KEEP_RUN_LOGS = 5;

    /** One gh read of a pull request; blocking and bounded, never throws. */
    @FunctionalInterface
    public interface PrReader {
        PrSnapshot read(Path root, Optional<String> repository, int prNumber);
    }

    /** The freshly fetched remote tip of {@code branch}; blocking and bounded. */
    @FunctionalInterface
    public interface TipReader {
        RemoteBranchTip tip(Path root, String branch);
    }

    /** What one headless run came to. */
    public sealed interface RunOutcome {
        /** The agent ran and exited with {@code exitCode}. */
        record Completed(int exitCode) implements RunOutcome {
        }

        /** The run hit its timeout and was killed; it may have acted. */
        record TimedOut() implements RunOutcome {
        }

        /** The run could not start or broke off. */
        record Failed(String message) implements RunOutcome {
        }

        /** The provider has no headless write command (or no executable). */
        record Unsupported(String message) implements RunOutcome {
        }
    }

    /** Resumes {@code engagement}'s conversation headlessly with {@code prompt}, writing its output to {@code log}. */
    @FunctionalInterface
    public interface HeadlessRunner {
        RunOutcome run(DetachedEngagement engagement, String prompt, Path log);
    }

    /** gh's run read and the rerun POST; blocking and bounded, never throw. */
    public interface Reruns {
        RunRead readRun(Path root, Optional<String> repository, long runId);

        GhCiRerunService.Outcome rerun(Path root, Optional<String> repository, long runId);
    }

    private enum Delivery { DELIVERED, SKIPPED, FAILED, STOPPED }

    /** What the watcher remembers of one engagement between ticks. */
    private static final class Track {
        Observation observation;
        List<BabysitEvent> pending = List.of();
        List<String> results = new ArrayList<>();
        boolean readOnlySent;
        boolean paused;
        boolean unboundLogged;
        boolean instructionsEnsured;
        int escalations;
        /** The failed-read streak's delay; a good read resets it. */
        long readBackoffMs;
        /** The failed-delivery (write check, headless run) streak's delay; only a delivery resets it. */
        long deliveryBackoffMs;
        long retryAfterMs;
    }

    private final BabysitStateFile stateFile;
    private final Path baseDir;
    private final UUID generation;
    private final PrReader reader;
    private final TipReader tips;
    private final HeadlessRunner runner;
    private final Reruns reruns;
    private final LongSupplier clock;
    private final long backoffInitialMs;
    private final Map<String, Track> tracks = new HashMap<>();
    /** The engagements this watcher has carried, by babysitter id: drained at exit even after a re-attach. */
    private final Map<String, DetachedEngagement> carried = new HashMap<>();

    public BabysitWatcher(BabysitStateFile stateFile, Path baseDir, UUID generation, PrReader reader, TipReader tips,
                          HeadlessRunner runner, Reruns reruns, LongSupplier clock, long backoffInitialMs) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.tips = Objects.requireNonNull(tips, "tips");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.reruns = Objects.requireNonNull(reruns, "reruns");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.backoffInitialMs = backoffInitialMs;
    }

    /**
     * Whether this watcher must stop: the state file's detachment is no
     * longer its generation (cleared, replaced, or revoked). For the idle
     * wait: a file that cannot be read is no stop there -- but it never
     * permits a run ({@link #mayRun}).
     */
    public boolean stopRequested() {
        BabysitStateFile.Snapshot snapshot = stateFile.read();
        return snapshot.problem().isEmpty() && !snapshot.contents().current(generation);
    }

    /**
     * The check under the run lock: a run may start only when the state
     * file reads cleanly and this watcher's generation is current in it. An
     * unreadable file is a stop here, never a permission: a re-attach whose
     * revocation this watcher cannot read must not race a fresh run.
     */
    boolean mayRun() {
        BabysitStateFile.Snapshot snapshot = stateFile.read();
        return snapshot.problem().isEmpty() && snapshot.contents().current(generation);
    }

    /** One pass over the handed-over engagements; false when the watcher should exit. */
    public boolean tick() {
        BabysitStateFile.Snapshot snapshot = stateFile.read();
        if (snapshot.problem().isPresent()) {
            LOG.log(Level.WARNING, "The babysitting state file cannot be read (" + snapshot.problem().get()
                    + "); trying again next poll");
            return true;
        }
        Contents contents = snapshot.contents();
        if (!contents.current(generation)) {
            LOG.log(Level.INFO, "Detachment " + generation + " is no longer current: the watcher stops");
            return false;
        }
        boolean active = false;
        for (DetachedEngagement engagement : contents.detachment().orElseThrow().engagements()) {
            if (engagement.ended().isPresent()) {
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            try {
                active |= tickOne(engagement);
            } catch (RuntimeException e) {
                active = true;
                LOG.log(Level.WARNING, "Watching PR #" + engagement.prNumber() + " of " + engagement.watchedId()
                        + " failed this poll", e);
            }
        }
        if (!active) {
            LOG.log(Level.INFO, "No babysitting engagement is left to watch: the watcher stops");
        }
        return active;
    }

    /** Drains every engagement's drop box once more: called as the watcher exits, so no request is left behind. */
    public void drainAll() {
        for (DetachedEngagement engagement : carried.values()) {
            drain(engagement, tracks.computeIfAbsent(engagement.babysitterId().toString(), id -> new Track()));
        }
    }

    /** Answers whether the engagement is still active. */
    private boolean tickOne(DetachedEngagement engagement) {
        carried.put(engagement.babysitterId().toString(), engagement);
        Track track = tracks.computeIfAbsent(engagement.babysitterId().toString(), id -> new Track());
        if (clock.getAsLong() < track.retryAfterMs) {
            return true;
        }
        PrSnapshot outcome = reader.read(engagement.repositoryRoot(), engagement.repository(), engagement.prNumber());
        switch (outcome) {
            case PrSnapshot.Unsupported unsupported -> {
                if (!track.paused) {
                    LOG.log(Level.INFO, "gh is unavailable: babysitting PR #" + engagement.prNumber()
                            + " is paused until it is");
                    track.paused = true;
                }
                return true;
            }
            case PrSnapshot.Failed failed -> {
                track.paused = false;
                track.readBackoffMs = BabysitMonitorRules.nextBackoffDelayMs(track.readBackoffMs, backoffInitialMs);
                track.retryAfterMs = clock.getAsLong() + track.readBackoffMs;
                LOG.log(Level.INFO, "Reading babysat PR #" + engagement.prNumber() + " failed (" + failed.message()
                        + "); retry in " + track.readBackoffMs + " ms");
                return true;
            }
            case Snapshot read -> {
                track.paused = false;
                track.readBackoffMs = 0;
                track.retryAfterMs = 0;
                return observed(engagement, track, read);
            }
        }
    }

    private boolean observed(DetachedEngagement engagement, Track track, Snapshot snapshot) {
        if (BabysitMonitorRules.ended(snapshot)) {
            String why = "PR #" + engagement.prNumber() + " is " + snapshot.lifecycle();
            try {
                stateFile.update(contents -> contents.withEngagementEnded(generation, engagement.babysitterId(), why));
            } catch (BabysitStateFileException e) {
                LOG.log(Level.WARNING, "Could not record that babysitting " + why + "; the IDE finds out itself", e);
            }
            LOG.log(Level.INFO, "Babysitting PR #" + engagement.prNumber() + " of " + engagement.watchedId()
                    + " ended: " + why);
            return false;
        }
        if (track.observation == null) {
            // Silent baseline: whatever the PR shows now was there at the handover.
            track.observation = Observation.first(snapshot);
            return true;
        }
        Step step = BabysitMonitorRules.observe(track.observation, snapshot);
        track.observation = step.observation();
        if (!step.events().isEmpty()) {
            track.pending = BabysitMonitorRules.coalesce(track.pending, step.events());
            track.readOnlySent = false;
        }
        if (!track.pending.isEmpty()) {
            deliver(engagement, track, snapshot);
        }
        return true;
    }

    private void deliver(DetachedEngagement engagement, Track track, Snapshot snapshot) {
        if (engagement.agentSessionId().isEmpty()) {
            if (!track.unboundLogged) {
                LOG.log(Level.WARNING, "Babysitter " + engagement.babysitterId() + " has no bound conversation;"
                        + " it is never resumed headlessly (a resume without one is a picker). "
                        + track.pending.size() + " event(s) wait for the IDE");
                track.unboundLogged = true;
            }
            return;
        }
        Verdict verdict;
        try {
            verdict = BabysitWriteGuard.verdictFor(tips.tip(engagement.repositoryRoot(), snapshot.headRefName()),
                    snapshot.headRefOid());
        } catch (RuntimeException e) {
            verdict = new Verdict.GitFailed(String.valueOf(e.getMessage()));
        }
        switch (verdict) {
            case Verdict.GitFailed failed -> {
                LOG.log(Level.WARNING, "The write check of babysat PR #" + engagement.prNumber() + " failed: "
                        + failed.message());
                backOffDelivery(track);
            }
            case Verdict.WatchedBusy busy -> LOG.log(Level.DEBUG, "Unexpected WatchedBusy while detached");
            case Verdict.HeadMoved moved -> {
                if (!track.readOnlySent) {
                    Delivery delivery = run(engagement, track, snapshot, verdict);
                    if (delivery == Delivery.DELIVERED) {
                        track.readOnlySent = true;
                        track.deliveryBackoffMs = 0;
                    } else if (delivery == Delivery.FAILED) {
                        backOffDelivery(track);
                    }
                }
            }
            case Verdict.Allowed allowed -> {
                Delivery delivery = run(engagement, track, snapshot, verdict);
                if (delivery == Delivery.DELIVERED) {
                    track.pending = List.of();
                    track.readOnlySent = false;
                    track.deliveryBackoffMs = 0;
                } else if (delivery == Delivery.FAILED) {
                    backOffDelivery(track);
                }
            }
        }
    }

    /** One headless run with a delta for {@code verdict}, then the drop box. */
    private Delivery run(DetachedEngagement engagement, Track track, Snapshot snapshot, Verdict verdict) {
        Path runLock = BabysitLocks.runLockFile(baseDir, engagement.babysitterId());
        Optional<BabysitLocks.Held> lock;
        try {
            lock = BabysitLocks.tryLock(runLock);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not take the run lock " + runLock, e);
            return Delivery.FAILED;
        }
        if (lock.isEmpty()) {
            LOG.log(Level.INFO, "Babysitter " + engagement.babysitterId() + " is running elsewhere; not resumed");
            return Delivery.SKIPPED;
        }
        RunOutcome outcome;
        try (BabysitLocks.Held held = lock.get()) {
            // Under the lock: a re-attach that revoked the generation before
            // this point is seen here, so no fresh run races the IDE's resume;
            // a file that cannot be read stops the run too (fail closed).
            if (!mayRun()) {
                return Delivery.STOPPED;
            }
            Optional<String> prompt = writeDelta(engagement, track, snapshot, verdict);
            if (prompt.isEmpty()) {
                return Delivery.FAILED;
            }
            Path log = runLogFile(engagement, Instant.ofEpochMilli(clock.getAsLong()));
            LOG.log(Level.INFO, "Resuming babysitter " + engagement.babysitterId() + " headlessly for PR #"
                    + engagement.prNumber() + " (" + track.pending + "); output in " + log);
            try {
                outcome = runner.run(engagement, prompt.get(), log);
            } catch (RuntimeException e) {
                outcome = new RunOutcome.Failed(String.valueOf(e));
            }
            pruneRunLogs(engagement);
        }
        drain(engagement, track);
        return switch (outcome) {
            case RunOutcome.Completed completed when completed.exitCode() == 0 -> Delivery.DELIVERED;
            case RunOutcome.Completed completed -> {
                LOG.log(Level.WARNING, "The headless run of babysitter " + engagement.babysitterId() + " exited "
                        + completed.exitCode());
                yield Delivery.FAILED;
            }
            case RunOutcome.TimedOut timedOut -> {
                // It read the delta and may have acted: sending it again would repeat the work.
                LOG.log(Level.WARNING, "The headless run of babysitter " + engagement.babysitterId()
                        + " timed out and was killed; its delta counts as delivered");
                yield Delivery.DELIVERED;
            }
            case RunOutcome.Failed failed -> {
                LOG.log(Level.WARNING, "The headless run of babysitter " + engagement.babysitterId() + " failed: "
                        + failed.message());
                yield Delivery.FAILED;
            }
            case RunOutcome.Unsupported unsupported -> {
                LOG.log(Level.WARNING, "Babysitter " + engagement.babysitterId() + " cannot run headlessly: "
                        + unsupported.message());
                yield Delivery.FAILED;
            }
        };
    }

    /** Writes the delta and answers the one-line prompt pointing at it; empty when it could not be written. */
    private Optional<String> writeDelta(DetachedEngagement engagement, Track track, Snapshot snapshot,
                                        Verdict verdict) {
        Path instructions = BabysitInstructions.fileFor(baseDir, engagement.watchedId(), engagement.prNumber());
        if (!track.instructionsEnsured) {
            try {
                BabysitInstructions.writeIfAbsent(instructions,
                        BabysitInstructions.baseline(engagement.prNumber(), snapshot.headRefName()));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not write the babysitter instructions " + instructions, e);
            }
            track.instructionsEnsured = true;
        }
        Path box = BabysitDropBox.directory(baseDir, engagement.babysitterId());
        Instant at = Instant.ofEpochMilli(clock.getAsLong());
        Path delta;
        try {
            Files.createDirectories(box);
            delta = BabysitDelta.write(baseDir, engagement.watchedId(), engagement.prNumber(), at,
                    BabysitDelta.render(engagement.pairing(), snapshot, track.pending, verdict, instructions, at,
                            Optional.of(new BabysitDelta.Detached(box, track.results))));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write the babysitting delta for PR #" + engagement.prNumber(), e);
            return Optional.empty();
        }
        track.results = new ArrayList<>();
        try {
            BabysitDelta.prune(baseDir, engagement.watchedId(), engagement.prNumber(), BabysitDelta.KEEP);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not prune old babysitting deltas: " + e);
        }
        String pointer = BabysitDelta.pointer(engagement.prNumber(), delta);
        if (!BabysitWakePolicy.isWakePointer(pointer)) {
            LOG.log(Level.WARNING, "The delta path " + delta + " cannot be a one-line prompt");
            return Optional.empty();
        }
        return Optional.of(pointer);
    }

    /**
     * Queues the drop box's escalations and mediates its rerun requests;
     * each request file is deleted only once its effect is on disk (an
     * escalation queued, a rerun's charge and outcome settled), so a crash
     * reads it again under the same id rather than losing it.
     */
    private void drain(DetachedEngagement engagement, Track track) {
        BabysitDropBox.Drained drained;
        try {
            drained = BabysitDropBox.read(BabysitDropBox.directory(baseDir, engagement.babysitterId()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read the drop box of babysitter " + engagement.babysitterId(), e);
            return;
        }
        for (BabysitDropBox.Request request : drained.requests()) {
            boolean settled = true;
            if (request.escalation().isPresent()) {
                settled = escalate(engagement, track, request.id(), request.escalation().get());
            } else if (request.rerun().isPresent()) {
                String result = mediate(engagement, track, request.rerun().get());
                LOG.log(Level.INFO, "Babysitter " + engagement.babysitterId() + ": " + result);
                track.results.add(result);
            }
            if (settled) {
                try {
                    BabysitDropBox.delete(request);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not delete the drop-box request " + request.file(), e);
                }
            }
        }
    }

    /** Queues one escalation under {@code id}; false when the queue could not be written (the request stays). */
    private boolean escalate(DetachedEngagement engagement, Track track, UUID id, String text) {
        if (track.escalations >= MAX_ESCALATIONS) {
            LOG.log(Level.WARNING, "Babysitter " + engagement.babysitterId() + " reached " + MAX_ESCALATIONS
                    + " escalations; this one is dropped: " + BabysitDelta.plain(text));
            return true;
        }
        Escalation escalation = new Escalation(id, engagement.watchedId(), engagement.babysitterId(),
                engagement.prNumber(), text, Instant.ofEpochMilli(clock.getAsLong()), false);
        try {
            stateFile.update(contents -> contents.escalationQueue().stream().anyMatch(queued -> queued.id().equals(id))
                    ? contents : contents.withEscalationQueued(escalation));
            track.escalations++;
            return true;
        } catch (BabysitStateFileException e) {
            LOG.log(Level.WARNING, "Could not queue an escalation of babysitter " + engagement.babysitterId()
                    + "; its request is kept for the next drain: " + BabysitDelta.plain(text), e);
            return false;
        }
    }

    /**
     * One rerun request: the run must have run on the PR's head as last
     * read, the cap is charged before the POST (and refunded only when the
     * POST definitely did not reach GitHub), and a request at the cap is
     * escalated. Answers the one-line result the next delta reports.
     */
    private String mediate(DetachedEngagement engagement, Track track, BabysitDropBox.RerunRequest request) {
        long runId = request.runId();
        String label = "Rerun of run " + runId + request.checkName().map(name -> " (" + name + ")").orElse("");
        if (track.observation == null) {
            return label + " refused: Drydock has not read the pull request yet. Nothing was rerun or counted.";
        }
        String head = track.observation.snapshot().headRefOid();
        RunRead read = reruns.readRun(engagement.repositoryRoot(), engagement.repository(), runId);
        switch (read) {
            case GhCiRerunService.RunUnreadable unreadable -> {
                return label + " refused: the run could not be read (" + unreadable.message()
                        + "). Nothing was rerun or counted.";
            }
            case GhCiRerunService.RunHead runHead -> {
                if (!runHead.headSha().equalsIgnoreCase(head)) {
                    return label + " refused: the run ran on commit " + runHead.headSha() + ", but PR #"
                            + engagement.prNumber() + "'s head is " + head + ". Nothing was rerun or counted.";
                }
                String branch = track.observation.snapshot().headRefName();
                if (!runHead.headBranch().equals(branch)) {
                    return label + " refused: the run is for branch '" + runHead.headBranch() + "', but PR #"
                            + engagement.prNumber() + "'s head branch is '" + branch
                            + "'. Nothing was rerun or counted.";
                }
            }
        }
        RerunCharge charge;
        try {
            charge = stateFile.chargeRerun(runId, BabysitStateFile.MAX_RERUNS_PER_RUN);
        } catch (BabysitStateFileException e) {
            return label + " refused: the rerun count cannot be updated (" + e.getMessage() + "). Nothing was rerun.";
        }
        if (charge instanceof RerunCharge.AtCap atCap) {
            escalate(engagement, track, UUID.randomUUID(), "CI run " + runId + request.checkName().map(n -> " (" + n + ")").orElse("")
                    + " on PR #" + engagement.prNumber() + " still fails after " + atCap.count()
                    + " reruns, and the babysitter asked for another while the IDE was closed. Drydock did not"
                    + " rerun it: read the run's log (`gh run view " + runId + " --log-failed`) and decide whether"
                    + " the failure is real.");
            return label + " refused: the run already had " + atCap.count() + " reruns, the cap. Drydock escalated"
                    + " it to the watched session for you; do not ask again.";
        }
        int count = ((RerunCharge.Charged) charge).count();
        GhCiRerunService.Outcome outcome = reruns.rerun(engagement.repositoryRoot(), engagement.repository(), runId);
        return switch (outcome) {
            case GhCiRerunService.Rerun rerun -> label + ": its failed jobs were rerun (" + count + " of "
                    + BabysitStateFile.MAX_RERUNS_PER_RUN + ").";
            case GhCiRerunService.Unknown unknown -> label + ": whether GitHub reran it is unknown ("
                    + unknown.message() + "); it still counts (" + count + " of "
                    + BabysitStateFile.MAX_RERUNS_PER_RUN + ").";
            case GhCiRerunService.Rejected rejected -> label + " failed: " + rejected.message() + ". "
                    + refunded(runId, count);
            case GhCiRerunService.Unavailable unavailable -> label + " failed: " + unavailable.message() + ". "
                    + refunded(runId, count);
        };
    }

    /** Refunds the charge of a rerun that did not reach GitHub, and says honestly whether it did. */
    private String refunded(long runId, int count) {
        try {
            stateFile.refundRerun(runId);
            return "Not counted.";
        } catch (BabysitStateFileException e) {
            LOG.log(Level.WARNING, "Could not refund the rerun of run " + runId + "; it stays counted", e);
            return "The refund could not be recorded, so it still counts (" + count + " of "
                    + BabysitStateFile.MAX_RERUNS_PER_RUN + ").";
        }
    }

    /** {@code <watchedId>-pr<n>-run-<epochMillis, 13 digits>.log}, beside the deltas. */
    Path runLogFile(DetachedEngagement engagement, Instant at) {
        return BabysitDelta.directory(baseDir).resolve(runLogPrefix(engagement)
                + String.format(Locale.ROOT, "%013d", at.toEpochMilli()) + ".log");
    }

    private static String runLogPrefix(DetachedEngagement engagement) {
        return engagement.watchedId() + "-pr" + engagement.prNumber() + "-run-";
    }

    private void pruneRunLogs(DetachedEngagement engagement) {
        Path dir = BabysitDelta.directory(baseDir);
        String prefix = runLogPrefix(engagement);
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, prefix + "*.log")) {
            entries.forEach(logs::add);
            logs.sort(null);
            for (Path old : logs.subList(0, Math.max(0, logs.size() - KEEP_RUN_LOGS))) {
                Files.deleteIfExists(old);
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not prune old headless run logs: " + e);
        }
    }

    /** One more failed delivery: its own streak backs off exponentially, as failed reads do. */
    private void backOffDelivery(Track track) {
        track.deliveryBackoffMs = BabysitMonitorRules.nextBackoffDelayMs(track.deliveryBackoffMs, backoffInitialMs);
        track.retryAfterMs = clock.getAsLong() + track.deliveryBackoffMs;
    }
}
