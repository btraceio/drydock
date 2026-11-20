package app.drydock.agent.identity;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityEnvFileTest {

    private final ManagedSessionId session = ManagedSessionId.newId();

    @Test
    void theEnvFileIsWrittenOwnerOnlyInsideAnOwnerOnlyDirectory(@TempDir Path base) throws Exception {
        AgentIdentityEnvFile writer = new AgentIdentityEnvFile(base);

        Path file = writer.write(session, Map.of("GH_TOKEN", "tok"));

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions,
                "the file carries a token; nobody but the owner may read it");
        Set<PosixFilePermission> directory = Files.getPosixFilePermissions(file.getParent());
        Set<PosixFilePermission> ownerOnlyDirectory = Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        assertEquals(ownerOnlyDirectory, directory,
                "the directory listing is a discovery vector of its own");
        assertEquals(ownerOnlyDirectory, Files.getPosixFilePermissions(file.getParent().getParent()),
                "the identity parent must also be owner-only");
    }

    @Test
    void anExistingIdentityParentIsRestrictedToOwnerOnly(@TempDir Path base) throws Exception {
        Path identity = base.resolve("identity");
        Files.createDirectories(identity);
        Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rwxrwxrwx"));

        new AgentIdentityEnvFile(base).write(session, Map.of("GH_TOKEN", "tok"));

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(identity));
    }

    @Test
    void everyValueIsRenderedSingleQuoted(@TempDir Path base) throws Exception {
        AgentIdentityEnvFile writer = new AgentIdentityEnvFile(base);

        writer.write(session, new LinkedHashMap<>(Map.of(
                "GIT_AUTHOR_NAME", "Bot 'The' Agent",
                "GH_TOKEN", "tok$(&);!")));

        String content = Files.readString(writerFile(base, session));
        assertTrue(content.contains("GIT_AUTHOR_NAME='Bot '\\''The'\\'' Agent'"), content);
        assertTrue(content.contains("GH_TOKEN='tok$(&);!'"), content);
    }

    @Test
    void rewritingIsIdempotent(@TempDir Path base) throws Exception {
        AgentIdentityEnvFile writer = new AgentIdentityEnvFile(base);

        Path first = writer.write(session, Map.of("GH_TOKEN", "tok"));
        Path second = writer.write(session, Map.of("GH_TOKEN", "tok"));

        assertEquals(first, second);
        assertTrue(Files.exists(first));
        assertFalse(Files.exists(first.resolveSibling(first.getFileName() + ".tmp")),
                "the atomic-write temp file is gone after the rename");
    }

    @Test
    void deleteRemovesTheFileAndItsTemp(@TempDir Path base) throws Exception {
        AgentIdentityEnvFile writer = new AgentIdentityEnvFile(base);
        writer.write(session, Map.of("GH_TOKEN", "tok"));
        Files.createFile(writerFile(base, session).resolveSibling(writerFile(base, session).getFileName() + ".tmp"));

        writer.delete(session);

        assertFalse(Files.exists(writerFile(base, session)));
        assertFalse(Files.exists(writerFile(base, session)
                .resolveSibling(writerFile(base, session).getFileName() + ".tmp")));
    }

    @Test
    void deleteOfANeverWrittenSessionIsQuiet(@TempDir Path base) {
        AgentIdentityEnvFile writer = new AgentIdentityEnvFile(base);

        writer.delete(ManagedSessionId.newId());
    }

    @Test
    void purgeStaleRemovesOnlyEntriesOlderThanTheGracePeriod(@TempDir Path base) throws Exception {
        AgentIdentityEnvFile writer = new AgentIdentityEnvFile(base);
        Path oldFile = writer.write(ManagedSessionId.newId(), Map.of("GH_TOKEN", "old"));
        Path freshFile = writer.write(ManagedSessionId.newId(), Map.of("GH_TOKEN", "fresh"));
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minusSeconds(90)));

        writer.purgeStale();

        assertFalse(Files.exists(oldFile), "stale env files are purged");
        assertTrue(Files.exists(freshFile), "a concurrent launch's fresh env file must be preserved");
    }

    @Test
    void aFailedAtomicMoveDeletesTheCredentialBearingTempFile(@TempDir Path base) throws Exception {
        AgentIdentityEnvFile writer = new AgentIdentityEnvFile(base);
        Path targetDirectory = writerFile(base, session);
        Files.createDirectories(targetDirectory);
        Path blocker = targetDirectory.resolve("blocker");
        Files.writeString(blocker, "keep");

        assertThrows(IOException.class, () -> writer.write(session, Map.of("GH_TOKEN", "secret")));

        assertFalse(Files.exists(targetDirectory.resolveSibling(targetDirectory.getFileName() + ".tmp")),
                "a failed move must not leave credential material in the temp file");
        assertTrue(Files.exists(blocker));
    }

    @Test
    void purgeStaleToleratesAMissingDirectory(@TempDir Path base) {
        new AgentIdentityEnvFile(base).purgeStale();
    }

    private Path writerFile(Path base, ManagedSessionId session) {
        return base.resolve("identity").resolve("env").resolve(session + ".env");
    }
}
