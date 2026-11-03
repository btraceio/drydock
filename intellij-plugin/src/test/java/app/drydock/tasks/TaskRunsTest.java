package app.drydock.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance fixtures for the durable run receipt registry (T27b, D10/D13/P4):
 * the accepted {@link TaskRun}/{@link TaskRunFiles}/{@link TaskRuns} production
 * behavior over real files under {@code @TempDir} -- no mocking framework, no
 * faked state. Ports the T27a smoke matrix: the reserve/launch/forget
 * progression with bounded retention, duplicate and contention refusals with
 * exact reasons, torn-tail quarantine blocking only the affected task,
 * stale-callback visible no-ops, consumer lookups surviving catalog deletion,
 * foreign-owner read-only history, the KNOWN-DEFERRED vanished-owner live
 * PREPARE release refusal pinned as-is, and the schema-version guard.
 */
class TaskRunsTest {

    @TempDir
    Path root;

    /** An owner-catalog path that really exists, so rows start live-owned. */
    private Path catalog(String name) throws IOException {
        Path file = root.resolve(name);
        Files.writeString(file, "catalog", StandardCharsets.UTF_8);
        return file;
    }

    private Path receiptFile(TaskId task) {
        return root.resolve("runs").resolve(task.text() + ".run");
    }

    private TaskRun reserve(TaskRuns runs, TaskId task, String session, Path worktree, Path owner)
            throws TaskRuns.TaskRunsException {
        return runs.reserve(task, session, worktree, owner, List.of());
    }

    /** An attempt that may refuse visibly; the checked exception fits assertThrows' executable. */
    private interface Attempt {
        void attempt() throws Exception;
    }

    private static TaskRuns.TaskRunsException refused(Attempt attempt) {
        return assertThrows(TaskRuns.TaskRunsException.class, attempt::attempt);
    }

    /** Matrix 1: reserve -> launch -> forget records the phase/timestamp progression with bounded retention. */
    @Test
    void reserveLaunchForgetProgressionKeepsBoundedHistory() throws Exception {
        TaskId task = TaskId.of("round-trip");
        Path worktree = root.resolve("wt-a");
        Path owner = catalog("catalog-a");
        try (TaskRuns runs = new TaskRuns(root)) {
            TaskRun prepared = reserve(runs, task, "sess-a", worktree, owner);
            assertEquals(TaskRun.Phase.PREPARE, prepared.phase());
            TaskRun launched = runs.launch(task, "sess-a");
            assertEquals(TaskRun.Phase.LAUNCH, launched.phase());
            assertFalse(launched.at().isBefore(prepared.at()));
            TaskRun forgotten = runs.forget(task).orElseThrow();
            assertEquals(TaskRun.Phase.FORGET, forgotten.phase());
            assertFalse(forgotten.at().isBefore(launched.at()));
            assertEquals(TaskRun.Phase.FORGET, runs.byTask(task).orElseThrow().phase());

            for (int cycle = 0; cycle < 5; cycle++) { // 3 + 10 rows written: past the retention cap
                reserve(runs, task, "sess-a", worktree, owner);
                runs.forget(task);
            }
            List<TaskRun> rows = runs.bySession("sess-a");
            assertEquals(TaskRun.MAX_ROWS_PER_TASK, rows.size()); // oldest dropped, 8 kept
            assertFalse(rows.contains(prepared)); // the first PREPARE fell off the bound
        }
    }

    /** Matrix 2: a duplicate reservation and a competing live reservation refuse with the exact reasons. */
    @Test
    void duplicateAndContentionRefuseWithExactReasons() throws Exception {
        TaskId task = TaskId.of("reserve-twice");
        Path worktree = root.resolve("wt-b");
        Path owner = catalog("catalog-b");
        try (TaskRuns runs = new TaskRuns(root)) {
            reserve(runs, task, "sess-first", worktree, owner);
            assertEquals("Task 'reserve-twice' is already reserved (PREPARE by session sess-first, worktree "
                    + worktree + "); no two live plans may reserve one task",
                    refused(() -> reserve(runs, task, "sess-first", worktree, owner)).getMessage());
            runs.launch(task, "sess-first");
            assertEquals("Task 'reserve-twice' is already reserved (LAUNCH by session sess-first, worktree "
                    + worktree + "); no two live plans may reserve one task",
                    refused(() -> reserve(runs, task, "sess-second", worktree, owner)).getMessage());
            runs.forget(task); // terminal history never blocks a fresh run
            assertEquals(TaskRun.Phase.PREPARE, reserve(runs, task, "sess-second", worktree, owner).phase());
        }
    }

    /** Matrix 3: a torn record blocks only its own task until a release quarantines it; nothing is deleted. */
    @Test
    void tornRecordBlocksOnlyItsTaskUntilQuarantined() throws Exception {
        TaskId affected = TaskId.of("torn-affected");
        TaskId other = TaskId.of("torn-other");
        Path worktree = root.resolve("wt-c");
        Path owner = catalog("catalog-c");
        try (TaskRuns runs = new TaskRuns(root)) {
            reserve(runs, affected, "sess-torn", worktree, owner);
            runs.launch(affected, "sess-torn");
            runs.forget(affected);
            reserve(runs, affected, "sess-torn", worktree, owner);
            reserve(runs, other, "sess-other", root.resolve("wt-c2"), owner);

            Path file = receiptFile(affected); // a torn write: cut before the last record's end guard
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Files.writeString(file, text.substring(0, text.lastIndexOf("end " + affected.text())),
                    StandardCharsets.UTF_8);

            assertEquals("Task 'torn-affected' cannot start: its receipt file " + file
                    + " has a torn record; release it (fail or forget) to quarantine the record first",
                    refused(() -> reserve(runs, affected, "sess-torn", worktree, owner)).getMessage());
            assertEquals("Task 'torn-affected' has a torn receipt record in " + file
                    + "; its lookups refuse until the record is released and quarantined",
                    refused(() -> runs.byTask(affected)).getMessage());
            assertTrue(refused(() -> runs.bySession("sess-torn")).getMessage().contains("cannot be read"));
            assertEquals(TaskRun.Phase.PREPARE, runs.byTask(other).orElseThrow().phase()); // unaffected
            assertEquals(1, runs.bySession("sess-other").size()); // non-empty lookups still resolve
            assertEquals(TaskRun.Phase.PREPARE, reserve(runs, TaskId.of("torn-third"), "sess-third",
                    root.resolve("wt-c3"), owner).phase()); // other starts are not blocked

            runs.forget(affected); // the release quarantines the torn record, conservatively
            Path sidecar = root.resolve("runs").resolve(affected.text() + ".run.torn");
            assertTrue(Files.exists(sidecar)); // kept aside, never silently deleted
            assertTrue(Files.readString(sidecar, StandardCharsets.UTF_8).contains("run PREPARE"));
            assertEquals(TaskRun.Phase.FORGET, runs.byTask(affected).orElseThrow().phase());
            assertEquals(TaskRun.Phase.PREPARE, reserve(runs, affected, "sess-torn", worktree, owner).phase());
        }
    }

    /** Matrix 4: launch/fail without a live reservation is a visible no-op: no crash, no file change. */
    @Test
    void staleLaunchAndFailCallbacksAreVisibleNoOps() throws Exception {
        TaskId unknown = TaskId.of("stale-unknown");
        TaskId released = TaskId.of("stale-released");
        TaskId held = TaskId.of("stale-launched");
        Path owner = catalog("catalog-d");
        try (TaskRuns runs = new TaskRuns(root)) {
            assertEquals("Launch completion for task 'stale-unknown' is a no-op: no receipt exists",
                    refused(() -> runs.launch(unknown, "sess-x")).getMessage());
            assertEquals("Failure report for task 'stale-unknown' is a no-op: no live reservation exists",
                    refused(() -> runs.fail(unknown, "late")).getMessage());
            assertFalse(Files.exists(receiptFile(unknown))); // nothing was written for an unknown task

            reserve(runs, released, "sess-stale", root.resolve("wt-d"), owner);
            runs.launch(released, "sess-stale");
            runs.forget(released);
            byte[] before = Files.readAllBytes(receiptFile(released));
            assertEquals("Failure report for task 'stale-released' is a no-op: no live reservation"
                    + " (latest phase FORGET)", refused(() -> runs.fail(released, "late failure")).getMessage());

            reserve(runs, held, "sess-stale", root.resolve("wt-d2"), owner);
            runs.launch(held, "sess-stale");
            byte[] heldBefore = Files.readAllBytes(receiptFile(held));
            assertEquals("Launch completion for task 'stale-launched' is a no-op: latest phase is LAUNCH,"
                    + " not a live PREPARE", refused(() -> runs.launch(held, "sess-stale")).getMessage());
            assertArrayEquals(before, Files.readAllBytes(receiptFile(released))); // no write happened
            assertArrayEquals(heldBefore, Files.readAllBytes(receiptFile(held)));
        }
    }

    /** Matrix 5: session and worktree lookups resolve retained paths after the owner catalog is deleted. */
    @Test
    void consumerLookupsSurviveCatalogDeletion() throws Exception {
        Path owner = catalog("catalog-e");
        Path worktree = root.resolve("wt-e");
        Path consumer = root.resolve("consumer-e");
        TaskId task = TaskId.of("consumer-lookup");
        try (TaskRuns runs = new TaskRuns(root)) {
            runs.reserve(task, "sess-lookup", worktree, owner, List.of(consumer));
            runs.launch(task, "sess-lookup");
            Files.delete(owner); // catalog deletion: rows keep paths, not catalog objects
            List<TaskRun> bySession = runs.bySession("sess-lookup");
            assertEquals(2, bySession.size());
            for (TaskRun row : bySession) {
                assertEquals(worktree, row.worktree());
                assertEquals(owner, row.ownerCatalog());
                assertEquals(List.of(consumer), row.consumers());
            }
            List<TaskRun> byWorktree = runs.byWorktree(worktree);
            assertEquals(2, byWorktree.size());
            assertEquals("sess-lookup", byWorktree.get(1).session()); // the LAUNCH row's bound session
        }
    }

    /** Matrix 6: a terminal row whose owner catalog vanished is read-only history blocking no other task. */
    @Test
    void vanishedOwnerTerminalRowIsReadOnlyHistory() throws Exception {
        TaskId historical = TaskId.of("history-row");
        Path owner = catalog("catalog-f");
        try (TaskRuns runs = new TaskRuns(root)) {
            reserve(runs, historical, "sess-h", root.resolve("wt-f"), owner);
            runs.forget(historical); // terminal history while the catalog still exists
            Files.delete(owner); // then the owner catalog vanishes
            String readOnly = "Task 'history-row' has a read-only history row: its owner catalog "
                    + owner + " no longer exists";
            assertEquals(readOnly, refused(() -> runs.launch(historical, "sess-h")).getMessage());
            assertEquals("Failure report for task 'history-row' is a no-op: no live reservation"
                    + " (latest phase FORGET)", refused(() -> runs.fail(historical, "late")).getMessage());
            assertEquals(readOnly, refused(() -> runs.forget(historical)).getMessage());
            assertEquals(TaskRun.Phase.FORGET, // the row is still readable history
                    runs.byTask(historical).orElseThrow().phase());
            assertEquals(TaskRun.Phase.PREPARE, reserve(runs, TaskId.of("fresh-task"), "sess-new",
                    root.resolve("wt-f2"), catalog("catalog-g")).phase()); // a different task still starts
        }
    }

    /** Matrix 7, PINNED AS-IS: a live PREPARE whose owner catalog vanished cannot be released yet. */
    @Test
    void vanishedOwnerLivePrepareReleaseRefusedPinnedAsIs() throws Exception {
        TaskId task = TaskId.of("pinned-live");
        Path owner = catalog("catalog-h");
        Path worktree = root.resolve("wt-h");
        try (TaskRuns runs = new TaskRuns(root)) {
            reserve(runs, task, "sess-p", worktree, owner);
            Files.delete(owner); // the owner vanishes while the reservation is still live
            // KNOWN DEFERRED (T27a checker note): the explicit stale sweep is a later task's
            // job. These assertions pin the CURRENT refused-release semantics as the baseline
            // that sweep will change; do not "fix" them here.
            String readOnly = "Task 'pinned-live' has a read-only history row: its owner catalog "
                    + owner + " no longer exists";
            assertEquals(readOnly, refused(() -> runs.fail(task, "owner gone")).getMessage());
            assertEquals(readOnly, refused(() -> runs.forget(task)).getMessage());
            assertEquals(TaskRun.Phase.PREPARE, runs.byTask(task).orElseThrow().phase()); // still live
            assertEquals("Task 'pinned-live' is already reserved (PREPARE by session sess-p, worktree "
                    + worktree + "); no two live plans may reserve one task",
                    refused(() -> reserve(runs, task, "sess-q", worktree, catalog("catalog-i"))).getMessage());
        }
    }

    /** Matrix 8: a wrong or missing schema line refuses loudly; the file is never silently reparsed. */
    @Test
    void wrongOrMissingSchemaLineRefusesLoudly() throws Exception {
        TaskId task = TaskId.of("schema-guard");
        Path file = receiptFile(task);
        String row = "run PREPARE 2026-01-01T00:00:00Z schema-guard\nsession -\nworktree /tmp/wt\n"
                + "owner /tmp/catalog\nnote -\nend schema-guard\n";
        byte[] wrongVersion = ("drydock-run-receipts 2\n" + row).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        Files.write(file, wrongVersion);
        try (TaskRuns runs = new TaskRuns(root)) {
            assertEquals("Task 'schema-guard' has a torn receipt record in " + file
                    + "; its lookups refuse until the record is released and quarantined",
                    refused(() -> runs.byTask(task)).getMessage());
            assertEquals("Task 'schema-guard' cannot start: its receipt file " + file
                    + " has a torn record; release it (fail or forget) to quarantine the record first",
                    refused(() -> reserve(runs, task, "sess-v", root.resolve("wt-v"), catalog("catalog-j")))
                            .getMessage());
            assertArrayEquals(wrongVersion, Files.readAllBytes(file)); // refused, never silently rewritten

            Files.write(file, row.getBytes(StandardCharsets.UTF_8)); // schema line missing entirely
            assertEquals("Task 'schema-guard' has a torn receipt record in " + file
                    + "; its lookups refuse until the record is released and quarantined",
                    refused(() -> runs.byTask(task)).getMessage());
            assertTrue(refused(() -> runs.bySession("anyone")).getMessage().contains("cannot be read"));
            assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).startsWith("run PREPARE"));
        }
    }
}
