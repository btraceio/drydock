package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** The Files tree keeps its selected row across the rebuild a new snapshot causes. */
public class ReviewTabBrowserSelectionPlatformTest extends BasePlatformTestCase {

    private static ReviewTabModel.Snapshot snapshot(String... paths) {
        List<UnifiedDiff.FileDiff> files = new ArrayList<>();
        for (String path : paths) {
            files.add(new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false, List.of(new UnifiedDiff.Hunk("@@",
                    List.of(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1),
                            "x"))))));
        }
        return ReviewTabModel.snapshot(new ReviewTabModel.Inputs(new UnifiedDiff(files), List.of(), List.of(),
                Optional.empty()));
    }

    /** Waits until the async tree displays {@code rows} file rows and its rebuild's completion has run. */
    private static void settle(ReviewTabBrowser browser, int rows) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            PlatformTestUtil.waitWhileBusy(browser.getViewer());
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Object root = browser.getViewer().getModel().getRoot();
            if (root instanceof TreeNode node && node.getChildCount() == rows) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("the tree never showed " + rows + " rows");
            }
            Thread.sleep(20);
        }
    }

    private static String selectedPath(ReviewTabBrowser browser) {
        TreePath path = browser.getViewer().getSelectionPath();
        return path == null ? null : String.valueOf(path.getLastPathComponent());
    }

    public void testASelectedFileStaysSelectedAcrossARebuildWithoutAskingAgain() throws Exception {
        List<Object> shown = new ArrayList<>();
        ReviewTabBrowser browser = new ReviewTabBrowser(getProject(), shown::add);
        try {
            browser.accept(snapshot("src/A.java", "src/B.java"));
            settle(browser, 2);
            browser.selectFile("src/B.java");
            settle(browser, 2);
            assertEquals(1, shown.size());
            String selected = selectedPath(browser);
            assertNotNull(selected);

            browser.accept(snapshot("src/A.java", "src/B.java", "src/C.java"));
            settle(browser, 3);
            assertEquals("the same row is selected again", selected, selectedPath(browser));
            assertEquals("the consumer, whose diff shows it already, is not told again", 1, shown.size());

            browser.accept(snapshot("src/A.java"));
            settle(browser, 1);
            // The tree may select a neighbour on its own once the row is gone; the gone row is never restored.
            String afterRemoval = selectedPath(browser);
            assertTrue("a row the snapshot no longer has is not selected: " + afterRemoval,
                    afterRemoval == null || !afterRemoval.equals(selected));
        } finally {
            browser.dispose();
        }
    }
}
