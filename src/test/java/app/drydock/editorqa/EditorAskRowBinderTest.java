package app.drydock.editorqa;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;
import app.drydock.editorqa.EditorAskRowBinder.RowException;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorAskRowBinderTest {

    private static final Path ROOT = Path.of("/tmp/qa-binder-repo");

    private static final class Repo implements ApplicationStateRepository {
        volatile ApplicationState state = ApplicationState.empty();
        final AtomicBoolean failSave = new AtomicBoolean();

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState s) {
            if (failSave.get()) {
                throw new IllegalStateException("disk full");
            }
            state = s;
        }
    }

    private final Repo repo = new Repo();
    private final ApplicationStateStore catalog = ApplicationStateStore.forRepository(repo);
    private final EditorAskRowBinder binder = new EditorAskRowBinder(catalog);

    @Test
    void reserveInsertsOnceAndReturnsTheSameRow() {
        ManagedAgentSession first = binder.reserve(EditorAskCoordinatorTest.candidate(ROOT));
        ManagedAgentSession second = binder.reserve(EditorAskCoordinatorTest.candidate(ROOT));
        assertEquals(first.id(), second.id());
        assertEquals(1, catalog.state().sessions().size());
        assertEquals(SessionMode.READ_ONLY, first.binding().mode());
    }

    @Test
    void unmarkedOrWritableCandidateIsRefused() {
        ManagedAgentSession ordinary = EditorAskCoordinatorTest.candidate(ROOT)
                .toBuilder().purpose(SessionPurpose.ORDINARY).build();
        assertThrows(RowException.class, () -> binder.reserve(ordinary));
        ManagedAgentSession writable = EditorAskCoordinatorTest.candidate(ROOT).withMode(SessionMode.WRITES);
        assertThrows(RowException.class, () -> binder.reserve(writable));
    }

    @Test
    void unsavedCatalogIsAVisibleErrorAndIsRetriedNextTime() {
        repo.failSave.set(true);
        assertThrows(RowException.class, () -> binder.reserve(EditorAskCoordinatorTest.candidate(ROOT)));
        repo.failSave.set(false);
        ManagedAgentSession row = binder.reserve(EditorAskCoordinatorTest.candidate(ROOT));
        assertTrue(repo.state.sessions().stream().anyMatch(s -> s.id().equals(row.id())));
    }

    @Test
    void duplicateMarkedRowsAreARecoveryErrorNotAGuess() {
        catalog.update(s -> s.withSessions(List.of(EditorAskCoordinatorTest.candidate(ROOT),
                EditorAskCoordinatorTest.candidate(ROOT))));
        assertThrows(RowException.class, () -> binder.reserve(EditorAskCoordinatorTest.candidate(ROOT)));
    }

    @Test
    void foreignOwnerInHistoryIsRefused() {
        ManagedAgentSession row = binder.reserve(EditorAskCoordinatorTest.candidate(ROOT));
        EditorAskRecord foreign = new EditorAskRecord(EditorAskRecord.newId(), 0, ManagedSessionId.newId(), "a.txt",
                new EditorAskRecord.TextRange(0, 1, 0, 0, 0, 1), "x", "q", Instant.EPOCH,
                EditorAskRecord.Status.ANSWERED, null, "a", Instant.EPOCH, null);
        assertThrows(RowException.class, () -> binder.checkOwner(row.id(), List.of(foreign)));
        binder.checkOwner(row.id(), List.of());
    }

    @Test
    void conversationIsSavedClearedAndValidated() {
        ManagedAgentSession row = binder.reserve(EditorAskCoordinatorTest.candidate(ROOT));
        assertFalse(binder.saveConversation(row.id(), AgentKind.CLAUDE, "  "));
        assertFalse(binder.saveConversation(row.id(), AgentKind.CLAUDE, "bad\nid"));
        assertTrue(binder.saveConversation(row.id(), AgentKind.PI, "pi-1"));
        ManagedAgentSession saved = binder.current(row.id()).orElseThrow();
        assertEquals(AgentKind.PI, saved.binding().kind());
        assertEquals(Optional.of("pi-1"), saved.binding().sessionId());
        assertTrue(binder.clearConversation(row.id()));
        assertEquals(Optional.empty(), binder.current(row.id()).orElseThrow().binding().sessionId());
        assertFalse(binder.clearConversation(ManagedSessionId.newId()));
    }

    @Test
    void forgetProtectionNeedsAMarkedRowWithHistory() {
        ManagedAgentSession marked = EditorAskCoordinatorTest.candidate(ROOT);
        assertTrue(EditorAskRowBinder.forgetProtection(marked, true).isPresent());
        assertTrue(EditorAskRowBinder.forgetProtection(marked, false).isEmpty());
        ManagedAgentSession ordinary = marked.toBuilder().purpose(SessionPurpose.ORDINARY).build();
        assertTrue(EditorAskRowBinder.forgetProtection(ordinary, true).isEmpty());
    }
}
