package app.drydock.state;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Resolves the app's per-launch-path state directory: {@code <data root>/<app
 * dir>/<install id>}, where the install id is derived from where this
 * instance's code actually runs from.
 *
 * <p>Why. The state directory used to be one fixed, shared path, so two app
 * instances running side by side -- a kept-running working install and the
 * image of the build under test, which is exactly how a release is verified
 * -- each held the same {@code state.json} in memory and wrote it through
 * {@code ApplicationState}'s single writer each: one instance's save deleted
 * the other's sessions, and the second launch's {@code
 * McpConfigWriter.purgeStale()} destroyed the first's per-session MCP tokens
 * while their agents were alive. Worse, the second launch STARTED on the
 * first's everything: its open sessions, its tabs, its UI arrangement, its
 * reviews -- state that belonged to a different instance, now apparently
 * mine. Instances that share a directory share one writer either way, so the
 * directory is per launch path and never shared.</p>
 *
 * <p>The install id is a short hash of the code source's real path: stable
 * across restarts and rebuilds of one install (the path is the install's
 * own; nothing is stored, so nothing can be lost), distinct across installs
 * -- the {@code build/dist} bundle, a built runtime image, and a {@code
 * gradlew run} of the working tree each own a directory, their own writer
 * behind the instance lock below, and nothing of each other's.</p>
 *
 * <p>Nothing is adopted from anywhere. A fresh install directory has no
 * repositories and no sessions; they are what that install itself
 * registers. An install's pre-per-install state remains in the shared
 * directory it used to write (and no running instance touches it anymore) --
 * that is the old install's archive, read by nobody else. Re-deriving an
 * install's state from a DIFFERENT instance's file would reintroduce the
 * very mix this split exists to remove.</p>
 *
 * <p>One running instance per launch path, enforced with a file lock: a
 * second launch of the same install refuses to start rather than race its
 * sibling's writes and tokens again. Launches of different launch paths
 * resolve to distinct directories and run freely beside each other.</p>
 */
public final class StateDirectory {

    private static final Logger LOG = System.getLogger(StateDirectory.class.getName());

    /**
     * The subdirectory of the platform's Application Support the app keeps its
     * data under. Historical name, kept so existing data stays where it is
     * rather than moving an unknown install's files out of reach.
     */
    private static final String APP_DIRECTORY_NAME = "ClaudeProjectManager";

    private StateDirectory() {
    }

    /**
     * This install's state file, {@code
     * <data root>/ClaudeProjectManager/install-<id>/state.json}. {@code
     * anchor} is this app's own class -- the code source {@link
     * #installDirectoryName} hashes.
     *
     * <p>The data root is macOS-only by construction: {@code ~/Library/
     * Application Support}. The app is macOS-only (AppKit/ghostty host),
     * so no other platform's convention is resolved here.
     *
     * <p>A launch whose code source cannot be determined keeps the shared
     * directory it has always used ({@code .../ClaudeProjectManager/state.json}):
     * that launch has no identity to install on, and an invented one would
     * lose its state.</p>
     */
    public static Path defaultStateFile(Class<?> anchor) {
        Path dataRoot = Path.of(System.getProperty("user.home"), "Library", "Application Support",
                APP_DIRECTORY_NAME);
        return stateFileUnder(dataRoot, codeSourceOf(anchor).orElse(null));
    }

    /**
     * {@link #defaultStateFile}'s resolution over an injected data root and
     * code source, so the naming is testable: the state file under {@code
     * dataRoot}/<install id>, for the shared directory itself when the code
     * source is absent. Resolving a location creates nothing.
     */
    public static Path stateFileUnder(Path dataRoot, Path codeSource) {
        if (codeSource == null) {
            return dataRoot.resolve("state.json");
        }
        return dataRoot.resolve(installDirectoryName(codeSource), "state.json");
    }

    /**
     * The code source of {@code anchor}'s class loader -- where this
     * install's jars (or classes) actually are -- realpath'd; empty when it
     * cannot be determined.
     */
    public static Optional<Path> codeSourceOf(Class<?> anchor) {
        try {
            var location = anchor.getProtectionDomain().getCodeSource();
            if (location == null) {
                return Optional.empty();
            }
            return Optional.of(Path.of(location.getLocation().toURI()).toRealPath());
        } catch (URISyntaxException | IOException e) {
            LOG.log(Level.WARNING, "Could not resolve this install's code source", e);
            return Optional.empty();
        }
    }

    /**
     * The install directory's name: {@code install-} plus the first 10 hex of
     * SHA-256 over the code source path. The hash is all the identity there
     * is; stability comes from the path being the install's own, not from
     * anything stored.
     */
    public static String installDirectoryName(Path codeSource) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(
                    sha256.digest(codeSource.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return "install-" + hex.substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to exist", e);
        }
    }

    /**
     * The outcome of claiming the instance lock. The three outcomes must
     * stay distinct: "another instance holds it" gets the "close that one"
     * dialog, while "could not lock" (read-only directory, full disk, a
     * filesystem without advisory locks) is an environment failure the user
     * cannot fix by closing anything -- collapsing the two misreports an
     * unusable machine as a second instance.
     */
    public sealed interface InstanceLock {

        /** This instance holds the lock; keep {@link #channel()} open, close it on shutdown. */
        record Held(FileChannel channel) implements InstanceLock { }

        /** Another running instance holds the lock. */
        record HeldByOther() implements InstanceLock { }

        /** The lock could not be taken; {@code cause} is the reason, for the log and the alert. */
        record Failed(Exception cause) implements InstanceLock { }
    }

    /**
     * Claims an exclusive, advisory lock on {@code stateDirectory}, returning
     * the outcome: {@link InstanceLock.Held} carries the channel the caller
     * keeps open and closes on shutdown; {@link InstanceLock.HeldByOther}
     * means another running instance holds it; {@link InstanceLock.Failed}
     * means the lock could not be taken at all (the cause is logged here and
     * carried for the caller's alert). Creates the directory if needed. The
     * channel is closed before any failure outcome is returned.
     *
     * <p>The lock lives in the state directory itself ({@code
     * instance.lock}) rather than next to the state file, so it guards the
     * whole directory -- annotations, tokens, trails -- not just one file.</p>
     */
    public static InstanceLock tryLockInstance(Path stateDirectory) {
        FileChannel channel = null;
        try {
            Files.createDirectories(stateDirectory);
            channel = FileChannel.open(stateDirectory.resolve("instance.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return new InstanceLock.HeldByOther();
            }
            return new InstanceLock.Held(channel);
        } catch (IOException | OverlappingFileLockException e) {
            if (channel != null && channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    closeFailure.addSuppressed(e);
                }
            }
            LOG.log(Level.WARNING, "Could not lock the state directory " + stateDirectory, e);
            return new InstanceLock.Failed(e);
        }
    }

    /** Releases the instance lock. Idempotent; a null channel is a caller that never locked. */
    public static void releaseInstance(FileChannel lock) {
        if (lock == null || !lock.isOpen()) {
            return;
        }
        try {
            lock.close();
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Could not release the instance lock", e);
        }
    }
}