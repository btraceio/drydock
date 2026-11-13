package app.drydock.intellij.tasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.projects.SharedProject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mirror flow's project resolution ({@code TaskMirroring#resolveShared}):
 * a task whose repository id the shared list carries resolves directly, a
 * task carrying the install catalog's id for the same checkout resolves
 * through the shared list's root, and an id neither registry maps resolves
 * to nothing -- the caller refuses. Pinned through the package-private pure
 * helper: no platform types, no mocking, no I/O.
 */
class TaskMirroringResolveTest {

    private static SharedProject shared(RepositoryId id, String root) {
        return SharedProject.local(id, Path.of(root), "repo", Instant.EPOCH);
    }

    private static Repository install(RepositoryId id, String root) {
        return new Repository(id, Path.of(root), "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT);
    }

    @Test
    void sharedListIdResolvesDirectly() {
        RepositoryId id = RepositoryId.newId(); // the id the IDE dialog stored
        assertEquals(Optional.of(shared(id, "/shared/root")), TaskMirroring.resolveShared(
                List.of(shared(id, "/shared/root")),
                List.of(install(RepositoryId.newId(), "/install/root")),
                id));
    }

    @Test
    void installCatalogIdResolvesThroughTheSharedRoot() {
        RepositoryId sharedId = RepositoryId.newId(); // the shared registry's id
        RepositoryId installId = RepositoryId.newId(); // the id a task_create call stored
        assertEquals(Optional.of(shared(sharedId, "/same/root")), TaskMirroring.resolveShared(
                List.of(shared(sharedId, "/same/root")),
                List.of(install(installId, "/same/root")),
                installId));
    }

    @Test
    void remoteSharedProjectResolvesToo() {
        // A Jira mirror talks to the site alone; the remote check is the caller's.
        RepositoryId sharedId = RepositoryId.newId();
        RepositoryId installId = RepositoryId.newId();
        SharedProject remote = new SharedProject(sharedId, Path.of("/placeholder"), "remote",
                Instant.EPOCH, Optional.of(new SshRemote("host", "/remote/path")));
        assertEquals(Optional.of(remote), TaskMirroring.resolveShared(
                List.of(remote),
                List.of(install(installId, "/placeholder")),
                installId));
    }

    @Test
    void rootMustMatchForTheInstallIdToResolve() {
        RepositoryId sharedId = RepositoryId.newId();
        RepositoryId installId = RepositoryId.newId();
        assertTrue(TaskMirroring.resolveShared(
                List.of(shared(sharedId, "/other/root")),
                List.of(install(installId, "/same/root")),
                installId).isEmpty());
    }

    @Test
    void unknownIdResolvesToNothing() {
        assertTrue(TaskMirroring.resolveShared(
                List.of(shared(RepositoryId.newId(), "/shared/root")),
                List.of(install(RepositoryId.newId(), "/install/root")),
                RepositoryId.newId()).isEmpty());
    }
}
