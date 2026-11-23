package app.drydock.intellij.review;

import app.drydock.git.DiffScope;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.RemoteWorkbenchSnapshots.NoText;
import app.drydock.intellij.review.RemoteWorkbenchSnapshots.Ready;
import app.drydock.intellij.review.RemoteWorkbenchSnapshots.Unavailable;
import app.drydock.intellij.review.RemoteWorkbenchSnapshots.View;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSnapshot.Content;
import app.drydock.review.ReviewDataSource;
import app.drydock.review.ReviewDataSource.Result;

import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** The remote snapshot becomes a read-only request over real ReviewDiffRequests contents. */
public class RemoteWorkbenchSnapshotsTest extends BasePlatformTestCase {

    private static Content text(String s) {
        return new Content.Text(s.getBytes(StandardCharsets.UTF_8));
    }

    private static ReviewDataSnapshot snapshot(String file, Content left, Content right) {
        UnifiedDiff.FileDiff diff = new UnifiedDiff.FileDiff(file, "M", 1, 1, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,1 +1,1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL, OptionalInt.of(1), OptionalInt.empty(), "old"),
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "new")))));
        return new ReviewDataSnapshot("scope", ReviewDataSource.Backend.REMOTE, "box:1", 3, DiffScope.BASE,
                new ReviewBase("main", ReviewBase.Origin.PULL_REQUEST), "b", "h", "b", "fp",
                new UnifiedDiff(List.of(diff)), Map.of(file, new ReviewDataSnapshot.SourceFile(left, right)),
                Instant.EPOCH);
    }

    public void testASameNamedRegisteredLocalFileIsNeverOpenedOrRead() {
        VirtualFile local = myFixture.addFileToProject("a.txt", "new").getVirtualFile();
        FileDocumentManager.getInstance().saveAllDocuments();
        boolean cachedBefore = FileDocumentManager.getInstance().getCachedDocument(local) != null;
        View view = RemoteWorkbenchSnapshots.open(snapshot("a.txt", text("old"), text("new")), "a.txt", false);
        Ready ready = (Ready) view;
        assertTrue(ready.context().worktreeFile().isEmpty());
        Disposable parent = Disposer.newDisposable();
        try {
            SimpleDiffRequest request = ReviewDiffRequests.toRequest(getProject(), parent, ready.context());
            assertEquals(ReviewDiffContents.RightSide.SNAPSHOT, request.getUserData(ReviewDiffRequests.RIGHT_SIDE_KEY));
            assertEquals(Boolean.TRUE, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY));
            DocumentContent right = (DocumentContent) request.getContents().get(1);
            assertNotSame(FileDocumentManager.getInstance().getCachedDocument(local), right.getDocument());
            assertNotSame(local, right.getHighlightFile());
            assertEquals("new", right.getDocument().getText());
            assertEquals(cachedBefore, FileDocumentManager.getInstance().getCachedDocument(local) != null);
        } finally {
            Disposer.dispose(parent);
        }
        // Remote text that differs from the local file is what is shown.
        Ready differing = (Ready) RemoteWorkbenchSnapshots.open(
                snapshot("a.txt", text("old"), text("new")), "a.txt", false);
        assertEquals("new", differing.context().plan().rightText().toString());
    }

    public void testARetainedFailedSnapshotIsExplicitlyStale() {
        ReviewDataSnapshot s = snapshot("a.txt", text("old"), text("new"));
        Ready fresh = (Ready) RemoteWorkbenchSnapshots.open(s, "a.txt", false);
        Ready stale = (Ready) RemoteWorkbenchSnapshots.open(s, "a.txt", true);
        assertFalse(fresh.stale());
        assertTrue(stale.stale());
        assertTrue(stale.context().path().startsWith(RemoteWorkbenchSnapshots.STALE_TITLE_PREFIX));
        assertFalse(fresh.context().path().contains("STALE"));
    }

    public void testAbsentSideBinaryAndFailedReadStayDistinct() {
        ReviewDataSnapshot s = snapshot("a.txt", new Content.Absent(), text("new"));
        // An absent left side is an empty text only when the diff rows allow it.
        View absent = RemoteWorkbenchSnapshots.open(s, "a.txt", false);
        assertTrue(absent instanceof Unavailable);
        ReviewDataSnapshot ok = snapshot("a.txt", text("old"), text("new"));
        View failed = RemoteWorkbenchSnapshots.fromSides(ok, "a.txt",
                new Result.Unavailable<>("ssh timeout"), new Result.Ok<>(text("new")), false);
        assertTrue(((Unavailable) failed).reason().contains("read failed: ssh timeout"));
        View binary = RemoteWorkbenchSnapshots.open(snapshot("a.txt", text("old"), new Content.Binary(3, "ab")),
                "a.txt", false);
        assertTrue(binary instanceof NoText);
        View missing = RemoteWorkbenchSnapshots.open(ok, "other.txt", false);
        assertTrue(((Unavailable) missing).reason().contains("not captured"));
        View refused = RemoteWorkbenchSnapshots.fromSides(ok, "a.txt",
                new Result.Ok<>(text("old")), new Result.Refused<>("mismatch"), false);
        assertTrue(((Unavailable) refused).reason().contains("refused"));
    }

    public void testAnAbsentSideIsAnEmptyTextForAnAddition() {
        UnifiedDiff.FileDiff add = new UnifiedDiff.FileDiff("n.txt", "A", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -0,0 +1,1 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "x")))));
        ReviewDataSnapshot base = snapshot("n.txt", new Content.Absent(), text("x"));
        ReviewDataSnapshot s = new ReviewDataSnapshot("scope", base.backend(), "box:1", 3, DiffScope.BASE, base.base(),
                "b", "h", "b", "fp", new UnifiedDiff(List.of(add)), base.sources(), Instant.EPOCH);
        Ready ready = (Ready) RemoteWorkbenchSnapshots.open(s, "n.txt", false);
        assertEquals("", ready.context().plan().leftText().toString());
        assertEquals("x", ready.context().plan().rightText().toString());
    }
}
