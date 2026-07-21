package app.drydock.ui;

import app.drydock.domain.ManagedSessionId;
import app.drydock.terminal.api.Shortcut;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSurface;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.layout.Region;

import java.lang.System.Logger;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The native-terminal side of one session tab (extracted from {@code
 * OpenSessionTab} -- see docs/plans/workspace-split-design.md): owns the
 * tab's {@link TerminalRuntime} + {@link TerminalHostView} pair and its
 * attached {@link TerminalSurface}, and everything that talks to them --
 * key/mouse/scroll forwarding, geometry sync, focus, visibility, theming,
 * prompt typing, tick/draw, and disposal. {@code OpenSessionTab} keeps the
 * JavaFX chrome and delegates here.
 *
 * <p>The terminal does not render into the JavaFX scene graph: the host
 * view is a real native overlay on the current window's content view,
 * positioned in that window's own pixel coordinate space via {@link
 * TerminalHostView#setFrame}. The {@code anchor} node is an otherwise-empty
 * pane used purely as a JavaFX layout marker: its on-screen bounds (via
 * {@link javafx.scene.Node#localToScene}) tell this class where to move the
 * native view so it visually tracks the tab's content area as the window
 * resizes, the sidebar divider moves, etc.</p>
 *
 * <p>Key classification (app-shortcut interception, special-key vs
 * typed-character split, unshifted-codepoint policy) lives behind {@link
 * TerminalSurface#dispatchKeyEvent}; this class only routes raw events, so
 * it stays a thin layer over the (native-backed, and therefore not
 * unit-constructible) runtime/host/surface trio.</p>
 */
final class TerminalBridge {

    private static final Logger LOG = System.getLogger(TerminalBridge.class.getName());

    /** Diagnostic: -Dapp.drydock.diag.logKeys=true logs every raw AppKit key event reaching this tab. */
    private static final boolean LOG_KEYS = Boolean.getBoolean("app.drydock.diag.logKeys");

    private final TerminalRuntime app;
    private final TerminalHostView host;
    /** The JavaFX layout anchor whose scene bounds position the native view (owned by the tab's chrome). */
    private final Region anchor;
    /** The window's output scale ({@code Stage#getOutputScaleX}; 2.0 on Retina). */
    private final DoubleSupplier outputScale;
    /** The hosting tab's session id, read live (it may be adopted after launch); log messages only. */
    private final Supplier<ManagedSessionId> sessionId;
    /** Performs an intercepted app shortcut (wired to the tab's sub-tab/cycling/sidebar handlers). */
    private final Consumer<Shortcut> shortcutHandler;

    private TerminalSurface surface;
    private boolean disposed;
    private boolean surfaceClosing;
    /** Whether MainWorkspace wants this tab's terminal shown (selected tab, no modal). */
    private boolean workspaceWantsVisible;
    /** Whether the tab's Terminal sub-tab is the active one (the Explorer/Review replace the terminal region). */
    private boolean terminalSubTabActive = true;

    TerminalBridge(TerminalRuntime app, TerminalHostView host, Region anchor, DoubleSupplier outputScale,
                   Supplier<ManagedSessionId> sessionId, Consumer<Shortcut> shortcutHandler) {
        this.app = app;
        this.host = host;
        this.anchor = anchor;
        this.outputScale = outputScale;
        this.sessionId = sessionId;
        this.shortcutHandler = shortcutHandler;
    }

    TerminalRuntime app() {
        return app;
    }

    TerminalHostView host() {
        return host;
    }

    /**
     * Adopts the now-running {@link TerminalSurface}. Deliberately does NOT
     * draw yet: the native host view is still hidden at this point (it only
     * becomes visible via a subsequent {@link #setWorkspaceVisible(boolean)}
     * call from {@code MainWorkspace.attachOpenedSession}), and drawing into
     * a layer-backed {@code NSView} before it is ever shown, then only
     * toggling visibility afterward, is a known way for the first frame to
     * never actually composite. {@link #setWorkspaceVisible(boolean)}
     * performs the real first geometry/draw, in the correct order (become
     * visible, then draw).
     *
     * <p>Split from {@link #wireInputListeners()} so {@code
     * OpenSessionTab.attachSurface} can remove its "Starting session..."
     * label between the two, preserving the original statement order (the
     * label removal can fire a synchronous geometry update, which must see
     * the surface but need not see the input listeners).</p>
     */
    void adoptSurface(TerminalSurface surface) {
        this.surface = surface;
    }

    /** Starts forwarding keyboard/mouse input to the adopted surface (register-once per host slot). */
    void wireInputListeners() {
        host.setKeyEventListener(this::onKeyEvent);
        host.setScrollEventListener(this::onScrollEvent);
        host.setMousePosEventListener(this::onMousePosEvent);
        host.setMouseButtonEventListener(this::onMouseButtonEvent);
    }

    /** Forwards the mouse position (view points, top-left origin) to the surface. */
    private void onMousePosEvent(double x, double y, int modifierFlags) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            surface.sendMousePos(x, y, modifierFlags);
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    /**
     * Forwards a mouse button press/release to the surface (text selection,
     * click reporting). A press also refocuses the terminal -- clicking
     * into a terminal is the universal "type here now" gesture.
     */
    private void onMouseButtonEvent(int state, int button, int modifierFlags) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        if (state == 1) {
            focus();
        }
        try {
            surface.sendMouseButton(state, button, modifierFlags);
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    /** Diagnostic-only: feeds a synthetic scroll through the same path a real scrollWheel takes. */
    void diagScroll(double deltaY) {
        // Real scrollWheel events report the position first (see the host
        // shim); mirror that with the terminal-region center so
        // mouse-reporting TUIs hit-test the scroll as inside the content.
        onMousePosEvent(anchor.getWidth() / 2, anchor.getHeight() / 2, 0);
        onScrollEvent(0, deltaY, 1);
    }

    /** Forwards a raw scrollWheel event (already in ghostty's units/mods) to the surface. */
    private void onScrollEvent(double deltaX, double deltaY, int scrollMods) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            surface.sendMouseScroll(deltaX, deltaY, scrollMods);
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    /**
     * Diagnostic-only: feeds a synthetic key event through the exact same
     * {@link #onKeyEvent} translation path a real AppKit key event takes
     * (used by the {@code app.drydock.diag.*} harness, which cannot inject real
     * NSEvents without an Accessibility permission grant).
     */
    void diagPressKey(int keyCode, String characters, String unshiftedCharacters) {
        onKeyEvent(keyCode, 0, true, characters, unshiftedCharacters);
        onKeyEvent(keyCode, 0, false, characters, unshiftedCharacters);
    }

    /**
     * Routes a raw AppKit key event to the surface, which classifies and
     * performs it ({@link TerminalSurface#dispatchKeyEvent}); an intercepted
     * app shortcut comes back as a neutral {@link Shortcut} and runs here.
     * App shortcuts are intercepted at this layer because while the terminal
     * is focused its native NSEvent monitor sees every key BEFORE JavaFX's
     * scene filter -- they must switch views instead of being typed into
     * claude.
     */
    private void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                             String unshiftedCharacters) {
        if (LOG_KEYS) {
            System.out.println("[diag] key event: keyCode=" + keyCode + " mods=0x" + Integer.toHexString(modifierFlags)
                    + " down=" + keyDown + " chars=" + toCodepoints(characters)
                    + " unshifted=" + toCodepoints(unshiftedCharacters));
        }
        if (disposed || surface == null) {
            return;
        }
        surface.dispatchKeyEvent(keyCode, modifierFlags, keyDown, characters, unshiftedCharacters)
               .ifPresent(shortcutHandler);
    }

    private static String toCodepoints(String s) {
        if (s == null || s.isEmpty()) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> sb.append("U+").append(Integer.toHexString(cp)).append(' '));
        return sb.toString().trim();
    }

    /** Pumps the runtime and draws; bound to this tab's own runtime's wakeup callback. */
    void tickAndDraw() {
        if (disposed || surfaceClosing) {
            return;
        }
        try {
            app.tick();
            if (surface != null) {
                surface.draw();
            }
        } catch (IllegalStateException e) {
            // Surface was closed (SessionManager.closeSession's
            // closeGracefully) in the gap between this tick firing and
            // MainWorkspace calling markSurfaceClosing() -- benign, not a
            // bug in itself (the guard above closes this window going
            // forward, this catch handles the unavoidable race remnant).
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "tick/draw failed for session " + sessionId.get(), e);
        }
    }

    /**
     * Records whether MainWorkspace wants this tab's native view shown
     * (selected tab, no modal open) and applies the combined visibility.
     * The view is actually shown only while the Terminal sub-tab is active
     * -- the Explorer replaces the terminal region, so the native overlay
     * must stay hidden even for the selected tab (see {@link
     * #setTerminalSubTabActive}).
     */
    void setWorkspaceVisible(boolean visible) {
        workspaceWantsVisible = visible;
        applyVisibility();
    }

    /** Records whether the tab's Terminal sub-tab is active and applies the combined visibility. */
    void setTerminalSubTabActive(boolean active) {
        terminalSubTabActive = active;
        applyVisibility();
    }

    /**
     * Shows/hides the native view from the AND of every visibility input.
     * When becoming visible, unhides the view <em>before</em> computing
     * geometry/drawing (see {@link #adoptSurface}'s Javadoc for why that
     * order matters), then focuses it.
     */
    void applyVisibility() {
        if (disposed || surfaceClosing) {
            return;
        }
        boolean visible = workspaceWantsVisible && terminalSubTabActive;
        host.setVisible(visible);
        if (visible) {
            updateGeometry();
            focus();
        }
    }

    /**
     * Types {@code instruction} into the live claude process as real
     * keystrokes, then submits it with Return ({@link
     * TerminalSurface#submitLine} -- the per-codepoint keyboard codepath,
     * not paste semantics, which corrupt input once claude enables
     * bracketed paste). The instruction must be a single line: an embedded
     * newline would submit early.
     */
    void sendPrompt(String instruction) {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            surface.submitLine(instruction);
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    void focus() {
        if (disposed || surfaceClosing || surface == null) {
            return;
        }
        try {
            host.setFocused(true);
            app.setFocus(true);
            surface.setFocus(true);
        } catch (IllegalStateException e) {
            // See tickAndDraw's identical catch: surface closed out from
            // under this tab in the gap before markSurfaceClosing() runs.
        }
    }

    /** Releases the terminal's AppKit first-responder status so JavaFX text inputs receive keys. */
    void releaseFocus() {
        if (disposed || surfaceClosing) {
            return;
        }
        try {
            host.setFocused(false);
            if (surface != null) {
                surface.setFocus(false);
            }
        } catch (IllegalStateException e) {
            // Surface closed in the teardown gap; see tickAndDraw's identical catch.
        }
    }

    /**
     * Whether this tab's child process has exited while the surface is
     * still open (polled by {@code MainWorkspace}'s exit watcher). Returns
     * {@code false} during/after teardown -- those paths already record the
     * exit through {@code SessionManager.closeSession}.
     */
    boolean isProcessExited() {
        if (disposed || surfaceClosing || surface == null) {
            return false;
        }
        try {
            return surface.processExited();
        } catch (IllegalStateException e) {
            return false; // surface closed out from under us; closeSession's path owns the status update.
        }
    }

    /**
     * Re-themes this tab's live terminal (app theme toggle): loads the new
     * ghostty config into the app and pushes it to the running surface.
     * Safe to call before the surface is attached -- a surface created
     * later inherits the app's (already updated) config.
     */
    void applyTerminalTheme(Path configFile) {
        if (disposed || surfaceClosing) {
            return;
        }
        try {
            app.updateConfig(configFile);
            if (surface != null) {
                surface.applyConfig(app);
                surface.draw();
            }
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Could not re-theme terminal for session " + sessionId.get(), e);
        }
    }

    /** Positions the native view over the anchor's scene bounds and sizes/draws the surface. */
    void updateGeometry() {
        if (disposed || surfaceClosing || surface == null || anchor.getScene() == null) {
            return;
        }
        Bounds sceneBounds = anchor.localToScene(anchor.getBoundsInLocal());
        if (sceneBounds.getWidth() <= 0 || sceneBounds.getHeight() <= 0) {
            return;
        }
        try {
            double scale = outputScale.getAsDouble();
            host.setFrame(sceneBounds.getMinX(), sceneBounds.getMinY(), sceneBounds.getWidth(), sceneBounds.getHeight());
            surface.setSize((int) Math.round(sceneBounds.getWidth() * scale), (int) Math.round(sceneBounds.getHeight() * scale));
            surface.draw();
        } catch (IllegalStateException e) {
            // See tickAndDraw's identical catch: surface closed out from
            // under this tab in the gap before markSurfaceClosing() runs.
            // Without this catch, an uncaught IllegalStateException thrown
            // from here (reachable via the anchor's
            // localToSceneTransformProperty listener, itself fired
            // synchronously while MainWorkspace.removeTab() removes this
            // tab's node from the TabPane) propagates out of JavaFX's
            // property-invalidation machinery mid-removal, on the JavaFX
            // Application Thread, with no caller able to catch it.
        }
    }

    /**
     * Marks this tab's surface as being torn down. Must be called (by {@code
     * MainWorkspace.removeTab}, via {@code OpenSessionTab.markSurfaceClosing})
     * <em>before</em> removing the tab's node from the {@code TabPane} --
     * doing so fires JavaFX property-invalidation listeners (e.g. {@code
     * localToSceneTransformProperty}) synchronously, which would otherwise
     * call back into {@link #updateGeometry()} against a surface that {@code
     * SessionManager}'s {@code closeGracefully} has (in the closing case)
     * already closed by this point. Distinct from {@link #disposed} (which
     * {@link #disposeNativeResources()} uses for its own idempotency) so
     * that flag isn't set before this tab's runtime/host are actually
     * closed.
     */
    void markSurfaceClosing() {
        surfaceClosing = true;
    }

    /**
     * Frees this tab's native resources. Must be called only after the
     * session's {@link TerminalSurface} is already confirmed closed (e.g.
     * via {@code SessionManager#closeSession}, which uses {@code
     * TerminalSurface#closeGracefully} internally) -- never call this to
     * bypass that graceful shutdown.
     */
    void disposeNativeResources() {
        if (disposed) {
            return;
        }
        disposed = true;
        assert Platform.isFxApplicationThread() : "native resource teardown must run on the FX Application Thread";
        try {
            host.close();
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Failed to close terminal host for session " + sessionId.get(), e);
        }
        try {
            app.close();
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Failed to close terminal runtime for session " + sessionId.get(), e);
        }
    }
}
