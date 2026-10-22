package app.drydock.intellij.review.spike;

import com.intellij.diff.DiffExtension;
import com.intellij.diff.DiffContext;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.Key;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import app.drydock.git.UnifiedDiff;

import java.awt.Font;
import java.util.List;

/**
 * SPIKE (P2 spike 2): decorates only spike-tagged diff requests with
 * changed-line highlights, drawn after the viewer's own line transfer. This
 * verifies live that decorations placed through {@code transferLineToOneside}
 * (unified) and {@code getEditor(Side)} (two-side) land on the right lines
 * in both viewer modes -- the route P3 findings and inner-word highlights
 * would take.
 */
final class SpikeReviewDiffExtension extends DiffExtension {

    private static final Logger LOG = Logger.getInstance(SpikeReviewDiffExtension.class);

    /** Marks requests this spike created; foreign diffs never match. */
    static final Key<UnifiedDiff.FileDiff> SPIKE_KEY = Key.create("drydock.spike.review.fileDiff");

    private static final TextAttributes CHANGED_LINE = new TextAttributes();

    static {
        CHANGED_LINE.setBackgroundColor(new JBColor(new java.awt.Color(0xFF, 0xF8, 0xE1),
                new java.awt.Color(0x3B, 0x38, 0x2C)));
        CHANGED_LINE.setEffectColor(new JBColor(new java.awt.Color(0xE6, 0xA2, 0x3C),
                new java.awt.Color(0x8C, 0x6C, 0x2E)));
        CHANGED_LINE.setEffectType(EffectType.SEARCH_MATCH);
        CHANGED_LINE.setFontType(Font.ITALIC);
    }

    @Override
    public void onViewerCreated(@NotNull FrameDiffTool.DiffViewer viewer, @NotNull DiffContext context,
                                @NotNull DiffRequest request) {
        UnifiedDiff.FileDiff file = request.getUserData(SPIKE_KEY);
        if (file == null) {
            return;
        }
        List<LineFragment> fragments = SpikeReviewSupport.fragmentsFor(file);
        if (viewer instanceof UnifiedDiffViewer unified) {
            Editor editor = unified.getEditor();
            for (LineFragment fragment : fragments) {
                highlightNewSide(editor, unified.transferLineToOneside(Side.RIGHT, fragment.getStartLine2()),
                        unified.transferLineToOneside(Side.RIGHT, Math.max(fragment.getEndLine2() - 1,
                                fragment.getStartLine2())));
            }
            LOG.info("SpikeReview: highlighted " + fragments.size()
                    + " fragment(s) in the unified viewer for " + file.path());
        } else if (viewer instanceof TwosideTextDiffViewer twoside) {
            for (LineFragment fragment : fragments) {
                highlightNewSide(twoside.getEditor(Side.RIGHT), fragment.getStartLine2(),
                        Math.max(fragment.getEndLine2() - 1, fragment.getStartLine2()));
            }
            LOG.info("SpikeReview: highlighted " + fragments.size()
                    + " fragment(s) in the two-side viewer for " + file.path());
        }
    }

    private static void highlightNewSide(Editor editor, int firstLine, int lastLine) {
        for (int line = firstLine; line <= lastLine; line++) {
            if (line < 0 || line >= editor.getDocument().getLineCount()) {
                LOG.warn("SpikeReview: transferred line out of range: " + line);
                continue;
            }
            int start = editor.getDocument().getLineStartOffset(line);
            int end = editor.getDocument().getLineEndOffset(line);
            editor.getMarkupModel().addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1,
                    CHANGED_LINE, HighlighterTargetArea.EXACT_RANGE);
        }
    }
}
