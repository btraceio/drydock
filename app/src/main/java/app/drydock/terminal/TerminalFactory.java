package app.drydock.terminal;

import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.ghostty.GhosttyApp;
import app.drydock.terminal.ghostty.GhosttyNativeLibrary;
import app.drydock.terminal.host.DrydockTerminalHost;

import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The single construction seam between the app and the terminal implementation.
 * Everything outside the {@code terminal.ghostty} / {@code terminal.host}
 * packages obtains runtimes and host views here and otherwise depends only on
 * {@code app.drydock.terminal.api}. Phase B replaces the direct impl references
 * below with a provider lookup when the impl moves to its own module.
 */
public final class TerminalFactory {

    private TerminalFactory() {
    }

    /** Calls {@code ghostty_init} once per process (idempotent). Call before {@link #createRuntime}. */
    public static void ensureProcessInitialized() {
        GhosttyApp.ensureProcessInitialized(GhosttyNativeLibrary.lookup());
    }

    /** Creates a runtime whose wakeup callback fires {@code onWakeup} on the FX thread (coalesced). */
    public static TerminalRuntime createRuntime(Runnable onWakeup, Optional<Path> configFile) {
        SymbolLookup lookup = GhosttyNativeLibrary.lookup();
        return GhosttyApp.create(lookup, onWakeup, configFile);
    }

    /** Creates a host view attached to the current (most recently shown) window. */
    public static TerminalHostView createHostForCurrentWindow() {
        return DrydockTerminalHost.createForCurrentWindow();
    }
}
