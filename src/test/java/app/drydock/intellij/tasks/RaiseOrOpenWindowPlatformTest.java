package app.drydock.intellij.tasks;

import app.drydock.intellij.agent.JumpPlan;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The raise-or-open shared by the session jump and the checkout window
 * action, pinned end to end in a booted IDE: the background scan finds the
 * test project as an open window for its base directory (through a symlink,
 * too), the folder-gone path says so in a dialog instead of opening
 * anything, and an already-open window is focused without being remembered
 * for the restore at the next IDE start.
 */
public class RaiseOrOpenWindowPlatformTest extends BasePlatformTestCase {

    public void testScanFindsTheTestProjectShowingItsBaseDirectory() throws Exception {
        String basePath = getProject().getBasePath();
        assertNotNull("the test project has a base path", basePath);
        Path base = Path.of(basePath);
        // The light test project's base directory is not guaranteed to exist;
        // the scenario pinned here is an existing checkout.
        Files.createDirectories(base);

        RaiseOrOpenWindow.Scan scan = RaiseOrOpenWindow.scan(base);

        assertTrue("the base directory exists", scan.folderExists());
        assertEquals("the test project shows its base directory", Optional.of(getProject()),
                scan.openWindow());
    }

    public void testScanCanonicalizesBeforeComparing() throws Exception {
        String basePath = getProject().getBasePath();
        assertNotNull("the test project has a base path", basePath);
        Path base = Path.of(basePath);
        Files.createDirectories(base);
        Path tempDir = Files.createTempDirectory("drydock-raise-or-open");
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, base);

            RaiseOrOpenWindow.Scan scan = RaiseOrOpenWindow.scan(link);

            assertTrue("the symlink resolves to a directory", scan.folderExists());
            assertEquals("the symlinked path matches the project after canonicalization",
                    Optional.of(getProject()), scan.openWindow());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(tempDir);
        }
    }

    public void testRaiseOrOpenReportsAGoneFolderAndShowsOneDialog() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"),
                "drydock-raise-or-open-missing-" + UUID.randomUUID());
        List<String> shown = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> { shown.add(message); return Messages.OK; },
                getTestRootDisposable());

        Optional<Project> opened = RaiseOrOpenWindow.raiseOrOpen(getProject(), missing, "missing",
                new RaiseOrOpenWindow.Scan(false, Optional.empty()));

        assertTrue("nothing opened for a folder gone since the click", opened.isEmpty());
        assertEquals("exactly one dialog was shown", 1, shown.size());
        assertTrue("the dialog carries the folder-gone text for the path",
                shown.get(0).contains(JumpPlan.folderGone("missing", missing)));
    }

    public void testRaiseOrOpenRaisesTheAlreadyOpenWindowWithoutRememberingIt() {
        String basePath = getProject().getBasePath();
        assertNotNull("the test project has a base path", basePath);
        String[] rememberedBefore = PropertiesComponent.getInstance().getValues(TaskWindows.OPEN_WINDOWS_KEY);

        Optional<Project> opened = RaiseOrOpenWindow.raiseOrOpen(getProject(), Path.of(basePath), "project",
                new RaiseOrOpenWindow.Scan(true, Optional.of(getProject())));

        assertEquals("the already-open window is raised, not re-opened", Optional.of(getProject()), opened);
        String[] rememberedAfter = PropertiesComponent.getInstance().getValues(TaskWindows.OPEN_WINDOWS_KEY);
        assertTrue("an already-open window is not remembered for the restore",
                Arrays.equals(rememberedBefore, rememberedAfter));
    }
}
