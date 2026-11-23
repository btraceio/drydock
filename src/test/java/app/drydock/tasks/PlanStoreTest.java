package app.drydock.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T33b acceptance matrix for the accepted {@link PlanStore} and
 * {@link PlanFiles} production behavior (P4.13), the ported T33a smoke:
 * the real store over real files under {@code @TempDir}, plain assertions,
 * no mocking. Covers the byte-stable round trip with revision increments;
 * a torn write quarantined to a kept {@code .torn} sidecar blocking only
 * its own plan; a foreign schema version refused loudly and never
 * quarantined-as-torn; the exact stale-revision CAS refusal with the file
 * untouched; a failed save as the visible no-permission signal the driver
 * consumes; the second-driver refusal in-JVM and against a really
 * OS-locked claim file; and close releasing the claim so re-acquisition
 * durably pauses a RUNNING plan with no auto-resume until the explicit
 * Continue.
 */
class PlanStoreTest {

    private static final RepositoryId REPO =
            RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final PlanLaunch.Origin ORIGIN = new PlanLaunch.Origin("win-1",
            Path.of("/tmp/worktrees/alpha"));

    @TempDir
    Path root;

    /** One RUNNING plan around a single unlaunched stage holding the target. */
    private static RunningPlan runningPlan(String target) {
        TaskId id = TaskId.of(target);
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        selections.put(id, new PlanLaunch.Selection(AgentKind.CLAUDE, REPO, "drydock-plugin"));
        PlanLaunch launch = new PlanLaunch(id, List.of(1), selections, 7L, List.of("launch-a"),
                0, ORIGIN);
        return new RunningPlan(launch, List.of(new RunningPlan.StageState(1, List.of(id), false,
                false)), RunningPlan.Status.RUNNING);
    }

    private static <T> T await(CompletableFuture<T> operation) {
        try {
            return operation.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("The store operation did not complete: " + e, e);
        }
    }

    /** The one visible store failure a refused operation carries (the driver's signal). */
    private static PlanStore.PlanStoreException refusal(CompletableFuture<?> operation) {
        try {
            operation.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PlanStore.PlanStoreException failure) {
                return failure;
            }
            throw new AssertionError("Expected a PlanStoreException, got: " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (TimeoutException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("The operation unexpectedly succeeded");
    }

    @Test
    void roundTripIsByteStableAndTheRevisionIncrements() throws Exception {
        TaskId plan = TaskId.of("alpha-target");
        Path file = new PlanFiles(root).fileOf(plan);
        try (PlanStore store = new PlanStore(root)) {
            PlanStore.Claim claim = await(store.acquireClaim(plan));
            PlanStore.PlanRecord created = await(store.save(claim, 0, runningPlan("alpha-target"),
                    false));
            assertEquals(1, created.revision(), "revision 0 creates at 1");
            PlanStore.PlanRecord loaded = await(store.load(plan)).orElseThrow();
            assertEquals(created, loaded);
            assertEquals(PlanFiles.render(loaded), Files.readString(file, StandardCharsets.UTF_8),
                    "the loaded record renders back as the exact file bytes");
            PlanStore.PlanRecord resaved = await(store.save(claim, 1, loaded.plan(), false));
            assertEquals(2, resaved.revision(), "a successful save increments the revision");
            assertEquals(PlanFiles.render(resaved), Files.readString(file, StandardCharsets.UTF_8),
                    "the re-save is byte-stable too");
        }
    }

    @Test
    void aTornWriteIsQuarantinedAndBlocksOnlyItsOwnPlan() throws Exception {
        TaskId healthy = TaskId.of("alpha-target");
        TaskId torn = TaskId.of("bravo-target");
        PlanFiles files = new PlanFiles(root);
        String tornBytes = "{\"schemaVersion\":1,\"revision\"";
        try (PlanStore store = new PlanStore(root)) {
            PlanStore.Claim healthyClaim = await(store.acquireClaim(healthy));
            await(store.save(healthyClaim, 0, runningPlan("alpha-target"), false));
            Files.createDirectories(files.plansDir());
            Files.writeString(files.fileOf(torn), tornBytes, StandardCharsets.UTF_8);
            assertTrue(await(store.load(healthy)).isPresent(),
                    "a torn plan blocks only itself; the neighbor still loads");
            assertEquals("Plan 'bravo-target' cannot be read: " + files.fileOf(torn)
                    + " does not parse as JSON: Expected ':' at offset " + tornBytes.length(),
                    refusal(store.load(torn)).getMessage());
            PlanStore.Claim tornClaim = await(store.acquireClaim(torn));
            assertEquals("Plan 'bravo-target' cannot be saved: " + files.fileOf(torn)
                    + " does not parse as JSON: Expected ':' at offset " + tornBytes.length(),
                    refusal(store.save(tornClaim, 0, runningPlan("bravo-target"), false))
                            .getMessage());
            assertTrue(await(store.quarantineTorn(tornClaim)), "the torn bytes are quarantined");
            Path sidecar = files.plansDir().resolve("bravo-target.json.torn");
            String kept = Files.readString(sidecar, StandardCharsets.UTF_8);
            assertTrue(kept.startsWith("quarantined "), "the sidecar names itself");
            assertTrue(kept.contains(tornBytes), "the torn bytes are kept, never deleted");
            assertFalse(Files.exists(files.fileOf(torn)), "the main file is removed");
            assertTrue(await(store.load(torn)).isEmpty(), "the plan id is free again");
            assertEquals(1, await(store.save(tornClaim, 0, runningPlan("bravo-target"), false))
                    .revision(), "a fresh create succeeds after the quarantine");
        }
    }

    @Test
    void aForeignSchemaVersionIsRefusedLoudlyAndNeverQuarantined() throws Exception {
        TaskId plan = TaskId.of("alpha-target");
        PlanFiles files = new PlanFiles(root);
        Files.createDirectories(files.plansDir());
        String foreignBytes = "{\"schemaVersion\":99,\"revision\":1,\"paused\":false}";
        Files.writeString(files.fileOf(plan), foreignBytes, StandardCharsets.UTF_8);
        String reason = "carries schemaVersion 99 (expected 1); refusing to reinterpret it (P4.13)";
        try (PlanStore store = new PlanStore(root)) {
            assertEquals("Plan 'alpha-target' cannot be read: " + files.fileOf(plan) + " " + reason,
                    refusal(store.load(plan)).getMessage());
            PlanStore.Claim claim = await(store.acquireClaim(plan));
            assertEquals("Plan 'alpha-target' cannot be quarantined: " + files.fileOf(plan) + " "
                    + reason + "; a foreign schema is an operator decision",
                    refusal(store.quarantineTorn(claim)).getMessage());
        }
        assertEquals(foreignBytes, Files.readString(files.fileOf(plan), StandardCharsets.UTF_8),
                "healthy bytes with a foreign schema are left in place");
        assertFalse(Files.exists(files.plansDir().resolve("alpha-target.json.torn")),
                "no sidecar is written for a foreign schema");
    }

    @Test
    void aStaleRevisionIsRefusedByTheCasAndTheFileStaysUntouched() throws Exception {
        TaskId plan = TaskId.of("alpha-target");
        PlanFiles files = new PlanFiles(root);
        try (PlanStore store = new PlanStore(root)) {
            PlanStore.Claim claim = await(store.acquireClaim(plan));
            await(store.save(claim, 0, runningPlan("alpha-target"), false));
            assertEquals("Stale revision for plan 'alpha-target' (0 means absent): "
                    + files.fileOf(plan) + " is at revision 1, the caller expected 0;"
                    + " reload and retry (P4.13)",
                    refusal(store.save(claim, 0, runningPlan("alpha-target"), false)).getMessage());
            PlanStore.PlanRecord still = await(store.load(plan)).orElseThrow();
            assertEquals(1, still.revision(), "the refused save did not touch the file");
            assertEquals(PlanFiles.render(still), Files.readString(files.fileOf(plan),
                    StandardCharsets.UTF_8), "the bytes are exactly the last good save");
        }
    }

    @Test
    void aFailedSaveIsTheVisibleNoPermissionSignalTheDriverConsumes() throws Exception {
        TaskId plan = TaskId.of("alpha-target");
        Path storeRoot = root.toAbsolutePath().normalize();
        PlanStore store = new PlanStore(storeRoot);
        PlanStore.Claim claim = await(store.acquireClaim(plan));
        await(store.save(claim, 0, runningPlan("alpha-target"), false));
        store.close();
        assertEquals("The plan store writer on " + storeRoot + " is closed",
                refusal(store.save(claim, 1, runningPlan("alpha-target"), false)).getMessage(),
                "a failed save is a visible failure, so the driver has no launch permission");
    }

    @Test
    void aSecondDriverIsRefusedWithTheConcreteReason() throws Exception {
        TaskId plan = TaskId.of("alpha-target");
        Path claimFile = new PlanFiles(root).claimFileOf(plan);
        try (PlanStore first = new PlanStore(root); PlanStore second = new PlanStore(root)) {
            PlanStore.Claim claim = await(first.acquireClaim(plan));
            assertEquals("Plan 'alpha-target' is already driven by this Drydock instance (claim "
                    + claimFile + "); one driver per plan (P4.13)",
                    refusal(second.acquireClaim(plan)).getMessage());
            assertEquals("Plan 'alpha-target' is already driven by this Drydock instance (claim "
                    + claimFile + "); one driver per plan (P4.13)",
                    refusal(first.acquireClaim(plan)).getMessage());
            claim.close();
            try (FileChannel held = FileChannel.open(claimFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                    FileLock osLock = held.tryLock()) {
                assertNotNull(osLock, "the test itself must hold the OS claim lock");
                assertEquals("Plan 'alpha-target' is already driven by this Drydock"
                        + " (the claim file is locked) (claim " + claimFile
                        + "); one driver per plan (P4.13)",
                        refusal(second.acquireClaim(plan)).getMessage());
            }
        }
    }

    @Test
    void closeReleasesTheClaimAndReacquisitionDurablyPausesARunningPlan() throws Exception {
        TaskId plan = TaskId.of("alpha-target");
        RunningPlan running = runningPlan("alpha-target");
        PlanStore first = new PlanStore(root);
        PlanStore.Claim claim = await(first.acquireClaim(plan));
        await(first.save(claim, 0, running, false));
        first.close();
        assertFalse(claim.valid(), "close releases the claim");
        try (PlanStore second = new PlanStore(root)) {
            PlanStore.Claim reacquired = await(second.acquireClaim(plan));
            PlanStore.PlanRecord paused = await(second.load(plan)).orElseThrow();
            assertEquals(2, paused.revision(), "the durable pause is a persisted save");
            assertTrue(paused.paused(), "re-acquisition durably pauses; no auto-resume");
            assertEquals(running, paused.plan());
            assertEquals(RunningPlan.Status.RUNNING, paused.plan().status());
            assertTrue(await(second.load(plan)).orElseThrow().paused(),
                    "the pause survives every later read until an explicit Continue");
            PlanStore.PlanRecord continued = await(second.save(reacquired, 2, paused.plan(), false));
            assertEquals(3, continued.revision());
            assertFalse(await(second.load(plan)).orElseThrow().paused(),
                    "the explicit Continue (paused=false save) is the only resume path");
        }
    }
}
