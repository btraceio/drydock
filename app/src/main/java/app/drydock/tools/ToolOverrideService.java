package app.drydock.tools;

import app.drydock.config.UserConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads and saves one {@link ExternalTool}'s executable override -- the
 * blocking filesystem work (the executability validation, the post-save
 * re-resolution) behind async boundaries so both callers, the settings
 * rows and the startup gate dialog, can stay on the JavaFX application
 * thread.
 *
 * <p>Saving goes through {@link UserConfig#updateAsync}, so a tool override
 * write is serialized against every other preference write and cannot drop
 * the rest of the file. The override is validated BEFORE it is written: a
 * path that is not an executable file is refused and the file is left
 * byte-unchanged -- silently persisting a path that cannot work would turn
 * the next startup into the same gate dialog for a reason the user already
 * saw. After a successful write the tool is re-resolved and the outcome
 * reports where it now comes from, so the row can say "Found: ..." with a
 * real path even when the override was cleared.</p>
 */
public final class ToolOverrideService {

    /** What {@link #loadAsync} reports: the stored override and where the tool resolves today. */
    public record Snapshot(Optional<Path> override, Optional<Path> resolved, String searchedDescription) {
    }

    /** The outcome of a save: committed (with the fresh resolution), or refused with the reason. */
    public sealed interface Outcome {

        record Saved(Optional<Path> resolved) implements Outcome {
        }

        record Refused(String reason) implements Outcome {
        }
    }

    /**
     * One at a time: the saves are quick (one stat plus one small file
     * write plus one discovery), but serializing them keeps two overlapping
     * commits from interleaving their read-validate-write sequences.
     */
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> Thread.ofVirtual().unstarted(r));

    private ToolOverrideService() {
    }

    /** Loads the snapshot off the caller's thread; never blocks the FX thread. */
    public static CompletableFuture<Snapshot> loadAsync(ExternalTool tool) {
        CompletableFuture<Snapshot> future = new CompletableFuture<>();
        EXECUTOR.execute(() -> {
            try {
                future.complete(load(tool));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Validates and saves off the caller's thread; completes with {@link Outcome.Refused} for a bad path. */
    public static CompletableFuture<Outcome> saveAsync(ExternalTool tool, Optional<Path> override) {
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        EXECUTOR.execute(() -> {
            try {
                future.complete(save(tool, override));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static Snapshot load(ExternalTool tool) {
        UserConfig config = UserConfig.load();
        ToolLocator locator = new ToolLocator(tool);
        Optional<Path> resolved = locator.locate();
        return new Snapshot(config.toolOverride(tool.configKey), resolved, locator.describeSearched());
    }

    private static Outcome save(ExternalTool tool, Optional<Path> override) throws Exception {
        Optional<Path> validated = override;
        if (validated.isPresent()) {
            Path path = validated.get().toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                return new Outcome.Refused("Not an executable file: " + path);
            }
            validated = Optional.of(path);
        }
        final Optional<Path> toSave = validated;
        // Blocks until the write lands: the row disables its controls for
        // exactly this span, so an overlapping commit is impossible and the
        // re-resolution below cannot race the write.
        UserConfig.updateAsync(config -> config.withToolPath(tool.configKey, toSave)).join();
        Optional<Path> resolved = new ToolLocator(tool).locate();
        return new Outcome.Saved(resolved);
    }
}
