package app.drydock.terminal.ghostty;

import app.drydock.terminal.NativeLibraryLocator;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

/**
 * Locates and loads the native {@code libghostty} shared library for the
 * current machine's CPU architecture. A thin facade over
 * {@link NativeLibraryLocator} (the shared path-resolution logic).
 *
 * <p><b>This class, and the rest of {@code app.drydock.terminal.ghostty}, is the
 * narrow native boundary mandated by the implementation plan (section 2.4
 * "Narrow native boundary" / section 4.2): all {@code MemorySegment},
 * {@code Linker}, {@code MethodHandle}, and native-pointer usage for
 * libghostty must stay inside this package. No UI, project-management, Git,
 * or persistence code may touch those types directly.</b></p>
 *
 * <p>This project deliberately targets <b>both</b> macOS x86_64 and macOS
 * arm64 for v0.1 (an approved deviation from the plan, which targeted Apple
 * Silicon only); {@link NativeLibraryLocator#detectArchDirectoryName()} is
 * the single place that inspects {@code os.arch} to decide which
 * architecture's prebuilt library to load.</p>
 */
public final class GhosttyNativeLibrary {

    /**
     * System property that, if set, is used as the <b>root</b> native
     * library directory: the directory that itself contains one
     * {@code macos-x86_64}/{@code macos-arm64} subdirectory per
     * architecture (the same layout {@code scripts/build-ghostty.sh}
     * produces under {@code build/native/}), each holding
     * {@code libghostty.dylib} for that architecture. Overrides the default
     * {@code build/native} discovery, but arch detection still happens
     * either way -- this property only relocates the root.
     *
     * <p>The jlink runtime image (Gate 0F / Task 8, see {@code
     * docs/implementation-plan.md} section 23.1) sets this to its bundled
     * {@code <image>/lib} directory, which ships both architectures'
     * {@code libghostty.dylib} side by side precisely so this single
     * property, plus the shared arch detection, is enough to pick the right
     * one at launch on either machine.</p>
     */
    public static final String NATIVE_DIR_PROPERTY = "app.drydock.ghostty.nativeDir";

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
                    result = SymbolLookup.libraryLookup(resolveLibraryPath(), Arena.global());
                    lookup = result;
                }
            }
        }
        return result;
    }

    /** Finds {@code libghostty.dylib} for the current architecture (see {@link NativeLibraryLocator}). */
    static Path resolveLibraryPath() {
        return NativeLibraryLocator.resolveLibraryPath(
            NATIVE_DIR_PROPERTY, "libghostty.dylib",
            "./gradlew buildGhosttyNative", "scripts/build-ghostty.sh");
    }
}
