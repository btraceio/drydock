package app.drydock.projects;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedProjectsSeedTest {

    private static final Instant T = Instant.parse("2026-10-19T10:00:00Z");

    @TempDir
    Path dir;

    private static Repository local(String root) {
        return new Repository(RepositoryId.newId(), Path.of(root), Path.of(root).getFileName().toString(), T, T,
                RepositorySettings.DEFAULT);
    }

    private static Function<Path, Optional<Path>> mains(Map<String, String> worktreeToMain) {
        return root -> Optional.of(Path.of(worktreeToMain.getOrDefault(root.toString(), root.toString())));
    }

    @Test
    void remoteKeptAsIs() {
        SshRemote remote = new SshRemote("box", "/srv/repo");
        Repository repo = new Repository(RepositoryId.newId(), remote.placeholderRoot(), "repo", T, T,
                RepositorySettings.DEFAULT, remote);

        List<SharedProject> seeded = SharedProjectsSeed.fromRepositories(List.of(repo), root -> Optional.empty());

        assertEquals(List.of(new SharedProject(repo.id(), remote.placeholderRoot(), "repo", T, Optional.of(remote))),
                seeded);
    }

    @Test
    void worktreeEntryReRootedToItsMain() {
        Repository worktree = local("/w/wt1");

        List<SharedProject> seeded = SharedProjectsSeed.fromRepositories(List.of(worktree),
                mains(Map.of("/w/wt1", "/w/main")));

        assertEquals(List.of(SharedProject.local(worktree.id(), Path.of("/w/main"), "main", T)), seeded);
    }

    @Test
    void worktreeEntryDroppedWhenMainAlsoRegistered() {
        Repository worktree = local("/w/wt1");
        Repository main = local("/w/main");

        List<SharedProject> seeded = SharedProjectsSeed.fromRepositories(List.of(worktree, main),
                mains(Map.of("/w/wt1", "/w/main")));

        assertEquals(List.of(SharedProject.local(main.id(), Path.of("/w/main"), "main", T)), seeded);
    }

    @Test
    void unresolvableRootKeptAsIs() {
        Repository gone = local("/w/gone");

        List<SharedProject> seeded = SharedProjectsSeed.fromRepositories(List.of(gone), root -> Optional.empty());

        assertEquals(List.of(SharedProject.local(gone.id(), Path.of("/w/gone"), "gone", T)), seeded);
    }

    @Test
    void newestAppStateFilePicksMostRecent() throws Exception {
        Path older = Files.createDirectories(dir.resolve("install-a")).resolve("state.json");
        Path newer = Files.createDirectories(dir.resolve("install-b")).resolve("state.json");
        Path shared = dir.resolve("state.json");
        for (Path file : List.of(older, newer, shared)) {
            Files.writeString(file, "{}");
        }
        Files.setLastModifiedTime(older, FileTime.from(T));
        Files.setLastModifiedTime(shared, FileTime.from(T.plusSeconds(60)));
        Files.setLastModifiedTime(newer, FileTime.from(T.plusSeconds(120)));

        assertEquals(Optional.of(newer), SharedProjectsSeed.newestAppStateFile(dir));
    }

    @Test
    void newestAppStateFileEmptyWhenNoAppData() {
        assertEquals(Optional.empty(), SharedProjectsSeed.newestAppStateFile(dir.resolve("absent")));
    }

    @Test
    void readRepositoriesOnlyIgnoresSessionsItCannotDecode() throws Exception {
        RepositoryId id = RepositoryId.newId();
        Path state = dir.resolve("state.json");
        Files.writeString(state, """
                {"schemaVersion":2,
                 "repositories":[{"id":"%s","root":"/w/main","displayName":"main",
                   "addedAt":"2026-10-19T10:00:00Z","lastOpenedAt":"2026-10-19T10:00:00Z","settings":{}},
                   {"id":"broken"}],
                 "sessions":[{"shape":"from the future"}]}""".formatted(id));

        List<Repository> repositories = SharedProjectsSeed.readRepositoriesOnly(state);

        assertEquals(1, repositories.size());
        assertEquals(id, repositories.get(0).id());
        assertEquals(Path.of("/w/main"), repositories.get(0).root());
    }

    @Test
    void readRepositoriesOnlyMalformedGivesEmpty() throws Exception {
        Path state = dir.resolve("state.json");
        Files.writeString(state, "{nope");

        assertEquals(List.of(), SharedProjectsSeed.readRepositoriesOnly(state));
    }
}
