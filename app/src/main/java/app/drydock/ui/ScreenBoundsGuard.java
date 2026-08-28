package app.drydock.ui;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the undecorated stage usable after the display configuration
 * changes (e.g. an external monitor is disconnected): macOS relocates the
 * window onto the remaining screen but keeps its old size, so a window
 * sized for a 4K panel can end up larger than the laptop display it now
 * lives on -- title bar tucked under the menu bar, resize edges off the
 * right/bottom, nothing draggable.
 *
 * <p>JavaFX exposes no event for display-configuration changes
 * ({@link Screen#getScreens()} is a snapshot, not observable), so this
 * polls it on a slow daemon timer and acts only when the screen set
 * actually changes, by signature. On a change it clamps the stage to the
 * visual bounds of the screen it now occupies, repositioning so the title
 * bar and resize edges are reachable. Normal use never changes the screen
 * set, so dragging and resizing are never fought -- the guard is inert
 * until a monitor is (dis)connected or the resolution changes.
 */
final class ScreenBoundsGuard {

    private static final long POLL_MS = 1500;

    private final Stage stage;
    private final double minWidth;
    private final double minHeight;
    private final ScheduledExecutorService timer;
    private volatile String signature;

    private ScreenBoundsGuard(Stage stage, double minWidth, double minHeight) {
        this.stage = stage;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drydock-screen-guard");
            t.setDaemon(true);
            return t;
        });
        this.signature = signatureOf(Screen.getScreens());
    }

    static ScreenBoundsGuard start(Stage stage, double minWidth, double minHeight) {
        ScreenBoundsGuard guard = new ScreenBoundsGuard(stage, minWidth, minHeight);
        guard.timer.scheduleWithFixedDelay(guard::tick, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        return guard;
    }

    private void tick() {
        try {
            List<Screen> screens = Screen.getScreens();
            String sig = signatureOf(screens);
            if (sig.equals(signature)) {
                return;
            }
            signature = sig;
            Platform.runLater(() -> clampTo(screens));
        } catch (RuntimeException e) {
            // A transient Screen query failure must not kill the timer thread.
        }
    }

    private void clampTo(List<Screen> screens) {
        if (screens.isEmpty() || !stage.isShowing() || stage.isIconified() || stage.isMaximized()) {
            return;
        }
        Rectangle2D bounds = bestScreenBounds(screens);
        double x = stage.getX();
        double y = stage.getY();
        double w = stage.getWidth();
        double h = stage.getHeight();

        boolean tooWide = w > bounds.getWidth();
        boolean tooTall = h > bounds.getHeight();
        double newW = tooWide ? Math.max(minWidth, bounds.getWidth()) : w;
        double newH = tooTall ? Math.max(minHeight, bounds.getHeight()) : h;

        // Reposition only if the (possibly resized) window would not fit at
        // its current position -- a window the user dragged partly off the
        // edge stays put unless it is also too big or fully off-screen.
        boolean fitsX = x >= bounds.getMinX() && x + newW <= bounds.getMaxX();
        boolean fitsY = y >= bounds.getMinY() && y + newH <= bounds.getMaxY();
        if (!tooWide && !tooTall && fitsX && fitsY) {
            return;
        }
        double newX = Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX() - newW));
        double newY = Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY() - newH));
        stage.setX(newX);
        stage.setY(newY);
        stage.setWidth(newW);
        stage.setHeight(newH);
    }

    /**
     * The visual bounds of the screen containing the window's top-left
     * corner, else the one with the largest overlap (the one macOS moved
     * the window onto when the previous screen disappeared).
     */
    private Rectangle2D bestScreenBounds(List<Screen> screens) {
        double x = stage.getX();
        double y = stage.getY();
        for (Screen s : screens) {
            Rectangle2D b = s.getVisualBounds();
            if (b.contains(x, y)) {
                return b;
            }
        }
        double bestArea = -1;
        Rectangle2D best = screens.get(0).getVisualBounds();
        for (Screen s : screens) {
            Rectangle2D b = s.getVisualBounds();
            double ix = Math.max(0, Math.min(x + stage.getWidth(), b.getMaxX()) - Math.max(x, b.getMinX()));
            double iy = Math.max(0, Math.min(y + stage.getHeight(), b.getMaxY()) - Math.max(y, b.getMinY()));
            double area = ix * iy;
            if (area > bestArea) {
                bestArea = area;
                best = b;
            }
        }
        return best;
    }

    /** A string that changes when the screen count, geometry, or DPI changes. */
    private static String signatureOf(List<Screen> screens) {
        StringBuilder sb = new StringBuilder();
        for (Screen s : screens) {
            Rectangle2D b = s.getVisualBounds();
            sb.append(s.getDpi()).append('|')
                    .append(b.getMinX()).append(',').append(b.getMinY()).append(',')
                    .append(b.getWidth()).append(',').append(b.getHeight()).append(';');
        }
        return sb.toString();
    }

    void close() {
        timer.shutdownNow();
    }
}
