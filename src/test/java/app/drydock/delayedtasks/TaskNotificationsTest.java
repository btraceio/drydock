package app.drydock.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TaskNotificationsTest {

    private static final RepositoryId REPO = RepositoryId.newId();

    private static ManagedAgentSession session(String name) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), REPO, name,
                AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/tmp/repo")), Instant.EPOCH).build();
    }

    private static TaskSnapshot snapshotWith(TaskId id, List<String> sessions) {
        TaskDocument document = new TaskDocument(id, REPO, "Task", TaskStatus.REVIEW, DoneBy.HUMAN,
                Optional.empty(), List.of(), sessions, "", "");
        return new TaskSnapshot(List.of(document), List.of(REPO), Map.of(id, document), Map.of(), List.of(),
                List.of());
    }

    @Test
    void theNewestCatalogSessionWins() {
        ManagedAgentSession older = session("older");
        ManagedAgentSession newer = session("newer");
        TaskId id = TaskId.of("show-a");
        TaskSnapshot latest = snapshotWith(id, List.of(older.id().toString(), newer.id().toString()));
        assertSame(newer, TaskNotifications.sessionOf(id, latest, List.of(older, newer)));
    }

    @Test
    void aSessionGoneFromTheCatalogFallsBackOrAnswersNull() {
        ManagedAgentSession older = session("older");
        TaskId id = TaskId.of("show-b");
        TaskSnapshot latest = snapshotWith(id, List.of(older.id().toString(), "gone-session"));
        assertSame(older, TaskNotifications.sessionOf(id, latest, List.of(older)));
        assertNull(TaskNotifications.sessionOf(id, latest, List.of()));
        assertNull(TaskNotifications.sessionOf(TaskId.of("show-missing"), latest, List.of(older)));
    }
}
