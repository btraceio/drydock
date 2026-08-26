package app.drydock.agent.providers.claude;

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
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.agent.providers.claude.internal.ClaudeCapabilities;
import app.drydock.agent.providers.claude.internal.ClaudeCapabilityService;
import app.drydock.agent.providers.claude.internal.ClaudeEvalContainer;
import app.drydock.agent.providers.claude.internal.ClaudeEvalContainer.EvalSetup;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.agent.providers.claude.internal.ClaudeHookInstaller;
import app.drydock.agent.providers.claude.internal.ConversationCatalog;
import app.drydock.process.SshCommandBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Claude Code as an {@link AgentProvider}. Command strings are identical to the
 * pre-seam {@code SessionManager} output. Delegates discovery/capabilities/
 * catalog/activity to the existing {@code app.drydock.agent.providers.claude.internal} classes.
 */
public final class ClaudeAgentProvider implements AgentProvider {

    static final String ENV_CLEANUP_PREFIX = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT ";

    private final ClaudeExecutableLocator locator;
    private ClaudeCapabilityService capabilityService;
    private ClaudeConversationSource conversationSource;
    private ClaudeActivityReporter activityReporter;
    /** The eval container; a test stub from the constructor, or the real one built at {@link #init}. */
    private ClaudeEvalContainer evalContainer;
    /** The activity state-word dir, needed to mount it into the eval container. Set at {@link #init}. */
    private Path activityDirectory;
    /** Set once by the background probe at {@link #init}; read by {@link #evalAvailable()} on the FX thread. */
    private volatile boolean evalAvailable;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public ClaudeAgentProvider() {
        this(new ClaudeExecutableLocator(), null);
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public ClaudeAgentProvider(ClaudeExecutableLocator locator) {
        this(locator, null);
    }

    /** For tests: inject the eval container (e.g. a stub that reports unavailable). */
    public ClaudeAgentProvider(ClaudeExecutableLocator locator, ClaudeEvalContainer evalContainer) {
        this.locator = locator;
        this.evalContainer = evalContainer;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.CLAUDE;
    }

    @Override
    public String displayName() {
        return "Claude";
    }

    @Override
    public void init(AgentContext ctx) {
        this.capabilityService = new ClaudeCapabilityService(locator, ctx.backgroundExecutor());
        this.conversationSource = new ClaudeConversationSource(new ConversationCatalog());
        this.activityReporter = new ClaudeActivityReporter(new ClaudeHookInstaller(ctx.stateDirectory()));
        this.activityDirectory = ctx.activityDirectory();
        if (evalContainer == null) {
            evalContainer = new ClaudeEvalContainer(ctx.stateDirectory());
        }
        ctx.backgroundExecutor().execute(() -> evalAvailable = evalContainer.probe());
    }

    @Override
    public Optional<Path> locateExecutable() {
        return locator.locate();
    }

    @Override
    public String describeSearched() {
        return locator.describeSearched();
    }

    @Override
    public AgentCapabilities probeCapabilities() {
        ClaudeCapabilities caps = detectCaps();
        return new AgentCapabilities(true, caps.supportsResume(), caps.version());
    }

    @Override
    public boolean supportsRemote() {
        return true;
    }

    @Override
    public boolean supportsSubagents() {
        return true;
    }

    /** Claude reads a config file drydock writes ({@code --mcp-config}). */
    @Override
    public McpDelivery mcpDelivery() {
        return McpDelivery.CONFIG_FILE;
    }

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.of(SshCommandBuilder.interactiveSessionCommand(c.remote().get(), "exec claude"), false);
        }
        ClaudeCapabilities caps = detectCaps();
        StringBuilder command = new StringBuilder(ENV_CLEANUP_PREFIX).append("claude");
        boolean sessionIdUsed = false;
        if (caps.supportsName()) {
            command.append(" -n ").append(AgentCommands.shellQuote(c.displayName()));
        }
        if (caps.supportsSessionId()) {
            command.append(" --session-id ").append(AgentCommands.shellQuote(c.sessionId()));
            sessionIdUsed = true;
        }
        command.append(activitySettingsFlag(caps));
        command.append(mcpConfigFlag(caps, c.mcp().flatMap(McpAccess::credentialFile)));
        if (c.evalMode()) {
            return LaunchPlan.of(
                    wrapEval(command.toString(), c.sessionId(), c.workingDirectory(),
                            c.mcp().flatMap(McpAccess::credentialFile)),
                    sessionIdUsed);
        }
        return LaunchPlan.of(command.toString(), sessionIdUsed);
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            String exec = "exec claude --resume";
            if (r.agentSessionId().isPresent()) {
                exec += " " + SshCommandBuilder.posixQuote(r.agentSessionId().get());
            } else if (r.agentSessionName().isPresent()) {
                exec += " " + SshCommandBuilder.posixQuote(r.agentSessionName().get());
            }
            return LaunchPlan.of(SshCommandBuilder.interactiveSessionCommand(r.remote().get(), exec), false);
        }
        ClaudeCapabilities caps = detectCaps();
        String suffix = activitySettingsFlag(caps) + mcpConfigFlag(caps, r.mcp().flatMap(McpAccess::credentialFile));
        String inner;
        if (r.agentSessionId().isPresent()) {
            inner = ENV_CLEANUP_PREFIX + "claude --resume " + AgentCommands.shellQuote(r.agentSessionId().get()) + suffix;
        } else if (r.agentSessionName().isPresent()) {
            inner = ENV_CLEANUP_PREFIX + "claude --resume " + AgentCommands.shellQuote(r.agentSessionName().get()) + suffix;
        } else {
            inner = ENV_CLEANUP_PREFIX + "claude --resume" + suffix;
        }
        if (r.evalMode()) {
            // Resume key is the agent session id; for PRESET it equals the
            // --session-id drydock minted at create, so the seeded config
            // dir (keyed by it) persists across resumes and mark() refreshes
            // the token.
            String key = r.agentSessionId().orElse("");
            return LaunchPlan.of(wrapEval(inner, key, r.workingDirectory(),
                    r.mcp().flatMap(McpAccess::credentialFile)), false);
        }
        return LaunchPlan.of(inner, false);
    }

    @Override
    public SessionIdStrategy idStrategy() {
        return SessionIdStrategy.PRESET;
    }

    @Override
    public Optional<ConversationSource> conversations() {
        return Optional.of(conversationSource);
    }

    @Override
    public Optional<ActivityReporter> activity() {
        return Optional.of(activityReporter);
    }

    @Override
    public Optional<SessionIdDiscovery> idDiscovery() {
        return Optional.empty();   // Claude is PRESET
    }

    @Override
    public boolean evalAvailable() {
        return evalAvailable;
    }

    @Override
    public void markEvalSession(String sessionKey) {
        evalContainer.mark(sessionKey);
    }

    @Override
    public void unmarkEvalSession(String sessionKey) {
        evalContainer.unmark(sessionKey);
    }

    @Override
    public Optional<Instant> evalTokenExpiry(String sessionKey) {
        return evalContainer.setupFor(sessionKey).map(EvalSetup::tokenExpiry);
    }

    /** Uncached, like the pre-seam code: every launch/resume re-probes. Runs on the caller's (background) thread. */
    private ClaudeCapabilities detectCaps() {
        try {
            return capabilityService.detectCapabilitiesBlocking();
        } catch (RuntimeException e) {
            // Fail conservatively: no name/session-id/settings support (matches NO_CAPABILITIES semantics).
            return new ClaudeCapabilities(false, true, false, false, false, false, "unknown");
        }
    }

    /**
     * Wraps the bare {@code claude ...} command in a {@code docker run} that
     * runs it inside the eval container. Throws if the container setup is
     * missing (ddtool or docker unavailable): an eval session that cannot
     * be containerized fails loudly rather than silently shipping an
     * unauthenticated host launch.
     */
    private String wrapEval(String innerCommand, String sessionKey, Path worktree, Optional<Path> mcpConfig) {
        EvalSetup setup = evalContainer.setupFor(sessionKey).orElseThrow(() -> new IllegalStateException(
                "Eval session " + sessionKey + " has no container setup; ddtool or docker unavailable"));
        Optional<Path> settingsFile = activityReporter.settingsFile();
        if (settingsFile.isEmpty() || activityDirectory == null) {
            throw new IllegalStateException("Eval container needs the activity hook dirs, which are not installed");
        }
        try {
            return evalContainer.wrap(setup, innerCommand, worktree, mcpConfig,
                    settingsFile.get().getParent(), activityDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write eval entrypoint: " + e.getMessage(), e);
        }
    }

    private String activitySettingsFlag(ClaudeCapabilities caps) {
        Optional<Path> settings = activityReporter.settingsFile();
        if (!caps.supportsSettings() || settings.isEmpty()) {
            return "";
        }
        return " --settings " + AgentCommands.shellQuote(settings.get().toString());
    }

    /**
     * Adds {@code --mcp-config <file>} so the session can call back into this
     * app (see {@code app.drydock.mcp.McpServer}). Empty whenever the installed
     * {@code claude} does not advertise the flag, or no per-session config file
     * was minted for this launch -- a session without Drydock tools is strictly
     * better than one that fails to launch.
     *
     * <p>No {@code --strict-mcp-config}: that would suppress the user's own MCP
     * servers, and Drydock's tools are an addition to their setup, not a
     * replacement.</p>
     *
     * <p>Only reached after each builder's remote early-return: {@code claude}
     * runs on the remote host and cannot reach this machine's loopback
     * address, so a remote session never receives a local config path.</p>
     */
    private static String mcpConfigFlag(ClaudeCapabilities caps, Optional<Path> mcpConfig) {
        if (!caps.supportsMcpConfig() || mcpConfig.isEmpty()) {
            return "";
        }
        return " --mcp-config " + AgentCommands.shellQuote(mcpConfig.get().toString());
    }

}
