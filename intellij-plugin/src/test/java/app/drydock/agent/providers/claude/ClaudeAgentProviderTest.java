package app.drydock.agent.providers.claude;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.EvalTokenResolver;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.LaunchResources;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.agent.providers.claude.internal.ClaudeEvalContainer;
import app.drydock.agent.providers.claude.internal.ClaudeEvalContainer.EvalSetup;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeAgentProviderTest {

    /**
     * Eval sessions mirror the user's ~/.claude into their config dir; the
     * eval tests below run mark()/wrap(), so point the home at an empty
     * fixture to keep them hermetic (see ClaudeEvalContainerTest).
     */
    @org.junit.jupiter.api.io.TempDir
    static Path fixtureHome;

    @org.junit.jupiter.api.BeforeAll
    static void isolateHome() throws Exception {
        Files.createDirectories(fixtureHome.resolve(".claude"));
        System.setProperty("app.drydock.eval.claude.home", fixtureHome.toString());
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreHome() {
        System.clearProperty("app.drydock.eval.claude.home");
    }

    private static final String ENV = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT LANG=en_US.UTF-8 CLAUDE_CODE_NO_FLICKER=1 ";

    /** Force "not found" so capability detection yields the conservative all-false caps deterministically. */
    private ClaudeAgentProvider newProviderNoExecutable() {
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")), null, noDdtool());
        provider.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        return provider;
    }

    /** A ddtool locator that never resolves, so PATH injection stays off and the exact command is stable. */
    private static HostBinaryLocator noDdtool() {
        return new HostBinaryLocator("ddtool", List.of()) {
            @Override protected Optional<Path> discover() { return Optional.empty(); }
        };
    }

    @Test
    void kindIsClaude() {
        assertEquals(AgentKind.CLAUDE, newProviderNoExecutable().kind());
    }

    @Test
    void idStrategyIsPreset() {
        assertEquals(SessionIdStrategy.PRESET, newProviderNoExecutable().idStrategy());
    }

    @Test
    void createWithConservativeCapsIsBarClaude() {
        // With no executable, caps detect conservatively (no -n/--session-id/--settings).
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(
                new CreateContext("Session 1", "uuid-1", Path.of("/tmp"), Optional.empty(), Optional.empty()));
        assertEquals(ENV + "claude", plan.command());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.supported());
    }

    @Test
    void createCarriesThePickedStartingModel() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("Session 1", "uuid-1",
                Path.of("/tmp"), Optional.empty(), Optional.empty(), Optional.of("sonnet"), false, false,
                Optional.empty(), List.of()));
        assertEquals(ENV + "claude --model 'sonnet'", plan.command());
    }

    @Test
    void remoteCreateCarriesThePickedStartingModel() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("Session 1", "uuid-1",
                Path.of("/tmp"), Optional.of(new SshRemote("build-box", "/srv/repo")), Optional.empty(),
                Optional.of("opus"), false, false, Optional.empty(), List.of()));
        String command = plan.command();
        // The remote command is double-quoted by SshCommandBuilder (outer ssh
        // layer, inner remote-shell layer), so the model value comes back
        // re-quoted: --model \'opus\'.
        assertTrue(command.contains("claude --model"), command);
        assertTrue(command.contains("opus"), command);
    }

    @Test
    void createWithoutAModelOmitsTheModelFlag() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("Session 1", "uuid-1",
                Path.of("/tmp"), Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of()));
        assertFalse(plan.command().contains("--model"), plan.command());
    }

    @Test
    void modelSuggestionsAreTheAliasesClaudesHelpNames() {
        // claude has no model-listing command; --help names fable/opus/sonnet
        // as alias examples. A suggestion list, not a whitelist.
        assertEquals(List.of("fable", "opus", "sonnet"), newProviderNoExecutable().modelSuggestions());
    }

    @Test
    void resumePrefersSessionId() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.of("name"), Path.of("/tmp"), Optional.empty(), Optional.empty()));
        assertEquals(ENV + "claude --resume 'abc-123'", plan.command());
    }

    @Test
    void resumeFallsBackToName() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.of("my-name"), Path.of("/tmp"), Optional.empty(), Optional.empty()));
        assertEquals(ENV + "claude --resume 'my-name'", plan.command());
    }

    @Test
    void resumeFallsBackToBare() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty()));
        assertEquals(ENV + "claude --resume", plan.command());
    }

    @Test
    void transferSummaryResumesTheExactConversationWithoutAPty(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path executable = Files.writeString(dir.resolve("claude"), "#!/bin/sh\n");
        assertTrue(executable.toFile().setExecutable(true));
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(executable), null, noDdtool());
        provider.init(new AgentContext(dir, dir.resolve("activity"), Executors.newVirtualThreadPerTaskExecutor()));

        List<String> command = provider.buildTransferSummaryCommand(dir, "conversation-42", "summarize").orElseThrow();

        assertEquals(executable.toString(), command.get(14));
        assertEquals(List.of("--safe-mode", "--print", "--resume", "conversation-42", "--permission-mode", "plan",
                        "--output-format", "json", "--", "summarize"),
                command.subList(15, command.size()));
    }

    @Test
    void transferSummaryMakesTheApiKeyHelperReachable(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path executable = Files.writeString(dir.resolve("claude"), "#!/bin/sh\n");
        Path fakeDdtool = Files.createFile(dir.resolve("ddtool"));
        assertTrue(executable.toFile().setExecutable(true));
        HostBinaryLocator ddtool = new HostBinaryLocator("ddtool", List.of()) {
            @Override protected Optional<Path> discover() { return Optional.of(fakeDdtool); }
        };
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(executable), null, ddtool);
        provider.init(new AgentContext(dir, dir.resolve("activity"),
                Executors.newVirtualThreadPerTaskExecutor()));

        List<String> command = provider.buildTransferSummaryCommand(
                dir, "conversation-42", "summarize").orElseThrow();

        assertTrue(command.stream().anyMatch(argument -> argument.startsWith("PATH=" + dir + ":")),
                command.toString());
        assertTrue(command.contains(executable.toString()), command.toString());
    }

    @Test
    void everyLocalClaudeLaunchForcesAUtf8Locale() {
        ClaudeAgentProvider provider = newProviderNoExecutable();

        assertTrue(provider.buildCreateCommand(new CreateContext(
                "Session 1", "uuid-1", Path.of("/tmp"), Optional.empty(), Optional.empty()))
                .command().contains("LANG=en_US.UTF-8"));
        assertTrue(provider.buildResumeCommand(new ResumeContext(
                Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty()))
                .command().contains("LANG=en_US.UTF-8"));
    }

    @Test
    void everyLocalClaudeLaunchUsesTheStableFullscreenRenderer() {
        ClaudeAgentProvider provider = newProviderNoExecutable();

        assertTrue(provider.buildCreateCommand(new CreateContext(
                "Session 1", "uuid-1", Path.of("/tmp"), Optional.empty(), Optional.empty()))
                .command().contains("CLAUDE_CODE_NO_FLICKER=1"));
        assertTrue(provider.buildResumeCommand(new ResumeContext(
                Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty()))
                .command().contains("CLAUDE_CODE_NO_FLICKER=1"));
    }

    @Test
    void claudeSupportsRemote() {
        assertTrue(newProviderNoExecutable().probeCapabilities().supportsRemote());
    }

    @Test
    void evalCreateWrapsInDockerWhenSetupExists() throws Exception {
        Path stateDir = Files.createTempDirectory("drydock-eval-test");
        EvalTokenResolver resolver = () -> Optional.of(
                new EvalTokenResolver.ResolvedToken("tok", Optional.of(Instant.now().plusSeconds(3600))));
        ClaudeEvalContainer container = new ClaudeEvalContainer(stateDir, resolver);
        // mark() seeds the config dir and stashes the setup (resolves the token).
        EvalSetup setup = container.mark("sess-eval").orElseThrow();
        // Install the activity hook dirs the wrapper mounts + reads.
        Files.createDirectories(stateDir.resolve("hooks"));
        Files.createDirectories(stateDir.resolve("activity"));
        Files.writeString(stateDir.resolve("hooks").resolve("settings.json"), "{}");
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")), container, noDdtool());
        provider.init(new AgentContext(stateDir, stateDir.resolve("activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        provider.activity().orElseThrow().install();   // writes hooks/settings.json
        LaunchPlan plan = provider.buildCreateCommand(new CreateContext("S", "sess-eval", Path.of("/tmp"),
                Optional.empty(), Optional.empty(), true));
        String cmd = plan.command();
        assertTrue(cmd.startsWith("docker run "), () -> "eval create wraps in docker: " + cmd);
        assertTrue(cmd.contains("drydock-claude-eval:latest"));
        // No double "sh": the image ENTRYPOINT is sh, so the command ends
        // with the image tag then the entrypoint script path -- not "sh <path>".
        String afterImage = cmd.substring(cmd.indexOf("drydock-claude-eval:latest"));
        assertFalse(afterImage.contains(" sh "), "no extra sh after image: " + afterImage);
        // The token must not leak into the command string.
        assertFalse(cmd.contains("tok"), "token must not be on the argv: " + cmd);
    }

    @Test
    void evalCreateThrowsWhenSetupMissing() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                provider.buildCreateCommand(new CreateContext("S", "no-such-sess", Path.of("/tmp"),
                        Optional.empty(), Optional.empty(), true)));
    }

    /**
     * When {@code ddtool} resolves to a directory NOT on the inherited PATH
     * (the Finder/Dock case whose login-shell merge never landed), the
     * launch prepends {@code ddtool}'s directory to {@code PATH} so the
     * managed-settings {@code apiKeyHelper} ({@code ddtool auth token ...})
     * can find it -- otherwise claude exits 127 with "ddtool: command not
     * found" before the session even starts.
     */
    @Test
    void ddtoolPathIsInjectedWhenNotOnTheInheritedPath(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path fakeDdtool = Files.createFile(dir.resolve("ddtool"));
        HostBinaryLocator ddtool = new HostBinaryLocator("ddtool", List.of()) {
            @Override protected Optional<Path> discover() { return Optional.of(fakeDdtool); }
        };
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")), null, ddtool);
        provider.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        String command = provider.buildCreateCommand(
                new CreateContext("S", "uuid", Path.of("/tmp"), Optional.empty(), Optional.empty())).command();
        int envIdx = command.indexOf(ENV);
        int pathIdx = command.indexOf("PATH='");
        int claudeIdx = command.indexOf(" claude", pathIdx < 0 ? 0 : pathIdx);
        assertTrue(pathIdx > envIdx,
                "PATH= must follow the env cleanup prefix: " + command);
        assertTrue(claudeIdx > pathIdx,
                "PATH= must precede claude: " + command);
        assertTrue(command.contains(dir.toString()),
                "ddtool's directory must be on the injected PATH: " + command);
        // The tail defers to the launching shell's PATH (rc-repaired, dogbrew
        // shims first) instead of a literal snapshot of the IDE's PATH, which
        // as the last PATH assignment before claude would drop the shims.
        assertTrue(command.contains(" PATH=" + AgentCommands.shellQuote(dir + ":") + "\"$PATH\" claude"),
                "PATH= must be the literal ddtool dir followed by the deferred \"$PATH\": " + command);
    }

    /**
     * When {@code ddtool} is already on the inherited PATH, no {@code PATH=}
     * override is emitted -- claude and its bash-tool children keep the full
     * shell PATH rather than a stripped prefix.
     */
    /**
     * A read-only session on a {@code claude} whose capabilities could not be
     * probed emits no {@code --permission-mode} at all, and the provider says
     * so: a flag the installed CLI may not list is a startup error, and a
     * read-only session that refuses to launch is worse than one the UI has
     * to call unenforced.
     */
    @Test
    void readOnlyEmitsNoPlanFlagWhenPlanModeCouldNotBeProbed() {
        ClaudeAgentProvider provider = newProviderNoExecutable();

        String created = provider.buildCreateCommand(new CreateContext(
                "S", "uuid", Path.of("/tmp"), Optional.empty(), Optional.empty(), false, true)).command();
        String resumed = provider.buildResumeCommand(new ResumeContext(
                Optional.of("sess"), Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty(),
                false, true)).command();

        assertFalse(created.contains("--permission-mode"), created);
        assertFalse(resumed.contains("--permission-mode"), resumed);
        assertFalse(provider.enforcesReadOnly(),
                "an unprobed plan mode must never be reported as enforced");
    }

    @Test
    void noPathInjectionWhenDdtoolIsAlreadyOnPath() {
        // noDdtool() -> ddtool unresolved -> nothing to inject.
        ClaudeAgentProvider provider = newProviderNoExecutable();
        String command = provider.buildCreateCommand(
                new CreateContext("S", "uuid", Path.of("/tmp"), Optional.empty(), Optional.empty())).command();
        assertEquals(ENV + "claude", command);
        assertFalse(command.contains("PATH='"), "no PATH override when ddtool is absent/on PATH: " + command);
    }

    /**
     * The task typed into New Agent Session reaches claude as its positional
     * prompt, last, after {@code --} — so the session opens working on it,
     * and a task that starts with a dash cannot be read as an option.
     */
    @Test
    void theInitialPromptIsThePositionalPromptAfterEndOfOptions() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        String command = provider.buildCreateCommand(new CreateContext("S", "uuid", Path.of("/tmp"),
                Optional.empty(), Optional.empty(), false, false,
                Optional.of("--fix the 'parser' crash"))).command();
        assertTrue(command.endsWith(" -- '--fix the '\\''parser'\\'' crash'"), command);

        String blank = provider.buildCreateCommand(new CreateContext("S", "uuid", Path.of("/tmp"),
                Optional.empty(), Optional.empty(), false, false, Optional.of("  "))).command();
        assertEquals(ENV + "claude", blank, "a blank task must not become an empty first message");
    }

    // ---- extra working directories (LaunchResources) -----------------------

    /** One plain extra directory, the minimal request. */
    private static final LaunchResources ONE_DIR = new LaunchResources(
            List.of(Path.of("/wt/prereq")), Optional.empty());

    /** Three extra directories whose paths carry a space, an embedded quote, and a leading dash. */
    private static final LaunchResources NASTY_DIRS = new LaunchResources(List.of(
            Path.of("/wt/prereq one"), Path.of("/wt/it's \"quoted\" wt"), Path.of("/-lead-dash/wt")),
            Optional.empty());

    /** The shell-quoted add-dir arguments NASTY_DIRS must render as. */
    private static final String NASTY_ADD_DIRS = " --add-dir '/wt/prereq one' --add-dir '/wt/it'\\''s \"quoted\" wt'"
            + " --add-dir '/-lead-dash/wt'";

    /**
     * Each requested extra working directory is one safely quoted {@code
     * --add-dir} option (ClaudeAgentProvider.addDirFlags): a space stays one
     * argument, an embedded quote is escaped the POSIX way, and a
     * dash-leading path stays an option value, never prompt text.
     */
    @Test
    void createRendersEachExtraDirectoryAsAQuotedAddDir() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(
                new CreateContext("S", "uuid", Path.of("/tmp"), Optional.empty(), Optional.empty())
                        .withLaunchResources(NASTY_DIRS));
        assertEquals(ENV + "claude" + NASTY_ADD_DIRS, plan.command());
    }

    @Test
    void resumeRendersEachExtraDirectoryAsAQuotedAddDirAfterTheResumeId() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"),
                        Optional.empty(), Optional.empty()).withLaunchResources(NASTY_DIRS));
        assertEquals(ENV + "claude --resume 'abc-123'" + NASTY_ADD_DIRS, plan.command());
    }

    /**
     * Extra directories are options, so they come before the {@code --}
     * positional prompt: the task is still the last thing on the command, and
     * a dash-leading task still cannot be read as an option.
     */
    @Test
    void extraDirectoriesStayBeforeThePositionalPromptOnCreateAndResume() {
        String created = newProviderNoExecutable().buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), Optional.empty(), Optional.empty(), false, false,
                Optional.of("--fix the 'parser' crash")).withLaunchResources(ONE_DIR)).command();
        assertEquals(ENV + "claude --add-dir '/wt/prereq' -- '--fix the '\\''parser'\\'' crash'", created);

        String resumed = newProviderNoExecutable().buildResumeCommand(new ResumeContext(Optional.of("abc-123"),
                Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty(), false, false, List.of(),
                Optional.of("pick up")).withLaunchResources(ONE_DIR)).command();
        assertEquals(ENV + "claude --resume 'abc-123' --add-dir '/wt/prereq' -- 'pick up'", resumed);
    }

    /** Duplicates collapse to the first occurrence, in first-occurrence order. */
    @Test
    void duplicateDirectoriesCollapseKeepingFirstOccurrenceOrder() {
        LaunchResources dups = new LaunchResources(List.of(Path.of("/wt/a"), Path.of("/wt/b"),
                Path.of("/wt/a"), Path.of("/wt/c"), Path.of("/wt/b")), Optional.empty());
        LaunchPlan create = newProviderNoExecutable().buildCreateCommand(
                new CreateContext("S", "uuid", Path.of("/tmp"), Optional.empty(), Optional.empty())
                        .withLaunchResources(dups));
        assertEquals(ENV + "claude --add-dir '/wt/a' --add-dir '/wt/b' --add-dir '/wt/c'", create.command());

        LaunchPlan resume = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"),
                        Optional.empty(), Optional.empty()).withLaunchResources(dups));
        assertEquals(ENV + "claude --resume 'abc-123' --add-dir '/wt/a' --add-dir '/wt/b' --add-dir '/wt/c'",
                resume.command());
    }

    /**
     * A launch that requests no extra directories keeps exactly today's
     * command, byte for byte: an absent {@link LaunchResources} and an empty
     * one are the same launch (ClaudeAgentProvider.addDirFlags returns ""
     * for both), and so is one carrying only the task-guidance location,
     * which rendering here does not consume.
     */
    @Test
    void createAndResumeWithoutExtraDirectoriesKeepTodayExactCommand() {
        ClaudeAgentProvider provider = newProviderNoExecutable();

        String bareCreate = provider.buildCreateCommand(new CreateContext("S", "uuid", Path.of("/tmp"),
                Optional.empty(), Optional.empty())).command();
        String emptyCreate = provider.buildCreateCommand(new CreateContext("S", "uuid", Path.of("/tmp"),
                Optional.empty(), Optional.empty())
                .withLaunchResources(new LaunchResources(List.of(), Optional.empty()))).command();
        String guidanceOnlyCreate = provider.buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withLaunchResources(new LaunchResources(List.of(), Optional.of(Path.of("/guidance")))))
                .command();
        assertEquals(ENV + "claude", bareCreate);
        assertEquals(bareCreate, emptyCreate);
        assertEquals(bareCreate, guidanceOnlyCreate);

        String bareResume = provider.buildResumeCommand(new ResumeContext(Optional.of("abc-123"),
                Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty())).command();
        String emptyResume = provider.buildResumeCommand(new ResumeContext(Optional.of("abc-123"),
                Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withLaunchResources(new LaunchResources(List.of(), Optional.empty()))).command();
        String guidanceOnlyResume = provider.buildResumeCommand(new ResumeContext(Optional.of("abc-123"),
                Optional.empty(), Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withLaunchResources(new LaunchResources(List.of(), Optional.of(Path.of("/guidance")))))
                .command();
        assertEquals(ENV + "claude --resume 'abc-123'", bareResume);
        assertEquals(bareResume, emptyResume);
        assertEquals(bareResume, guidanceOnlyResume);
    }

    /**
     * Resumes render their resources per call: the same resume requested
     * repeatedly yields the same command each time, and a resume without
     * resources in between never inherits (or leaves) any flags.
     */
    @Test
    void repeatedResumesRenderTheSameAddDirsEveryTime() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        ResumeContext withDirs = new ResumeContext(Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"),
                Optional.empty(), Optional.empty()).withLaunchResources(ONE_DIR);
        String expected = ENV + "claude --resume 'abc-123' --add-dir '/wt/prereq'";
        assertEquals(expected, provider.buildResumeCommand(withDirs).command());
        assertEquals(expected, provider.buildResumeCommand(withDirs).command());
        assertEquals(ENV + "claude --resume 'abc-123'",
                provider.buildResumeCommand(new ResumeContext(Optional.of("abc-123"), Optional.empty(),
                        Path.of("/tmp"), Optional.empty(), Optional.empty())).command());
        assertEquals(expected, provider.buildResumeCommand(withDirs).command());
    }

    /**
     * A remote claude runs on another host and cannot see this machine's
     * directories, so a launch that requests them is refused rather than
     * silently launching without them. The same remote context with no
     * requested directories still launches.
     */
    @Test
    void remoteCreateRefusesRequestedExtraDirectories() {
        LaunchPlan refused = newProviderNoExecutable().buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), Optional.of(new SshRemote("build-box", "/srv")), Optional.empty())
                .withLaunchResources(ONE_DIR));
        assertFalse(refused.supported());

        LaunchPlan plain = newProviderNoExecutable().buildCreateCommand(new CreateContext("S", "uuid",
                Path.of("/tmp"), Optional.of(new SshRemote("build-box", "/srv")), Optional.empty()));
        assertTrue(plain.supported());
    }

    @Test
    void remoteResumeRefusesRequestedExtraDirectories() {
        LaunchPlan refused = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"),
                        Optional.of(new SshRemote("build-box", "/srv")), Optional.empty())
                        .withLaunchResources(ONE_DIR));
        assertFalse(refused.supported());

        LaunchPlan plain = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("abc-123"), Optional.empty(), Path.of("/tmp"),
                        Optional.of(new SshRemote("build-box", "/srv")), Optional.empty()));
        assertTrue(plain.supported());
    }

    /**
     * The eval container mounts only the session worktree and the hook
     * dirs, so an eval launch that requests extra directories is refused
     * before the container is even consulted (no container is installed
     * here; the no-resources eval path throws instead (see
     * {@code evalCreateThrowsWhenSetupMissing}).
     */
    @Test
    void evalCreateRefusesRequestedExtraDirectories() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("S", "sess-eval",
                Path.of("/tmp"), Optional.empty(), Optional.empty(), true).withLaunchResources(ONE_DIR));
        assertFalse(plan.supported());
    }

    @Test
    void evalResumeRefusesRequestedExtraDirectories() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("sess-eval"), Optional.empty(), Path.of("/tmp"),
                        Optional.empty(), Optional.empty(), true, false, List.of(), Optional.empty())
                        .withLaunchResources(ONE_DIR));
        assertFalse(plan.supported());
    }
}
