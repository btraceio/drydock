package app.drydock.domain;

import app.drydock.testing.TestPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshRemoteTest {

    @Test
    void placeholderRootIsAbsoluteNormalizedAndStable() {
        SshRemote remote = new SshRemote("user@build.example.com", "/srv/repos/app");
        Path root = remote.placeholderRoot();
        assertTrue(root.isAbsolute());
        assertEquals(root.normalize(), root);
        // Contained in the .drydock-remote subtree anchored at the product's
        // anchor (the user home's drive root -- see placeholderRoot's
        // javadoc), and carrying the encoded host and the remote path.
        assertTrue(root.startsWith(remoteRootPrefix()), "got " + root);
        assertTrue(root.endsWith("srv/repos/app"), "got " + root);
        // Stable: same inputs, same root (dedupe key).
        assertEquals(root, new SshRemote("user@build.example.com", "/srv/repos/app").placeholderRoot());
    }

    @Test
    void placeholderRootDistinguishesHosts() {
        assertNotEquals(
                new SshRemote("host-a", "/srv/app").placeholderRoot(),
                new SshRemote("host-b", "/srv/app").placeholderRoot());
    }

    @Test
    void placeholderRootEncodesHostPathSafely() {
        // A hostile host alias must not escape the .drydock-remote subtree.
        Path root = new SshRemote("a/../../etc", "/x").placeholderRoot();
        assertTrue(root.startsWith(remoteRootPrefix()), "got " + root);
        assertEquals(root.normalize(), root);
    }

    @Test
    void rejectsOptionInjectionHost() {
        assertThrows(IllegalArgumentException.class, () -> new SshRemote("-oProxyCommand=evil", "/x"));
    }

    @Test
    void rejectsBlankAndRelative() {
        assertThrows(IllegalArgumentException.class, () -> new SshRemote(" ", "/x"));
        assertThrows(IllegalArgumentException.class, () -> new SshRemote("h", "relative/path"));
        assertThrows(IllegalArgumentException.class, () -> new SshRemote("h", " "));
    }

    @Test
    void repositoryConvenienceConstructorIsLocal() {
        Repository repo = new Repository(RepositoryId.newId(), TestPaths.abs("/tmp/x"), "x",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, RepositorySettings.DEFAULT);
        assertFalse(repo.isRemote());
    }

    @Test
    void repositoryWithRemoteIsRemoteAndKeepsWithers() {
        SshRemote remote = new SshRemote("h", "/srv/app");
        Repository repo = new Repository(RepositoryId.newId(), remote.placeholderRoot(), "app",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, RepositorySettings.DEFAULT, remote);
        assertTrue(repo.isRemote());
        assertTrue(repo.withDisplayName("y").isRemote());
        assertTrue(repo.withLastOpenedAt(java.time.Instant.MAX).isRemote());
    }

    /** The product's .drydock-remote anchor: the user home's drive root. */
    private static Path remoteRootPrefix() {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().getRoot().resolve(".drydock-remote");
    }
}
