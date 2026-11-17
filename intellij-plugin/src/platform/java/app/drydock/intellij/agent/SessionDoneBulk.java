package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.SessionDonePlan.Choice;
import app.drydock.intellij.agent.SessionDonePlan.Risks;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.delayedtasks.TaskWorktreeUse;
import app.drydock.delayedtasks.SessionTaskCompletion;
import app.drydock.domain.ManagedSessionId;
import app.drydock.tasks.TaskId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * "Clean up done sessions", from the Drydock tool window's Sessions node:
 * the per-session Done ("Done with This Task…") run over every done
 * session of the selected project at once. A session is done when its
 * worktree's work has landed -- the branch merged (ancestry, or its pull
 * request recorded as merged), or the worktree prunable or detached -- the
 * test {@link SessionDonePlan#doneSession} applies, the same one the
 * standalone app's stale bucket applies to session-less worktrees. A
 * merely idle session on an unmerged branch is not done and is never
 * touched.
 *
 * <p>One dialog asks the two choices the per-session Done asks -- forget
 * only, or forget and remove the worktrees -- once for all of them, and
 * says how many agents still run (done sessions may still be running; the
 * user has confirmed they can be stopped). The per-session guards stay
 * per-session: an agent that will not stop, a task completion that will
 * not commit, a deletion that fails, a worktree with uncommitted or
 * untracked files, another live session on the same worktree, or a T41
 * dependent-session refusal each keep that session (or its worktree) and
 * are named in the report; everything else proceeds. Task completions of
 * forgotten sessions commit before the deletion (the per-session plan's
 * hard ordering, T30a), with every captured task marked done.</p>
 *
 * <p>Everything blocking runs in a background task with the IDE's own
 * progress, and every path out of that task ends it (AGENTS.md).</p>
 */
public final class SessionDoneBulk {
    private static final Logger LOG = System.getLogger(SessionDoneBulk.class.getName());

    /** Bounded wait for the status queries; they are local git reads. */
    private static final long QUERY_TIMEOUT_SECONDS = 15;

    /** Bounded wait for {@code git worktree remove}; deleting a large tree takes time. */
    private static final long REMOVE_TIMEOUT_SECONDS = 120;

    /** How long a done session's agent gets to stop before its session is skipped. */
    private static final long STOP_WAIT_MILLIS = 5_000;
    private static final long STOP_POLL_MILLIS = 100;

    private SessionDoneBulk() {
    }

    /** One done session found by the scan: the row, its worktree entry, whether its agent runs. */
    record Done(ManagedAgentSession session, Worktree worktree, boolean running) {
    }

    /** A listed worktree paired with its canonical root, resolved once per scan. */
    private record CanonicalWorktree(Worktree worktree, Path canonical) {
    }

    /**
     * Pure: the done sessions of one project's worktree listing. A session
     * joins through paths -- its canonical working directory sits on the
     * deepest listed worktree whose root contains it (a session started in
     * a subdirectory still resolves to its worktree, the same rule the
     * tree's branch lookup applies) -- and that worktree must pass
     * {@link SessionDonePlan#doneSession}. The running predicate is
     * injected so tests need no registry.
     */
    static List<Done> select(List<ManagedAgentSession> sessions, List<Worktree> worktrees,
                             Predicate<ManagedAgentSession> running) {
        // Canonicalize the worktree roots once, not once per session: the
        // deepest-worktree rule reads them for every candidate.
        List<CanonicalWorktree> candidates = worktrees.stream()
                .map(worktree -> new CanonicalWorktree(worktree, WorktreeService.canonical(worktree.path())))
                .toList();
        List<Done> done = new ArrayList<>();
        for (ManagedAgentSession session : sessions) {
            Path directory = WorktreeService.canonical(session.workingDirectory());
            candidates.stream()
                    .filter(cw -> directory.startsWith(cw.canonical()))
                    .max(Comparator.comparingInt(cw -> cw.worktree().path().getNameCount()))
                    .filter(cw -> SessionDonePlan.doneSession(cw.worktree()))
                    .ifPresent(cw -> done.add(new Done(session, cw.worktree(), running.test(session))));
        }
        return List.copyOf(done);
    }

    /** EDT entry point: scan off the EDT, ask once, act. {@code mainRoot} is the project's main checkout. */
    public static void cleanup(Project from, Path mainRoot) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Finding done sessions…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<Done> targets;
                try (WorktreeService worktrees = new WorktreeService()) {
                    List<Worktree> listed = worktrees.list(mainRoot).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    List<ManagedAgentSession> sessions =
                            DrydockPluginService.getInstance().stateStore().state().sessions();
                    targets = select(sessions, listed, session -> AgentExecutionRegistry.getInstance().isRunning(session.id()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, () -> "Could not look for done sessions under " + mainRoot + ": " + e);
                    report(from, "Could not look for done sessions: " + WorktreeErrors.visibleMessage(e));
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (targets.isEmpty()) {
                        Messages.showInfoMessage(from, "No done sessions here: every session's work is still "
                                + "on an unmerged branch.", "Drydock");
                        return;
                    }
                    SessionDoneBulkDialog dialog = new SessionDoneBulkDialog(from, targets);
                    if (dialog.showAndGet()) {
                        perform(from, mainRoot, targets, dialog.choice(), dialog.deleteBranches());
                    }
                    // Cancelled: capture only reads, so nothing was written.
                }, ModalityState.nonModal(), from.getDisposed());
            }
        });
    }

    /** A forgotten session and what its per-session Done step captured, for the removal pass. */
    private record Forgotten(ManagedAgentSession session, Worktree worktree, SessionTaskCompletion completion) {
    }

    /**
     * The accepted choice, applied per session. Phase one stops and forgets
     * every target it can; phase two (removal choice only) removes the
     * forgotten sessions' clean worktrees, grouped by worktree so two
     * sessions sharing one never race its guards, then prunes the
     * repositories whose prunable worktree entries were forgotten.
     */
    private static void perform(Project from, Path mainRoot, List<Done> targets, Choice choice,
                                boolean deleteBranches) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Cleaning up done sessions…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> failures = new ArrayList<>();
                List<Forgotten> forgotten = new ArrayList<>();
                try {
                    for (int i = 0; i < targets.size(); i++) {
                        indicator.checkCanceled();
                        Done target = targets.get(i);
                        ManagedAgentSession session = target.session();
                        indicator.setText("Forgetting " + session.displayName());
                        indicator.setFraction((double) i / targets.size());
                        forget(from, target, choice, indicator, failures)
                                .ifPresent(record -> forgotten.add(record));
                    }
                    if (choice == Choice.FORGET_AND_REMOVE && !forgotten.isEmpty()) {
                        removeWorktrees(mainRoot, forgotten, deleteBranches, indicator, failures);
                    }
                } catch (ProcessCanceledException e) {
                    // Cancelled: the forgotten sessions' removal reservations
                    // would otherwise pin the pending-removal guard on
                    // worktrees that will never be removed. Extras are safe
                    // no-ops (two-arg remove), so this runs unconditionally.
                    forgotten.forEach(record -> record.completion().releaseWorktreeRemovalReservation());
                    throw e;
                }
                if (!forgotten.isEmpty()) {
                    closeTabs(from, forgotten.stream().map(Forgotten::session).toList());
                    // The tree rebuilt as the rows were forgotten; the removals
                    // (or prunes) above changed the worktree picture underneath.
                    DrydockSessionsListener.publishChanged();
                }
                if (!failures.isEmpty()) {
                    report(from, "Some done sessions could not be cleaned up:\n" + String.join("\n", failures));
                }
            }
        });
    }

    /**
     * Stops, commits, and forgets one done session; the returned record
     * feeds the removal pass. A failure anywhere adds to {@code failures}
     * and yields empty -- the session stays, with what happened named.
     */
    private static Optional<Forgotten> forget(Project from, Done target, Choice choice,
                                              ProgressIndicator indicator, List<String> failures) {
        ManagedAgentSession session = target.session();
        if (SessionTransfer.inProgress(session.id())) {
            failures.add(session.displayName() + ": the session is being transferred");
            return java.util.Optional.empty();
        }
        // target.running() is the scan-time snapshot, taken before the modal
        // dialog; an agent launched since then would otherwise be forgotten
        // (and its worktree force-removed) while still running.
        boolean running = target.running()
                || AgentExecutionRegistry.getInstance().isRunning(session.id());
        if (running) {
            // Done means the user has confirmed the work landed; stopping is
            // the bulk confirm's own promise, so no per-agent question here.
            AgentExecutionRegistry.getInstance().stop(session.id());
            if (!awaitStopped(session.id(), indicator)) {
                failures.add(session.displayName() + ": " + StopAgentsPlan.notStoppedYet(session.displayName()));
                return java.util.Optional.empty();
            }
        }
        SessionTaskCompletion completion = SessionDone.captureTasks(session);
        // The reservation marks the worktree pending removal before any
        // completion commits (T41: stage auto-proceed cannot launch into a
        // worktree about to be removed); a prunable worktree has no folder
        // to remove, only a metadata prune, so it never reserves.
        boolean reserving = choice == Choice.FORGET_AND_REMOVE && !target.worktree().prunable();
        if (reserving) {
            completion.reservePendingWorktreeRemoval();
        }
        SessionTaskCompletion.Result committed = completion.commit(allTaskIds(completion));
        if (!committed.deletionAllowed()) {
            if (reserving) {
                completion.releaseWorktreeRemovalReservation();
            }
            failures.add(session.displayName() + ": could not save its task completions, so it was NOT forgotten"
                    + " -- " + SessionDone.reasons(committed));
            return java.util.Optional.empty();
        }
        try {
            DrydockStartupActivity.sessions(from).deleteSession(session.id())
                    .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (reserving) {
                completion.releaseWorktreeRemovalReservation();
            }
            failures.add(session.displayName() + ": interrupted before it could be forgotten");
            return java.util.Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            if (reserving) {
                completion.releaseWorktreeRemovalReservation();
            }
            failures.add(session.displayName() + ": could not forget the task -- " + WorktreeErrors.visibleMessage(e));
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Forgotten(session, target.worktree(), completion));
    }

    /** Every captured task marked done (the bulk dialog's standing choice). */
    private static Set<TaskId> allTaskIds(SessionTaskCompletion completion) {
        Set<TaskId> ids = new LinkedHashSet<>();
        completion.tasks().forEach(task -> ids.add(task.id()));
        return ids;
    }

    /** Bounded wait for a stopped agent; false when it would not end in time. */
    private static boolean awaitStopped(ManagedSessionId id, ProgressIndicator indicator) {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        long deadline = System.currentTimeMillis() + STOP_WAIT_MILLIS;
        while (registry.isRunning(id)) {
            indicator.checkCanceled();
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(STOP_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Removes the forgotten sessions' clean worktrees. One worktree at a
     * time (its sharers were all forgotten in phase one, so "another
     * session still using it" can only name a session that was not done --
     * kept, with the blocker named); a prunable worktree has no folder, its
     * repository gets one metadata prune. Each removal's reservation is
     * consumed whatever the outcome (the T30a/T41 boundary).
     */
    private static void removeWorktrees(Path mainRoot, List<Forgotten> forgotten,
                                        boolean deleteBranches, ProgressIndicator indicator,
                                        List<String> failures) {
        Map<Path, List<Forgotten>> byWorktree = new LinkedHashMap<>();
        List<Forgotten> prunable = new ArrayList<>();
        for (Forgotten record : forgotten) {
            if (record.worktree().prunable()) {
                prunable.add(record);
            } else {
                // Group by the worktree root select chose -- not the
                // session's own working directory, which can sit in a
                // subdirectory -- canonicalized the same way.
                byWorktree.computeIfAbsent(WorktreeService.canonical(record.worktree().path()),
                        directory -> new ArrayList<>()).add(record);
            }
        }
        try (WorktreeService worktrees = new WorktreeService()) {
            int i = 0;
            for (Map.Entry<Path, List<Forgotten>> entry : byWorktree.entrySet()) {
                indicator.checkCanceled();
                Path directory = entry.getKey();
                indicator.setText("Removing " + directory.getFileName());
                indicator.setFraction((double) i++ / (byWorktree.size() + 1));
                removeOne(worktrees, mainRoot, directory, entry.getValue(), deleteBranches, failures);
            }
            indicator.checkCanceled();
            if (!prunable.isEmpty()) {
                indicator.setText("Pruning removed worktrees…");
                indicator.setFraction(1);
                try {
                    // git's prune has no per-worktree form; one pass clears
                    // every prunable entry and touches no working tree.
                    worktrees.prune(mainRoot).get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.add(prunable.size() + " pruned worktree"
                            + (prunable.size() == 1 ? "" : "s") + " kept: interrupted");
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    failures.add("Could not prune " + prunable.size() + " removed worktree"
                            + (prunable.size() == 1 ? "" : "s") + ": " + WorktreeErrors.visibleMessage(e));
                }
            }
        }
    }

    /** One clean worktree's guarded removal; every refusal keeps it, with the reason named. */
    private static void removeOne(WorktreeService worktrees, Path mainRoot, Path directory,
                                  List<Forgotten> sharers, boolean deleteBranches, List<String> failures) {
        Forgotten first = sharers.get(0);
        // Post-deletion live sessions: the forgotten ones are gone from the
        // catalog, so a hit names a session the cleanup never touched. The
        // containment match covers a kept, live session running anywhere in
        // the worktree root's subtree, not just at the root.
        List<String> others = DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                .filter(other -> WorktreeService.canonical(other.workingDirectory()).startsWith(directory))
                .map(ManagedAgentSession::displayName)
                .toList();
        Optional<String> blocker = SessionDonePlan.removalBlocker(false, true, others);
        blocker = blocker.or(() -> TaskWorktreeUse.guard(
                DrydockPluginService.getInstance().taskService().root(), directory, SessionDone.liveSessionIds()));
        if (blocker.isPresent()) {
            releaseAll(sharers);
            failures.add(directory.getFileName() + ": worktree kept -- " + blocker.get());
            return;
        }
        Risks risks = dirt(worktrees, directory);
        if (risks == null) {
            releaseAll(sharers);
            failures.add(directory.getFileName() + ": worktree kept -- its state could not be read");
            return;
        }
        if (SessionDonePlan.riskLine(risks).isPresent()) {
            releaseAll(sharers);
            failures.add(directory.getFileName() + ": worktree kept -- "
                    + SessionDonePlan.bulkKeptReason(risks));
            return;
        }
        Optional<String> branch = deleteBranches ? first.worktree().branch() : Optional.empty();
        try {
            // Clean by the check above, and the confirm chose removal: the
            // lock-preserving force mode is the per-session Done's own path
            // (its status is only a snapshot; a deliberate lock stays a
            // blocker and was refused above).
            worktrees.removeDiscardingChanges(mainRoot, directory, branch)
                    .get(REMOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.add(directory.getFileName() + ": interrupted; the worktree was kept");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            failures.add(directory.getFileName() + ": could not be removed -- " + WorktreeErrors.visibleMessage(e));
        } finally {
            // The removal path this reservation waited for has run, one way
            // or another (T30a/T41 boundary) -- for every sharer.
            releaseAll(sharers);
        }
    }

    /** Releases every sharer's removal reservation; extras are safe no-ops (two-arg remove). */
    private static void releaseAll(List<Forgotten> sharers) {
        for (Forgotten sharer : sharers) {
            sharer.completion().releaseWorktreeRemovalReservation();
        }
    }

    /**
     * The work a removal of {@code directory} would lose: uncommitted and
     * untracked files, IDE project files not counted (the per-session
     * plan's rule). Null when the status could not be read -- never "clean"
     * by default. The done test already guarantees the branch is merged, so
     * no ancestry check here; the branch's {@code merged} risks flag is
     * unused by the kept-reason unless the read fails.
     */
    private static Risks dirt(WorktreeService worktrees, Path directory) {
        try {
            List<String> status = worktrees.uncommittedChanges(directory)
                    .get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                    .filter(line -> !SessionDonePlan.isIdeProjectFile(line))
                    .toList();
            int untracked = (int) status.stream().filter(line -> line.startsWith("??")).count();
            return new Risks(status.size() - untracked, untracked, 0, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Could not read the status of " + directory + ": " + e);
            return null;
        }
    }

    /**
     * The forgotten sessions' consoles close wherever they were hosted; EDT
     * only, and skipped once the project is disposed (no UI to update).
     */
    private static void closeTabs(Project from, List<ManagedAgentSession> forgotten) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (ManagedAgentSession session : forgotten) {
                SessionDone.closeConsoleTabs(session);
            }
        }, ModalityState.nonModal(), from.getDisposed());
    }

    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }
}
