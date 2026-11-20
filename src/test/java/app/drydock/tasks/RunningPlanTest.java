package app.drydock.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T32b acceptance matrix for the accepted {@link PlanLaunch} and
 * {@link RunningPlan} production behavior (D8/P4): defensive-copy
 * isolation, byte-stable wire serialization, invalid target and stage
 * refusals with exact reasons, completed/canceled round-trips with the
 * cancellation generation, loud unknown-version refusal, wire exclusion
 * of live objects, and the loud plan invariants. Real values and the
 * real codec only: no mocking, no files, no store.
 */
class RunningPlanTest {

    private static final RepositoryId REPO =
            RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final RepositoryId OTHER_REPO =
            RepositoryId.of("22222222-2222-2222-2222-222222222222");
    private static final Path WORKTREE = Path.of("/tmp/worktrees/alpha");
    private static final PlanLaunch.Origin ORIGIN = new PlanLaunch.Origin("win-1", WORKTREE);

    private static PlanLaunch.Selection selection(AgentKind agent) {
        return new PlanLaunch.Selection(agent, REPO, "drydock-plugin");
    }

    private static Map<TaskId, PlanLaunch.Selection> selections(String... taskIds) {
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        for (String id : taskIds) {
            selections.put(TaskId.of(id), selection(AgentKind.CLAUDE));
        }
        return selections;
    }

    private static PlanLaunch launch(TaskId target, List<Integer> confirmedStages,
            Map<TaskId, PlanLaunch.Selection> selections, int cancellationGeneration) {
        return new PlanLaunch(target, confirmedStages, selections, 7L, List.of("launch-a"),
                cancellationGeneration, ORIGIN);
    }

    private static RunningPlan.StageState stage(int number, boolean launched, boolean finished,
            String... taskIds) {
        List<TaskId> tasks = new ArrayList<>();
        for (String id : taskIds) {
            tasks.add(TaskId.of(id));
        }
        return new RunningPlan.StageState(number, tasks, launched, finished);
    }

    /** One running plan around one unlaunched stage holding the target task. */
    private static RunningPlan planAround(PlanLaunch launch) {
        return new RunningPlan(launch, List.of(stage(1, false, false, "target-task")),
                RunningPlan.Status.RUNNING);
    }

    @Test
    void constructedValuesAreIsolatedFromLaterSourceMutation() {
        List<Integer> confirmed = new ArrayList<>(List.of(1));
        List<String> launchIds = new ArrayList<>(List.of("launch-a"));
        Map<TaskId, PlanLaunch.Selection> selections =
                selections("target-task", "a-task", "b-task");
        PlanLaunch built = new PlanLaunch(TaskId.of("target-task"), confirmed, selections, 7L,
                launchIds, 0, ORIGIN);
        List<TaskId> stageTasks = new ArrayList<>();
        stageTasks.add(TaskId.of("target-task"));
        List<RunningPlan.StageState> stages = new ArrayList<>();
        stages.add(new RunningPlan.StageState(1, stageTasks, false, false));
        RunningPlan plan = new RunningPlan(built, stages, RunningPlan.Status.RUNNING);

        confirmed.add(2);
        launchIds.add("launch-z");
        selections.put(TaskId.of("target-task"), selection(AgentKind.TERMINAL));
        selections.remove(TaskId.of("a-task"));
        stageTasks.add(TaskId.of("hijacked-task"));
        stages.add(stage(2, false, false, "b-task"));

        assertEquals(List.of(1), built.confirmedStages());
        assertEquals(List.of("launch-a"), built.launchIds());
        assertEquals(3, built.selections().size());
        assertEquals(AgentKind.CLAUDE, built.selections().get(TaskId.of("target-task")).agent());
        assertTrue(built.selections().containsKey(TaskId.of("a-task")));
        assertEquals(1, plan.stages().size());
        assertEquals(List.of(TaskId.of("target-task")), plan.stages().get(0).tasks());

        assertThrows(UnsupportedOperationException.class, () -> built.confirmedStages().add(9));
        assertThrows(UnsupportedOperationException.class, () -> built.launchIds().add("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> built.selections().put(TaskId.of("c-task"), selection(AgentKind.PI)));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.stages().add(stage(2, false, false, "e-task")));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.stages().get(0).tasks().add(TaskId.of("d-task")));
    }

    @Test
    void parsedPlansAreImmutableAndSurviveRoundTrip() {
        PlanLaunch built = launch(TaskId.of("target-task"), List.of(1),
                selections("target-task"), 0);
        RunningPlan original = new RunningPlan(built,
                List.of(stage(1, true, true, "target-task")), RunningPlan.Status.COMPLETED);
        RunningPlan parsed = RunningPlan.fromJson(original.toJson());

        assertEquals(original, parsed);
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.stages().add(stage(2, false, false, "e-task")));
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.launch().confirmedStages().add(2));
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.launch().selections().put(TaskId.of("c-task"), selection(AgentKind.PI)));
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.stages().get(0).tasks().add(TaskId.of("d-task")));
        assertEquals(original, parsed);
    }

    @Test
    void wireIsByteStableWithFixedKeyOrderAndSortedSelectionKeys() {
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        selections.put(TaskId.of("target-task"), selection(AgentKind.CLAUDE));
        selections.put(TaskId.of("b-task"), selection(AgentKind.CLAUDE));
        selections.put(TaskId.of("a-task"), selection(AgentKind.PI));
        PlanLaunch built = launch(TaskId.of("target-task"), List.of(1), selections, 0);
        assertEquals(built.toJson(), built.toJson());
        assertEquals(built.toJson(), PlanLaunch.fromJson(built.toJson()).toJson());

        RunningPlan plan = new RunningPlan(built, List.of(stage(1, false, false, "target-task")),
                RunningPlan.Status.RUNNING);
        String json = plan.toJson();
        assertEquals(json, plan.toJson());
        assertEquals(json, RunningPlan.fromJson(json).toJson());

        assertTrue(json.indexOf("\"schemaVersion\"") < json.indexOf("\"status\""));
        assertTrue(json.indexOf("\"status\"") < json.indexOf("\"launch\""));
        assertTrue(json.indexOf("\"launch\"") < json.indexOf("\"stages\""));
        int from = json.indexOf("\"launch\"");
        assertTrue(json.indexOf("\"target\"", from) < json.indexOf("\"graphRevision\"", from));
        assertTrue(json.indexOf("\"graphRevision\"", from) < json.indexOf("\"confirmedStages\"", from));
        assertTrue(json.indexOf("\"confirmedStages\"", from) < json.indexOf("\"launchIds\"", from));
        assertTrue(json.indexOf("\"launchIds\"", from) < json.indexOf("\"cancellationGeneration\"", from));
        assertTrue(json.indexOf("\"cancellationGeneration\"", from) < json.indexOf("\"selections\"", from));
        assertTrue(json.indexOf("\"selections\"", from) < json.indexOf("\"origin\"", from));
        int stageAt = json.indexOf("\"stages\"");
        assertTrue(json.indexOf("\"stage\"", stageAt) < json.indexOf("\"tasks\"", stageAt));
        assertTrue(json.indexOf("\"tasks\"", stageAt) < json.indexOf("\"launched\"", stageAt));
        assertTrue(json.indexOf("\"launched\"", stageAt) < json.indexOf("\"finished\"", stageAt));
        assertTrue(json.indexOf("\"a-task\"") < json.indexOf("\"b-task\""));
    }

    @Test
    void invalidTargetAndOutOfRangeStagesAreRefusedWithExactReasons() {
        PlanLaunch ghostTarget = launch(TaskId.of("ghost-task"), List.of(1),
                selections("target-task"), 0);
        IllegalArgumentException notInStages = assertThrows(IllegalArgumentException.class,
                () -> planAround(ghostTarget));
        assertEquals("Plan target 'ghost-task' is not a task of any stage", notInStages.getMessage());

        RunningPlan plan = planAround(launch(TaskId.of("target-task"), List.of(1),
                selections("target-task"), 0));
        IllegalArgumentException forged = assertThrows(IllegalArgumentException.class,
                () -> RunningPlan.fromJson(plan.toJson().replaceFirst(
                        "\"target\": \"target-task\"", "\"target\": \"ghost-task\"")));
        assertEquals("Plan target 'ghost-task' is not a task of any stage", forged.getMessage());

        PlanLaunch beyondStages = launch(TaskId.of("target-task"), List.of(1, 2),
                selections("target-task"), 0);
        IllegalArgumentException outOfRange = assertThrows(IllegalArgumentException.class,
                () -> planAround(beyondStages));
        assertEquals("Confirmed stage 2 is out of range; the plan has 1 stages", outOfRange.getMessage());

        IllegalArgumentException notIncreasing = assertThrows(IllegalArgumentException.class,
                () -> launch(TaskId.of("target-task"), List.of(2, 1), selections("target-task"), 0));
        assertEquals("Confirmed stage indices must be positive and strictly increasing"
                + " (stage numbers start at 1), got: 1", notIncreasing.getMessage());
    }

    @Test
    void completedAndCanceledPlansRoundTripWithCancellationGeneration() {
        PlanLaunch completedLaunch = launch(TaskId.of("target-task"), List.of(1),
                selections("target-task"), 3);
        RunningPlan completed = new RunningPlan(completedLaunch,
                List.of(stage(1, true, true, "target-task")), RunningPlan.Status.COMPLETED);
        RunningPlan reloadedCompleted = RunningPlan.fromJson(completed.toJson());
        assertEquals(RunningPlan.Status.COMPLETED, reloadedCompleted.status());
        assertEquals(3, reloadedCompleted.cancellationGeneration());
        assertTrue(reloadedCompleted.stages().get(0).launched());
        assertTrue(reloadedCompleted.stages().get(0).finished());
        assertTrue(reloadedCompleted.confirmed(1));
        assertEquals(completed, reloadedCompleted);

        RunningPlan running = planAround(launch(TaskId.of("target-task"), List.of(1),
                selections("target-task"), 0));
        RunningPlan canceled = running.canceled();
        assertEquals(RunningPlan.Status.CANCELED, canceled.status());
        assertEquals(1, canceled.cancellationGeneration());
        assertEquals(0, running.cancellationGeneration());
        assertEquals(running.stages(), canceled.stages());
        RunningPlan reloadedCanceled = RunningPlan.fromJson(canceled.toJson());
        assertEquals(canceled, reloadedCanceled);
        assertEquals(RunningPlan.Status.CANCELED, reloadedCanceled.status());
        assertEquals(1, reloadedCanceled.cancellationGeneration());

        PlanLaunch bumped = completedLaunch.withCancellationGeneration(9);
        assertEquals(9, bumped.cancellationGeneration());
        assertEquals(3, completedLaunch.cancellationGeneration());
        assertEquals(completedLaunch.withCancellationGeneration(9), bumped);
    }

    @Test
    void unknownOrMissingSchemaVersionIsRefusedLoudly() {
        PlanLaunch built = launch(TaskId.of("target-task"), List.of(1),
                selections("target-task"), 0);
        String launchJson = built.toJson();
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> PlanLaunch.fromJson(launchJson.replaceFirst(
                        "\"schemaVersion\": 1", "\"schemaVersion\": 2")));
        assertEquals("Unsupported plan launch schemaVersion: 2 (expected 1)", wrong.getMessage());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> PlanLaunch.fromJson(launchJson.replaceFirst(
                        "(?m)^  \"schemaVersion\": 1,\\n", "")));
        assertEquals("Missing or non-numeric schemaVersion for plan launch", missing.getMessage());

        RunningPlan plan = planAround(built);
        String planJson = plan.toJson();
        IllegalArgumentException planWrong = assertThrows(IllegalArgumentException.class,
                () -> RunningPlan.fromJson(planJson.replaceFirst(
                        "(?m)^  \"schemaVersion\": 1,", "  \"schemaVersion\": 99,")));
        assertEquals("Unsupported running plan schemaVersion: 99 (expected 1)", planWrong.getMessage());
        IllegalArgumentException planMissing = assertThrows(IllegalArgumentException.class,
                () -> RunningPlan.fromJson(planJson.replaceFirst(
                        "(?m)^  \"schemaVersion\": 1,\\n", "")));
        assertEquals("Missing or non-numeric schemaVersion for running plan", planMissing.getMessage());
    }

    @Test
    void wireCarriesOnlyPersistedStringsNeverLiveObjects() {
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        selections.put(TaskId.of("target-task"), selection(AgentKind.CLAUDE));
        selections.put(TaskId.of("a-task"), selection(AgentKind.PI));
        RunningPlan plan = new RunningPlan(
                launch(TaskId.of("target-task"), List.of(1), selections, 0),
                List.of(stage(1, false, false, "target-task")), RunningPlan.Status.RUNNING);
        String json = plan.toJson();

        assertTrue(json.contains("\"claude\""));
        assertTrue(json.contains("\"pi\""));
        assertTrue(json.contains("\"" + REPO + "\""));
        assertTrue(json.contains("\"win-1\""));
        assertTrue(json.contains("\"" + WORKTREE + "\""));
        assertTrue(json.contains("\"drydock-plugin\""));

        String[] forbidden = {"app.drydock", "AgentKind", "RepositoryId", "PlanLaunch",
                "RunningPlan", "Project", "Inputs", "Credential", "CLAUDE", "PI", "CODEX",
                "java.", "instanceof", "class "};
        for (String leak : forbidden) {
            assertFalse(json.contains(leak), "The wire must not contain '" + leak + "'");
        }
    }

    /** One root stacked unit carrying the named tasks (no base, no waits). */
    private static PlanLaunch.StackedUnit rootUnit(String... taskIds) {
        List<TaskId> tasks = new ArrayList<>();
        for (String id : taskIds) {
            tasks.add(TaskId.of(id));
        }
        return new PlanLaunch.StackedUnit(tasks, null, List.of());
    }

    @Test
    void aStackedUnitsBaseMustBeOneOfItsWaits() {
        PlanLaunch.StackedUnit stacked = new PlanLaunch.StackedUnit(
                List.of(TaskId.of("b-task")), "dd/a-task", List.of("dd/a-task"));
        assertFalse(stacked.isRoot());

        IllegalArgumentException unrelatedBase = assertThrows(IllegalArgumentException.class,
                () -> new PlanLaunch.StackedUnit(List.of(TaskId.of("b-task")), "dd/other",
                        List.of("dd/unrelated")));
        assertTrue(unrelatedBase.getMessage().contains("stack base is one of its waits"));
    }

    @Test
    void aTaskIdMayNotAppearInTwoUnitsOfOneRepositorysStack() {
        Map<RepositoryId, List<PlanLaunch.StackedUnit>> stacks = new LinkedHashMap<>();
        stacks.put(REPO, List.of(rootUnit("a-task"), rootUnit("a-task")));
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> launchWithStacks(stacks));
        assertTrue(duplicate.getMessage().contains("appears in two units of one repository's stack"));
    }

    @Test
    void theSameTaskInTwoDifferentRepositoriesStacksIsValid() {
        Map<RepositoryId, List<PlanLaunch.StackedUnit>> stacks = new LinkedHashMap<>();
        stacks.put(REPO, List.of(rootUnit("a-task")));
        stacks.put(OTHER_REPO, List.of(rootUnit("a-task")));
        PlanLaunch built = launchWithStacks(stacks);
        assertEquals(List.of(rootUnit("a-task")), built.stacks().get(REPO));
        assertEquals(List.of(rootUnit("a-task")), built.stacks().get(OTHER_REPO));
    }

    @Test
    void aMergedStackShapeCarriesEveryStagesUnitsForOneRepository() {
        // One repository contributing tasks to two stages: each stage's units
        // append to the same per-repository list, disjoint task ids.
        Map<RepositoryId, List<PlanLaunch.StackedUnit>> stacks = new LinkedHashMap<>();
        stacks.put(REPO, List.of(rootUnit("a-task"), rootUnit("b-task")));
        PlanLaunch built = launchWithStacks(stacks);
        assertEquals(List.of(rootUnit("a-task"), rootUnit("b-task")), built.stacks().get(REPO));
    }

    private static PlanLaunch launchWithStacks(Map<RepositoryId, List<PlanLaunch.StackedUnit>> stacks) {
        return new PlanLaunch(TaskId.of("target-task"), List.of(1), selections("target-task"),
                7L, List.of("launch-a"), 0, ORIGIN, stacks);
    }

    @Test
    void loudInvariantsRefuseInconsistentPlans() {
        PlanLaunch built = launch(TaskId.of("target-task"), List.of(1),
                selections("target-task"), 0);
        List<RunningPlan.StageState> unselected = List.of(stage(1, false, false, "target-task"),
                stage(2, false, false, "unselected-task"));
        IllegalArgumentException coverage = assertThrows(IllegalArgumentException.class,
                () -> new RunningPlan(built, unselected, RunningPlan.Status.RUNNING));
        assertEquals("Task 'unselected-task' has no launch selection; the plan dialog fixes the"
                + " choice of every segment (D8)", coverage.getMessage());

        IllegalArgumentException finishedUnlaunched = assertThrows(IllegalArgumentException.class,
                () -> new RunningPlan(built, List.of(stage(1, false, true, "target-task")),
                        RunningPlan.Status.RUNNING));
        assertEquals("Stage 1 is finished but was never launched", finishedUnlaunched.getMessage());

        IllegalArgumentException prematureCompleted = assertThrows(IllegalArgumentException.class,
                () -> new RunningPlan(built, List.of(stage(1, false, false, "target-task")),
                        RunningPlan.Status.COMPLETED));
        assertEquals("A completed plan has every stage finished", prematureCompleted.getMessage());

        IllegalArgumentException staleRunning = assertThrows(IllegalArgumentException.class,
                () -> new RunningPlan(built, List.of(stage(1, true, true, "target-task")),
                        RunningPlan.Status.RUNNING));
        assertEquals("A plan with every stage finished is not running", staleRunning.getMessage());
    }
}
