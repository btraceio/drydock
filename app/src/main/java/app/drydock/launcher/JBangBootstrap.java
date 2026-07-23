package app.drydock.launcher;

import app.drydock.Main;
import app.drydock.terminal.NativeLibraryLocator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * jbang entry point. Stages the bundled native dylibs for the current CPU
 * architecture onto disk (FFM's {@code SymbolLookup.libraryLookup} needs an
 * absolute path, not a classpath resource), points the app's native-locator
 * system properties at them, then hands off to {@link Main}.
 *
 * <p>Only used when Drydock is launched from the published jbang jar; the
 * jlink image and {@code ./gradlew run} enter through {@link Main} directly
 * and resolve natives from {@code build/native} or the image {@code lib/}.</p>
 *
 * <p>Deliberately does <b>no</b> AWT/dock-icon work and never calls
 * {@code System.getenv()}/{@code ProcessBuilder} before delegating: {@link
 * Main} must run {@code LoginShellEnvironment.mergeLoginShellPath()} before the
 * environment is first snapshotted.</p>
 */
public final class JBangBootstrap {

    private static final String GHOSTTY_DYLIB = "libghostty.dylib";
    private static final String HOST_DYLIB = "libdrydockterminalhost.dylib";
    private static final List<String> DYLIBS = List.of(GHOSTTY_DYLIB, HOST_DYLIB);

    private static final String GHOSTTY_NATIVE_DIR_PROP = "app.drydock.ghostty.nativeDir";
    private static final String HOST_NATIVE_DIR_PROP = "app.drydock.terminalhost.nativeDir";

    private JBangBootstrap() {
    }

    public static void main(String[] args) throws IOException {
        String archDir = NativeLibraryLocator.detectArchDirectoryName();
        Path cacheRoot = defaultCacheRoot(resolveVersion());
        stageNatives(cacheRoot, archDir);
        applyNativeDirProperties(cacheRoot);
        Main.main(args);
    }

    /** {@code ~/Library/Caches/drydock/native/<version>}. */
    static Path defaultCacheRoot(String version) {
        return Path.of(System.getProperty("user.home"),
                "Library", "Caches", "drydock", "native", version);
    }

    /**
     * Copies this arch's two dylibs from classpath resources
     * ({@code native/<archDir>/<file>}) into {@code <cacheRoot>/<archDir>/},
     * skipping any already present. Writes via a temp file + atomic rename so
     * concurrent first-launches do not tear.
     */
    static void stageNatives(Path cacheRoot, String archDir) throws IOException {
        Path archDest = cacheRoot.resolve(archDir);
        Files.createDirectories(archDest);
        for (String dylib : DYLIBS) {
            Path dest = archDest.resolve(dylib);
            if (Files.isRegularFile(dest)) {
                continue;
            }
            copyResource("native/" + archDir + "/" + dylib, dest);
        }
    }

    private static void copyResource(String resourcePath, Path dest) throws IOException {
        try (InputStream in = JBangBootstrap.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Bundled native resource not found on classpath: " + resourcePath);
            }
            Path tmp = Files.createTempFile(dest.getParent(), ".stage-", ".tmp");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /** Points both native-locator roots at {@code cacheRoot}, never overriding a caller-set value. */
    static void applyNativeDirProperties(Path cacheRoot) {
        String root = cacheRoot.toString();
        setIfAbsent(GHOSTTY_NATIVE_DIR_PROP, root);
        setIfAbsent(HOST_NATIVE_DIR_PROP, root);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /** Manifest {@code Implementation-Version}, or {@code dev-<hash>} when unavailable. */
    static String resolveVersion() {
        String version = JBangBootstrap.class.getPackage().getImplementationVersion();
        if (version != null && !version.isBlank()) {
            return version;
        }
        return "dev-" + ghosttyContentHash();
    }

    private static String ghosttyContentHash() {
        String archDir;
        try {
            archDir = NativeLibraryLocator.detectArchDirectoryName();
        } catch (RuntimeException e) {
            return "unknown";
        }
        try (InputStream in = JBangBootstrap.class.getClassLoader()
                .getResourceAsStream("native/" + archDir + "/" + GHOSTTY_DYLIB)) {
            if (in == null) {
                return "unknown";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
