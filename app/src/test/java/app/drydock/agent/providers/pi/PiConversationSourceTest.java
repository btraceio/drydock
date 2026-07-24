package app.drydock.agent.providers.pi;

import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.providers.pi.internal.PiSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiConversationSourceTest {

    private static Path writeSession(Path root, Path cwd, String id, String iso) throws IOException {
        Path dir = root.resolve(PiSessionStore.encodeCwdDir(cwd));
        Files.createDirectories(dir);
        Path f = dir.resolve(iso.replace(":", "-") + "_" + id + ".jsonl");
        String cwdString = cwd.toRealPath().toString();
        String meta = "{\"type\":\"session\",\"id\":\"" + id + "\",\"timestamp\":\"" + iso + "\","
                + "\"cwd\":\"" + cwdString.replace("\\", "\\\\") + "\"}\n";
        Files.writeString(f, meta);
        return f;
    }

    @Test
    void transcriptExistsTrueForKnownId(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(cwd);
        String id = "aaaa1111-0000-0000-0000-000000000001";
        writeSession(tmp, cwd, id, "2026-07-23T10:00:00.000Z");

        PiSessionStore store = new PiSessionStore(tmp);
        ConversationSource source = new PiConversationSource(store);

        assertTrue(source.transcriptExists(cwd, id));
    }

    @Test
    void transcriptExistsFalseForUnknownId(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(cwd);
        writeSession(tmp, cwd, "aaaa1111-0000-0000-0000-000000000001", "2026-07-23T10:00:00.000Z");

        PiSessionStore store = new PiSessionStore(tmp);
        ConversationSource source = new PiConversationSource(store);

        assertFalse(source.transcriptExists(cwd, "00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void listConversationsMapsSessionMetaToConversation(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(cwd);
        String id = "bbbb2222-0000-0000-0000-000000000002";
        Instant timestamp = Instant.parse("2026-07-23T11:00:00.000Z");
        writeSession(tmp, cwd, id, timestamp.toString());

        PiSessionStore store = new PiSessionStore(tmp);
        ConversationSource source = new PiConversationSource(store);

        List<ConversationSource.Conversation> conversations = source.listConversations(cwd);

        assertEquals(1, conversations.size());
        ConversationSource.Conversation conv = conversations.get(0);
        assertEquals(id, conv.sessionId());
        assertEquals(id, conv.title());
        assertEquals(0, conv.messageCount());
        assertEquals(timestamp, conv.lastModified());
    }
}
