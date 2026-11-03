package app.drydock.intellij.review;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * The review diff's selection actions (the diff editor's popup menu): open
 * the inbox's composer for the selected lines, to comment on them or to
 * ask the reviewing agent about them. Visible only in a Drydock review
 * diff ({@link ReviewDiffRequests#REQUEST_KEY}).
 */
abstract class ReviewSelectionAction extends AnAction implements DumbAware {

    private final ReviewWorkbenchInbox.Composer.Mode mode;

    ReviewSelectionAction(ReviewWorkbenchInbox.Composer.Mode mode) {
        this.mode = mode;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        boolean review = reviewPath(e.getData(DiffDataKeys.DIFF_REQUEST)) != null;
        e.getPresentation().setEnabledAndVisible(review && e.getProject() != null
                && e.getData(CommonDataKeys.EDITOR) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        FrameDiffTool.DiffViewer viewer = e.getData(DiffDataKeys.DIFF_VIEWER);
        String path = reviewPath(e.getData(DiffDataKeys.DIFF_REQUEST));
        if (project == null || editor == null || viewer == null || path == null) {
            return;
        }
        ReviewTabProvider.compose(project, ReviewSelection.of(viewer, editor, path), mode);
    }

    /** The review file a request shows, or null when it is not a Drydock review diff. */
    private static String reviewPath(DiffRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getUserData(ReviewDiffRequests.REQUEST_KEY))) {
            return null;
        }
        ReviewDiffRequests.RequestContext context = request.getUserData(ReviewDiffRequests.CONTEXT_KEY);
        return context == null ? null : context.path();
    }

    /** "Comment on Selection": the composer adds a human finding at the selected lines. */
    public static final class Comment extends ReviewSelectionAction {
        public Comment() {
            super(ReviewWorkbenchInbox.Composer.Mode.COMMENT);
        }
    }

    /** "Ask Reviewer About Selection…": the composer asks the session's agent about the selected lines. */
    public static final class Ask extends ReviewSelectionAction {
        public Ask() {
            super(ReviewWorkbenchInbox.Composer.Mode.ASK);
        }
    }
}
