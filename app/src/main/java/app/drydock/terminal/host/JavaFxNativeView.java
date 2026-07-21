package app.drydock.terminal.host;

import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Obtains the native macOS {@code NSView*} pointer backing a JavaFX window,
 * for handing to {@link DrydockTerminalHost#create}.
 *
 * <h2>Why this class exists (plan rule 27.7: "do not assume JavaFX exposes
 * an NSView through a public API; verify it")</h2>
 *
 * <p><b>Verified empirically for this task (see docs/native-integration.md,
 * "Does JavaFX expose a native NSView handle?"):</b></p>
 * <ul>
 *   <li>{@code javafx.stage.Window}/{@code Stage} and every other class in
 *       the public {@code javafx.*} packages have <b>no</b> method that
 *       returns a native handle of any kind.</li>
 *   <li>The underlying native handle <i>does</i> exist, one layer down, in
 *       {@code com.sun.glass.ui.View#getNativeView()} (Glass, JavaFX's
 *       internal windowing-toolkit abstraction) -- but {@code
 *       com.sun.glass.ui} is a <b>qualified</b> export in {@code
 *       javafx.graphics}'s {@code module-info} (to {@code javafx.media},
 *       {@code javafx.swing}, {@code javafx.web} only; verified by
 *       inspecting the {@code javafx-graphics-26-mac.jar} module descriptor
 *       with {@code javap -verbose}). This application's module is not on
 *       that list, so the package is inaccessible without an explicit
 *       {@code --add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED}
 *       JVM argument.</li>
 * </ul>
 *
 * <p>Per plan rule 27.9 ("prefer a tiny explicit native host shim over
 * extensive reflection into JavaFX internals"), this class is kept to the
 * absolute minimum needed to obtain <i>one pointer</i> at window-creation
 * time; all further native work (attaching a child view, resizing, focus,
 * destruction) is done by the AppKit host shim
 * ({@code native-host/DrydockTerminalHost.h}) via {@link DrydockTerminalHost}, not
 * by more Glass calls. This is still an internal, unsupported JavaFX API:
 * a future JavaFX release could rename/remove {@code com.sun.glass.ui}
 * without notice, which would break window creation outright (there is no
 * graceful fallback implemented). See docs/native-integration.md for the
 * full risk writeup (plan rule 15).</p>
 *
 * <p>This class -- together with {@link DrydockTerminalHost}, {@link
 * DrydockTerminalHostBinding}, and {@link DrydockTerminalHostLibrary} -- is part of
 * the narrow native boundary (plan section 2.4/4.2). It is deliberately
 * package-private: only {@link DrydockTerminalHost} (this package's own public
 * entry point) may hold a {@link MemorySegment}; no code outside {@code
 * app.drydock.terminal.host}/{@code app.drydock.terminal.ghostty} may reference it,
 * per plan section 2.4.</p>
 */
final class JavaFxNativeView {

    private JavaFxNativeView() {
    }

    /**
     * Returns the {@code NSView*} of the application's main Glass window,
     * wrapped as a zero-length {@link MemorySegment} (callers must not
     * read/write through it directly -- it is an opaque native pointer,
     * handed straight to {@link DrydockTerminalHost#create}).
     *
     * <p>This must be called after the corresponding {@code javafx.stage.Stage}
     * has been shown ({@code Stage#show()}); before that, Glass has not yet
     * created a native window and {@link Window#getWindows()} is empty.</p>
     *
     * <p><b>Window selection:</b> the first OWNERLESS window in {@link
     * Window#getWindows()} (creation order). It must NOT be the most
     * recently created window: JavaFX creates a real Glass window for every
     * tooltip, context menu, and MenuButton popup, each OWNED by the window
     * that spawned it. Attaching the terminal to whichever popup happened
     * to be created last rendered it into a window that immediately
     * disappears -- the real-world "new session opens a tab but no
     * terminal" bug (popups are unavoidable in any mouse-driven flow, e.g.
     * the Add-repository menu or a hover tooltip). The main stage is the
     * only long-lived ownerless window in this single-main-window
     * application; a future multi-window application would need to
     * correlate a specific {@code Stage} to its Glass {@code Window}
     * (e.g. via {@code com.sun.javafx.stage.WindowHelper}, deliberately
     * avoided here -- see docs/native-integration.md).</p>
     */
    static MemorySegment currentWindowNsView() {
        List<Window> windows = Window.getWindows();
        if (windows.isEmpty()) {
            throw new IllegalStateException(
                "com.sun.glass.ui.Window.getWindows() returned no windows. "
                    + "Call this only after the Stage has been shown.");
        }
        Window window = windows.stream()
                .filter(w -> w.getOwner() == null)
                .findFirst()
                .orElse(windows.get(0));
        View view = window.getView();
        if (view == null) {
            throw new IllegalStateException(
                "The main Glass Window has no View yet (Stage not fully realized).");
        }
        long nativeViewPointer = view.getNativeView();
        if (nativeViewPointer == 0L) {
            throw new IllegalStateException("com.sun.glass.ui.View.getNativeView() returned 0.");
        }
        return MemorySegment.ofAddress(nativeViewPointer);
    }
}
