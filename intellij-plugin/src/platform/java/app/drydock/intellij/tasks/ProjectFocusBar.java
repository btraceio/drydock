package app.drydock.intellij.tasks;

import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.WrapLayout;

import javax.swing.JPanel;

import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.util.Optional;

/**
 * The strip under the view ribbon that says the tree shows one project only.
 * Hidden while nothing is focused. Focused, it names the project and offers
 * Show all; when the agent tab the user selected belongs to another project,
 * it says so in the warning colour and also offers to move the focus there.
 * It holds no state of its own: the panel decides what it shows. EDT.
 */
final class ProjectFocusBar extends JPanel {

    /** What the bar says: the focused project, and the selected session the focus hides, if any. */
    record State(String focusedName, Optional<Outside> outside) {
    }

    /** A selected session the focus hides, and the project it belongs to. */
    record Outside(String sessionName, String projectName) {
    }

    private final JBLabel message = new JBLabel();
    private final ActionLink switchFocus;
    private final ActionLink showAll;

    ProjectFocusBar(Runnable onSwitch, Runnable onShowAll) {
        super(new WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2)));
        switchFocus = new ActionLink("", (ActionListener) event -> onSwitch.run());
        showAll = new ActionLink("Show all", (ActionListener) event -> onShowAll.run());
        add(message);
        add(switchFocus);
        add(showAll);
        setBorder(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR));
        display(Optional.empty());
    }

    /** Shows {@code state}, or hides the bar when nothing is focused. */
    void display(Optional<State> state) {
        setVisible(state.isPresent());
        state.ifPresent(focus -> {
            Optional<Outside> outside = focus.outside();
            message.setText(message(focus));
            setBackground(outside.isPresent() ? JBUI.CurrentTheme.Banner.WARNING_BACKGROUND
                    : JBUI.CurrentTheme.Banner.INFO_BACKGROUND);
            switchFocus.setVisible(outside.isPresent());
            outside.ifPresent(o -> switchFocus.setText("Focus " + o.projectName()));
        });
        revalidate();
        repaint();
    }

    /** Pure: the bar's message for {@code state}. */
    static String message(State state) {
        return state.outside()
                .map(outside -> "“" + outside.sessionName() + "” is in " + outside.projectName()
                        + ", outside the focus on " + state.focusedName() + ".")
                .orElse("Showing " + state.focusedName() + " only.");
    }
}
