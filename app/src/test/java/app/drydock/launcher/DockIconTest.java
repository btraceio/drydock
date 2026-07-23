package app.drydock.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DockIconTest {

    @Test
    void loadsBundledIcon() {
        // Headless-safe: touches only ImageIO + the classpath resource, not Taskbar.
        assertNotNull(DockIcon.loadIconImage(), "bundled /icon/drydock.png must load");
    }
}
