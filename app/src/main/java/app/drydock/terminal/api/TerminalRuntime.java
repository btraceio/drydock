package app.drydock.terminal.api;

import java.nio.file.Path;

/**
 * A per-view terminal runtime: the event/render pump that owns one or more
 * {@link TerminalSurface}s embedded in a {@link TerminalHostView}. Every
 * method must be called on the JavaFX Application Thread.
 */
public interface TerminalRuntime extends AutoCloseable {

    /** Pumps one iteration of the runtime's event loop. */
    void tick();

    void setFocus(boolean focused);

    /** Re-loads the runtime's config from {@code configFile} (theme switch). */
    void updateConfig(Path configFile);

    /** Opens a new surface in {@code host}, running {@code spec}, at the given output scale. */
    TerminalSurface openSurface(TerminalHostView host, double scaleFactor, TerminalSpec spec);

    @Override
    void close();
}
