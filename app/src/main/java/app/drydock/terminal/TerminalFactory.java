package app.drydock.terminal;

import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.ghostty.GhosttyApp;
import app.drydock.terminal.ghostty.GhosttyNativeLibrary;
import app.drydock.terminal.host.DrydockTerminalHost;
import app.drydock.terminal.jediterm.JediTermFxHostView;
import app.drydock.terminal.jediterm.JediTermFxRuntime;

import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The single construction seam between the app and the terminal implementation.
 * Everything outside the terminal impl packages obtains runtimes and host views
 * here and otherwise depends only on {@link app.drydock.terminal.api}.
 *
 * <p><b>Backend selection.</b> Two backends sit behind this seam:</p>
 * <ul>
 *   <li><b>native</b> (default on macOS) -- the libghostty Metal surface + AppKit
 *       host shim ({@link GhosttyApp}, {@link DrydockTerminalHost}). The only
 *       path that can render a real Ghostty terminal surface, and the only one
 *       that can run on macOS (Metal + AppKit).</li>
 *   <li><b>jediterm</b> (default on Windows) -- the pure-JavaFX JediTermFX widget
 *       over a pty4j PTY ({@link JediTermFxRuntime}, {@link JediTermFxHostView}).
 *       The Windows path: Metal/AppKit do not exist there, and only
 *       {@code libghostty-vt} (no rendering) is Windows-supported upstream, so
 *       the full native surface cannot run. See docs/windows-terminal-spike.md.</li>
 * </ul>
 *
 * <p>The active backend is chosen once at class-init from the
 * {@code app.drydock.terminal.backend} system property ({@code native} or
 * {@code jediterm}), defaulting to the host platform's natural choice. The
 * override exists so the JediTermFX backend can be exercised on a macOS dev
 * machine and on the Windows CI spike without changing platform: set
 * {@code -Dapp.drydock.terminal.backend=jediterm}. Both backends implement the
 * same {@code terminal.api} interfaces, so {@code OpenSessionTab},
 * {@code TerminalBridge}, {@code SessionManager}, and {@code MainWorkspace}
 * are backend-agnostic -- they consume the trio through this seam and never
 * branch on it themselves.</p>
 */
public final class TerminalFactory {

    private static final String BACKEND = System.getProperty(
            "app.drydock.terminal.backend", defaultBackend());

    private static final boolean USE_JEDITERM = "jediterm".equals(BACKEND);

    private TerminalFactory() {
    }

    private static String defaultBackend() {
        return isWindows() ? "jediterm" : "native";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Calls {@code ghostty_init} once per process (idempotent) on the native
     * backend; a no-op on JediTermFX, which has no process-wide native state to
     * initialize.
     */
    public static void ensureProcessInitialized() {
        if (USE_JEDITERM) {
            return;
        }
        GhosttyApp.ensureProcessInitialized(GhosttyNativeLibrary.lookup());
    }

    /** Creates a runtime whose wakeup callback fires {@code onWakeup} on the FX thread (coalesced). */
    public static TerminalRuntime createRuntime(Runnable onWakeup, Optional<Path> configFile) {
        if (USE_JEDITERM) {
            return new JediTermFxRuntime(onWakeup, configFile);
        }
        SymbolLookup lookup = GhosttyNativeLibrary.lookup();
        return GhosttyApp.create(lookup, onWakeup, configFile);
    }

    /**
     * Creates a host view for the current window. The native backend attaches
     * an AppKit overlay to the current window; the JediTermFX backend is a
     * pure-JavaFX node carrier (its view is the widget's pane, set later in
     * {@code openSurface}).
     */
    public static TerminalHostView createHostForCurrentWindow() {
        if (USE_JEDITERM) {
            return new JediTermFxHostView();
        }
        return DrydockTerminalHost.createForCurrentWindow();
    }
}