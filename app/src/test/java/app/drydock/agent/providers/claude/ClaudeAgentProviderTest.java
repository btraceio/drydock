package app.drydock.agent.providers.claude;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeAgentProviderTest {

    private static final String ENV = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT ";

    /** Force "not found" so capability detection yields the conservative all-false caps deterministically. */
    private ClaudeAgentProvider newProviderNoExecutable() {
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        provider.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        return provider;
    }

    @Test
    void kindIsClaude() {
        assertEquals(AgentKind.CLAUDE, newProviderNoExecutable().kind());
    }

    @Test
    void idStrategyIsPreset() {
        assertEquals(SessionIdStrategy.PRESET, newProviderNoExecutable().idStrategy());
    }

    @Test
    void createWithConservativeCapsIsBarClaude() {
        // With no executable, caps detect conservatively (no -n/--session-id/--settings).
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(
                new CreateContext("Session 1", "uuid-1", Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude", plan.command());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.supported());
    }

    @Test
    void resumePrefersSessionId() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.of("name"), Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude --resume 'abc-123'", plan.command());
    }

    @Test
    void resumeFallsBackToName() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.of("my-name"), Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude --resume 'my-name'", plan.command());
    }

    @Test
    void resumeFallsBackToBare() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/tmp"), Optional.empty()));
        assertEquals(ENV + "claude --resume", plan.command());
    }

    @Test
    void claudeSupportsRemote() {
        assertTrue(newProviderNoExecutable().probeCapabilities().supportsRemote());
    }
}
