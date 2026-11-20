package app.drydock.delayedtasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.drydock.tasks.StartPlan.Kind.OWNED_TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-segment stacked launch's entry guard and stage derivation
 * (S15): a refused, segment-less or multi-segment plan never launches a
 * silent partial, the stage position comes from the plan's stages (never a
 * hardcoded default), and a vanished task refuses loudly instead of being
 * silently dropped from the unit prompt. Everything is pinned through the
 * package-private pure helpers -- no platform types, no mocking, no
 * processes.
 */
class DelayedTaskStartTest {

    private static TaskDocument document(RepositoryId repository, String id) {
        return new TaskDocument(TaskId.of(id), repository, "Task " + id, TaskStatus.OPEN,
                null, Optional.empty(), List.of(), List.of(), "", "");
    }

    private static StartPlan.Segment segment(String projectName, TaskDocument... tasks) {
        return new StartPlan.Segment(tasks[0].repository(), projectName, List.of(tasks));
    }

    // ---- the repository resolution (the shared-id fallback) --------------------

    private static Repository registered(RepositoryId id, String root) {
        return new Repository(id, Path.of(root), "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT);
    }

    @Test
    void installCatalogRootWins() {
        RepositoryId id = RepositoryId.newId();
        assertEquals(Path.of("/install/root"), DelayedTaskStart.rootOf(
                List.of(registered(id, "/install/root")),
                List.of(SharedProject.local(id, Path.of("/shared/root"), "repo", Instant.EPOCH)),
                id));
    }

    @Test
    void sharedListResolvesAnIdTheInstallNeverMinted() {
        RepositoryId id = RepositoryId.newId(); // the id task creation registered under
        assertEquals(Path.of("/shared/root"), DelayedTaskStart.rootOf(
                List.of(registered(RepositoryId.newId(), "/other/root")),
                List.of(SharedProject.local(id, Path.of("/shared/root"), "repo", Instant.EPOCH)),
                id));
    }

    @Test
    void remoteSharedProjectYieldsNoRoot() {
        RepositoryId id = RepositoryId.newId();
        SharedProject remote = new SharedProject(id, Path.of("/placeholder"), "remote", Instant.EPOCH,
                Optional.of(new SshRemote("host", "/remote/path")));
        assertNull(DelayedTaskStart.rootOf(List.of(), List.of(remote), id));
    }

    @Test
    void unknownIdYieldsNoRoot() {
        assertNull(DelayedTaskStart.rootOf(
                List.of(registered(RepositoryId.newId(), "/install/root")),
                List.of(SharedProject.local(RepositoryId.newId(), Path.of("/shared/root"),
                        "repo", Instant.EPOCH)),
                RepositoryId.newId()));
    }

    private static StartPlan plan(List<StartPlan.Segment> segments, List<StartPlan.Stage> stages) {
        return new StartPlan(TaskId.of("target"), segments, stages, List.of(), List.of(), List.of());
    }

    @Test
    void refusedPlanRefusesNamingTheTargetAndTheReasons() {
        StartPlan fresh = StartPlan.refused(TaskId.of("target"),
                List.of(new StartPlan.Refusal(OWNED_TASK, "the task is already owned")));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DelayedTaskStart.stackedEntryOf(fresh));
        assertTrue(refused.getMessage().contains("target"));
        assertTrue(refused.getMessage().contains("the task is already owned"));
    }

    @Test
    void segmentLessPlanRefuses() {
        StartPlan fresh = plan(List.of(), List.of());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DelayedTaskStart.stackedEntryOf(fresh));
        assertTrue(refused.getMessage().contains("target"));
        assertTrue(refused.getMessage().contains("no segment"));
    }

    @Test
    void multiSegmentPlanRefusesNamingTheSegmentCount() {
        RepositoryId first = RepositoryId.newId();
        RepositoryId second = RepositoryId.newId();
        StartPlan.Segment one = new StartPlan.Segment(first, "first",
                List.of(document(first, "a")));
        StartPlan.Segment two = new StartPlan.Segment(second, "second",
                List.of(document(second, "b")));
        StartPlan fresh = plan(List.of(one, two),
                List.of(new StartPlan.Stage(1, List.of(one, two))));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DelayedTaskStart.stackedEntryOf(fresh));
        assertTrue(refused.getMessage().contains("target"));
        assertTrue(refused.getMessage().contains("2 segments"));
    }

    @Test
    void stageWithoutTheSegmentRepositoryRefusesNamingIt() {
        RepositoryId repository = RepositoryId.newId();
        StartPlan.Segment segment = new StartPlan.Segment(repository, "orphan-repo",
                List.of(document(repository, "a")));
        RepositoryId other = RepositoryId.newId();
        StartPlan fresh = plan(List.of(segment),
                List.of(new StartPlan.Stage(1,
                        List.of(new StartPlan.Segment(other, "other-repo",
                                List.of(document(other, "b")))))));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DelayedTaskStart.stackedEntryOf(fresh));
        assertTrue(refused.getMessage().contains("target"));
        assertTrue(refused.getMessage().contains("orphan-repo"));
    }

    @Test
    void reachableSingleSegmentPlanDerivesTheStagePositionFromTheStages() {
        RepositoryId repository = RepositoryId.newId();
        TaskDocument task = document(repository, "a");
        StartPlan.Segment segment = new StartPlan.Segment(repository, "the-repo", List.of(task));
        StartPlan fresh = plan(List.of(segment), List.of(new StartPlan.Stage(1, List.of(segment))));
        DelayedTaskStart.StackedEntry entry = DelayedTaskStart.stackedEntryOf(fresh);
        assertEquals(segment, entry.segment());
        assertEquals(1, entry.stage());
        assertEquals(1, entry.stageCount());
    }

    @Test
    void stageNumberIsTheStagesOwnNumberNeverTheIndex() {
        RepositoryId repository = RepositoryId.newId();
        TaskDocument task = document(repository, "a");
        StartPlan.Segment segment = new StartPlan.Segment(repository, "the-repo", List.of(task));
        StartPlan fresh = plan(List.of(segment),
                List.of(new StartPlan.Stage(2, List.of(segment))));
        DelayedTaskStart.StackedEntry entry = DelayedTaskStart.stackedEntryOf(fresh);
        assertEquals(2, entry.stage());
        assertEquals(1, entry.stageCount());
    }

    @Test
    void vanishedTaskRefusesInsteadOfBeingSilentlyDropped() {
        RepositoryId repository = RepositoryId.newId();
        TaskDocument present = document(repository, "present");
        Map<TaskId, TaskDocument> docs = new LinkedHashMap<>();
        docs.put(present.id(), present);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DelayedTaskStart.unitDocsOf(docs, List.of(TaskId.of("present"), TaskId.of("vanished")),
                        "the-repo"));
        assertTrue(refused.getMessage().contains("vanished"));
        assertTrue(refused.getMessage().contains("is no longer in the store"));
        assertTrue(refused.getMessage().contains("launches nothing"));
    }

    @Test
    void unitDocsKeepTheSegmentDocumentsInUnitOrder() {
        RepositoryId repository = RepositoryId.newId();
        TaskDocument first = document(repository, "first");
        TaskDocument second = document(repository, "second");
        Map<TaskId, TaskDocument> docs = new LinkedHashMap<>();
        docs.put(first.id(), first);
        docs.put(second.id(), second);
        assertEquals(List.of(second, first),
                DelayedTaskStart.unitDocsOf(docs, List.of(second.id(), first.id()), "the-repo"));
    }
}
