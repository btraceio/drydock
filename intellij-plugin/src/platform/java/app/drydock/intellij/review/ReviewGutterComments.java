package app.drydock.intellij.review;

import app.drydock.review.DiffSelection;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.DumbAwareAction;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.awt.EventQueue;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The review diff's gutter "+" (spec §4, "Selection → comment"): while the
 * mouse is over the gutter of a line a comment can go on, that line carries
 * a "+" icon; a click opens the inbox composer for the line, or for the
 * whole selection when the line is inside it. Installed once per viewer, its
 * listeners live as long as the viewer. EDT.
 */
final class ReviewGutterComments {

    static final String TOOLTIP = "Comment on this line, or on the selection it is in";

    private final Predicate<ReviewSelection.Target> commentable;
    private final Consumer<ReviewSelection.Target> compose;
    private final Set<FrameDiffTool.DiffViewer> installed = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * {@code commentable} says whether a line can carry a comment (it
     * resolves to a range); {@code compose} opens the composer for a target.
     */
    ReviewGutterComments(Predicate<ReviewSelection.Target> commentable, Consumer<ReviewSelection.Target> compose) {
        this.commentable = Objects.requireNonNull(commentable, "commentable");
        this.compose = Objects.requireNonNull(compose, "compose");
    }

    /** A review viewer became active: its editors get the hover "+" once. Any thread; the work hops to the EDT. */
    void viewerChanged(FrameDiffTool.DiffViewer viewer) {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater(() -> viewerChanged(viewer));
            return;
        }
        if (!(viewer instanceof DiffViewerBase base) || !installed.add(viewer)) {
            return;
        }
        if (viewer instanceof TwosideTextDiffViewer twoside) {
            install(base, twoside.getEditor(Side.LEFT), (path, line) -> line(path, DiffSelection.Side.OLD, line));
            install(base, twoside.getEditor(Side.RIGHT), (path, line) -> line(path, DiffSelection.Side.NEW, line));
        } else if (viewer instanceof UnifiedDiffViewer unified) {
            install(base, unified.getEditor(), (path, line) -> ReviewSelection.unifiedTarget(path,
                    new ReviewSelection.Span(line, line), unified::transferLineFromOnesideStrict));
        }
    }

    /** The target of one editor line, if the line maps to a side. */
    private interface LineTarget {
        Optional<ReviewSelection.Target> of(String path, int editorLine);
    }

    private static Optional<ReviewSelection.Target> line(String path, DiffSelection.Side side, int editorLine) {
        return Optional.of(new ReviewSelection.Target(path, side, editorLine + 1, editorLine + 1));
    }

    private void install(DiffViewerBase viewer, EditorEx editor, LineTarget lines) {
        if (editor == null) {
            return;
        }
        Hover hover = new Hover(viewer, editor, lines);
        editor.addEditorMouseMotionListener(hover, viewer);
    }

    /**
     * Where a "+" click comments (pure): the selection when the hovered line
     * lies inside it on the same side, else the hovered line alone.
     */
    static ReviewSelection.Target clickTarget(ReviewSelection.Target hovered, Optional<ReviewSelection.Target> selection) {
        return selection
                .filter(s -> s.path().equals(hovered.path()) && s.side() == hovered.side()
                        && s.first() <= hovered.first() && hovered.first() <= s.last())
                .orElse(hovered);
    }

    /** One editor's hover: one "+" highlighter that follows the mouse over commentable lines. */
    private final class Hover implements EditorMouseMotionListener {
        private final DiffViewerBase viewer;
        private final EditorEx editor;
        private final LineTarget lines;
        private RangeHighlighter plus;
        private int plusLine = -1;

        Hover(DiffViewerBase viewer, EditorEx editor, LineTarget lines) {
            this.viewer = viewer;
            this.editor = editor;
            this.lines = lines;
        }

        @Override
        public void mouseMoved(@NotNull EditorMouseEvent e) {
            EditorMouseEventArea area = e.getArea();
            boolean gutter = area == EditorMouseEventArea.LINE_MARKERS_AREA
                    || area == EditorMouseEventArea.LINE_NUMBERS_AREA
                    || area == EditorMouseEventArea.ANNOTATIONS_AREA;
            int line = gutter ? e.getLogicalPosition().line : -1;
            Optional<ReviewSelection.Target> target = line < 0 ? Optional.empty() : path().flatMap(p -> lines.of(p, line));
            if (target.isEmpty() || !commentable.test(target.get())) {
                clear();
                return;
            }
            if (line == plusLine && plus != null && plus.isValid()) {
                return;
            }
            clear();
            plusLine = line;
            plus = editor.getMarkupModel().addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null);
            ReviewSelection.Target hovered = target.get();
            plus.setGutterIconRenderer(new Plus(() -> compose.accept(clickTarget(hovered, selection()))));
        }

        private Optional<ReviewSelection.Target> selection() {
            return path().flatMap(p -> ReviewSelection.of(viewer, editor, p));
        }

        private Optional<String> path() {
            ContentDiffRequest request = viewer.getRequest();
            ReviewDiffRequests.RequestContext context =
                    request == null ? null : request.getUserData(ReviewDiffRequests.CONTEXT_KEY);
            return context == null ? Optional.empty() : Optional.of(context.path());
        }

        private void clear() {
            if (plus != null && plus.isValid() && !editor.isDisposed()) {
                editor.getMarkupModel().removeHighlighter(plus);
            }
            plus = null;
            plusLine = -1;
        }
    }

    /** The "+" icon; its click is the composer for the line or the selection. */
    private static final class Plus extends GutterIconRenderer {
        private final Runnable click;

        Plus(Runnable click) {
            this.click = click;
        }

        @Override public @NotNull Icon getIcon() {
            return AllIcons.General.InlineAdd;
        }

        @Override public String getTooltipText() {
            return TOOLTIP;
        }

        @Override public AnAction getClickAction() {
            return new DumbAwareAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    click.run();
                }
            };
        }

        @Override public boolean isNavigateAction() {
            return true;
        }

        @Override public boolean equals(Object other) {
            return other == this;
        }

        @Override public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
