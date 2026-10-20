package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ResumeCostEstimate;
import app.drydock.agent.providers.claude.internal.ConversationCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeConversationSourceTest {

    @Test
    void compactBoundaryReplacesThePreCompactionContext(@TempDir Path root) throws Exception {
        Path cwd = root.resolve("repo");
        Files.createDirectories(cwd);
        ConversationCatalog catalog = new ConversationCatalog(root.resolve("projects"));
        Path project = catalog.projectDirFor(cwd);
        Files.createDirectories(project);
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Files.writeString(project.resolve(id + ".jsonl"), """
                {"type":"assistant","message":{"model":"claude-opus-4-6","usage":{"input_tokens":10,"cache_creation_input_tokens":190000,"cache_read_input_tokens":10000}}}
                {"type":"system","subtype":"compact_boundary","compactMetadata":{"postTokens":12000}}
                """);

        ResumeCostEstimate estimate = new ClaudeConversationSource(catalog)
                .estimateResumeCost(cwd, id).orElseThrow();

        assertEquals(12_000, estimate.contextTokens());
        assertEquals(0.075, estimate.maximumInputCostUsd(), 0.000_001);
    }
}
