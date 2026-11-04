package app.drydock.intellij.review;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** The review tree's file rows carry their file type's icon, not plain text for every file. */
public class ReviewTabBrowserIconPlatformTest extends BasePlatformTestCase {

    public void testAFileRowShowsItsFileTypesIcon() {
        assertSame(FileTypeRegistry.getInstance().getFileTypeByFileName("plugin.xml").getIcon(),
                ReviewTabBrowser.fileIcon("src/main/resources/META-INF/plugin.xml"));
        assertNotSame("not the plain-text icon for an XML file", AllIcons.FileTypes.Text,
                ReviewTabBrowser.fileIcon("src/main/resources/META-INF/plugin.xml"));
    }

    public void testAnUnknownTypeFallsBackToItsTypesIconOrPlainText() {
        assertNotNull(ReviewTabBrowser.fileIcon("docs/notes.zzz-unknown"));
    }
}
