package app.drydock.ui;

import app.drydock.config.UserConfig;
import app.drydock.git.WorktreeService;
import app.drydock.lsp.JdtServerManager;
import app.drydock.lsp.LspUsageProvider;
import app.drydock.review.UsageProvider;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        final List<JdtServerManager> managers = new CopyOnWriteArrayList<>();

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

            servers.updateConfig(configured(Path.of("/opt/other-jdt.ls"))).join();
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
        List<Path> deletedCaches = new CopyOnWriteArrayList<>();
        MainWorkspace.LanguageServerRegistry servers = registry(created, deletedCaches);
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            Path removed = worktrees.resolve("removed");
            Path kept = worktrees.resolve("kept");
            Path neverAsked = worktrees.resolve("never-asked");
            servers.provider(LEXICAL, Optional.of(removed));
            servers.provider(LEXICAL, Optional.of(kept));

            servers.removeAndDeleteCache(removed).join();

            assertEquals(JdtServerManager.Readiness.CLOSED, created.managers.get(0).state());
            assertEquals(List.of(WorktreeService.canonical(removed)), deletedCaches,
                    "exactly the removed worktree's cache is deleted");
            assertEquals(1, servers.size());
            assertEquals(JdtServerManager.Readiness.NOT_STARTED, created.managers.get(1).state());

            servers.removeAndDeleteCache(neverAsked).join();
            assertEquals(List.of(WorktreeService.canonical(removed), WorktreeService.canonical(neverAsked)),
                    deletedCaches, "a worktree whose manager never existed still loses its cache");
            assertEquals(1, servers.size(), "removing an unmanaged worktree adds no manager");
        } finally {
            servers.closeAll();
        }
    }

    // ---------------------------------------------------------- live managers

    /** Runs every task inline; no timer ever fires. */
    private static final JdtServerManager.Scheduler INLINE = new JdtServerManager.Scheduler() {
        @Override
        public Instant now() {
            return Instant.EPOCH;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public Timer afterMillis(long delayMillis, Runnable task) {
            return () -> { };
        }
    };

    /** A validating jdt.ls layout (config_mac + one launcher jar) and a private -data root. */
    private static Path liveJdtHome(Path tmp) throws IOException {
        Path jdtHome = tmp.resolve("jdt");
        Files.createDirectories(jdtHome.resolve("config_mac"));
        Files.createDirectories(jdtHome.resolve("plugins"));
        Files.writeString(jdtHome.resolve("plugins/org.eclipse.equinox.launcher_x.jar"), "launcher");
        return jdtHome;
    }

    /**
     * A manager that really STARTS, over fakes: a query moves it to STARTING with a
     * live client whose {@code close()} runs {@code onClientClose} -- how a test makes
     * a close block on a latch or a barrier. Nothing is launched.
     */
    private static JdtServerManager live(Path root, Path jdtHome, Path dataRoot, Runnable onClientClose) {
        return new JdtServerManager(root,
                new JdtServerManager.LaunchConfig(jdtHome, null),
                javaHome -> "17.0.2",
                (command, workingDirectory) -> new JdtServerManager.ServerProcess() {
                    private final CompletableFuture<Integer> exit = new CompletableFuture<>();

                    @Override public InputStream stdout() { return InputStream.nullInputStream(); }
                    @Override public OutputStream stdin() { return OutputStream.nullOutputStream(); }
                    @Override public CompletableFuture<Integer> exitFuture() { return exit; }
                    @Override public void close() { exit.complete(0); }
                },
                (process, settings, listener) -> new JdtServerManager.ClientConnection() {
                    @Override
                    public CompletableFuture<JsonValue> request(String method, JsonValue params) {
                        return "shutdown".equals(method)
                                ? CompletableFuture.completedFuture(JsonNull.INSTANCE)
                                : new CompletableFuture<>();
                    }

                    @Override public void notification(String method, JsonValue params) { }
                    @Override public void close() { onClientClose.run(); }
                },
                INLINE, dataRoot);
    }

    /** Moves a live manager to STARTING, with its client installed. */
    private static void start(JdtServerManager manager, Path root) {
        assertTrue(manager.references(root.resolve("A.java"), 0, 0).isEmpty());
        assertEquals(JdtServerManager.Readiness.STARTING, manager.state());
    }

    /** A close that blocks until {@code release}, then marks {@code finished}. */
    private static Runnable blockingClose(CountDownLatch release, AtomicBoolean finished, long seconds) {
        return () -> {
            try {
                release.await(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
        };
    }

    private static MainWorkspace.LanguageServerRegistry liveRegistry(
            BiFunction<Path, UserConfig.LanguageServer, JdtServerManager> factory, List<Path> deletedCaches) {
        return new MainWorkspace.LanguageServerRegistry(factory, (root, config) -> deletedCaches.add(root));
    }

    // ------------------------------------------------------ per-query lookup

    @Test
    void aProviderBuiltBeforeATabCloseRebindsAfterIt(@TempDir Path worktrees) throws Exception {
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = registry(created, new ArrayList<>());
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            Path wt = worktrees.resolve("wt");
            UsageProvider provider = servers.provider(LEXICAL, Optional.of(wt));
            JdtServerManager first = created.last();

            servers.closeFor(wt);
            assertEquals(JdtServerManager.Readiness.CLOSED, first.state());
            assertEquals(0, servers.size());

            provider.declaration("sym").get(5, TimeUnit.SECONDS);

            assertEquals(1, servers.size(), "the provider looked its manager up again");
            assertEquals(2, created.managers.size());
            assertNotSame(first, created.last());
            assertFalse(created.last().state() == JdtServerManager.Readiness.CLOSED,
                    "the retired manager is never the one in use");
        } finally {
            servers.closeAll();
        }
    }

    // --------------------------------------------------- off-thread retirement

    @Test
    void updateConfigReturnsBeforeTheCloseFinishes(@TempDir Path tmp) throws Exception {
        Path jdtHome = liveJdtHome(tmp);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = liveRegistry((root, config) -> {
            JdtServerManager manager = live(root, jdtHome, tmp.resolve("lsp"), blockingClose(release, finished, 10));
            created.managers.add(manager);
            return manager;
        }, new CopyOnWriteArrayList<>());
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            Path wt = tmp.resolve("wt");
            servers.provider(LEXICAL, Optional.of(wt));
            start(created.last(), wt);

            long began = System.nanoTime();
            CompletableFuture<Void> retired = servers.updateConfig(configured(Path.of("/opt/other-jdt.ls")));
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

            assertTrue(tookMillis < 500, "updateConfig waited " + tookMillis + " ms on a close");
            assertFalse(retired.isDone(), "the close is still running");
            assertEquals(0, servers.size(), "the swap and drain are synchronous");
            release.countDown();
            retired.get(5, TimeUnit.SECONDS);
            assertTrue(finished.get());
            assertEquals(JdtServerManager.Readiness.CLOSED, created.managers.get(0).state());
        } finally {
            release.countDown();
            servers.closeAll();
        }
    }

    @Test
    void removalReturnsBeforeTheCloseAndClosesBeforeDeleting(@TempDir Path tmp) throws Exception {
        Path jdtHome = liveJdtHome(tmp);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = new MainWorkspace.LanguageServerRegistry(
                (root, config) -> {
                    JdtServerManager manager = live(root, jdtHome, tmp.resolve("lsp"), () -> {
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        events.add("close");
                    });
                    created.managers.add(manager);
                    return manager;
                },
                (root, config) -> events.add("delete"));
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            Path wt = tmp.resolve("wt");
            servers.provider(LEXICAL, Optional.of(wt));
            start(created.last(), wt);

            long began = System.nanoTime();
            CompletableFuture<Void> removal = servers.removeAndDeleteCache(wt);
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

            assertTrue(tookMillis < 500, "the removal waited " + tookMillis + " ms on a close");
            assertTrue(events.isEmpty(), "nothing is deleted while the server may still hold -data");
            assertEquals(0, servers.size());
            release.countDown();
            removal.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("close", "delete"), events, "close before delete");
        } finally {
            release.countDown();
            servers.closeAll();
        }
    }

    @Test
    void anUnchangedConfigStartsNoWork(@TempDir Path worktrees) {
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = registry(created, new ArrayList<>());
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls"))).join();
            servers.provider(LEXICAL, Optional.of(worktrees.resolve("wt")));
            CompletableFuture<Void> reload = servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            assertTrue(reload.isDone(), "an unchanged reload has nothing to retire");
            assertEquals(1, servers.size());
            assertEquals(JdtServerManager.Readiness.NOT_STARTED, created.last().state());
        } finally {
            servers.closeAll();
        }
    }

    // ------------------------------------------------- config/creation races

    @Test
    @Timeout(30)
    void aConfigChangeDuringCreationNeverLeavesAnOldConfigManager(@TempDir Path worktrees) throws Exception {
        UserConfig.LanguageServer configA = configured(Path.of("/opt/a"));
        UserConfig.LanguageServer configB = configured(Path.of("/opt/b"));
        Map<JdtServerManager, UserConfig.LanguageServer> createdWith = new ConcurrentHashMap<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        MainWorkspace.LanguageServerRegistry servers = new MainWorkspace.LanguageServerRegistry(
                (root, config) -> {
                    if (firstCall.getAndSet(false)) {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    JdtServerManager manager = neverStarting(root, config);
                    createdWith.put(manager, config);
                    return manager;
                },
                (root, config) -> { });
        try {
            servers.updateConfig(configA).join();
            Path wt = worktrees.resolve("wt");
            Thread creator = Thread.ofVirtual().start(() -> servers.provider(LEXICAL, Optional.of(wt)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<CompletableFuture<Void>> change = new AtomicReference<>();
            Thread changer = Thread.ofVirtual().start(() -> change.set(servers.updateConfig(configB)));
            release.countDown();
            creator.join(5_000);
            changer.join(5_000);
            change.get().get(5, TimeUnit.SECONDS);

            servers.provider(LEXICAL, Optional.of(wt));
            JdtServerManager inUse = servers.managerFor(WorktreeService.canonical(wt)).orElseThrow();
            assertEquals(configB, createdWith.get(inUse), "the manager in use runs under the new config");
            for (Map.Entry<JdtServerManager, UserConfig.LanguageServer> entry : createdWith.entrySet()) {
                if (entry.getValue().equals(configA)) {
                    assertEquals(JdtServerManager.Readiness.CLOSED, entry.getKey().state(),
                            "an old-config manager is retired, never left serving");
                }
            }
        } finally {
            release.countDown();
            servers.closeAll();
        }
    }

    @Test
    @Timeout(30)
    void concurrentConfigChangesNeverLeaveAStaleManager(@TempDir Path worktrees) throws Exception {
        UserConfig.LanguageServer configA = configured(Path.of("/opt/a"));
        UserConfig.LanguageServer configB = configured(Path.of("/opt/b"));
        UserConfig.LanguageServer configC = configured(Path.of("/opt/c"));
        Map<JdtServerManager, UserConfig.LanguageServer> createdWith = new ConcurrentHashMap<>();
        MainWorkspace.LanguageServerRegistry servers = new MainWorkspace.LanguageServerRegistry(
                (root, config) -> {
                    JdtServerManager manager = neverStarting(root, config);
                    createdWith.put(manager, config);
                    return manager;
                },
                (root, config) -> { });
        List<Path> roots = List.of(worktrees.resolve("w0"), worktrees.resolve("w1"),
                worktrees.resolve("w2"), worktrees.resolve("w3"));
        try {
            servers.updateConfig(configA).join();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Path root = roots.get(t);
                int seed = t;
                threads.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < 500; i++) {
                        if ((i + seed) % 2 == 0) {
                            servers.updateConfig((i / 2) % 2 == 0 ? configA : configB);
                        } else {
                            servers.provider(LEXICAL, Optional.of(root));
                        }
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join(20_000);
            }
            servers.updateConfig(configC).get(10, TimeUnit.SECONDS);
            for (Path root : roots) {
                servers.provider(LEXICAL, Optional.of(root));
            }
            assertEquals(roots.size(), servers.size());
            for (Path root : roots) {
                JdtServerManager manager = servers.managerFor(WorktreeService.canonical(root)).orElseThrow();
                assertEquals(configC, createdWith.get(manager), "no stale-config manager survives");
            }
        } finally {
            servers.closeAll();
        }
    }

    // ------------------------------------------------------- parallel closes

    @Test
    void closesRunInParallel(@TempDir Path tmp) throws Exception {
        Path jdtHome = liveJdtHome(tmp);
        CyclicBarrier bothClosing = new CyclicBarrier(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = liveRegistry((root, config) -> {
            JdtServerManager manager = live(root, jdtHome, tmp.resolve("lsp"), () -> {
                try {
                    bothClosing.await(3, TimeUnit.SECONDS); // a serial close never meets its sibling
                } catch (Exception e) {
                    failures.add(e);
                }
            });
            created.managers.add(manager);
            return manager;
        }, new CopyOnWriteArrayList<>());
        servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
        for (String name : List.of("one", "two")) {
            Path wt = tmp.resolve(name);
            servers.provider(LEXICAL, Optional.of(wt));
            start(created.last(), wt);
        }

        servers.closeAll();

        assertTrue(failures.isEmpty(), "the closes did not overlap: " + failures);
        for (JdtServerManager manager : created.managers) {
            assertEquals(JdtServerManager.Readiness.CLOSED, manager.state());
        }
    }

    @Test
    void aFailingCloseDoesNotStopItsSiblings(@TempDir Path tmp) throws Exception {
        Path jdtHome = liveJdtHome(tmp);
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = liveRegistry((root, config) -> {
            boolean failing = created.managers.isEmpty();
            JdtServerManager manager = live(root, jdtHome, tmp.resolve("lsp"), () -> {
                if (failing) {
                    throw new IllegalStateException("close failed");
                }
            });
            created.managers.add(manager);
            return manager;
        }, new CopyOnWriteArrayList<>());
        servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
        for (String name : List.of("one", "two", "three")) {
            Path wt = tmp.resolve(name);
            servers.provider(LEXICAL, Optional.of(wt));
            start(created.last(), wt);
        }

        servers.closeAll();

        assertEquals(3, created.managers.size());
        for (JdtServerManager manager : created.managers) {
            assertEquals(JdtServerManager.Readiness.CLOSED, manager.state());
        }
    }

    @Test
    void closeAllWaitsForEarlierRetirements(@TempDir Path tmp) throws Exception {
        Path jdtHome = liveJdtHome(tmp);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = liveRegistry((root, config) -> {
            JdtServerManager manager = live(root, jdtHome, tmp.resolve("lsp"), blockingClose(release, finished, 10));
            created.managers.add(manager);
            return manager;
        }, new CopyOnWriteArrayList<>());
        servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
        Path wt = tmp.resolve("wt");
        servers.provider(LEXICAL, Optional.of(wt));
        start(created.last(), wt);
        CompletableFuture<Void> retired = servers.updateConfig(configured(Path.of("/opt/other-jdt.ls")));
        assertFalse(retired.isDone());
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });

        servers.closeAll();

        assertTrue(finished.get(), "closeAll returned before an earlier retirement's close finished");
        assertTrue(retired.isDone());
    }

    @Test
    @Timeout(30)
    void closeAllIsBoundedByOneOverallDeadline(@TempDir Path tmp) throws Exception {
        Path jdtHome = liveJdtHome(tmp);
        CountDownLatch release = new CountDownLatch(1);
        Created created = new Created();
        MainWorkspace.LanguageServerRegistry servers = liveRegistry((root, config) -> {
            JdtServerManager manager = live(root, jdtHome, tmp.resolve("lsp"),
                    blockingClose(release, new AtomicBoolean(), 60));
            created.managers.add(manager);
            return manager;
        }, new CopyOnWriteArrayList<>());
        try {
            servers.updateConfig(configured(Path.of("/opt/jdt.ls")));
            for (String name : List.of("one", "two")) {
                Path wt = tmp.resolve(name);
                servers.provider(LEXICAL, Optional.of(wt));
                start(created.last(), wt);
            }

            long began = System.nanoTime();
            servers.closeAll();
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

            assertTrue(tookMillis < 15_000, "closeAll took " + tookMillis + " ms: one deadline, not one per manager");
            assertTrue(tookMillis >= MainWorkspace.LanguageServerRegistry.CLOSE_ALL_DEADLINE_MILLIS - 1_000,
                    "closeAll waited for the blocked closes up to the deadline (" + tookMillis + " ms)");
        } finally {
            release.countDown();
        }
    }

    // ---------------------------------------------------------------- hint

    @Test
    void hintAppliesOnlyOnceConfigIsKnownToBeEmpty() {
        MainWorkspace.LanguageServerRegistry servers = registry(new Created(), new ArrayList<>());
        assertFalse(servers.hintApplies(), "before the first load, unconfigured is unknown");
        servers.updateConfig(new UserConfig.LanguageServer(Optional.empty(), Optional.empty())).join();
        assertTrue(servers.hintApplies(), "a loaded empty config: the hint applies");
        servers.updateConfig(configured(Path.of("/opt/jdt.ls"))).join();
        assertFalse(servers.hintApplies(), "a configured tier is never told to configure itself");
        servers.closeAll();
        assertFalse(servers.hintApplies(), "a closed tier shows no hint");
    }
}
