package app.cpm.terminal.host;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Locates and loads the native {@code libcpmterminalhost} shared library
 * (the AppKit host shim, plan section 8) for the current machine's CPU
 * architecture.
 *
 * <p>Deliberately mirrors {@code app.cpm.terminal.ghostty.GhosttyNativeLibrary}
 * (same resolution order, same dual-architecture deviation, same "one place
 * allowed to branch on os.arch" rule) but is kept as a separate class/file
 * because this shim is built from a completely separate native source tree
 * ({@code native-host/}, not {@code third_party/ghostty}) with no
 * dependency on libghostty whatsoever -- see
 * {@code native-host/CpmTerminalHost.h}.</p>
 *
 * <p>Part of the narrow native boundary (plan section 2.4/4.2): together
 * with {@code app.cpm.terminal.ghostty} and {@code app.cpm.terminal.host},
 * this is the complete set of packages allowed to touch {@code
 * MemorySegment}/{@code Linker}/{@code MethodHandle}/native pointers for
 * terminal rendering.</p>
 */
public final class CpmTerminalHostLibrary {

    /**
     * Mirrors {@code GhosttyNativeLibrary#NATIVE_DIR_PROPERTY}: a
     * <b>root</b> directory containing one {@code macos-x86_64}/{@code
     * macos-arm64} subdirectory per architecture. Arch selection always
     * goes through {@link #detectArchDirectoryName()}; this property only
     * relocates the root it is resolved under (used by the jlink runtime
     * image's bundled {@code <image>/lib} directory).
     */
    public static final String NATIVE_DIR_PROPERTY = "app.cpm.terminalhost.nativeDir";

    private static volatile SymbolLookup lookup;

    private CpmTerminalHostLibrary() {
    }

    public static SymbolLookup lookup() {
        SymbolLookup result = lookup;
        if (result == null) {
            synchronized (CpmTerminalHostLibrary.class) {
                result = lookup;
                if (result == null) {
                    result = SymbolLookup.libraryLookup(resolveLibraryPath(), Arena.global());
                    lookup = result;
                }
            }
        }
        return result;
    }

    static Path resolveLibraryPath() {
        String archDir = detectArchDirectoryName();
        String fileName = "libcpmterminalhost.dylib";

        String override = System.getProperty(NATIVE_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override).resolve(archDir).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            throw new IllegalStateException(
                "System property " + NATIVE_DIR_PROPERTY + "='" + override
                    + "' does not contain " + archDir + "/" + fileName);
        }

        Path buildNative = findBuildNativeDirectory();
        Path candidate = buildNative.resolve(archDir).resolve(fileName);
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalStateException(
                "Could not find " + candidate + ". Run './gradlew buildNativeHost' first"
                    + " (see scripts/build-native-host.sh), or set -D" + NATIVE_DIR_PROPERTY
                    + " to point at a directory containing " + fileName + ".");
        }
        return candidate;
    }

    /** Same arch-directory mapping as {@code GhosttyNativeLibrary}. */
    static String detectArchDirectoryName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "macos-x86_64";
            case "aarch64", "arm64" -> "macos-arm64";
            default -> throw new IllegalStateException(
                "Unsupported CPU architecture '" + arch + "'. This project supports only "
                    + "macOS x86_64 and macOS arm64 for v0.1.");
        };
    }

    private static Path findBuildNativeDirectory() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("build").resolve("native");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path settingsFile = dir.resolve("settings.gradle.kts");
            if (Files.isRegularFile(settingsFile)) {
                return dir.resolve("build").resolve("native");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "Could not locate the project root (no settings.gradle.kts found above "
                + Path.of("").toAbsolutePath() + "). Set -D" + NATIVE_DIR_PROPERTY
                + " explicitly instead.");
    }
}
