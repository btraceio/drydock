package app.drydock.agent.providers.pi;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.pi.internal.PiCapabilities;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

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
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.supported());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.command().endsWith("pi"));   // env-scrub prefix (if any) + "pi"; no id
    }

    @Test
    void resumeByIdWhenKnown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.command().endsWith("pi --session '019f9072-abc'"));
    }

    @Test
    void resumeUsesPickerWhenIdUnknown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.command().endsWith("pi --resume"));   // picker; never --continue/--last
    }

    @Test
    void remoteIsUnsupported() {
        LaunchPlan createPlan = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.of(new SshRemote("host", "/remote/path")), Optional.empty()));
        assertFalse(createPlan.supported());

        LaunchPlan resumePlan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("id"), Optional.empty(), Path.of("/repo"),
                        Optional.of(new SshRemote("host", "/remote/path")), Optional.empty()));
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

    /** A provider whose version gate and extension file are both satisfied, for the bridge cases. */
    private PiAgentProvider bridgeProvider(Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                PiCapabilities.of("0.84.1"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        return p;
    }

    private static McpAccess access(Path configFile) {
        return new McpAccess("http://127.0.0.1:1234/mcp", "tok-abc", Optional.of(configFile));
    }

    @Test
    void deliveryIsConfigFile() {
        assertEquals(McpDelivery.CONFIG_FILE, provider().mcpDelivery());
    }

    @Test
    void bridgeFlagsCarryTheConfigPathAndTheExtensionButNeverTheToken(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        LaunchPlan plan = bridgeProvider(state).buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"),
                        Optional.empty(), Optional.of(access(config))));

        String command = plan.command();
        assertTrue(command.startsWith("env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='" + config + "' "),
                command);
        assertTrue(command.endsWith("pi -e '" + state.resolve("pi").resolve("drydock-mcp.ts") + "'"), command);
        assertFalse(command.contains("tok-abc"), "the token must never reach the command line");
    }

    @Test
    void bridgeFlagsSurviveAStateDirectoryContainingASpace(@TempDir Path parent) throws Exception {
        Path state = Files.createDirectories(parent.resolve("Application Support").resolve("Drydock"));
        Path config = state.resolve("mcp").resolve("s1.json");
        String command = bridgeProvider(state).buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.of(access(config))))
                .command();

        assertTrue(command.contains("DRYDOCK_MCP_CONFIG='" + config + "'"), command);
        assertTrue(command.contains("-e '" + state.resolve("pi").resolve("drydock-mcp.ts") + "'"), command);
    }

    @Test
    void resumeCarriesTheSameFlags(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        String command = bridgeProvider(state).buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"),
                        Optional.empty(), Optional.of(access(config))))
                .command();

        assertTrue(command.contains("DRYDOCK_MCP_CONFIG='" + config + "'"), command);
        assertTrue(command.endsWith("--session '019f9072-abc'"), command);
        assertTrue(command.contains(" -e '"), command);
    }

    @Test
    void subFloorPiGetsNoFlagsEvenWithMcpAccess(@TempDir Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                PiCapabilities.of("0.79.10"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));

        String command = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(state.resolve("mcp").resolve("s1.json"))))).command();

        assertTrue(command.endsWith("pi"), command);
        assertFalse(command.contains("DRYDOCK_MCP_CONFIG"), command);
    }

    @Test
    void noMcpAccessMeansNoFlagsEvenOnAModernPi(@TempDir Path state) {
        String command = bridgeProvider(state).buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.empty())).command();
        assertTrue(command.endsWith("pi"), command);
    }

    /**
     * The property the memoised future exists for: a failed write must not be
     * latched, or one transient IOException costs every Pi tab its tools for
     * the rest of the app run. This exercises the shared {@code memoised}
     * helper -- NOT the probe's own {@code worthKeeping} predicate, which every
     * bridge test bypasses via the injected capabilities. "A failed probe
     * followed by a good one yields the good version" stays on the manual
     * checklist until that injection becomes a supplier.
     */
    @Test
    void aFailedWriteIsNotLatchedAndTheNextLaunchRetries(@TempDir Path parent) throws Exception {
        Path state = Files.createDirectories(parent.resolve("state"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-xr-xr-x"));
        PiAgentProvider p = bridgeProvider(state);
        Path config = state.resolve("mcp").resolve("s1.json");

        try {
            String firstAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                    Optional.empty(), Optional.of(access(config)))).command();
            assertTrue(firstAttempt.endsWith("pi"), "a failed install must degrade, not fail the launch");
        } finally {
            // Restore in a finally, or a failed assertion also fails @TempDir
            // cleanup and buries the real error.
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        String secondAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(secondAttempt.contains(" -e '"), "the next launch must retry: " + secondAttempt);
    }

    /**
     * Two tabs opened together must both receive the path rather than one
     * racing the other into a degraded launch. Note this does NOT count writes
     * -- `memoised` guarantees one, but proving that needs a counting installer,
     * and the property that matters at this seam is that no caller degrades.
     */
    @Test
    void concurrentLaunchesShareOneInstall(@TempDir Path state) throws Exception {
        PiAgentProvider p = bridgeProvider(state);
        Path config = state.resolve("mcp").resolve("s1.json");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> calls = java.util.Collections.nCopies(4,
                    () -> p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                            Optional.empty(), Optional.of(access(config)))).command());
            for (Future<String> f : pool.invokeAll(calls)) {
                assertTrue(f.get().contains(" -e '"), f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
