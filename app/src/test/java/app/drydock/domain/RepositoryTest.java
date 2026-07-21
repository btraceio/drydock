package app.drydock.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryTest {

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
    void acceptsAbsoluteNormalizedRoot() {
        Path root = tempDir.toAbsolutePath().normalize();
        Repository repository = repositoryAt(root);
        assertEquals(root, repository.root());
    }

    @Test
    void rejectsRelativeRoot() {
        assertThrows(IllegalArgumentException.class, () -> repositoryAt(Path.of("relative/path")));
    }

    @Test
    void rejectsNonNormalizedRoot() {
        Path notNormalized = tempDir.toAbsolutePath().resolve("child/../child");
        assertThrows(IllegalArgumentException.class, () -> repositoryAt(notNormalized));
    }

    @Test
    void rejectsBlankDisplayName() {
        Path root = tempDir.toAbsolutePath().normalize();
        assertThrows(IllegalArgumentException.class, () -> new Repository(
                RepositoryId.newId(), root, "   ", Instant.now(), Instant.now(), RepositorySettings.DEFAULT));
    }

    @Test
    void withLastOpenedAtPreservesOtherFields() {
        Path root = tempDir.toAbsolutePath().normalize();
        Repository original = repositoryAt(root);
        Instant newInstant = original.lastOpenedAt().plusSeconds(60);

        Repository updated = original.withLastOpenedAt(newInstant);

        assertEquals(original.id(), updated.id());
        assertEquals(original.root(), updated.root());
        assertEquals(original.displayName(), updated.displayName());
        assertEquals(original.addedAt(), updated.addedAt());
        assertEquals(newInstant, updated.lastOpenedAt());
    }
}
