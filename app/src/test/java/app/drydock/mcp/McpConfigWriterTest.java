package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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
