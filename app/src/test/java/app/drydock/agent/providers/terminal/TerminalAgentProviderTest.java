package app.drydock.agent.providers.terminal;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentProviderTest {

    private TerminalAgentProvider provider() {
        TerminalAgentProvider p = new TerminalAgentProvider();
        p.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool()));
        return p;
    }

    private CreateContext create(Optional<SshRemote> remote) {
        return new CreateContext("Session 1", "unused-id", Path.of("/repo"), remote, Optional.empty());
    }

    @Test
    void identity() {
        TerminalAgentProvider p = provider();
        assertEquals(AgentKind.TERMINAL, p.kind());
        assertEquals("Terminal", p.displayName());
        assertEquals(McpDelivery.NONE, p.mcpDelivery());
        assertEquals(SessionIdStrategy.PRESET, p.idStrategy());
        assertFalse(p.evalAvailable());
        assertTrue(p.supportsRemote());
    }

    /** The local launch is the login-shell literal, no binary lookup, no flags. */
    @Test
    void createCommandIsTheLoginShell() {
        LaunchPlan plan = provider().buildCreateCommand(create(Optional.empty()));
        assertTrue(plan.supported());
        assertEquals("${SHELL:-/bin/zsh}", plan.command());
        assertFalse(plan.sessionIdUsed());
    }

    /** Resume is the same command: there is no conversation to reattach. */
    @Test
    void resumeCommandIsTheLoginShellToo() {
        ResumeContext ctx = new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"),
                Optional.empty(), Optional.empty(), false);
        LaunchPlan plan = provider().buildResumeCommand(ctx);
        assertTrue(plan.supported());
        assertEquals("${SHELL:-/bin/zsh}", plan.command());
    }

    /** Remote rides the same interactive-ssh shape Claude uses: cd, then exec the remote shell. */
    @Test
    void remoteCommandExecsTheShellOnTheRemoteHost() {
        LaunchPlan plan = provider().buildCreateCommand(create(Optional.of(new SshRemote("host.example", "/srv/repo"))));
        assertTrue(plan.supported());
        assertTrue(plan.command().startsWith("ssh -t "), plan.command());
        assertTrue(plan.command().contains("/srv/repo"), plan.command());
        assertTrue(plan.command().contains("&& exec \"${SHELL:-/bin/zsh}\""), plan.command());
        assertFalse(plan.sessionIdUsed());
    }

    /** A shell always exists, so the kind is never reported as not-installed. */
    @Test
    void executableIsAlwaysFound() {
        assertTrue(provider().locateExecutable().isPresent());
    }
}
