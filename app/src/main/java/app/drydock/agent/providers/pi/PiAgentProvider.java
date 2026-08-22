package app.drydock.agent.providers.pi;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.api.SnapshotClaimDiscovery;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.providers.pi.internal.PiCapabilities;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.agent.providers.pi.internal.PiExtensionInstaller;
import app.drydock.agent.providers.pi.internal.PiSessionStore;
import app.drydock.agent.providers.pi.internal.PiVersionProbe;
import app.drydock.agent.spi.AgentProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pi coding agent CLI as an {@link AgentProvider}. DISCOVERED id strategy, no
 * remote, no activity badges.
 */
public final class PiAgentProvider implements AgentProvider {

    // Pi refuses to run nested inside itself unless PI_CODING_AGENT is scrubbed.
    private static final List<String> ENV_SCRUB = List.of("PI_CODING_AGENT");

    private static final String CONFIG_ENV_VAR = "DRYDOCK_MCP_CONFIG";

    /** Bounds a hung filesystem: a stuck write costs a tab its tools, never its launch. */
    private static final Duration INSTALL_JOIN = Duration.ofSeconds(5);

    /** Longer than {@code PiVersionProbe}'s own 30s, so its timeout fires first and yields "unknown". */
    private static final Duration PROBE_JOIN = Duration.ofSeconds(35);

    private static final Logger LOG = System.getLogger(PiAgentProvider.class.getName());

    private final PiExecutableLocator locator;
    private final Supplier<PiCapabilities> probe;   // tests only; null in production
    private PiConversationSource conversationSource;
    private SessionIdDiscovery idDiscovery;
    private PiExtensionInstaller installer;
    private ExecutorService background;

    /**
     * Memoised on success only. Caching a failure would turn one timeout on a
     * busy machine into every Pi tab silently losing its tools for the rest of
     * the app run, and would mean installing or upgrading pi never takes
     * effect until drydock restarts. Futures rather than booleans because two
     * tabs opened together run the command builders on different background
     * threads.
     */
    private final AtomicReference<CompletableFuture<PiCapabilities>> capabilities = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Path>> extensionFile = new AtomicReference<>();

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public PiAgentProvider() {
        this(new PiExecutableLocator());
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public PiAgentProvider(PiExecutableLocator locator) {
        this(locator, null);
    }

    /** For tests: inject a version probe, so the bridge form can be exercised without a real pi. */
    PiAgentProvider(PiExecutableLocator locator, Supplier<PiCapabilities> probe) {
        this.locator = locator;
        this.probe = probe;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.PI;
    }

    @Override
    public String displayName() {
        return "Pi";
    }

    @Override
    public void init(AgentContext ctx) {
        PiSessionStore store = new PiSessionStore();
        this.conversationSource = new PiConversationSource(store);
        this.idDiscovery = new SnapshotClaimDiscovery(store);
        this.installer = new PiExtensionInstaller(ctx.stateDirectory());
        this.background = ctx.backgroundExecutor();
    }

    @Override
    public Optional<Path> locateExecutable() {
        return locator.locate();
    }

    @Override
    public String describeSearched() {
        return locator.describeSearched();
    }

    /** Probes {@code pi --version} (blocking; off the FX thread per the SPI contract). */
    @Override
    public AgentCapabilities probeCapabilities() {
        return new AgentCapabilities(false, true, PiVersionProbe.probe(locator.locate().orElse(null)));
    }

    @Override
    public boolean supportsRemote() {
        return false;
    }

    /**
     * Pi has no MCP client of its own, and by design: its README says so
     * outright ("No MCP. Build CLI tools with READMEs, or build an extension
     * that adds MCP support"). This takes the second half of that sentence at
     * its word — drydock ships the extension, and it reads the same owner-only
     * JSON config Claude is handed, so the delivery really is
     * {@code CONFIG_FILE} rather than a fourth kind.
     */
    @Override
    public McpDelivery mcpDelivery() {
        return McpDelivery.CONFIG_FILE;
    }

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.unsupported();   // Pi declines remote
        }
        return LaunchPlan.of(piCommand(c.mcp(), c.evalMode()), false);   // DISCOVERED: no id
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        String pi = piCommand(r.mcp(), r.evalMode());
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(pi + " --session " + AgentCommands.shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id -> picker. NEVER --continue/--last (same-cwd ambiguity).
        return LaunchPlan.of(pi + " --resume", false);
    }

    /**
     * The {@code pi} invocation up to any subcommand: the env prefix, then
     * {@code -e} pointing at drydock's bridge extension. Both are omitted
     * together — a config path with no extension to read it, or an extension
     * with no config to find, is worse than neither.
     *
     * <p>When {@code eval} is set, {@code DRYDOCK_EVAL=1} is added to the
     * {@code env} prefix: the bridge extension reads it and registers an
     * Anthropic provider override carrying {@code x-target-account: eval}, so
     * this session's model traffic is charged to the eval account. The flag
     * is a non-secret boolean, so it rides the {@code env} argv like the path
     * literals in {@link AgentCommands#envPrefix} rather than going through
     * the credential-only {@code fromFiles} channel.</p>
     */
    private String piCommand(Optional<McpAccess> access, boolean eval) {
        Optional<Path> configFile = access.flatMap(McpAccess::credentialFile);
        // PI_SESSION_ID: the user's pi models.json sends `x-claude-code-session-id: $PI_SESSION_ID`
        // to omlx_proxy for per-session correlation, but pi does NOT set PI_SESSION_ID in its
        // own process env (only for spawned bash subprocesses). It is resolved from the
        // launch env at config-load time, so a launch without it (e.g. drydock started from
        // a fresh shell) fails provider auth. drydock sets it per launch -- a non-secret id,
        // so it rides the env argv like DRYDOCK_EVAL.
        String piSessionId = "PI_SESSION_ID=" + AgentCommands.shellQuote(java.util.UUID.randomUUID().toString()) + " ";
        if (configFile.isEmpty()) {
            return evalEnvPrefix(eval) + piSessionId + "pi";
        }
        // Start BOTH, then join both: the cost is max(probe, write), not the
        // sum. Sequencing them would put up to 35 s in front of the first Pi
        // tab of an app run, inside the stage that gates surface creation.
        CompletableFuture<PiCapabilities> caps = capabilitiesFuture();
        CompletableFuture<Path> extension = extensionFuture();

        // Join BOTH before branching, even when the gate is about to fail. An
        // early return would leave the install running into a directory a test's
        // @TempDir is about to delete, and in production would write a file no
        // launch will read.
        PiCapabilities probed = join(caps, PROBE_JOIN, PiCapabilities.of("unknown"));
        Path bridgeExtension = join(extension, INSTALL_JOIN, null);
        if (bridgeExtension != null && !Files.isReadable(bridgeExtension)) {
            // The install succeeded earlier in this app run and was memoised,
            // but the file is gone now -- removed, or the state directory was
            // wiped. `pi -e <missing>` exits 1 and takes the whole tab down
            // with it, which is exactly the invariant this bridge must not
            // violate. Degrade this one launch, and un-latch the memo so the
            // next launch reinstalls instead of repeating this same false
            // success forever.
            extensionFile.compareAndSet(extension, null);
            bridgeExtension = null;
        }
        if (!probed.supportsBridge() || bridgeExtension == null) {
            LOG.log(Level.INFO, "Pi bridge declined for this launch (drydock tools unavailable): "
                    + declineReason(probed, bridgeExtension));
            return evalEnvPrefix(eval) + piSessionId + "pi";
        }
        return AgentCommands.envPrefix(ENV_SCRUB, Map.of(CONFIG_ENV_VAR, configFile.get()), Map.of())
                + evalEnvSuffix(eval) + piSessionId + "pi -e " + AgentCommands.shellQuote(bridgeExtension.toString());
    }

    /**
     * The env prefix for a launch with no bridge extension: scrub vars, plus
     * the eval flag when set.
     */
    private String evalEnvPrefix(boolean eval) {
        return AgentCommands.envPrefix(ENV_SCRUB) + evalEnvSuffix(eval);
    }

    /**
     * {@code DRYDOCK_EVAL=1 } to splice into an existing {@code env} prefix,
     * or empty. {@link AgentCommands#envPrefix} always emits the literal
     * {@code env} for Pi's non-empty scrub list, so the result is a valid
     * {@code env VAR=value} assignment rather than a bare shell assignment.
     */
    private static String evalEnvSuffix(boolean eval) {
        return eval ? "DRYDOCK_EVAL=1 " : "";
    }

    private CompletableFuture<PiCapabilities> capabilitiesFuture() {
        Supplier<PiCapabilities> work = probe != null
                ? probe
                : () -> PiCapabilities.of(PiVersionProbe.probe(locator.locate().orElse(null)));
        // A version that parsed and is simply below the floor is worth
        // keeping -- re-probing cannot change it. A version that did not
        // parse (including the probe's own "unknown") is not: it might be a
        // transient probe hiccup, so the next launch should try again rather
        // than repeat a false read for the rest of the app run.
        return memoised(capabilities, work, PiCapabilities::versionParsed);
    }

    /** Names which of {@code piCommand}'s two gate conditions declined, for the F1 diagnostic log. */
    private static String declineReason(PiCapabilities probed, Path bridgeExtension) {
        StringBuilder reason = new StringBuilder();
        if (!probed.supportsBridge()) {
            reason.append(probed.versionParsed()
                    ? "pi " + probed.version() + " is below the 0.80.3 floor"
                    : "pi's version could not be read (probed as \"" + probed.version() + "\")");
        }
        if (bridgeExtension == null) {
            if (reason.length() > 0) {
                reason.append("; ");
            }
            reason.append("the bridge extension could not be installed");
        }
        return reason.toString();
    }

    /**
     * Installs the bridge extension once per app run and memoises the result.
     * {@code piCommand} re-validates the memoised path with
     * {@code Files.isReadable} on every launch, but only for existence: it
     * does not check content, so a truncated or hand-edited file would still
     * pass this check and still fail to load in pi.
     */
    private CompletableFuture<Path> extensionFuture() {
        return memoised(extensionFile, () -> {
            try {
                return installer.install();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, written -> true);
    }

    /**
     * Get-or-start a memoised future.
     *
     * <p>Three properties, each of which review established the hard way. The
     * future is published <em>before</em> the work starts, so a second tab
     * opened at the same moment joins the first rather than re-running it —
     * that is the "re-entered probe" a bare boolean would allow. The work runs
     * on the background executor rather than the caller's thread, so the join
     * bound applies to the thread doing the work and not merely to its
     * neighbours. And a result that is not worth keeping un-publishes the slot,
     * so the next launch retries: caching a failure would turn one timeout on a
     * busy machine into every Pi tab losing its tools for the rest of the app
     * run, and would mean installing or upgrading pi never takes effect until
     * drydock restarts.</p>
     */
    private <T> CompletableFuture<T> memoised(AtomicReference<CompletableFuture<T>> slot,
                                              Supplier<T> work, Predicate<T> worthKeeping) {
        while (true) {
            CompletableFuture<T> existing = slot.get();
            if (existing != null) {
                return existing;
            }
            CompletableFuture<T> created = new CompletableFuture<>();
            if (!slot.compareAndSet(null, created)) {
                continue;   // someone published first -- loop and take theirs
            }
            try {
                background.execute(() -> {
                    try {
                        T value = work.get();
                        if (!worthKeeping.test(value)) {
                            slot.compareAndSet(created, null);
                        }
                        created.complete(value);
                    } catch (RuntimeException e) {
                        slot.compareAndSet(created, null);
                        created.completeExceptionally(e);
                    }
                });
            } catch (RuntimeException | Error e) {
                // RejectedExecutionException means shutdown drained the executor;
                // anything else here is unexpected. Either way this must not leave
                // an uncompleted future latched in the slot forever -- that would
                // make every later launch pay the full join and degrade, for the
                // rest of the app run, over a submission that never even started.
                slot.compareAndSet(created, null);
                created.completeExceptionally(e);
            }
            return created;
        }
    }

    /** Joins with a bound, degrading to {@code fallback} rather than to a stuck launch. */
    private static <T> T join(CompletableFuture<T> future, Duration bound, T fallback) {
        try {
            return future.get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, "Pi bridge setup unavailable; launching without drydock tools: "
                    + e.getMessage());
            return fallback;
        }
    }

    @Override
    public SessionIdStrategy idStrategy() {
        return SessionIdStrategy.DISCOVERED;
    }

    @Override
    public Optional<ConversationSource> conversations() {
        return Optional.of(conversationSource);
    }

    @Override
    public Optional<ActivityReporter> activity() {
        return Optional.empty();
    }

    @Override
    public Optional<SessionIdDiscovery> idDiscovery() {
        return Optional.of(idDiscovery);
    }

}
