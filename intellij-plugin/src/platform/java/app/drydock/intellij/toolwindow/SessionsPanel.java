package app.drydock.intellij.toolwindow;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.session.DrydockSessionService;
import app.drydock.intellij.terminal.SessionTabRegistry;
import app.drydock.intellij.terminal.TerminalSessionLauncher;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;

import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.AbstractListModel;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Drydock tool window's session list (task T12): one row per session of
 * the project's repository — a static status dot ({@link SessionDots}; no
 * animation, ever), the agent label, the display name, and the status text —
 * with a toolbar built from the shared {@code Drydock.SessionToolbar} action
 * group (T13 appends its actions to it; the group is rendered even while
 * empty) and per-row actions: focus the session's terminal tab and delete
 * the session.
 *
 * <p>Threading: everything the panel itself does runs on the EDT; the one
 * slow operation — deleting a session's metadata — runs as a background task
 * whose lifecycle shows and always clears visible progress (success, failure,
 * and early return alike, AC12). The data model ({@link SessionListModel})
 * refreshes coalesced on the EDT and only notifies on actual change, so
 * repaint traffic never scales with event count.</p>
 */
public final class SessionsPanel extends JPanel {

    /** Sessions live as tabs of the standard Terminal tool window (plan Q7). */
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";

    /** Bounded wait for the delete; a wedged store must not pin a progress task. */
    private static final long DELETE_TIMEOUT_SECONDS = 30;

    private static final String TOOLBAR_GROUP_ID = "Drydock.SessionToolbar";
    private static final String TOOLBAR_PLACE = "Drydock.SessionList";

    private final Project project;
    private final AgentRegistry agents;
    private final SessionListModel model;
    private final DrydockSessionService sessions;
    private final SessionTabRegistry tabs;

    private final JBList<SessionListModel.Row> list;

    SessionsPanel(Project project, AgentRegistry agents, SessionListModel model,
            DrydockSessionService sessions, SessionTabRegistry tabs) {
        super(new BorderLayout());
        this.project = project;
        this.agents = agents;
        this.model = model;
        this.sessions = sessions;
        this.tabs = tabs;
        this.list = new JBList<>(new RowListAdapter(model));
        list.setCellRenderer(new RowRenderer());
        list.setEmptyText("No sessions for this repository yet.");
        add(toolbar(), BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
        wireRowActions();
        // Rebuild when the tool window is shown again: sessions created
        // elsewhere (the toolbar's dialogs, another tool window action)
        // otherwise wait for the next lifecycle event.
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                model.requestRefresh();
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
                // Nothing to stop — the model only coalesces onto the EDT.
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
                // Movement alone changes nothing.
            }
        });
    }

    // ---- toolbar ---------------------------------------------------------------

    private JComponent toolbar() {
        ActionManager actions = ActionManager.getInstance();
        // The group is declared in plugin.xml (T11) and starts empty; T13
        // appends its actions. Rendering it while empty is the contract:
        // the dialogs register themselves, this panel never hardcodes them.
        AnAction action = actions.getAction(TOOLBAR_GROUP_ID);
        DefaultActionGroup group = action instanceof DefaultActionGroup declared ? declared : new DefaultActionGroup();
        ActionToolbar toolbar = actions.createActionToolbar(TOOLBAR_PLACE, group, true);
        toolbar.setTargetComponent(this);
        return toolbar.getComponent();
    }

    // ---- row interaction ---------------------------------------------------------

    private void wireRowActions() {
        InputMap inputs = list.getInputMap();
        inputs.put(KeyStroke.getKeyStroke("ENTER"), "focusSelectedSession");
        ActionMap actions = list.getActionMap();
        actions.put("focusSelectedSession", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                focusSelectedRow();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    focusSelectedRow();
                }
            }
        });
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = list.locationToIndex(e.getPoint());
        if (index < 0 || index >= model.rows().size()) {
            return;
        }
        list.setSelectedIndex(index);
        showRowPopup(model.rows().get(index), e.getX(), e.getY());
    }

    private void showRowPopup(SessionListModel.Row row, int x, int y) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem focus = new JMenuItem("Focus Terminal Tab");
        focus.setEnabled(!tabs.tabsFor(row.session().id()).isEmpty());
        focus.addActionListener(event -> focusSession(row));
        popup.add(focus);
        JMenuItem delete = new JMenuItem("Delete Session…");
        delete.addActionListener(event -> deleteSession(row));
        popup.add(delete);
        popup.show(list, x, y);
    }

    private void focusSelectedRow() {
        int index = list.getSelectedIndex();
        if (index < 0 || index >= model.rows().size()) {
            return;
        }
        focusSession(model.rows().get(index));
    }

    /**
     * Activates the Terminal tool window with the session's tab selected —
     * the list's primary action (Q7: the Drydock window lists and focuses;
     * the sessions themselves live in Terminal).
     */
    private void focusSession(SessionListModel.Row row) {
        List<TerminalSessionLauncher.TerminalSessionTab> liveTabs = tabs.tabsFor(row.session().id());
        if (liveTabs.isEmpty()) {
            return; // nothing to focus; the row's dot already says so
        }
        TerminalToolWindowTab ideTab = liveTabs.get(0).tab();
        ToolWindow terminal = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
        if (terminal == null) {
            return;
        }
        // The selection runs in the activation callback so the tool window
        // (and its content manager) is initialized before it is mutated.
        terminal.activate(() -> {
            if (ideTab.getContent() != null) {
                terminal.getContentManager().setSelectedContent(ideTab.getContent());
            }
        });
    }

    /**
     * Deletes a session (AC12): a confirmation dialog, then — with visible
     * progress — close the live tab (the IDE's tab close terminates the
     * agent process, AC13) and remove the metadata. The agent's own on-disk
     * conversation history is never touched.
     */
    private void deleteSession(SessionListModel.Row row) {
        ManagedAgentSession session = row.session();
        String question = "Delete the session \"" + session.displayName() + "\"?\n"
                + "A still-running agent is stopped with its terminal tab; Drydock forgets the session.\n"
                + "The agent's own conversation history on disk is kept.";
        if (Messages.showYesNoDialog(this, question, "Delete Session", Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        // Close live tabs first, on the EDT: the IDE's own tab close
        // terminates the processes (the lifecycle monitor turns that into
        // the INACTIVE record).
        for (TerminalSessionLauncher.TerminalSessionTab tab : tabs.tabsFor(session.id())) {
            tab.close();
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(
                project, "Deleting session " + session.displayName() + "…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    sessions.deleteSession(session.id()).get(DELETE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException e) {
                    throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
                }
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Messages.showErrorDialog(SessionsPanel.this,
                        "Deleting the session failed: " + error.getMessage(), "Delete Session");
            }

            @Override
            public void onFinished() {
                // Success and failure alike: the progress is cleared here,
                // and the row leaves the list once the deletion landed.
                model.requestRefresh();
            }
        });
    }

    // ---- rendering ---------------------------------------------------------------

    /**
     * Bridges the pure model into a Swing list model. The model notifies on
     * the EDT (its coalesced refresh executor is the panel's EDT hop), and
     * the bridge only forwards — so Swing events never leave the EDT.
     */
    private static final class RowListAdapter extends AbstractListModel<SessionListModel.Row>
            implements SessionListModel.Listener {

        private final SessionListModel model;

        RowListAdapter(SessionListModel model) {
            this.model = model;
            model.addListener(this);
        }

        @Override
        public int getSize() {
            return model.rows().size();
        }

        @Override
        public SessionListModel.Row getElementAt(int index) {
            return model.rows().get(index);
        }

        @Override
        public void rowsChanged() {
            fireContentsChanged(this, 0, Math.max(getSize() - 1, 0));
        }
    }

    /** One row: dot, agent label + display name, status text at the right edge. */
    private final class RowRenderer extends JPanel implements ListCellRenderer<SessionListModel.Row> {

        private final JLabel dot = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel status = new JLabel();

        RowRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
            JPanel start = new JPanel(new BorderLayout(6, 0));
            start.setOpaque(false);
            start.add(dot, BorderLayout.WEST);
            start.add(name, BorderLayout.CENTER);
            add(start, BorderLayout.CENTER);
            add(status, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SessionListModel.Row> list,
                SessionListModel.Row row, int index, boolean selected, boolean focused) {
            ManagedAgentSession session = row.session();
            dot.setIcon(SessionDots.dotIcon(session.status(), row.activity()));
            name.setText(agentLabel(session) + " — " + session.displayName());
            status.setText(SessionDots.statusText(session.status(), row.activity()));
            setToolTipText(SessionDots.tooltip(session.status(), row.activity()));
            setOpaque(selected);
            if (selected) {
                setBackground(list.getSelectionBackground());
                name.setForeground(list.getSelectionForeground());
                status.setForeground(list.getSelectionForeground());
            } else {
                setBackground(null);
                name.setForeground(list.getForeground());
                status.setForeground(dimmed());
            }
            return this;
        }

        private Color dimmed() {
            Color hint = UIManager.getColor("Label.disabledForeground");
            return hint != null ? hint : list.getForeground();
        }
    }

    /**
     * The agent's name for a row. Mirrors {@code AgentLabels}'s
     * session-aware rule (its overload is package-private in the app tree):
     * an UNSUPPORTED_AGENT session's kind is only a placeholder, so the one
     * name known to be wrong is never shown.
     */
    private String agentLabel(ManagedAgentSession session) {
        return session.status() == SessionStatus.UNSUPPORTED_AGENT
                ? "Unknown agent"
                : AgentLabels.displayName(agents, session.agentKind());
    }
}
