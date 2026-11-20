package app.drydock.agent.identity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;

/**
 * Installs the session {@code gh} wrapper into {@code <base>/identity/bin/gh}.
 *
 * <p>The wrapper is a generic shell script shipped as a core resource: it
 * re-invokes whatever credential command the session env names ({@code
 * DRYDOCK_AGENT_CREDENTIAL_COMMAND}), takes the fresh {@code GH_TOKEN} out of
 * its output, and execs the next {@code gh} on PATH. It knows nothing about
 * who issued the token; the glue that mints it is the user's own credential
 * command, outside this repository.</p>
 *
 * <p>The launch prefix puts {@code identity/bin} first on the session's PATH,
 * ahead of both the user's profile and the dogbrew shims, so an agent CLI's
 * {@code gh} reaches the wrapper and every gh call re-mints its token.</p>
 */
public final class AgentIdentityWrapper {

    /** The resource the wrapper script ships at. */
    static final String RESOURCE = "/app/drydock/agentidentity/gh-wrapper.sh";

    /** File mode for the installed script: executable, but only by its owner. */
    private static final FileAttribute<?> OWNER_EXECUTABLE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    /**
     * The bin directory exists solely to hold the wrapper executable: an
     * owner-only directory keeps its listing to the owning user, whatever the
     * umask would have allowed.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private AgentIdentityWrapper() {
    }

    /**
     * Installs (idempotently refreshes) the wrapper, returning the bin
     * directory to put first on the session's PATH. Refreshes when the
     * installed copy differs from the resource, so a plugin update rewrites
     * it. Performs filesystem I/O; callers run off the EDT.
     */
    public static Path install(Path baseDirectory) throws IOException {
        Path bin = baseDirectory.resolve("identity").resolve("bin");
        createBinDirectory(bin);
        Path target = bin.resolve("gh");
        byte[] script = readResource();
        if (Files.exists(target) && !Arrays.equals(Files.readAllBytes(target), script)) {
            Files.delete(target);
        }
        if (Files.notExists(target)) {
            Path temp = bin.resolve("gh.tmp");
            Files.deleteIfExists(temp);
            Files.createFile(temp, OWNER_EXECUTABLE);
            Files.write(temp, script);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return bin;
    }

    private static void createBinDirectory(Path bin) throws IOException {
        Files.createDirectories(bin.getParent());
        try {
            Files.createDirectory(bin, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (FileAlreadyExistsException existing) {
            Files.setPosixFilePermissions(bin, OWNER_ONLY_DIRECTORY);
        }
    }

    private static byte[] readResource() throws IOException {
        try (InputStream in = AgentIdentityWrapper.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new NoSuchFileException(RESOURCE, null, "the gh wrapper resource is missing");
            }
            return in.readAllBytes();
        }
    }
}
