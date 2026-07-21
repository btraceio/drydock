package app.drydock.terminal.host;

import app.drydock.terminal.NativeLibraryLocator;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

/**
 * Locates and loads the native {@code libdrydockterminalhost} shared library
 * (the AppKit host shim, plan section 8) for the current machine's CPU
 * architecture. A thin facade over {@link NativeLibraryLocator} (the shared
 * path-resolution logic also behind
 * {@code app.drydock.terminal.ghostty.GhosttyNativeLibrary}); kept as a
 * separate class because this shim is built from a completely separate
 * native source tree ({@code native-host/}, not {@code third_party/ghostty})
 * with no dependency on libghostty whatsoever -- see
 * {@code native-host/DrydockTerminalHost.h}.
 *
 * <p>Part of the narrow native boundary (plan section 2.4/4.2): together
 * with {@code app.drydock.terminal.ghostty} and {@code app.drydock.terminal.host},
 * this is the complete set of packages allowed to touch {@code
 * MemorySegment}/{@code Linker}/{@code MethodHandle}/native pointers for
 * terminal rendering.</p>
 */
public final class DrydockTerminalHostLibrary {

    /**
     * Mirrors {@code GhosttyNativeLibrary#NATIVE_DIR_PROPERTY}: a
     * <b>root</b> directory containing one {@code macos-x86_64}/{@code
     * macos-arm64} subdirectory per architecture. Arch selection always
     * goes through the shared {@link NativeLibraryLocator}; this property
     * only relocates the root it is resolved under (used by the jlink
     * runtime image's bundled {@code <image>/lib} directory).
     */
    public static final String NATIVE_DIR_PROPERTY = "app.drydock.terminalhost.nativeDir";

    private static volatile SymbolLookup lookup;

    private DrydockTerminalHostLibrary() {
    }

    /**
     * Returns a {@link SymbolLookup} bound to the libdrydockterminalhost shared
     * library for this process's CPU architecture, loading it on first use
     * (never unloaded; loaded into the global {@link Arena#global()} arena,
     * same process-wide-singleton reasoning as {@code GhosttyNativeLibrary}).
     */
    public static SymbolLookup lookup() {
        SymbolLookup result = lookup;
        if (result == null) {
            synchronized (DrydockTerminalHostLibrary.class) {
                result = lookup;
                if (result == null) {
                    result = SymbolLookup.libraryLookup(resolveLibraryPath(), Arena.global());
                    lookup = result;
                }
            }
        }
        return result;
    }

    /** Finds {@code libdrydockterminalhost.dylib} for the current architecture (see {@link NativeLibraryLocator}). */
    static Path resolveLibraryPath() {
        return NativeLibraryLocator.resolveLibraryPath(
            NATIVE_DIR_PROPERTY, "libdrydockterminalhost.dylib",
            "./gradlew buildNativeHost", "scripts/build-native-host.sh");
    }
}
