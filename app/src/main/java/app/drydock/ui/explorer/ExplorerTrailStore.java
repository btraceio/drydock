package app.drydock.ui.explorer;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where each session's Explorer trail is kept between runs (Explorer delta,
 * part 1: "per-session, persisted").
 *
 * <p>A sibling of the application state file, like the annotation store, and
 * for the same reason: it is per-profile state that has nothing to do with
 * the repository being worked on and must not land inside a worktree.</p>
 *
 * <p>Writes are debounced onto one background thread and coalesced, so a
 * reader hopping through files does not touch the disk once per hop.
 * Loading is lenient -- a missing or malformed file yields no trails, and a
 * malformed waypoint is skipped rather than discarding the session's whole
 * trail. A trail is a convenience; nothing here is worth failing over.</p>
 */
public final class ExplorerTrailStore implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ExplorerTrailStore.class.getName());

    private static final int SCHEMA_VERSION = 1;

    /** One session's persisted trail. */
    public record Trail(List<NavigationTrail.Waypoint> waypoints, int cursor) {
        public static final Trail EMPTY = new Trail(List.of(), -1);

        public Trail {
            waypoints = List.copyOf(waypoints);
        }
    }

    private final Path file;
    private final Map<String, Trail> trails = new LinkedHashMap<>();
    private final AtomicReference<Map<String, Trail>> pending = new AtomicReference<>();
    private final ExecutorService saveExecutor =
            Executors.newSingleThreadExecutor(runnable -> Thread.ofVirtual().unstarted(runnable));

    public ExplorerTrailStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        load();
    }

    /** The trails file next to {@code stateFile} (same directory, {@code explorer-trails.json}). */
    public static Path siblingOf(Path stateFile) {
        return stateFile.toAbsolutePath().normalize().resolveSibling("explorer-trails.json");
    }

    public synchronized Trail load(String sessionKey) {
        return trails.getOrDefault(sessionKey, Trail.EMPTY);
    }

    /** Records {@code trail} for {@code sessionKey} and schedules a coalesced write. */
    public synchronized void save(String sessionKey, Trail trail) {
        trails.put(sessionKey, trail);
        Map<String, Trail> snapshot = new LinkedHashMap<>(trails);
        if (pending.getAndSet(snapshot) == null) {
            saveExecutor.execute(this::writePending);
        }
    }

    private void writePending() {
        Map<String, Trail> snapshot = pending.getAndSet(null);
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, JsonWriter.write(encode(snapshot)), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not persist Explorer trails to " + file, e);
        }
    }

    private static JsonValue encode(Map<String, Trail> snapshot) {
        JsonObject root = JsonObject.empty()
                .put("version", JsonNumber.of(SCHEMA_VERSION));
        JsonObject bySession = JsonObject.empty();
        for (Map.Entry<String, Trail> entry : snapshot.entrySet()) {
            List<JsonValue> waypoints = new ArrayList<>();
            for (NavigationTrail.Waypoint waypoint : entry.getValue().waypoints()) {
                waypoints.add(JsonObject.empty()
                        .put("file", new JsonString(waypoint.file().toString()))
                        .put("label", new JsonString(waypoint.label()))
                        .put("line", JsonNumber.of(waypoint.line()))
                        .put("pinned", new JsonBoolean(waypoint.pinned())));
            }
            bySession = bySession.put(entry.getKey(), JsonObject.empty()
                    .put("cursor", JsonNumber.of(entry.getValue().cursor()))
                    .put("waypoints", JsonArray.of(waypoints)));
        }
        return root.put("trails", bySession);
    }

    private synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonValue parsed = JsonParser.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof JsonObject root) || !(root.get("trails") instanceof JsonObject bySession)) {
                return;
            }
            bySession.members().forEach((sessionKey, value) -> {
                if (!(value instanceof JsonObject entry)) {
                    return;
                }
                List<NavigationTrail.Waypoint> waypoints = new ArrayList<>();
                if (entry.get("waypoints") instanceof JsonArray array) {
                    for (JsonValue element : array.elements()) {
                        decodeWaypoint(element).ifPresent(waypoints::add);
                    }
                }
                int cursor = entry.get("cursor") instanceof JsonNumber number ? number.asInt() : -1;
                if (!waypoints.isEmpty()) {
                    trails.put(sessionKey, new Trail(waypoints, cursor));
                }
            });
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Ignoring unreadable Explorer trails file " + file, e);
        }
    }

    private static java.util.Optional<NavigationTrail.Waypoint> decodeWaypoint(JsonValue value) {
        if (!(value instanceof JsonObject object) || !(object.get("file") instanceof JsonString path)) {
            return java.util.Optional.empty();
        }
        try {
            String label = object.get("label") instanceof JsonString text
                    ? text.value()
                    : Path.of(path.value()).getFileName().toString();
            int line = object.get("line") instanceof JsonNumber number ? number.asInt() : 1;
            boolean pinned = object.get("pinned") instanceof JsonBoolean flag && flag.value();
            return java.util.Optional.of(
                    new NavigationTrail.Waypoint(Path.of(path.value()), label, line, pinned));
        } catch (RuntimeException e) {
            // One malformed waypoint is not a reason to drop the trail.
            return java.util.Optional.empty();
        }
    }

    /** Flushes any pending write and stops the executor. */
    @Override
    public void close() {
        saveExecutor.execute(this::writePending);
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Explorer trail save did not finish in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
