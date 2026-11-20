package app.drydock.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateDirectoryTest {

    @TempDir
    Path temp;

    private Path distCodeSource() {
        // A path with the shape a real install has: distinct from another
        // install's, and stable for hashing (installDirectoryName names from
        // the path string alone; it never touches the filesystem here).
        return temp.resolve("build/dist/Drydock.app/Contents/app/app-0.1.0.jar");
    }

    @Test
    void aLaunchWithoutACodeSourceKeepsTheSharedDirectoryItHasAlwaysUsed() throws IOException {
        Path dataRoot = Files.createDirectory(temp.resolve("ClaudeProjectManager"));

        Path stateFile = StateDirectory.stateFileUnder(dataRoot, null);

        assertEquals(dataRoot.resolve("state.json"), stateFile);
    }

    @Test
    void anInstallResolvesStateInsideADirectoryNamedForItsCodeSource() throws IOException {
        Path dataRoot = temp.resolve("ClaudeProjectManager");

        Path stateFile = StateDirectory.stateFileUnder(dataRoot, distCodeSource());

        assertEquals(dataRoot, stateFile.getParent().getParent(), "<data root>/<install id>/state.json");
        assertTrue(stateFile.getParent().getFileName().toString().startsWith("install-"),
                "the install directory is named install-<id>, was " + stateFile.getParent());
    }

    @Test
    void resolvingALocationCreatesNothing() throws IOException {
        // The data root carries another install's full state; resolving the
        // state file for THIS install must not touch or create anything --
        // the install directory appears when a write makes it, never at
        // resolution. (An earlier revision copied the shared state into the
        // new install's directory on first start; that re-instated the very
        // mix per-launch-path state exists to remove.)
        Path dataRoot = Files.createDirectory(temp.resolve("ClaudeProjectManager"));
        write(dataRoot.resolve("state.json"), "{\"schemaVersion\":9}");

        Path stateFile = StateDirectory.stateFileUnder(dataRoot, distCodeSource());

        assertTrue(stateFile.getParent().getParent().equals(dataRoot));
        assertFalse(Files.exists(stateFile.getParent()),
                "the install directory is created by a write, not by resolving where it would be");
        try (var listing = Files.list(temp)) {
            assertEquals(List.of("ClaudeProjectManager"),
                    listing.map(path -> path.getFileName().toString()).sorted().toList(),
                    "nothing appears beside the data root, not even empty directories");
        }
    }

    @Test
    void theSameCodeSourceNamesTheSameDirectoryAndAnotherNamesAnother() throws IOException {
        String name = StateDirectory.installDirectoryName(distCodeSource());

        assertEquals(name, StateDirectory.installDirectoryName(distCodeSource()),
                "stable across restarts: the name is derived, not stored");
        assertNotEquals(name, StateDirectory.installDirectoryName(temp.resolve("build/image/app/app-0.1.0.jar")),
                "another launch path gets its own directory");
        assertTrue(name.startsWith("install-"));
    }

    @Test
    void anInstallOwnsTheWholeDirectoryAndNothingOfAnotherInstall() throws IOException {
        // Two launches that wrote independently: the older install's state
        // file must not be the newer's, and each must be its own directory --
        // no state is ever shared, read, or copied between them.
        Path dataRoot = temp.resolve("ClaudeProjectManager");
        Path older = StateDirectory.stateFileUnder(dataRoot, distCodeSource());
        write(older, "older install's state");
        Path newer = StateDirectory.stateFileUnder(dataRoot, temp.resolve("build/image/app/app-0.1.0.jar"));
        write(newer, "newer install's state");

        assertNotEquals(older, newer);
        assertEquals("older install's state", Files.readString(older));
        assertEquals("newer install's state", Files.readString(newer));
    }

    @Test
    void theInstanceLockIsExclusiveWithinAStateDirectory() throws IOException {
        Path directory = Files.createDirectory(temp.resolve("ClaudeProjectManager"));

        StateDirectory.InstanceLock first = StateDirectory.tryLockInstance(directory);
        assertInstanceOf(StateDirectory.InstanceLock.Held.class, first, "the first instance claims the lock");
        // A second claim is refused. Within ONE JVM the refusal surfaces as
        // Failed(OverlappingFileLockException) -- the JVM-wide lock table
        // rejects a second channel on the same file; the HeldByOther outcome
        // (tryLock returned null) is the cross-process second instance, which
        // is what the app's own double-launch case hits.
        StateDirectory.InstanceLock second = StateDirectory.tryLockInstance(directory);
        assertFalse(second instanceof StateDirectory.InstanceLock.Held, "a second claim is refused");

        StateDirectory.releaseInstance(((StateDirectory.InstanceLock.Held) first).channel());
        StateDirectory.InstanceLock again = StateDirectory.tryLockInstance(directory);
        assertInstanceOf(StateDirectory.InstanceLock.Held.class, again,
                "the released lock is free for the next launch");
        StateDirectory.releaseInstance(((StateDirectory.InstanceLock.Held) again).channel());
    }

    @Test
    void anUnwritableStateDirectoryIsAFailureNotASecondInstance() throws IOException {
        // The tri-state contract this pins: an I/O failure (here: the lock
        // file's parent does not exist and cannot be created because its
        // parent is a FILE) is NOT "another instance holds it" -- the caller
        // shows a permissions error, never a "close the other Drydock".
        Path fileNotDirectory = Files.createFile(temp.resolve("a-file"));
        StateDirectory.InstanceLock failed =
                StateDirectory.tryLockInstance(fileNotDirectory.resolve("state"));
        assertInstanceOf(StateDirectory.InstanceLock.Failed.class, failed);
        assertInstanceOf(IOException.class, ((StateDirectory.InstanceLock.Failed) failed).cause());
    }

    @Test
    void distinctStateDirectoriesLockIndependently() throws IOException {
        StateDirectory.InstanceLock dist = StateDirectory.tryLockInstance(temp.resolve("dist"));
        StateDirectory.InstanceLock image = StateDirectory.tryLockInstance(temp.resolve("image"));

        assertInstanceOf(StateDirectory.InstanceLock.Held.class, dist);
        assertInstanceOf(StateDirectory.InstanceLock.Held.class, image,
                "another launch path runs beside this one, with its own lock");
        StateDirectory.releaseInstance(((StateDirectory.InstanceLock.Held) dist).channel());
        StateDirectory.releaseInstance(((StateDirectory.InstanceLock.Held) image).channel());
    }

    @Test
    void releasingTheInstanceLockIsIdempotent() throws IOException {
        java.nio.channels.FileChannel lock =
                ((StateDirectory.InstanceLock.Held) StateDirectory.tryLockInstance(temp.resolve("dist"))).channel();

        StateDirectory.releaseInstance(lock);
        StateDirectory.releaseInstance(lock);

        assertFalse(lock.isOpen(), "released once, and a second release is a no-op");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}