package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.tasks.SessionBabysit;
import app.drydock.intellij.ui.SessionDots;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The band above an agent console: what the session is, and the commands
 * that apply to it — one row of borderless toolbar buttons, with the rare
 * commands behind a {@code ⋯} menu.
 *
 * <p>Structure and copy come from the UX handoff; every colour and inset comes
 * from the LaF, so the strip reads as part of the IDE in any theme. The one
 * exception is the status dot, whose palette is drydock's own
 * ({@link SessionDots}). The identity panel (state, chip, secondary,
 * provenance) takes the left; the commands sit right-aligned. The session's
 * name is not shown here: the window's tab title already names it, and a
 * second, width-capped copy in the strip only truncated itself. Renaming
 * stays with the tab popup's {@code Rename…} action. When the editor
 * is too narrow for the labels, Changes and then the lifecycle command drop
 * to icon-only (their text becomes the tooltip) so Done is never hidden (A5).
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
     * {@code SHOW_WATCHED} and {@code SHOW_BABYSITTER} are babysitting's
     * jumps to the other side of the tab's engagement.
     */
    enum StripAction { DEACTIVATE, RESUME, START, SHOW_AGENT, TRANSFER, SHOW_CHANGES, OPEN_PROJECT_WINDOW, DONE,
        SHOW_WATCHED, SHOW_BABYSITTER }

    private final JBLabel state = new JBLabel();
    private final JBLabel chip = new JBLabel();
    /**
     * The babysitting chip ({@link SessionBabysit#chipText}), monospace like
     * the inbox row's: the engagement state, and on a watched session's tab
     * the latest escalation's message while one stands.
     */
    private final JBLabel babysit = new JBLabel();
    private final JBLabel secondary = new JBLabel();
    /** {@code from <repo> ⑂ <branch>}: only on a tab hosted from another worktree. */
    private final JBLabel provenance = new JBLabel();
    /**
     * The identity block — state, chip, secondary, provenance — as the
     * wrapping ribbon's first child, so it moves as one unit and never has a
     * command wedged inside it.
     */
    private final JPanel identity = new JPanel();
    /** The editor's action handler, driving every button and menu item. */
    private final Consumer<StripAction> onAction;
    private final StripToolbarButton changes;
    private final StripToolbarButton primary = new StripToolbarButton("", null);
    private final StripToolbarButton done;
    /** The {@code ⋯} button; shown only while at least one overflow item is. */
    private final StripToolbarButton more;
    private final JComponent separator = new JComponent() {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(9), JBUI.scale(16));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(JBColor.border());
            g.fillRect(getWidth() / 2, 0, 1, getHeight());
        }
    };
    /** The right-aligned command group: Changes | primary, Done, more. */
    private final JPanel commands = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0));
    /** The overflow items, in menu order; their visibility and enablement are set by {@link #update}. */
    private final List<OverflowItem> overflow = new ArrayList<>();
    private final OverflowItem showWatched;
    private final OverflowItem showBabysitter;

    /** Which action {@link #primary} currently performs; see {@link #update}. */
    private StripAction primaryAction = StripAction.START;

    AgentConsoleStrip(Consumer<StripAction> onAction) {
        this.onAction = onAction;
        this.changes = commandButton(StripAction.SHOW_CHANGES, SessionCommands.Command.CHANGES);
        this.done = commandButton(StripAction.DONE, SessionCommands.Command.DONE);
        this.done.setToolTipText("Done with this session…");
        primary.addActionListener(event -> onAction.accept(primaryAction));
        this.more = new StripToolbarButton(AgentStripText.MORE_ACTIONS, AllIcons.Actions.More);
        more.setCompact(true);
        more.getAccessibleContext().setAccessibleName(AgentStripText.MORE_ACTIONS);
        more.addActionListener(event -> showOverflow());
        overflow.add(new OverflowItem(AgentStripText.OPEN_IN_PROJECT_WINDOW,
                SessionCommands.Command.OWN_PROJECT.icon(), StripAction.OPEN_PROJECT_WINDOW,
                "Drydock.AgentTab.OwnProject"));
        overflow.add(new OverflowItem(AgentStripText.TRANSFER_TO_PROVIDER,
                SessionCommands.Command.TRANSFER.icon(), StripAction.TRANSFER,
                "Drydock.AgentTab.Transfer"));
        showWatched = new OverflowItem(AgentTabCommands.BABYSIT_WATCHED_LABEL, AllIcons.Actions.Back,
                StripAction.SHOW_WATCHED, "Drydock.AgentTab.Babysit");
        overflow.add(showWatched);
        showBabysitter = new OverflowItem(AgentTabCommands.BABYSIT_BABYSITTER_LABEL, AllIcons.Actions.Forward,
                StripAction.SHOW_BABYSITTER, "Drydock.AgentTab.Babysit");
        overflow.add(showBabysitter);

        // One row; a narrow editor drops labels to icons (see doLayout)
        // rather than wrapping or clipping the last command (A4, A5).
        setLayout(new BorderLayout(JBUI.scale(16), 0));
        setBackground(UIUtil.getPanelBackground());
        setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(5, 12, 5, 6)));

        chip.setFont(JBUI.Fonts.smallFont());
        chip.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(0, 4)));
        // The small font's size is already scaled; only the family changes.
        babysit.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.smallFont().getSize()));
        babysit.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(0, 4)));
        babysit.setVisible(false);
        secondary.setForeground(UIUtil.getContextHelpForeground());
        provenance.setForeground(UIUtil.getContextHelpForeground());

        identity.setLayout(new BoxLayout(identity, BoxLayout.X_AXIS));
        identity.setOpaque(false);
        identity.add(state);
        identity.add(Box.createHorizontalStrut(JBUI.scale(8)));
        identity.add(chip);
        identity.add(Box.createHorizontalStrut(JBUI.scale(8)));
        identity.add(babysit);
        identity.add(Box.createHorizontalStrut(JBUI.scale(8)));
        identity.add(secondary);
        identity.add(Box.createHorizontalStrut(JBUI.scale(12)));
        identity.add(provenance);

        commands.setOpaque(false);
        commands.add(changes);
        commands.add(separator);
        commands.add(primary);
        commands.add(done);
        commands.add(more);

        add(identity, BorderLayout.CENTER);
        add(commands, BorderLayout.EAST);
    }

    /** A command button — icon and text from the shared command. */
    private StripToolbarButton commandButton(StripAction action, SessionCommands.Command command) {
        StripToolbarButton button = new StripToolbarButton(command.label(), command.icon());
        button.addActionListener(event -> onAction.accept(action));
        return button;
    }

    /**
     * One entry of the {@code ⋯} menu. Visibility and enablement are the
     * strip's {@link #update} verdicts, so the menu never decides what a
     * command's rules are. The shortcut shown, if any, is the one registered
     * for the tab action that performs the same command.
     */
    private final class OverflowItem {
        final String text;
        final Icon icon;
        final StripAction action;
        final String tabActionId;
        boolean visible;
        boolean enabled;

        OverflowItem(String text, Icon icon, StripAction action, String tabActionId) {
            this.text = text;
            this.icon = icon;
            this.action = action;
            this.tabActionId = tabActionId;
        }

        AnAction toAction() {
            AnAction item = new DumbAwareAction(text, null, icon) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent event) {
                    onAction.accept(action);
                }

                @Override
                public void update(@NotNull AnActionEvent event) {
                    event.getPresentation().setEnabled(enabled);
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }
            };
            AnAction registered = ActionManager.getInstance().getAction(tabActionId);
            if (registered != null) {
                item.copyShortcutFrom(registered);
            }
            return item;
        }
    }

    private boolean anyOverflowVisible() {
        for (OverflowItem item : overflow) {
            if (item.visible) {
                return true;
            }
        }
        return false;
    }

    /**
     * Repaints the babysitting part of the strip for {@code part}, the tab's
     * session's side of an engagement as the editor last read it: the chip,
     * and the overflow menu's jump to the other side. Empty hides both.
     * EDT only; the editor calls it after {@link #update} on every refresh.
     */
    void showBabysit(Optional<SessionBabysit> part) {
        babysit.setVisible(part.isPresent());
        part.ifPresent(found -> {
            String text = found.chipText();
            if (found.needsHuman() && found.escalation().isPresent()) {
                text += ": " + SessionBabysit.summary(found.escalation().get().message(),
                        BABYSIT_CHIP_SUMMARY_LIMIT);
            }
            babysit.setText(text);
            babysit.setIcon(found.needsHuman() ? AllIcons.General.BalloonWarning : null);
            babysit.setToolTipText(found.tooltipText());
        });
        showWatched.visible = part.filter(found -> found.role() == SessionBabysit.Role.BABYSITTER).isPresent();
        showWatched.enabled = true;
        showBabysitter.visible = part.filter(found -> found.role() == SessionBabysit.Role.WATCHED).isPresent();
        showBabysitter.enabled = true;
        more.setVisible(anyOverflowVisible());
        revalidate();
        repaint();
    }

    /** How much of an escalation's message the strip's chip carries; the tooltip has the longer form. */
    static final int BABYSIT_CHIP_SUMMARY_LIMIT = 60;

    /** The babysitting chip's text, or empty while it is hidden. */
    Optional<String> babysitChip() {
        return babysit.isVisible() ? Optional.of(babysit.getText()) : Optional.empty();
    }

    /** The labels of the overflow items currently offered, in menu order. */
    List<String> overflowItems() {
        List<String> shown = new ArrayList<>();
        for (OverflowItem item : overflow) {
            if (item.visible) {
                shown.add(item.text);
            }
        }
        return shown;
    }

    /** The {@code ⋯} button; visible only while {@link #overflowItems} is non-empty. */
    JComponent moreButton() {
        return more;
    }

    private void showOverflow() {
        DefaultActionGroup group = new DefaultActionGroup();
        for (OverflowItem item : overflow) {
            if (item.visible) {
                group.add(item.toAction());
            }
        }
        JBPopupFactory.getInstance()
                .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(more),
                        JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
                .showUnderneathOf(more);
    }

    /**
     * Fits the commands to the width: Changes drops to its icon first, then
     * the lifecycle command, before anything is clipped; Done and {@code ⋯}
     * are already icon-sized or short. The identity keeps a floor of its
     * state label; secondary text and provenance clip first.
     */
    @Override
    public void doLayout() {
        Insets insets = getInsets();
        int available = getWidth() - insets.left - insets.right;
        int floor = JBUI.scale(16) + state.getPreferredSize().width;
        int gap = JBUI.scale(2);
        boolean changesCompact = false;
        boolean primaryCompact = false;
        if (getWidth() > 0 && commandsWidth(false, false, gap) + floor > available) {
            changesCompact = true;
            if (commandsWidth(true, false, gap) + floor > available) {
                primaryCompact = true;
            }
        }
        changes.setCompact(changesCompact);
        primary.setCompact(primaryCompact);
        super.doLayout();
    }

    private int commandsWidth(boolean changesCompact, boolean primaryCompact, int gap) {
        int width = 0;
        if (changes.isVisible()) {
            width += (changesCompact ? changes.compactWidth() : changes.expandedWidth()) + gap
                    + separator.getPreferredSize().width + gap;
        }
        width += (primaryCompact ? primary.compactWidth() : primary.expandedWidth()) + gap;
        width += done.expandedWidth() + gap;
        if (more.isVisible()) {
            width += more.getPreferredSize().width + gap;
        }
        return width + gap;
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
        // height is the layout's own.
        return new Dimension(Integer.MAX_VALUE, super.getMaximumSize().height);
    }

    /**
     * Repaints the strip for {@code session}. EDT only. A row bound to a
     * workspace box paints the box name and the liveness verdict through the
     * remote rules ({@link AgentStripText#remoteStatusText}), never a local
     * activity claim; every local row keeps the presentation below unchanged.
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
        if (commands.box().isPresent()) {
            // A remote row's strip line is the box's own verdict — the box
            // name plus the liveness word (OQ10, contract component 9) —
            // never this window's local rules: the local displayStatus
            // would flatten every detached remote answer into "Not
            // running", and the activity monitor cannot see a box-side
            // agent, so its word would be a guess. `ownedHere` — this IDE
            // owns the live ssh execution, in this window or another —
            // outranks a stale liveness entry; the dot keeps the shared
            // palette through the remote mapping, with no activity folded
            // in.
            String boxName = commands.box().map(WorkspaceBoxBinding::boxName).orElseThrow();
            boolean ownedHere = running || detached;
            SessionStatus remote = AgentStripText.remoteDisplayStatus(
                    session.status(), ownedHere, commands.liveness());
            state.setIcon(SessionDots.dotIcon(remote, Optional.empty()));
            state.setText(AgentStripText.remoteStatusText(ownedHere, commands.liveness(), boxName)
                    + (detached ? " · not shown here" : ""));
            state.setToolTipText(detached
                    ? "The agent is running — just not in this window. Show agent opens its live console"
                    : AgentStripText.remoteStatusTooltip(ownedHere, commands.liveness(), boxName));
        } else if (commands.sshRemote().isPresent()) {
            // The ssh-remote arm's line: the host plus the one fact this IDE
            // has. No activity folds in (the monitor cannot see a remote
            // agent), no liveness probe runs — the agent does not outlive
            // its ssh client, so the persisted status is the whole story.
            String host = commands.sshRemote().map(SshRemote::host).orElseThrow();
            boolean ownedHere = running || detached;
            SessionStatus shown = detached ? SessionStatus.RUNNING : session.status();
            state.setIcon(SessionDots.dotIcon(shown, Optional.empty()));
            state.setText(AgentStripText.sshRemoteStatusText(ownedHere, session.status(), host)
                    + (detached ? " · not shown here" : ""));
            state.setToolTipText(detached
                    ? "The agent is running — just not in this window. Show agent opens its live console"
                    : AgentStripText.sshRemoteStatusTooltip(ownedHere, session.status(), host));
        } else {
            SessionStatus shown = detached
                    ? SessionStatus.RUNNING
                    : AgentStripText.displayStatus(session.status(), running);
            state.setIcon(SessionDots.dotIcon(shown, activity));
            state.setText(SessionDots.statusWord(shown, activity)
                    + (detached ? " · not shown here" : ""));
            state.setToolTipText(detached
                    ? "The agent is running — just not in this window. Show agent opens its live console"
                    : SessionDots.tooltip(shown, activity));
        }

        Optional<String> chipText = AgentStripText.readOnlyChip(session, readOnlyEnforced);
        chip.setText(chipText.orElse(""));
        chip.setVisible(chipText.isPresent());

        secondary.setText(AgentStripText.secondary(agentName, age, changedFiles, running, indexing));

        // The handoff puts Changes on the running strip only; a stopped
        // session's review is reached from the Commit tool window instead.
        changes.setVisible(running);
        separator.setVisible(running);
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
        OverflowItem openWindow = overflow.get(0);
        openWindow.visible = hostedFrom.isPresent();
        openWindow.enabled = SessionCommands.ownProjectEnabled(commands);

        OverflowItem transfer = overflow.get(1);
        transfer.visible = running && !commands.transferring()
                && SessionTransfer.available(session, activity);
        transfer.enabled = !commands.moving();
        more.setVisible(anyOverflowVisible());

        SessionCommands.Lifecycle lifecycle = SessionCommands.lifecycle(commands);
        primaryAction = switch (lifecycle) {
            case DEACTIVATE -> StripAction.DEACTIVATE;
            case SHOW_AGENT -> StripAction.SHOW_AGENT;
            case RESUME -> StripAction.RESUME;
            case START -> StripAction.START;
        };
        // The shared busy label while an operation is in flight; otherwise
        // the lifecycle's own words, with the strip's resume-cost copy for a
        // stopped session. A Deactivate primary always keeps its own label:
        // for a remote row the box says is ALIVE, nothing runs in this
        // window, so the not-running branch would relabel the Stop as a
        // Resume — every other not-running row keeps the resume/start copy.
        Optional<String> busy = SessionCommands.busyLabel(commands);
        primary.setText(busy.orElseGet(() -> lifecycle == SessionCommands.Lifecycle.DEACTIVATE
                || commands.running()
                        ? lifecycle.label()
                        : AgentStripText.primaryLabel(commands.resumable(), estimatedTokens)));
        primary.setIcon(lifecycle.icon());
        primary.setEnabled(SessionCommands.lifecycleEnabled(commands));
        done.setEnabled(SessionCommands.doneEnabled(commands));

        revalidate();
        repaint();
    }
}
