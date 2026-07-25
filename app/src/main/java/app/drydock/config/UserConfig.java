package app.drydock.config;

import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User-editable settings read from {@code ~/.drydock/config.json}, separate
 * from the app's own {@code ApplicationState} (which the app writes itself
 * and never expects a human to hand-edit). Deliberately tiny: one field for
 * now, {@code worktreesDirectory} -- the directory new worktrees are
 * created under, in place of the {@code <home>/dev/wt} default (see
 * {@link app.drydock.ui.WorktreeNaming}).
 *
 * <p>{@link #load()} never throws for a missing or malformed config file:
 * it logs a warning for malformed input and falls back to {@link #empty()},
 * consistent with how {@code JsonApplicationStateRepository} treats a
 * corrupt state file.</p>
 */
public record UserConfig(Optional<Path> worktreesDirectory) {

    private static final Logger LOG = System.getLogger(UserConfig.class.getName());

    public static UserConfig empty() {
        return new UserConfig(Optional.empty());
    }

    /** {@code ~/.drydock/config.json}. */
    public static Path defaultConfigFile() {
        return Path.of(System.getProperty("user.home"), ".drydock", "config.json");
    }

    public static UserConfig load() {
        return load(defaultConfigFile());
    }

    /**
     * As {@link #load()}, but off the caller's thread (plan section 18:
     * "file loading" must never block the JavaFX application thread) --
     * {@link #load()} does a synchronous stat + read, so callers on the FX
     * thread (e.g. the create-worktree modal) must use this instead.
     */
    public static CompletableFuture<UserConfig> loadAsync() {
        CompletableFuture<UserConfig> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> future.complete(load()));
        return future;
    }

    /** Package-visible for tests; reads and parses {@code configFile} directly. */
    static UserConfig load(Path configFile) {
        if (!Files.exists(configFile)) {
            return empty();
        }
        try {
            String text = Files.readString(configFile, StandardCharsets.UTF_8);
            JsonValue parsed = JsonParser.parse(text);
            if (!(parsed instanceof JsonObject root)) {
                throw new JsonParseException("Expected a JSON object at the top level");
            }
            Optional<Path> worktreesDirectory = root.get("worktreesDirectory") instanceof JsonString s
                    ? Optional.of(Path.of(s.value()).toAbsolutePath().normalize())
                    : Optional.empty();
            return new UserConfig(worktreesDirectory);
        } catch (IOException | JsonParseException | InvalidPathException e) {
            LOG.log(Level.WARNING, "Config file " + configFile + " is missing, unreadable, or malformed; "
                    + "ignoring it and using defaults", e);
            return empty();
        }
    }

    /**
     * Writes {@code config} to {@code configFile}: temp file in the same
     * directory, then an atomic move, so a crash mid-write can never leave a
     * truncated config where a valid one was.
     *
     * <p>Members this build does not know about are read back from the
     * existing file and preserved -- the file is hand-editable, so silently
     * dropping a key a newer build (or the user) put there would be data
     * loss.</p>
     *
     * <p>Unlike {@link #load()}, a failure here throws: a save is a
     * user-visible action, and one that silently did nothing is worse than
     * one that reports why.</p>
     */
    static void save(UserConfig config, Path configFile) throws IOException {
        JsonObject root = JsonObject.empty();
        if (Files.exists(configFile)) {
            try {
                if (JsonParser.parse(Files.readString(configFile, StandardCharsets.UTF_8))
                        instanceof JsonObject existing) {
                    root = existing;
                }
            } catch (IOException | JsonParseException e) {
                LOG.log(Level.WARNING, "Existing config " + configFile
                        + " is unreadable or malformed; replacing it", e);
            }
        }
        JsonObject finalRoot = root;
        finalRoot.members().remove("worktreesDirectory");
        config.worktreesDirectory().ifPresent(dir ->
                finalRoot.put("worktreesDirectory", new JsonString(dir.toString())));

        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "config", ".json.tmp");
        try {
            Files.writeString(temp, JsonWriter.write(root), StandardCharsets.UTF_8);
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static final AtomicReference<CompletableFuture<Void>> PENDING_SAVE =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    /**
     * As {@link #save}, off the caller's thread -- the settings modal calls
     * this from the FX thread, where a synchronous write is forbidden. The
     * returned future completes exceptionally on failure so the caller can
     * surface it; it is never swallowed.
     */
    public static CompletableFuture<Void> saveAsync(UserConfig config) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        PENDING_SAVE.set(future);
        Thread.ofVirtual().start(() -> {
            try {
                save(config, defaultConfigFile());
                future.complete(null);
            } catch (IOException | RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Awaits any in-flight {@link #saveAsync} (AGENTS.md: a service writing
     * files from a background thread exposes a flush, so shutdown and tests
     * do not race a pending write). Bounded, because a wedged disk must not
     * hang shutdown -- the atomic move means the worst case is a stale file,
     * never a corrupt one.
     */
    public static void flushPendingSaves() {
        try {
            PENDING_SAVE.get().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // saveAsync's caller already reported the failure to the user.
        }
    }
}
