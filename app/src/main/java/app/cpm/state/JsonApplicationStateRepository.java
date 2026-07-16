package app.cpm.state;

import app.cpm.domain.ApplicationState;
import app.cpm.state.json.JsonParseException;
import app.cpm.state.json.JsonParser;
import app.cpm.state.json.JsonValue;
import app.cpm.state.json.JsonWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * JSON-file-backed {@link ApplicationStateRepository} (plan section 17).
 *
 * <p>Default location: {@code ~/Library/Application Support/ClaudeProjectManager/state.json}
 * (plan sections 6.6, 17). Writes are atomic: the new state is written to
 * a temporary file in the same directory, fsynced, the previous state
 * file (if any) is copied to a {@code .bak} sibling, and then the
 * temporary file is atomically renamed over the real state file. A crash
 * at any point before the final rename leaves the real state file
 * untouched.</p>
 *
 * <p>{@link #load()} never throws for a missing, truncated, or malformed
 * state file: it logs a warning, backs up the offending file next to
 * itself (suffixed {@code .corrupt-<epoch-millis>}), and returns {@link
 * ApplicationState#empty()}.</p>
 */
public final class JsonApplicationStateRepository implements ApplicationStateRepository {

    private static final Logger LOG = System.getLogger(JsonApplicationStateRepository.class.getName());

    private final Path stateFile;

    public JsonApplicationStateRepository(Path stateFile) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
    }

    /**
     * The default state file location (plan sections 6.6 / 17): {@code
     * ~/Library/Application Support/ClaudeProjectManager/state.json}.
     */
    public static Path defaultStateFile() {
        String home = System.getProperty("user.home");
        return Path.of(home, "Library", "Application Support", "ClaudeProjectManager", "state.json");
    }

    public static JsonApplicationStateRepository atDefaultLocation() {
        return new JsonApplicationStateRepository(defaultStateFile());
    }

    public Path stateFile() {
        return stateFile;
    }

    @Override
    public ApplicationState load() {
        if (!Files.exists(stateFile)) {
            return ApplicationState.empty();
        }
        try {
            String text = Files.readString(stateFile, StandardCharsets.UTF_8);
            JsonValue json = JsonParser.parse(text);
            return ApplicationStateCodec.fromJson(json);
        } catch (IOException | JsonParseException | StateDecodeException e) {
            LOG.log(Level.WARNING,
                    "State file " + stateFile + " is missing, truncated, or malformed; "
                            + "backing it up and starting from an empty application state", e);
            backupCorruptFile();
            return ApplicationState.empty();
        }
    }

    @Override
    public void save(ApplicationState state) {
        try {
            Path directory = stateFile.getParent();
            Files.createDirectories(directory);

            String text = JsonWriter.write(ApplicationStateCodec.toJson(state));
            Path tempFile = Files.createTempFile(directory, stateFile.getFileName().toString() + ".", ".tmp");
            try {
                writeAndFsync(tempFile, text);
                retainBackupOfExistingStateFile();
                // ATOMIC_MOVE already replaces an existing target
                // atomically on POSIX filesystems (as rename(2) does);
                // REPLACE_EXISTING is included for the (unlikely, on
                // macOS) case of a filesystem where ATOMIC_MOVE alone
                // would otherwise reject an existing target.
                Files.move(tempFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save application state to " + stateFile, e);
        }
    }

    private void writeAndFsync(Path tempFile, String text) throws IOException {
        try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            // fsync "where practical" (plan section 17); FileChannel#force
            // is the practical mechanism available without native code.
            channel.force(true);
        }
    }

    private void retainBackupOfExistingStateFile() throws IOException {
        if (Files.exists(stateFile)) {
            Path backupFile = stateFile.resolveSibling(stateFile.getFileName() + ".bak");
            Files.copy(stateFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backupCorruptFile() {
        try {
            Path backup = stateFile.resolveSibling(stateFile.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.copy(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
            LOG.log(Level.INFO, "Backed up corrupt state file to " + backup);
        } catch (IOException copyFailure) {
            LOG.log(Level.WARNING, "Could not back up corrupt state file " + stateFile, copyFailure);
        }
    }
}
