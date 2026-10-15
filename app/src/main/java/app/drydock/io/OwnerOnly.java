package app.drydock.io;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Owner-only file creation and tightening that degrades on filesystems
 * without POSIX permissions. The MCP config writer, the session-handoff
 * seed store, the pi extension installer, and the eval container all create
 * bearer-token material that must not be readable by other local users --
 * but {@link Files#createDirectories(Path, FileAttribute...)} and {@link
 * Files#setPosixFilePermissions} throw {@link UnsupportedOperationException}
 * on filesystems without the {@code posix} attribute view (NTFS), which
 * would turn every handoff and every config write into a failure on
 * Windows.
 *
 * <p>Constraint: on a non-POSIX filesystem these methods do NOT enforce
 * owner-only access -- they rely on the OS default (a Windows user profile
 * is not readable by other local users). Anything that must hold a secret
 * outside the profile directory cannot rely on this class alone.</p>
 */
public final class OwnerOnly {

    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            PosixFilePermissions.fromString("rw-------");

    private OwnerOnly() { }

    /**
     * Creates {@code directory} (and parents), owner-only where the
     * filesystem supports it. Always tightens afterwards, so a directory
     * left loose by an earlier run (or by a version that inherited the
     * umask) is tightened rather than trusted.
     */
    public static void createDirectories(Path directory) throws IOException {
        Files.createDirectories(directory, directoryAttributes());
        tightenDirectory(directory);
    }

    /**
     * Creates {@code file} as an empty owner-only file where the filesystem
     * supports it; throws {@link FileAlreadyExistsException} if it exists,
     * exactly like {@link Files#createFile}.
     */
    public static void createFile(Path file) throws IOException {
        Files.createFile(file, fileAttributes());
    }

    /** Tightens an existing directory to owner-only where the filesystem supports it. */
    public static void tightenDirectory(Path directory) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
        }
    }

    /** Tightens an existing file to owner-only where the filesystem supports it. */
    public static void tightenFile(Path file) throws IOException {
        if (POSIX_SUPPORTED) {
            Files.setPosixFilePermissions(file, OWNER_ONLY_FILE);
        }
    }

    private static FileAttribute<?>[] directoryAttributes() {
        return POSIX_SUPPORTED
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY)}
                : new FileAttribute<?>[0];
    }

    private static FileAttribute<?>[] fileAttributes() {
        return POSIX_SUPPORTED
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
                : new FileAttribute<?>[0];
    }
}
