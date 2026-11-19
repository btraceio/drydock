package app.drydock.app;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins {@link AgentRenameMutation} against a real {@link ApplicationStateStore}. */
class AgentRenameMutationTest {

    private static final RepositoryId REPO = RepositoryId.newId();
    private static final RepositoryId OTHER_REPO = RepositoryId.newId();

    private final List<ApplicationState> saves = new CopyOnWriteArrayList<>();
    private final ApplicationStateStore store = ApplicationStateStore.forRepository(new ApplicationStateRepository() {
        @Override
        public ApplicationState load() {
            return ApplicationState.empty();
        }

        @Override
        public void save(ApplicationState state) {
            saves.add(state);
        }
    });

    private ManagedAgentSession add(RepositoryId repo, String name, boolean pinned) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), repo, name,
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                SessionWorkspace.inRepository(Path.of("/tmp/agent-rename-mutation-test").toAbsolutePath()),
                Instant.parse("2026-08-12T10:00:00Z"))
                .status(SessionStatus.INACTIVE).namePinned(pinned).build();
        store.update(state -> {
            List<ManagedAgentSession> sessions = new ArrayList<>(state.sessions());
            sessions.add(session);
            return state.withSessions(sessions);
        });
        return session;
    }

    private String nameOf(ManagedSessionId id) {
        return store.state().sessions().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow()
                .displayName();
    }

    private int savesAfterSetup() {
        store.flush();
        return saves.size();
    }

    @Test
    void missingRowFailsWithoutRecreation() {
        add(REPO, "Other", false);
        int before = savesAfterSetup();

        assertThrows(IllegalStateException.class,
                () -> AgentRenameMutation.apply(store, ManagedSessionId.newId(), "New"));

        store.flush();
        assertEquals(1, store.state().sessions().size());
        assertEquals(before, saves.size());
    }

    @Test
    void pinnedNameIsRefusedAndUntouched() {
        ManagedAgentSession session = add(REPO, "Human name", true);
        int before = savesAfterSetup();

        RenameOutcome outcome = AgentRenameMutation.apply(store, session.id(), "Agent title");

        assertEquals(new RenameOutcome(RenameKind.PINNED, "Human name"), outcome);
        assertEquals("Human name", nameOf(session.id()));
        store.flush();
        assertEquals(before, saves.size());
    }

    @Test
    void exactStoredTitleIsUnchanged() {
        ManagedAgentSession session = add(REPO, "First", false);
        int before = savesAfterSetup();

        assertEquals(new RenameOutcome(RenameKind.UNCHANGED, "First"),
                AgentRenameMutation.apply(store, session.id(), "First"));

        store.flush();
        assertEquals(before, saves.size());
    }

    @Test
    void sameRepositoryCollisionIsRefusedCaseInsensitivelyAndFolded() {
        ManagedAgentSession first = add(REPO, "First", false);
        add(REPO, "Existing\u00a0name", false);
        int before = savesAfterSetup();

        RenameOutcome outcome = AgentRenameMutation.apply(store, first.id(), "existing name");

        assertEquals(RenameKind.COLLIDED, outcome.kind());
        assertEquals("Existing\u00a0name", outcome.currentName());
        assertEquals("First", nameOf(first.id()));
        store.flush();
        assertEquals(before, saves.size());
    }

    @Test
    void otherRepositoryNameDoesNotCollide() {
        ManagedAgentSession first = add(REPO, "First", false);
        ManagedAgentSession other = add(OTHER_REPO, "Shared", false);
        int before = savesAfterSetup();

        assertEquals(new RenameOutcome(RenameKind.RENAMED, "Shared"),
                AgentRenameMutation.apply(store, first.id(), "Shared"));

        assertEquals("Shared", nameOf(first.id()));
        assertEquals("Shared", nameOf(other.id()));
        store.flush();
        assertEquals(before + 1, saves.size());
    }

    @Test
    void renameReplacesOnlyThatSessionsNameInOneTransformAndStaysUnpinned() {
        ManagedAgentSession target = add(REPO, "Generated", false);
        ManagedAgentSession sibling = add(REPO, "Sibling", false);
        int before = savesAfterSetup();

        RenameOutcome outcome = AgentRenameMutation.apply(store, target.id(), "Fix the login flow");

        assertEquals(new RenameOutcome(RenameKind.RENAMED, "Fix the login flow"), outcome);
        assertEquals("Fix the login flow", nameOf(target.id()));
        assertEquals("Sibling", nameOf(sibling.id()));
        assertFalse(store.state().sessions().stream().filter(s -> s.id().equals(target.id()))
                .findFirst().orElseThrow().namePinned());
        store.flush();
        assertEquals(before + 1, saves.size());
    }
}
