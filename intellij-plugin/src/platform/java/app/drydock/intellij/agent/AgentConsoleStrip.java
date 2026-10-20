package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.ui.SessionDots;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionListener;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The band above an agent console: what the session is doing, and the two or
 * three things to do about it.
 *
 * <p>Structure and copy come from the UX handoff; every colour and inset comes
 * from the LaF, so the strip reads as part of the IDE in any theme. The one
 * exception is the status dot, whose palette is drydock's own
 * ({@link SessionDots}).</p>
 *
 * <p>The strip holds no state of its own and derives nothing: the editor
 * passes a resolved session plus the few facts that are not on it, and every
 * string comes from {@link AgentStripText}. That split is what lets the copy
 * be tested without a running IDE.</p>
 */
final class AgentConsoleStrip extends JPanel {

    /** Roughly the handoff's 36 px, scaled with the IDE's own scaling. */
    private static final int HEIGHT = 36;

    /**
     * What the strip can ask the editor to do. {@code START} and
     * {@code RESUME} are distinct because they are different agent commands —
     * a fresh conversation versus one resumed by id — and only one of them is
     * ever offered.
     */
    enum StripAction { STOP, RESUME, START, SHOW_CHANGES, OPEN_PROJECT_WINDOW, SESSION_MENU }

    private final JBLabel state = new JBLabel();
    private final JBLabel chip = new JBLabel();
    private final JBLabel secondary = new JBLabel();
    /** {@code from <repo> ⑂ <branch>}: only on a tab hosted from another worktree. */
    private final JBLabel provenance = new JBLabel();
    private final ActionLink openWindow;
    private final ActionLink changes;
    private final JButton primary = new JButton();
    private final ActionLink more;

    /** Which action {@link #primary} currently performs; see {@link #update}. */
    private StripAction primaryAction = StripAction.START;

    AgentConsoleStrip(Consumer<StripAction> onAction) {
        // The ActionListener overload explicitly: ActionLink also takes a
        // Kotlin Function1 of the same shape, and a bare lambda is ambiguous
        // between the two.
        this.changes = new ActionLink("Changes",
                (ActionListener) event -> onAction.accept(StripAction.SHOW_CHANGES));
        this.openWindow = new ActionLink("Open project window ↗",
                (ActionListener) event -> onAction.accept(StripAction.OPEN_PROJECT_WINDOW));
        this.more = new ActionLink("⋯",
                (ActionListener) event -> onAction.accept(StripAction.SESSION_MENU));

        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBackground(UIUtil.getPanelBackground());
        setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(0, 10)));

        state.setFont(state.getFont().deriveFont(Font.BOLD));
        chip.setFont(JBUI.Fonts.smallFont());
        chip.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(0, 4)));
        secondary.setForeground(UIUtil.getContextHelpForeground());
        provenance.setForeground(UIUtil.getContextHelpForeground());
        more.setToolTipText("Done with this session…");
        primary.addActionListener(event -> onAction.accept(primaryAction));

        add(state);
        add(Box.createHorizontalStrut(JBUI.scale(8)));
        add(chip);
        add(Box.createHorizontalStrut(JBUI.scale(8)));
        add(secondary);
        add(Box.createHorizontalStrut(JBUI.scale(12)));
        add(provenance);
        add(Box.createHorizontalGlue());
        add(openWindow);
        add(Box.createHorizontalStrut(JBUI.scale(10)));
        add(changes);
        add(Box.createHorizontalStrut(JBUI.scale(10)));
        add(primary);
        add(Box.createHorizontalStrut(JBUI.scale(10)));
        add(more);
    }

    /**
     * The strip's one primary control, so a tab with no running agent can
     * hand keyboard focus to something that does anything at all — the
     * Resume button. A real focusable {@code JButton}, so Enter and Space
     * trigger it (AGENTS.md's keyboard-access rule).
     */
    JButton primaryButton() {
        return primary;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, JBUI.scale(HEIGHT));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, JBUI.scale(HEIGHT));
    }

    /**
     * Repaints the strip for {@code session}. EDT only.
     *
     * @param running          whether this window has a live process for it
     * @param readOnlyEnforced whether the agent CLI can actually hold it to reading
     * @param age              how long since the session was last started
     * @param changedFiles     files changed against the base, when known
     * @param resumable        whether the session has a recorded conversation
     *                         to resume into; when it does not, the primary
     *                         button starts a fresh one instead of offering a
     *                         Resume that could only reach a picker
     * @param estimatedTokens  the resume cost estimate, when known
     * @param indexing         whether the project is still in dumb mode
     * @param hostedFrom       where the agent comes from, when this tab hosts
     *                         it from another worktree; adds the provenance
     *                         and the jump to its own window
     * @param moving           whether the console is on its way to the
     *                         agent's own window: the strip says so and its
     *                         actions wait (typing still reaches the agent)
     */
    void update(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                boolean readOnlyEnforced, String agentName, Duration age, Optional<Integer> changedFiles,
                boolean resumable, Optional<Integer> estimatedTokens, boolean indexing,
                Optional<SessionPlace> hostedFrom, boolean moving) {
        // The window, not the catalog, is the authority on what is alive in
        // it: a row left in the running band with nothing running here reads
        // as not running (see AgentStripText.displayStatus).
        SessionStatus shown = AgentStripText.displayStatus(session.status(), running);
        state.setIcon(SessionDots.dotIcon(shown, activity));
        state.setText(SessionDots.statusWord(shown, activity));
        state.setToolTipText(SessionDots.tooltip(shown, activity));

        Optional<String> chipText = AgentStripText.readOnlyChip(session, readOnlyEnforced);
        chip.setText(chipText.orElse(""));
        chip.setVisible(chipText.isPresent());

        secondary.setText(AgentStripText.secondary(agentName, age, changedFiles, running, indexing));

        // The handoff puts Changes on the running strip only; a stopped
        // session's review is reached from the Commit tool window instead.
        changes.setVisible(running);
        // A hosted tab's Changes opens in the agent's own window (F2/F3).
        changes.setText(hostedFrom.isPresent() ? "Changes ↗" : "Changes");
        provenance.setText(hostedFrom.map(place -> moving ? JumpPlan.opening(place) : place.provenance()).orElse(""));
        provenance.setVisible(hostedFrom.isPresent());
        openWindow.setVisible(hostedFrom.isPresent());
        openWindow.setEnabled(!moving);
        changes.setEnabled(!moving);
        more.setEnabled(!moving);

        primaryAction = running ? StripAction.STOP
                : resumable ? StripAction.RESUME
                : StripAction.START;
        primary.setText(running ? "■ Deactivate" : AgentStripText.primaryLabel(resumable, estimatedTokens));
        // Nothing to resume for an agent this build cannot run: the resume
        // would pick a provider at random or fail at the command builder.
        primary.setEnabled(!moving && (running || session.status() != SessionStatus.UNSUPPORTED_AGENT));

        revalidate();
        repaint();
    }
}
