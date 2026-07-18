package app.cpm.state;

import app.cpm.domain.ApplicationState;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.PrState;
import app.cpm.domain.Repository;
import app.cpm.domain.RepositoryId;
import app.cpm.domain.RepositorySettings;
import app.cpm.domain.SessionStatus;
import app.cpm.domain.WorkspaceUiState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
                Optional.of(repo.id()), 321.0, Set.of(repo.id()), app.cpm.domain.UiTheme.LIGHT);
        ApplicationState state = new ApplicationState(List.of(repo), List.of(), ui);

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
    void saveThenLoadRoundTripsManagedClaudeSessions() throws IOException {
        Path repoRoot = Files.createDirectory(tempDir.resolve("repo-root"));
        Repository repo = sampleRepository(repoRoot);
        Path workingDirectory = Files.createDirectory(tempDir.resolve("session-working-dir"));
        Path worktreeRoot = Files.createDirectory(tempDir.resolve("session-worktree"));
        ManagedClaudeSession session = new ManagedClaudeSession(
                ManagedSessionId.newId(),
                repo.id(),
                "my session",
                Optional.of("claude-session-id-abc"),
                Optional.of("claude-session-name"),
                workingDirectory,
                Optional.of(worktreeRoot),
                SessionStatus.EXITED,
                Instant.parse("2026-01-03T00:00:00Z"),
                Instant.parse("2026-01-04T00:00:00Z"),
                Optional.of(1),
                PrState.OPEN,
                Optional.of(128));
        ApplicationState state = new ApplicationState(List.of(repo), List.of(session), WorkspaceUiState.empty());

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
        ManagedClaudeSession session = new ManagedClaudeSession(
                ManagedSessionId.newId(),
                repo.id(),
                "bare session",
                Optional.empty(),
                Optional.empty(),
                workingDirectory,
                Optional.empty(),
                SessionStatus.INACTIVE,
                Instant.parse("2026-01-03T00:00:00Z"),
                Instant.parse("2026-01-04T00:00:00Z"),
                Optional.empty(),
                PrState.NONE,
                Optional.empty());
        ApplicationState state = new ApplicationState(List.of(repo), List.of(session), WorkspaceUiState.empty());

        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(state);

        assertEquals(state, repository.load());
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
    }

    @Test
    void saveRetainsBackupOfPreviousStateFile() throws IOException {
        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(ApplicationState.empty());

        Path repoRoot = Files.createDirectory(tempDir.resolve("second-root"));
        ApplicationState secondState = new ApplicationState(List.of(sampleRepository(repoRoot)), List.of(), WorkspaceUiState.empty());
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
