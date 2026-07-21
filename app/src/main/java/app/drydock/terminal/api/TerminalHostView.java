package app.drydock.terminal.api;

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
