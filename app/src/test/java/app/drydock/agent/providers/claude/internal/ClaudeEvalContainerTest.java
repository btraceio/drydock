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

    @TempDir
    Path fixtureHome;

    /**
     * The container reads the user's Claude Code state from the home dir
     * (settings, skills, plugins, marketplaces). Point it at an empty
     * fixture home so tests are hermetic: without this, mark()/wrap() on
     * a machine with a real ~/.claude would create symlinks into it and
     * put its mount in the built docker command.
     */
    @org.junit.jupiter.api.BeforeEach
    void isolateHome() throws Exception {
        Files.createDirectories(fixtureHome.resolve(".claude"));
        System.setProperty("app.drydock.eval.claude.home", fixtureHome.toString());
    }

    @org.junit.jupiter.api.AfterEach
    void restoreHome() {
        System.clearProperty("app.drydock.eval.claude.home");
    }

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

    @Test
    void markMirrorsPersonalStateIntoConfigDir() throws Exception {
        // .claude.json with host-bound members and onboarding/trust state.
        Files.writeString(fixtureHome.resolve(".claude.json"), """
                {
                  "hasCompletedOnboarding": true,
                  "apiKeyHelper": "ddtool ...",
                  "mcpServers": {"datadog": {"command": "ddtool"}},
                  "projects": {
                    "/tmp/proj": {"hasTrustDialogAccepted": true}
                  }
                }
                """);
        // Personal content entries.
        Files.createDirectories(fixtureHome.resolve(".claude/skills/someskill"));
        Files.createDirectories(fixtureHome.resolve(".claude/plugins/marketplaces"));
        Files.createDirectories(fixtureHome.resolve(".claude/plugins/cache"));
        Files.writeString(fixtureHome.resolve(".claude/plugins/known_marketplaces.json"),
                "{\"materialized\": {\"installLocation\": \"" + fixtureHome + "/inner-market\"}}");
        Files.writeString(fixtureHome.resolve(".claude/plugins/installed_plugins.json"),
                "{\"version\": 2, \"plugins\": {}}");
        Files.createDirectories(fixtureHome.resolve(".claude/agents"));
        Files.createDirectories(fixtureHome.resolve(".claude/commands"));
        Files.writeString(fixtureHome.resolve(".claude/CLAUDE.md"), "memory");

        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, fixedToken("tok", Instant.now().plusSeconds(60)));
        Path configDir = c.mark("sess").orElseThrow().configDir();

        // .claude.json: copied with the host-bound members stripped.
        String claudeJson = Files.readString(configDir.resolve(".claude.json"));
        assertTrue(claudeJson.contains("hasCompletedOnboarding"), "onboarding state carried over");
        assertTrue(claudeJson.contains("hasTrustDialogAccepted"), "project trust carried over");
        assertFalse(claudeJson.contains("mcpServers"), "host MCP servers stripped");
        assertFalse(claudeJson.contains("apiKeyHelper"), "host auth helper stripped");
        // Content entries: symlinks resolving into the fixture home.
        for (String entry : new String[] {"skills", "agents", "commands", "CLAUDE.md"}) {
            Path link = configDir.resolve(entry);
            assertTrue(Files.isSymbolicLink(link), entry + " is a symlink");
            assertEquals(fixtureHome.resolve(".claude").resolve(entry).toRealPath(),
                    Files.readSymbolicLink(link).toRealPath(), entry + " points at ~/.claude");
        }
        // plugins: a real dir -- state files copied (writable), content
        // dirs symlinked (read-only host content).
        Path plugins = configDir.resolve("plugins");
        assertTrue(Files.isDirectory(plugins), "plugins is a real dir, not a symlink");
        assertTrue(Files.isRegularFile(plugins.resolve("installed_plugins.json")),
                "installed_plugins.json copied");
        assertTrue(Files.isRegularFile(plugins.resolve("known_marketplaces.json")),
                "known_marketplaces.json present");
        assertTrue(Files.isDirectory(plugins.resolve("data")), "session-writable data dir created");
        for (String dir : new String[] {"marketplaces", "cache"}) {
            assertTrue(Files.isSymbolicLink(plugins.resolve(dir)), dir + " symlinked to host content");
        }
    }

    @Test
    void markMaterializesDeclaredMarketplacesIntoPluginState() throws Exception {
        // A known marketplace (already materialized on the host) plus an
        // in-progress one declared only in extraKnownMarketplaces.
        Files.createDirectories(fixtureHome.resolve(".claude/plugins"));
        Files.writeString(fixtureHome.resolve(".claude/plugins/known_marketplaces.json"), """
                {
                  "existing": {
                    "source": {"source": "directory", "path": "%s/existing-market"},
                    "installLocation": "%s/existing-market",
                    "lastUpdated": "2026-01-01T00:00:00.000Z"
                  }
                }
                """.formatted(fixtureHome, fixtureHome));
        Files.writeString(fixtureHome.resolve(".claude/settings.json"), """
                {
                  "extraKnownMarketplaces": {
                    "in-progress": {
                      "source": {"source": "directory", "path": "%s/in-progress-market"}
                    },
                    "existing": {
                      "source": {"source": "directory", "path": "%s/existing-market"}
                    },
                    "missing-on-disk": {
                      "source": {"source": "directory", "path": "%s/deleted-market"}
                    },
                    "remote": {
                      "source": {"source": "github", "repo": "owner/repo"}
                    }
                  }
                }
                """.formatted(fixtureHome, fixtureHome, fixtureHome));
        Files.createDirectories(fixtureHome.resolve("in-progress-market"));

        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, fixedToken("tok", Instant.now().plusSeconds(60)));
        Path configDir = c.mark("sess").orElseThrow().configDir();

        String known = Files.readString(configDir.resolve("plugins/known_marketplaces.json"));
        assertTrue(known.contains("\"existing\""), "already-materialized marketplace kept");
        assertTrue(known.contains("\"in-progress\""), "declared marketplace materialized");
        assertTrue(known.contains(fixtureHome + "/in-progress-market\""),
                "installLocation equals the declared path");
        assertFalse(known.contains("deleted-market"), "nonexistent path not materialized");
        assertFalse(known.contains("owner/repo"), "github-source declaration not materialized");
    }

    @Test
    void seedSettingsMergesUserSettingsUnderManagedAndStripsHostBound() throws Exception {
        Path configDir = Files.createDirectories(tmp.resolve("eval3").resolve("s"));
        Files.writeString(fixtureHome.resolve(".claude/settings.json"), """
                {
                  "model": "opus",
                  "theme": "dark",
                  "enabledPlugins": {"trajectory@trajectory": true},
                  "permissions": {"allow": ["Bash(git log:*)"]},
                  "statusLine": {"type": "command", "command": "git branch"},
                  "hooks": {"PreToolUse": []},
                  "mcpServers": {"datadog": {"command": "ddtool"}},
                  "env": {"ANTHROPIC_BASE_URL": "http://127.0.0.1:4000"}
                }
                """);
        Path managed = tmp.resolve("managed-settings.json");
        Files.writeString(managed, """
                {
                  "model": "sonnet",
                  "env": {
                    "ANTHROPIC_BASE_URL": "http://127.0.0.1:4000",
                    "ANTHROPIC_CUSTOM_HEADERS": "source: claude-code"
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
        assertTrue(seeded.contains("\"sonnet\""), "managed model wins over the user's");
        assertTrue(seeded.contains("enabledPlugins"), "enabledPlugins carried over (plugin activation)");
        assertTrue(seeded.contains("Bash(git log:*)"), "permissions carried over");
        assertTrue(seeded.contains("\"dark\""), "theme carried over");
        assertTrue(seeded.contains("statusLine"), "statusLine carried over (degrades gracefully)");
        assertTrue(seeded.contains("host.docker.internal:4000"), "base URL rewritten");
        assertFalse(seeded.contains("\"hooks\""), "host hooks stripped");
        assertFalse(seeded.contains("mcpServers"), "host MCP servers stripped");
    }

    @Test
    void wrapMountsUserClaudeAndExternalMarketplaces() throws Exception {
        // One marketplace inside ~/.claude (covered by its mount), one
        // outside that exists (materialized), one outside that exists only
        // as an extraKnownMarketplaces declaration, one outside that does
        // not exist at all.
        Files.createDirectories(fixtureHome.resolve(".claude/plugins/marketplaces/inner"));
        Files.writeString(fixtureHome.resolve(".claude/plugins/known_marketplaces.json"), """
                {
                  "inner": {"installLocation": "%s/.claude/plugins/marketplaces/inner"},
                  "outer": {"installLocation": "%s/outer-market"},
                  "gone": {"installLocation": "%s/deleted-market"}
                }
                """.formatted(fixtureHome, fixtureHome, fixtureHome));
        Files.writeString(fixtureHome.resolve(".claude/settings.json"), """
                {
                  "extraKnownMarketplaces": {
                    "declared-only": {
                      "source": {"source": "directory", "path": "%s/declared-market"}
                    },
                    "declared-gone": {
                      "source": {"source": "directory", "path": "%s/declared-deleted"}
                    }
                  }
                }
                """.formatted(fixtureHome, fixtureHome));
        Files.createDirectories(fixtureHome.resolve("outer-market"));
        Files.createDirectories(fixtureHome.resolve("declared-market"));

        ClaudeEvalContainer c = new ClaudeEvalContainer(tmp, fixedToken("tok", Instant.now().plusSeconds(60)));
        ClaudeEvalContainer.EvalSetup setup = c.mark("sess").orElseThrow();
        Path worktree = Files.createDirectories(tmp.resolve("wt"));
        Path hooksDir = Files.createDirectories(tmp.resolve("hooks"));
        Path activityDir = Files.createDirectories(tmp.resolve("activity"));

        String cmd = c.wrap(setup, "claude", worktree, Optional.empty(), hooksDir, activityDir);

        assertTrue(cmd.contains("-v '" + fixtureHome + "/.claude':'" + fixtureHome
                + "/.claude':ro"), "~/.claude mounted read-only at its host path: " + cmd);
        assertTrue(cmd.contains("-v '" + fixtureHome + "/outer-market':'" + fixtureHome
                + "/outer-market':ro"), "existing external marketplace mounted");
        assertTrue(cmd.contains("-v '" + fixtureHome + "/declared-market':'" + fixtureHome
                + "/declared-market':ro"), "declared-only marketplace mounted");
        assertFalse(cmd.contains("deleted-market"), "missing external marketplace not mounted");
        assertFalse(cmd.contains("declared-deleted"), "missing declared marketplace not mounted");
        assertFalse(cmd.contains("marketplaces/inner"), "in-~/.claude marketplace needs no own mount");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { count++; i += needle.length(); }
        return count;
    }
}
