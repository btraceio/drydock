package app.drydock.agent.providers.pi;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiAgentProviderTest {

    private PiAgentProvider provider() {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")));
        p.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool()));
        return p;
    }

    @Test
    void identity() {
        PiAgentProvider p = provider();
        assertEquals(AgentKind.PI, p.kind());
        assertEquals("Pi", p.displayName());
        assertEquals(SessionIdStrategy.DISCOVERED, p.idStrategy());
    }

    @Test
    void createCarriesNoId() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty()));
        assertTrue(plan.supported());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.command().endsWith("pi"));   // env-scrub prefix (if any) + "pi"; no id
    }

    @Test
    void resumeByIdWhenKnown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"), Optional.empty()));
        assertTrue(plan.command().endsWith("pi --session '019f9072-abc'"));
    }

    @Test
    void resumeUsesPickerWhenIdUnknown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty()));
        assertTrue(plan.command().endsWith("pi --resume"));   // picker; never --continue/--last
    }

    @Test
    void remoteIsUnsupported() {
        LaunchPlan createPlan = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.of(new SshRemote("host", "/remote/path"))));
        assertFalse(createPlan.supported());

        LaunchPlan resumePlan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("id"), Optional.empty(), Path.of("/repo"),
                        Optional.of(new SshRemote("host", "/remote/path"))));
        assertFalse(resumePlan.supported());

        assertFalse(provider().supportsRemote());
    }

    @Test
    void activityAndRemoteDeclinedButConversationsAndDiscoveryPresent() {
        PiAgentProvider p = provider();
        assertTrue(p.activity().isEmpty());
        assertTrue(p.conversations().isPresent());
        assertTrue(p.idDiscovery().isPresent());
    }
}
