package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.PlanLaunch.Selection;
import app.drydock.tasks.PlanStore;
import app.drydock.tasks.PlanStore.Claim;
import app.drydock.tasks.PlanStore.PlanRecord;
import app.drydock.tasks.RunningPlan;
import app.drydock.tasks.RunningPlan.StageState;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskRun;
import app.drydock.tasks.TaskRun.Phase;
import app.drydock.tasks.TaskRuns;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskStatus;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The restart recovery of one store's persisted plans (T36a, P4/P9/P4.10).
 * {@link #scan} is read-only: it loads every record of the reserved
 * {@code plans/} family, reconciles the persisted launch intent with the
 * catalog and the {@link TaskRuns} receipts, and NEVER launches anything
 * (P4.10: a restart is a human checkpoint; recovery only PUBLISHES the
 * Continue each plan requires). {@link #continuePlan} is that checkpoint:
 * claim (a foreign or second driver refuses visibly, P4.13), revalidate
 * graph/choices, reconcile -- a stage whose receipts prove every segment
 * went out is durably marked launched, its LIVE sessions REUSED,
 * never re-spawned; ambiguous evidence (a live PREPARE receipt whose session
 * is gone and whose work is not done) stays HALTED with its remediation,
 * never auto-retried. No launcher lives here, so nothing here can start a
 * session; driving is the caller's (the service).
 */
public final class PlanRecovery {

    /** One bounded wait for a store answer (the store's own waits are shorter). */
    private static final int STORE_SECONDS = 10;

    public enum Verdict { COMPLETED, CONTINUE, HALTED } // what one persisted plan needs

    /** One plan's published requirement: identity, verdict and text. */
    public record Recovered(TaskId plan, long revision, boolean paused, Verdict verdict,
            String requirement) { }

    public record Report(List<Recovered> plans, List<String> unreadable) { } // scan's answer

    /** The explicit Continue's answer (T36a, P4.10). */
    public sealed interface Outcome permits Refused, Halted, Proceed { }

    /** A visible refusal: claim taken (a foreign or second driver, P4.13), or no plan. */
    public record Refused(String reason) implements Outcome { }

    /** The plan stays halted with its remediation; it is never retried automatically (P4). */
    public record Halted(String reason) implements Outcome { }

    /** Reconciled, so the caller may drive it: every stage's tasks (statuses to feed), the
      * plan's graph revision, and the LIVE sessions already running the current stage. */
    public record Proceed(TaskId plan, List<TaskId> tasks, long graphRevision,
            List<String> reusedSessions) implements Outcome { }

    /** One stage's reconciliation: halt non-null = stuck; covered = whole-stage evidence;
      * live = the sessions still running (the reuse proof). */
    private record Assessment(String halt, boolean covered, List<String> live) { }

    private static final class Halt extends RuntimeException { // unreadable receipt (T27)

        Halt(String message) { super(message); }
    }

    /** The read-only restart scan (T36a): every persisted plan's requirement, ZERO launches. */
    public static Report scan(Path storeRoot, TaskSnapshot catalog, Set<String> liveSessions) throws IOException {
        Objects.requireNonNull(storeRoot, "storeRoot"); Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(liveSessions, "liveSessions");
        List<Recovered> recovered = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<TaskId> ids = new ArrayList<>();
        Path plans = storeRoot.toAbsolutePath().normalize().resolve("plans");
        if (Files.isDirectory(plans)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(plans, "*.json")) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    try { ids.add(TaskId.of(name.substring(0, name.length() - ".json".length()))); }
                    catch (IllegalArgumentException e) { unreadable.add(name + " is not a plan id: "
                            + e.getMessage()); }
                }
            }
            ids.sort(Comparator.comparing(TaskId::text));
        }
        try (PlanStore store = new PlanStore(storeRoot); TaskRuns runs = new TaskRuns(storeRoot)) {
            for (TaskId plan : ids) {
                PlanRecord record; // a foreign schema is an operator decision, never reread (T33a)
                try { record = answer(store.load(plan)).orElse(null); } catch (RuntimeException e) {
                    unreadable.add("plan '" + plan.text() + "' cannot be read: " + reason(e)); continue; }
                if (record == null) continue;
                RunningPlan running = record.plan();
                if (running.stages().stream().allMatch(StageState::finished)) { recovered.add(new
                        Recovered(plan, record.revision(), record.paused(), Verdict.COMPLETED,
                        "the plan is completed; it needs no Continue")); continue; }
                StageState current = running.stages().stream().filter(s -> !s.finished()).findFirst().orElseThrow();
                Assessment assessed; // an unreadable receipt halts the published requirement (T27)
                String requirement = "the restart paused the plan at stage " + current.stage()
                        + "; an explicit Continue resumes it -- nothing launched (P4.10)";
                try { assessed = assess(current, runs, catalog, liveSessions); }
                catch (Halt halt) { assessed = stuck(halt.getMessage()); }
                if (assessed.halt() != null) requirement = assessed.halt();
                else if (assessed.covered()) requirement = "the restart interrupted the launch of"
                        + " stage " + current.stage() + "; the sessions " + assessed.live() + " still"
                        + " run, so an explicit Continue reuses them, never re-spawning (P9)";
                recovered.add(new Recovered(plan, record.revision(), record.paused(), assessed.halt()
                        == null ? Verdict.CONTINUE : Verdict.HALTED, requirement));
            }
        }
        return new Report(List.copyOf(recovered), List.copyOf(unreadable));
    }

    /** The explicit Continue (T36a, P4.10): claim, revalidate, reconcile -- never a launch. */
    public static Outcome continuePlan(Path storeRoot, PlanStore store, TaskId plan, TaskSnapshot catalog,
            Set<String> liveSessions, Set<RepositoryId> registered) {
        Objects.requireNonNull(storeRoot, "storeRoot"); Objects.requireNonNull(store, "store");
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(liveSessions, "liveSessions"); Objects.requireNonNull(registered, "registered");
        Claim claim; // a foreign or second driver (P4.13): a visible refusal, never a silent drive
        try { claim = answer(store.acquireClaim(plan)); } catch (RuntimeException e) {
            return new Refused(reason(e)); }
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            try {
                PlanRecord record;
                try { record = answer(store.load(plan)).orElse(null); } catch (RuntimeException e) {
                    return new Refused(reason(e)); }
                if (record == null) return new Refused("plan '" + plan.text() + "' has no persisted"
                        + " plan; a confirmed start persists it first (T38)");
                RunningPlan running = record.plan();
                if (running.stages().stream().allMatch(StageState::finished)) return new Refused(
                        "nothing to resume: every stage of '" + plan.text() + "' is finished");
                String invalid = invalidGraph(running, catalog, registered); // graph/choices
                if (invalid != null) return new Halted(invalid); // revalidated; a mismatch halts
                StageState current = running.stages().stream().filter(s -> !s.finished()).findFirst().orElseThrow();
                Assessment assessed = assess(current, runs, catalog, liveSessions);
                if (assessed.halt() != null) return new Halted(assessed.halt());
                if (assessed.covered()) {
                    try { // the durable reconciliation (P9): the receipts prove the stage went
                        List<StageState> confirmed = new ArrayList<>(); // out, so its launch is
                        for (StageState one : running.stages()) { // confirmed, never a re-spawn
                            confirmed.add(one.stage() == current.stage() ? new StageState(one.stage(),
                                    one.tasks(), true, one.finished()) : one);
                        }
                        running = answer(store.save(claim, record.revision(), new RunningPlan(
                                running.launch(), confirmed, running.status()), record.paused())).plan();
                    } catch (RuntimeException e) {
                        return new Halted("the reconciled plan of '" + plan.text() + "' could not be"
                                + " persisted (" + reason(e) + "); a failed save is no permission"
                                + " (P4.13): nothing drives it until a Continue saves");
                    }
                }
                List<TaskId> tasks = new ArrayList<>();
                for (StageState stage : running.stages()) tasks.addAll(stage.tasks());
                return new Proceed(plan, List.copyOf(tasks), running.launch().graphRevision(), assessed
                        .covered() ? assessed.live() : List.of());
            } finally {
                claim.close();
            }
        } catch (Halt halt) { return new Halted(halt.getMessage()); }
        catch (RuntimeException e) { // the receipt registry itself could not be opened
            return new Refused("the run receipts on " + storeRoot + " cannot be read: " + reason(e));
        }
    }

    /** One stage's evidence: the live reservations its tasks hold, and the sessions running. */
    private static Assessment assess(StageState current, TaskRuns runs, TaskSnapshot catalog,
            Set<String> liveSessions) {
        List<String> live = new ArrayList<>();
        int held = 0;
        for (TaskId task : current.tasks()) {
            TaskRun receipt; // an unreadable receipt (T27) halts until released and quarantined
            try { receipt = runs.byTask(task).orElse(null); } catch (TaskRuns.TaskRunsException e) {
                throw new Halt("the run receipt of task '" + task.text() + "' cannot be read: "
                        + e.getMessage() + ". Remediation: release it (fail or forget), then continue"); }
            if (receipt == null || !receipt.live()) continue; // none or released: not outstanding
            held++;
            String session = receipt.session();
            if (session.isEmpty()) return stuck("task '" + task.text() + "' holds a live "
                    + receipt.phase() + " receipt with no session id to reconcile; release it (a"
                    + " receipt forget) and continue again (P4: never guessed)");
            if (liveSessions.contains(session)) { live.add(session); continue; } // proven, running
            // still: REUSED, never re-spawned
            if (receipt.phase() == Phase.LAUNCH) continue; // proven spawn; a dead session interrupts
            TaskStatus status = catalog.find(task).map(document -> document.status()).orElse(null);
            if (status == TaskStatus.DONE || status == TaskStatus.DROPPED) continue; // finished work
            return stuck("task '" + task.text() + "' holds a live PREPARE receipt by session '"
                    + session + "', which is no longer live, and its work is not done; whether that"
                    + " spawn happened cannot be told from the durable record. Remediation: release"
                    + " the reservation (the session's Done or a receipt forget), then continue; never"
                    + " retried automatically (P4/P4.10)");
        }
        // No live reservation: before the intent or a released failure -- safe to resume. Full
        // cover: every segment went out -- confirmed. Between: partial, a stage launches as a whole.
        if (held == 0) return new Assessment(null, false, List.of());
        if (held == current.tasks().size()) return new Assessment(null, true, List.copyOf(live));
        return stuck("the live receipts cover " + held + " of " + current.tasks().size() + " tasks of"
                + " stage " + current.stage() + "; a stage launches as a whole, so a partial cover"
                + " is not a launch. Remediation: let the running sessions finish or release their"
                + " reservations, then continue again");
    }

    /** Revalidates graph and choices; null when valid. */
    private static String invalidGraph(RunningPlan plan, TaskSnapshot catalog, Set<RepositoryId> registered) {
        for (StageState stage : plan.stages()) {
            for (TaskId task : stage.tasks()) {
                if (catalog.find(task).isEmpty()) return "task '" + task.text() + "' of stage "
                        + stage.stage() + " is no longer in the catalog; the confirmed graph cannot"
                        + " be revalidated. Remediation: start a fresh plan (P4.10)";
            }
        }
        for (Selection selection : plan.launch().selections().values()) {
            if (!registered.contains(selection.repository())) return "the plan's choice of repository"
                    + " for '" + selection.projectName() + "' is no longer registered; the choices"
                    + " cannot be revalidated. Remediation: start a fresh plan (P4.10)";
        }
        return null;
    }

    /** One bounded store wait; failure and timeout are visible. */
    private static <T> T answer(CompletableFuture<T> future) {
        try {
            return future.get(STORE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted waiting");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause(); throw new IllegalStateException(reason(cause));
        } catch (TimeoutException e) { throw new IllegalStateException("no answer within " + STORE_SECONDS + "s"); }
    }
    private static String reason(Throwable failure) {
        String message = failure.getMessage();
        return message != null && !message.isBlank() ? message : String.valueOf(failure);
    }
    private static Assessment stuck(String halt) { return new Assessment(halt, false, List.of()); }
    private PlanRecovery() { }
}
