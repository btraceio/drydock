package app.drydock.intellij.tasks;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;

/** The remembered task windows as stored in the IDE's application properties. */
public class TaskWindowsPlatformTest extends BasePlatformTestCase {

    @Override
    protected void tearDown() throws Exception {
        try {
            PropertiesComponent.getInstance().setValues(TaskWindows.OPEN_WINDOWS_KEY, new String[0]);
        } finally {
            super.tearDown();
        }
    }

    public void testClosingTheLastRememberedWindowForgetsIt() {
        TaskWindows.remember(Path.of(getProject().getBasePath()));
        assertNotNull(PropertiesComponent.getInstance().getValues(TaskWindows.OPEN_WINDOWS_KEY));

        TaskWindows.windowClosing(getProject(), false);

        String[] remembered = PropertiesComponent.getInstance().getValues(TaskWindows.OPEN_WINDOWS_KEY);
        assertTrue("still remembered: " + (remembered == null ? null : String.join(", ", remembered)),
                remembered == null || remembered.length == 0);
    }
}
