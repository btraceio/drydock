package app.cpm.terminal.ghostty;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates and loads the native {@code libghostty} shared library for the
 * current machine's CPU architecture.
 *
 * <p><b>This class, and the rest of {@code app.cpm.terminal.ghostty}, is the
 * narrow native boundary mandated by the implementation plan (section 2.4
 * "Narrow native boundary" / section 4.2): all {@code MemorySegment},
 * {@code Linker}, {@code MethodHandle}, and native-pointer usage for
 * libghostty must stay inside this package. No UI, project-management, Git,
 * or persistence code may touch those types directly.</b></p>
 *
 * <p>This project deliberately targets <b>both</b> macOS x86_64 and macOS
 * arm64 for v0.1 (an approved deviation from the plan, which targeted Apple
 * Silicon only). {@link #detectArchDirectoryName()} is the single place that
 * inspects {@code os.arch} to decide which architecture's prebuilt library
 * to load; nothing outside this package should ever branch on CPU
 * architecture for native-library purposes.</p>
 */
public final class GhosttyNativeLibrary {

    /**
     * System property that, if set, is used verbatim as the directory
     * containing {@code libghostty.dylib} (and {@code ghostty.h}, though
     * headers are not needed at runtime). Overrides all other discovery.
     *
     * <p>This exists because the project has not yet reached the packaging
     * milestone (plan section 2.5 / Gate 0F): today, libghostty only exists
     * under this Gradle project's {@code build/native/<arch>/} directories,
     * produced by {@code scripts/build-ghostty.sh}. Once the jlink runtime
     * image ships its own bundled native libraries, this property will
     * become the packaging step's way of pointing at the bundled copy
     * instead of a developer's local build output, and the classpath/
     * resource-based fallback below can be added if needed. No behavior
     * here should change for that future step except where the library is
     * found -- the loading and arch-selection logic itself stays put.</p>
     */
    public static final String NATIVE_DIR_PROPERTY = "app.cpm.ghostty.nativeDir";

    private static volatile SymbolLookup lookup;

    private GhosttyNativeLibrary() {
    }

    /**
     * Returns a {@link SymbolLookup} bound to the libghostty shared library
     * for this process's CPU architecture, loading it on first use.
     *
     * <p>The backing library is never unloaded (it is loaded into a global
     * {@link Arena#global()} arena): libghostty is a process-wide
     * singleton by design (see {@code docs/native-integration.md},
     * "Lifecycle and thread constraints" -- {@code ghostty_init} is called
     * exactly once per process).</p>
     */
    public static SymbolLookup lookup() {
        SymbolLookup result = lookup;
        if (result == null) {
            synchronized (GhosttyNativeLibrary.class) {
                result = lookup;
                if (result == null) {
                    result = load();
                    lookup = result;
                }
            }
        }
        return result;
    }

    private static SymbolLookup load() {
        Path libraryPath = resolveLibraryPath();
        return SymbolLookup.libraryLookup(libraryPath, Arena.global());
    }

    /**
     * Finds {@code libghostty.dylib} for the current architecture.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code -D}{@value #NATIVE_DIR_PROPERTY}{@code =<dir>} pointing
     *       directly at a directory containing {@code libghostty.dylib};</li>
     *   <li>{@code <repo-root>/build/native/<arch-dir>/libghostty.dylib},
     *       located by walking up from the working directory to find a
     *       {@code build/native} directory -- this is where
     *       {@code scripts/build-ghostty.sh} / the {@code buildGhosttyNative}
     *       Gradle task puts it during development.</li>
     * </ol>
     */
    static Path resolveLibraryPath() {
        String archDir = detectArchDirectoryName();
        String fileName = "libghostty.dylib";

        String override = System.getProperty(NATIVE_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            throw new IllegalStateException(
                "System property " + NATIVE_DIR_PROPERTY + "='" + override
                    + "' does not contain " + fileName);
        }

        Path buildNative = findBuildNativeDirectory();
        Path candidate = buildNative.resolve(archDir).resolve(fileName);
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalStateException(
                "Could not find " + candidate + ". Run './gradlew buildGhosttyNative' first"
                    + " (see scripts/build-ghostty.sh), or set -D" + NATIVE_DIR_PROPERTY
                    + " to point at a directory containing " + fileName + ".");
        }
        return candidate;
    }

    /**
     * Maps {@code os.arch} to this project's {@code build/native}
     * subdirectory naming (see {@code scripts/build-ghostty.sh}):
     * {@code macos-x86_64} or {@code macos-arm64}.
     *
     * <p>This is the one place in the whole codebase that is allowed to
     * branch on CPU architecture for native-library selection, per the
     * approved dual-architecture deviation (see {@code README.md}).</p>
     */
    static String detectArchDirectoryName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "macos-x86_64";
            case "aarch64", "arm64" -> "macos-arm64";
            default -> throw new IllegalStateException(
                "Unsupported CPU architecture '" + arch + "'. This project supports only "
                    + "macOS x86_64 and macOS arm64 for v0.1 (see docs/implementation-plan.md "
                    + "section 3, and the approved dual-architecture deviation in README.md).");
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
                // Reached the Gradle root without finding build/native yet;
                // it may simply not have been built there. Return it anyway
                // so the caller's "does not contain libghostty.dylib" error
                // message points somewhere sensible.
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
