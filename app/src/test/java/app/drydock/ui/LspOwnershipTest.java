package app.drydock.ui;

import app.drydock.config.UserConfig;
import app.drydock.git.WorktreeService;
import app.drydock.lsp.JdtServerManager;
import app.drydock.lsp.LspUsageProvider;
import app.drydock.review.UsageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The tier-3 ownership rules over {@link MainWorkspace.LanguageServerRegistry}
 * (spec docs/superpowers/specs/2026-10-08-lsp-tier3-usage-resolution.md,
 * §§3, 5, 8), as a plain unit over the registry the workspace owns -- the
 * {@code MainWorkspaceReviewDefaultsTest} pattern: the rules are pinned
 * without constructing a workspace, and no test ever launches a language
 * server (the injected managers are the never-starting kind; a spawn here
 * fails the test).
 *
 * <p>What composition itself does with a live manager is {@code
 * LspUsageProviderTest}'s ground; these tests pin only who owns the manager,
 * when it comes to exist, and when it goes away.</p>
 */
class LspOwnershipTest {

    /** The lexical floor: any provider instance; the tests pin identity, not behaviour. */
    private static final UsageProvider LEXICAL = new UsageProvider() {
        @Override
        public java.util.concurrent.CompletableFuture<Optional<UsageProvider.Usage>> declaration(String symbol) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public java.util.concurrent.CompletableFuture<List<UsageProvider.Usage>> usages(String symbol) {
            return java.util.concurrent.CompletableFuture.completedFuture(List.of());
        }
    };

    /** The managers a registry created, in creation order. */
    private static final class Created {
        final List<JdtServerManager> managers = new ArrayList<>();

        JdtServerManager last() {
            return managers.get(managers.size() - 1);
        }
    }

    /**
     * A never-starting manager: construction is legitimate (it is cheap and
     * validates nothing), spawning is not -- a fake factory throws, so a bug
     * that started a real server here fails loudly instead of quietly.
     */
    private static JdtServerManager neverStarting(Path root, @SuppressWarnings("unused") UserConfig.LanguageServer config) {
        return new JdtServerManager(root,
                new JdtServerManager.LaunchConfig(root.resolve("jdt.ls"), null),
                javaHome -> "17.0.2",
                (command, workingDirectory) -> {
                    throw new AssertionError("no language server may spawn in a unit test");
                },
                (process, settings, listener) -> {
                    throw new AssertionError("no language server may spawn in a unit test");
                },
                null, null);
    }

    private static MainWorkspace.LanguageServerRegistry registry(Created created, List<Path> deletedCaches) {
        return new MainWorkspace.LanguageServerRegistry(
                (root, config) -> {
                    JdtServerManager manager = neverStarting(root, config);
                    created.managers.add(manager);
                    return manager;
                },
                (root, config) -> deletedCaches.add(root));
    }

    private static UserConfig.LanguageServer configured(Path jdtHome) {
        return new UserConfig.LanguageServer(Optional.of(jdtHome), Optional.empty());
    }

    /**
     * §3: the tier is off until configured, and off means byte-identical to
     * tier 2 -- the SAME lexical instance, not an equal-looking wrapper.
     * A remote-only review has no checkout to serve (§8), so it too keeps
     * the floor; nothing is spawned in either case.
     */
    @Test
    void theUnconfiguredTierReturnsTheLexicalFloorByteForByte(@TempDir Path worktrees) {
        MainWorkspace.LanguageServerRegistry servers = registry(new Created(), new ArrayList<>());
        try {
            Path localWorktree = worktrees.resolve("wt");

            assertSame(LEXICAL, servers.provider(LEXICAL, Optional.of(localWorktree)),
                    "§3: the tier is off until configured");
            assertSame(LEXICAL, servers.provider(LEXICAL, Optional.empty()),
                    "§8: no local checkout means no language server, and the existing paths answer");
            assertEquals(0, servers.size(), "no manager may exist while the tier is off");
        } finally {
            servers.closeAll();
        }
    }

    /**
     * §5: never at worktree open -- a manager exists only after the first
     * factory ask, at most one per worktree, and a second worktree gets its
     * own rather than sharing (one server per worktree, §2).
     */
    @Test
    void noManagerExistsUntilTheFirstFactoryAskAndThenOnePerWorktree(@TempDir Path worktrees) {
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = registry(created, new ArrayList<>());
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            Path first = worktrees.resolve("first");
            Path second = worktrees.resolve("second");

            assertEquals(0, servers.size(), "a configured tier still spawns nothing at rest");

            assertInstanceOf(LspUsageProvider.class, servers.provider(LEXICAL, Optional.of(first)));
            assertEquals(1, servers.size());
            assertEquals(1, created.managers.size());

            servers.provider(LEXICAL, Optional.of(first));
            assertEquals(1, created.managers.size(), "at most one manager per worktree (§5)");

            servers.provider(LEXICAL, Optional.of(second));
            assertEquals(2, created.managers.size(), "a second worktree gets its own manager (§2)");
        } finally {
            servers.closeAll();
        }
    }

    /**
     * §3: a config change retires every live manager so the next eligible
     * query starts fresh under the new paths -- and a config reload that
     * changed nothing retires nothing (the settings modal re-reads on every
     * close).
     */
    @Test
    void aConfigChangeRetiresEveryManagerWhileAReloadDoesNot(@TempDir Path worktrees) {
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = registry(created, new ArrayList<>());
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            servers.provider(LEXICAL, Optional.of(worktrees.resolve("wt")));
            JdtServerManager firstManager = created.last();
            assertEquals(JdtServerManager.Readiness.NOT_STARTED, firstManager.state());

            servers.updateConfig(configured(Path.of("/opt/other-jdt.ls")));
            assertEquals(0, servers.size(), "the next eligible query uses the new paths");
            assertEquals(JdtServerManager.Readiness.CLOSED, firstManager.state(),
                    "retiring closes the old manager");

            servers.provider(LEXICAL, Optional.of(worktrees.resolve("wt")));
            JdtServerManager secondManager = created.last();
            servers.updateConfig(configured(Path.of("/opt/other-jdt.ls")));
            assertEquals(1, servers.size(), "an unchanged reload retires nothing");
            assertEquals(JdtServerManager.Readiness.NOT_STARTED, secondManager.state());
        } finally {
            servers.closeAll();
        }
    }

    /**
     * §5: the stop close is idempotent, closes every manager, and ends the
     * tier -- afterwards the factory hands back the lexical floor, so a
     * shutdown-time query can never spawn a server nobody will release.
     */
    @Test
    void theStopCloseIsIdempotentAndEndsTheTier(@TempDir Path worktrees) {
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = registry(created, new ArrayList<>());
        servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
        servers.provider(LEXICAL, Optional.of(worktrees.resolve("wt")));
        JdtServerManager manager = created.last();

        servers.closeAll();
        servers.closeAll();

        assertEquals(JdtServerManager.Readiness.CLOSED, manager.state());
        assertEquals(0, servers.size());
        assertSame(LEXICAL, servers.provider(LEXICAL, Optional.of(worktrees.resolve("wt"))),
                "§8: after the stop path the lower tier answers");
        assertEquals(0, servers.size());
    }

    /**
     * §5: tab close cancels that worktree's manager -- a starting one as
     * much as a running one, which close() itself distinguishes -- and
     * leaves every other worktree's server alone.
     */
    @Test
    void closingAWorktreeClosesItsManagerOnly(@TempDir Path worktrees) {
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = registry(created, new ArrayList<>());
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            Path closed = worktrees.resolve("closed");
            Path kept = worktrees.resolve("kept");
            servers.provider(LEXICAL, Optional.of(closed));
            servers.provider(LEXICAL, Optional.of(kept));

            servers.closeFor(closed);

            assertEquals(1, servers.size(), "only the closed worktree's manager is dropped");
            assertEquals(JdtServerManager.Readiness.CLOSED, created.managers.get(0).state());
            assertEquals(JdtServerManager.Readiness.NOT_STARTED, created.managers.get(1).state(),
                    "the other worktree's server is untouched");
        } finally {
            servers.closeAll();
        }
    }

    /**
     * §5: actual worktree deletion closes the worktree's manager and deletes
     * its persistent ~/.drydock/lsp/&lt;slug&gt; cache -- only that
     * worktree's -- and deletes the cache even when no manager ever existed,
     * because the cache outlives every manager (§4: it is kept between runs).
     */
    @Test
    void worktreeRemovalClosesTheManagerAndDeletesOnlyThatWorktreesCache(@TempDir Path worktrees) {
        Created created = new Created();
        List<Path> deletedCaches = new ArrayList<>();
        MainWorkspace.LanguageServerRegistry servers = registry(created, deletedCaches);
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            Path removed = worktrees.resolve("removed");
            Path kept = worktrees.resolve("kept");
            Path neverAsked = worktrees.resolve("never-asked");
            servers.provider(LEXICAL, Optional.of(removed));
            servers.provider(LEXICAL, Optional.of(kept));

            servers.removeAndDeleteCache(removed);

            assertEquals(JdtServerManager.Readiness.CLOSED, created.managers.get(0).state());
            assertEquals(List.of(WorktreeService.canonical(removed)), deletedCaches,
                    "exactly the removed worktree's cache is deleted");
            assertEquals(1, servers.size());
            assertEquals(JdtServerManager.Readiness.NOT_STARTED, created.managers.get(1).state());

            servers.removeAndDeleteCache(neverAsked);
            assertEquals(List.of(WorktreeService.canonical(removed), WorktreeService.canonical(neverAsked)),
                    deletedCaches, "a worktree whose manager never existed still loses its cache");
            assertEquals(1, servers.size(), "removing an unmanaged worktree adds no manager");
        } finally {
            servers.closeAll();
        }
    }
}
