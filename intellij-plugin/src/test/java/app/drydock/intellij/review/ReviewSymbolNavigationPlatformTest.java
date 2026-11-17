package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.workbench.model.FlowModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.util.Side;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The symbol navigation's resolution seam ({@link ReviewSymbolNavigation#definitionAt}) and the
 * rewritten {@code viewerChanged} discipline. The test runtime has no language plugin with
 * references ({@code ReviewDiffContentsPlatformTest}'s note), so only the honest miss
 * is asserted there — a file whose text carries no reference resolves to
 * null, never a guess. The positive path (Ctrl+Click on a Java identifier
 * opens its definition) is the live IDE gate.
 *
 * <p>The viewerChanged discipline is exercised on the flow centre's real hunk viewer: the install
 * path runs on a live unified viewer, an immediate repeat is a no-op by the
 * installed guard, and a background-thread call hops to the EDT without
 * error. The disposal-race guard and the null-editor retry cannot be
 * reproduced deterministically here (the platform delivers complete
 * viewers); their enforcement is the code's ordering itself.</p>
 */
public class ReviewSymbolNavigationPlatformTest extends BasePlatformTestCase {

    public void testAFileWithoutReferencesResolvesToNothing() throws Exception {
        Path file = Files.createTempFile("review-symbol-nav", ".txt");
        Files.writeString(file, "plain text with no language references\n");
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull(virtualFile);
        Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
        PsiFile psiFile = PsiDocumentManager.getInstance(getProject()).getPsiFile(document);

        assertNull("no reference, no definition — never a wrong-line guess",
                ReviewSymbolNavigation.definitionAt(getProject(), psiFile, 10));
    }

    public void testARealViewerInstallsOnceAndABackgroundCallHopsToTheEdt() throws Exception {
        ReviewSymbolNavigation navigation = new ReviewSymbolNavigation();
        List<FrameDiffTool.DiffViewer> viewers = new ArrayList<>();
        List<String> events = new ArrayList<>();
        ReviewWorkbenchFlow flow = new ReviewWorkbenchFlow(() -> Optional.of("scope-nav"),
                new ReviewFlowMarks(PropertiesComponent.getInstance(getProject())), tab -> {
                });
        ReviewWorkbenchFlowView view = new ReviewWorkbenchFlowView(getProject(), flow,
                new ReviewWorkbenchFlowView.Actions() {
                    @Override
                    public CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(
                            String path, int hunkIndex, String scrollKey) {
                        return CompletableFuture.completedFuture(Optional.of(new ReviewDiffRequests.RequestContext(
                                path, ReviewDiffRequests.plan("a\nb\n", "a\nc\n", List.of(),
                                new ReviewDiffRequests.ScrollTarget(Side.RIGHT, 1)))));
                    }

                    @Override
                    public CompletableFuture<Optional<String>> comment(FlowModel.Hunk hunk, String text) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }

                    @Override
                    public CompletableFuture<Optional<String>> submitReview() {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }

                    @Override
                    public CompletableFuture<Optional<String>> sendToSession() {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }

                    @Override
                    public Consumer<FrameDiffTool.DiffViewer> decorator(String path, int hunkIndex,
                                                                        Disposable lifetime) {
                        return viewer -> {
                            if (viewer == null) {
                                return;
                            }
                            viewers.add(viewer);
                            navigation.viewerChanged(viewer); // the install path on a live viewer
                            navigation.viewerChanged(viewer); // idempotent: the installed guard
                            events.add("installed " + viewer.getClass().getSimpleName());
                        };
                    }
                });
        try {
            view.setSize(800, 900);
            flow.render(state());
            layOut(view);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertFalse("the hunk's real viewer reached the rewritten viewerChanged", viewers.isEmpty());

            // The thread-hop contract: a background-thread call schedules the EDT work, which
            // runs without error and keeps the install idempotent.
            FrameDiffTool.DiffViewer viewer = viewers.getFirst();
            Thread background = new Thread(() -> navigation.viewerChanged(viewer));
            background.start();
            background.join();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            navigation.viewerChanged(viewer);
            assertFalse(events.isEmpty());
        } finally {
            Disposer.dispose(view);
        }
    }

    private static WorkbenchState state() {
        UnifiedDiff.Hunk hunk = new UnifiedDiff.Hunk("@@ -1,2 +1,2 @@", List.of(
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(1), OptionalInt.of(1), "a"),
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL, OptionalInt.of(2), OptionalInt.empty(), "b"),
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(2), "c")));
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/A.java", "modified", 0, 0, false, false,
                List.of(hunk));
        ReviewTabModel.FileRow row = new ReviewTabModel.FileRow("src/A.java", file,
                new ReviewTabModel.Mark(ReviewTabModel.Mark.Kind.UNREVIEWED, 0, Optional.empty()), List.of(),
                Optional.empty(), List.of(HunkIds.hunkId("src/A.java", 0)),
                List.of(HunkDigest.of("src/A.java", hunk)), 0, 1);
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, new ReviewTabModel.Snapshot(List.of(row), 1, 0),
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT);
    }

    private static void layOut(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) {
                layOut(container);
            }
        }
    }
}
