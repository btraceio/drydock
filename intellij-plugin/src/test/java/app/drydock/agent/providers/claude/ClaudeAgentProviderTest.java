package app.drydock.agent.providers.claude;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.EvalTokenResolver;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.LaunchResources;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.RemotePersistence;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.agent.providers.claude.internal.ClaudeEvalContainer;
import app.drydock.agent.providers.claude.internal.ClaudeEvalContainer.EvalSetup;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SshRemote;
import app.drydock.process.SshCommandBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    // ---- characterization: today's exact plan strings (the INV-1 oracle) ------

    /**
     * The ssh {@code -t} shell today's remote branches wrap their claude in
     * (SshCommandBuilder's interactive form): keep-alive options, {@code --}
     * before the host, then the remote command with its inner {@code cd}
     * double-quoted through both ssh layers.
     */
    private static final String REMOTE_SSH_PREFIX =
            "ssh -t -o ServerAliveInterval=30 -o ServerAliveCountMax=6 -- 'build-box'"
                    + " 'export TERM=xterm-256color; cd '\\''/srv/repo'\\'' && ";

    /**
     * A stub {@code claude} whose {@code --help} advertises every flag the
     * local launch path renders, so the full-capability create and resume
     * shapes can be pinned byte for byte. Real subprocesses for the
     * {@code --version}/{@code --help} probes (the no-mocks rule for
     * capability detection); the ddtool locator is forced to "not found"
     * so no host ddtool can splice a {@code PATH=} override into the pinned
     * string.
     */
    private ClaudeAgentProvider fullCapsProvider(Path dir) throws Exception {
        Path stub = dir.resolve("claude");
        Files.writeString(stub, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  echo "1.2.3 (Claude Code)"
                  exit 0
                fi
                if [ "$1" = "--help" ]; then
                  echo "Usage: claude [options]"
                  echo "  -n, --name <name>"
                  echo "  --resume [sessionId]"
                  echo "  --session-id <id>"
                  echo "  --settings <file>"
                  echo "  --mcp-config <file>"
                  echo '  --permission-mode <mode> ("plan" or "acceptEdits")'
                  exit 0
                fi
                exit 1
                """);
        assertTrue(stub.toFile().setExecutable(true));
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(stub), null, noDdtool());
        provider.init(new AgentContext(dir, dir.resolve("activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        // The plugin installs the activity hooks at startup; the settings
        // file the launch renders is only minted after install(), so the
        // pinned string must be the shape production launches see.
        provider.activity().orElseThrow().install();
        return provider;
    }

    /**
     * INV-1 oracle, provider half: a local create with every capability
     * present renders exactly this string — env cleanup prefix, the resolved
     * binary, the picked model, the display name, the preset session id, the
     * activity settings file, plan mode for a read-only session, and the
     * positional prompt after {@code --}. Every later task must keep this
     * byte-identical for a context with no persistence, whatever the flag.
     */
    @Test
    void localCreateWithEveryCapabilityRendersTodayExactString(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        ClaudeAgentProvider provider = fullCapsProvider(dir);

        LaunchPlan plan = provider.buildCreateCommand(new CreateContext("My session", "gen-42",
                Path.of("/wt/repo"), Optional.empty(), Optional.empty(), Optional.of("sonnet"), false, true,
                Optional.of("Fix the 'parser' today"), List.of()));

        assertEquals(ENV + AgentCommands.shellQuote(dir.resolve("claude").toString())
                        + " --model 'sonnet' -n 'My session' --session-id 'gen-42'"
                        + " --settings '" + dir.resolve("hooks").resolve("settings.json") + "'"
                        + " --permission-mode plan"
                        + " -- " + AgentCommands.shellQuote("Fix the 'parser' today"),
                plan.command());
        assertTrue(plan.sessionIdUsed(), "a probed --session-id makes the launch carry the id");
    }

    /**
     * The bare-capability counterpart: no model, no prompt, not read-only —
     * today's exact string is the name, the id and the settings file and
     * nothing else.
     */
    @Test
    void localCreateWithoutModelPromptOrReadOnlyRendersTodayExactString(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        ClaudeAgentProvider provider = fullCapsProvider(dir);

        LaunchPlan plan = provider.buildCreateCommand(new CreateContext("My session", "gen-42",
                Path.of("/wt/repo"), Optional.empty(), Optional.empty()));

        assertEquals(ENV + AgentCommands.shellQuote(dir.resolve("claude").toString())
                        + " -n 'My session' --session-id 'gen-42'"
                        + " --settings '" + dir.resolve("hooks").resolve("settings.json") + "'",
                plan.command());
    }

    /**
     * INV-1 oracle on resume, provider half: by id (settings, plan mode for
     * a read-only resume, and the re-orientation prompt after {@code --})
     * and by name (no prompt — nothing has been picked to re-orient).
     */
    @Test
    void localResumeRendersTodayExactStringsByIdAndByName(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        ClaudeAgentProvider provider = fullCapsProvider(dir);
        String settings = " --settings '" + dir.resolve("hooks").resolve("settings.json") + "'";
        String binary = AgentCommands.shellQuote(dir.resolve("claude").toString());

        String byId = provider.buildResumeCommand(new ResumeContext(Optional.of("conv-7"), Optional.empty(),
                Path.of("/wt/repo"), Optional.empty(), Optional.empty(), false, true, List.of(),
                Optional.of("pick up"))).command();
        assertEquals(ENV + binary + " --resume 'conv-7'" + settings
                + " --permission-mode plan -- " + AgentCommands.shellQuote("pick up"), byId);

        String byName = provider.buildResumeCommand(new ResumeContext(Optional.empty(), Optional.of("my-name"),
                Path.of("/wt/repo"), Optional.empty(), Optional.empty())).command();
        assertEquals(ENV + binary + " --resume 'my-name'" + settings, byName);
    }

    /**
     * INV-1 oracle on the remote-without-persistence branches, provider
     * half: today's remote create is a bare {@code exec env LANG=…
     * CLAUDE_CODE_NO_FLICKER=1 claude} inside the interactive ssh shell —
     * no name, no session id, no plan mode, no prompt, no env cleanup -u
     * list — and it does not claim to have used the session id.
     */
    @Test
    void remoteCreateWithoutPersistenceRendersTodayExactString() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("My session", "gen-42",
                Path.of("/tmp"), Optional.of(new SshRemote("build-box", "/srv/repo")), Optional.empty()));

        assertEquals(REMOTE_SSH_PREFIX + "exec env LANG=en_US.UTF-8 CLAUDE_CODE_NO_FLICKER=1 claude'",
                plan.command());
        assertTrue(plan.supported());
        assertFalse(plan.sessionIdUsed());
    }

    /** The picked model is shell-quoted here and then re-quoted by the ssh layer. */
    @Test
    void remoteCreateWithAModelRendersTodayExactString() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("My session", "gen-42",
                Path.of("/tmp"), Optional.of(new SshRemote("build-box", "/srv/repo")), Optional.empty(),
                Optional.of("opus"), false, false, Optional.empty(), List.of()));

        assertEquals(REMOTE_SSH_PREFIX
                + "exec env LANG=en_US.UTF-8 CLAUDE_CODE_NO_FLICKER=1 claude --model '\\''opus'\\'''",
                plan.command());
    }

    /**
     * INV-1 oracle on the remote-without-persistence resume: by id, by
     * name, and bare (the picker) — each id/name posix-quoted for the ssh
     * layer, no read-only rendering, no prompt.
     */
    @Test
    void remoteResumeWithoutPersistenceRendersTodayExactStrings() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        Optional<SshRemote> remote = Optional.of(new SshRemote("build-box", "/srv/repo"));
        String prefix = REMOTE_SSH_PREFIX + "exec env LANG=en_US.UTF-8 CLAUDE_CODE_NO_FLICKER=1 claude --resume ";

        String byId = provider.buildResumeCommand(new ResumeContext(Optional.of("conv-7"), Optional.empty(),
                Path.of("/tmp"), remote, Optional.empty())).command();
        assertEquals(prefix + "'\\''conv-7'\\'''", byId);

        String byName = provider.buildResumeCommand(new ResumeContext(Optional.empty(), Optional.of("my-name"),
                Path.of("/tmp"), remote, Optional.empty())).command();
        assertEquals(prefix + "'\\''my-name'\\'''", byName);

        String bare = provider.buildResumeCommand(new ResumeContext(Optional.empty(), Optional.empty(),
                Path.of("/tmp"), remote, Optional.empty())).command();
        assertEquals(REMOTE_SSH_PREFIX + "exec env LANG=en_US.UTF-8 CLAUDE_CODE_NO_FLICKER=1 claude --resume'",
                bare);
    }

    // ---- persistence: the workspace-box tmux branch ---------------------------

    /** A fixed managed id, so the tmux session name is a known literal. */
    private static final ManagedSessionId MANAGED_ID =
            ManagedSessionId.of("3f1a2b3c-1234-4d5e-9f0a-bcdef0123456");

    /** The binding-derived remote of a workspace box (host alias, clone path). */
    private static final SshRemote BOX = new SshRemote("workspace-box", "/home/dev/repo");

    /** The box agent environment: all LLM traffic goes to the box-local proxy. */
    private static final Map<String, String> BOX_ENV =
            Map.of("ANTHROPIC_BASE_URL", "http://127.0.0.1:4000");

    /** That environment rendered as the {@code env} literal the branch emits. */
    private static final String BOX_ENV_ENTRY = "ANTHROPIC_BASE_URL='http://127.0.0.1:4000'";

    private static RemotePersistence persistence() {
        return new RemotePersistence(MANAGED_ID, BOX_ENV);
    }

    /** The agent exec the tests pin, spelled independently of the provider's builder. */
    private static String boxExec(String claudeArguments) {
        return "exec " + ENV + BOX_ENV_ENTRY + " " + claudeArguments;
    }

    /** The full plan the provider must produce for the given agent exec. */
    private static String boxPlan(String agentExec) {
        return SshCommandBuilder.persistentSessionCommand(BOX,
                "drydock-" + MANAGED_ID, agentExec);
    }

    /**
     * AC-3/AC-6, provider half: a persistent create is the attach-or-create
     * tmux plan — the ssh keep-alive prefix, TERM forcing, the two global
     * options before {@code new-session -A -s drydock-<managed id>}, the box
     * clone path as the pane cwd — carrying the full env-cleanup prefix plus
     * {@code ANTHROPIC_BASE_URL}, the display name as {@code -n}, the
     * generated UUID as {@code --session-id} (so it appears literally and
     * the launch persists {@code agentSessionId}), plan mode for a read-only
     * session, and the initial prompt after {@code --}.
     *
     * <p>The provider here is {@code newProviderNoExecutable()} — whose
     * {@code detectCaps()} yields the conservative all-false caps — so the
     * presence of {@code -n}, {@code --session-id} and {@code
     * --permission-mode plan} also proves the branch consults no capability
     * probe: with caps consulted, all three would be absent.</p>
     */
    @Test
    void persistentCreateRendersTheAttachOrCreateTmuxPlan() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("My session",
                "gen-42", Path.of("/tmp"), Optional.of(BOX), Optional.empty(), Optional.of("sonnet"),
                false, true, Optional.of("Fix the parser today"), List.of())
                .withPersistence(persistence()));

        assertEquals(boxPlan(boxExec("claude --model 'sonnet' -n 'My session' --session-id 'gen-42'"
                + " --permission-mode plan -- 'Fix the parser today'")), plan.command());
        assertTrue(plan.supported());
        assertTrue(plan.sessionIdUsed(), "the generated --session-id must be reported as used");
        assertTrue(plan.command().contains("new-session -A -s drydock-" + MANAGED_ID),
                "AC-3: the tmux session is named after the managed id: " + plan.command());
        assertTrue(plan.command().contains("gen-42"),
                "the generated UUID appears literally so the service's contains-check passes");
    }

    /** With nothing optional picked: no model, not read-only, no prompt. */
    @Test
    void persistentCreateWithoutModelReadOnlyOrPrompt() {
        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("S", "gen-42",
                Path.of("/tmp"), Optional.of(BOX), Optional.empty())
                .withPersistence(persistence()));

        assertEquals(boxPlan(boxExec("claude -n 'S' --session-id 'gen-42'")), plan.command());
        assertTrue(plan.supported());
        assertTrue(plan.sessionIdUsed());
    }

    /**
     * AC-3/AC-6, provider half: a persistent resume attaches with the same
     * {@code new-session -A -s drydock-<managed id>} plan, resumes the exact
     * conversation id, adds plan mode for a read-only resume, and delivers
     * the re-orientation prompt after {@code --}. It does not claim a
     * session id was minted (nothing is persisted on attach).
     */
    @Test
    void persistentResumeRendersTheAttachPlanById() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("conv-7"), Optional.empty(), Path.of("/tmp"),
                        Optional.of(BOX), Optional.empty(), false, true, List.of(),
                        Optional.of("pick up"))
                        .withPersistence(persistence()));

        assertEquals(boxPlan(boxExec("claude --resume 'conv-7' --permission-mode plan -- 'pick up'")),
                plan.command());
        assertTrue(plan.supported());
        assertFalse(plan.sessionIdUsed(), "an attach never mints or rewrites a session id");
        assertTrue(plan.command().contains("new-session -A -s drydock-" + MANAGED_ID),
                "AC-3: create and resume run the identical attach-or-create command");
    }

    /** A resume without read-only or prompt is just the id. */
    @Test
    void persistentResumeWithoutReadOnlyOrPrompt() {
        LaunchPlan plan = newProviderNoExecutable().buildResumeCommand(
                new ResumeContext(Optional.of("conv-7"), Optional.empty(), Path.of("/tmp"),
                        Optional.of(BOX), Optional.empty())
                        .withPersistence(persistence()));

        assertEquals(boxPlan(boxExec("claude --resume 'conv-7'")), plan.command());
        assertTrue(plan.supported());
    }

    /**
     * A persistent resume keys on the conversation id only. An absent or
     * blank one is refused outright — never the interactive picker and
     * never a fallback to the session name: a picker on the box would open
     * some other conversation than the row the user resumed.
     */
    @Test
    void persistentResumeRefusesAnEmptyAgentSessionId() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        Optional<SshRemote> box = Optional.of(BOX);

        LaunchPlan noId = provider.buildResumeCommand(new ResumeContext(Optional.empty(),
                Optional.of("my-name"), Path.of("/tmp"), box, Optional.empty())
                .withPersistence(persistence()));
        LaunchPlan blankId = provider.buildResumeCommand(new ResumeContext(Optional.of("  "),
                Optional.empty(), Path.of("/tmp"), box, Optional.empty())
                .withPersistence(persistence()));
        LaunchPlan bare = provider.buildResumeCommand(new ResumeContext(Optional.empty(),
                Optional.empty(), Path.of("/tmp"), box, Optional.empty())
                .withPersistence(persistence()));

        assertFalse(noId.supported(), "a name must not stand in for the conversation id on a box");
        assertFalse(blankId.supported());
        assertFalse(bare.supported());
        assertEquals("", noId.command());
        assertEquals("", blankId.command());
        assertEquals("", bare.command());
    }

    /**
     * INV-15: host-path launch resources are refused for a persistent launch
     * exactly as for a plain remote one — the box cannot see this machine's
     * extra directories or guidance location.
     */
    @Test
    void persistentCreateAndResumeRefuseHostPathLaunchResources() {
        ClaudeAgentProvider provider = newProviderNoExecutable();

        LaunchPlan refusedCreate = provider.buildCreateCommand(new CreateContext("S", "gen-42",
                Path.of("/tmp"), Optional.of(BOX), Optional.empty())
                .withPersistence(persistence()).withLaunchResources(ONE_DIR));
        LaunchPlan refusedResume = provider.buildResumeCommand(
                new ResumeContext(Optional.of("conv-7"), Optional.empty(), Path.of("/tmp"),
                        Optional.of(BOX), Optional.empty())
                        .withPersistence(persistence()).withLaunchResources(ONE_DIR));

        assertFalse(refusedCreate.supported());
        assertFalse(refusedResume.supported());
    }

    /**
     * INV-15, provider half: even a launch that carries MCP access and every
     * capability renders none of the local-only surfaces — no settings file,
     * no {@code --mcp-config}, no PATH injection, no {@code --add-dir}, no
     * {@code --plugin-dir} — because none of them can cross the ssh hop. The
     * capability half of the proof is {@code
     * persistentCreateRendersTheAttachOrCreateTmuxPlan}: the provider whose
     * probe yields all-false caps still emits every flag.
     */
    @Test
    void persistentPlansCarryNoLocalFlags() {
        ClaudeAgentProvider provider = newProviderNoExecutable();
        McpAccess mcp = new McpAccess("http://127.0.0.1:0/mcp", "token", Optional.of(Path.of("/tmp/mcp.json")));

        String created = provider.buildCreateCommand(new CreateContext("S", "gen-42", Path.of("/tmp"),
                Optional.of(BOX), Optional.of(mcp)).withPersistence(persistence())).command();
        String resumed = provider.buildResumeCommand(new ResumeContext(Optional.of("conv-7"),
                Optional.empty(), Path.of("/tmp"), Optional.of(BOX), Optional.of(mcp))
                .withPersistence(persistence())).command();

        for (String command : List.of(created, resumed)) {
            assertFalse(command.contains("--settings"), command);
            assertFalse(command.contains("--mcp-config"), command);
            assertFalse(command.contains("PATH="), command);
            assertFalse(command.contains("--add-dir"), command);
            assertFalse(command.contains("--plugin-dir"), command);
        }
    }

    /**
     * The agent environment renders in sorted key order, so the same map
     * always yields the same command regardless of the caller's iteration
     * order — the same determinism {@code RemotePersistence} keeps in its
     * own rendering.
     */
    @Test
    void persistentEnvRendersInSortedKeyOrder() {
        RemotePersistence unsorted = new RemotePersistence(MANAGED_ID,
                Map.of("CLAUDE_CODE_EXTRA", "on", "ANTHROPIC_BASE_URL", "http://127.0.0.1:4000"));

        LaunchPlan plan = newProviderNoExecutable().buildCreateCommand(new CreateContext("S", "gen-42",
                Path.of("/tmp"), Optional.of(BOX), Optional.empty())
                .withPersistence(unsorted));

        assertEquals(boxPlan("exec " + ENV
                + "ANTHROPIC_BASE_URL='http://127.0.0.1:4000' CLAUDE_CODE_EXTRA='on'"
                + " claude -n 'S' --session-id 'gen-42'"), plan.command());
        assertTrue(plan.supported());
    }
}
