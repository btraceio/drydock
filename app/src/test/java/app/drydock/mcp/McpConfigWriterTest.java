package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigWriterTest {

    @Test
    void writesAnMcpServerEntryCarryingTheEndpointAndToken(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);

        Path config = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:54321/mcp", "tok-abc");

        JsonValue parsed = JsonParser.parse(Files.readString(config));
        JsonValue entry = JsonPeek.field(JsonPeek.field(parsed, "mcpServers"), "drydock");
        assertEquals("http", JsonPeek.str(entry, "type"));
        assertEquals("http://127.0.0.1:54321/mcp", JsonPeek.str(entry, "url"));
        assertEquals("tok-abc", JsonPeek.str(JsonPeek.field(entry, "headers"), "X-Drydock-Session-Token"));
    }

    @Test
    void eachSessionGetsItsOwnFile(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);

        Path first = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "a");
        Path second = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "b");

        assertFalse(first.equals(second), "a per-session token demands a per-session file");
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    @Test
    void rewritingIsIdempotent(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);
        ManagedSessionId session = ManagedSessionId.newId();

        Path first = writer.writeFor(session, "http://127.0.0.1:1/mcp", "tok");
        Path second = writer.writeFor(session, "http://127.0.0.1:1/mcp", "tok");

        assertEquals(first, second);
        assertEquals(Files.readString(first), Files.readString(second));
    }

    /**
     * The {@code mcp/} directory exists only to hold token files, so its
     * listing is a discovery vector in its own right (the design says as much).
     * Owner-only, not whatever the umask allowed.
     */
    @Test
    void theMcpDirectoryIsOwnerOnly(@TempDir Path base) throws Exception {
        Path config = new McpConfigWriter(base).writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "tok");

        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(config.getParent()),
                "the directory holding bearer tokens must not be readable by anyone else");
    }

    /** ...including one left looser by an earlier run: it is tightened, not trusted. */
    @Test
    void anExistingLooseMcpDirectoryIsTightened(@TempDir Path base) throws Exception {
        Path directory = Files.createDirectory(base.resolve("mcp"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));

        new McpConfigWriter(base).writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "tok");

        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(directory));
    }

    @Test
    void theFileIsNotReadableByOtherUsers(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);

        Path config = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "secret-token");

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(config);
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ),
                "a file holding a bearer token must not be world-readable: " + permissions);
        assertFalse(permissions.contains(PosixFilePermission.GROUP_READ),
                "a file holding a bearer token must not be group-readable: " + permissions);
    }

    @Test
    void aTokenWithJsonMetacharactersIsEscapedNotConcatenated(@TempDir Path base) throws Exception {
        // The token is base64url today, but the writer must not depend on that.
        McpConfigWriter writer = new McpConfigWriter(base);

        Path config = writer.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "a\"b\\c");

        JsonValue parsed = JsonParser.parse(Files.readString(config));
        JsonValue entry = JsonPeek.field(JsonPeek.field(parsed, "mcpServers"), "drydock");
        assertEquals("a\"b\\c", JsonPeek.str(JsonPeek.field(entry, "headers"), "X-Drydock-Session-Token"));
    }

    @Test
    void deleteRemovesTheFileAndIsSilentWhenAlreadyGone(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);
        ManagedSessionId session = ManagedSessionId.newId();
        Path config = writer.writeFor(session, "http://127.0.0.1:1/mcp", "tok");

        writer.delete(session);
        assertFalse(Files.exists(config));

        writer.delete(session);
    }

    // ---- the bare token file, for a command-line provider -------------------

    /**
     * The whole point of this file is that the launch command can name it
     * instead of carrying the token, so its content must be the token and
     * nothing else -- no newline, no wrapper -- because a shell reads it with
     * {@code $(cat …)} and hands the result straight to the agent.
     */
    @Test
    void theTokenFileHoldsTheBareTokenWithNothingAroundIt(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);

        Path tokenFile = writer.writeTokenFor(ManagedSessionId.newId(), "tok-abc");

        assertEquals("tok-abc", Files.readString(tokenFile));
    }

    @Test
    void theTokenFileIsNotReadableByOtherUsers(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);

        Path tokenFile = writer.writeTokenFor(ManagedSessionId.newId(), "tok-abc");

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokenFile);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
    }

    /**
     * A session has a JSON config or a token file, never both -- but delete()
     * cannot know which, and removing only the JSON would leave the token of
     * every command-line session on disk after it closed.
     */
    @Test
    void deleteRemovesTheTokenFileToo(@TempDir Path base) throws Exception {
        McpConfigWriter writer = new McpConfigWriter(base);
        ManagedSessionId session = ManagedSessionId.newId();
        Path tokenFile = writer.writeTokenFor(session, "tok-abc");

        writer.delete(session);

        assertFalse(Files.exists(tokenFile));
    }

    @Test
    void purgeStaleDropsTokenFilesFromAPreviousRun(@TempDir Path base) throws Exception {
        Path stale = new McpConfigWriter(base).writeTokenFor(ManagedSessionId.newId(), "old");

        new McpConfigWriter(base).purgeStale();

        assertFalse(Files.exists(stale));
    }

    @Test
    void purgeStaleDropsConfigsFromAPreviousRun(@TempDir Path base) throws Exception {
        // No terminal process survives an app restart, so every file present at
        // startup is stale -- and each holds a token that no longer resolves.
        // Mirrors ClaudeHookInstaller.purgeStaleActivity.
        McpConfigWriter first = new McpConfigWriter(base);
        Path stale = first.writeFor(ManagedSessionId.newId(), "http://127.0.0.1:1/mcp", "old");

        new McpConfigWriter(base).purgeStale();

        assertFalse(Files.exists(stale));
    }

    @Test
    void purgeStaleOnAFreshInstallIsSilent(@TempDir Path base) {
        // The mcp/ directory does not exist yet on a first run; Files.list
        // would throw NoSuchFileException. This is the expected, common
        // case -- not just "does not throw" but "does not even warn".
        Logger logger = Logger.getLogger(McpConfigWriter.class.getName());
        List<LogRecord> published = new ArrayList<>();
        Handler recorder = new Handler() {
            @Override
            public void publish(LogRecord record) {
                published.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(recorder);
        try {
            new McpConfigWriter(base.resolve("never-created")).purgeStale();

            assertTrue(published.stream().noneMatch(record -> record.getLevel().intValue() >= Level.WARNING.intValue()),
                    "a missing mcp/ directory on a first run is expected, not a warning: " + published);
        } finally {
            logger.removeHandler(recorder);
        }
    }
}
