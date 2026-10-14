package app.drydock.webview;

import app.drydock.terminal.NativeLibraryLocator;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

/**
 * Locates and loads the native {@code libdrydockwebviewhost} shared library
 * (the WKWebView embedding shim, native-host/DrydockWebviewHost.h) for the
 * current machine's CPU architecture. Mirrors {@code
 * app.drydock.terminal.host.DrydockTerminalHostLibrary}: a thin facade over
 * {@link NativeLibraryLocator}, kept as a separate class because this shim is
 * built from its own native source ({@code native-host/DrydockWebviewHost.m},
 * linking the macOS system WebKit framework -- nothing to bundle or install)
 * with no dependency on libghostty or the terminal host shim.
 *
 * <p>Part of the narrow native boundary, together with {@code
 * app.drydock.terminal.host} and {@code app.drydock.terminal.ghostty}: these
 * are the packages allowed to hold FFM pointers.</p>
 */
public final class WebviewHostLibrary {

    /**
     * Mirrors {@code DrydockTerminalHostLibrary#NATIVE_DIR_PROPERTY}: a
     * <b>root</b> directory containing one {@code macos-x86_64}/{@code
     * macos-arm64} subdirectory per architecture. Arch selection always goes
     * through the shared {@link NativeLibraryLocator}; this property only
     * relocates the root it is resolved under (used by the jlink runtime
     * image's bundled {@code <image>/lib} directory).
     */
    public static final String NATIVE_DIR_PROPERTY = "app.drydock.webviewhost.nativeDir";

    private static volatile SymbolLookup lookup;

    private WebviewHostLibrary() {
    }

    /**
     * Returns a {@link SymbolLookup} bound to the libdrydockwebviewhost shared
     * library for this process's CPU architecture, loading it on first use
     * (never unloaded; loaded into the global {@link Arena#global()} arena,
     * same process-wide-singleton reasoning as {@code DrydockTerminalHostLibrary}).
     */
    public static SymbolLookup lookup() {
        SymbolLookup result = lookup;
        if (result == null) {
            synchronized (WebviewHostLibrary.class) {
                result = lookup;
                if (result == null) {
                    result = SymbolLookup.libraryLookup(resolveLibraryPath(), Arena.global());
                    lookup = result;
                }
            }
        }
        return result;
    }

    /** Finds {@code libdrydockwebviewhost.dylib} for the current architecture (see {@link NativeLibraryLocator}). */
    static Path resolveLibraryPath() {
        return NativeLibraryLocator.resolveLibraryPath(
            NATIVE_DIR_PROPERTY, "libdrydockwebviewhost.dylib",
            "./gradlew buildWebviewHost", "scripts/build-webview-host.sh");
    }
}
