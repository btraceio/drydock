package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance fixtures for {@link TaskFiles#scan} (T04b): the real production
 * behavior over real files and filesystem attributes under {@code @TempDir}
 * -- no mocks, no faked attributes. Covers the store layout {@code
 * <root>/<repository-id>/<task-id>.md} (D2/D4), the reserved {@code plans/}
 * and {@code runs/} directories (P4), per-file failure retention, id
 * collisions with no silent winner, symlink refusal, snapshot immutability,
 * and the creation-time fallback (P1).
 *
 * <p>Not covered here: the epoch-birthtime branch of the creation fallback.
 * A file whose creation time is the epoch cannot be created portably from
 * Java (creating one needs a host-specific tool such as macOS SetFile, which
 * this test deliberately does not shell out to), so that branch stays
 * smoke-covered by the T04a artifact; this file asserts the normal real
 * fallback only.</p>
 */
class TaskFilesTest {

    private static final String REPO_A = "11111111-1111-1111-1111-111111111111";
    private static final String REPO_B = "22222222-2222-2222-2222-222222222222";

    private static final String TASK_A = "fix-login-redirect-k3f9";
    private static final String TASK_B = "ship-dark-mode-9a2c";

    @TempDir
    Path root;

    private Path repository(String uuidText) throws IOException {
        Path dir = root.resolve(uuidText);
        Files.createDirectories(dir);
        return dir;
    }

    private Path task(Path repositoryDir, String stem) throws IOException {
        return task(repositoryDir, stem, "sessions: []\n");
    }

    private Path task(Path repositoryDir, String stem, String extraFrontMatter) throws IOException {
        Path file = repositoryDir.resolve(stem + ".md");
        Files.writeString(file, "---\ntitle: " + stem + "\n" + extraFrontMatter + "---\nBody of " + stem + ".\n");
        return file;
    }

    /** Asserts some failure names exactly this path and a non-blank reason containing the given part. */
    private static void assertFailure(TaskSnapshot snapshot, Path path, String reasonPart) {
        assertTrue(snapshot.failures().stream().anyMatch(failure -> failure.path().equals(path)
                        && failure.failure().getMessage() != null
                        && !failure.failure().getMessage().isBlank()
                        && failure.failure().getMessage().contains(reasonPart)),
                () -> "expected a failure for " + path + " mentioning '" + reasonPart + "', got: "
                        + snapshot.failures());
    }

    @Test
    void twoRepositoriesWithValidTasksAreIndexedByIdSessionAndRepository() throws Exception {
        Path repoA = repository(REPO_A);
        Path repoB = repository(REPO_B);
        task(repoA, TASK_A, "sessions: [s-1]\n");
        task(repoB, TASK_B);

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertEquals(List.of(RepositoryId.of(REPO_A), RepositoryId.of(REPO_B)), snapshot.repositories());
        assertEquals(2, snapshot.documents().size());
        assertTrue(snapshot.failures().isEmpty());
        assertTrue(snapshot.idCollisions().isEmpty());

        TaskDocument documentA = snapshot.byId().get(TaskId.of(TASK_A));
        assertEquals(RepositoryId.of(REPO_A), documentA.repository());
        assertEquals(TASK_A, documentA.title());
        TaskDocument documentB = snapshot.byId().get(TaskId.of(TASK_B));
        assertEquals(RepositoryId.of(REPO_B), documentB.repository());

        assertEquals(Optional.of(documentA), snapshot.find(TaskId.of(TASK_A)));
        assertEquals(Optional.of(documentB), snapshot.find(TaskId.of(TASK_B)));
        assertEquals(Optional.empty(), snapshot.find(TaskId.of("never-stored-z0z0")));
        assertEquals(List.of(documentA), snapshot.bySessionReference().get("s-1"));
        assertEquals(1, snapshot.bySessionReference().size());
    }

    @Test
    void crossRepositoryDuplicateStemsCollideWithNoSilentWinner() throws Exception {
        Path repoA = repository(REPO_A);
        Path repoB = repository(REPO_B);
        Path fileA = task(repoA, TASK_A);
        Path fileB = task(repoB, TASK_A);

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertTrue(snapshot.documents().isEmpty());
        assertTrue(snapshot.byId().isEmpty());
        assertTrue(snapshot.bySessionReference().isEmpty());
        assertEquals(Optional.empty(), snapshot.find(TaskId.of(TASK_A)));
        assertEquals(2, snapshot.failures().size());
        assertFailure(snapshot, fileA, "duplicate task id");
        assertFailure(snapshot, fileB, "duplicate task id");

        assertEquals(1, snapshot.idCollisions().size());
        TaskSnapshot.IdCollision collision = snapshot.idCollisions().get(0);
        assertEquals(TaskId.of(TASK_A), collision.id());
        assertEquals(List.of(fileA, fileB), collision.paths());
        assertThrows(UnsupportedOperationException.class, () -> collision.paths().add(fileA));
    }

    @Test
    void malformedSiblingFailsAloneWhileOtherTasksStillLoad() throws Exception {
        Path repoA = repository(REPO_A);
        Path repoB = repository(REPO_B);
        Path broken = repoA.resolve("not-a-fence-b7d1.md");
        Files.writeString(broken, "This file has no front matter at all.\n");
        task(repoA, TASK_A);
        task(repoB, TASK_B);

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertEquals(1, snapshot.failures().size());
        assertFailure(snapshot, broken, "fence");
        assertEquals(2, snapshot.documents().size());
        assertEquals(Optional.of(TaskId.of(TASK_A)), snapshot.find(TaskId.of(TASK_A)).map(TaskDocument::id));
        assertEquals(Optional.of(TaskId.of(TASK_B)), snapshot.find(TaskId.of(TASK_B)).map(TaskDocument::id));
        assertEquals(List.of(RepositoryId.of(REPO_A), RepositoryId.of(REPO_B)), snapshot.repositories());
    }

    @Test
    void invalidEntryFormsInsideARepositoryArePerFileFailures() throws Exception {
        Path repoA = repository(REPO_A);
        Path badStem = repoA.resolve("Not_A_Stem.md");
        Files.writeString(badStem, "---\ntitle: valid front matter\n---\n");
        Path nonMd = repoA.resolve("notes.txt");
        Files.writeString(nonMd, "not a task file");
        Path subdirectory = Files.createDirectories(repoA.resolve("subdirectory"));
        task(repoA, TASK_A);

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertEquals(3, snapshot.failures().size());
        assertFailure(snapshot, badStem, "not a valid task id");
        assertFailure(snapshot, nonMd, "not a valid task id");
        assertFailure(snapshot, subdirectory, "must be regular");
        assertEquals(Optional.of(TaskId.of(TASK_A)), snapshot.find(TaskId.of(TASK_A)).map(TaskDocument::id));
        assertEquals(List.of(RepositoryId.of(REPO_A)), snapshot.repositories());
    }

    @Test
    void rootLayoutReservedDirsSkippedInvalidEntriesFlagged() throws Exception {
        Path plans = Files.createDirectories(root.resolve("plans"));
        Files.writeString(plans.resolve("garbage-entry.md"), "no fence");
        Path runs = Files.createDirectories(root.resolve("runs"));
        Files.writeString(runs.resolve("123"), "reserved content");
        Path nonUuid = Files.createDirectories(root.resolve("not-a-uuid"));
        Path rootLevelTask = root.resolve("task-at-root.md");
        Files.writeString(rootLevelTask, "---\ntitle: not inside a repository dir\n---\n");

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertTrue(snapshot.repositories().isEmpty());
        assertTrue(snapshot.documents().isEmpty());
        assertEquals(2, snapshot.failures().size());
        assertFailure(snapshot, nonUuid, "not a canonical repository id");
        // A .md directly under the root is a root entry that is a file: the
        // real classification, not a task and not a repository.
        assertFailure(snapshot, rootLevelTask, "must be <repository-id> directories");
        assertTrue(snapshot.failures().stream().noneMatch(failure -> failure.path().startsWith(plans)
                || failure.path().startsWith(runs)));
    }

    @Test
    void orphanRepositoryDirIsListedAndClaimsNoIds() throws Exception {
        repository(REPO_A);

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertEquals(List.of(RepositoryId.of(REPO_A)), snapshot.repositories());
        assertTrue(snapshot.documents().isEmpty());
        assertTrue(snapshot.byId().isEmpty());
        assertTrue(snapshot.bySessionReference().isEmpty());
        assertTrue(snapshot.failures().isEmpty());
        assertEquals(Optional.empty(), snapshot.find(TaskId.of(TASK_A)));
    }

    @Test
    void symlinksAreFlaggedNotFollowedIncludingDanglingOnes() throws Exception {
        Path repoA = repository(REPO_A);
        task(repoA, TASK_A);
        Path realTask = repoA.resolve(TASK_A + ".md");
        Path linkedRepo = root.resolve(REPO_B);
        Files.createSymbolicLink(linkedRepo, repoA);
        Path linkedTask = repoA.resolve("link-task-x1y2.md");
        Files.createSymbolicLink(linkedTask, realTask);
        Path danglingTask = repoA.resolve("dangling-task-z9z8.md");
        Files.createSymbolicLink(danglingTask, repoA.resolve("missing-target.md"));

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertEquals(List.of(RepositoryId.of(REPO_A)), snapshot.repositories());
        assertEquals(3, snapshot.failures().size());
        assertFailure(snapshot, linkedRepo, "repository entry is a symbolic link");
        assertFailure(snapshot, linkedTask, "symbolic link");
        assertFailure(snapshot, danglingTask, "symbolic link");
        // The real task behind the links still loads; none of the links claimed it.
        assertEquals(Optional.of(TaskId.of(TASK_A)), snapshot.find(TaskId.of(TASK_A)).map(TaskDocument::id));
        assertEquals(Optional.empty(), snapshot.find(TaskId.of("link-task-x1y2")));
    }

    @Test
    void missingCreatedFallsBackToTheRealFileCreationTime() throws Exception {
        Path repoA = repository(REPO_A);
        Path file = task(repoA, TASK_A);

        TaskSnapshot snapshot = TaskFiles.scan(root);

        TaskDocument document = snapshot.find(TaskId.of(TASK_A)).orElseThrow();
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        Instant created = document.created().orElseThrow();
        assertTrue(created.isAfter(Instant.EPOCH));
        assertEquals(attributes.creationTime().toInstant(), created);
    }

    @Test
    void snapshotCollectionsAreImmutable() throws Exception {
        Path repoA = repository(REPO_A);
        task(repoA, TASK_A, "sessions: [s-1]\n");
        TaskSnapshot snapshot = TaskFiles.scan(root);
        TaskDocument document = snapshot.find(TaskId.of(TASK_A)).orElseThrow();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.documents().add(document));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.failures().add(null));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.idCollisions().add(null));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.repositories().add(null));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.byId().put(TaskId.of(TASK_B), document));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.bySessionReference().put("s-2", List.of(document)));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.bySessionReference().get("s-1").add(document));
        assertEquals(Optional.of(document), snapshot.find(TaskId.of(TASK_A)));
        assertEquals(1, snapshot.documents().size());
        assertEquals(1, snapshot.byId().size());
    }

    @Test
    void twoTasksSharingASessionIdAreBothFound() throws Exception {
        Path repoA = repository(REPO_A);
        Path repoB = repository(REPO_B);
        task(repoA, TASK_A, "sessions: [s-1, s-42]\n");
        task(repoB, TASK_B, "sessions: [s-42]\n");

        TaskSnapshot snapshot = TaskFiles.scan(root);

        assertEquals(2, snapshot.bySessionReference().size());
        List<TaskDocument> shared = snapshot.bySessionReference().get("s-42");
        assertEquals(2, shared.size());
        assertTrue(shared.containsAll(snapshot.documents()));
        assertEquals(List.of(snapshot.find(TaskId.of(TASK_A)).orElseThrow(),
                snapshot.find(TaskId.of(TASK_B)).orElseThrow()), shared);
        assertEquals(1, snapshot.bySessionReference().get("s-1").size());
    }
}
