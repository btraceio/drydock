package app.drydock.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryCatalogTest {

    @TempDir
    Path tempDir;

    private Repository repositoryAt(Path root) {
        return new Repository(
                RepositoryId.newId(),
                root,
                "example",
                Instant.now(),
                Instant.now(),
                RepositorySettings.DEFAULT);
    }

    @Test
    void detectsDuplicateByExactSamePath() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("repo"));
        List<Repository> repositories = List.of(repositoryAt(root));

        assertTrue(RepositoryCatalog.isDuplicate(repositories, root));
    }

    @Test
    void detectsDuplicateThroughSymlink() throws IOException {
        Path real = Files.createDirectory(tempDir.resolve("real-repo"));
        Path link = tempDir.resolve("link-to-repo");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            // Symlinks may be unavailable in some sandboxed CI environments;
            // skip rather than fail the whole suite.
            return;
        }

        List<Repository> repositories = List.of(repositoryAt(real));

        assertTrue(RepositoryCatalog.isDuplicate(repositories, link));
    }

    @Test
    void distinctDirectoriesAreNotDuplicates() throws IOException {
        Path first = Files.createDirectory(tempDir.resolve("repo-a"));
        Path second = Files.createDirectory(tempDir.resolve("repo-b"));
        List<Repository> repositories = List.of(repositoryAt(first));

        assertFalse(RepositoryCatalog.isDuplicate(repositories, second));
    }

    @Test
    void canonicalizeFallsBackGracefullyForNonExistentPath() {
        Path doesNotExist = tempDir.resolve("does-not-exist-anymore");

        Path canonical = RepositoryCatalog.canonicalize(doesNotExist);

        assertTrue(canonical.isAbsolute());
    }

    @Test
    void duplicateDetectionToleratesAlreadyDeletedRegisteredRepository() throws IOException {
        Path removed = Files.createDirectory(tempDir.resolve("removed-repo"));
        List<Repository> repositories = List.of(repositoryAt(removed));
        Files.delete(removed);

        // Must not throw even though the previously-registered
        // repository's directory no longer exists.
        assertFalse(RepositoryCatalog.isDuplicate(repositories, tempDir.resolve("unrelated")));
    }
}
