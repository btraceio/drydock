package app.drydock.terminal.api;

import java.util.Optional;

/**
 * One running command inside a {@link TerminalHostView}. Input (keyboard,
 * mouse), output (screen text), and lifecycle. All keycode translation lives
 * behind this interface: callers hand it raw platform key/mouse events and it
 * either performs them or, for an intercepted app shortcut, reports a neutral
 * {@link Shortcut}. Every method must be called on the JavaFX Application Thread.
 */
public interface TerminalSurface extends AutoCloseable {

    /**
     * Classifies and performs a raw platform key event. Returns the neutral
     * {@link Shortcut} to run (on key-down only) when the event is an
     * intercepted app shortcut; otherwise performs the key (forwarding or
     * typing into the running program) and returns {@link Optional#empty()}.
     */
    Optional<Shortcut> dispatchKeyEvent(int keyCode, int modifierFlags, boolean keyDown,
                                        String characters, String unshiftedCharacters);

    /** Types {@code line} as real keystrokes and submits it with Return (single line; no embedded newline). */
    void submitLine(String line);

    void setSize(int widthPx, int heightPx);

    void setFocus(boolean focused);

    void draw();

    void refresh();

    /** Forwards a mouse-position event; {@code modifierFlags} are raw platform flags. */
    void sendMousePos(double x, double y, int modifierFlags);

    /** Forwards a mouse-button event; {@code modifierFlags} are raw platform flags. */
    void sendMouseButton(int state, int button, int modifierFlags);

    /** Forwards a scroll event; {@code scrollMods} is the pre-packed value from the host. */
    void sendMouseScroll(double deltaX, double deltaY, int scrollMods);

    String readScreenText();

    boolean processExited();

    /** Gracefully closes the surface, killing the child after {@code gracePeriodMillis}. */
    void closeGracefully(long gracePeriodMillis, long pollIntervalMillis, Runnable onDone);

    @Override
    void close();
}
