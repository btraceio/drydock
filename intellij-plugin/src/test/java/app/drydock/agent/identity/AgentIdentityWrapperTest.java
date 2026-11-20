package app.drydock.agent.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityWrapperTest {

    @Test
    void installsTheWrapperExecutableOwnerOnly(@TempDir Path base) throws Exception {
        Path bin = AgentIdentityWrapper.install(base);

        Path wrapper = bin.resolve("gh");
        assertTrue(Files.exists(wrapper));
        String script = Files.readString(wrapper);
        assertTrue(script.startsWith("#!/bin/sh"), "the installed file is the wrapper script itself");
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(wrapper),
                "the wrapper must be executable, and only by its owner");
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(bin),
                "the bin directory exists solely to hold the wrapper");
    }

    @Test
    void reinstallingIsIdempotent(@TempDir Path base) throws Exception {
        Path first = AgentIdentityWrapper.install(base).resolve("gh");
        long firstModified = Files.getLastModifiedTime(first).toMillis();

        Path second = AgentIdentityWrapper.install(base).resolve("gh");

        assertEquals(first, second);
        assertEquals(firstModified, Files.getLastModifiedTime(second).toMillis(),
                "an unchanged resource must not rewrite the installed copy");
    }
}
