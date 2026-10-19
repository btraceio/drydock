package app.drydock.intellij.projects;

import app.drydock.domain.RepositoryCatalog;
import app.drydock.domain.RepositoryId;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsException;
import app.drydock.projects.SharedProjectsFile;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The plugin's view of the registered-projects list it shares with the
 * standalone app ({@link SharedProjectsFile}): which projects the Drydock
 * tool window lists.
 *
 * <p>Seeded from the app's own list when no shared list exists yet — never
 * from the plugin's catalog, which registered every project the IDE ever
 * opened (the user's decision). Opening a project registers nothing; only
 * {@link #register} does, and only the tool window's "Add" and starting a
 * task call it.</p>
 *
 * <p>All file work runs on the given single-thread scheduler, which also
 * re-reads the file every {@value #POLL_SECONDS} seconds so a project added
 * or removed in the app shows up here. {@link #projects()} is a cached
 * snapshot and safe on the EDT. Listeners run on the scheduler.</p>
 */
public final class SharedProjectsService {

    private static final Logger LOG = System.getLogger(SharedProjectsService.class.getName());

    private static final long POLL_SECONDS = 2;

    private final SharedProjectsFile file;
    private final Supplier<List<SharedProject>> appSeed;
    private final ScheduledExecutorService executor;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile List<SharedProject> projects = List.of();
    private volatile Map<Path, SharedProject> byCanonicalRoot = Map.of();
    private volatile Optional<String> problem = Optional.empty();
    /** Guarded by {@code this}. */
    private long stamp = Long.MIN_VALUE;

    public SharedProjectsService(SharedProjectsFile file, Supplier<List<SharedProject>> appSeed,
                                 ScheduledExecutorService executor) {
        this.file = Objects.requireNonNull(file, "file");
        this.appSeed = Objects.requireNonNull(appSeed, "appSeed");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Seeds when needed, reads the list, then follows it; the future completes after the first read. */
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                file.seedIfAbsent(appSeed);
            } catch (SharedProjectsException e) {
                LOG.log(Level.WARNING, () -> "Could not seed the shared projects list: " + e.getMessage());
            }
            refresh();
        }, executor);
        executor.scheduleWithFixedDelay(this::refreshQuietly, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
        return first;
    }

    public void stop() {
        executor.shutdownNow();
    }

    /** The registered projects, local and remote, as last read. EDT-safe. */
    public List<SharedProject> projects() {
        return projects;
    }

    /**
     * Why the shared list cannot be changed, for the tool window to show:
     * malformed, entries this version cannot read, or a newer schema.
     * Empty when all is well.
     */
    public Optional<String> problem() {
        return problem;
    }

    /** The project registered at {@code mainRoot}, compared canonically. Touches the disk: not on the EDT. */
    public Optional<SharedProject> byRoot(Path mainRoot) {
        return Optional.ofNullable(byCanonicalRoot.get(RepositoryCatalog.canonicalize(mainRoot)));
    }

    /** Registers {@code mainRoot} unless it already is; completes with its entry either way. */
    public CompletableFuture<SharedProject> register(Path mainRoot, String displayName) {
        return CompletableFuture.supplyAsync(() -> {
            Path canonical = RepositoryCatalog.canonicalize(mainRoot);
            SharedProject[] result = new SharedProject[1];
            try {
                file.update(list -> {
                    for (SharedProject existing : list) {
                        if (RepositoryCatalog.canonicalize(existing.root()).equals(canonical)) {
                            result[0] = existing;
                            return list;
                        }
                    }
                    result[0] = SharedProject.local(RepositoryId.newId(), canonical, displayName, Instant.now());
                    List<SharedProject> next = new ArrayList<>(list);
                    next.add(result[0]);
                    return next;
                }, appSeed);
            } catch (SharedProjectsException e) {
                throw new CompletionException(e);
            }
            refresh();
            return result[0];
        }, executor);
    }

    /** Takes {@code id} off the shared list — for the app too. Touches nothing else. */
    public CompletableFuture<Void> remove(RepositoryId id) {
        return CompletableFuture.runAsync(() -> {
            try {
                file.update(list -> list.stream().filter(project -> !project.id().equals(id)).toList(), appSeed);
            } catch (SharedProjectsException e) {
                throw new CompletionException(e);
            }
            refresh();
        }, executor);
    }

    /** Calls {@code listener} after every change to the list; returns the call that unsubscribes it. */
    public Runnable addListener(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Re-reads the file when it changed and tells the listeners. Blocking; package-private for tests. */
    synchronized void refresh() {
        SharedProjectsFile.Snapshot snapshot = file.read();
        if (!snapshot.present()) {
            // Deleted while running (the advice for a malformed file): seed
            // it again rather than show, and later write, an empty list.
            try {
                file.seedIfAbsent(appSeed);
            } catch (SharedProjectsException e) {
                LOG.log(Level.WARNING, () -> "Could not re-seed the shared projects list: " + e.getMessage());
            }
            snapshot = file.read();
        }
        if (snapshot.stamp() == stamp && snapshot.problem().equals(problem)) {
            return;
        }
        stamp = snapshot.stamp();
        problem = snapshot.problem();
        if (snapshot.malformed()) {
            // Keep showing the last good list while the file is being fixed.
            listeners.forEach(Runnable::run);
            return;
        }
        Map<Path, SharedProject> index = new LinkedHashMap<>();
        for (SharedProject project : snapshot.projects()) {
            index.put(RepositoryCatalog.canonicalize(project.root()), project);
        }
        projects = snapshot.projects();
        byCanonicalRoot = index;
        listeners.forEach(Runnable::run);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read the shared projects list", e);
        }
    }
}
