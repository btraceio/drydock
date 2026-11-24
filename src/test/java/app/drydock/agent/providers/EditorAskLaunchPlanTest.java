package app.drydock.agent.providers;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.agent.providers.codex.CodexAgentProvider;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import app.drydock.agent.providers.pi.PiAgentProvider;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.agent.spi.AgentProvider.EditorAskConversationProof;
import app.drydock.agent.spi.AgentProvider.EditorAskDelivery;
import app.drydock.agent.spi.AgentProvider.EditorAskLaunch;
import app.drydock.agent.spi.AgentProvider.EditorAskPiece;
import app.drydock.agent.spi.AgentProvider.EditorAskRequest;
import app.drydock.editorqa.EditorAskRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q&amp;A launch preparation: typed delivery evidence derived from the rendered command, per provider. */
class EditorAskLaunchPlanTest {

    private static final String SECRET = "synthetic-credential-9f3a";
    private static final String ENDPOINT = "http://127.0.0.1:51234/mcp";
    private static final String NONCE = "nonce-1234abcd";
    private static final String PROMPT_HEAD = "Answer it 'q' $(touch x) `y` \"z\" \\ ; Envelope: ";

    private final String marker = EditorAskRecord.newId();

    private EditorAskRequest request() {
        return new EditorAskRequest(NONCE, marker, PROMPT_HEAD + "{\"questionId\":\"" + marker + "\"}");
    }

    private static Path tokenFile(Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("token.json"), "{}");
        return file;
    }

    private static Optional<McpAccess> mcp(Path file) {
        return Optional.of(new McpAccess(ENDPOINT, SECRET, Optional.ofNullable(file)));
    }

    private static CreateContext create(Path dir, Optional<McpAccess> mcp, boolean readOnly, Optional<String> model) {
        return new CreateContext("Ask", "11111111-2222-3333-4444-555555555555", dir, Optional.empty(), mcp, model,
                false, readOnly, Optional.empty(), List.of());
    }

    private static ResumeContext resume(Path dir, Optional<McpAccess> mcp, Optional<String> id) {
        return new ResumeContext(id, Optional.empty(), dir, Optional.empty(), mcp, false, true);
    }

    private static void assertNoLeak(EditorAskLaunch launch) {
        String text = launch + " " + launch.refusal() + " " + launch.delivery() + " " + launch.observer();
        assertFalse(text.contains(SECRET), text);
        assertFalse(text.contains(ENDPOINT), text);
    }

    // ---- SPI defaults and the evidence type ----

    @Test
    void providerWithoutQaSupportRefuses(@TempDir Path dir) throws Exception {
        TerminalAgentProvider provider = new TerminalAgentProvider();
        EditorAskLaunch launch = provider.prepareEditorAskCreate(
                create(dir, mcp(tokenFile(dir)), true, Optional.empty()), request());
        assertFalse(launch.accepted());
        assertFalse(launch.plan().supported());
    }

    @Test
    void evidenceIsIncompleteWithAnyMissingPiece() {
        for (AgentKind kind : new AgentKind[] {AgentKind.CLAUDE, AgentKind.PI, AgentKind.CODEX}) {
            var all = EditorAskDelivery.required(kind);
            new EditorAskDelivery(kind, all, EditorAskConversationProof.CORRELATED_HOOK);
            for (EditorAskPiece missing : all) {
                var partial = EnumSet.copyOf(all);
                partial.remove(missing);
                assertThrows(IllegalArgumentException.class, () -> new EditorAskDelivery(
                        kind, partial, EditorAskConversationProof.CORRELATED_HOOK), kind + " without " + missing);
            }
        }
    }

    @Test
    void requestValidatesNonceMarkerAndPrompt() {
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRequest("../x", marker, "Hi " + marker));
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRequest(NONCE, "nope", "Hi nope"));
        assertThrows(IllegalArgumentException.class, () -> new EditorAskRequest(NONCE, marker, "Hi"));
        assertThrows(RuntimeException.class, () -> new EditorAskRequest(NONCE, marker, "Hi\n" + marker));
    }

    // ---- Claude ----

    private ClaudeAgentProvider claude(Path dir, boolean mcpFlag, boolean installHooks) throws Exception {
        Path stub = dir.resolve("claude");
        Files.writeString(stub, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then echo "2.1.0 (Claude Code)"; exit 0; fi
                if [ "$1" = "--help" ]; then
                  echo "  --resume [sessionId]  Resume a session"
                  echo "  --session-id <uuid>   Use a session id"
                  echo "  --settings <file>     Load settings"
                  echo "  -n, --name <name>     Name"
                """ + (mcpFlag ? "  echo \"  --mcp-config <file>   Load MCP servers\"\n" : "") + """
                  exit 0
                fi
                exit 1
                """);
        assertTrue(stub.toFile().setExecutable(true));
        ClaudeAgentProvider provider = new ClaudeAgentProvider(new ClaudeExecutableLocator(stub));
        provider.init(new AgentContext(dir, dir.resolve("activity"), Executors.newVirtualThreadPerTaskExecutor()));
        if (installHooks) {
            provider.activity().orElseThrow().install();
        }
        return provider;
    }

    @Test
    void claudeCreateCarriesToolHooksAndOnePositionalPrompt(@TempDir Path dir) throws Exception {
        ClaudeAgentProvider provider = claude(dir, true, true);
        EditorAskLaunch launch = provider.prepareEditorAskCreate(
                create(dir, mcp(tokenFile(dir)), true, Optional.of("opus")), request());
        assertTrue(launch.accepted(), launch.refusal());
        String command = launch.plan().command();
        assertTrue(command.contains(" --mcp-config '" + dir.resolve("token.json") + "'"), command);
        assertTrue(command.contains("editor-ask"), command);
        assertTrue(command.contains(" --model 'opus'"), command);
        assertTrue(command.endsWith(" -- " + AgentCommands.shellQuote(request().firstTurnPrompt())), command);
        assertEquals(1, command.split(" -- ", -1).length - 1);
        assertTrue(command.contains("'\\''"), "single quote in the prompt is escaped");
        assertEquals(EditorAskConversationProof.RENDERED_PRESET_ID, launch.delivery().orElseThrow().proof());
        // Armed before any spawn: a second arm of the same marker is refused.
        assertThrows(IllegalArgumentException.class, () -> launch.observer().orElseThrow().arm(marker));
        assertNoLeak(launch);
    }

    @Test
    void claudeResumeRendersPinnedIdWithoutModelAndOnePrompt(@TempDir Path dir) throws Exception {
        ClaudeAgentProvider provider = claude(dir, true, true);
        EditorAskLaunch launch = provider.prepareEditorAskResume(
                resume(dir, mcp(tokenFile(dir)), Optional.of("conv-77")), request());
        assertTrue(launch.accepted(), launch.refusal());
        String command = launch.plan().command();
        assertTrue(command.contains(" --resume 'conv-77'"), command);
        assertFalse(command.contains("--model"), command);
        assertTrue(command.endsWith(" -- " + AgentCommands.shellQuote(request().firstTurnPrompt())), command);
        assertEquals(EditorAskConversationProof.CORRELATED_HOOK, launch.delivery().orElseThrow().proof());
        assertTrue(provider.prepareEditorAskResume(resume(dir, mcp(tokenFile(dir)), Optional.empty()), request())
                .plan().command().isEmpty(), "the bare picker is never a Q&A resume");
    }

    @Test
    void claudeWithoutMcpConfigFlagOrHooksOrToolsRefuses(@TempDir Path dir) throws Exception {
        Path other = Files.createDirectory(dir.resolve("b"));
        Path third = Files.createDirectory(dir.resolve("c"));
        EditorAskLaunch noFlag = claude(dir, false, true).prepareEditorAskCreate(
                create(dir, mcp(tokenFile(dir)), true, Optional.empty()), request());
        assertFalse(noFlag.accepted());
        assertFalse(noFlag.plan().supported());
        EditorAskLaunch noHooks = claude(other, true, false).prepareEditorAskCreate(
                create(other, mcp(tokenFile(other)), true, Optional.empty()), request());
        assertFalse(noHooks.accepted());
        EditorAskLaunch noFile = claude(third, true, true).prepareEditorAskCreate(
                create(third, mcp(null), true, Optional.empty()), request());
        assertFalse(noFile.accepted());
        assertNoLeak(noFlag);
        assertNoLeak(noFile);
    }

    // ---- Pi ----

    private PiAgentProvider pi(Path dir) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));   // not under the state directory's pi/
        Path stub = Files.writeString(bin.resolve("pi"), "#!/bin/sh\necho 1.3.0\n");
        assertTrue(stub.toFile().setExecutable(true));
        PiAgentProvider provider = new PiAgentProvider(new PiExecutableLocator(stub));
        provider.init(new AgentContext(dir, dir.resolve("activity"), Executors.newVirtualThreadPerTaskExecutor()));
        return provider;
    }

    @Test
    void piCreateUsesSinglePositionalPathWithAdapterConfigAndExtension(@TempDir Path dir) throws Exception {
        PiAgentProvider provider = pi(dir);
        Path token = tokenFile(dir);
        EditorAskLaunch launch = provider.prepareEditorAskCreate(
                create(dir, mcp(token), true, Optional.of("m1")), request());
        assertTrue(launch.accepted(), launch.refusal());
        String command = launch.plan().command();
        assertTrue(command.contains(" -e '" + dir.resolve("pi").resolve("drydock-mcp.ts") + "'"), command);
        assertTrue(command.contains(" -e '" + dir.resolve("pi").resolve("drydock-editor-ask.ts") + "'"), command);
        assertTrue(command.contains("DRYDOCK_EDITOR_ASK_NONCE='" + NONCE + "'"), command);
        assertTrue(command.contains("DRYDOCK_EDITOR_ASK_DIR='"), command);
        assertTrue(command.contains(" --model 'm1'"), command);
        assertFalse(command.contains("initial-prompt"), "no split task injector: " + command);
        assertFalse(command.contains("DRYDOCK_INITIAL_PROMPT"), command);
        assertTrue(command.endsWith(" -- " + AgentCommands.shellQuote(request().firstTurnPrompt())), command);
        assertEquals(1, command.split(" -- ", -1).length - 1);
        assertEquals(EditorAskConversationProof.PI_EXTENSION, launch.delivery().orElseThrow().proof());
        assertTrue(Files.isDirectory(dir.resolve("pi").resolve("editor-ask").resolve(NONCE)));
        assertNoLeak(launch);
    }

    @Test
    void piResumeRendersSessionThenOnePrompt(@TempDir Path dir) throws Exception {
        PiAgentProvider provider = pi(dir);
        EditorAskLaunch launch = provider.prepareEditorAskResume(
                resume(dir, mcp(tokenFile(dir)), Optional.of("conv-9")), request());
        assertTrue(launch.accepted(), launch.refusal());
        assertTrue(launch.plan().command().endsWith(" --session 'conv-9' -- "
                + AgentCommands.shellQuote(request().firstTurnPrompt())), launch.plan().command());
        assertFalse(launch.plan().command().contains("--model"));
    }

    @Test
    void piRefusesWithoutConfigOrWithoutTheInstalledAdapter(@TempDir Path dir) throws Exception {
        PiAgentProvider provider = pi(dir);
        assertFalse(provider.prepareEditorAskCreate(create(dir, mcp(null), true, Optional.empty()), request())
                .accepted(), "no owner-only JSON");
        // The adapter's target is a non-empty directory: its install fails, so no -e adapter is rendered.
        Path other = Files.createDirectory(dir.resolve("other"));
        Files.createDirectories(other.resolve("pi").resolve("drydock-mcp.ts").resolve("blocker"));
        EditorAskLaunch noAdapter = pi(other).prepareEditorAskCreate(
                create(other, mcp(tokenFile(other)), true, Optional.empty()), request());
        assertFalse(noAdapter.accepted());
        assertFalse(noAdapter.plan().supported());
        assertNoLeak(noAdapter);
    }

    // ---- Codex ----

    private CodexAgentProvider codex(Path dir) throws Exception {
        Path stub = Files.writeString(dir.resolve("codex"), "#!/bin/sh\necho codex-cli 0.1.0\n");
        assertTrue(stub.toFile().setExecutable(true));
        CodexAgentProvider provider = new CodexAgentProvider(new CodexExecutableLocator(stub));
        provider.init(new AgentContext(dir, dir.resolve("activity"), Executors.newVirtualThreadPerTaskExecutor()));
        return provider;
    }

    @Test
    void codexCreateCarriesEndpointOverridesTokenFromFileAndOnePrompt(@TempDir Path dir) throws Exception {
        CodexAgentProvider provider = codex(dir);
        Path token = tokenFile(dir);
        EditorAskLaunch launch = provider.prepareEditorAskCreate(
                create(dir, mcp(token), true, Optional.of("gpt-x")), request());
        assertTrue(launch.accepted(), launch.refusal());
        String command = launch.plan().command();
        assertTrue(command.contains("DRYDOCK_SESSION_TOKEN=\"$(cat '" + token + "')\""), command);
        assertTrue(command.contains("mcp_servers.drydock.url="), command);
        assertTrue(command.contains("env_http_headers="), command);
        assertTrue(command.contains(" --sandbox read-only"), command);
        assertTrue(command.contains(" -m 'gpt-x'"), command);
        assertFalse(command.contains(SECRET), "the credential never rides the command");
        assertTrue(command.endsWith(" -- " + AgentCommands.shellQuote(request().firstTurnPrompt())), command);
        assertEquals(EditorAskConversationProof.CODEX_ROLLOUT_THREAD, launch.delivery().orElseThrow().proof());
        assertThrows(IllegalArgumentException.class, () -> launch.observer().orElseThrow().arm(marker));
        assertNoLeak(launch);
    }

    @Test
    void codexResumeRendersPinnedThreadWithoutModel(@TempDir Path dir) throws Exception {
        CodexAgentProvider provider = codex(dir);
        EditorAskLaunch launch = provider.prepareEditorAskResume(
                resume(dir, mcp(tokenFile(dir)), Optional.of("thread-5")), request());
        assertTrue(launch.accepted(), launch.refusal());
        String command = launch.plan().command();
        assertTrue(command.contains(" resume 'thread-5' -- " + AgentCommands.shellQuote(
                request().firstTurnPrompt())), command);
        assertFalse(command.contains(" -m "), command);
    }

    @Test
    void codexRefusesWithoutTokenFileOrAccess(@TempDir Path dir) throws Exception {
        CodexAgentProvider provider = codex(dir);
        assertFalse(provider.prepareEditorAskCreate(create(dir, mcp(null), true, Optional.empty()), request())
                .accepted(), "no token file path");
        assertFalse(provider.prepareEditorAskCreate(
                create(dir, mcp(dir.resolve("missing.json")), true, Optional.empty()), request()).accepted(),
                "token file not readable");
        assertFalse(provider.prepareEditorAskCreate(create(dir, Optional.empty(), true, Optional.empty()), request())
                .accepted(), "no access at all");
    }

    // ---- Shared context guards ----

    @Test
    void contextsThatCannotBeQaAreRefusedBeforeAnyPrompt(@TempDir Path dir) throws Exception {
        PiAgentProvider provider = pi(dir);
        Optional<McpAccess> mcp = mcp(tokenFile(dir));
        assertFalse(provider.prepareEditorAskCreate(create(dir, mcp, false, Optional.empty()), request())
                .accepted(), "not read-only");
        CreateContext withTask = new CreateContext("Ask", "id", dir, Optional.empty(), mcp, Optional.empty(),
                false, true, Optional.of("task"), List.of());
        assertFalse(provider.prepareEditorAskCreate(withTask, request()).accepted(), "a second prompt channel");
        ResumeContext eval = new ResumeContext(Optional.of("c"), Optional.empty(), dir, Optional.empty(), mcp,
                true, true);
        assertFalse(provider.prepareEditorAskResume(eval, request()).accepted(), "eval");
    }
}
