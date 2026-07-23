package app.drydock.agent.providers.codex;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAgentProviderTest {

    private CodexAgentProvider provider() {
        CodexAgentProvider p = new CodexAgentProvider(new CodexExecutableLocator(Path.of("/nonexistent/codex")));
        p.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool()));
        return p;
    }

    @Test
    void identity() {
        CodexAgentProvider p = provider();
        assertEquals(AgentKind.CODEX, p.kind());
        assertEquals("Codex", p.displayName());
        assertEquals(SessionIdStrategy.DISCOVERED, p.idStrategy());
    }

    @Test
    void createCarriesNoIdAndNoSettings() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty()));
        assertTrue(plan.supported());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.command().endsWith("codex"));   // env-scrub prefix (if any) + "codex"; no id, no --settings
    }

    @Test
    void resumeByIdWhenKnown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"), Optional.empty()));
        assertTrue(plan.command().endsWith("codex resume '019f9072-abc'"));
    }

    @Test
    void resumeUsesPickerWhenIdUnknown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty()));
        assertTrue(plan.command().endsWith("codex resume"));   // picker; never --last
    }

    @Test
    void remoteIsUnsupported() {
        // A remote CreateContext yields an unsupported plan (Codex declines remote).
        LaunchPlan plan = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.of(new SshRemote("host", "/remote/path"))));
        assertFalse(plan.supported());
        assertFalse(provider().probeCapabilities().supportsRemote());
    }

    @Test
    void activityAndRemoteDeclinedButConversationsAndDiscoveryPresent() {
        CodexAgentProvider p = provider();
        assertTrue(p.activity().isEmpty());
        assertTrue(p.conversations().isPresent());
        assertTrue(p.idDiscovery().isPresent());
    }
}
