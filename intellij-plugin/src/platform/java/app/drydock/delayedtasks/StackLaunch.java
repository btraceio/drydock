package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GitStatusService;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.intellij.tasks.TaskLauncher;
import app.drydock.intellij.actions.SessionDialogs;
import com.intellij.openapi.project.Project;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.StackUnits;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskPrompt;
import app.drydock.tasks.TaskStatus;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static app.drydock.tasks.TaskPrompt.stackedPrompt;

/**
 * The stacked-branch launch of ONE repository segment (S1/S2 of
 * docs/StackedPrExecution.md): the segment's fresh documents decompose into
 * launch units ({@link StackUnits}), every unit becomes its own session on
 * its own stacked branch ({@code dd/<task-id>}, the first task's branch cut
 * from the unit's stack base or the confirmed start point), and a stacked
 * unit's launch WAITS for its wait branches to exist -- the fork children
 * of a chain start on their own once the chain's commits show up, with no
 * scheduler beyond the branch waits themselves (S2).
 *
 * <p>The unit prompt is composed fresh at launch time ({@link
 * TaskPrompt#stackedPrompt}) from the CURRENT documents, the graph and the
 * unit's shape -- never from a prompt frozen at confirmation, which a
 * resumed plan (P9) could not even reload. A unit whose wait branches never
 * appear inside {@link #BRANCH_TIMEOUT} fails its launch visibly; the
 * already-started sibling units are never killed (P4.12).</p>
 *
 * <p>Threading: every git probe is a blocking process run; the branch waits
 * split the work between a private daemon timer ({@link #POLLS}, which
 * schedules only the delays) and a virtual-thread runner ({@link #PROBES},
 * which runs each probe's git work) -- never the EDT, never the plan
 * driver's lane. Launches ride the existing {@link TaskLauncher} chain
 * with the unit's pre-spawn bindings.</p>
 */
final class StackLaunch {

    private static final Logger LOG = System.getLogger(StackLaunch.class.getName());

    /** One bounded wait for a stack's prerequisite branches (S2): the base
      * chain's agent creates them by committing its tasks. Generous, because
      * a chain may run for hours; a unit past it fails its launch visibly
      * instead of waiting forever. */
    static final Duration BRANCH_TIMEOUT = Duration.ofHours(4);

    /** How often the branch probes run while a stacked unit waits. */
    private static final long POLL_SECONDS = 10;

    /** The private daemon timer of every branch wait; never shut down
      * (plugin lifetime), one thread total. It schedules only the delays --
      * each probe's blocking git work runs on {@link #PROBES}, so one
      * blocked git run can never queue another unit's probe. */
    private static final ScheduledExecutorService POLLS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "drydock-stack-branch-wait");
        thread.setDaemon(true);
        return thread;
    });

    /** The probe runner of every branch wait: each probe step's blocking
      * git work runs on its own virtual thread (plugin lifetime, never shut
      * down), so one blocked run -- bounded by GitStatusService's process
      * timeout -- cannot queue another unit's probe. */
    private static final ExecutorService PROBES = Executors.newVirtualThreadPerTaskExecutor();

    private StackLaunch() {
    }

    /** The launch units of one segment's fresh documents (S1): maximal
      * linear chains, forks split off, resolved through {@code graph}. */
    static List<StackUnits.Unit> units(List<TaskDocument> segmentDocs, TaskGraph graph) {
        return StackUnits.decompose(segmentDocs.stream().map(TaskDocument::id).toList(),
                StackUnits.inSegmentEdges(segmentDocs, graph));
    }

    /** Everything the unit prompt composes from, all stored or freshly read
      * values: the plan target and its stage position, the segment's
      * identity, the unit's CURRENT documents, the done/dropped context
      * reachable from the unit's tasks, and the target's direct follow-ups. */
    record PromptInputs(TaskId target, int stage, int stageCount, String projectName, RepositoryId repository,
            List<TaskDocument> unitDocs, List<StartPlan.Context> context, List<StartPlan.FollowUp> followUps) {

        static PromptInputs of(TaskId target, int stage, int stageCount, String projectName,
                RepositoryId repository, List<TaskDocument> unitDocs, TaskGraph graph) {
            List<StartPlan.Context> context = new ArrayList<>();
            Set<TaskId> seen = new HashSet<>();
            Deque<TaskId> work = new ArrayDeque<>();
            for (TaskDocument unit : unitDocs) {
                work.push(unit.id());
            }
            while (!work.isEmpty()) {
                TaskId id = work.pop();
                if (!seen.add(id)) {
                    continue;
                }
                for (TaskGraph.ResolvedReference reference : graph.diagnosticsOf(id)
                        .orElseThrow(() -> new IllegalArgumentException("the graph holds no diagnostics for '"
                                + id.text() + "'")).resolved()) {
                    TaskDocument prerequisite = reference.target();
                    if (prerequisite.status() == TaskStatus.DONE
                            || prerequisite.status() == TaskStatus.DROPPED) {
                        context.add(new StartPlan.Context(prerequisite.id(), prerequisite.repository(),
                                prerequisite.title()));
                    } else {
                        work.push(prerequisite.id());
                    }
                }
            }
            List<StartPlan.FollowUp> followUps = new ArrayList<>();
            for (TaskDocument dependent : graph.dependents().getOrDefault(target, List.of())) {
                followUps.add(new StartPlan.FollowUp(dependent.id(), dependent.repository(), dependent.title()));
            }
            return new PromptInputs(target, stage, stageCount, projectName, repository, List.copyOf(unitDocs),
                    context, followUps);
        }
    }

    /** The unit prompt (S1/S2): composed from the given inputs, the unit's
      * shape and its start point. No {@link StartPlan} is fabricated: the
      * inputs themselves carry everything the prompt renders. */
    static String prompt(TaskPrompt.Kind kind, PromptInputs inputs, StackUnits.Unit unit,
            String baseBranch, String defaultBranch) {
        StartPlan.Segment segment = new StartPlan.Segment(inputs.repository(), inputs.projectName(),
                inputs.unitDocs());
        TaskPrompt.StackedInputs stacked = new TaskPrompt.StackedInputs(inputs.target(), inputs.stage(),
                inputs.stageCount(), segment, unit, inputs.context(), inputs.followUps());
        return stackedPrompt(kind, stacked, baseBranch, defaultBranch);
    }

    /**
     * Launches ONE unit as its own session: a root unit immediately, a
     * stacked one after its wait branches exist (S2, bounded by
     * {@link #BRANCH_TIMEOUT}). The unit's session works in a fresh worktree
     * whose branch is {@code dd/<first-task-id>}, cut from the stack base
     * branch -- or, for a root, from the repository's default branch (the
     * same branch its draft pull request opens against) -- with the unit
     * prompt as the instructions and one pre-spawn binding per member task.
     * The future completes with the RUNNING session, or exceptionally
     * on every refusal, timeout and failure; it never cancels a launch.
     */
    static CompletableFuture<ManagedAgentSession> launchUnit(Project from, DelayedTaskService service,
            TaskLauncher.PreparedTask prepared, NewTaskPlan.Choice segmentChoice, PromptInputs inputs,
            StackUnits.Unit unit, Path mainRoot, TaskPrompt.Kind kind, int cancellationGeneration) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(segmentChoice, "segmentChoice");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(mainRoot, "mainRoot");
        Objects.requireNonNull(kind, "kind");
        CompletableFuture<Void> ready = unit.isRoot()
                ? CompletableFuture.completedFuture(null)
                : awaitBranches(mainRoot, branchNames(unit));
        return ready.thenCompose(nothing -> {
            String first = StackBranches.name(unit.tasks().get(0));
            String baseBranch = unit.isRoot() ? null : StackBranches.name(unit.stackBase());
            // A root unit's branch is cut from the repository's default
            // branch -- the same branch its draft pull request opens against
            // -- never from the confirmed dialog start point; a stacked unit
            // starts at its stack base branch.
            CompletableFuture<String> startPoint = unit.isRoot()
                    ? defaultBranch(mainRoot)
                    : CompletableFuture.completedFuture(baseBranch);
            return startPoint.thenCompose(start -> {
                String prompt = prompt(kind, inputs, unit, baseBranch, start);
                NewTaskPlan.Choice unitChoice = new NewTaskPlan.Choice(
                        titleOf(inputs, unit),
                        java.util.Optional.of(prompt),
                        segmentChoice.agent(), segmentChoice.mode(), WorkIn.NEW_WORKTREE,
                        first, start, prepared.worktreeFolder(first), segmentChoice.openIn(),
                        segmentChoice.model(), segmentChoice.tiers(), segmentChoice.subagentModelRule());
                List<TaskLaunchBinding> bindings = unit.tasks().stream()
                        .map(task -> service.launchBinding(task, service.root(), List.of(),
                                cancellationGeneration))
                        .toList();
                return TaskLauncher.launchChosen(from, prepared, TaskLauncher.chosen(unitChoice),
                        new SessionDialogs.PreparedSessionCallback() { }, bindings);
            });
        });
    }

    /** Every unit of one segment, launched in parallel; the future completes
      * when every unit's session launched (or fails with the first failure;
      * the already-started units are never canceled, P4.12). */
    static CompletableFuture<List<ManagedAgentSession>> launchStack(Project from, DelayedTaskService service,
            TaskLauncher.PreparedTask prepared, NewTaskPlan.Choice segmentChoice, Path mainRoot,
            List<StackUnits.Unit> units, java.util.function.Function<StackUnits.Unit, PromptInputs> inputsOf,
            TaskPrompt.Kind kind, int cancellationGeneration) {
        List<CompletableFuture<ManagedAgentSession>> launches = new ArrayList<>();
        for (StackUnits.Unit unit : units) {
            launches.add(launchUnit(from, service, prepared, segmentChoice, inputsOf.apply(unit),
                    unit, mainRoot, kind, cancellationGeneration));
        }
        return CompletableFuture.allOf(launches.toArray(CompletableFuture[]::new))
                .thenApply(nothing -> launches.stream().map(CompletableFuture::join).toList());
    }

    /** The repository's default branch, resolved exactly like the root
      * unit's pull-request base ({@code origin/HEAD}, then the well-known
      * names, then {@code main}): the start point of a root unit's branch.
      * Composed, never blocking the caller's thread; an empty resolution
      * falls back to {@code main} -- a wrong name fails visibly later, at
      * the worktree's creation. Package-private for tests. */
    static CompletableFuture<String> defaultBranch(Path mainRoot) {
        GitStatusService git = new GitStatusService();
        return git.defaultBranch(mainRoot)
                .thenApply(branch -> branch.orElse("main"))
                .whenComplete((nothing, failure) -> git.close());
    }

    /** The unit's wait branches, in the naming convention. */
    private static List<String> branchNames(StackUnits.Unit unit) {
        return unit.waits().stream().map(StackBranches::name).toList();
    }

    /** The unit session's label: its first task's title, cut to the dialog's
      * name limit, as every session is named after its task. */
    private static String titleOf(PromptInputs inputs, StackUnits.Unit unit) {
        String title = inputs.projectName();
        for (TaskDocument document : inputs.unitDocs()) {
            if (document.id().equals(unit.tasks().get(0))) {
                title = document.title();
                break;
            }
        }
        return truncateTitle(title);
    }

    /** The dialog's 60-character label cut, never splitting a surrogate
      * pair: a title whose 60th char is the high half of a surrogate pair
      * is cut at 59. Package-private pure seam for tests. */
    static String truncateTitle(String title) {
        if (title.length() <= 60) {
            return title;
        }
        if (Character.isHighSurrogate(title.charAt(59))) {
            return title.substring(0, 59);
        }
        return title.substring(0, 60);
    }

    /**
     * Waits until every named branch exists in {@code mainRoot} (local refs
     * are shared across a repository's worktrees), polling {@code git
     * rev-parse --verify} on the probe runner; fails visibly at
     * {@link #BRANCH_TIMEOUT}. An immediately satisfied wait completes
     * without a probe delay.
     */
    static CompletableFuture<Void> awaitBranches(Path mainRoot, List<String> branches) {
        return awaitBranches(mainRoot, branches, BRANCH_TIMEOUT);
    }

    /** The timeout-injectable form of {@link #awaitBranches} (package-
      * private test seam): the wait fails at the caller-supplied timeout
      * instead of {@link #BRANCH_TIMEOUT}, so tests exercise the deadline
      * expiry deterministically. */
    static CompletableFuture<Void> awaitBranches(Path mainRoot, List<String> branches, Duration timeout) {
        Objects.requireNonNull(mainRoot, "mainRoot");
        Objects.requireNonNull(branches, "branches");
        Objects.requireNonNull(timeout, "timeout");
        if (branches.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<Void> done = new CompletableFuture<>();
        probe(mainRoot, branches, deadline, done, 0);
        return done;
    }

    /** One probe step: {@link #POLLS} schedules only the delay; the
      * blocking git work runs on {@link #PROBES} (one virtual thread per
      * probe). Completes when every branch resolves, reschedules after the
      * git work otherwise (fixed-delay semantics: at most one probe in
      * flight per waiter), fails at the deadline. */
    private static void probe(Path mainRoot, List<String> branches, long deadline,
            CompletableFuture<Void> done, long delaySeconds) {
        POLLS.schedule(() -> {
            if (done.isDone()) {
                return;
            }
            PROBES.execute(() -> {
                try (GitStatusService git = new GitStatusService()) {
                    boolean all = true;
                    for (String branch : branches) {
                        if (git.commitForRefBlocking(mainRoot, branch).isEmpty()) {
                            all = false;
                            break;
                        }
                    }
                    if (all) {
                        done.complete(null);
                    } else if (System.nanoTime() >= deadline) {
                        done.completeExceptionally(new IllegalStateException("the stack's prerequisite branches "
                                + branches + " did not appear within " + BRANCH_TIMEOUT + "; the unit's launch"
                                + " is refused (S2)"));
                    } else {
                        probe(mainRoot, branches, deadline, done, POLL_SECONDS);
                    }
                } catch (RuntimeException failure) {
                    LOG.log(Level.WARNING, "The branch probe of a stacked unit failed; retrying", failure);
                    if (System.nanoTime() >= deadline) {
                        done.completeExceptionally(new IllegalStateException("the stack's prerequisite branches "
                                + branches + " could not be probed within " + BRANCH_TIMEOUT + "; the unit's"
                                + " launch is refused (S2)", failure));
                    } else {
                        probe(mainRoot, branches, deadline, done, POLL_SECONDS);
                    }
                }
            });
        }, delaySeconds, TimeUnit.SECONDS);
    }
}
