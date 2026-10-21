package app.drydock.state;

import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.PrLink;
import app.drydock.domain.AgentBinding;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.WorkspaceUiState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonApplicationStateRepositoryTest {

    @TempDir
    Path tempDir;

    private Path stateFile() {
        return tempDir.resolve("state.json");
    }

    private Repository sampleRepository(Path root) {
        return new Repository(
                RepositoryId.newId(),
                root,
                "sample-repo",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                RepositorySettings.DEFAULT);
    }

    @Test
    void loadOnMissingFileReturnsEmptyState() {
        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());

        ApplicationState loaded = repository.load();

        assertEquals(ApplicationState.empty(), loaded);
    }

    @Test
    void saveThenLoadRoundTripsRepositoriesAndUiState() throws IOException {
        Path repoRoot = Files.createDirectory(tempDir.resolve("repo-root"));
        Repository repo = sampleRepository(repoRoot);
        WorkspaceUiState ui = new WorkspaceUiState(
                Optional.of(repo.id()), 321.0, Set.of(repo.id()), app.drydock.domain.UiTheme.LIGHT,
                WorkspaceUiState.DEFAULT_UI_FONT_SIZE, WorkspaceUiState.DEFAULT_TERMINAL_FONT_SIZE,
                List.of(), Optional.empty(), Map.of());
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), List.of(), ui, List.of());

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(state);

        ApplicationState loaded = repository.load();

        assertEquals(state, loaded);
    }

    @Test
    void saveCreatesParentDirectoriesIfMissing() {
        Path nested = tempDir.resolve("nested/dir/state.json");
        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(nested);

        repository.save(ApplicationState.empty());

        assertTrue(Files.exists(nested));
    }

    @Test
    void loadRecoversFromTruncatedStateFile() throws IOException {
        Path file = stateFile();
        Files.writeString(file, "{\"schemaVersion\": 1, \"repositories\": [ { \"id\": \"trunc", StandardCharsets.UTF_8);

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(file);

        ApplicationState loaded = repository.load();

        assertEquals(ApplicationState.empty(), loaded);
        assertCorruptBackupExists(file);
    }

    @Test
    void loadRecoversFromMalformedJson() throws IOException {
        Path file = stateFile();
        Files.writeString(file, "not json at all", StandardCharsets.UTF_8);

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(file);

        ApplicationState loaded = repository.load();

        assertEquals(ApplicationState.empty(), loaded);
        assertCorruptBackupExists(file);
    }

    @Test
    void loadRecoversFromUnknownSchemaVersion() throws IOException {
        Path file = stateFile();
        Files.writeString(file, "{\"schemaVersion\": 999, \"repositories\": [], \"ui\": {}}", StandardCharsets.UTF_8);

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(file);

        ApplicationState loaded = repository.load();

        assertEquals(ApplicationState.empty(), loaded);
        assertCorruptBackupExists(file);
    }

    @Test
    void saveThenLoadRoundTripsManagedAgentSessions() throws IOException {
        Path repoRoot = Files.createDirectory(tempDir.resolve("repo-root"));
        Repository repo = sampleRepository(repoRoot);
        Path workingDirectory = Files.createDirectory(tempDir.resolve("session-working-dir"));
        Path worktreeRoot = Files.createDirectory(tempDir.resolve("session-worktree"));
        // IDLE is the durable no-process state, so it round-trips as
        // itself; the legacy statuses it replaced migrate at load (see
        // loadMigratesLegacyAndTransientStatusesToIdle).
        ManagedAgentSession session = new ManagedAgentSession(
                ManagedSessionId.newId(), repo.id(), "my session",
                new AgentBinding(AgentKind.CLAUDE, Optional.of("claude-session-id-abc"), Optional.of("claude-session-name")),
                new SessionWorkspace(workingDirectory, Optional.of(worktreeRoot), true),
                SessionStatus.IDLE, Instant.parse("2026-01-03T00:00:00Z"), Instant.parse("2026-01-04T00:00:00Z"), Optional.of(1),
                PrLink.of(PrState.OPEN, Optional.of(128)), false, Optional.empty());
        ApplicationState state = new ApplicationState(List.of(repo), List.of(session), List.of(), WorkspaceUiState.empty(), List.of());

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(state);

        ApplicationState loaded = repository.load();

        assertEquals(state, loaded);
    }

    @Test
    void saveThenLoadRoundTripsSessionWithNoOptionalFieldsSet() throws IOException {
        Path repoRoot = Files.createDirectory(tempDir.resolve("repo-root"));
        Repository repo = sampleRepository(repoRoot);
        Path workingDirectory = Files.createDirectory(tempDir.resolve("session-working-dir"));
        ManagedAgentSession session = new ManagedAgentSession(
                ManagedSessionId.newId(), repo.id(), "bare session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(workingDirectory, Optional.empty(), true),
                SessionStatus.IDLE, Instant.parse("2026-01-03T00:00:00Z"), Instant.parse("2026-01-04T00:00:00Z"), Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
        ApplicationState state = new ApplicationState(List.of(repo), List.of(session), List.of(), WorkspaceUiState.empty(), List.of());

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(state);

        assertEquals(state, repository.load());
    }

    @Test
    void loadMigratesLegacyAndTransientStatusesToIdle() throws IOException {
        // Execution-sessions migration, at the repository level: a legacy
        // INACTIVE/EXITED row and a cold persisted transient
        // STARTING/RUNNING/STOPPING row all decode as IDLE — the durable
        // no-process state — while error statuses stay themselves. No row
        // is dropped and identities survive (AC6).
        Path repoRoot = Files.createDirectory(tempDir.resolve("repo-root"));
        Repository repo = sampleRepository(repoRoot);
        Path workingDirectory = Files.createDirectory(tempDir.resolve("session-working-dir"));
        List<ManagedAgentSession> sessions = List.of(
                sessionWithStatus(repo, workingDirectory, SessionStatus.INACTIVE),
                sessionWithStatus(repo, workingDirectory, SessionStatus.EXITED),
                sessionWithStatus(repo, workingDirectory, SessionStatus.STARTING),
                sessionWithStatus(repo, workingDirectory, SessionStatus.RUNNING),
                sessionWithStatus(repo, workingDirectory, SessionStatus.STOPPING),
                sessionWithStatus(repo, workingDirectory, SessionStatus.FAILED));
        ApplicationState state = new ApplicationState(List.of(repo), sessions, List.of(),
                WorkspaceUiState.empty(), List.of());

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(state);

        ApplicationState loaded = repository.load();

        assertEquals(6, loaded.sessions().size(), "the migration must not drop a row");
        for (int i = 0; i < 5; i++) {
            assertEquals(SessionStatus.IDLE, loaded.sessions().get(i).status(), "row " + i);
        }
        assertEquals(SessionStatus.FAILED, loaded.sessions().get(5).status(), "errors stay error states");
        for (int i = 0; i < 6; i++) {
            assertEquals(state.sessions().get(i).id(), loaded.sessions().get(i).id());
            assertEquals(state.sessions().get(i).binding(), loaded.sessions().get(i).binding());
            assertEquals(state.sessions().get(i).workspace(), loaded.sessions().get(i).workspace());
        }
    }

    private static ManagedAgentSession sessionWithStatus(Repository repo, Path workingDirectory, SessionStatus status) {
        return new ManagedAgentSession(
                ManagedSessionId.newId(), repo.id(), "migrating session",
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-of-" + status), Optional.empty()),
                new SessionWorkspace(workingDirectory, Optional.empty(), true),
                status, Instant.parse("2026-01-03T00:00:00Z"), Instant.parse("2026-01-04T00:00:00Z"), Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    @Test
    void loadsOldSchemaVersionOneStateFileWithoutSessionsFieldAsEmptySessionList() throws IOException {
        Path file = stateFile();
        Path repoRoot = Files.createDirectory(tempDir.resolve("repo-root"));
        // Hand-written schemaVersion-1 document (Milestone 4 shape, before
        // "sessions" existed) -- this must load successfully with an empty
        // session list rather than crashing or requiring a user-visible
        // migration step.
        String oldSchemaJson = """
                {
                  "schemaVersion": 1,
                  "repositories": [
                    {
                      "id": "%s",
                      "root": "%s",
                      "displayName": "old-repo",
                      "addedAt": "2026-01-01T00:00:00Z",
                      "lastOpenedAt": "2026-01-02T00:00:00Z",
                      "settings": {}
                    }
                  ],
                  "ui": {
                    "selectedRepositoryId": null,
                    "sidebarWidth": 260.0,
                    "expandedRepositoryIds": []
                  }
                }
                """.formatted(RepositoryId.newId(), repoRoot.toString().replace("\\", "\\\\"));
        Files.writeString(file, oldSchemaJson, StandardCharsets.UTF_8);

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(file);
        ApplicationState loaded = repository.load();

        assertEquals(1, loaded.repositories().size());
        assertTrue(loaded.sessions().isEmpty());
    }

    @Test
    void loadsSessionEntryWithoutPrFieldsAsPrStateNone() throws IOException {
        Path file = stateFile();
        Path repoRoot = Files.createDirectory(tempDir.resolve("repo-root"));
        Path workingDirectory = Files.createDirectory(tempDir.resolve("session-working-dir"));
        RepositoryId repoId = RepositoryId.newId();
        // Hand-written schemaVersion-2 session written before prState /
        // prNumber existed -- must decode leniently to NONE / empty.
        String json = """
                {
                  "schemaVersion": 2,
                  "repositories": [
                    {
                      "id": "%s",
                      "root": "%s",
                      "displayName": "repo",
                      "addedAt": "2026-01-01T00:00:00Z",
                      "lastOpenedAt": "2026-01-02T00:00:00Z",
                      "settings": {}
                    }
                  ],
                  "sessions": [
                    {
                      "id": "%s",
                      "repositoryId": "%s",
                      "displayName": "pre-pr session",
                      "claudeSessionId": null,
                      "claudeSessionName": null,
                      "workingDirectory": "%s",
                      "worktreeRoot": null,
                      "status": "INACTIVE",
                      "createdAt": "2026-01-03T00:00:00Z",
                      "lastOpenedAt": "2026-01-04T00:00:00Z",
                      "lastExitCode": null
                    }
                  ],
                  "ui": {
                    "selectedRepositoryId": null,
                    "sidebarWidth": 260.0,
                    "expandedRepositoryIds": []
                  }
                }
                """.formatted(repoId, repoRoot.toString().replace("\\", "\\\\"),
                ManagedSessionId.newId(), repoId, workingDirectory.toString().replace("\\", "\\\\"));
        Files.writeString(file, json, StandardCharsets.UTF_8);

        ApplicationState loaded = new JsonApplicationStateRepository(file).load();

        assertEquals(1, loaded.sessions().size());
        assertEquals(PrState.NONE, loaded.sessions().get(0).prState());
        assertTrue(loaded.sessions().get(0).prNumber().isEmpty());
        // The legacy INACTIVE status decodes as IDLE (execution-sessions
        // migration) while the lenient PR fields decode to NONE/empty.
        assertEquals(SessionStatus.IDLE, loaded.sessions().get(0).status());
    }

    @Test
    void saveRetainsBackupOfPreviousStateFile() throws IOException {
        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(ApplicationState.empty());

        Path repoRoot = Files.createDirectory(tempDir.resolve("second-root"));
        ApplicationState secondState = new ApplicationState(List.of(sampleRepository(repoRoot)), List.of(), List.of(), WorkspaceUiState.empty(), List.of());
        repository.save(secondState);

        Path backup = stateFile().resolveSibling("state.json.bak");
        assertTrue(Files.exists(backup));
    }

    @Test
    void crashBeforeRenameLeavesRealStateFileUntouched() throws IOException {
        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        ApplicationState originalState = ApplicationState.empty();
        repository.save(originalState);

        // Simulate a crash between "write temp file" and "atomic rename":
        // create a leftover temp file (matching the naming pattern used by
        // JsonApplicationStateRepository) with different content, but
        // never rename it.
        Path leftoverTemp = Files.createTempFile(tempDir, "state.json.", ".tmp");
        Files.writeString(leftoverTemp, "{\"schemaVersion\": 1, \"repositories\": [], \"ui\": {}, \"corrupted\": true}",
                StandardCharsets.UTF_8);

        ApplicationState loaded = repository.load();

        assertEquals(originalState, loaded);
        assertTrue(Files.exists(leftoverTemp), "leftover temp file should still exist, untouched");
    }

    private void assertCorruptBackupExists(Path file) throws IOException {
        Path parent = file.getParent();
        try (Stream<Path> entries = Files.list(parent)) {
            boolean found = entries.anyMatch(p -> p.getFileName().toString().startsWith(file.getFileName() + ".corrupt-"));
            assertTrue(found, "expected a *.corrupt-<timestamp> backup file next to " + file);
        }
    }
}
