package app.drydock.delayedtasks;

import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFiles;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskRun;
import app.drydock.tasks.TaskRuns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The prerequisite-worktree lifetime resolver and deletion guard (T41,
 * D13/P4): which tasks and sessions a worktree still serves as CONTEXT,
 * resolved from the RETAINED run receipts -- provenance that survives
 * catalog deletion -- plus the just-before-deletion recheck that keeps a
 * dependent launch from slipping between a removal warning and the remove
 * itself.
 *
 * <p>Resolution answers from receipts only, never from live agent state:
 * a {@code live} user is a live catalog session (T07 -- idle or stopped,
 * the process may be either) holding a live claim (a PREPARE or LAUNCH
 * row, P4), directly bound or through its task's sessions; a {@code
 * retained} claim is held with no live session left anywhere (an orphan
 * a vanished owner can no longer release -- T27b's refused-release
 * semantics are untouched, so removal stays possible rather than
 * blocked forever); {@code released} is forgotten history, still
 * resolvable through the T27a bounded rows. A claim is released by an
 * explicit FORGET only -- never by process idle or stop.
 *
 * <p>The guard is ordered for the TOCTOU window: warn (the caller's
 * dialog), then claim ({@link #claimRemoval}, after the warning), then
 * {@link #guard} -- the recheck -- and only then delete. A consumer that
 * arrives between the warning and the claim is caught by the recheck and
 * blocks the removal. A registry that cannot be read never reads as
 * unconsumed: the recheck refuses visibly instead of guessing. A path
 * that vanished underneath while receipts still promise it as context is
 * a visible missing-context failure, never a silent success.
 *
 * <p>Threading: every method is blocking file I/O (receipt scan, store
 * scan, directory probe) and runs off the FX application thread
 * (AGENTS.md). Nothing here writes.
 */
public final class TaskWorktreeUse {

    /** One resolved use of a worktree: the consuming task, its bound session, whether the claim is held. */
    public record Consumer(TaskId task, String session, boolean claimHeld) {
    }

    /**
     * The receipts' answer for one worktree: live users block a removal,
     * retained orphans and released history do not (the folder may go;
     * the history still names what used it).
     */
    public record Use(List<Consumer> live, List<Consumer> retained, List<Consumer> released) {
        public Use {
            live = List.copyOf(live);
            retained = List.copyOf(retained);
            released = List.copyOf(released);
        }
    }

    private TaskWorktreeUse() {
    }

    /**
     * Resolves every receipt that names {@code worktree} as a consumer
     * directory: each task whose LATEST row claims it, classified by the
     * claim's phase and whether a live catalog session still stands behind
     * it. The latest row is read through the registry's own authoritative
     * lookup ({@link TaskRuns#byTask}), which refuses a torn record
     * visibly; candidates come from the live sessions' retained rows and
     * from the task store's documents, so provenance that outlived either
     * is still found. Blocking I/O, off the FX thread.
     */
    public static Use resolve(Path storeRoot, Path worktree, Set<String> liveSessions) {
        Objects.requireNonNull(storeRoot, "storeRoot");
        Objects.requireNonNull(worktree, "worktree");
        Set<String> live = Set.copyOf(Objects.requireNonNull(liveSessions, "liveSessions"));
        Path path = worktree.toAbsolutePath().normalize();
        Map<TaskId, Set<String>> taskSessions = taskSessions(storeRoot);
        Set<TaskId> candidates = new LinkedHashSet<>(taskSessions.keySet());
        List<Consumer> liveUsers = new ArrayList<>();
        List<Consumer> retained = new ArrayList<>();
        List<Consumer> released = new ArrayList<>();
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            for (String session : live) {
                for (TaskRun row : runs.bySession(session)) {
                    candidates.add(row.task());
                }
            }
            for (TaskId task : candidates) {
                TaskRun latest = runs.byTask(task).orElse(null);
                if (latest == null || !latest.consumers().contains(path)) {
                    continue;
                }
                Consumer use = new Consumer(task, latest.session(), latest.live());
                if (!latest.live()) {
                    released.add(use); // FORGET or FAILURE: history, not a claim
                } else if (servesLiveSession(latest, taskSessions.get(task), live)) {
                    liveUsers.add(use);
                } else {
                    retained.add(use); // held, but no live session anywhere behind it
                }
            }
        } catch (TaskRuns.TaskRunsException e) {
            throw new UseRefusal("The run receipts on " + storeRoot + " could not be read while resolving the"
                    + " users of " + path + ": " + e.getMessage(), e);
        }
        return new Use(liveUsers, retained, released);
    }

    /**
     * The just-before-deletion recheck, taken AFTER the removal claim so a
     * consumer that arrived between the warning and the claim is caught:
     * empty when the removal may proceed, otherwise the visible refusal --
     * a live dependent session still uses the path, the receipts cannot be
     * read (never guessed clean), or the path vanished underneath while
     * receipts still name it as prerequisite context. Never throws.
     */
    public static Optional<String> guard(Path storeRoot, Path worktree, Set<String> liveSessions) {
        Path path = Objects.requireNonNull(worktree, "worktree").toAbsolutePath().normalize();
        Use use;
        try {
            use = resolve(storeRoot, path, liveSessions);
        } catch (UseRefusal refusal) {
            return Optional.of("could not verify that no live session uses " + path + " as prerequisite context ("
                    + refusal.getMessage() + ")");
        }
        List<Consumer> promised = new ArrayList<>(use.live());
        promised.addAll(use.retained());
        promised.addAll(use.released());
        if (!Files.isDirectory(path) && !promised.isEmpty()) {
            return Optional.of(path + " is already gone from disk, but its receipts still name it as"
                    + " prerequisite context (" + names(promised) + "); those sessions' context is missing");
        }
        if (!use.live().isEmpty()) {
            return Optional.of("a live dependent session still uses it as prerequisite context ("
                    + names(use.live()) + "); its task advances, the folder stays until the claim is"
                    + " released by an explicit forget");
        }
        return Optional.empty();
    }

    /**
     * Claims {@code worktree} for removal -- acquired AFTER the warning,
     * BEFORE the recheck and the delete -- so a next-stage launch that
     * consults pending removals cannot slip into the window. The claim is
     * the T30a pending-removal marker, held through a claim-only engine;
     * closing releases it when the removal attempt has run, one way or
     * another.
     */
    public static RemovalClaim claimRemoval(Path worktree, String owner) {
        Path path = Objects.requireNonNull(worktree, "worktree").toAbsolutePath().normalize();
        SessionTaskCompletion claim = SessionTaskCompletion.captureFailed(
                Objects.requireNonNull(owner, "owner"), path, "standalone worktree removal");
        claim.reservePendingWorktreeRemoval();
        return new RemovalClaim(claim);
    }

    /** A held removal claim; close releases it. Never throws. */
    public static final class RemovalClaim implements AutoCloseable {

        private final SessionTaskCompletion claim;

        private RemovalClaim(SessionTaskCompletion claim) {
            this.claim = claim;
        }

        @Override
        public void close() {
            claim.releaseWorktreeRemovalReservation();
        }
    }

    /** One visible resolution refusal: an unreadable receipt registry or task store, never guessed past. */
    public static final class UseRefusal extends RuntimeException {

        public UseRefusal(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---- internals ---------------------------------------------------------

    /** The store's documents by task: id to live-catalog candidate sessions. */
    private static Map<TaskId, Set<String>> taskSessions(Path storeRoot) {
        Map<TaskId, Set<String>> sessions = new LinkedHashMap<>();
        try {
            for (TaskDocument document : TaskFiles.scan(storeRoot).documents()) {
                sessions.put(document.id(), Set.copyOf(document.sessions()));
            }
        } catch (IOException e) {
            throw new UseRefusal("The task store on " + storeRoot + " could not be scanned while resolving the"
                    + " worktree users: " + e, e);
        }
        return sessions;
    }

    /** Whether a live claim still serves a live session: its own bound session, or any of its task's. */
    private static boolean servesLiveSession(TaskRun row, Set<String> taskSessions, Set<String> live) {
        if (!row.session().isEmpty() && live.contains(row.session())) {
            return true;
        }
        return taskSessions != null && taskSessions.stream().anyMatch(live::contains);
    }

    /** "task 'x' of session y" -- the session named only when the row carries one. */
    private static String names(List<Consumer> users) {
        List<String> parts = new ArrayList<>();
        for (Consumer user : users) {
            parts.add("task '" + user.task().text() + "'"
                    + (user.session().isEmpty() ? "" : " of session " + user.session()));
        }
        return String.join(", ", parts);
    }
}
