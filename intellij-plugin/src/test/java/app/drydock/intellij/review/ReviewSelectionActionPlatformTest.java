package app.drydock.intellij.review;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.util.Side;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Arrays;
import java.util.List;

/** The review diff's selection action: registered in the diff editor's popup, shown only in a review diff. */
public class ReviewSelectionActionPlatformTest extends BasePlatformTestCase {

    public void testCommentOnSelectionIsInTheDiffEditorPopup() {
        AnAction action = ActionManager.getInstance().getAction("Drydock.Review.CommentOnSelection");
        assertNotNull("registered", action);
        AnAction group = ActionManager.getInstance().getAction("Diff.EditorPopupMenu");
        assertNotNull("the platform's diff editor popup group exists", group);
        List<String> ids = Arrays.stream(((DefaultActionGroup) group).getChildActionsOrStubs())
                .map(child -> ActionManager.getInstance().getId(child)).toList();
        assertTrue(ids.toString(), ids.contains("Drydock.Review.CommentOnSelection"));
    }

    public void testItShowsOnlyInADrydockReviewDiffWithAnEditor() {
        AnAction action = new ReviewSelectionAction.Comment();
        Editor editor = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument("a\n"),
                getProject());
        try {
            SimpleDiffRequest review = request();
            ReviewDiffRequests.applyUserData(review, new ReviewDiffRequests.RequestContext("A.java",
                    new ReviewDiffRequests.Plan("a\n", "a\n", List.of(), Side.RIGHT, 0)));
            assertTrue(visible(action, review, editor));
            assertFalse("another diff", visible(action, request(), editor));
            assertFalse("no editor", visible(action, review, null));
        } finally {
            EditorFactory.getInstance().releaseEditor(editor);
        }
    }

    private boolean visible(AnAction action, SimpleDiffRequest request, Editor editor) {
        SimpleDataContext.Builder data = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(DiffDataKeys.DIFF_REQUEST, request);
        if (editor != null) {
            data.add(CommonDataKeys.EDITOR, editor);
        }
        AnActionEvent event = TestActionEvent.createTestEvent(action, data.build());
        action.update(event);
        return event.getPresentation().isEnabledAndVisible();
    }

    private static SimpleDiffRequest request() {
        return new SimpleDiffRequest("A.java", DiffContentFactory.getInstance().createEmpty(),
                DiffContentFactory.getInstance().createEmpty(), null, null);
    }
}
