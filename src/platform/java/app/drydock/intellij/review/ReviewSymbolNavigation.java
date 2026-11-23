package app.drydock.intellij.review;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntUnaryOperator;

/**
 * IDE symbol navigation in the review diffs (spec §4): Ctrl+Click (Cmd on
 * macOS) on an identifier opens the symbol at its definition in the IDE
 * editor, the plain platform navigation — no MCP, no server.
 *
 * <p>Resolved through the real project file only. The review's right side
 * is the live project document exactly while its text equals the reviewed
 * text ({@link ReviewDiffContents.RightSide#FILE}); a snapshot side has no
 * PSI to resolve against, and a stale request's offsets no longer address
 * the file's lines — both refuse to navigate rather than guess. A unified
 * viewer's click maps through {@code transferLineFromOnesideStrict}: only
 * lines the new side carries navigate; a line only the base side has is
 * removed code, and a definition resolved from it against the worktree
 * would be a guess. On the floor (2025.3) only the line transfer exists, so
 * the click's column is applied to the right document's line as-is.</p>
 *
 * <p>Installed once per viewer, its listeners living as long as the viewer
 * ({@link ReviewGutterComments}'s rule), on the file diff and on every
 * section card's diff. EDT.</p>
 */
final class ReviewSymbolNavigation {

    private final Set<FrameDiffTool.DiffViewer> installed = Collections.newSetFromMap(new WeakHashMap<>());

    /** A review viewer became active: its editors get the click handler once. Any thread; the work hops to the EDT. */
    void viewerChanged(FrameDiffTool.DiffViewer viewer) {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater(() -> viewerChanged(viewer));
            return;
        }
        if (Disposer.isDisposed(viewer)) {
            return; // the viewer died while the hop was pending: no editor to touch
        }
        if (!(viewer instanceof DiffViewerBase base) || installed.contains(viewer)) {
            return;
        }
        if (viewer instanceof TwosideTextDiffViewer twoside) {
            EditorEx right = twoside.getEditor(Side.RIGHT);
            if (right == null) {
                return; // no editor yet: a later viewerChanged retries the install
            }
            install(base, right, offset -> offset);
            installed.add(viewer);
        } else if (viewer instanceof UnifiedDiffViewer unified) {
            EditorEx editor = unified.getEditor();
            if (editor == null) {
                return; // no editor yet: a later viewerChanged retries the install
            }
            install(base, editor, offset -> rightOffset(unified, editor, offset));
            installed.add(viewer);
        }
    }

    /** The clicked unified-editor offset as an offset into the new text; -1 when the line is base-side only. */
    static int rightOffset(UnifiedDiffViewer unified, EditorEx editor, int offset) {
        Document oneside = editor.getDocument();
        if (offset < 0 || offset >= oneside.getTextLength()) {
            return -1;
        }
        int line = oneside.getLineNumber(offset);
        int rightLine = unified.transferLineFromOnesideStrict(Side.RIGHT, line);
        Document right = rightDocument(unified);
        if (rightLine < 0 || right == null || rightLine >= right.getLineCount()) {
            return -1;
        }
        int column = offset - oneside.getLineStartOffset(line);
        int start = right.getLineStartOffset(rightLine);
        return start + Math.min(column, right.getLineEndOffset(rightLine) - start);
    }

    /** The request's right content document, when it backs the real project file and still matches it. */
    private static @Nullable Document rightDocument(FrameDiffTool.DiffViewer viewer) {
        if (!(viewer instanceof DiffViewerBase base)
                || !(base.getRequest() instanceof ContentDiffRequest request)) {
            return null;
        }
        if (request.getUserData(ReviewDiffRequests.RIGHT_SIDE_KEY) != ReviewDiffContents.RightSide.FILE) {
            return null; // a snapshot side: no PSI to resolve against
        }
        var staleness = request.getUserData(ReviewDiffContents.STALENESS_KEY);
        if (staleness != null && staleness.isStale()) {
            return null; // the file moved on: the diff's offsets address other lines now
        }
        return request.getContents().get(Side.RIGHT.getIndex()) instanceof com.intellij.diff.contents.DocumentContent content
                ? content.getDocument() : null;
    }

    private void install(DiffViewerBase viewer, EditorEx editor, IntUnaryOperator toRightOffset) {
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override
            public void mouseClicked(@NotNull EditorMouseEvent e) {
                if (e.getArea() != EditorMouseEventArea.EDITING_AREA) {
                    return;
                }
                MouseEvent event = e.getMouseEvent();
                boolean modifier = SystemInfo.isMac ? event.isMetaDown() : event.isControlDown();
                if (!modifier) {
                    return;
                }
                int offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(event.getPoint()));
                int right = toRightOffset.applyAsInt(offset);
                if (right >= 0) {
                    navigate(viewer, editor, right);
                }
            }
        }, viewer);
    }

    /** Resolves the reference at {@code rightOffset} in the project file and opens it in the editor. EDT. */
    private static void navigate(FrameDiffTool.DiffViewer viewer, EditorEx editor, int rightOffset) {
        Project project = editor.getProject();
        Document right = rightDocument(viewer);
        if (project == null || right == null) {
            return;
        }
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(right);
        VirtualFile file = FileDocumentManager.getInstance().getFile(right);
        if (psiFile == null || file == null) {
            return;
        }
        PsiElement target = definitionAt(project, psiFile, rightOffset);
        if (target instanceof Navigatable navigatable && navigatable.canNavigate()) {
            navigatable.navigate(true);
        }
    }

    /**
     * The element the reference at {@code offset} points at, or null: nothing
     * resolvable there, or indexing is in flight ({@code resolve} in dumb
     * mode answers from stubs that a navigation would wrongly vouch for).
     * Read action: safe from the EDT and from a background read.
     */
    static @Nullable PsiElement definitionAt(@NotNull Project project, @NotNull PsiFile psiFile, int offset) {
        if (DumbService.getInstance(project).isDumb()) {
            return null;
        }
        return ReadAction.compute(() -> {
            PsiReference reference = psiFile.findReferenceAt(offset);
            return reference == null ? null : reference.resolve();
        });
    }
}
