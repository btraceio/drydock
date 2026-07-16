package app.cpm.state;

import app.cpm.domain.ApplicationState;
import app.cpm.domain.Repository;
import app.cpm.domain.RepositoryId;
import app.cpm.domain.RepositorySettings;
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
                Optional.of(repo.id()), 321.0, Set.of(repo.id()));
        ApplicationState state = new ApplicationState(List.of(repo), ui);

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
    void saveRetainsBackupOfPreviousStateFile() throws IOException {
        JsonApplicationStateRepository repository = new JsonApplicationStateRepository(stateFile());
        repository.save(ApplicationState.empty());

        Path repoRoot = Files.createDirectory(tempDir.resolve("second-root"));
        ApplicationState secondState = new ApplicationState(List.of(sampleRepository(repoRoot)), WorkspaceUiState.empty());
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
