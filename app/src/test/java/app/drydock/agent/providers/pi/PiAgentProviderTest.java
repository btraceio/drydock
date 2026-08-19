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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
    void evalCreateSetsEvalEnvFlag() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"),
                        Optional.empty(), Optional.empty(), true));
        String command = plan.command();
        assertTrue(command.contains("DRYDOCK_EVAL=1 "), command);
        assertTrue(command.endsWith("pi"));
    }

    @Test
    void nonEvalCreateOmitsEvalEnvFlag() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"),
                        Optional.empty(), Optional.empty(), false));
        assertFalse(plan.command().contains("DRYDOCK_EVAL"));
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
                () -> PiCapabilities.of("0.84.1"));
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
        assertFalse(command.contains("tok-abc"), "the token must never reach the command line");
    }

    @Test
    void subFloorPiGetsNoFlagsEvenWithMcpAccess(@TempDir Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of("0.79.10"));
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
     * helper via the extension-install slot; {@link
     * #aFailedProbeIsNotLatchedAndTheNextLaunchRetries} exercises the same
     * un-publish path via the capabilities slot.
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
     * The capabilities-slot counterpart to {@link
     * #aFailedWriteIsNotLatchedAndTheNextLaunchRetries}: a probe reporting
     * {@code "unknown"} on its first call must not be latched, or one
     * transient probe failure costs every Pi tab its tools for the rest of
     * the app run. Also the only test that drives an injected probe through
     * {@code memoised} instead of around it, so it is the one place the
     * "start both, join both" path and the un-publish branch are both
     * actually exercised for the capabilities slot.
     */
    @Test
    void aFailedProbeIsNotLatchedAndTheNextLaunchRetries(@TempDir Path state) {
        AtomicInteger calls = new AtomicInteger();
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of(calls.incrementAndGet() == 1 ? "unknown" : "0.84.1"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        Path config = state.resolve("mcp").resolve("s1.json");

        String firstAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(firstAttempt.endsWith("pi"), "an \"unknown\" probe must not carry the bridge flags: " + firstAttempt);

        String secondAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(secondAttempt.contains(" -e '"),
                "a probe that recovers must be retried, not latched to \"unknown\": " + secondAttempt);
    }

    /**
     * F2: a version that parses but sits below the 0.80.3 floor is worth
     * memoising -- re-probing cannot change it. Unlike {@link
     * #aFailedProbeIsNotLatchedAndTheNextLaunchRetries}, the second call must
     * see the SAME degraded result even though the injected probe would
     * happily report a modern version on a later call.
     */
    @Test
    void aParsedSubFloorVersionIsMemoisedAndNotReprobed(@TempDir Path state) {
        AtomicInteger calls = new AtomicInteger();
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of(calls.incrementAndGet() == 1 ? "0.79.10" : "0.84.1"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        Path config = state.resolve("mcp").resolve("s1.json");

        String firstAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(firstAttempt.endsWith("pi"), "0.79.10 is below the floor: " + firstAttempt);

        String secondAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(secondAttempt.endsWith("pi"),
                "a parsed sub-floor read must stay memoised, not re-probed: " + secondAttempt);
        assertEquals(1, calls.get(), "the probe must run exactly once");
    }

    /**
     * F2: a version that does NOT parse -- here a stray "v" prefix surviving
     * {@code PiVersionProbe}'s whitespace-token parse -- must not be latched
     * any more than the probe's own {@code "unknown"} is. Complements {@link
     * #aFailedProbeIsNotLatchedAndTheNextLaunchRetries}, which covers the
     * literal {@code "unknown"} case.
     */
    @Test
    void anUnparseableVersionIsNotLatchedAndTheNextLaunchRetries(@TempDir Path state) {
        AtomicInteger calls = new AtomicInteger();
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of(calls.incrementAndGet() == 1 ? "v0.84.1" : "0.84.1"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        Path config = state.resolve("mcp").resolve("s1.json");

        String firstAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(firstAttempt.endsWith("pi"), "an unparseable version must not carry the bridge flags: " + firstAttempt);

        String secondAttempt = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(secondAttempt.contains(" -e '"),
                "an unparseable read must be retried, not latched: " + secondAttempt);
    }

    /**
     * Two tabs opened together must both receive the path rather than one
     * racing the other into a degraded launch. This does NOT prove
     * {@code memoised} de-duplicated the install to a single write --
     * {@code PiExtensionInstaller} is safe under concurrency by construction
     * (atomic, per-call temp names), so duplicate writes are harmless and not
     * worth a test-only counting seam. What this checks is only that no
     * concurrent caller degrades.
     */
    @Test
    void concurrentLaunchesAllReceiveTheExtensionPath(@TempDir Path state) throws Exception {
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

    /**
     * The guard that keeps a vanished extension file from stopping a Pi tab
     * starting at all.
     *
     * <p>{@code pi -e <missing>} exits 1 -- the tab never opens -- so a memo
     * that outlived its file would take down every later launch in the app
     * run. That is the one failure the whole bridge is built to avoid, and
     * until this test it rested on code-level reasoning alone.</p>
     *
     * <p>Two properties, and the second is the one an implementation can pass
     * the first half of while still being wrong: the affected launch degrades
     * rather than emitting {@code -e} at a path that is not there, AND the memo
     * un-latches, so the launch after it reinstalls instead of repeating the
     * same false success forever.</p>
     */
    @Test
    void aVanishedExtensionDegradesThenReinstallsOnTheNextLaunch(@TempDir Path state) throws Exception {
        PiAgentProvider p = bridgeProvider(state);
        Path config = state.resolve("mcp").resolve("s1.json");
        Path installed = state.resolve("pi").resolve("drydock-mcp.ts");

        String first = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(first.contains(" -e '"), "the first launch should ship the bridge: " + first);
        assertTrue(Files.exists(installed), "the installer should have written " + installed);

        Files.delete(installed);

        String afterDeletion = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(afterDeletion.endsWith("pi"),
                "a vanished extension must degrade, never point -e at a missing path: " + afterDeletion);
        assertFalse(afterDeletion.contains(" -e '"), afterDeletion);

        String next = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(access(config)))).command();
        assertTrue(next.contains(" -e '"),
                "the memo must un-latch so the next launch reinstalls: " + next);
        assertTrue(Files.exists(installed), "the extension should be back on disk");
    }

    /** Captures what {@code PiAgentProvider} logs, following McpConfigWriterTest's idiom. */
    private static List<LogRecord> recordWhile(Runnable body) {
        Logger logger = Logger.getLogger(PiAgentProvider.class.getName());
        List<LogRecord> published = new ArrayList<>();
        Handler recorder = new Handler() {
            @Override
            public void publish(LogRecord record) {
                published.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(recorder);
        try {
            body.run();
        } finally {
            logger.removeHandler(recorder);
        }
        return published;
    }

    /**
     * A declined gate used to be completely silent: no log line, no banner,
     * nothing in the command. A user on an old pi lost every drydock tool
     * with no way to find out why. The level matters as much as the text --
     * an old pi is an expected outcome, not a fault, so this must not shout.
     */
    @Test
    void aSubFloorDeclineIsLoggedAtInfoAndNamesTheVersion(@TempDir Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of("0.79.10"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        Path config = state.resolve("mcp").resolve("s1.json");

        List<LogRecord> published = recordWhile(() -> p.buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.of(access(config)))));

        LogRecord decline = published.stream()
                .filter(r -> r.getMessage().contains("Pi bridge declined"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a declined gate must say so: " + published));
        assertEquals(Level.INFO, decline.getLevel(),
                "an old pi is expected, not a fault: " + decline.getMessage());
        assertTrue(decline.getMessage().contains("0.79.10"),
                "the log must name the version it read: " + decline.getMessage());
        assertTrue(published.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
                "declining on an old pi must not warn: " + published);
    }

    /**
     * The two decline conditions must not be conflated. "Read the version and
     * it is too old" is a wholly different diagnosis from "could not read the
     * version at all" -- the second is the case that also silently re-probes
     * on every launch, so a reader who is told the wrong one will chase the
     * wrong problem.
     */
    @Test
    void anUnreadableVersionDeclineSaysSoRatherThanBlamingTheFloor(@TempDir Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of("v0.84.1"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        Path config = state.resolve("mcp").resolve("s1.json");

        List<LogRecord> published = recordWhile(() -> p.buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.of(access(config)))));

        String message = published.stream()
                .map(LogRecord::getMessage)
                .filter(m -> m.contains("Pi bridge declined"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a declined gate must say so: " + published));
        assertTrue(message.contains("could not be read"),
                "an unparseable version must be reported as unreadable: " + message);
        assertFalse(message.contains("below the 0.80.3 floor"),
                "an unparseable version is not a too-old version: " + message);
        assertTrue(message.contains("v0.84.1"),
                "the log must quote what it actually read: " + message);
    }
}
