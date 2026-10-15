package app.drydock.intellij.toolwindow;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;

import javax.swing.JLabel;

/**
 * T17, Item C: the Drydock tool window's one content is created untitled and
 * non-closeable, which is exactly the input combination the platform's tab
 * layout keys its strip-hiding rule on (TabContentLayout's isToDrawTabs:
 * a single content with an empty title and no active tab icons draws no
 * strip). Pinned here through the real, booted ContentFactory — the
 * rendering itself is the manual runIde gate (a headless test has no tool
 * window to lay out).
 */
public class SessionsToolWindowFactoryContentTest extends BasePlatformTestCase {

    public void testTheSingleContentIsUntitledAndNonCloseable() {
        Content content = SessionsToolWindowFactory.newContent(new JLabel("loading"));

        assertTrue("an untitled content is what hides the redundant inner header",
                content.getDisplayName() == null || content.getDisplayName().isBlank());
        assertFalse("a single non-closeable content keeps the tab free of icons (and is not closeable at all)",
                content.isCloseable());
        assertFalse("nothing pins the Drydock window's only content", content.isPinnable());
    }
}
