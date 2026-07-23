package app.drydock.launcher;

import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Taskbar.Feature;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import javax.imageio.ImageIO;

/**
 * Sets the macOS dock icon at run time from the bundled PNG. Needed because a
 * jbang launch is not a {@code .app} bundle and {@code -Xdock:icon} needs a
 * launch-time path the jar cannot provide.
 *
 * <p>MUST be invoked on the JavaFX application thread, after the toolkit is up
 * -- initializing AWT's Cocoa layer before JavaFX's Glass toolkit risks an
 * {@code NSApplication} main-thread conflict.</p>
 */
public final class DockIcon {

    private static final Logger LOG = System.getLogger(DockIcon.class.getName());
    private static final String ICON_RESOURCE = "/icon/drydock.png";

    private DockIcon() {
    }

    /** Best-effort: set the dock icon. Silently does nothing if unsupported. Never throws. */
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
                taskbar.setIconImage(image);
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
}
