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
import com.intellij.util.ui.WrapLayout;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The band above an agent console: what the session is, and the commands
 * that apply to it — a wrapping ribbon of icon+text buttons rather than a
 * single clipped line (A4).
 *
 * <p>Structure and copy come from the UX handoff; every colour and inset comes
 * from the LaF, so the strip reads as part of the IDE in any theme. The one
 * exception is the status dot, whose palette is drydock's own
 * ({@link SessionDots}). The first child is one identity panel (title, state,
 * chip, secondary, provenance) so the session stays readable as a unit; the
 * commands after it wrap onto further rows instead of ever hiding Done (A5).
 * Which command exists, which is clickable and what the lifecycle one says
 * are not decided here — {@link SessionCommands} decides for every surface,
 * and the strip only places the buttons.</p>
 *
 * <p>The strip holds no state of its own and derives nothing: the editor
 * passes a resolved session plus the few facts that are not on it, and every
 * string comes from {@link AgentStripText}. That split is what lets the copy
 * be tested without a running IDE.</p>
 */
final class AgentConsoleStrip extends JPanel {

    /**
     * What the strip can ask the editor to do. {@code START} and
     * {@code RESUME} are distinct because they are different agent commands —
     * a fresh conversation versus one resumed by id — and only one of them is
     * ever offered. {@code DEACTIVATE}, {@code SHOW_AGENT} and {@code DONE}
     * share the names of their {@link SessionCommands} counterparts so the
     * strip and the tab surfaces cannot drift apart; {@code SHOW_AGENT} is
     * the detached tab's reconnect (A6) — the agent runs, just not in this
     * window, and the command reaches it without starting anything.
     */
    enum StripAction { DEACTIVATE, RESUME, START, SHOW_AGENT, TRANSFER, RENAME, SHOW_CHANGES, OPEN_PROJECT_WINDOW, DONE }

    private final ActionLink title;
    private final JBLabel state = new JBLabel();
    private final JBLabel chip = new JBLabel();
    private final JBLabel secondary = new JBLabel();
    /** {@code from <repo> ⑂ <branch>}: only on a tab hosted from another worktree. */
    private final JBLabel provenance = new JBLabel();
    /**
     * The identity block — title, state, chip, secondary, provenance — as the
     * wrapping ribbon's first child, so it moves as one unit and never has a
     * command wedged inside it.
     */
    private final JPanel identity = new JPanel();
    /** The inline rename editor; created on first use, swapped in over {@link #title}. */
    private final JTextField renameField = new JTextField();
    /**
     * The rename commit: {@code pin} is true for an explicit Enter (which
     * claims the name against agent renames even when the text is unchanged)
     * and false for a focus-loss commit. Set by the owning editor.
     */
    private BiConsumer<String, Boolean> onRenamed = (name, pin) -> { };
    /** Single-click vs double-click disambiguation on the title link. */
    private Timer singleClickDialog;
    /** The title as the rename editor must restore it; kept in {@link #update}. */
    private String titleText = "";
    /** The editor's action handler, kept for the title link's single click. */
    private final Consumer<StripAction> onAction;
    private final JButton openWindow;
    private final JButton changes;
    private final JButton transfer;
    private final JButton primary = new JButton();
    private final JButton done;

    /** Which action {@link #primary} currently performs; see {@link #update}. */
    private StripAction primaryAction = StripAction.START;

    AgentConsoleStrip(Consumer<StripAction> onAction) {
        this.onAction = onAction;
        // The ActionListener overload explicitly: ActionLink also takes a
        // Kotlin Function1 of the same shape, and a bare lambda is ambiguous
        // between the two.
        // The link's own click handling is NOT used: single and double clicks
        // must be told apart (single opens the rename dialog, double turns
        // the title into an inline editor), and an ActionLink fires on every
        // click of a double-click pair.
        this.title = new ActionLink("", (ActionListener) event -> { });
        installTitleClicks();
        this.openWindow = commandButton(StripAction.OPEN_PROJECT_WINDOW,
                SessionCommands.Command.OWN_PROJECT);
        this.changes = commandButton(StripAction.SHOW_CHANGES, SessionCommands.Command.CHANGES);
        this.transfer = commandButton(StripAction.TRANSFER, SessionCommands.Command.TRANSFER);
        this.done = commandButton(StripAction.DONE, SessionCommands.Command.DONE);
        this.done.setToolTipText("Done with this session…");
        primary.setMargin(JBUI.insets(1, 6));
        primary.addActionListener(event -> onAction.accept(primaryAction));

        // WrapLayout, not a fixed-height single row: a narrow editor grows a
        // second row instead of clipping the last command (A4, A5).
        setLayout(new WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)));
        setBackground(UIUtil.getPanelBackground());
        setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(0, 10)));

        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setToolTipText("Rename session");
        title.setMinimumSize(new Dimension(JBUI.scale(40), 0));
        title.setMaximumSize(new Dimension(JBUI.scale(220), Integer.MAX_VALUE));
        chip.setFont(JBUI.Fonts.smallFont());
        chip.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(0, 4)));
        secondary.setForeground(UIUtil.getContextHelpForeground());
        provenance.setForeground(UIUtil.getContextHelpForeground());

        identity.setLayout(new BoxLayout(identity, BoxLayout.X_AXIS));
        identity.setOpaque(false);
        identity.add(title);
        identity.add(Box.createHorizontalStrut(JBUI.scale(10)));
        identity.add(state);
        identity.add(Box.createHorizontalStrut(JBUI.scale(8)));
        identity.add(chip);
        identity.add(Box.createHorizontalStrut(JBUI.scale(8)));
        identity.add(secondary);
        identity.add(Box.createHorizontalStrut(JBUI.scale(12)));
        identity.add(provenance);

        add(identity);
        add(openWindow);
        add(changes);
        add(transfer);
        add(primary);
        add(done);
    }

    /** A command button — icon and text from the shared command, compact margins. */
    private JButton commandButton(StripAction action, SessionCommands.Command command) {
        JButton button = new JButton(command.label(), command.icon());
        button.setMargin(JBUI.insets(1, 6));
        button.addActionListener(event -> onAction.accept(action));
        return button;
    }

    /** The rename commit, wired by the owning editor; see {@link #onRenamed}. */
    void setOnRenamed(BiConsumer<String, Boolean> onRenamed) {
        this.onRenamed = onRenamed;
    }

    /**
     * Single click on the title opens the rename dialog (after a short
     * double-click window, so a second click can still claim the gesture);
     * double click swaps the title for an inline editor.
     */
    private void installTitleClicks() {
        title.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                if (event.getClickCount() == 2) {
                    cancelSingleClickDialog();
                    startInlineRename();
                } else if (event.getClickCount() == 1) {
                    cancelSingleClickDialog();
                    singleClickDialog = new Timer(280, ignored -> onAction.accept(StripAction.RENAME));
                    singleClickDialog.setRepeats(false);
                    singleClickDialog.start();
                }
            }
        });
        // Enter is an explicit confirm: it pins, even when the text is
        // unchanged -- a human who opened the editor, read the agent's title
        // and pressed Enter has claimed that name.
        renameField.addActionListener((ActionEvent event) -> commitInlineRename(true));
        renameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                // Focus loss is not a confirm. An agent's console opening and
                // selecting its tab can steal focus, so pinning here would
                // let an agent pin a human's session at will; commit(false)
                // still applies a changed name, preserving any earlier pin.
                commitInlineRename(false);
            }
        });
        renameField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    event.consume();
                    endInlineRename();
                }
            }
        });
    }

    private void cancelSingleClickDialog() {
        if (singleClickDialog != null) {
            singleClickDialog.stop();
            singleClickDialog = null;
        }
    }

    /** EDT: swaps the title for an inline editor; Enter pins, blur commits, Esc cancels. */
    private void startInlineRename() {
        if (renameField.getParent() == identity) {
            return;
        }
        cancelSingleClickDialog();
        renameField.setText(titleText);
        renameField.setFont(title.getFont());
        renameField.setColumns(16);
        int at = identity.getComponentZOrder(title);
        identity.remove(title);
        identity.add(renameField, at);
        revalidate();
        repaint();
        renameField.requestFocusInWindow();
        renameField.selectAll();
    }

    /** EDT: restores the title; a changed name is committed with the caller's pin. */
    private void commitInlineRename(boolean pin) {
        if (renameField.getParent() != identity) {
            return;
        }
        String newName = renameField.getText() == null ? "" : renameField.getText().strip();
        endInlineRename();
        // Empty text cancels on both paths: the human rename applies no
        // emptiness filter of its own, and committing one would blank the
        // title permanently -- and, on the pin path, pin the blank.
        if (!newName.isEmpty() && (pin || !newName.equals(titleText))) {
            onRenamed.accept(newName, pin);
        }
    }

    private void endInlineRename() {
        cancelSingleClickDialog();
        int at = identity.getComponentZOrder(renameField);
        identity.remove(renameField);
        identity.add(title, at);
        revalidate();
        repaint();
    }

    /**
     * The strip's one primary control, so a tab with no running agent can
     * hand keyboard focus to something that does anything at all — the
     * lifecycle button. A real focusable {@code JButton}, so Enter and Space
     * trigger it (AGENTS.md's keyboard-access rule).
     */
    JButton primaryButton() {
        return primary;
    }

    @Override
    public Dimension getMaximumSize() {
        // Width stays unbounded so the strip stretches with the editor; the
        // height is the layout's own — WrapLayout wraps onto another row
        // instead of being clipped to one.
        return new Dimension(Integer.MAX_VALUE, super.getMaximumSize().height);
    }

    /**
     * Repaints the strip for {@code session}. EDT only.
     *
     * @param activity         the live process's current activity, when running
     * @param running          whether this window has a live process for it
     * @param readOnlyEnforced whether the agent CLI can actually hold it to reading
     * @param agentName        the agent's display name, for the secondary line
     * @param age              how long since the session was last started
     * @param changedFiles     files changed against the base, when known
     * @param estimatedTokens  the resume cost estimate, when known
     * @param indexing          whether the project is still in dumb mode
     * @param hostedFrom       where the agent comes from, when this tab hosts
     *                         it from another worktree; adds the provenance
     *                         and the jump to its own window
     * @param folderMissing    whether a hosted agent's worktree folder is gone
     * @param checkoutGone     whether the session's checkout directory is gone
     *                         or is not a git checkout — the Changes button
     *                         could resolve no review there, so it is disabled
     *                         with a tooltip saying so rather than clicking
     *                         into nothing
     * @param commands        the session's command state: what is clickable
     *                         while an operation is in flight, and which
     *                         lifecycle command applies — decided once in
     *                         {@link SessionCommands} for the strip, the tab
     *                         header and the tree alike
     */
    void update(ManagedAgentSession session, Optional<SessionActivity> activity, boolean running,
                boolean readOnlyEnforced, String agentName, Duration age, Optional<Integer> changedFiles,
                Optional<Integer> estimatedTokens, boolean indexing,
                Optional<SessionPlace> hostedFrom, boolean folderMissing, boolean checkoutGone,
                SessionCommands.State commands) {
        // The window, not the catalog, is the authority on what is alive in
        // it (see AgentStripText.displayStatus). A detached tab must not read
        // that authority as "stopped": the agent is live somewhere — another
        // window, or none at all — so the dot and the word stay live and the
        // qualifier says where it is not, instead of a hollow dot and "Not
        // running" implying the globally live agent stopped (A6).
        boolean detached = commands.detachedFromThisWindow();
        SessionStatus shown = detached
                ? SessionStatus.RUNNING
                : AgentStripText.displayStatus(session.status(), running);
        titleText = session.displayName();
        title.setText(session.displayName());
        title.setToolTipText("Rename \"" + session.displayName() + "\"");
        state.setIcon(SessionDots.dotIcon(shown, activity));
        state.setText(SessionDots.statusWord(shown, activity)
                + (detached ? " · not shown here" : ""));
        state.setToolTipText(detached
                ? "The agent is running — just not in this window. Show agent opens its live console"
                : SessionDots.tooltip(shown, activity));

        Optional<String> chipText = AgentStripText.readOnlyChip(session, readOnlyEnforced);
        chip.setText(chipText.orElse(""));
        chip.setVisible(chipText.isPresent());

        secondary.setText(AgentStripText.secondary(agentName, age, changedFiles, running, indexing));

        // The handoff puts Changes on the running strip only; a stopped
        // session's review is reached from the Commit tool window instead.
        changes.setVisible(running);
        // A hosted tab's Changes opens in the agent's own window (F2/F3).
        changes.setText(hostedFrom.isPresent() ? "Changes ↗" : "Changes");
        // A checkout that is gone (a deleted worktree, or a directory that
        // no longer holds a git checkout) cannot produce a review: the
        // button says so instead of clicking into a silent nothing.
        changes.setEnabled(!commands.moving() && !checkoutGone);
        changes.setToolTipText(checkoutGone
                ? "The session's checkout is gone or is not a git repository"
                : null);
        provenance.setText(hostedFrom.map(place -> commands.moving() ? JumpPlan.opening(place)
                : place.provenance() + (folderMissing ? " · folder missing" : "")).orElse(""));
        provenance.setVisible(hostedFrom.isPresent());
        // A jump only exists for a tab hosting another worktree's agent;
        // the owning window already is the project window (A8).
        openWindow.setVisible(hostedFrom.isPresent());
        openWindow.setEnabled(SessionCommands.ownProjectEnabled(commands));

        transfer.setVisible(running && !commands.transferring()
                && SessionTransfer.available(session, activity));
        transfer.setEnabled(!commands.moving());

        SessionCommands.Lifecycle lifecycle = SessionCommands.lifecycle(commands);
        primaryAction = switch (lifecycle) {
            case DEACTIVATE -> StripAction.DEACTIVATE;
            case SHOW_AGENT -> StripAction.SHOW_AGENT;
            case RESUME -> StripAction.RESUME;
            case START -> StripAction.START;
        };
        // The shared busy label while an operation is in flight; otherwise
        // the lifecycle's own words, with the strip's resume-cost copy for a
        // stopped session.
        Optional<String> busy = SessionCommands.busyLabel(commands);
        primary.setText(busy.orElseGet(() -> commands.running()
                ? lifecycle.label()
                : AgentStripText.primaryLabel(commands.resumable(), estimatedTokens)));
        primary.setIcon(lifecycle.icon());
        primary.setEnabled(SessionCommands.lifecycleEnabled(commands));
        done.setEnabled(SessionCommands.doneEnabled(commands));

        revalidate();
        repaint();
    }
}
