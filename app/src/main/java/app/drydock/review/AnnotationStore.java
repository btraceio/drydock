package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The per-session store of Review annotations (design handoff section C):
 * in-memory, mutated on the FX thread by the Review tab, persisted as a
 * JSON file <em>alongside</em> the application state file
 * ({@code annotations.json} in the same directory as {@code state.json}).
 *
 * <p>Persisting to a sibling file -- rather than adding a member to
 * {@code state.json} -- keeps this store the single writer of its file:
 * {@code SessionManager} snapshots and rewrites {@code state.json}
 * wholesale, so a second writer there would race it. Saves run on a
 * single background thread (writes are serialized; the newest snapshot
 * wins) with the same temp-file-and-atomic-rename pattern as the state
 * repository. Rapid mutations coalesce: only the newest snapshot is
 * written, not one full-file rewrite per mutation. Loading is lenient: a
 * missing or malformed file yields an empty store.</p>
 */
public final class AnnotationStore implements AutoCloseable {

    private static final Logger LOG = System.getLogger(AnnotationStore.class.getName());

    private static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final ExecutorService saveExecutor =
            Executors.newSingleThreadExecutor(runnable -> Thread.ofVirtual().unstarted(runnable));
    private final List<ReviewAnnotation> annotations = new ArrayList<>();

    /**
     * Newest-wins pending snapshot: mutations replace it, and at most one
     * writer task is queued to consume it, so a burst of edits produces a
     * single file write of the latest state.
     */
    private final AtomicReference<List<ReviewAnnotation>> pendingSnapshot = new AtomicReference<>();

    /**
     * Change listeners, notified after every mutation with the affected
     * annotation's id (or {@code null} for a bulk change).
     *
     * <p>Exists because this store now has more than one writer: the Review
     * tab on the FX thread, and the MCP tool router on its own executor. A
     * view that caches annotation values must be told to re-read them, or a
     * later read-modify-write from the stale value silently discards the
     * other writer's change (AGENTS.md, "One writer for persistent state").</p>
     */
    private final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();

    public AnnotationStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        loadFromDisk();
    }

    /** The annotations file next to {@code stateFile} (same directory, {@code annotations.json}). */
    public static Path siblingOf(Path stateFile) {
        return stateFile.toAbsolutePath().normalize().resolveSibling("annotations.json");
    }

    // ---- queries ----

    public synchronized List<ReviewAnnotation> forSession(ManagedSessionId sessionId) {
        return annotations.stream().filter(a -> a.sessionId().equals(sessionId)).toList();
    }

    public synchronized List<ReviewAnnotation> forScope(ManagedSessionId sessionId, DiffScope scope) {
        return annotations.stream()
                .filter(a -> a.sessionId().equals(sessionId) && a.scope() == scope)
                .toList();
    }

    public synchronized Optional<ReviewAnnotation> byId(String id) {
        return annotations.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    // ---- change notification ----

    /** Registers a listener; returns a handle that unregisters it. */
    public Runnable addChangeListener(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    /**
     * Fires listeners outside this object's monitor. A listener that throws is
     * logged and skipped: notification is cosmetic next to the write that just
     * succeeded, and one bad subscriber must not fail the others.
     */
    private void fireChanged(String annotationId) {
        for (Consumer<String> listener : changeListeners) {
            try {
                listener.accept(annotationId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Annotation change listener failed", e);
            }
        }
    }

    // ---- mutations (each persists asynchronously) ----

    public void add(ReviewAnnotation annotation) {
        addInternal(annotation);
        fireChanged(annotation.id());
    }

    private synchronized void addInternal(ReviewAnnotation annotation) {
        annotations.add(annotation);
        persistAsync();
    }

    /**
     * The atomic read-modify-write: re-reads the annotation with {@code id}
     * under this store's monitor, applies {@code transform} to it, stores the
     * result and returns it. Empty when no annotation has that id.
     *
     * <p>The only way to change a stored annotation, deliberately: a
     * replace-by-id mutator alongside it would let a caller read, decide, and
     * then write, giving the other writer -- the FX Review tab or the MCP tool
     * router -- a window in which to land a change that the write then
     * overwrites (AGENTS.md: "One writer for persistent state" names this
     * read-modify-write pattern as a past data-loss bug).</p>
     *
     * <p>{@code transform} runs while the monitor is held, so it must be short
     * and must not call back into this store. It may throw: an exception
     * propagates to the caller with nothing written and no listener fired,
     * which is how a caller refuses the mutation based on the value it was
     * handed (see {@code McpToolRouter}'s {@code review_reply}). Listeners fire
     * after the monitor is released, never while it is held.</p>
     */
    public Optional<ReviewAnnotation> mutate(String id, UnaryOperator<ReviewAnnotation> transform) {
        Optional<ReviewAnnotation> updated = mutateInternal(id, transform);
        updated.ifPresent(annotation -> fireChanged(annotation.id()));
        return updated;
    }

    private synchronized Optional<ReviewAnnotation> mutateInternal(String id,
                                                                   UnaryOperator<ReviewAnnotation> transform) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transform, "transform");
        for (int i = 0; i < annotations.size(); i++) {
            if (annotations.get(i).id().equals(id)) {
                ReviewAnnotation updated = Objects.requireNonNull(transform.apply(annotations.get(i)),
                        "transform must not return null");
                if (!updated.id().equals(id)) {
                    throw new IllegalArgumentException("transform must not change the annotation id");
                }
                annotations.set(i, updated);
                persistAsync();
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    public void remove(String id) {
        if (removeInternal(id)) {
            fireChanged(id);
        }
    }

    private synchronized boolean removeInternal(String id) {
        if (annotations.removeIf(a -> a.id().equals(id))) {
            persistAsync();
            return true;
        }
        return false;
    }

    /** Drops every annotation of a deleted session. */
    public void removeSession(ManagedSessionId sessionId) {
        if (removeSessionInternal(sessionId)) {
            fireChanged(null);
        }
    }

    private synchronized boolean removeSessionInternal(ManagedSessionId sessionId) {
        if (annotations.removeIf(a -> a.sessionId().equals(sessionId))) {
            persistAsync();
            return true;
        }
        return false;
    }

    // ---- persistence ----

    /**
     * Blocks until every save queued so far has finished writing. For
     * tests and shutdown paths that must not race the background writer
     * (e.g. JUnit's {@code @TempDir} cleanup deleting the directory while
     * a queued save re-creates it).
     */
    public void flushPendingSaves() {
        try {
            saveExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // saveSnapshot never throws; it logs its own failures.
        } catch (TimeoutException e) {
            // A wedged disk must not hang shutdown forever; the atomic
            // temp-file write means the worst case is a stale file, not a
            // corrupt one.
        }
    }

    /**
     * Flushes the newest snapshot and stops the background writer. Safe to
     * call once at shutdown; mutations after close are not persisted.
     */
    @Override
    public void close() {
        flushPendingSaves();
        saveExecutor.shutdown();
    }

    private void persistAsync() {
        List<ReviewAnnotation> snapshot = List.copyOf(annotations);
        // Queue a writer task only when there is no snapshot already
        // pending; otherwise the queued task picks up this newer one.
        if (pendingSnapshot.getAndSet(snapshot) == null) {
            saveExecutor.execute(() -> {
                List<ReviewAnnotation> latest = pendingSnapshot.getAndSet(null);
                if (latest != null) {
                    saveSnapshot(latest);
                }
            });
        }
    }

    private void saveSnapshot(List<ReviewAnnotation> snapshot) {
        try {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            String text = JsonWriter.write(toJson(snapshot));
            Path tempFile = Files.createTempFile(directory, file.getFileName().toString() + ".", ".tmp");
            try {
                Files.writeString(tempFile, text, StandardCharsets.UTF_8);
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save annotations to " + file, e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            annotations.addAll(fromJson(JsonParser.parse(text)));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Annotations file " + file + " is malformed; starting empty", e);
        }
    }

    // ---- codec (package-private for tests) ----

    static JsonValue toJson(List<ReviewAnnotation> annotations) {
        JsonObject root = JsonObject.empty();
        root.put("schemaVersion", JsonNumber.of(SCHEMA_VERSION));
        List<JsonValue> entries = new ArrayList<>();
        for (ReviewAnnotation annotation : annotations) {
            JsonObject obj = JsonObject.empty();
            obj.put("id", new JsonString(annotation.id()));
            obj.put("sessionId", new JsonString(annotation.sessionId().value().toString()));
            obj.put("scope", new JsonString(annotation.scope().name()));
            obj.put("file", new JsonString(annotation.file()));
            obj.put("startKey", new JsonString(annotation.startKey()));
            obj.put("endKey", new JsonString(annotation.endKey()));
            obj.put("status", new JsonString(annotation.status().name()));
            List<JsonValue> thread = new ArrayList<>();
            for (ReviewAnnotation.Message message : annotation.thread()) {
                JsonObject messageObj = JsonObject.empty();
                messageObj.put("author", new JsonString(message.author()));
                messageObj.put("at", new JsonString(message.at().toString()));
                messageObj.put("text", new JsonString(message.text()));
                thread.add(messageObj);
            }
            obj.put("thread", new JsonArray(thread));
            entries.add(obj);
        }
        root.put("annotations", new JsonArray(entries));
        return root;
    }

    static List<ReviewAnnotation> fromJson(JsonValue value) {
        if (!(value instanceof JsonObject root) || !(root.get("annotations") instanceof JsonArray entries)) {
            return List.of();
        }
        List<ReviewAnnotation> result = new ArrayList<>();
        for (JsonValue entryValue : entries.elements()) {
            if (!(entryValue instanceof JsonObject obj)) {
                continue;
            }
            try {
                List<ReviewAnnotation.Message> thread = new ArrayList<>();
                if (obj.get("thread") instanceof JsonArray messages) {
                    for (JsonValue messageValue : messages.elements()) {
                        if (messageValue instanceof JsonObject messageObj) {
                            thread.add(new ReviewAnnotation.Message(
                                    requireString(messageObj, "author"),
                                    Instant.parse(requireString(messageObj, "at")),
                                    requireString(messageObj, "text")));
                        }
                    }
                }
                result.add(new ReviewAnnotation(
                        requireString(obj, "id"),
                        ManagedSessionId.of(requireString(obj, "sessionId")),
                        DiffScope.valueOf(requireString(obj, "scope").toUpperCase(Locale.ROOT)),
                        requireString(obj, "file"),
                        requireString(obj, "startKey"),
                        requireString(obj, "endKey"),
                        AnnotationStatus.fromPersisted(requireString(obj, "status")),
                        thread));
            } catch (IllegalArgumentException | DateTimeException e) {
                // One malformed entry never discards the rest.
                LOG.log(Level.WARNING, "Skipping malformed annotation entry: " + e.getMessage());
            }
        }
        return result;
    }

    private static String requireString(JsonObject obj, String key) {
        if (obj.get(key) instanceof JsonString s) {
            return s.value();
        }
        throw new IllegalArgumentException("Missing or non-string field: " + key);
    }
}
