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
import app.drydock.agent.api.StdioMcpServer;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.agent.providers.pi.internal.PiCapabilities;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.agent.providers.pi.internal.PiExtensionInstaller;
import app.drydock.agent.providers.pi.internal.PiSessionStore;
import app.drydock.agent.providers.pi.internal.PiVersionProbe;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
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

    /**
     * Fallback locations for the {@code node} interpreter, tried after the
     * live PATH. Covers Apple Silicon and Intel homebrew, the
     * {@code ~/.local/bin} native-installer location, and Volta's shim dir;
     * enough to find a node when the inherited PATH is launchd's bare
     * {@code /usr/bin:/bin} and the login-shell merge never landed.
     */
    private static List<Path> nodeFallbacks() {
        String home = System.getProperty("user.home", "");
        return List.of(
                Path.of("/opt/homebrew/bin/node"),
                Path.of("/usr/local/bin/node"),
                Path.of(home, ".local", "bin", "node"),
                Path.of(home, ".volta", "bin", "node"));
    }

    // Pi refuses to run nested inside itself unless PI_CODING_AGENT is scrubbed.
    private static final List<String> ENV_SCRUB = List.of("PI_CODING_AGENT");

    private static final String CONFIG_ENV_VAR = "DRYDOCK_MCP_CONFIG";

    /** Read by {@code PiIdeMcpExtensionSource}: the stdio MCP servers, as JSON. */
    private static final String STDIO_SERVERS_ENV_VAR = "DRYDOCK_STDIO_MCP_SERVERS";

    /** Read once and removed by {@code PiInitialPromptExtensionSource}. */
    private static final String INITIAL_PROMPT_ENV_VAR = "DRYDOCK_PI_INITIAL_PROMPT";

    /** Bounds a hung filesystem: a stuck write costs a tab its tools, never its launch. */
    private static final Duration INSTALL_JOIN = Duration.ofSeconds(5);

    /** Longer than {@code PiVersionProbe}'s own 30s, so its timeout fires first and yields "unknown". */
    private static final Duration PROBE_JOIN = Duration.ofSeconds(35);

    private static final Logger LOG = System.getLogger(PiAgentProvider.class.getName());

    private final PiExecutableLocator locator;
    private final HostBinaryLocator nodeLocator;
    private final Supplier<PiCapabilities> probe;   // tests only; null in production
    private PiConversationSource conversationSource;
    private SessionIdDiscovery idDiscovery;
    private PiExtensionInstaller installer;
    private PiExtensionInstaller ideInstaller;
    private PiExtensionInstaller initialPromptInstaller;
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
    private final AtomicReference<CompletableFuture<Path>> ideExtensionFile = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Path>> initialPromptExtensionFile = new AtomicReference<>();

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public PiAgentProvider() {
        this(new PiExecutableLocator(), new HostBinaryLocator("node", nodeFallbacks()), null);
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public PiAgentProvider(PiExecutableLocator locator) {
        this(locator, new HostBinaryLocator("node", nodeFallbacks()), null);
    }

    /** For tests: inject a version probe, so the bridge form can be exercised without a real pi. */
    PiAgentProvider(PiExecutableLocator locator, Supplier<PiCapabilities> probe) {
        this(locator, new HostBinaryLocator("node", nodeFallbacks()), probe);
    }

    /** For tests: inject both locators, so the node-prefix form can be exercised deterministically. */
    PiAgentProvider(PiExecutableLocator locator, HostBinaryLocator nodeLocator, Supplier<PiCapabilities> probe) {
        this.locator = locator;
        this.nodeLocator = nodeLocator;
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
        this.ideInstaller = PiExtensionInstaller.ideMcp(ctx.stateDirectory());
        this.initialPromptInstaller = PiExtensionInstaller.initialPrompt(ctx.stateDirectory());
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
     * Pi's CLI has no read-only mode: {@code pi --help} offers tool
     * allow/deny lists ({@code --tools}, {@code --exclude-tools}), but no
     * single switch that means "read, do not write", and enumerating edit
     * tools here would silently rot the moment pi adds one. A read-only pi
     * session therefore runs unrestricted, and the UI calls it unenforced
     * rather than showing a chip that is not true.
     */
    @Override
    public boolean enforcesReadOnly() {
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
        PiCommand pi = piCommand(c.mcp(), c.stdioMcpServers(), c.evalMode(), c.initialPrompt());
        // The extension submits after Pi has mounted its UI and initialized
        // the session. If its owner-only file could not be installed, retain
        // the CLI positional form as a degraded but functional fallback.
        String withTask = c.initialPrompt().isPresent() && !pi.initialPromptInjected()
                ? pi.command() + " -- " + AgentCommands.shellQuote(c.initialPrompt().get())
                : pi.command();
        return LaunchPlan.of(withTask, false);   // DISCOVERED: no id
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        String pi = piCommand(r.mcp(), r.stdioMcpServers(), r.evalMode(), Optional.empty()).command();
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(pi + " --session " + AgentCommands.shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id -> picker. NEVER --continue/--last (same-cwd ambiguity).
        return LaunchPlan.of(pi + " --resume", false);
    }

    /**
     * The token(s) that begin the {@code pi} command. When the resolved pi
     * file is itself a Node script and {@code node} resolves to an absolute
     * path, the invocation is {@code node <pi-script>} -- bypassing the
     * script's {@code #!/usr/bin/env node} shebang, so the launch does not
     * need {@code node} on the inherited PATH. That is what keeps pi
     * launching after a GUI/Finder start whose bare launchd PATH missed
     * {@code node} (a slow login shell whose merge applied late, or one that
     * never reported back): the shebang's {@code env node} would search the
     * bare PATH and fail with {@code env: node: No such file or directory},
     * while an absolute {@code node} runs regardless.
     *
     * <p>The prefix is valid only for a Node script. A file shebanged for a
     * shell -- the managed installer's {@code #!/bin/sh} launcher at
     * {@code ~/.local/bin/pi}, which validates the install, puts pi's
     * bundled node on PATH and execs the release bin -- must be executed
     * directly: handing it to {@code node} parses the shell script as
     * JavaScript and dies on its first line, which took down every
     * managed-install launch (observed 2026-10-13 against pi 1.2.0).
     * Executing the file directly is correct for any shebang the kernel
     * honours, and the launcher itself survives a bare PATH (it prepends
     * pi's bundled node before exec), so dropping the prefix loses nothing
     * there.
     *
     * <p>When the pi file is not a Node script, or either side is
     * unresolved, falls back to the {@code pi} path alone (or bare
     * {@code "pi"}) and relies on the shebang plus a merged PATH -- the
     * original behaviour, correct once the login-shell merge has landed.
     * {@code node} is resolved by {@link HostBinaryLocator} against the live
     * (post-merge) PATH, so the node chosen is the user's shell node (Volta,
     * nvm) when the merge succeeded, and a fallback homebrew node only when
     * the PATH is bare.
     */
    private String piBinary() {
        Optional<Path> pi = locator.locate();
        if (pi.isPresent() && isNodeScript(pi.get())) {
            Optional<Path> node = nodeLocator.locate();
            if (node.isPresent()) {
                return AgentCommands.shellQuote(node.get().toString())
                        + " " + AgentCommands.shellQuote(pi.get().toString());
            }
        }
        return pi.map(p -> AgentCommands.shellQuote(p.toString())).orElse("pi");
    }

    /**
     * Whether the resolved pi file is itself a Node script -- the only shape
     * the {@code node <pi-script>} prefix is valid for. Follows symlinks to
     * the real file and inspects the shebang: a Node interpreter (directly
     * or via {@code env node}) keeps the prefix, a shell shebang drops it.
     * A file with no readable shebang keeps the prefix -- the historical
     * assumption before the managed installer's shell launcher existed.
     */
    private static boolean isNodeScript(Path pi) {
        Path real;
        try {
            real = pi.toRealPath();
        } catch (IOException e) {
            real = pi;   // unreadable/broken link: keep the historical behaviour
        }
        String firstLine;
        try (BufferedReader reader = Files.newBufferedReader(real)) {
            firstLine = reader.readLine();
        } catch (IOException e) {
            return true;   // as above: cannot tell, keep the historical behaviour
        }
        if (firstLine == null || !firstLine.startsWith("#!")) {
            return true;
        }
        String[] tokens = firstLine.substring(2).trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return true;   // bare "#!": cannot tell, keep the historical behaviour
        }
        String interpreter = baseName(tokens[0]);
        if (interpreter.equals("node")) {
            return true;
        }
        return interpreter.equals("env") && tokens.length > 1
                && baseName(tokens[1]).equals("node");
    }

    /** The file name of an interpreter path, or "" for an empty/root token. */
    private static String baseName(String token) {
        if (token.isEmpty()) {
            return "";
        }
        var name = Path.of(token).getFileName();
        return name == null ? "" : name.toString();   // "/" has no file name
    }

    /**
     * The {@code pi} invocation up to any subcommand: the env prefix, then one
     * {@code -e} per extension the launch needs.
     *
     * <p>Two extensions, offered independently. Drydock's bridge needs
     * {@code DRYDOCK_MCP_CONFIG}; its env var and its {@code -e} are omitted
     * together -- a config path with no extension to read it, or an extension
     * with no config to find, is worse than neither. The IDE extension needs
     * {@code DRYDOCK_STDIO_MCP_SERVERS} and a pi with native MCP support
     * (0.99.0), and is omitted the same way. Inside the IDE only the second is
     * ever offered: there is no Drydock server there.</p>
     *
     * <p>When {@code eval} is set, {@code DRYDOCK_EVAL=1} is added to the
     * {@code env} prefix: the bridge extension reads it and registers an
     * Anthropic provider override carrying {@code x-target-account: eval}, so
     * this session's model traffic is charged to the eval account. The flag
     * is a non-secret boolean, so it rides the {@code env} argv like the path
     * literals in {@link AgentCommands#envPrefix} rather than going through
     * the credential-only {@code fromFiles} channel. The stdio servers ride it
     * the same way: a command, a port and paths, no credential.</p>
     */
    private PiCommand piCommand(Optional<McpAccess> access, List<StdioMcpServer> stdioServers, boolean eval,
                                Optional<String> initialPrompt) {
        Optional<Path> configFile = access.flatMap(McpAccess::credentialFile);
        // PI_SESSION_ID: the user's pi models.json sends `x-claude-code-session-id: $PI_SESSION_ID`
        // to omlx_proxy for per-session correlation, but pi does NOT set PI_SESSION_ID in its
        // own process env (only for spawned bash subprocesses). It is resolved from the
        // launch env at config-load time, so a launch without it (e.g. drydock started from
        // a fresh shell) fails provider auth. drydock sets it per launch -- a non-secret id,
        // so it rides the env argv like DRYDOCK_EVAL.
        String piSessionId = "PI_SESSION_ID=" + AgentCommands.shellQuote(java.util.UUID.randomUUID().toString()) + " ";
        // Start everything, then join everything: the cost is max(probe, writes),
        // not the sum. Sequencing them would put up to 35 s in front of the first
        // Pi tab of an app run, inside the stage that gates surface creation.
        CompletableFuture<PiCapabilities> caps = configFile.isPresent() || !stdioServers.isEmpty()
                ? capabilitiesFuture() : null;
        CompletableFuture<Path> bridge = configFile.isPresent() ? extensionFuture(extensionFile, installer) : null;
        CompletableFuture<Path> ide = stdioServers.isEmpty() ? null : extensionFuture(ideExtensionFile, ideInstaller);
        CompletableFuture<Path> prompt = initialPrompt.isEmpty() ? null
                : extensionFuture(initialPromptExtensionFile, initialPromptInstaller);

        // Join ALL before branching, even when a gate is about to fail. An
        // early return would leave an install running into a directory a test's
        // @TempDir is about to delete, and in production would write a file no
        // launch will read.
        PiCapabilities probed = caps == null ? PiCapabilities.of("unknown")
                : join(caps, PROBE_JOIN, PiCapabilities.of("unknown"));
        Path bridgeExtension = bridge == null ? null : installed(extensionFile, bridge);
        Path ideExtension = ide == null ? null : installed(ideExtensionFile, ide);
        Path promptExtension = prompt == null ? null : installed(initialPromptExtensionFile, prompt);

        boolean useBridge = configFile.isPresent() && probed.supportsBridge() && bridgeExtension != null;
        if (configFile.isPresent() && !useBridge) {
            LOG.log(Level.INFO, "Pi bridge declined for this launch (drydock tools unavailable): "
                    + declineReason(probed, bridgeExtension));
        }
        boolean useIde = !stdioServers.isEmpty() && probed.supportsMcpServers() && ideExtension != null;
        if (!stdioServers.isEmpty() && !useIde) {
            LOG.log(Level.INFO, "Pi IDE MCP servers declined for this launch: "
                    + ideDeclineReason(probed, ideExtension));
        }
        boolean useInitialPrompt = initialPrompt.isPresent() && promptExtension != null;
        if (initialPrompt.isPresent() && !useInitialPrompt) {
            LOG.log(Level.WARNING, "Pi initial-prompt extension could not be installed; using CLI fallback");
        }

        StringBuilder command = new StringBuilder(useBridge
                ? AgentCommands.envPrefix(ENV_SCRUB, Map.of(CONFIG_ENV_VAR, configFile.get()), Map.of())
                        + evalEnvSuffix(eval)
                : evalEnvPrefix(eval));
        command.append(piSessionId);
        if (useInitialPrompt) {
            String encoded = Base64.getEncoder().encodeToString(
                    initialPrompt.orElseThrow().getBytes(StandardCharsets.UTF_8));
            command.append(INITIAL_PROMPT_ENV_VAR).append('=')
                    .append(AgentCommands.shellQuote(encoded)).append(' ');
        }
        if (useIde) {
            command.append(STDIO_SERVERS_ENV_VAR).append('=')
                    .append(AgentCommands.shellQuote(stdioServersJson(stdioServers))).append(' ');
        }
        command.append(piBinary());
        if (useBridge) {
            command.append(" -e ").append(AgentCommands.shellQuote(bridgeExtension.toString()));
        }
        if (useIde) {
            command.append(" -e ").append(AgentCommands.shellQuote(ideExtension.toString()));
        }
        if (useInitialPrompt) {
            command.append(" -e ").append(AgentCommands.shellQuote(promptExtension.toString()));
        }
        return new PiCommand(command.toString(), useInitialPrompt);
    }

    private record PiCommand(String command, boolean initialPromptInjected) {
    }

    /**
     * The installed extension's path, or null when the install failed, timed
     * out, or its file is gone. The last case is an install that succeeded
     * earlier in this app run and was memoised, whose file was since removed
     * or whose state directory was wiped. {@code pi -e <missing>} exits 1 and
     * takes the whole tab down with it, which is exactly the invariant this
     * bridge must not violate. Degrade this one launch, and un-latch the memo
     * so the next launch reinstalls instead of repeating this same false
     * success forever.
     */
    private static Path installed(AtomicReference<CompletableFuture<Path>> slot, CompletableFuture<Path> install) {
        Path extension = join(install, INSTALL_JOIN, null);
        if (extension != null && !Files.isReadable(extension)) {
            slot.compareAndSet(install, null);
            return null;
        }
        return extension;
    }

    /** The stdio servers in the {@code mcpServers} shape Claude is handed and pi's own mcp.json uses. */
    private static String stdioServersJson(List<StdioMcpServer> servers) {
        JsonValue.JsonObject entries = JsonValue.JsonObject.empty();
        for (StdioMcpServer server : servers) {
            JsonValue.JsonObject env = JsonValue.JsonObject.empty();
            server.env().forEach((key, value) -> env.put(key, new JsonValue.JsonString(value)));
            entries.put(server.name(), JsonValue.JsonObject.empty()
                    .put("command", new JsonValue.JsonString(server.command()))
                    .put("args", JsonValue.JsonArray.of(server.args().stream()
                            .<JsonValue>map(JsonValue.JsonString::new).toList()))
                    .put("env", env));
        }
        return JsonWriter.write(JsonValue.JsonObject.empty().put("mcpServers", entries)).strip();
    }

    /** Names which of the IDE extension's two gate conditions declined, for the diagnostic log. */
    private static String ideDeclineReason(PiCapabilities probed, Path ideExtension) {
        StringBuilder reason = new StringBuilder();
        if (!probed.supportsMcpServers()) {
            reason.append(probed.versionParsed()
                    ? "pi " + probed.version() + " predates native MCP support (0.99.0)"
                    : "pi's version could not be read (probed as \"" + probed.version() + "\")");
        }
        if (ideExtension == null) {
            if (reason.length() > 0) {
                reason.append("; ");
            }
            reason.append("the IDE extension could not be installed");
        }
        return reason.toString();
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
     * Installs an extension once per app run and memoises the result.
     * {@code piCommand} re-validates the memoised path with
     * {@code Files.isReadable} on every launch, but only for existence: it
     * does not check content, so a truncated or hand-edited file would still
     * pass this check and still fail to load in pi.
     */
    private CompletableFuture<Path> extensionFuture(AtomicReference<CompletableFuture<Path>> slot,
                                                    PiExtensionInstaller installer) {
        return memoised(slot, () -> {
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
