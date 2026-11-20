package app.drydock.agent.providers.pi;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.LaunchResources;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.providers.pi.internal.PiCapabilities;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task-guidance half of {@link PiAgentProvider}'s command
 * construction: the per-invocation {@code --skill} flag (appendSkillFlag,
 * called from the common piCommand so every create/resume path -- the
 * initial-prompt injection path, its CLI positional fallback, and the bare
 * spawn -- carries it). The gate is the guidance directory present in
 * {@code LaunchResources} AND the launch carrying MCP access (its config
 * file); the TASK_TOOLS intersection stays upstream, so no tool list
 * appears here. The flag renders the SKILL.md file inside the guidance
 * directory (the layout TaskGuidance materializes), before any {@code --}
 * positional a caller appends after piCommand returns.
 *
 * <p>pi byte-identity is modulo the per-launch random PI_SESSION_ID, which
 * piCommand mints on every invocation by design. Codex is not covered here:
 * CodexAgentProviderTest (T24b) owns that provider's resource surface.</p>
 */
class PiAgentProviderTaskGuidanceTest {

    /** The guidance directory a task-tool-enabled launch is handed: a state-root path with a space. */
    private static final Path GUIDANCE =
            Path.of("/Users/me/Application Support/Drydock/tasks-guidance/1");

    /** The SKILL.md file inside it, the flag's argument. */
    private static final String SKILL =
            "/Users/me/Application Support/Drydock/tasks-guidance/1/skills/drydock-tasks/SKILL.md";

    /** A guidance directory with an embedded quote, for the POSIX-escaping shape. */
    private static final Path QUOTED_GUIDANCE = Path.of("/wt/it's \"quoted\" guid");

    /** MCP access with a config file: the other half of the flag's gate. */
    private static McpAccess access(Path configFile) {
        return new McpAccess("http://127.0.0.1:1234/mcp", "tok-abc", Optional.of(configFile));
    }

    /** MCP wiring exists but no config file was minted: the gate stays open. */
    private static final McpAccess MCP_NO_FILE = new McpAccess(
            "http://127.0.0.1:1234/mcp", "tok-abc", Optional.empty());

    /** A local pi on a supported version, whose extension installs succeed in a writable state dir. */
    private PiAgentProvider bridgeProvider(Path state) {
        PiAgentProvider p = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")),
                () -> PiCapabilities.of("1.3.0"));
        p.init(new AgentContext(state, state.resolve("activity"), ForkJoinPool.commonPool()));
        return p;
    }

    /** pi mints a fresh PI_SESSION_ID per invocation, so equality is modulo it. */
    private static String withoutSessionId(String command) {
        return command.replaceAll("PI_SESSION_ID='[^']*'", "PI_SESSION_ID='<id>'");
    }

    private static LaunchResources guidance(Path directory) {
        return new LaunchResources(List.of(), Optional.of(directory));
    }

    private static String mcpFlag(Path state) {
        return state.resolve("pi").resolve("drydock-mcp.ts").toString();
    }

    private static String activityFlag(Path state) {
        return state.resolve("pi").resolve("drydock-activity.ts").toString();
    }

    @Test
    void createWithMcpAndGuidanceCarriesTheQuotedSkillFile(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        String command = bridgeProvider(state).buildCreateCommand(new CreateContext("s", "x",
                Path.of("/repo"), Optional.empty(), Optional.of(access(config)))
                .withLaunchResources(guidance(GUIDANCE))).command();
        assertEquals("env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='" + config
                + "' DRYDOCK_ACTIVITY_DIR='" + state.resolve("activity")
                + "' PI_SESSION_ID='<id>' pi -e '" + mcpFlag(state) + "' -e '" + activityFlag(state)
                + "' --skill '" + SKILL + "'", withoutSessionId(command));
    }

    /** A guidance path with an embedded quote is escaped the POSIX way, never dropped. */
    @Test
    void aSkillPathWithAnEmbeddedQuoteIsEscapedPosixStyle(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        String command = bridgeProvider(state).buildCreateCommand(new CreateContext("s", "x",
                Path.of("/repo"), Optional.empty(), Optional.of(access(config)))
                .withLaunchResources(guidance(QUOTED_GUIDANCE))).command();
        assertTrue(command.endsWith(" --skill '/wt/it'\\''s \"quoted\" guid"
                + "/skills/drydock-tasks/SKILL.md'"), command);
    }

    /**
     * The initial-prompt injection path: the task rides the one-shot extension
     * (no CLI positional), and the flag still lands after the extensions --
     * appendSkillFlag runs inside the common piCommand that both paths build.
     */
    @Test
    void theInjectionPathCarriesTheSkillFlagToo(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        String encoded = Base64.getEncoder().encodeToString(
                "Add a farewell method".getBytes(StandardCharsets.UTF_8));
        String command = bridgeProvider(state).buildCreateCommand(new CreateContext("s", "x",
                Path.of("/repo"), Optional.empty(), Optional.of(access(config)), false, false,
                Optional.of("Add a farewell method")).withLaunchResources(guidance(GUIDANCE))).command();
        assertEquals("env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='" + config
                + "' DRYDOCK_ACTIVITY_DIR='" + state.resolve("activity")
                + "' PI_SESSION_ID='<id>' DRYDOCK_PI_INITIAL_PROMPT='" + encoded
                + "' pi -e '" + mcpFlag(state) + "' -e '"
                + state.resolve("pi").resolve("drydock-initial-prompt.ts") + "' -e '" + activityFlag(state)
                + "' --skill '" + SKILL + "'", withoutSessionId(command));
        assertFalse(command.contains(" -- 'Add a farewell method'"),
                "the injection path never adds the CLI positional: " + command);
    }

    /**
     * The CLI positional fallback: when the initial-prompt extension cannot
     * be installed (here: a read-only state dir, the same hermetic trick
     * PiAgentProviderTest uses), the prompt rides after {@code --} -- and the
     * flag still precedes it, so the skill path is an option, never prompt
     * text.
     */
    @Test
    void theFallbackPathKeepsTheSkillFlagBeforeThePositionalPrompt(@TempDir Path parent) throws Exception {
        Path state = Files.createDirectories(parent.resolve("state"));
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-xr-xr-x"));
        Path config = state.resolve("mcp").resolve("s1.json");
        try {
            String command = bridgeProvider(state).buildCreateCommand(new CreateContext("s", "x",
                    Path.of("/repo"), Optional.empty(), Optional.of(access(config)), false, false,
                    Optional.of("Add a farewell method")).withLaunchResources(guidance(GUIDANCE)))
                    .command();
            assertEquals("env -u PI_CODING_AGENT PI_SESSION_ID='<id>' pi --skill '" + SKILL
                    + "' -- 'Add a farewell method'", withoutSessionId(command));
        } finally {
            // Restore in a finally, or a failed assertion also fails @TempDir cleanup.
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * Resources are per invocation: the same flagged resume rendered twice
     * yields the same command (modulo the minted session id), an unflagged
     * resume in between never inherits the flag, and a resume prompt still
     * lands after {@code --} -- behind the flag.
     */
    @Test
    void resumesCarryTheSkillFlagOnEveryResumeWithoutInheritance(@TempDir Path state) {
        PiAgentProvider p = bridgeProvider(state);
        Path config = state.resolve("mcp").resolve("s1.json");
        ResumeContext flagged = new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(),
                Path.of("/repo"), Optional.empty(), Optional.of(access(config)))
                .withLaunchResources(guidance(GUIDANCE));
        String expected = "env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='" + config
                + "' DRYDOCK_ACTIVITY_DIR='" + state.resolve("activity") + "' PI_SESSION_ID='<id>' pi -e '"
                + mcpFlag(state) + "' -e '" + activityFlag(state) + "' --skill '" + SKILL
                + "' --session '019f9072-abc'";
        assertEquals(expected, withoutSessionId(p.buildResumeCommand(flagged).command()));
        assertEquals(expected, withoutSessionId(p.buildResumeCommand(flagged).command()));
        assertEquals(expected.replace(" --skill '" + SKILL + "'", ""), withoutSessionId(
                p.buildResumeCommand(new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(),
                        Path.of("/repo"), Optional.empty(), Optional.of(access(config)))).command()));

        String withPrompt = p.buildResumeCommand(new ResumeContext(Optional.of("019f9072-abc"),
                Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.of(access(config)),
                false, false, List.of(), Optional.of("pick up")).withLaunchResources(guidance(GUIDANCE)))
                .command();
        assertEquals(expected + " -- 'pick up'", withoutSessionId(withPrompt));
    }

    /**
     * The gate: without MCP access (or without its config file) the guidance
     * request alone changes nothing -- the flag is omitted and the launch
     * proceeds, byte-identical modulo PI_SESSION_ID, never refused. Guidance
     * absent renders the same nothing.
     */
    @Test
    void mcpAbsentMeansAByteIdenticalCommandNotARefusal(@TempDir Path state) {
        PiAgentProvider p = bridgeProvider(state);

        LaunchPlan noMcpBare = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.empty()));
        LaunchPlan noMcpGuidance = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.empty()).withLaunchResources(guidance(GUIDANCE)));
        assertTrue(noMcpGuidance.supported(), "an unmet gate omits the flag, never refuses the launch");
        assertEquals(withoutSessionId(noMcpBare.command()), withoutSessionId(noMcpGuidance.command()));
        assertFalse(noMcpGuidance.command().contains("--skill"), noMcpGuidance.command());

        LaunchPlan mcpNoFile = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.of(MCP_NO_FILE)).withLaunchResources(guidance(GUIDANCE)));
        assertTrue(mcpNoFile.supported());
        assertEquals(withoutSessionId(noMcpBare.command()), withoutSessionId(mcpNoFile.command()));

        LaunchPlan resumeNoMcp = p.buildResumeCommand(new ResumeContext(Optional.of("019f9072-abc"),
                Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty())
                .withLaunchResources(guidance(GUIDANCE)));
        assertTrue(resumeNoMcp.supported());
        assertEquals(withoutSessionId(p.buildResumeCommand(new ResumeContext(Optional.of("019f9072-abc"),
                Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty())).command()),
                withoutSessionId(resumeNoMcp.command()));
    }

    /**
     * Pi declines remote outright (pre-existing), so a remote launch that
     * requests guidance is refused before any command is built -- the skill
     * path can never leak into a remote form, and the refusal is not the
     * guidance's doing: a remote launch without resources is refused too.
     */
    @Test
    void remoteIsRefusedSoTheSkillPathNeverLeavesThisHost(@TempDir Path state) {
        PiAgentProvider p = bridgeProvider(state);
        Optional<SshRemote> remote = Optional.of(new SshRemote("host", "/remote/path"));
        Path config = state.resolve("mcp").resolve("s1.json");

        LaunchPlan refusedGuidance = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                remote, Optional.of(access(config))).withLaunchResources(guidance(GUIDANCE)));
        LaunchPlan refusedResume = p.buildResumeCommand(new ResumeContext(Optional.of("019f9072-abc"),
                Optional.empty(), Path.of("/repo"), remote, Optional.of(access(config)))
                .withLaunchResources(guidance(GUIDANCE)));
        LaunchPlan refusedPlain = p.buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                remote, Optional.empty()));
        assertFalse(p.supportsRemote());
        assertFalse(refusedGuidance.supported());
        assertFalse(refusedResume.supported());
        assertFalse(refusedPlain.supported());
        assertEquals("", refusedGuidance.command());
        assertFalse(refusedResume.command().contains(SKILL), refusedResume.command());
    }

    /**
     * Pi's eval routing runs in the same local process (an extension, not a
     * container), which can see host paths -- so an eval launch with the
     * guidance keeps the flag, unlike Claude's containerized eval refusal.
     */
    @Test
    void evalIncludesTheSkillFlagBecausePiStaysALocalProcess(@TempDir Path state) {
        Path config = state.resolve("mcp").resolve("s1.json");
        String command = bridgeProvider(state).buildCreateCommand(new CreateContext("s", "x",
                Path.of("/repo"), Optional.empty(), Optional.of(access(config)), true, false)
                .withLaunchResources(guidance(GUIDANCE))).command();
        assertEquals("env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='" + config
                + "' DRYDOCK_ACTIVITY_DIR='" + state.resolve("activity") + "' DRYDOCK_EVAL=1"
                + " PI_SESSION_ID='<id>' pi -e '" + mcpFlag(state) + "' -e '"
                + state.resolve("pi").resolve("drydock-eval.ts") + "' -e '" + activityFlag(state)
                + "' --skill '" + SKILL + "'", withoutSessionId(command));
    }
}
