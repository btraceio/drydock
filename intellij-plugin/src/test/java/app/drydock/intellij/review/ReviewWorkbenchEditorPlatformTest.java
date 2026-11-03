package app.drydock.intellij.review;

import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.Side;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The review workbench tab in a booted IDE: one reusable file per project,
 * a provider that opens only that file, and an editor whose centre hosts the
 * live review's diff processor and disposes it with the tab.
 */
public class ReviewWorkbenchEditorPlatformTest extends BasePlatformTestCase {

    public void testTheProjectHasOneWorkbenchFile() {
        assertSame(ReviewWorkbenchFile.of(getProject()), ReviewWorkbenchFile.of(getProject()));
    }

    public void testTheTabIsNamedAfterTheReviewSubject() {
        assertEquals("Drydock Review", ReviewWorkbenchFile.title(null));
        assertEquals("Drydock Review", ReviewWorkbenchFile.title(" "));
        assertEquals("Drydock Review — PR #930", ReviewWorkbenchFile.title("PR #930"));
    }

    public void testTheProviderIsRegisteredAndOpensOnlyTheWorkbenchFile() {
        ReviewWorkbenchEditorProvider provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList()
                .stream().filter(ReviewWorkbenchEditorProvider.class::isInstance)
                .map(ReviewWorkbenchEditorProvider.class::cast).findFirst().orElse(null);
        assertNotNull("registered in plugin.xml", provider);
        assertTrue(provider.accept(getProject(), ReviewWorkbenchFile.of(getProject())));
        assertFalse(provider.accept(getProject(), new LightVirtualFile("A.java", "class A {}")));
    }

    public void testWithoutALiveReviewTheCentreSaysSo() {
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                (project, parent) -> Optional.empty());
        try {
            assertEquals(ReviewWorkbenchEditor.NO_REVIEW_TEXT,
                    ((JBLabel) editor.inboxSplitter.getFirstComponent()).getText());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testTheCentreHostsTheReviewDiffSeededWithTheCurrentRequestAndClosingDisposesIt() {
        List<Integer> opens = new ArrayList<>();
        ReviewDiffEditor diff = new ReviewDiffEditor(() -> opens.add(1),
                context -> new SimpleDiffRequest(context.path(), DiffContentFactory.getInstance().createEmpty(),
                        DiffContentFactory.getInstance().createEmpty(), null, null),
                viewer -> {
                });
        diff.selection("row");
        diff.requestReady("row", context("src/A.java"));
        assertEquals("the first show opens the workbench", 1, opens.size());

        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                (project, parent) -> Optional.of(diff.host(project, parent)));
        JComponent focus = editor.getPreferredFocusedComponent();
        assertNotNull(focus);
        assertTrue("the diff is inside the workbench",
                SwingUtilities.isDescendingFrom(focus, editor.getComponent()));

        Disposer.dispose(editor);
        // The processor went with the tab: the next selection reopens the workbench.
        diff.selection("row2");
        diff.requestReady("row2", context("src/B.java"));
        assertEquals(2, opens.size());
    }

    public void testTheLayoutIsNavigatorDiffInboxOverTheOutbox() {
        ReviewWorkbenchEditor editor = new ReviewWorkbenchEditor(getProject(), ReviewWorkbenchFile.of(getProject()),
                (project, parent) -> Optional.empty());
        try {
            assertTrue("the outbox sits below", editor.outboxSplitter.isVertical());
            assertSame(editor.navSplitter, editor.outboxSplitter.getFirstComponent());
            assertFalse(editor.navSplitter.isVertical());
            assertSame(editor.inboxSplitter, editor.navSplitter.getSecondComponent());
            assertFalse(editor.inboxSplitter.isVertical());
            assertEquals(ReviewWorkbenchEditor.INBOX_EMPTY_TEXT,
                    ((JBLabel) editor.inboxSplitter.getSecondComponent()).getText());
            assertEquals(ReviewWorkbenchEditor.OUTBOX_EMPTY_TEXT,
                    ((JBLabel) editor.outboxSplitter.getSecondComponent()).getText());
            assertEquals(ReviewWorkbenchEditor.NAV_KEY, editor.navSplitter.getSplitterProportionKey());
            assertEquals(ReviewWorkbenchEditor.INBOX_KEY, editor.inboxSplitter.getSplitterProportionKey());
            assertEquals(ReviewWorkbenchEditor.OUTBOX_KEY, editor.outboxSplitter.getSplitterProportionKey());
        } finally {
            Disposer.dispose(editor);
        }
    }

    public void testAMovedDividerIsStoredAndANewTabLoadsIt() {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        String saved = properties.getValue(ReviewWorkbenchEditor.NAV_KEY);
        try {
            ReviewWorkbenchEditor first = new ReviewWorkbenchEditor(getProject(),
                    ReviewWorkbenchFile.of(getProject()), (project, parent) -> Optional.empty());
            first.navSplitter.setProportion(0.33f);
            assertEquals("0.33", properties.getValue(ReviewWorkbenchEditor.NAV_KEY));
            Disposer.dispose(first);

            ReviewWorkbenchEditor second = new ReviewWorkbenchEditor(getProject(),
                    ReviewWorkbenchFile.of(getProject()), (project, parent) -> Optional.empty());
            // The splitter loads the stored value when first shown (addNotify);
            // a headless test is never shown, so it asks for the load itself.
            second.navSplitter.setAndLoadSplitterProportionKey(ReviewWorkbenchEditor.NAV_KEY);
            assertEquals(0.33f, second.navSplitter.getProportion(), 0.001f);
            Disposer.dispose(second);
        } finally {
            properties.setValue(ReviewWorkbenchEditor.NAV_KEY, saved);
        }
    }

    private static ReviewDiffRequests.RequestContext context(String path) {
        return new ReviewDiffRequests.RequestContext(path,
                new ReviewDiffRequests.Plan("left\n", "right\n", List.<LineFragment>of(), Side.RIGHT, 0));
    }
}
