package app.drydock.launcher;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.Taskbar.Feature;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import javax.imageio.ImageIO;

/**
 * Sets the macOS dock icon at run time from the bundled PNG. Needed because a
 * jbang launch is not a {@code .app} bundle and {@code -Xdock:icon} needs a
 * launch-time path the bare jar cannot provide.
 *
 * <p>MUST be invoked on the JavaFX application thread, after the toolkit is up
 * -- doing AWT/Taskbar work before Glass initializes risks an
 * {@code NSApplication} main-thread conflict.</p>
 *
 * <p>The application <em>name</em> (menu bar / Dock / Cmd-Tab) is set elsewhere,
 * far earlier: {@link app.drydock.Main} initializes AWT with
 * {@code apple.awt.application.name} before JavaFX launches. See Main for why.</p>
 */
public final class DockIcon {

    private static final Logger LOG = System.getLogger(DockIcon.class.getName());
    private static final String ICON_RESOURCE = "/icon/drydock.png";

    // The bundled PNG is full-bleed (opaque artwork edge-to-edge). Native macOS
    // dock icons instead sit on Apple's icon grid, where the artwork fills only
    // ~80% of the tile with transparent padding around it. Without that margin a
    // full-bleed image renders visibly larger than every neighbouring icon, so
    // we inset it to the grid before handing it to the dock.
    private static final double DOCK_CONTENT_FRACTION = 0.80;

    private DockIcon() {
    }

    /**
     * Best-effort: set the dock icon (padded to the macOS icon grid). Silently
     * does nothing if unsupported. Never throws.
     */
    public static void applyDockIcon() {
        try {
            if (!Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Feature.ICON_IMAGE)) {
                return;
            }
            Image image = loadIconImage();
            if (image != null) {
                taskbar.setIconImage(padForDock(image));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Could not set the dock icon", e);
        }
    }

    /** Loads the bundled icon, or returns {@code null} if missing/unreadable. */
    static Image loadIconImage() {
        try (InputStream in = DockIcon.class.getResourceAsStream(ICON_RESOURCE)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Centers {@code source} at {@link #DOCK_CONTENT_FRACTION} of its size on a
     * transparent square canvas, giving the full-bleed artwork the margin macOS
     * expects. Returns {@code source} unchanged if its dimensions are unknown.
     */
    static Image padForDock(Image source) {
        int width = source.getWidth(null);
        int height = source.getHeight(null);
        if (width <= 0 || height <= 0) {
            return source;
        }
        int canvas = Math.max(width, height);
        int content = (int) Math.round(canvas * DOCK_CONTENT_FRACTION);
        int offset = (canvas - content) / 2;

        BufferedImage padded = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = padded.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, offset, offset, content, content, null);
        } finally {
            g.dispose();
        }
        return padded;
    }
}
