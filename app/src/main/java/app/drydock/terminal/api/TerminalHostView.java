package app.drydock.terminal.api;

import java.util.Optional;

/**
 * A native view embedded as an overlay in the host window, into which a
 * {@link TerminalSurface} renders. The only implementation is the macOS
 * AppKit host shim, but the contract is expressed neutrally: positions are in
 * device-independent window coordinates and input arrives as raw platform key
 * events through the listener interfaces below.
 *
 * <p>Every method must be called on the JavaFX Application Thread.</p>
 */
public interface TerminalHostView extends AutoCloseable {

    /** Sets the view's frame in the parent window's content-view coordinate space. */
    void setFrame(double x, double y, double width, double height);

    void setVisible(boolean visible);

    void setFocused(boolean focused);

    /** Registers the raw key-event listener (at most once per view). */
    void setKeyEventListener(KeyEventListener listener);

    /** Registers the raw scroll-event listener (at most once per view). */
    void setScrollEventListener(ScrollEventListener listener);

    /** Registers the mouse-position listener (at most once per view). */
    void setMousePosEventListener(MousePosEventListener listener);

    /** Registers the mouse-button listener (at most once per view). */
    void setMouseButtonEventListener(MouseButtonEventListener listener);

    /**
     * Updates the host view's backing-layer content scale to {@code scale}
     * (e.g. 2.0 on Retina, 1.0 on a standard-DPI display). The native host
     * applies this to its Metal layer's {@code contentsScale}; a pure-JavaFX
     * backend ignores it (JavaFX scales its own scene). Called by the bridge
     * on every geometry update so the renderer composites at the right
     * resolution after the window's backing scale changes (monitor move,
     * suspend/wake, system scaling preference).
     */
    default void setContentScale(double scale) {
        // No-op: a non-native host has no backing layer to scale.
    }

    /**
     * Registers the display-change listener (at most once per view). The
     * native host dispatches it when its window moves to a different screen
     * or the display configuration changes (suspend/wake), and once
     * immediately on registration with the current display id; a pure-JavaFX
     * backend never dispatches it. {@code displayId} is the macOS
     * {@code CGDirectDisplayID} (0 if unknown).
     */
    default void setDisplayChangeListener(DisplayChangeListener listener) {
        // No-op: a non-native host has no per-display id to report.
    }

    /**
     * A display-configuration change: the host view's window moved to a
     * different screen, or the display configuration changed (suspend/wake,
     * monitor attach/detach). {@code displayId} is the macOS
     * {@code CGDirectDisplayID} of the window's current screen (0 if none).
     */
    @FunctionalInterface
    interface DisplayChangeListener {
        void onDisplayChange(int displayId);
    }

    /**
     * The JavaFX node this host renders into, if any.
     *
     * <p>The macOS AppKit host returns {@link Optional#empty()} (it overlays a
     * native view on the window, never entering the JavaFX scene graph). A
     * pure-JavaFX backend (JediTermFX, the Windows path) returns its widget’s
     * pane so the owning tab can add it to its layout. Returning empty is the
     * native-overlay path; returning a node is the embedded-node path. Defaulting
     * to empty keeps every native host unchanged.
     */
    default Optional<javafx.scene.Node> embeddedNode() {
        return Optional.empty();
    }

    @Override
    void close();

    /**
     * A raw, uninterpreted platform key event. {@code keyCode}/{@code
     * modifierFlags} are the native (AppKit) values; {@code characters} and
     * {@code unshiftedCharacters} are NSEvent's {@code characters} /
     * {@code charactersIgnoringModifiers}.
     */
    @FunctionalInterface
    interface KeyEventListener {
        void onKeyEvent(int keyCode, int modifierFlags, boolean keyDown, String characters,
                        String unshiftedCharacters);
    }

    /** A raw scrollWheel event; {@code scrollMods} is a pre-packed scroll-mods value. */
    @FunctionalInterface
    interface ScrollEventListener {
        void onScrollEvent(double deltaX, double deltaY, int scrollMods);
    }

    /** A mouse-position event in view-local points (top-left origin); {@code modifierFlags} raw. */
    @FunctionalInterface
    interface MousePosEventListener {
        void onMousePosEvent(double x, double y, int modifierFlags);
    }

    /** A mouse-button event; {@code state}/{@code button} carry ghostty enum values, {@code modifierFlags} raw. */
    @FunctionalInterface
    interface MouseButtonEventListener {
        void onMouseButtonEvent(int state, int button, int modifierFlags);
    }
}
