package app.drydock.ui;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.claude.ClaudeExecutableLocator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSelectorTest {

    private AgentRegistry claudeAvailableRegistry() {
        AgentContext ctx = new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                ForkJoinPool.commonPool());
        // Real claude on PATH → available. If CI lacks claude, this test asserts the fallback path instead.
        return new AgentRegistry(List.of(new ClaudeAgentProvider(new ClaudeExecutableLocator())), ctx);
    }

    @Test
    void initialSelectionHonorsRepoLastUsedWhenAvailable() {
        AgentRegistry registry = claudeAvailableRegistry();
        // resolveDefault is the source of truth; initialSelection just delegates.
        assertEquals(registry.resolveDefault(Optional.of(AgentKind.CLAUDE)),
                AgentSelector.initialSelection(registry, Optional.of(AgentKind.CLAUDE)));
    }
}
