package app.cpm.claude;

import app.cpm.claude.ConversationCatalog.Conversation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationCatalogTest {

    @TempDir
    Path projectsRoot;

    private ConversationCatalog catalogFor(Path workingDirectory, String... transcriptLines) throws IOException {
        ConversationCatalog catalog = new ConversationCatalog(projectsRoot);
        Path projectDir = Files.createDirectories(catalog.projectDirFor(workingDirectory));
        Files.writeString(projectDir.resolve("11111111-2222-3333-4444-555555555555.jsonl"),
                String.join("\n", transcriptLines) + "\n");
        return catalog;
    }

    @Test
    void encodesTheWorkingDirectoryLikeClaudeDoes() {
        ConversationCatalog catalog = new ConversationCatalog(projectsRoot);
        assertEquals(projectsRoot.resolve("-private-tmp-cpm-visual-repo"),
                catalog.projectDirFor(Path.of("/private/tmp/cpm-visual-repo")));
    }

    @Test
    void prefersTheLatestSummaryAsTitleAndCountsMessages() throws IOException {
        Path repo = Path.of("/tmp/some-repo");
        ConversationCatalog catalog = catalogFor(repo,
                "{\"type\":\"summary\",\"summary\":\"Old title\"}",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"say just mango\"}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"mango\"}]}}",
                "{\"type\":\"summary\",\"summary\":\"Fresh title\"}");

        List<Conversation> conversations = catalog.listConversations(repo);

        assertEquals(1, conversations.size());
        Conversation conversation = conversations.get(0);
        assertEquals("11111111-2222-3333-4444-555555555555", conversation.sessionId());
        assertEquals("Fresh title", conversation.title());
        assertEquals(2, conversation.messageCount());
    }

    @Test
    void fallsBackToTheFirstUserMessageWhenNoSummaryExists() throws IOException {
        Path repo = Path.of("/tmp/other-repo");
        ConversationCatalog catalog = catalogFor(repo,
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"  fix the \\n build  \"}]}}");

        List<Conversation> conversations = catalog.listConversations(repo);

        assertEquals(1, conversations.size());
        assertEquals("fix the build", conversations.get(0).title());
    }

    @Test
    void skipsTranscriptsWithNoMessagesAtAll() throws IOException {
        Path repo = Path.of("/tmp/empty-repo");
        ConversationCatalog catalog = catalogFor(repo,
                "{\"type\":\"summary\",\"summary\":\"Nothing here\"}");

        assertTrue(catalog.listConversations(repo).isEmpty());
    }

    @Test
    void listingAMissingProjectDirectoryIsEmptyNotAnError() {
        ConversationCatalog catalog = new ConversationCatalog(projectsRoot);
        assertTrue(catalog.listConversations(Path.of("/nowhere/at/all")).isEmpty());
    }
}
