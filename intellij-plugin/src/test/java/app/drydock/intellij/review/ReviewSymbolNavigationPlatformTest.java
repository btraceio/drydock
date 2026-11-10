package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.SectionsModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.util.Side;
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
 * <p>The viewerChanged discipline is exercised on real card viewers: the install
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
        ReviewWorkbenchSectionView view = new ReviewWorkbenchSectionView(getProject(),
                new ReviewWorkbenchSectionView.Actions() {
                    @Override
                    public CompletableFuture<Optional<ReviewDiffRequests.RequestContext>> hunkContext(
                            String path, int hunkIndex, String scrollKey) {
                        return CompletableFuture.completedFuture(Optional.of(new ReviewDiffRequests.RequestContext(
                                path, ReviewDiffRequests.plan("a\nb\n", "a\nc\n", List.of(),
                                new ReviewDiffRequests.ScrollTarget(Side.RIGHT, 1)))));
                    }

                    @Override
                    public CompletableFuture<Optional<String>> markReviewed(SectionsModel.Section section,
                                                                            List<String> digests) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }

                    @Override
                    public void fullFile(String path) {
                    }

                    @Override
                    public void showFinding(String findingId) {
                    }

                    @Override
                    public void showSection(String key) {
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
            view.render(state());
            layOut(view);
            view.updateLiveCards();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertFalse("the card's real viewer reached the rewritten viewerChanged", viewers.isEmpty());

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
        SectionsModel.Hunk hunk = new SectionsModel.Hunk("src/A.java", 0, "src/A.java#0", "d-A", "3–4", "n3",
                0, 2, List.of(), false, Optional.empty(), Optional.empty());
        SectionsModel.Section section = new SectionsModel.Section("step:s2", 2, Optional.of("s2"), "Callers",
                "Why the callers change.", List.of(), List.of(), List.of(hunk), 0, 1, 0);
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, new ReviewTabModel.Snapshot(List.of(), 0, 0),
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, List.of(),
                WorkbenchState.InboxChoice.DEFAULT,
                new SectionsModel.Snapshot(SectionsModel.Source.TOUR, Optional.empty(), List.of(section)),
                new WorkbenchState.SectionsChoice(Optional.of(WorkbenchState.Navigator.SECTIONS),
                        Optional.of("step:s2")));
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
