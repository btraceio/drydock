package app.drydock.agent.providers.claude;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.agent.providers.claude.internal.ClaudeCapabilities;
import app.drydock.agent.providers.claude.internal.ClaudeCapabilityService;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.agent.providers.claude.internal.ClaudeHookInstaller;
import app.drydock.agent.providers.claude.internal.ConversationCatalog;
import app.drydock.process.SshCommandBuilder;

import java.nio.file.Path;
import java.util.List;
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

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public ClaudeAgentProvider() {
        this(new ClaudeExecutableLocator());
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public ClaudeAgentProvider(ClaudeExecutableLocator locator) {
        this.locator = locator;
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
    public List<String> envScrubList() {
        return List.of("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH",
                "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_EFFORT");
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
            command.append(" -n ").append(shellQuote(c.displayName()));
        }
        if (caps.supportsSessionId()) {
            command.append(" --session-id ").append(shellQuote(c.sessionId()));
            sessionIdUsed = true;
        }
        command.append(activitySettingsFlag(caps));
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
        String suffix = activitySettingsFlag(detectCaps());
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(ENV_CLEANUP_PREFIX + "claude --resume " + shellQuote(r.agentSessionId().get()) + suffix, false);
        }
        if (r.agentSessionName().isPresent()) {
            return LaunchPlan.of(ENV_CLEANUP_PREFIX + "claude --resume " + shellQuote(r.agentSessionName().get()) + suffix, false);
        }
        return LaunchPlan.of(ENV_CLEANUP_PREFIX + "claude --resume" + suffix, false);
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

    /** Uncached, like the pre-seam code: every launch/resume re-probes. Runs on the caller's (background) thread. */
    private ClaudeCapabilities detectCaps() {
        try {
            return capabilityService.detectCapabilitiesBlocking();
        } catch (RuntimeException e) {
            // Fail conservatively: no name/session-id/settings support (matches NO_CAPABILITIES semantics).
            return new ClaudeCapabilities(false, true, false, false, false, "unknown");
        }
    }

    private String activitySettingsFlag(ClaudeCapabilities caps) {
        Optional<Path> settings = activityReporter.settingsFile();
        if (!caps.supportsSettings() || settings.isEmpty()) {
            return "";
        }
        return " --settings " + shellQuote(settings.get().toString());
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
