package app.drydock.terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Locates a bundled native shared library for the current machine's CPU
 * architecture -- the single implementation behind the two thin facades
 * {@code app.drydock.terminal.ghostty.GhosttyNativeLibrary} and
 * {@code app.drydock.terminal.host.DrydockTerminalHostLibrary}, which previously
 * duplicated this resolution logic near-verbatim.
 *
 * <p>Internal to the narrow native boundary (plan section 2.4/4.2): it is
 * {@code public} only because Java has no sub-package visibility and both
 * facades live in child packages of {@code app.drydock.terminal}. It exposes no
 * FFM types -- it only resolves {@link Path}s; loading (and the
 * process-wide {@code SymbolLookup} singletons) stays in the facades. No
 * code outside the terminal native-boundary packages should use it.</p>
 *
 * <p>Resolution order (both roots are joined with the arch-specific
 * subdirectory from {@link #detectArchDirectoryName()} -- the property
 * override never skips arch detection, it only relocates where the arch
 * subdirectories are looked for):</p>
 * <ol>
 *   <li>{@code -D<nativeDirProperty>=<root>}, i.e.
 *       {@code <root>/<arch-dir>/<fileName>} -- used by the jlink runtime
 *       image, which bundles both architectures under its own {@code lib/}
 *       directory;</li>
 *   <li>{@code <repo-root>/build/native/<arch-dir>/<fileName>}, located by
 *       walking up from the working directory to find a
 *       {@code build/native} directory -- where the {@code
 *       buildGhosttyNative}/{@code buildNativeHost} Gradle tasks put the
 *       libraries during development.</li>
 * </ol>
 */
public final class NativeLibraryLocator {

    private NativeLibraryLocator() {
    }

    /**
     * Finds {@code fileName} for the current architecture.
     *
     * @param nativeDirProperty the system property naming an override
     *                          <b>root</b> directory (one
     *                          {@code macos-x86_64}/{@code macos-arm64}
     *                          subdirectory per architecture)
     * @param fileName          the dylib file name, e.g. {@code libghostty.dylib}
     * @param buildTaskHint     the Gradle invocation to suggest when the
     *                          library is missing, e.g.
     *                          {@code "./gradlew buildGhosttyNative"}
     * @param buildScriptHint   the script to point at in that message, e.g.
     *                          {@code "scripts/build-ghostty.sh"}
     */
    public static Path resolveLibraryPath(String nativeDirProperty, String fileName,
                                          String buildTaskHint, String buildScriptHint) {
        String archDir = detectArchDirectoryName();

        String override = System.getProperty(nativeDirProperty);
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override).resolve(archDir).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            throw new IllegalStateException(
                "System property " + nativeDirProperty + "='" + override
                    + "' does not contain " + archDir + "/" + fileName);
        }

        Path buildNative = findBuildNativeDirectory(nativeDirProperty);
        Path candidate = buildNative.resolve(archDir).resolve(fileName);
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalStateException(
                "Could not find " + candidate + ". Run '" + buildTaskHint + "' first"
                    + " (see " + buildScriptHint + "), or set -D" + nativeDirProperty
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
    public static String detectArchDirectoryName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "macos-x86_64";
            case "aarch64", "arm64" -> "macos-arm64";
            default -> throw new IllegalStateException(
                "Unsupported CPU architecture '" + arch + "'. This project supports only "
                    + "macOS x86_64 and macOS arm64 for v0.1 (see docs/implementation-plan.md "
                    + "section 3, and the approved dual-architecture deviation in README.md).");
        };
    }

    private static Path findBuildNativeDirectory(String nativeDirProperty) {
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
                // so the caller's "does not contain <fileName>" error
                // message points somewhere sensible.
                return dir.resolve("build").resolve("native");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "Could not locate the project root (no settings.gradle.kts found above "
                + Path.of("").toAbsolutePath() + "). Set -D" + nativeDirProperty
                + " explicitly instead.");
    }
}
