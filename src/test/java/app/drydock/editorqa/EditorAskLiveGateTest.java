package app.drydock.editorqa;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AC-6 release gate. Live runs need authorized Pi/Claude/Codex CLIs and are executed by a human following
 * docs/editor-ask-agent-validation.md; this class never spawns a CLI. It is opt-in
 * ({@code DRYDOCK_EDITOR_ASK_LIVE=1}) and then checks that the recorded evidence file covers every scenario.
 */
class EditorAskLiveGateTest {

    static final Path RUNBOOK = Path.of("docs/editor-ask-agent-validation.md");

    static final List<String> SCENARIOS = List.of(
            "pi-direct-tab-turn", "pi-tool-then-continue", "pi-sequential-b-c", "pi-multi-step",
            "claude-focused", "claude-background", "claude-blocked-stop", "claude-permission-vs-idle",
            "codex-direct-tab-turn", "codex-sequential-b-c", "codex-stale-rollout",
            "codex-fresh-create", "codex-exact-resume");

    @Test
    void theRunbookNamesEveryScenarioAndRecordsTheGateState() throws IOException {
        String doc = Files.readString(RUNBOOK, StandardCharsets.UTF_8);
        for (String scenario : SCENARIOS) {
            assertTrue(doc.contains("`" + scenario + "`"), "runbook lacks scenario " + scenario);
        }
        assertTrue(doc.contains("RELEASE GATE: UNPASSED") || doc.contains("RELEASE GATE: PASSED"),
                "the runbook states the gate explicitly");
    }

    @Test
    void liveEvidenceCoversEveryScenarioWhenOptedIn() throws IOException {
        assumeTrue("1".equals(System.getenv("DRYDOCK_EDITOR_ASK_LIVE")), "live gate is opt-in");
        String evidencePath = System.getenv("DRYDOCK_EDITOR_ASK_LIVE_EVIDENCE");
        assumeTrue(evidencePath != null && Files.isRegularFile(Path.of(evidencePath)),
                "no live evidence file: the gate stays unpassed");
        String evidence = Files.readString(Path.of(evidencePath), StandardCharsets.UTF_8);
        for (String scenario : SCENARIOS) {
            assertTrue(evidence.contains(scenario + ": PASS"), "no passing live result for " + scenario);
        }
    }
}
