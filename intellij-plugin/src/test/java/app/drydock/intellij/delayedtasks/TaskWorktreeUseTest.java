package app.drydock.intellij.delayedtasks;

import app.drydock.delayedtasks.SessionTaskCompletion;
import app.drydock.delayedtasks.TaskWorktreeUse;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskRun;
import app.drydock.tasks.TaskRuns;
import app.drydock.tasks.TaskStatus;
import app.drydock.tasks.TaskStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T41b committed matrix: the accepted T41a prerequisite-worktree guard
 * ({@link TaskWorktreeUse}) over real durable stores -- actual run receipts
 * and task documents under {@code @TempDir} roots, no mocking, per the plan's
 * eight matrix items plus the warn-then-claim ordering. Honest seams: the
 * resolver's only liveness input is the caller's live-session set (T07:
 * catalog membership, idle or stopped -- process state is invisible here, so
 * "stopped" is pinned as a held claim whose catalog row still stands); the
 * Session Done dialog flow and the pending-removal marker's auto-proceed
 * consumer are later plan tasks and stay smoke-covered, not this fixture's.
 */
class TaskWorktreeUseTest {

    /** Matrix 1: DONE advancement and removal gating are separate. The DONE
     * write commits on disk while the dependent's claim still holds the
     * prerequisite context; the guard answers from receipts, never statuses,
     * and the release leaves the DONE committed. */
    @Test
    void doneAdvancementAndRemovalGatingAreSeparate(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        RepositoryId repo = RepositoryId.newId();
        try (TaskStore tasks = new TaskStore(store); TaskRuns runs = new TaskRuns(store)) {
            TaskDocument dependent = tasks.create(repo, "Dependent follow up", List.of("sess-live"));
            claim(runs, dependent.id(), "sess-live", dir.resolve("dependent-wt"), prereq, owner(store));
            tasks.update(dependent.id(),
                    new TaskFilePatch.Updates(null, TaskStatus.DONE, null, null, null, null));
            assertTrue(Files.readString(taskFile(store, repo, dependent.id())).contains("status: done"),
                    "the DONE write committed on disk");
            TaskWorktreeUse.Use use = TaskWorktreeUse.resolve(store, prereq, Set.of("sess-live"));
            assertEquals(1, use.live().size(), "a done dependent still holds its context claim");
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of("sess-live")).orElse("")
                    .contains("a live dependent session still uses it"),
                    "removal is gated by the claim, separately from the status");
            runs.forget(dependent.id()); // the release; the DONE write stays committed
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of("sess-live")).isEmpty());
            assertTrue(Files.readString(taskFile(store, repo, dependent.id())).contains("status: done"),
                    "statuses and claims are separate steps");
        }
    }

    /** Matrix 2: forgotten history stays resolvable for the guard and does
     * not block, and a task's own worktree row claims no prerequisite
     * context -- only consumer paths do. */
    @Test
    void forgottenHistoryStaysResolvableAndNonBlocking(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        TaskId source = TaskId.of("prereq-task");
        TaskId dependent = TaskId.of("dependent-forgotten");
        try (TaskRuns runs = new TaskRuns(store)) {
            runs.reserve(source, "sess-d", prereq, owner(store), List.of()); // own worktree, no consumers
            claim(runs, dependent, "sess-d", dir.resolve("dependent-wt"), prereq, owner(store));
            runs.forget(dependent); // the explicit release keeps the bounded history rows
            TaskWorktreeUse.Use use = TaskWorktreeUse.resolve(store, prereq, Set.of("sess-d"));
            assertTrue(use.live().isEmpty() && use.retained().isEmpty(), "nothing still claims the path");
            assertEquals(1, use.released().size(), "the own-worktree row resolves as no consumer claim");
            assertFalse(use.released().get(0).claimHeld());
            assertEquals(dependent, use.released().get(0).task());
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of("sess-d")).isEmpty(),
                    "forgotten history does not block the removal");
            try (TaskRuns fresh = new TaskRuns(store)) { // the bounded history answers a reopen
                TaskRun row = fresh.byTask(dependent).orElseThrow();
                assertEquals(TaskRun.Phase.FORGET, row.phase());
                assertTrue(row.consumers().contains(prereq), "the history still names the context");
            }
        }
    }

    /** Matrix 3: a stopped dependent still blocks (P4: only an explicit forget
     * releases, never idle or stop); a session that left the catalog leaves a
     * retained orphan, deliberately non-blocking. Liveness here is the
     * caller's catalog set -- process state is not an input this seam sees. */
    @Test
    void stoppedConsumerStillBlocksWhileItsCatalogRowStands(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        RepositoryId repo = RepositoryId.newId();
        try (TaskStore tasks = new TaskStore(store); TaskRuns runs = new TaskRuns(store)) {
            TaskDocument dependent = tasks.create(repo, "Held follow up", List.of("sess-held"));
            claim(runs, dependent.id(), "sess-held", dir.resolve("dependent-wt"), prereq, owner(store));
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of("sess-held")).orElse("")
                    .contains("a live dependent session still uses it"),
                    "a stopped row in the catalog still holds the claim (idle/stop never releases)");
            TaskWorktreeUse.Use orphan = TaskWorktreeUse.resolve(store, prereq, Set.of());
            assertTrue(orphan.live().isEmpty(), "no catalog row stands behind the claim");
            assertEquals(1, orphan.retained().size(), "the vanished session leaves a retained orphan");
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of()).isEmpty(),
                    "a retained orphan never blocks removal (accepted deviation)");
        }
    }

    /** Matrix 4: the main checkout gets no special case in the resolver -- a
     * live claim naming it as consumer directory gates it exactly like a
     * linked worktree. The dialog's own main-checkout blockers live upstream
     * in SessionDonePlan and stay smoke-covered. */
    @Test
    void mainCheckoutIsGuardedByReceiptsLikeAnyWorktree(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path main = Files.createDirectories(dir.resolve("repo-main"));
        TaskId dependent = TaskId.of("main-dependent");
        try (TaskRuns runs = new TaskRuns(store)) {
            claim(runs, dependent, "sess-m", dir.resolve("dependent-wt"), main, owner(store));
            assertEquals(1, TaskWorktreeUse.resolve(store, main, Set.of("sess-m")).live().size());
            assertTrue(TaskWorktreeUse.guard(store, main, Set.of("sess-m")).orElse("")
                    .contains("a live dependent session still uses it"),
                    "the main checkout is refused by the same receipt claim");
        }
    }

    /** Matrix 5: a path removed underneath while receipts still promise it
     * surfaces missing context visibly, ordered BEFORE the live refusal --
     * never a silent success. Even forgotten-only history keeps it visible. */
    @Test
    void externalRemovalSurfacesMissingContextFirst(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        TaskId dependent = TaskId.of("gone-dependent");
        try (TaskRuns runs = new TaskRuns(store)) {
            claim(runs, dependent, "sess-g", dir.resolve("dependent-wt"), prereq, owner(store));
            Files.delete(prereq); // removed outside Drydock, underneath the receipts
            String refusal = TaskWorktreeUse.guard(store, prereq, Set.of("sess-g")).orElse("");
            assertTrue(refusal.contains("already gone from disk"), refusal);
            assertFalse(refusal.contains("a live dependent session still uses it"),
                    "the missing-context refusal is ordered first");
            runs.forget(dependent); // history-only now, still visible
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of("sess-g")).orElse("")
                    .contains("already gone from disk"), "promised history keeps the refusal visible");
        }
    }

    /** Matrix 6: a path the receipts cannot speak for is never guessed clean
     * -- a registry that cannot be read refuses visibly. A path with no
     * receipt provenance at all resolves clean: nothing promised it. */
    @Test
    void unreadableProvenanceRefusesVisiblyNeverGuessesClean(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        Path unclaimed = Files.createDirectories(dir.resolve("unclaimed-wt"));
        RepositoryId repo = RepositoryId.newId();
        try (TaskStore tasks = new TaskStore(store); TaskRuns runs = new TaskRuns(store)) {
            TaskDocument dependent = tasks.create(repo, "Torn receipts", List.of("sess-t"));
            claim(runs, dependent.id(), "sess-t", dir.resolve("dependent-wt"), prereq, owner(store));
            assertTrue(TaskWorktreeUse.guard(store, unclaimed, Set.of("sess-t")).isEmpty(),
                    "no receipt provenance claims the unclaimed path: it resolves clean");
            Path receipts = store.resolve("runs").resolve(dependent.id().text() + ".run");
            String text = Files.readString(receipts);
            Files.writeString(receipts, text.substring(0, text.lastIndexOf("end " + dependent.id().text())));
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of("sess-t")).orElse("")
                    .contains("could not verify"),
                    "an unreadable registry refuses visibly instead of guessing unconsumed");
        }
    }

    /** Matrix 7, verified exactly as T41a does it: dropping a prerequisite
     * from the task document never releases its claims -- the receipts are
     * the provenance, not the front matter -- and not even deleting the
     * document does while its session is live; the explicit FORGET is the
     * only release (P4). */
    @Test
    void droppedPrerequisiteLinkNeverReleasesOnlyForgetDoes(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        RepositoryId repo = RepositoryId.newId();
        try (TaskStore tasks = new TaskStore(store); TaskRuns runs = new TaskRuns(store)) {
            TaskDocument dependent = tasks.create(repo, "Kept dependency", List.of("sess-k"));
            tasks.update(dependent.id(), new TaskFilePatch.Updates(null, null, null, null,
                    List.of("prereq-task"), null)); // the document names the prerequisite...
            claim(runs, dependent.id(), "sess-k", dir.resolve("dependent-wt"), prereq, owner(store));
            Set<String> live = Set.of("sess-k");
            assertTrue(TaskWorktreeUse.guard(store, prereq, live).isPresent());
            tasks.update(dependent.id(),
                    new TaskFilePatch.Updates(null, null, null, null, List.of(), null)); // ...dropped
            assertFalse(Files.readString(taskFile(store, repo, dependent.id())).contains("prereq-task"),
                    "the link is gone from the document");
            assertTrue(TaskWorktreeUse.guard(store, prereq, live).isPresent(),
                    "the document edit released nothing");
            Files.delete(taskFile(store, repo, dependent.id())); // the whole document dropped
            assertTrue(TaskWorktreeUse.guard(store, prereq, live).isPresent(),
                    "a live session behind the receipt still blocks");
            runs.forget(dependent.id());
            assertTrue(TaskWorktreeUse.guard(store, prereq, live).isEmpty(),
                    "the explicit FORGET is the only release");
        }
    }

    /** Matrix 8: two dependent sessions each hold a claim; both must be
     * forgotten before the removal proceeds, and the refusal names each. */
    @Test
    void twoConsumersEachMustBeForgottenBeforeRemoval(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        TaskId first = TaskId.of("dependent-one");
        TaskId second = TaskId.of("dependent-two");
        try (TaskRuns runs = new TaskRuns(store)) {
            claim(runs, first, "sess-1", dir.resolve("wt-one"), prereq, owner(store));
            claim(runs, second, "sess-2", dir.resolve("wt-two"), prereq, owner(store));
            String both = TaskWorktreeUse.guard(store, prereq, Set.of("sess-1", "sess-2")).orElse("");
            assertTrue(both.contains("dependent-one") && both.contains("dependent-two"), both);
            runs.forget(first);
            String remaining = TaskWorktreeUse.guard(store, prereq, Set.of("sess-1", "sess-2")).orElse("");
            assertTrue(remaining.contains("dependent-two"), remaining);
            assertFalse(remaining.contains("dependent-one"), remaining);
            runs.forget(second);
            assertTrue(TaskWorktreeUse.guard(store, prereq, Set.of("sess-1", "sess-2")).isEmpty(),
                    "after the last forget the removal proceeds");
        }
    }

    /** The warn-then-claim ordering, as far as this seam reaches: a consumer
     * arriving between the pre-dialog warning and the claim is caught by the
     * recheck taken under the claim -- the post-claim receipt re-read is the
     * TOCTOU closure -- and the claim's marker holds exactly while held. The
     * marker's auto-proceed consumer is a later plan task. */
    @Test
    void lateConsumerBetweenWarningAndClaimIsCaughtByTheRecheck(@TempDir Path dir) throws Exception {
        Path store = storeRoot(dir);
        Path prereq = Files.createDirectories(dir.resolve("prereq-wt"));
        TaskId dependent = TaskId.of("late-dependent");
        Set<String> live = Set.of("sess-late");
        try (TaskRuns runs = new TaskRuns(store)) {
            assertTrue(TaskWorktreeUse.guard(store, prereq, live).isEmpty(),
                    "the pre-dialog warning finds nothing yet");
            runs.reserve(dependent, "sess-late", dir.resolve("late-wt"), owner(store), List.of(prereq));
            try (TaskWorktreeUse.RemovalClaim claim = TaskWorktreeUse.claimRemoval(prereq, "T41b fixture")) {
                assertTrue(SessionTaskCompletion.worktreeRemovalPending(prereq),
                        "the claim reaches the T30a pending-removal marker");
                String recheck = TaskWorktreeUse.guard(store, prereq, live).orElse("");
                assertTrue(recheck.contains("a live dependent session still uses it"),
                        "the late consumer blocks the removal under the claim: " + recheck);
            }
            assertFalse(SessionTaskCompletion.worktreeRemovalPending(prereq), "close released the claim");
            assertTrue(Files.isDirectory(prereq), "the refusal kept the folder");
        }
    }

    // ---- fixtures ----------------------------------------------------------

    /** One live claim: reserve then launch, binding the session and the
     * prerequisite path as the receipt's consumer directory. */
    private static void claim(TaskRuns runs, TaskId task, String session, Path own, Path context, Path owner)
            throws TaskRuns.TaskRunsException {
        runs.reserve(task, session, own, owner, List.of(context));
        runs.launch(task, session);
    }

    /** The durable store root: real receipts under runs/, real documents under repository dirs. */
    private static Path storeRoot(Path dir) throws IOException {
        return Files.createDirectories(dir.resolve("store"));
    }

    /** An owner-catalog directory that exists, so the row's transitions stay writable. */
    private static Path owner(Path store) throws IOException {
        return Files.createDirectories(store.resolve(RepositoryId.newId().toString()));
    }

    /** The committed document path of one task: <store>/<repository-id>/<id>.md. */
    private static Path taskFile(Path store, RepositoryId repo, TaskId id) {
        return store.resolve(repo.toString()).resolve(id.text() + ".md");
    }
}
