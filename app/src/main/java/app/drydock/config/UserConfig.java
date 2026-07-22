package app.drydock.config;

import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
}
