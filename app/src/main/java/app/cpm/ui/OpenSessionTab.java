package app.cpm.ui;

import app.cpm.domain.ManagedSessionId;
import app.cpm.terminal.ghostty.GhosttyApp;
import app.cpm.terminal.ghostty.GhosttySurface;
import app.cpm.terminal.host.CpmTerminalHost;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.lang.System.Logger;
import java.util.Set;

/**
 * One open terminal tab (plan section 13): owns its own {@link GhosttyApp} +
 * {@link CpmTerminalHost} + {@link GhosttySurface} (per Gate 0C/0D/0E's
 * established one-surface-per-window/view pattern -- see those spikes'
 * {@code start()}/{@code onKeyEvent} methods, which this class's geometry
 * and key-forwarding logic is deliberately modeled on, without modifying
 * those spike files).
 *
 * <p>Ghostty does not render into the JavaFX scene graph: {@link
 * CpmTerminalHost} attaches a real AppKit {@code NSView} as a sibling
 * overlay on the current window's content view, positioned in that
 * window's own pixel coordinate space via {@link CpmTerminalHost#setFrame}.
 * This class's {@link #placeholder} is therefore an otherwise-empty {@link
 * StackPane} used purely as a JavaFX layout anchor: its on-screen bounds
 * (via {@link javafx.scene.Node#localToScene}) tell this class where to
 * move the native view so it visually tracks the tab's content area as the
 * window resizes, the sidebar divider moves, etc.</p>
 */
final class OpenSessionTab {

    private static final Logger LOG = System.getLogger(OpenSessionTab.class.getName());

    // Native macOS virtual keycodes -- see Gate0cSpike.SPECIAL_KEYS's
    // Javadoc for why these are raw platform keycodes, not GHOSTTY_KEY_*
    // ordinals (ghostty_input_key_s.keycode expects the former).
    private static final Set<Integer> SPECIAL_KEYS = Set.of(
            36,  // Return / Enter
            51,  // Delete (Backspace)
            48,  // Tab
            53,  // Escape
            123, // Left arrow
            124, // Right arrow
            125, // Down arrow
            126  // Up arrow
    );

    private static final int NS_SHIFT = 1 << 17;
    private static final int NS_CONTROL = 1 << 18;
    private static final int NS_OPTION = 1 << 19;
    private static final int NS_COMMAND = 1 << 20;

    private static final int GHOSTTY_MODS_SHIFT = 1;
    private static final int GHOSTTY_MODS_CTRL = 1 << 1;
    private static final int GHOSTTY_MODS_ALT = 1 << 2;
    private static final int GHOSTTY_MODS_SUPER = 1 << 3;

    final ManagedSessionId sessionId;
    final Tab tab;
    private final Stage stage;
    private final GhosttyApp app;
    private final CpmTerminalHost host;
    private final StackPane placeholder = new StackPane();
    private final Label statusLabel = new Label("Starting session...");

    private GhosttySurface surface;
    private boolean disposed;
    private boolean surfaceClosing;

    OpenSessionTab(ManagedSessionId sessionId, String displayName, Stage stage, GhosttyApp app, CpmTerminalHost host) {
        this.sessionId = sessionId;
        this.stage = stage;
        this.app = app;
        this.host = host;

        placeholder.getChildren().add(statusLabel);
        placeholder.boundsInLocalProperty().addListener((obs, oldV, newV) -> updateGeometry());
        placeholder.localToSceneTransformProperty().addListener((obs, oldV, newV) -> updateGeometry());

        this.tab = new Tab(displayName, placeholder);
    }

    void setDisplayName(String displayName) {
        tab.setText(displayName);
    }

    /**
     * Attaches the now-running {@link GhosttySurface} and starts forwarding
     * keyboard input to it. Deliberately does NOT draw yet: the native host
     * view is still hidden at this point (it only becomes visible via a
     * subsequent {@link #setVisible(boolean)} call from {@code
     * MainWorkspace.attachOpenedSession}), and drawing into a layer-backed
     * {@code NSView} before it is ever shown, then only toggling visibility
     * afterward, is a known way for the first frame to never actually
     * composite. {@link #setVisible(boolean)} performs the real first
     * geometry/draw, in the correct order (become visible, then draw).
     */
    void attachSurface(GhosttySurface surface) {
        this.surface = surface;
        placeholder.getChildren().remove(statusLabel);
        host.setKeyEventListener(this::onKeyEvent);
    }

    GhosttyApp app() {
        return app;
    }

    CpmTerminalHost host() {
        return host;
    }

    /**
     * Marks this tab's surface as being torn down. Must be called (by {@code
     * MainWorkspace.removeTab}) <em>before</em> removing this tab's node
     * from the {@code TabPane} -- doing so fires JavaFX property-
     * invalidation listeners (e.g. {@code localToSceneTransformProperty})
     * synchronously, which would otherwise call back into {@link
     * #updateGeometry()} against a surface that {@code SessionManager}'s
     * {@code closeGracefully} has *already* closed by this point (session
     * close and tab removal are two separate async hops -- see {@code
     * MainWorkspace.closeSession}). Distinct from {@link #disposed} (which
     * {@link #disposeNativeResources()} uses for its own idempotency) so
     * that flag isn't set before this tab's {@code GhosttyApp}/{@code
     * CpmTerminalHost} are actually closed.
     */
    void markSurfaceClosing() {
        surfaceClosing = true;
    }

    /** Calls {@code ghostty_app_tick} + draw; bound to this tab's own {@code GhosttyApp}'s wakeup callback. */
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
            LOG.log(Logger.Level.WARNING, "tick/draw failed for session " + sessionId, e);
        }
    }

    /**
     * Shows or hides this tab's native view. When becoming visible, unhides
     * the view <em>before</em> computing geometry/drawing (see {@link
     * #attachSurface}'s Javadoc for why that order matters), then focuses it.
     */
    void setVisible(boolean visible) {
        if (disposed || surfaceClosing) {
            return;
        }
        host.setVisible(visible);
        if (visible) {
            updateGeometry();
            focus();
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

    private void updateGeometry() {
        if (disposed || surfaceClosing || surface == null || placeholder.getScene() == null) {
            return;
        }
        Bounds sceneBounds = placeholder.localToScene(placeholder.getBoundsInLocal());
        if (sceneBounds.getWidth() <= 0 || sceneBounds.getHeight() <= 0) {
            return;
        }
        try {
            double scale = stage.getOutputScaleX();
            host.setFrame(sceneBounds.getMinX(), sceneBounds.getMinY(), sceneBounds.getWidth(), sceneBounds.getHeight());
            surface.setSize((int) Math.round(sceneBounds.getWidth() * scale), (int) Math.round(sceneBounds.getHeight() * scale));
            surface.draw();
        } catch (IllegalStateException e) {
            // See tickAndDraw's identical catch: surface closed out from
            // under this tab in the gap before markSurfaceClosing() runs.
            // Without this catch, an uncaught IllegalStateException thrown
            // from here (reachable via placeholder's
            // localToSceneTransformProperty listener, itself fired
            // synchronously while MainWorkspace.removeTab() removes this
            // tab's node from the TabPane) propagates out of JavaFX's
            // property-invalidation machinery mid-removal, on the JavaFX
            // Application Thread, with no caller able to catch it.
        }
    }

    /**
     * Translates a raw AppKit key event into ghostty calls, matching the
     * corrected approach documented on {@link GhosttySurface#sendCharKey}
     * (per-character typing goes through the real keyboard codepath, not
     * {@link GhosttySurface#sendText}, which is paste semantics and
     * corrupts input once a foreground program enables bracketed paste) --
     * see that method's Javadoc and docs/claude-integration.md for the
     * history of that finding.
     */
    private void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                             String unshiftedCharacters) {
        if (disposed || surface == null) {
            return;
        }
        int mods = translateModifiers(modifierFlags);
        boolean isShortcut = (mods & (GHOSTTY_MODS_CTRL | GHOSTTY_MODS_SUPER)) != 0;
        if (SPECIAL_KEYS.contains(keyCode) || isShortcut) {
            int unshiftedCodepoint = (!keyDown || unshiftedCharacters.isEmpty()) ? 0 : unshiftedCharacters.codePointAt(0);
            surface.sendKey(keyCode, mods, keyDown, unshiftedCodepoint);
            return;
        }
        if (keyDown && !characters.isEmpty()) {
            characters.codePoints().forEach(cp -> surface.sendCharKey(cp, mods));
        }
    }

    private static int translateModifiers(int nsModifierFlags) {
        int mods = 0;
        if ((nsModifierFlags & NS_SHIFT) != 0) mods |= GHOSTTY_MODS_SHIFT;
        if ((nsModifierFlags & NS_CONTROL) != 0) mods |= GHOSTTY_MODS_CTRL;
        if ((nsModifierFlags & NS_OPTION) != 0) mods |= GHOSTTY_MODS_ALT;
        if ((nsModifierFlags & NS_COMMAND) != 0) mods |= GHOSTTY_MODS_SUPER;
        return mods;
    }

    /**
     * Frees this tab's native resources. Must be called only after the
     * session's {@link GhosttySurface} is already confirmed closed (e.g.
     * via {@code SessionManager#closeSession}, which uses {@code
     * GhosttySurface#closeGracefully} internally) -- never call this to
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
            LOG.log(Logger.Level.WARNING, "Failed to close CpmTerminalHost for session " + sessionId, e);
        }
        try {
            app.close();
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "Failed to close GhosttyApp for session " + sessionId, e);
        }
    }
}
