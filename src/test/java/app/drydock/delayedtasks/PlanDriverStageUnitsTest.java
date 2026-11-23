package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.PlanLaunch;
import app.drydock.tasks.PlanLaunch.StackedUnit;
import app.drydock.tasks.RunningPlan;
import app.drydock.tasks.TaskId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted stack shape's per-stage dispatch (T6): the stage-scoped
 * read of the merged per-repository units, the launch-id save's stack
 * passthrough, and the legacy SegmentRequest's stage-count invariant.
 * Real values only: no mocking, no store -- stageUnits and withLaunchIds
 * are package-private static seams on the production driver.
 */
class PlanDriverStageUnitsTest {

    private static final RepositoryId REPO =
            RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final Path WORKTREE = Path.of("/tmp/worktrees/alpha");

    private static TaskId task(String text) {
        return TaskId.of(text);
    }

    private static StackedUnit rootUnit(String... taskTexts) {
        return new StackedUnit(List.of(taskTexts).stream().map(PlanDriverStageUnitsTest::task).toList(),
                null, List.of());
    }

    @Test
    void unitsFullyInsideTheShareBelongToTheStageAndUnitsOutsideAreSkipped() {
        List<StackedUnit> persisted = List.of(rootUnit("a-task", "b-task"), rootUnit("c-task"));
        List<TaskId> share = List.of(task("a-task"), task("b-task"));
        assertEquals(List.of(rootUnit("a-task", "b-task")),
                PlanDriver.stageUnits(persisted, share, 1, REPO));
    }

    @Test
    void aLegacyCrossStageRootUnitIsSlicedOrderPreservinglyToTheShare() {
        List<StackedUnit> persisted = List.of(rootUnit("a-task", "b-task", "c-task", "d-task"));
        List<TaskId> share = List.of(task("b-task"), task("d-task"));
        List<StackedUnit> staged = PlanDriver.stageUnits(persisted, share, 2, REPO);
        assertEquals(1, staged.size());
        assertTrue(staged.get(0).isRoot());
        assertEquals(List.of(task("b-task"), task("d-task")), staged.get(0).tasks());
    }

    @Test
    void aNonRootPartialOverlapIsACorruptShapeRefusedLoudly() {
        List<StackedUnit> persisted = List.of(new StackedUnit(
                List.of(task("a-task"), task("b-task")), "dd/a-task", List.of("dd/a-task")));
        List<TaskId> share = List.of(task("a-task"), task("c-task"));
        IllegalArgumentException corrupt = assertThrows(IllegalArgumentException.class,
                () -> PlanDriver.stageUnits(persisted, share, 1, REPO));
        assertTrue(corrupt.getMessage().contains(REPO.toString()));
        assertTrue(corrupt.getMessage().contains("stage 1"));
    }

    @Test
    void anEmptyPersistedShapeMeansTheLegacyWholeShareFallback() {
        List<TaskId> share = List.of(task("a-task"));
        assertEquals(List.of(), PlanDriver.stageUnits(null, share, 1, REPO));
        assertEquals(List.of(), PlanDriver.stageUnits(List.of(), share, 1, REPO));
    }

    @Test
    void aShareTaskInNoSelectedUnitIsRefusedNeverSilentlyDropped() {
        List<StackedUnit> persisted = List.of(rootUnit("a-task"));
        List<TaskId> share = List.of(task("a-task"), task("c-task"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> PlanDriver.stageUnits(persisted, share, 1, REPO));
        assertTrue(missing.getMessage().contains(REPO.toString()));
        assertTrue(missing.getMessage().contains("stage 1"));
        assertTrue(missing.getMessage().contains("c-task"));
    }

    @Test
    void theLaunchIdSaveCarriesTheStackShapeThrough() {
        Map<RepositoryId, List<StackedUnit>> stacks = new LinkedHashMap<>();
        stacks.put(REPO, List.of(rootUnit("a-task", "b-task")));
        PlanLaunch launch = new PlanLaunch(task("target-task"), List.of(1),
                Map.of(task("target-task"), new PlanLaunch.Selection(AgentKind.CLAUDE, REPO,
                        "drydock-plugin")),
                7L, List.of(), 0, new PlanLaunch.Origin("win-1", WORKTREE), stacks);
        RunningPlan plan = new RunningPlan(launch,
                List.of(new RunningPlan.StageState(1, List.of(task("target-task")), false, false)),
                RunningPlan.Status.RUNNING);

        RunningPlan saved = PlanDriver.withLaunchIds(plan, List.of());

        assertEquals(launch.stacks(), saved.launch().stacks());
        assertFalse(saved.launch().stacks().isEmpty());
    }

    @Test
    void theLegacySegmentRequestCoversItsOwnStage() {
        PlanDriver.SegmentRequest stageOne = new PlanDriver.SegmentRequest(1,
                List.of(task("a-task")), AgentKind.CLAUDE, REPO, "drydock-plugin", "win-1",
                WORKTREE, 0);
        assertEquals(1, stageOne.stageCount());

        PlanDriver.SegmentRequest stageThree = new PlanDriver.SegmentRequest(3,
                List.of(task("a-task")), AgentKind.CLAUDE, REPO, "drydock-plugin", "win-1",
                WORKTREE, 0);
        assertEquals(3, stageThree.stageCount());
        assertTrue(stageThree.stageCount() >= stageThree.stage());
    }
}
