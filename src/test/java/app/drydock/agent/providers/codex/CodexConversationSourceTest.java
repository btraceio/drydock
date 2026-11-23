package app.drydock.agent.providers.codex;

import app.drydock.agent.api.ResumeCostEstimate;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexConversationSourceTest {

    @Test
    void latestTurnModelAndInputUsageEstimateResumeCost(@TempDir Path root) throws Exception {
        String id = "11111111-2222-3333-4444-555555555555";
        Path bucket = root.resolve("2026/08/23");
        Files.createDirectories(bucket);
        Path rollout = bucket.resolve("rollout-2026-08-23T10-00-00-" + id + ".jsonl");
        Files.writeString(rollout, """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/tmp","timestamp":"2026-08-23T10:00:00Z","source":"cli"}}
                {"type":"turn_context","payload":{"model":"gpt-5.6-sol"}}
                {"type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":400000,"cached_input_tokens":390000}}}}
                """.formatted(id));

        ResumeCostEstimate estimate = new CodexConversationSource(new CodexRolloutStore(root))
                .estimateResumeCost(Path.of("/tmp"), id).orElseThrow();

        assertEquals(400_000, estimate.contextTokens());
        assertEquals(2.5, estimate.maximumInputCostUsd(), 0.000_001);
    }
}
