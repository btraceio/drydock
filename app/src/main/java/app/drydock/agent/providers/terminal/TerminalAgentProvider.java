package app.drydock.agent.providers.terminal;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.process.SshCommandBuilder;
import app.drydock.agent.spi.AgentProvider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * No agent at all: the session's terminal runs the user's login shell in the
 * target checkout. "Start a session without an LLM" rides the same machinery
 * as the agentic kinds -- the picker, persistence, resume, close -- by
 * answering every provider question with the shell instead of a CLI.
 *
 * <p>The command is the same literal {@code ${SHELL:-/bin/zsh}} that
 * {@code TerminalSpec.loginShell} documents: libghostty wraps it as
 * {@code login ... bash -c "exec -l <command>"}, so the expansion happens in
 * that wrapper shell and {@code exec -l} turns the result into the user's
 * real login shell, profile included. Resume is deliberately the same
 * command: a terminal session has no conversation to resume, so reopening it
 * just opens a fresh shell in the same directory (the directory is what the
 * session row carries, and that is preserved).</p>
 */
public final class TerminalAgentProvider implements AgentProvider {

    /**
     * The user's shell, resolved by the wrapper shell at exec time (the same
     * expansion {@code TerminalSpec.loginShell} relies on). Never resolved
     * eagerly: this app's own process environment is not the shell's, and a
     * GUI launch may not carry {@code SHELL} at all.
     */
    static final String SHELL_COMMAND = "${SHELL:-/bin/zsh}";

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public TerminalAgentProvider() {
    }

    @Override
    public AgentKind kind() {
        return AgentKind.TERMINAL;
    }

    @Override
    public String displayName() {
        return "Terminal";
    }

    @Override
    public void init(AgentContext ctx) {
        // No state, no stores, no background work: there is nothing to init.
    }

    /**
     * A POSIX shell always exists, so this kind is never "not installed".
     * Resolved at exec time by the target shell itself (see {@link
     * #SHELL_COMMAND}), so the path here only has to satisfy the
     * availability contract, not be the shell that actually runs.
     */
    @Override
    public Optional<Path> locateExecutable() {
        return Optional.of(Path.of("/bin/sh"));
    }

    @Override
    public String describeSearched() {
        return "not searched (a POSIX shell is always present)";
    }

    @Override
    public AgentCapabilities probeCapabilities() {
        // No version to probe (there is no binary of ours); resume is
        // supported in the "reopen the session" sense.
        return new AgentCapabilities(true, true, "n/a");
    }

    @Override
    public boolean supportsRemote() {
        return true;
    }

    /** No MCP client exists to hand tools to, and no eval account to route to. */
    @Override
    public McpDelivery mcpDelivery() {
        return McpDelivery.NONE;
    }

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            // Same shape as Claude's remote launch: interactive ssh -t, cd to
            // the remote checkout, then exec the remote host's shell.
            return LaunchPlan.of(SshCommandBuilder.interactiveSessionCommand(
                    c.remote().get(), "exec \"" + SHELL_COMMAND + "\""), false);
        }
        return LaunchPlan.of(SHELL_COMMAND, false);
    }

    /**
     * The same command as create: there is no conversation to reattach, so a
     * resume opens a fresh shell in the session's directory.
     */
    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.of(SshCommandBuilder.interactiveSessionCommand(
                    r.remote().get(), "exec \"" + SHELL_COMMAND + "\""), false);
        }
        return LaunchPlan.of(SHELL_COMMAND, false);
    }

    /** PRESET with {@code sessionIdUsed = false}: the command never carries an id, so none is persisted. */
    @Override
    public SessionIdStrategy idStrategy() {
        return SessionIdStrategy.PRESET;
    }

    @Override
    public Optional<ConversationSource> conversations() {
        return Optional.empty();
    }

    @Override
    public Optional<ActivityReporter> activity() {
        return Optional.empty();
    }

    @Override
    public Optional<SessionIdDiscovery> idDiscovery() {
        return Optional.empty();
    }

    @Override
    public boolean evalAvailable() {
        return false;
    }
}
