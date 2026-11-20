package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.intellij.tasks.NewTaskPreset;
import app.drydock.intellij.tasks.TaskLauncher;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.StackUnits;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskStatus;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The claimable follow-up (S4 of docs/StackedPrExecution.md): ONE follow-up
 * task launched as its OWN stacked session, its work cut from its
 * in-repository prerequisite's {@code dd/<id>} branch tip. One code path
 * serves all three claim surfaces -- the {@code task_claim} MCP tool, the
 * Tasks tree's Claim action, and the auto-start of a follow-up that becomes
 * claimable inside a running stack. The confirmed O3 policy (2026-11-11):
 * a claim launches immediately, with no human confirmation step -- an
 * explicit, documented deviation from D12 scoped to follow-ups.
 *
 * <p>Claimability (the confirmed scope: follow-ups only): the task is
 * open, unowned by a live session, and carries at least one in-repository
 * prerequisite that is not dropped -- the branch its work stacks on, DONE
 * prerequisites included (claiming "later", after the prerequisite
 * finished, is S4's whole point). A task with no in-repository
 * prerequisite is not a follow-up; a task whose remaining prerequisites
 * live in other repositories keeps the staged-plan model (S5), because a
 * branch cannot stack across repositories. A task owned by a live session
 * refuses; the follow-up of a dead (interrupted) session is still open and
 * claimable again. Graph unsoundness (broken reference, cycle, P3)
 * refuses: a claim never starts on a graph a plan would reject.</p>
 *
 * <p>Branch existence is deliberately NOT part of the pure validation: it
 * is a git probe, and the MCP tool must answer it as an immediate refusal
 * ("the prerequisite has no branch yet", S4) rather than waiting -- the
 * probe is the caller's, against the shape this class computes. The pure
 * half carries no I/O and no platform types, so plain JUnit pins every
 * rule; the launch half composes the exact {@link StackLaunch} primitives
 * the confirmed stacks launch through.</p>
 */
final class ClaimLaunch {

    private ClaimLaunch() {
    }

    /** The validated stacked shape of one claim: the claimed task, the
      * prerequisite it stacks on (its FIRST in-repository non-dropped
      * prerequisite, the same first-base rule S3's pull requests use), and
      * every in-repository non-dropped prerequisite branch its launch
      * waits for. */
    record Shape(TaskId task, TaskId base, List<TaskId> waits) {
        Shape {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(base, "base");
            waits = List.copyOf(waits);
            if (!waits.contains(base)) {
                throw new IllegalArgumentException("the stack base '" + base.text()
                        + "' is always one of the waits");
            }
        }
    }

    /** One validation answer: exactly one of a shape and a refusal reason,
      * in the {@link app.drydock.mcp.TaskToolContext.Outcome} tradition -- a
      * refusal is a value the tool hands to the agent verbatim. A refusal is
      * {@code healable} when its condition heals on its own without a human
      * edit: the only such validate refusal is the S5 cross-repository one,
      * which stops firing once the cross-repo prerequisite reaches DONE
      * (validate then ignores DONE cross-repo prerequisites). Every other
      * refusal -- not open, owned by a live session, in flight, unsound
      * graph, no in-repository prerequisite -- is structural and only a
      * human edit lifts it. */
    record Attempt(Shape shape, String refusal, boolean healable) {

        static Attempt shaped(Shape shape) {
            return new Attempt(shape, null, false);
        }

        static Attempt refused(String reason) {
            return new Attempt(null, reason, false);
        }

        static Attempt refusedHealable(String reason) {
            return new Attempt(null, reason, true);
        }

        boolean refused() {
            return refusal != null;
        }
    }

    /**
     * Pure validation of one claim, over the snapshot the graph was built
     * from. {@code liveSessions} is the live catalog's session ids;
     * {@code inFlight} the claims and launches already under way (the
     * caller's guard set -- the same one the tree's Start uses, so a claim
     * never races a start of the same task). Every refusal names what went
     * wrong and what would make the task claimable.
     */
    static Attempt validate(TaskDocument task, TaskGraph graph, Set<String> liveSessions,
            Set<TaskId> inFlight) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(graph, "graph");
        if (task.status() != TaskStatus.OPEN) {
            return Attempt.refused("Task '" + task.id().text() + "' is " + task.status().storedName()
                    + ", not open; only an open follow-up is claimable.");
        }
        for (String session : task.sessions()) {
            if (liveSessions.contains(session)) {
                return Attempt.refused("Task '" + task.id().text() + "' is owned by the live session "
                        + session + "; a claimed task is launched as its own session, never"
                        + " started twice.");
            }
        }
        if (inFlight.contains(task.id())) {
            return Attempt.refused("A claim or start of '" + task.id().text()
                    + "' is already in flight; the task never launches twice.");
        }
        TaskGraph.TaskDiagnostics diagnostics = graph.diagnosticsOf(task.id())
                .orElseThrow(() -> new IllegalArgumentException("the graph holds no diagnostics for '"
                        + task.id().text() + "'; build it from the snapshot the task came from"));
        if (!diagnostics.broken().isEmpty() || !diagnostics.cycles().isEmpty()) {
            return Attempt.refused("The dependency graph of '" + task.id().text()
                    + "' is unsound (a broken reference or a cycle, P3); fix the graph"
                    + " before claiming.");
        }
        List<TaskId> inRepo = new ArrayList<>();
        boolean unfinishedElsewhere = false;
        for (TaskGraph.ResolvedReference reference : diagnostics.resolved()) {
            TaskDocument prerequisite = reference.target();
            if (prerequisite.status() == TaskStatus.DROPPED) {
                continue; // dropped work is done for; nothing stacks on it, nothing waits for it
            }
            if (prerequisite.repository().equals(task.repository())) {
                // DONE prerequisites are valid stack bases: claiming "later"
                // -- after the prerequisite finished -- is S4's whole point,
                // and the stacked pull-request chain survives a done state.
                inRepo.add(prerequisite.id());
            } else if (prerequisite.status() != TaskStatus.DONE) {
                unfinishedElsewhere = true;
            }
        }
        if (inRepo.isEmpty()) {
            if (unfinishedElsewhere) {
                // The one healable validate refusal: once the cross-repo
                // prerequisite reaches DONE, validate ignores it and this
                // branch stops firing -- so the refusal is never terminal.
                return Attempt.refusedHealable("Task '" + task.id().text()
                        + "' waits only on unfinished"
                        + " prerequisites in OTHER repositories; stacking cannot cross a repository"
                        + " boundary (S5), so this follow-up stays in the staged-plan model and"
                        + " cannot be claimed.");
            }
            return Attempt.refused("Task '" + task.id().text() + "' has no in-repository"
                    + " prerequisite to stack on, so it is not a claimable follow-up; start it"
                    + " from the Tasks tree (Start Session) instead.");
        }
        return Attempt.shaped(new Shape(task.id(), inRepo.get(0), inRepo));
    }

    /**
     * The follow-ups of ONE running plan's tree that auto-start (S4): open
     * tasks unowned by a live session that are descendants of the plan's
     * tasks through {@code graph} -- the follow-ups created inside the
     * executed stack, which no confirmed stage covers. The plan's own tasks
     * are never candidates: their driver starts them (P4). A ready task
     * outside every running plan's tree stays D12's balloon-and-one-click-
     * Start business and is never a candidate here. Dependency order:
     * ancestors before descendants, so a chain of follow-ups starts in
     * order.
     */
    static List<TaskId> autoStartCandidates(TaskSnapshot snapshot, TaskGraph graph,
            Set<TaskId> planTasks, Set<String> liveSessions) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(planTasks, "planTasks");
        Set<TaskId> descendants = new LinkedHashSet<>();
        ArrayDeque<TaskId> work = new ArrayDeque<>(planTasks);
        Set<TaskId> seen = new HashSet<>(planTasks);
        while (!work.isEmpty()) {
            TaskId current = work.pop();
            for (TaskDocument dependent : graph.dependents().getOrDefault(current, List.of())) {
                if (seen.add(dependent.id())) {
                    descendants.add(dependent.id());
                    work.push(dependent.id());
                }
            }
        }
        List<TaskId> candidates = new ArrayList<>();
        for (TaskId id : descendants) {
            TaskDocument document = snapshot.byId().get(id);
            if (document == null || document.status() != TaskStatus.OPEN) {
                continue;
            }
            boolean live = false;
            for (String session : document.sessions()) {
                if (liveSessions.contains(session)) {
                    live = true;
                    break;
                }
            }
            if (!live) {
                candidates.add(id);
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * Launches one validated follow-up as its own stacked session: a
     * single-task unit stacked on {@code shape.base()}'s branch, prompted
     * exactly like a confirmed stack unit -- {@link StackLaunch#launchUnit}
     * composes the stacked prompt (S1/S2) fresh from the CURRENT graph, the
     * same rule a confirmed unit's prompt follows. The caller has validated
     * the claim and probed the wait branches, so the unit's own branch wait
     * (S2) is a no-op formality. The launch inherits the stack's shape (the
     * confirmed launch shape): full write access, a fresh worktree, the
     * window the caller resolved, and the agent the caller resolved from
     * the running stack.
     */
    static CompletableFuture<ManagedAgentSession> launch(Project window, DelayedTaskService service,
            TaskLauncher.PreparedTask prepared, AgentKind agent, Shape shape, TaskDocument task,
            TaskGraph graph, String projectName, Path mainRoot) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(mainRoot, "mainRoot");
        StackUnits.Unit unit = new StackUnits.Unit(List.of(shape.task()), shape.waits(), shape.base());
        StackLaunch.PromptInputs inputs = StackLaunch.PromptInputs.of(shape.task(), 1, 1,
                projectName, task.repository(), List.of(task), graph);
        // StackLaunch.launchUnit derives the name, the prompt, the branch,
        // the start point and the worktree folder from the inputs and the
        // unit itself; the Choice contributes only the inherited launch
        // shape (agent, mode, window presentation, model pickers) -- the
        // same fields a confirmed segment's choice contributes.
        NewTaskPlan.Choice inherited = new NewTaskPlan.Choice(
                StackLaunch.truncateTitle(task.title()), Optional.empty(), agent,
                app.drydock.domain.SessionMode.WRITES, NewTaskPlan.WorkIn.NEW_WORKTREE,
                StackBranches.name(shape.task()), StackBranches.name(shape.base()),
                prepared.worktreeFolder(StackBranches.name(shape.task())),
                NewTaskPlan.OpenIn.THIS_WINDOW, Optional.empty(), Optional.empty(),
                Optional.empty());
        return StackLaunch.launchUnit(window, service, prepared, inherited,
                inputs, unit, mainRoot, NewTaskPreset.harnessOf(agent), 0);
    }
}
