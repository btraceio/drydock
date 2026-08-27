package app.drydock.terminal.jediterm;

import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSurface;
import app.drydock.terminal.api.Shortcut;
import com.pty4j.PtyProcess;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import javafx.application.Platform;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The JediTermFX {@link TerminalSurface}: one running command inside a
 * {@link JediTermFxWidget} over a pty4j {@link PtyProcess}.
 *
 * <p>The native-overlay-shaped methods ({@link #dispatchKeyEvent},
 * {@link #setSize}, {@link #draw}, {@link #sendMousePos} ...) are no-ops:
 * the widget renders itself and consumes its own key/mouse/scroll through
 * the JavaFX scene graph, so there is nothing for a native host to drive.
 * The methods that actually carry the session's semantics -- {@link #submitLine}
 * (typing a prompt), {@link #processExited} (the exit watcher's poll),
 * {@link #closeGracefully} (graceful teardown) -- are real and back onto the
 * pty4j process and JediTermFX widget.</p>
 */
final class JediTermFxSurface implements TerminalSurface {

    private final JediTermFxWidget widget;
    private final PtyProcess process;
    private final PtyConnector connector;
    private volatile boolean closed;

    JediTermFxSurface(JediTermFxWidget widget, PtyProcess process, PtyConnector connector) {
        this.widget = widget;
        this.process = process;
        this.connector = connector;
    }

    @Override
    public Optional<Shortcut> dispatchKeyEvent(int keyCode, int modifierFlags, boolean keyDown,
                                                 String characters, String unshiftedCharacters) {
        // The widget handles keyboard input itself; this is never called on
        // the embedded-node path (the host has no raw key-event listener).
        return Optional.empty();
    }

    @Override
    public void submitLine(String line) {
        if (closed) {
            return;
        }
        try {
            // \r = Return, matching the macOS surface's submitLine contract
            // (single line; an embedded newline would submit early).
            connector.write(line + "\r");
        } catch (Exception ignored) {
            // Process closed in the teardown gap.
        }
    }

    @Override
    public void setSize(int widthPx, int heightPx) {
        // No-op: the widget is laid out by JavaFX; pixel sizes are its own.
    }

    @Override
    public void setFocus(boolean focused) {
        // No-op: the widget manages focus.
    }

    @Override
    public void draw() {
        // No-op: the widget renders itself.
    }

    @Override
    public void refresh() {
        // No-op.
    }

    @Override
    public void sendMousePos(double x, double y, int modifierFlags) {
        // No-op: the widget handles mouse.
    }

    @Override
    public void sendMouseButton(int state, int button, int modifierFlags) {
        // No-op.
    }

    @Override
    public void sendMouseScroll(double deltaX, double deltaY, int scrollMods) {
        // No-op.
    }

    @Override
    public void applyConfig(TerminalRuntime runtime) {
        // Theming at runtime is deferred (see JediTermFxRuntime.updateConfig);
        // a freshly-opened surface inherits the settings provider it was built
        // with.
    }

    @Override
    public String readScreenText() {
        // Best-effort: not wired to JediTermFX's text buffer in this first cut.
        return "";
    }

    @Override
    public boolean processExited() {
        return closed || !process.isAlive();
    }

    @Override
    public void performBindingAction(String action) {
        // No-op: JediTermFX does not expose a binding-action API.
    }

    @Override
    public void setSearchListener(SearchListener listener) {
        // No-op: JediTermFX does not deliver search notifications.
    }

    @Override
    public void closeGracefully(long gracePeriodMillis, long pollIntervalMillis, Runnable onDone) {
        if (closed) {
            onDone.run();
            return;
        }
        // Tear down off the FX thread (this is called on the FX thread per the
        // interface, but the wait must not block it), then hop back to deliver
        // onDone -- matching the contract the macOS surface implements.
        Thread killer = new Thread(() -> {
            process.destroy();
            long deadline = System.currentTimeMillis() + gracePeriodMillis;
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(pollIntervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            closed = true;
            try {
                widget.close();
            } catch (Exception ignored) {
                // The widget may already be torn down; not the thing under test.
            }
            Platform.runLater(onDone);
        }, "jediterm-surface-close");
        killer.setDaemon(true);
        killer.start();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            widget.close();
        } catch (Exception ignored) {
            // Best-effort.
        }
    }
}