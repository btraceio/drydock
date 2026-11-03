package app.drydock.agent.providers.claude;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.LaunchResources;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task-guidance half of {@link ClaudeAgentProvider}'s command
 * construction: the per-invocation {@code --plugin-dir} flag that hands a
 * task-tool-enabled launch the installed guidance plugin directory. The
 * flag's gate (ClaudeAgentProvider.pluginDirFlag) is the guidance location
 * present in {@code LaunchResources} AND the launch carrying MCP access
 * (its credential file); the TASK_TOOLS intersection itself stays upstream,
 * so no tool list appears here. Position follows the T24 {@code --add-dir}
 * precedent: after the extra directories, before any {@code --}/positional
 * prompt, so the directory is always an option, never prompt text.
 *
 * <p>Codex is deliberately absent from this file: CodexAgentProviderTest
 * (T24b) owns that provider's resource-flag surface, and T26a left Codex
 * handshake-only with its addDirFlags untouched, so re-testing it here would
 * duplicate T24b's coverage inside a boundary this task does not own.</p>
 */
class ClaudeAgentProviderTaskGuidanceTest {

    private static final String ENV = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT LANG=en_US.UTF-8 CLAUDE_CODE_NO_FLICKER=1 ";

    /** The guidance directory a task-tool-enabled launch is handed: a state-root path with a space. */
    private static final Path GUIDANCE =
            Path.of("/Users/me/Application Support/Drydock/tasks-guidance/1");

    /** A guidance directory with an embedded quote, for the POSIX-escaping shape. */
    private static final Path QUOTED_GUIDANCE = Path.of("/wt/it's \"quoted\" guid");

    /** MCP access with a credential file: the other half of the flag's gate. */
    private static final Optional<McpAccess> SOME_MCP = Optional.of(new McpAccess(
            "http://127.0.0.1:51234/mcp", "tok-abc", Optional.of(Path.of("/base/mcp/abc.json"))));

    /** MCP wiring exists but this launch was minted no credential file: the gate stays open. */
    private static final Optional<McpAccess> MCP_NO_FILE = Optional.of(new McpAccess(
            "http://127.0.0.1:51234/mcp", "tok-abc", Optional.empty()));

    /**
     * Conservative all-false caps (no -n/--session-id/--settings/--mcp-config)
     * and an unresolved ddtool, so the exact command strings below are stable;
     * the same idiom ClaudeAgentProviderTest established for T24.
     */
    private ClaudeAgentProvider newProviderNoExecutable() {
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")), null, noDdtool());
        provider.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        return provider;
    }

    /** A ddtool locator that never resolves, so no PATH injection perturbs the exact commands. */
    private static HostBinaryLocator noDdtool() {
        return new HostBinaryLocator("ddtool", List.of()) {
            @Override protected Optional<Path> discover() { return Optional.empty(); }
        };
    }

    /** Resources carrying only the guidance directory: the minimal request that turns the flag on. */
    private static LaunchResources guidance(Path directory) {
        return new LaunchResources(List.of(), Optional.of(directory));
    }

    @Test
    void createRendersTheGuidanceDirectoryAsAQuotedPluginDir() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(
                new CreateContext("S", "uuid-1", Path.of("/tmp"), Optional.empty(), SOME_MCP)
                        .withLaunchResources(guidance(GUIDANCE)));
        assertTrue(plan.supported());
        assertEquals(ENV + "claude --plugin-dir '" + GUIDANCE + "'", plan.command());
    }

    @Test
    void resumeRendersTheQuotedPluginDirAfterTheResumeId() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"),
                        Optional.empty(), SOME_MCP).withLaunchResources(guidance(GUIDANCE)));
        assertTrue(plan.supported());
        assertEquals(ENV + "claude --resume 'abc-123' --plugin-dir '" + GUIDANCE + "'", plan.command());
    }

    /** A guidance path with an embedded quote is escaped the POSIX way, never dropped. */
    @Test
    void aGuidancePathWithAnEmbeddedQuoteIsEscapedPosixStyle() {
        String command = newProviderNoExecutable().buildCreateCommand(
                new CreateContext("S", "uuid", Path.of("/tmp"), Optional.empty(), SOME_MCP)
                        .withLaunchResources(guidance(QUOTED_GUIDANCE))).command();
        assertEquals(ENV + "claude --plugin-dir '/wt/it'\\''s \"quoted\" guid'", command);
    }

    /**
     * The flag's position in both builders: after the T24 {@code --add-dir}
     * options, before any {@code --}/positional prompt (ClaudeAgentProvider
     * appends pluginDirFlag right after addDirFlags, and the prompt last), so
     * the directory can never be eaten as prompt text.
     */
    @Test
    void pluginDirFollowsAddDirAndPrecedesThePositionalPrompt() {
        LaunchResources both = new LaunchResources(List.of(Path.of("/wt/prereq")), Optional.of(GUIDANCE));
        String created = newProviderNoExecutable().buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), Optional.empty(), SOME_MCP, false, false, Optional.of("--dash task"))
                .withLaunchResources(both)).command();
        assertEquals(ENV + "claude --add-dir '/wt/prereq' --plugin-dir '" + GUIDANCE + "' -- '--dash task'",
                created);

        String resumed = newProviderNoExecutable().buildResumeCommand(new ResumeContext(
                Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"), Optional.empty(), SOME_MCP,
                false, false, List.of(), Optional.of("pick up")).withLaunchResources(both)).command();
        assertEquals(ENV + "claude --resume 'abc-123' --add-dir '/wt/prereq' --plugin-dir '" + GUIDANCE
                + "' -- 'pick up'", resumed);
    }

    /**
     * The gate: without MCP access (or without its credential file) the
     * guidance request alone changes nothing -- the flag is omitted and the
     * launch proceeds, byte-identical, never refused. Likewise a launch with
     * MCP access but no guidance keeps exactly the MCP command it had before
     * the flag existed (the conservative caps suppress --mcp-config here).
     */
    @Test
    void theFlagIsGatedOnMcpAccessSoAbsenceIsOmissionNotRefusal() {
        ClaudeAgentProvider provider = newProviderNoExecutable();

        String mcpBare = provider.buildCreateCommand(new CreateContext("S", "uuid", Path.of("/tmp"),
                Optional.empty(), SOME_MCP)).command();
        assertEquals(ENV + "claude", mcpBare);

        // MCP absent: the guidance request alone renders nothing.
        LaunchPlan noMcp = provider.buildCreateCommand(new CreateContext("S", "uuid", Path.of("/tmp"),
                Optional.empty(), Optional.empty()).withLaunchResources(guidance(GUIDANCE)));
        assertTrue(noMcp.supported(), "an unmet gate omits the flag, never refuses the launch");
        assertEquals(mcpBare, noMcp.command());

        // MCP present but minted no credential file: the gate stays open.
        LaunchPlan mcpNoFile = provider.buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), Optional.empty(), MCP_NO_FILE).withLaunchResources(guidance(GUIDANCE)));
        assertTrue(mcpNoFile.supported());
        assertEquals(mcpBare, mcpNoFile.command());

        // Guidance absent (extra directories only, MCP present): byte-identical
        // to the pre-guidance command for that same launch.
        LaunchPlan dirsOnly = provider.buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), Optional.empty(), SOME_MCP).withLaunchResources(
                        new LaunchResources(List.of(Path.of("/wt/prereq")), Optional.empty())));
        assertTrue(dirsOnly.supported());
        assertEquals(ENV + "claude --add-dir '/wt/prereq'", dirsOnly.command());

        // Same omission on a resume with no MCP access.
        LaunchPlan resumeNoMcp = provider.buildResumeCommand(new ResumeContext(Optional.of("abc-123"),
                Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withLaunchResources(guidance(GUIDANCE)));
        assertTrue(resumeNoMcp.supported());
        assertEquals(ENV + "claude --resume 'abc-123'", resumeNoMcp.command());
    }

    /**
     * Resources are per invocation: the same flagged resume rendered twice
     * yields the same command each time, an unflagged resume in between never
     * inherits the flag (or leaves it behind), and the next flagged one gets
     * it again -- no global or memoised state enters the render.
     */
    @Test
    void repeatedResumesCarryTheFlagEveryTimeWithoutInheritance() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        ResumeContext flagged = new ResumeContext(Optional.of("abc-123"), Optional.empty(),
                Path.of("/tmp"), Optional.empty(), SOME_MCP).withLaunchResources(guidance(GUIDANCE));
        String expected = ENV + "claude --resume 'abc-123' --plugin-dir '" + GUIDANCE + "'";
        assertEquals(expected, provider.buildResumeCommand(flagged).command());
        assertEquals(expected, provider.buildResumeCommand(flagged).command());
        assertEquals(ENV + "claude --resume 'abc-123'", provider.buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"),
                        Optional.empty(), SOME_MCP)).command());
        assertEquals(expected, provider.buildResumeCommand(flagged).command());
    }

    /**
     * A remote claude runs on another host and cannot see this machine's
     * guidance directory, so a launch that requests it is refused (with or
     * without MCP access -- the host-path predicate fires before the flag's
     * gate) rather than silently launching without it or pushing a host path
     * through the ssh hop. A remote launch that requests no guidance still
     * launches, with no trace of the guidance path or the flag.
     */
    @Test
    void remoteRefusesAGuidanceRequestAndNeverCarriesThePath() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        Optional<SshRemote> remote = Optional.of(new SshRemote("build-box", "/srv"));

        LaunchPlan refusedWithMcp = provider.buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), remote, SOME_MCP).withLaunchResources(guidance(GUIDANCE)));
        LaunchPlan refusedWithoutMcp = provider.buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), remote, Optional.empty()).withLaunchResources(guidance(GUIDANCE)));
        LaunchPlan refusedResume = provider.buildResumeCommand(new ResumeContext(Optional.of("abc-123"),
                Optional.empty(), Path.of("/tmp"), remote, SOME_MCP).withLaunchResources(guidance(GUIDANCE)));
        assertFalse(refusedWithMcp.supported(), "remote + guidance is a refusal, MCP or not");
        assertFalse(refusedWithoutMcp.supported());
        assertFalse(refusedResume.supported());
        assertEquals("", refusedWithMcp.command());
        assertEquals("", refusedResume.command());

        String plainRemote = provider.buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), remote, SOME_MCP)).command();
        assertTrue(plainRemote.startsWith("ssh"), plainRemote);
        assertFalse(plainRemote.contains("--plugin-dir"), plainRemote);
        assertFalse(plainRemote.contains("tasks-guidance"), plainRemote);
    }

    /**
     * The eval container mounts only the session worktree and the hook dirs,
     * so an eval launch that requests the guidance directory is refused before
     * the container is even consulted (no container setup exists here; the
     * unresourced eval path would throw instead, as ClaudeAgentProviderTest
     * documents) -- the same host-path refusal as remote, for the same reason.
     */
    @Test
    void evalRefusesAGuidanceRequestBeforeTheContainerIsConsulted() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        LaunchPlan create = provider.buildCreateCommand(new CreateContext("S", "sess-eval",
                Path.of("/tmp"), Optional.empty(), SOME_MCP, true, false)
                .withLaunchResources(guidance(GUIDANCE)));
        LaunchPlan resume = provider.buildResumeCommand(new ResumeContext(Optional.of("sess-eval"),
                Optional.empty(), Path.of("/tmp"), Optional.empty(), SOME_MCP, true, false, List.of(),
                Optional.empty()).withLaunchResources(guidance(GUIDANCE)));
        assertFalse(create.supported(), "eval + guidance is a refusal, never a containerized host path");
        assertFalse(resume.supported());
        assertEquals("", create.command());
        assertEquals("", resume.command());
        assertFalse(resume.command().contains("tasks-guidance"));
    }
}
