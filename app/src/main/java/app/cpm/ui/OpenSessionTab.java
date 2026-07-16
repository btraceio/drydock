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

    /** Attaches the now-running {@link GhosttySurface} and starts forwarding keyboard input to it. */
    void attachSurface(GhosttySurface surface) {
        this.surface = surface;
        placeholder.getChildren().remove(statusLabel);
        host.setKeyEventListener(this::onKeyEvent);
        updateGeometry();
    }

    GhosttyApp app() {
        return app;
    }

    CpmTerminalHost host() {
        return host;
    }

    /** Calls {@code ghostty_app_tick} + draw; bound to this tab's own {@code GhosttyApp}'s wakeup callback. */
    void tickAndDraw() {
        if (disposed) {
            return;
        }
        try {
            app.tick();
            if (surface != null) {
                surface.draw();
            }
        } catch (RuntimeException e) {
            LOG.log(Logger.Level.WARNING, "tick/draw failed for session " + sessionId, e);
        }
    }

    void setVisible(boolean visible) {
        if (disposed) {
            return;
        }
        host.setVisible(visible);
        if (visible) {
            updateGeometry();
            focus();
        }
    }

    void focus() {
        if (disposed || surface == null) {
            return;
        }
        host.setFocused(true);
        app.setFocus(true);
        surface.setFocus(true);
    }

    private void updateGeometry() {
        if (disposed || surface == null || placeholder.getScene() == null) {
            return;
        }
        Bounds sceneBounds = placeholder.localToScene(placeholder.getBoundsInLocal());
        if (sceneBounds.getWidth() <= 0 || sceneBounds.getHeight() <= 0) {
            return;
        }
        double scale = stage.getOutputScaleX();
        host.setFrame(sceneBounds.getMinX(), sceneBounds.getMinY(), sceneBounds.getWidth(), sceneBounds.getHeight());
        surface.setSize((int) Math.round(sceneBounds.getWidth() * scale), (int) Math.round(sceneBounds.getHeight() * scale));
        surface.draw();
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
