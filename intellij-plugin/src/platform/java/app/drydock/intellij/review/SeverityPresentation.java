package app.drydock.intellij.review;

import app.drydock.review.Severity;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.Icon;
import java.awt.Color;

/**
 * The one place a finding's severity becomes an icon, a colour or a word in the IDE:
 * the diff inlay card, the diff's gutter mark, the source editor's grouped
 * gutter mark and the Review tree's finding rows all read it here, so a
 * blocking finding looks the same wherever it shows. Platform icons only —
 * the same ones the IDE uses for its own errors, warnings and notes — so the
 * marks read as the IDE's, not as a plugin's.
 */
final class SeverityPresentation {

    private SeverityPresentation() {
    }

    /**
     * The platform icon for the severity: error, question, warning,
     * information, all at the editor's 16 px icon size. A question is the
     * IDE's context-help mark, not its message-dialog icon, which is drawn
     * at dialog size and dwarfed every tree row and gutter it was in.
     */
    static Icon icon(Severity severity) {
        return switch (severity) {
            case BLOCKING -> AllIcons.General.Error;
            case QUESTION -> AllIcons.General.ContextHelp;
            case DEVIATION -> AllIcons.General.Warning;
            case NIT -> AllIcons.General.Information;
        };
    }

    /**
     * The editor colour key whose error-stripe colour marks a finding's range
     * on the diff's scrollbar ruler: the IDE's own error, warning, weak
     * warning and information colours, so the ruler reads like the IDE's.
     */
    static TextAttributesKey stripeKey(Severity severity) {
        return switch (severity) {
            case BLOCKING -> CodeInsightColors.ERRORS_ATTRIBUTES;
            case DEVIATION -> CodeInsightColors.WARNINGS_ATTRIBUTES;
            case QUESTION -> CodeInsightColors.WEAK_WARNING_ATTRIBUTES;
            case NIT -> CodeInsightColors.INFORMATION_ATTRIBUTES;
        };
    }

    /** The severity as a capitalised word for tooltips and tags. */
    static String label(Severity severity) {
        return switch (severity) {
            case BLOCKING -> "Blocking";
            case QUESTION -> "Question";
            case DEVIATION -> "Deviation";
            case NIT -> "Nit";
        };
    }

    /**
     * The severity word's colour: the IDE's error and warning colours for
     * the two severities a reviewer must act on, the context-help grey for
     * the two that only inform. A platform named colour, so it follows the
     * theme.
     */
    static Color foreground(Severity severity) {
        return switch (severity) {
            case BLOCKING -> JBUI.CurrentTheme.Label.errorForeground();
            case DEVIATION -> JBUI.CurrentTheme.Label.warningForeground();
            case QUESTION, NIT -> UIUtil.getContextHelpForeground();
        };
    }
}
