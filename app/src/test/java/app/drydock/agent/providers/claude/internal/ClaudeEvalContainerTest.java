package app.drydock.agent.providers.claude.internal;

import app.drydock.agent.api.EvalTokenResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeEvalContainerTest {

    @TempDir
    Path tmp;

    /** A resolver that returns a fixed token with a known expiry. */
    private static EvalTokenResolver fixedToken(String token, Instant expiry) {
        return () -> Optional.of(new EvalTokenResolver.ResolvedToken(token, Optional.ofNullable(expiry)));
    }

    /** A resolver that returns empty (ddtool unavailable). */
    private static EvalTokenResolver noToken() {
        return () -> Optional.empty();
    }

    @Test
    void markResolvesTokenAndStashesSetup() {
        Instant expiry = Instant.now().plusSeconds(3600);
        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, fixedToken("tok-1", expiry));
        Optional<ClaudeEvalContainer.EvalSetup> setup = c.mark("sess");
        assertTrue(setup.isPresent());
        assertEquals("tok-1", setup.get().token());
        assertEquals(expiry, setup.get().tokenExpiry());
        assertTrue(Files.exists(setup.get().configDir().resolve("settings.json")));
    }

    @Test
    void markEmptyWhenTokenUnavailable() {
        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, noToken());
        assertTrue(c.mark("sess").isEmpty());
        assertFalse(c.setupFor("sess").isPresent());
    }

    @Test
    void markIsIdempotentAndRefreshesToken() {
        Instant t1 = Instant.now().plusSeconds(3600);
        Instant t2 = Instant.now().plusSeconds(7200);
        // Two calls return different tokens (resume refreshes).
        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, new EvalTokenResolver() {
            int n;
            @Override public Optional<ResolvedToken> resolveToken() {
                return Optional.of(new ResolvedToken("tok-" + (++n), Optional.of(n == 1 ? t1 : t2)));
            }
        });
        c.mark("sess");
        c.mark("sess");
        assertEquals("tok-2", c.setupFor("sess").orElseThrow().token());
    }

    @Test
    void resolveMainRepoRootFromWorktreeGitFile() throws Exception {
        Path mainRepo = Files.createDirectories(tmp.resolve("mainRepo"));
        Path worktrees = Files.createDirectories(mainRepo.resolve(".git").resolve("worktrees").resolve("sess"));
        Path worktree = Files.createDirectories(tmp.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + worktrees + "\n");
        assertEquals(mainRepo, ClaudeEvalContainer.resolveMainRepoRoot(worktree));
    }

    @Test
    void resolveMainRepoRootReturnsWorktreeWhenGitIsDir() throws Exception {
        Path worktree = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(worktree.resolve(".git"));
        assertEquals(worktree, ClaudeEvalContainer.resolveMainRepoRoot(worktree));
    }

    @Test
    void seedSettingsRewritesBaseUrlAppendsHeaderDropsHelper() throws Exception {
        Path configDir = Files.createDirectories(tmp.resolve("eval").resolve("sess"));
        Path managed = tmp.resolve("managed-settings.json");
        Files.writeString(managed, """
                {
                  "apiKeyHelper": "ddtool auth token rapid-ai-platform",
                  "model": "sonnet",
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:4000",
                    "ANTHROPIC_CUSTOM_HEADERS": "source: claude-code\\norg-id: 2",
                    "CLAUDE_CODE_API_KEY_HELPER_TTL_MS": "7200000"
                  }
                }
                """);
        System.setProperty("app.drydock.eval.claude.managedSettings", managed.toString());
        try {
            new ClaudeEvalContainer(tmp, noToken()).seedSettings(configDir);
        } finally {
            System.clearProperty("app.drydock.eval.claude.managedSettings");
        }

        String seeded = Files.readString(configDir.resolve("settings.json"));
        assertTrue(seeded.contains("host.docker.internal:4000"), "base URL rewritten for the container");
        assertFalse(seeded.contains("127.0.0.1:4000"), "host base URL no longer present");
        assertTrue(seeded.contains("x-target-account: eval"), "eval header appended");
        assertTrue(seeded.contains("source: claude-code"), "original custom headers preserved");
        assertTrue(seeded.contains("\"sonnet\""), "non-env settings preserved");
        assertFalse(seeded.contains("apiKeyHelper"), "apiKeyHelper dropped (token passed as env)");
        assertFalse(seeded.contains("CLAUDE_CODE_API_KEY_HELPER_TTL_MS"), "helper TTL dropped");
    }

    @Test
    void seedSettingsIsIdempotentOnHeader() throws Exception {
        Path configDir = Files.createDirectories(tmp.resolve("eval2").resolve("s"));
        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, noToken());
        c.seedSettings(configDir);
        c.seedSettings(configDir);   // second pass must not duplicate the header
        String seeded = Files.readString(configDir.resolve("settings.json"));
        assertEquals(1, countOccurrences(seeded, "x-target-account: eval"));
    }

    @Test
    void unmarkDeletesConfigDir() {
        String key = "k";
        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, noToken());
        c.unmark(key);   // idempotent on missing
        Path dir = tmp.resolve("eval").resolve(key);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("settings.json"), "{}");
        } catch (Exception ignored) { }
        c.unmark(key);
        assertFalse(Files.exists(dir));
    }

    @Test
    void wrapWritesTokenToFileNotArgvAndDropsDoubleSh() throws Exception {
        Instant expiry = Instant.now().plusSeconds(3600);
        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, fixedToken("secret-tok", expiry));
        ClaudeEvalContainer.EvalSetup setup = c.mark("sess").orElseThrow();
        Path worktree = Files.createDirectories(tmp.resolve("wt"));
        Path hooksDir = Files.createDirectories(tmp.resolve("hooks"));
        Path activityDir = Files.createDirectories(tmp.resolve("activity"));

        String cmd = c.wrap(setup, "claude --session-id 'abc'", worktree, Optional.empty(), hooksDir, activityDir);

        // The token must NOT appear in the command string (it's in a file).
        assertFalse(cmd.contains("secret-tok"), "token must not be on the argv");
        // The token file exists and holds the token.
        assertEquals("secret-tok", Files.readString(setup.configDir().resolve("auth-token")));
        // The entrypoint exports the token from the file, then runs claude.
        String entrypoint = Files.readString(setup.configDir().resolve("entrypoint.sh"));
        assertTrue(entrypoint.contains("export ANTHROPIC_API_KEY=\"$(cat "), "entrypoint exports token from file");
        assertTrue(entrypoint.contains("claude --session-id 'abc'"));
        // No double "sh": the image ENTRYPOINT is already sh, so the command
        // ends with "<image> '<entrypoint>'", not "<image> sh '<entrypoint>'".
        String imageAndAfter = cmd.substring(cmd.indexOf("drydock-claude-eval:latest"));
        assertFalse(imageAndAfter.contains(" sh "), "no extra 'sh' after the image (entrypoint is already sh)");
        assertTrue(imageAndAfter.startsWith("drydock-claude-eval:latest '"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { count++; i += needle.length(); }
        return count;
    }
}
