package app.drydock.delayedtasks;

import app.drydock.tasks.TaskSnapshot;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.RefreshQueue;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The T13a store watcher: ONE app-level watch of the whole store root subtree
 * plus a single periodic batched refresh (P5), so external saves and atomic
 * replaces are observed even for files the IDE has never opened; dirty bursts
 * coalesce into one off-EDT cycle: the D6/P5 unsaved guard first (an unsaved
 * editor document refuses the reload as a {@link SaveConflict} signal, never
 * a clobbered buffer), one batched VFS refresh, then a fresh re-read
 * published as an immutable {@link WatchedSnapshot} retaining errors by
 * composition. Dispose removes the watch, listener and pending refreshes.
 */
public final class TaskStoreWatcher implements Disposable {
    private static final Logger LOG = System.getLogger(TaskStoreWatcher.class.getName());
    static final long DEBOUNCE_MILLIS = 150;
    static final long REFRESH_MILLIS = 5_000;
    private static final TaskSnapshot EMPTY_SNAPSHOT =
            new TaskSnapshot(List.of(), List.of(), Map.of(), Map.of(), List.of(), List.of());
    private final Path root;
    private final Environment environment;
    private final Callable<TaskSnapshot> reReader;
    private final Consumer<WatchedSnapshot> publisher;
    private final long debounceMillis;
    private final long refreshMillis;
    private final Object lock = new Object();
    private final LinkedHashSet<Path> dirty = new LinkedHashSet<>();
    private final ScheduledExecutorService scheduler;
    private WatchedSnapshot last;
    private ScheduledFuture<?> periodic;
    private boolean cycleScheduled;
    private boolean started;
    private volatile boolean disposed;

    public TaskStoreWatcher(Path root, Environment environment, Callable<TaskSnapshot> reReader,
            Consumer<WatchedSnapshot> publisher, long debounceMillis, long refreshMillis) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.environment = Objects.requireNonNull(environment, "environment");
        this.reReader = Objects.requireNonNull(reReader, "reReader");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.debounceMillis = debounceMillis;
        this.refreshMillis = refreshMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "drydock-task-store-watch");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        synchronized (lock) {
            if (started || disposed) {
                return;
            }
            started = true;
        }
        try {
            environment.watch(root, this::markDirty);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The store watch on " + root + " could not subscribe;"
                    + " the periodic refresh and re-reads continue", e);
        }
        try {
            periodic = scheduler.scheduleWithFixedDelay(() -> markDirty(root), refreshMillis,
                    refreshMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "The periodic store refresh on " + root + " could not start", e);
        }
        submit(this::reRead);
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            if (disposed) {
                return;
            }
            disposed = true;
            dirty.clear();
        }
        if (periodic != null) {
            periodic.cancel(false);
        }
        scheduler.shutdownNow();
        try {
            environment.stopWatching();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Removing the store watch on " + root + " failed", e);
        }
    }

    /** Records one dirty path: repeats deduplicate, paths outside the root are ignored. */
    void markDirty(Path path) {
        Path under = path == null ? null : path.toAbsolutePath().normalize();
        if (under == null || !under.startsWith(root)) {
            return;
        }
        synchronized (lock) {
            if (disposed) {
                return;
            }
            dirty.add(under);
            if (cycleScheduled) {
                return;
            }
            cycleScheduled = true;
        }
        try {
            scheduler.schedule(this::runCycle, debounceMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException disposedWhileScheduling) {
        }
    }

    /** One coalesced cycle: the D6/P5 guard first, then one batched VFS refresh. */
    private void runCycle() {
        synchronized (lock) {
            cycleScheduled = false;
            if (disposed || dirty.isEmpty()) {
                return;
            }
            dirty.clear();
        }
        List<SaveConflict> conflicts = checkUnsaved();
        if (!conflicts.isEmpty()) {
            publish(new WatchedSnapshot(last != null ? last.snapshot() : EMPTY_SNAPSHOT, conflicts,
                    last != null ? last.scanError() : null));
            return;
        }
        try {
            environment.refresh(root, () -> submit(this::reRead));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The store refresh on " + root + " failed; re-reading", e);
            submit(this::reRead);
        }
    }

    /** Queues work on the coalescing thread; queued after dispose, it is dropped. */
    private void submit(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException disposedWhileSubmitting) {
        }
    }

    /** The off-EDT re-read: a fresh immutable snapshot; failures compose, never throw. */
    private void reRead() {
        try {
            publish(new WatchedSnapshot(reReader.call(), List.of(), null));
        } catch (Exception e) { // a missing root is normal; other failures compose
            publish(new WatchedSnapshot(e instanceof NoSuchFileException ? EMPTY_SNAPSHOT
                    : last != null ? last.snapshot() : EMPTY_SNAPSHOT, List.of(),
                    e instanceof NoSuchFileException ? null : e.toString()));
        }
    }

    /** Publishes one composition (volatile, immutable); a disposed watcher publishes nothing. */
    private void publish(WatchedSnapshot snapshot) {
        if (disposed) {
            return;
        }
        last = snapshot;
        publisher.accept(snapshot);
    }

    /** The D6/P5 guard: every modified-but-unsaved document under the root as
     * a save/conflict signal; a non-empty result refuses the reload. */
    public List<SaveConflict> checkUnsaved() {
        List<SaveConflict> out = new ArrayList<>();
        List<Path> unsaved;
        try {
            unsaved = environment.unsavedDocumentsUnder(root);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Reading the unsaved documents under " + root + " failed", e);
            return out;
        }
        for (Path document : unsaved) {
            Path file = document == null ? null : document.toAbsolutePath().normalize();
            if (file != null && file.startsWith(root)) {
                out.add(new SaveConflict(file,
                        "modified but unsaved in an open editor; the reload was refused"));
            }
        }
        return out;
    }

    /** The published composition: the fresh snapshot (per-entry scan failures
     * retained inside it), the refused-reload conflicts, and the root-level error. */
    public record WatchedSnapshot(TaskSnapshot snapshot, List<SaveConflict> saveConflicts,
            String scanError) {
        public WatchedSnapshot {
            Objects.requireNonNull(snapshot, "snapshot");
            saveConflicts = List.copyOf(saveConflicts);
            scanError = scanError == null || scanError.isBlank() ? null : scanError;
        }
    }

    public record SaveConflict(Path file, String reason) {
    }

    /** The IDE seam: production wires the VFS watch, refresh and FileDocumentManager; fakes inject. */
    public interface Environment {
        /** One app-level watch of the whole root subtree; every change reaches
         * the sink as a path until stopWatching removes the watch. */
        void watch(Path root, Consumer<Path> dirty);

        /** Removes the watch; never throws. */
        void stopWatching();
        /** One batched refresh of the subtree; whenDone runs once, on any thread, after it. */
        void refresh(Path root, Runnable whenDone);

        List<Path> unsavedDocumentsUnder(Path root);
    }

    static TaskStoreWatcher forIde(DelayedTaskService service) {
        try {
            if (ApplicationManager.getApplication() == null) {
                return null;
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return new TaskStoreWatcher(service.root(), new IdeEnvironment(), service::scanForWatch,
                service::publishWatchedSnapshot, DEBOUNCE_MILLIS, REFRESH_MILLIS);
    }

    /** The production environment: one app-level VFS subscription, one scoped
     * RefreshQueue refresh of the subtree, and the FileDocumentManager's
     * unsaved set; a platform failure logs and degrades. */
    static final class IdeEnvironment implements Environment {
        private volatile MessageBusConnection watch;

        /** The one registration: the listener only offers a path to the coalescing queue. */
        @Override
        public void watch(Path root, Consumer<Path> dirty) {
            Application application = ApplicationManager.getApplication();
            if (application == null) {
                return;
            }
            MessageBusConnection connection = application.getMessageBus().connect();
            connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override
                public void after(@NotNull List<? extends VFileEvent> events) {
                    for (VFileEvent event : events) {
                        VirtualFile file = event.getFile();
                        Path path = file != null ? pathOf(file) : urlPath(event.getPath());
                        if (path != null) {
                            dirty.accept(path);
                        }
                    }
                }
            });
            this.watch = connection;
        }

        @Override
        public void stopWatching() {
            MessageBusConnection connection = watch;
            watch = null;
            if (connection != null) {
                connection.disconnect();
            }
        }

        @Override
        public void refresh(Path root, Runnable whenDone) {
            try {
                VirtualFile file = VirtualFileManager.getInstance().findFileByNioPath(root);
                if (file == null) {
                    // Not in the VFS yet: one async refresh adopts it (P5);
                    // the disk re-read proceeds without it.
                    VirtualFileManager.getInstance().asyncRefresh();
                    whenDone.run();
                } else {
                    ApplicationManager.getApplication().getService(RefreshQueue.class)
                            .refresh(true, true, whenDone, file);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "The VFS refresh of " + root + " failed; re-reading", e);
                whenDone.run();
            }
        }

        @Override
        public List<Path> unsavedDocumentsUnder(Path root) {
            List<Path> unsaved = new ArrayList<>();
            try {
                FileDocumentManager documents = FileDocumentManager.getInstance();
                for (Document document : documents.getUnsavedDocuments()) {
                    Path path = pathOf(documents.getFile(document));
                    if (path != null && path.startsWith(root)) {
                        unsaved.add(path);
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Reading the unsaved documents failed", e);
            }
            return unsaved;
        }

        private static Path pathOf(VirtualFile file) {
            if (file == null) {
                return null;
            }
            try {
                return file.toNioPath().toAbsolutePath().normalize();
            } catch (RuntimeException notLocal) {
                return null;
            }
        }

        private static Path urlPath(String url) {
            try {
                return Path.of(VfsUtilCore.urlToPath(url)).toAbsolutePath().normalize();
            } catch (RuntimeException notLocal) {
                return null;
            }
        }
    }
}
