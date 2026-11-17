package app.drydock.ui.model;

import app.drydock.agent.api.ResumeCostEstimate;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.git.GitStatus;
import app.drydock.git.WorktreeConfigGuard;
import app.drydock.git.WorktreeService;
import app.drydock.review.RepositoryPullRequests;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The observable store both session-rendering surfaces (the repository
 * sidebar and the session tab headers) read from: the managed-session
 * snapshot, per-repository and per-worktree git status, worktree discovery
 * results, and which session is active (selected tab).
 *
 * <p>Writers push fresh snapshots/values in; the model diffs against what
 * it already holds and emits the NARROWEST applicable {@link Listener}
 * event -- a field-level session change updates one row, a status arrival
 * updates one repo or worktree row, and only genuine row additions/
 * removals/moves escalate to {@link Listener#structureChanged()}. Setting
 * an identical value emits nothing, so redundant pushes (e.g. two callers
 * reporting the same deletion) are silent.</p>
 *
 * <p><b>Threading:</b> the model performs no thread hops itself and is not
 * synchronized; in the application every mutator is called on the JavaFX
 * Application Thread (async producers hop via {@code Platform.runLater}
 * first), which is also where listeners are then invoked, synchronously.
 * Listeners must not mutate the model from inside a notification. Tests
 * may drive the model from any single thread.</p>
 */
public final class WorkspaceViewModel {

    /** Change notifications; all methods default to no-ops so subscribers override only what they render. */
    public interface Listener {
        /** Rows were added, removed, or moved (session set/order, repo membership, worktree lists). */
        default void structureChanged() { }

        /** One session's fields changed (status, name, PR chip, timestamps) without moving its row. */
        default void sessionRowChanged(ManagedSessionId sessionId) { }

        /** A repository's git status, status failure, or aggregate session facts changed. */
        default void repoChanged(RepositoryId repositoryId) { }

        /** One worktree checkout's git status changed (branch tag / dirty dot of its row). */
        default void worktreeRowChanged(Path worktreeRoot) { }

        /** The active (selected-tab) session changed; both values may be empty. */
        default void activeSessionChanged(Optional<ManagedSessionId> previous,
                                          Optional<ManagedSessionId> current) { }
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private List<ManagedAgentSession> sessions = List.of();
    private final Map<RepositoryId, GitStatus> repoStatuses = new HashMap<>();
    private final Map<RepositoryId, Throwable> repoStatusFailures = new HashMap<>();
    private final Map<Path, GitStatus> worktreeStatuses = new HashMap<>();
    private final Map<RepositoryId, List<WorktreeService.Worktree>> worktrees = new HashMap<>();
    private final Map<RepositoryId, RepositoryPullRequests.Outcome> pullRequests = new HashMap<>();
    private Optional<ManagedSessionId> activeSession = Optional.empty();
    private Map<ManagedSessionId, SessionActivity> activities = Map.of();
    private final Map<ManagedSessionId, ResumeCostEstimate> resumeCostEstimates = new HashMap<>();

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---- Sessions -----------------------------------------------------------

    /** The current session snapshot (immutable). */
    public List<ManagedAgentSession> sessions() {
        return sessions;
    }

    /** The current version of one session, resolved by id (for cached menus/handlers that must not go stale). */
    public Optional<ManagedAgentSession> sessionById(ManagedSessionId sessionId) {
        return sessions.stream().filter(session -> session.id().equals(sessionId)).findFirst();
    }

    /**
     * Replaces the session snapshot. Emits {@link Listener#structureChanged()}
     * if sessions were added/removed/reordered OR any session changed the
     * facts that decide WHICH row/parent it renders under (repository,
     * worktree root); otherwise emits one {@link Listener#sessionRowChanged}
     * per changed session plus one {@link Listener#repoChanged} per affected
     * repository (the repo header aggregates its sessions' status/count).
     * Emits nothing when the snapshot is identical.
     */
    public void setSessions(List<ManagedAgentSession> newSessions) {
        List<ManagedAgentSession> updated = List.copyOf(newSessions);
        List<ManagedAgentSession> previous = sessions;
        if (previous.equals(updated)) {
            return;
        }
        sessions = updated;
        resumeCostEstimates.keySet().retainAll(updated.stream().map(ManagedAgentSession::id).toList());
        // A session that left the snapshot (deleted) cannot be closing anymore;
        // clearing here keeps the overlay from outliving its row.
        closingSessions.retainAll(updated.stream().map(ManagedAgentSession::id).toList());
        if (isRowLevelChange(previous, updated)) {
            Set<RepositoryId> affectedRepos = new LinkedHashSet<>();
            List<ManagedSessionId> changedIds = new ArrayList<>();
            for (int i = 0; i < updated.size(); i++) {
                if (!previous.get(i).equals(updated.get(i))) {
                    changedIds.add(updated.get(i).id());
                    affectedRepos.add(updated.get(i).repositoryId());
                }
            }
            for (ManagedSessionId id : changedIds) {
                notify(listener -> listener.sessionRowChanged(id));
            }
            for (RepositoryId repositoryId : affectedRepos) {
                notify(listener -> listener.repoChanged(repositoryId));
            }
        } else {
            notify(Listener::structureChanged);
        }
    }

    // ---- Closing overlay (optimistic UI) ------------------------------------

    /**
     * Sessions whose surface is being closed right now. Purely a view-layer
     * fact: the persisted status stays authoritative and only lands (EXITED,
     * or RUNNING-again for a tmux-backed session) when the graceful close
     * actually finishes, which can take the whole grace period. Rows render
     * a dimmed, non-pulsing dot while their id is here instead of lying with
     * a RUNNING dot they know is about to flip.
     */
    private final Set<ManagedSessionId> closingSessions = new HashSet<>();

    /** Whether {@code sessionId}'s surface close is in flight (row should render its closing treatment). */
    public boolean isSessionClosing(ManagedSessionId sessionId) {
        return closingSessions.contains(sessionId);
    }

    /** Marks one session's row as closing (or clears it); notifies that row only. */
    public void setSessionClosing(ManagedSessionId sessionId, boolean closing) {
        if (closing ? closingSessions.add(sessionId) : closingSessions.remove(sessionId)) {
            notify(listener -> listener.sessionRowChanged(sessionId));
        }
    }

    /** Current transcript-derived estimate for one managed session. */
    public Optional<ResumeCostEstimate> resumeCostEstimate(ManagedSessionId sessionId) {
        return Optional.ofNullable(resumeCostEstimates.get(sessionId));
    }

    /** Stores or clears one estimate and refreshes exactly that session's renderers. */
    public void setResumeCostEstimate(ManagedSessionId sessionId, Optional<ResumeCostEstimate> estimate) {
        ResumeCostEstimate next = estimate.orElse(null);
        ResumeCostEstimate previous = next == null
                ? resumeCostEstimates.remove(sessionId)
                : resumeCostEstimates.put(sessionId, next);
        if (!Objects.equals(previous, next)) {
            notify(listener -> listener.sessionRowChanged(sessionId));
        }
    }

    /**
     * Same ids in the same order, none changing a fact that decides where
     * its row renders — safe to update rows in place. Repository and
     * worktree root pick the row's parent/slot; the display name is
     * structural too because the sidebar SORTS sibling session rows by it,
     * so a rename can reorder rows.
     */
    private static boolean isRowLevelChange(List<ManagedAgentSession> previous,
                                            List<ManagedAgentSession> updated) {
        if (previous.size() != updated.size()) {
            return false;
        }
        for (int i = 0; i < updated.size(); i++) {
            ManagedAgentSession before = previous.get(i);
            ManagedAgentSession after = updated.get(i);
            if (!before.id().equals(after.id())
                    || !before.repositoryId().equals(after.repositoryId())
                    || !before.worktreeRoot().equals(after.worktreeRoot())
                    || !before.displayName().equals(after.displayName())) {
                return false;
            }
        }
        return true;
    }

    // ---- Repository git status ----------------------------------------------

    public Optional<GitStatus> repoStatus(RepositoryId repositoryId) {
        return Optional.ofNullable(repoStatuses.get(repositoryId));
    }

    public Optional<Throwable> repoStatusFailure(RepositoryId repositoryId) {
        return Optional.ofNullable(repoStatusFailures.get(repositoryId));
    }

    /** Records a successful status fetch (clearing any recorded failure); emits {@code repoChanged} on change. */
    public void setRepoStatus(RepositoryId repositoryId, GitStatus status) {
        boolean changed = !status.equals(repoStatuses.put(repositoryId, status));
        changed |= repoStatusFailures.remove(repositoryId) != null;
        if (changed) {
            notify(listener -> listener.repoChanged(repositoryId));
        }
    }

    /** Records a failed status fetch (clearing any stale status); emits {@code repoChanged} on change. */
    public void setRepoStatusFailure(RepositoryId repositoryId, Throwable failure) {
        boolean changed = repoStatusFailures.put(repositoryId, failure) != failure;
        changed |= repoStatuses.remove(repositoryId) != null;
        if (changed) {
            notify(listener -> listener.repoChanged(repositoryId));
        }
    }

    // ---- Worktree git status -------------------------------------------------

    public Optional<GitStatus> worktreeStatus(Path worktreeRoot) {
        return Optional.ofNullable(worktreeStatuses.get(worktreeRoot));
    }

    public void setWorktreeStatus(Path worktreeRoot, GitStatus status) {
        if (!status.equals(worktreeStatuses.put(worktreeRoot, status))) {
            notify(listener -> listener.worktreeRowChanged(worktreeRoot));
        }
    }

    public void removeWorktreeStatus(Path worktreeRoot) {
        if (worktreeStatuses.remove(worktreeRoot) != null) {
            notify(listener -> listener.worktreeRowChanged(worktreeRoot));
        }
    }

    // ---- Worktree discovery --------------------------------------------------

    /** The latest {@code git worktree list} result for a repository; empty until its first scan lands. */
    public Optional<List<WorktreeService.Worktree>> worktrees(RepositoryId repositoryId) {
        return Optional.ofNullable(worktrees.get(repositoryId));
    }

    /** True once ANY repository's worktree discovery has produced a result (footer fallback heuristic). */
    public boolean anyWorktreesDiscovered() {
        return !worktrees.isEmpty();
    }

    /** Stores a discovery result; worktree rows appear/disappear, so a change emits {@code structureChanged}. */
    public void setWorktrees(RepositoryId repositoryId, List<WorktreeService.Worktree> discovered) {
        List<WorktreeService.Worktree> updated = List.copyOf(discovered);
        if (!updated.equals(worktrees.put(repositoryId, updated))) {
            notify(Listener::structureChanged);
        }
    }

    /** Forgets everything recorded for a removed repository; emits {@code structureChanged} if anything was held. */
    public void removeRepository(RepositoryId repositoryId) {
        boolean changed = repoStatuses.remove(repositoryId) != null;
        changed |= repoStatusFailures.remove(repositoryId) != null;
        changed |= worktrees.remove(repositoryId) != null;
        changed |= worktreeConfigFlags.remove(repositoryId) != null;
        changed |= worktreeConfigProbeGenerations.remove(repositoryId) != null;
        changed |= pullRequests.remove(repositoryId) != null;
        if (changed) {
            notify(Listener::structureChanged);
        }
    }

    // ---- Worktree-config guard -----------------------------------------------

    /** The {@code extensions.worktreeConfig} probe results; only repositories with the flag SET are held here. */
    private final Map<RepositoryId, WorktreeConfigGuard.Flag> worktreeConfigFlags = new HashMap<>();

    /**
     * Monotonic probe generations per repository. A probe result may only
     * update the flag while its generation is still the latest issued one:
     * an in-flight check that completes after a successful fix would
     * otherwise resurrect the warning for a flag that is already gone.
     */
    private final Map<RepositoryId, Integer> worktreeConfigProbeGenerations = new HashMap<>();

    /** Issues a probe generation for {@code repositoryId}; the returned value must come back via {@link #applyWorktreeConfigProbe}. */
    public int issueWorktreeConfigProbe(RepositoryId repositoryId) {
        return worktreeConfigProbeGenerations.merge(repositoryId, 1, Integer::sum);
    }

    /**
     * Applies a probe result that was issued under {@code generation}.
     * Discarded silently when a newer probe was issued or a fix invalidated
     * the generation in the meantime; the model write itself is
     * {@link #setWorktreeConfigFlag}'s usual change-notifying path.
     */
    public void applyWorktreeConfigProbe(RepositoryId repositoryId, int generation, WorktreeConfigGuard.Flag flag) {
        if (worktreeConfigProbeGenerations.getOrDefault(repositoryId, 0) != generation) {
            return;
        }
        setWorktreeConfigFlag(repositoryId, flag);
    }

    /** Invalidates every in-flight probe for {@code repositoryId}; called when a fix starts. */
    public void invalidateWorktreeConfigProbes(RepositoryId repositoryId) {
        worktreeConfigProbeGenerations.merge(repositoryId, 1, Integer::sum);
    }

    /**
     * The latest {@code extensions.worktreeConfig} probe for a repository;
     * empty until its first check lands or the flag was found absent.
     * Only repositories with the flag SET are held here.
     */
    public Optional<WorktreeConfigGuard.Flag> worktreeConfigFlag(RepositoryId repositoryId) {
        return Optional.ofNullable(worktreeConfigFlags.get(repositoryId));
    }

    /**
     * Stores the flag probe; a flagged repository row carries the warning
     * marker, so a change emits {@code repoChanged}. A clean result clears
     * any stored flag.
     */
    public void setWorktreeConfigFlag(RepositoryId repositoryId, WorktreeConfigGuard.Flag flag) {
        boolean changed;
        if (flag.set()) {
            changed = !flag.equals(worktreeConfigFlags.put(repositoryId, flag));
        } else {
            changed = worktreeConfigFlags.remove(repositoryId) != null;
        }
        if (changed) {
            notify(listener -> listener.repoChanged(repositoryId));
        }
    }

    // ---- Pull requests ---------------------------------------------------

    /** The latest pull-request scan result for a repository; empty until its first scan lands. */
    public Optional<RepositoryPullRequests.Outcome> pullRequests(RepositoryId repositoryId) {
        return Optional.ofNullable(pullRequests.get(repositoryId));
    }

    /** Stores a scan result; PR rows appear/disappear, so a change emits {@code structureChanged}. */
    public void setPullRequests(RepositoryId repositoryId, RepositoryPullRequests.Outcome outcome) {
        if (!outcome.equals(pullRequests.put(repositoryId, outcome))) {
            notify(Listener::structureChanged);
        }
    }

    // ---- Session activity -----------------------------------------------------

    /**
     * What the session's Claude is currently doing, or {@link
     * SessionActivity#UNKNOWN} when nothing has reported. Distinct from the
     * session's {@code status}, which tracks the terminal process lifecycle.
     */
    public SessionActivity activityOf(ManagedSessionId sessionId) {
        return activities.getOrDefault(sessionId, SessionActivity.UNKNOWN);
    }

    /**
     * Replaces the activity snapshot, emitting one {@link
     * Listener#sessionRowChanged} per session whose activity actually moved.
     * This runs on every poll tick, so an unchanged map must stay silent --
     * otherwise the sidebar would rebuild a row every second forever.
     */
    public void setActivities(Map<ManagedSessionId, SessionActivity> newActivities) {
        Map<ManagedSessionId, SessionActivity> updated = Map.copyOf(newActivities);
        Map<ManagedSessionId, SessionActivity> previous = activities;
        if (previous.equals(updated)) {
            return;
        }
        activities = updated;
        Set<ManagedSessionId> touched = new LinkedHashSet<>(previous.keySet());
        touched.addAll(updated.keySet());
        for (ManagedSessionId sessionId : touched) {
            SessionActivity before = previous.getOrDefault(sessionId, SessionActivity.UNKNOWN);
            SessionActivity after = updated.getOrDefault(sessionId, SessionActivity.UNKNOWN);
            if (before != after) {
                notify(listener -> listener.sessionRowChanged(sessionId));
            }
        }
    }

    // ---- Active session ------------------------------------------------------

    public Optional<ManagedSessionId> activeSession() {
        return activeSession;
    }

    /** Records the selected tab's session; emits {@code activeSessionChanged} only on an actual change. */
    public void setActiveSession(Optional<ManagedSessionId> sessionId) {
        Optional<ManagedSessionId> updated = Objects.requireNonNull(sessionId);
        Optional<ManagedSessionId> previous = activeSession;
        if (previous.equals(updated)) {
            return;
        }
        activeSession = updated;
        notify(listener -> listener.activeSessionChanged(previous, updated));
    }

    // ---- Helpers -------------------------------------------------------------

    private void notify(Consumer<Listener> event) {
        for (Listener listener : listeners) {
            event.accept(listener);
        }
    }
}
