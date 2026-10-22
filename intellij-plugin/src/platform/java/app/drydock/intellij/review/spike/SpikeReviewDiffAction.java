package app.drydock.intellij.review.spike;

import app.drydock.git.UnifiedDiff;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.contents.DocumentContentImpl;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.diff.util.Side;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

/**
 * SPIKE (P2 spike 2): opens the CUSTOM_DIFF_COMPUTER-driven diff for every
 * changed file as a chain. See {@link SpikeReviewSupport} for the questions
 * this answers live in the sandbox IDE.
 */
final class SpikeReviewDiffAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(SpikeReviewDiffAction.class);

    private record SpikeSideContents(UnifiedDiff.FileDiff file, String base, String current) {
    }

    /** Entry point for the Review tab's double-click (spike 1 → spike 2). */
    static void openForProject(Project project) {
        run(project);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (e.getProject() != null) {
            run(e.getProject());
        }
    }

    private static void run(@NotNull Project project) {
        // The diff reads the worktree file from disk, so flush unsaved
        // buffers first or the viewer shows stale contents.
        FileDocumentManager.getInstance().saveAllDocuments();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Spike: Review Diff", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                UnifiedDiff diff = SpikeReviewSupport.branchDiff(project);
                if (diff == null || diff.files().isEmpty()) {
                    LOG.warn("SpikeReview: no branch diff for " + project.getName());
                    return;
                }
                // Snapshot the file texts off the EDT, then build documents
                // and show the diff on the EDT.
                List<SpikeSideContents> sides = new ArrayList<>();
                for (UnifiedDiff.FileDiff f : diff.files()) {
                    if (SpikeReviewSupport.isIdeNoise(f.path())) {
                        continue;
                    }
                    String base = SpikeReviewSupport.baseContent(project, f.path(), f.kind());
                    String current = SpikeReviewSupport.currentContent(project, f.path());
                    if (current == null) {
                        continue;
                    }
                    sides.add(new SpikeSideContents(f, base, current));
                }
                SwingUtilities.invokeLater(() -> show(project, sides));
            }
        });
    }

    private static void show(Project project, List<SpikeSideContents> sides) {
        List<com.intellij.diff.requests.DiffRequest> requests = new ArrayList<>();
        for (SpikeSideContents s : sides) {
            Document baseDoc = EditorFactory.getInstance().createDocument(s.base());
            Document currentDoc = EditorFactory.getInstance().createDocument(s.current());
            var fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName(s.file().path()));
            var baseContent = new DocumentContentImpl(project, baseDoc, fileType);
            var currentContent = new DocumentContentImpl(project, currentDoc, fileType);
            SimpleDiffRequest request = new SimpleDiffRequest(
                    "Spike: " + s.file().path(),
                    baseContent,
                    currentContent,
                    SpikeReviewSupport.BASE_BRANCH,
                    "Working tree");
            com.intellij.diff.util.DiffUserDataKeysEx.DiffComputer computer =
                    (text1, text2, policy, inner, indicator) -> SpikeReviewSupport.fragmentsFor(s.file());
            request.putUserData(DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER, computer);
            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
            request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
                    Pair.create(Side.RIGHT, SpikeReviewSupport.firstChangedLine(s.file())));
            // The DiffExtension matches on this key: foreign diffs stay untouched.
            request.putUserData(SpikeReviewDiffExtension.SPIKE_KEY, s.file());
            requests.add(request);
        }
        DiffManager.getInstance().showDiff(project,
                new SimpleDiffRequestChain(requests), DiffDialogHints.NON_MODAL);
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
