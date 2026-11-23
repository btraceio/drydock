package app.drydock.agent.providers.pi;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.LocalWorkflowAccess;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.providers.pi.internal.PiCapabilities;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pi compatibility with {@link LocalWorkflowAccess}: pi has no add-dir flag
 * and reads an absolute staged path through its ordinary filesystem
 * behaviour under unchanged permissions, so the typed access must not alter
 * any pi command line. No flag is invented; the staged path and eval
 * namespace never appear. Byte-identity is modulo the per-launch random
 * PI_SESSION_ID that piCommand mints on every invocation.
 */
class PiAgentProviderWorkflowAccessTest {

    private static final Path STAGED = Path.of("/Users/me/Application Support/Drydock/workflow/s1");
    private static final String NAMESPACE = "eval-ns-7f3a";

    private static final LocalWorkflowAccess ACCESS =
            new LocalWorkflowAccess(List.of(STAGED), Optional.of(NAMESPACE));

    private static PiAgentProvider provider(Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of("1.3.0"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        return p;
    }

    private static String withoutSessionId(LaunchPlan plan) {
        return plan.command().replaceAll("PI_SESSION_ID='[^']*'", "PI_SESSION_ID='<id>'");
    }

    private static void assertNoWorkflowRendering(String command) {
        assertFalse(command.contains(STAGED.toString()), command);
        assertFalse(command.contains(NAMESPACE), command);
        assertFalse(command.contains("--add-dir"), command);
    }

    @Test
    void createCommandIsByteIdenticalWithLocalWorkflowAccess(@TempDir Path state) {
        CreateContext plain = new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.empty());
        PiAgentProvider provider = provider(state);
        LaunchPlan without = provider.buildCreateCommand(plain);
        LaunchPlan with = provider.buildCreateCommand(plain.withWorkflowAccess(ACCESS));
        assertTrue(with.supported());
        assertEquals(without.sessionIdUsed(), with.sessionIdUsed());
        assertEquals(withoutSessionId(without), withoutSessionId(with));
        assertNoWorkflowRendering(with.command());
    }

    @Test
    void resumeCommandIsByteIdenticalWithLocalWorkflowAccess(@TempDir Path state) {
        ResumeContext plain = new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(),
                Path.of("/repo"), Optional.empty(), Optional.empty());
        PiAgentProvider provider = provider(state);
        LaunchPlan without = provider.buildResumeCommand(plain);
        LaunchPlan with = provider.buildResumeCommand(plain.withWorkflowAccess(ACCESS));
        assertTrue(with.supported());
        assertEquals(without.sessionIdUsed(), with.sessionIdUsed());
        assertEquals(withoutSessionId(without), withoutSessionId(with));
        assertNoWorkflowRendering(with.command());
    }

    /** Read-only and eval gates are unchanged by the access: same line with and without it. */
    @Test
    void gatedLaunchesAreUnchangedByWorkflowAccess(@TempDir Path state) {
        CreateContext plain = new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.empty(),
                Optional.of("some-model"), true, true, Optional.empty(), List.of(), Optional.empty());
        PiAgentProvider provider = provider(state);
        LaunchPlan without = provider.buildCreateCommand(plain);
        LaunchPlan with = provider.buildCreateCommand(plain.withWorkflowAccess(ACCESS));
        assertEquals(without.supported(), with.supported());
        assertEquals(withoutSessionId(without), withoutSessionId(with));
    }
}
