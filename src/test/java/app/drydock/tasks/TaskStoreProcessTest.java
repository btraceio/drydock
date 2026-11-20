package app.drydock.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The T43a committed cross-process matrix: each case launches genuine
 * second JVMs through {@link TaskStoreProcessFixture} that contend on one
 * temporary task root (no home store, no network) over the real
 * TaskStore/PlanStore/TaskRuns: (1) two JVMs creating, updating and
 * note-appending concurrently lose nothing and tear nothing; (2) the
 * T05c-deferred OS-lock foreign-holder timeout -- a child holding the
 * lock file makes the other process fail with the bounded
 * "locked by another Drydock" failure, not the same-JVM-only
 * OverlappingFileLockException; (3) one driver per plan across processes
 * -- the second claim is refused visibly; (4) a driver killed mid-flight
 * leaves a state the next process recovers per the T36a semantics: the
 * claim is re-acquirable, the RUNNING plan is durably paused with no
 * auto-resume, and the live receipt refuses a duplicate reservation.
 * What stays for T43b (the end-to-end IDE-driven suite): human validation
 * through the tool surface, watcher refresh, the controlled launcher, and
 * the published tree state.
 */
class TaskStoreProcessTest {

    @TempDir
    Path dir;

    private TaskStoreProcessFixture fixture() {
        TaskStoreProcessFixture fixture = TaskStoreProcessFixture.locate(dir);
        assumeTrue(fixture != null, "no java executable to start a second process");
        return fixture;
    }

    /** The temp task root both processes contend on, created up front: the
     * store's first mutation scans the root before any write creates it. */
    private Path tasksRoot() throws IOException {
        return Files.createDirectories(dir.resolve("tasks"));
    }

    @Test
    void twoJvmsContendingOnOneRootLoseNothingAndTearNothing() throws Exception {
        TaskStoreProcessFixture fixture = fixture();
        Path root = tasksRoot();
        TaskStoreProcessFixture.Child alpha = fixture.spawn("writer", root.toString(), "alpha", "8");
        TaskStoreProcessFixture.Child beta = fixture.spawn("writer", root.toString(), "beta", "8");

        assertEquals(0, alpha.complete("the alpha writer", 90_000));
        assertEquals(0, beta.complete("the beta writer", 90_000));
        for (TaskStoreProcessFixture.Child child : List.of(alpha, beta)) {
            List<String> written = child.lines();
            assertEquals(8, written.size(), "one OK line per task: " + written);
            assertTrue(written.stream().allMatch(line -> line.startsWith("OK ")),
                    "no write may be lost or refused: " + written);
        }

        TaskSnapshot snapshot = TaskFiles.scan(root);
        assertTrue(snapshot.failures().isEmpty(),
                () -> "no torn or unreadable document: " + snapshot.failures());
        assertTrue(snapshot.idCollisions().isEmpty(), "no duplicate id across the two JVMs");
        assertEquals(16, snapshot.documents().size());
        for (TaskDocument document : snapshot.documents()) {
            assertEquals(TaskStatus.STARTED, document.status(), "the status update survived");
            assertTrue(document.rawBody().contains("note from "), "the note append survived");
        }
    }

    @Test
    void aForeignLockHolderFailsTheOtherProcessBounded() throws Exception {
        TaskStoreProcessFixture fixture = fixture();
        Path root = tasksRoot();
        TaskStoreProcessFixture.Child holder = fixture.spawn("lock-holder", root.toString(), "20000");
        assertTrue(holder.printed("HELD", 30_000), "the holder must take the OS lock first: "
                + holder.lines());

        TaskStoreProcessFixture.Child attempt = fixture.spawn("task-op", root.toString());
        assertEquals(0, attempt.complete("the blocked writer", 60_000));
        List<String> lines = attempt.lines();
        String failure = lines.stream().filter(line -> line.startsWith("ERR ")).findFirst().orElse("");
        assertTrue(failure.contains("is locked by another Drydock"),
                "the bounded foreign-lock failure, never a hang: " + lines);
        assertFalse(failure.contains("OverlappingFileLock"),
                "OverlappingFileLockException is same-JVM only (T05c): " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("CREATED")),
                "no task may be written under the foreign lock: " + lines);
        assertEquals(0, TaskFiles.scan(root).documents().size(), "the root stayed untouched");
        holder.kill("the lock holder");
    }

    @Test
    void oneDriverPerPlanWinsAcrossTwoProcesses() throws Exception {
        TaskStoreProcessFixture fixture = fixture();
        Path root = tasksRoot();
        TaskStoreProcessFixture.Child holder = fixture.spawn("claim-holder", root.toString(),
                "alpha-target");
        assertTrue(holder.printed("CLAIMED", 30_000),
                "the first driver must take the claim first: " + holder.lines());

        TaskStoreProcessFixture.Child second = fixture.spawn("claim-attempt", root.toString(),
                "alpha-target");
        assertEquals(0, second.complete("the second driver", 60_000));
        List<String> lines = second.lines();
        String refusal = lines.stream().filter(line -> line.startsWith("ERR ")).findFirst().orElse("");
        assertTrue(refusal.contains("already driven by another Drydock process"),
                "the cross-process refusal names the OS-locked claim: " + lines);
        assertTrue(refusal.contains("one driver per plan"), lines.toString());
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("CREATED-CLAIM")),
                "the second process must not win the claim: " + lines);
        holder.kill("the claim holder");
    }

    @Test
    void aCrashedDriverIsRecoveredPausedWithNoDuplicateLaunch() throws Exception {
        TaskStoreProcessFixture fixture = fixture();
        Path root = tasksRoot();
        TaskStoreProcessFixture.Child driver = fixture.spawn("plan-driver", root.toString(),
                "alpha-target");
        assertTrue(driver.printed("SAVED rev=1 paused=false", 30_000),
                "the driver must save the RUNNING plan and reserve its receipt first: "
                        + driver.lines());

        driver.kill("the crashed driver");
        PlanStore successor = new PlanStore(root);
        try {
            PlanStore.Claim recovered = successor.acquireClaim(TaskId.of("alpha-target"))
                    .get(20, TimeUnit.SECONDS);
            try {
                PlanStore.PlanRecord paused = successor.load(TaskId.of("alpha-target"))
                        .get(20, TimeUnit.SECONDS).orElseThrow();
                assertEquals(2, paused.revision(), "the durable pause is a persisted save");
                assertTrue(paused.paused(), "restart is a human checkpoint: no auto-resume");
                assertEquals(RunningPlan.Status.RUNNING, paused.plan().status());
            } finally {
                recovered.close();
            }
        } finally {
            successor.close();
        }
        try (TaskRuns runs = new TaskRuns(root)) {
            TaskRuns.TaskRunsException refused = assertThrows(TaskRuns.TaskRunsException.class,
                    () -> runs.reserve(TaskId.of("alpha-target"), "successor-session",
                            root.resolveSibling("wt"), root.resolveSibling("owner-catalog.json"),
                            List.of()));
            assertTrue(refused.getMessage().contains("already reserved"),
                    "the crashed driver's live receipt blocks a duplicate launch: " + refused);
        }
    }
}
