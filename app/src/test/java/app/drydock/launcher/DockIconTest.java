package app.drydock.launcher;

import java.awt.Image;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockIconTest {

    @Test
    void loadsBundledIcon() {
        // Headless-safe: touches only ImageIO + the classpath resource, not Taskbar.
        assertNotNull(DockIcon.loadIconImage(), "bundled /icon/drydock.png must load");
    }

    @Test
    void bundledIconIsFullBleedSoPaddingIsWhatFixesTheSize() {
        // The whole reason padForDock exists: the source artwork touches every
        // edge (no built-in dock margin). If this ever stops being true, the
        // padding fraction should be revisited.
        BufferedImage icon = (BufferedImage) DockIcon.loadIconImage();
        assertNotNull(icon);
        int w = icon.getWidth();
        int h = icon.getHeight();
        assertTrue(alpha(icon, 0, h / 2) > 0, "left edge should be opaque (full-bleed)");
        assertTrue(alpha(icon, w - 1, h / 2) > 0, "right edge should be opaque (full-bleed)");
    }

    @Test
    void padForDockInsetsArtworkAndClearsTheMargin() {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                source.setRGB(x, y, 0xFF00FF00); // fully opaque green, edge to edge
            }
        }

        BufferedImage padded = (BufferedImage) DockIcon.padForDock(source);

        assertEquals(100, padded.getWidth());
        assertEquals(100, padded.getHeight());
        // Corners are now inside the transparent margin.
        assertEquals(0, alpha(padded, 0, 0), "top-left corner must be transparent margin");
        assertEquals(0, alpha(padded, 99, 99), "bottom-right corner must be transparent margin");
        // The centre still carries the artwork.
        assertTrue(alpha(padded, 50, 50) > 0, "centre must keep the opaque artwork");
    }

    @Test
    void padForDockReturnsSourceWhenDimensionsUnknown() {
        Image unsized = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) {
            @Override
            public int getWidth(java.awt.image.ImageObserver observer) {
                return -1;
            }
        };
        assertEquals(unsized, DockIcon.padForDock(unsized));
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }
}
