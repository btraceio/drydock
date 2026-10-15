package app.drydock.review.tour;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The single writer of {@code review-tours.json}: one tour record per review
 * scope, written in the background and debounced.
 *
 * <p>A sibling of the annotation store rather than part of it, because
 * {@code AnnotationStore} discards its whole file on any decode error; a
 * tour must never be able to cost a reviewer their findings. Loading is
 * lenient per scope, and per step inside {@link TourCodec}.</p>
 */
public final class TourStore implements AutoCloseable {

    private static final Logger LOG = System.getLogger(TourStore.class.getName());
    private static final int SCHEMA_VERSION = 1;

    private final Path file;
    private final Map<String, TourRecord> tours = new LinkedHashMap<>();
    private final ExecutorService saveExecutor =
            Executors.newSingleThreadExecutor(runnable -> Thread.ofVirtual().unstarted(runnable));
    private final AtomicReference<Map<String, TourRecord>> pendingSnapshot = new AtomicReference<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public TourStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        loadFromDisk();
    }

    public static Path siblingOf(Path stateFile) {
        return stateFile.toAbsolutePath().normalize().resolveSibling("review-tours.json");
    }

    public synchronized Optional<TourRecord> forScope(String scopeId) {
        return Optional.ofNullable(tours.get(scopeId));
    }

    public void put(TourRecord record) {
        putInternal(record);
        fireChanged(record.tour().scopeId());
    }

    public Optional<TourRecord> mutate(String scopeId, UnaryOperator<TourRecord> transform) {
        Optional<TourRecord> result = mutateInternal(scopeId, transform);
        result.ifPresent(record -> fireChanged(scopeId));
        return result;
    }

    public void remove(String scopeId) {
        boolean removed;
        synchronized (this) {
            removed = tours.remove(scopeId) != null;
            if (removed) {
                persistAsync();
            }
        }
        if (removed) {
            fireChanged(scopeId);
        }
    }

    public Runnable addChangeListener(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void flushPendingSaves() {
        try {
            saveExecutor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // saveSnapshot logs its own failures; a wedged disk must not hang shutdown.
        }
    }

    @Override
    public void close() {
        flushPendingSaves();
        saveExecutor.shutdown();
    }

    private synchronized void putInternal(TourRecord record) {
        tours.put(record.tour().scopeId(), record);
        persistAsync();
    }

    private synchronized Optional<TourRecord> mutateInternal(String scopeId, UnaryOperator<TourRecord> transform) {
        TourRecord current = tours.get(scopeId);
        if (current == null) {
            return Optional.empty();
        }
        TourRecord next = transform.apply(current);
        tours.put(scopeId, next);
        persistAsync();
        return Optional.of(next);
    }

    private void fireChanged(String scopeId) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(scopeId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Tour store listener failed for scope " + scopeId, e);
            }
        }
    }

    private void persistAsync() {
        if (pendingSnapshot.getAndSet(Map.copyOf(tours)) == null) {
            saveExecutor.execute(() -> {
                Map<String, TourRecord> latest = pendingSnapshot.getAndSet(null);
                if (latest != null) {
                    saveSnapshot(latest);
                }
            });
        }
    }

    private void saveSnapshot(Map<String, TourRecord> snapshot) {
        try {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            JsonObject scopes = JsonObject.empty();
            snapshot.forEach((scopeId, record) -> scopes.put(scopeId, TourCodec.recordToJson(record)));
            String text = JsonWriter.write(JsonObject.empty()
                    .put("version", JsonNumber.of(SCHEMA_VERSION)).put("scopes", scopes));
            Path tempFile = Files.createTempFile(directory, file.getFileName().toString() + ".", ".tmp");
            try {
                Files.writeString(tempFile, text, StandardCharsets.UTF_8);
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save review tours to " + file, e);
        }
    }

    private void loadFromDisk() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonValue parsed = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof JsonObject root) || !(root.get("scopes") instanceof JsonObject scopes)) {
                LOG.log(Level.WARNING, "Review tours file " + file + " has no scopes; starting empty");
                return;
            }
            scopes.members().forEach((scopeId, value) -> {
                try {
                    TourCodec.recordFromJson(value).ifPresent(record -> tours.put(scopeId, record));
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Dropping malformed tour for scope " + scopeId, e);
                }
            });
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Review tours file " + file + " is malformed; starting empty", e);
        }
    }
}
