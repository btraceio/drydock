package app.drydock.intellij.review;

import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffNotificationProvider;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.Side;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.EditorNotificationPanel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@link ReviewDiffContents} in a booted IDE: a worktree file whose document
 * holds the reviewed text backs the right side with that document (and its
 * PSI); an unsaved edit, or no worktree file, gives a typed snapshot holding
 * the reviewed text.
 *
 * <p>Reference resolution itself is not asserted here: the test runtime has
 * no language plugin with references. The spike (notes/review-workbench-spikes.md,
 * S1) showed resolution follows from a PSI-backed right document.</p>
 */
public class ReviewDiffContentsPlatformTest extends BasePlatformTestCase {

    private static final String LEFT = "a\nb\n";
    private static final String RIGHT = "a\nB\n";

    private Path dir;
    private Disposable parent;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dir = Files.createTempDirectory("review-diff-contents");
        parent = Disposer.newDisposable();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            Disposer.dispose(parent);
            FileDocumentManager.getInstance().saveAllDocuments();
        } finally {
            super.tearDown();
        }
    }

    public void testACleanWorktreeFileBacksTheRightSide() throws IOException {
        VirtualFile file = worktreeFile("a.txt", RIGHT);
        SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent, context(file));

        assertEquals(ReviewDiffContents.RightSide.FILE, request.getUserData(ReviewDiffRequests.RIGHT_SIDE_KEY));
        Document right = ((DocumentContent) request.getContents().get(1)).getDocument();
        assertSame(FileDocumentManager.getInstance().getDocument(file), right);
        assertNotNull("the right document has the project's PSI",
                PsiDocumentManager.getInstance(getProject()).getPsiFile(right));
        Document left = ((DocumentContent) request.getContents().get(0)).getDocument();
        assertEquals(LEFT, left.getText());
        assertFalse("the left side is a revision, never editable", left.isWritable());
    }

    public void testAnUnsavedEditGivesASnapshotOfTheReviewedText() throws IOException {
        VirtualFile file = worktreeFile("b.txt", RIGHT);
        Document document = FileDocumentManager.getInstance().getDocument(file);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.insertString(0, "edited\n"));

        SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent, context(file));

        assertEquals(ReviewDiffContents.RightSide.SNAPSHOT, request.getUserData(ReviewDiffRequests.RIGHT_SIDE_KEY));
        Document right = ((DocumentContent) request.getContents().get(1)).getDocument();
        assertNotSame(document, right);
        assertEquals(RIGHT, right.getText());
    }

    public void testNoWorktreeFileGivesASnapshot() {
        ReviewDiffRequests.RequestContext context = new ReviewDiffRequests.RequestContext("c.txt", plan());
        SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent, context);

        assertEquals(ReviewDiffContents.RightSide.SNAPSHOT, request.getUserData(ReviewDiffRequests.RIGHT_SIDE_KEY));
        assertEquals(RIGHT, ((DocumentContent) request.getContents().get(1)).getDocument().getText());
    }

    public void testEveryRequestCarriesItsOwnStaleness() throws IOException {
        VirtualFile file = worktreeFile("d.txt", RIGHT);
        SimpleDiffRequest first = ReviewDiffRequests.toRequest(getProject(), parent, context(file));
        SimpleDiffRequest second = ReviewDiffRequests.toRequest(getProject(), parent, context(file));

        ReviewDiffContents.Staleness staleness = first.getUserData(ReviewDiffContents.STALENESS_KEY);
        assertNotNull(staleness);
        assertNotSame(staleness, second.getUserData(ReviewDiffContents.STALENESS_KEY));
        assertFalse(staleness.isStale());
    }

    public void testASnapshotRequestShowsTheSnapshotNotice() {
        SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent,
                new ReviewDiffRequests.RequestContext("e.txt", plan()));

        EditorNotificationPanel notice = notice(request);
        assertTrue(notice.isVisible());
        assertEquals(ReviewDiffNotice.SNAPSHOT_TEXT, notice.getText());
    }

    public void testAFileBackedNoticeAppearsWhenTheRequestTurnsStale() throws IOException {
        VirtualFile file = worktreeFile("f.txt", RIGHT);
        SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent, context(file));
        EditorNotificationPanel notice = notice(request);
        assertFalse("silent while the file holds the reviewed text", notice.isVisible());

        request.getUserData(ReviewDiffContents.STALENESS_KEY).markStale();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue(notice.isVisible());
        assertEquals(ReviewDiffNotice.STALE_TEXT, notice.getText());
    }

    private static EditorNotificationPanel notice(SimpleDiffRequest request) {
        List<DiffNotificationProvider> providers = request.getUserData(DiffUserDataKeys.NOTIFICATION_PROVIDERS);
        assertNotNull(providers);
        assertEquals(1, providers.size());
        return (EditorNotificationPanel) providers.get(0).createNotification(null);
    }

    private VirtualFile worktreeFile(String name, String text) throws IOException {
        Path path = Files.writeString(dir.resolve(name), text);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        assertNotNull(file);
        return file;
    }

    private ReviewDiffRequests.RequestContext context(VirtualFile file) {
        return new ReviewDiffRequests.RequestContext(file.getName(), plan(), Optional.of(file.toNioPath()));
    }

    private static ReviewDiffRequests.Plan plan() {
        return new ReviewDiffRequests.Plan(LEFT, RIGHT, List.of(), Side.RIGHT, 1);
    }
}
