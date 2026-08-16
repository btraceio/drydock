package app.drydock.agent.providers.pi.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

/**
 * Writes {@link PiExtensionSource#SOURCE} to
 * {@code <stateDirectory>/pi/drydock-mcp.ts}, where the Pi launch command
 * points {@code -e}.
 *
 * <p>Modelled on {@code McpConfigWriter}, not on {@code ClaudeHookInstaller}:
 * the latter is a plain {@code Files.writeString} under the umask. The write
 * is temp-file-plus-rename because a second tab launching while a first is
 * {@code jiti}-loading this path would otherwise read a truncated file, and
 * the failure would be an intermittent, unreproducible loss of every drydock
 * tool in that tab.</p>
 *
 * <p>The file holds no secret, but a {@code -e} path loads with no trust
 * prompt, which makes it arbitrary code execution in every Pi tab — so both
 * it and its directory are owner-only.</p>
 *
 * <p>Performs filesystem I/O; call off the JavaFX application thread
 * (AGENTS.md).</p>
 */
public final class PiExtensionInstaller {

    private static final FileAttribute<?> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private static final String FILE_NAME = "drydock-mcp.ts";

    private final Path stateDirectory;

    public PiExtensionInstaller(Path stateDirectory) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
    }

    /** Where the extension lives, whether or not it has been written yet. */
    public Path extensionFile() {
        return stateDirectory.resolve("pi").resolve(FILE_NAME);
    }

    /** Writes (or rewrites) the extension, returning its path. */
    public Path install() throws IOException {
        Path directory = extensionFile().getParent();
        Files.createDirectories(directory.getParent());
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (FileAlreadyExistsException existing) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
        }
        Path target = extensionFile();
        writeAtomically(target, PiExtensionSource.SOURCE);
        return target;
    }

    /**
     * Temp-file-plus-rename, with a per-call temp name so two concurrent
     * installs cannot delete each other's in-progress file. Both the temp file
     * and the target are owner-only from creation, since setting permissions
     * afterwards would leave a readable window.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid()
                + "." + Thread.currentThread().threadId() + ".tmp");
        Files.deleteIfExists(temp);
        Files.createFile(temp, OWNER_ONLY);
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
