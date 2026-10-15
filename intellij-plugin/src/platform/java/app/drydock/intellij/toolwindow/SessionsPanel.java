package app.drydock.intellij.toolwindow;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionStatus;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.intellij.session.DrydockSessionService;
import app.drydock.intellij.terminal.SessionTabRegistry;
import app.drydock.intellij.terminal.TerminalSessionLauncher;
import app.drydock.intellij.worktree.WorktreeDialogs;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.ui.AgentLabels;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
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
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Drydock tool window's one content tab (T16, the combined-view
 * redesign): a single list of everything one can start a session on — the
 * repository's sessions (dot + agent + name + status; focus/delete actions
 * unchanged from T12) under a "Sessions" header, every <em>live</em>
 * worktree (the main checkout included — a session on the main checkout is
 * valid) under "Worktrees", and the stale entries (git says {@code
 * prunable}: the directory is gone, only git metadata remains) under
 * "Purgable", each purgable with one click and no confirmation — pruning
 * clears metadata only, never data.
 *
 * <p>A detached-HEAD worktree (directory exists, no branch) is a live
 * worktree: it holds real data, so its removal stays the confirmed
 * "Force Remove…" with the data-loss warning; only directory-less stale
 * entries are one-click purgable.</p>
 *
 * <p>Threading: everything the panel itself does runs on the EDT. The two
 * data sources refresh coalesced and independently — session state via the
 * {@link SessionListModel}'s event-driven rebuild (N events, one rebuild),
 * the worktree listing via the absorbed load state machine (a refresh that
 * arrives while a load is in flight collapses into the one reload that
 * follows; N triggers, at most two loads). Both git loads and the
 * purge/remove actions run on the {@link WorktreeService}'s background
 * executor and hop back with {@code invokeLater}; the status line is text
 * only (no spinner — an indefinite animation whose lifecycle the panel does
 * not own would violate the AGENTS.md UI lifecycle rule), and every
 * completion path — success, failure, early return — replaces it, so
 * progress is always cleared.</p>
 *
 * <p>Pure seams: {@link WorktreeRow}, {@link #rowsOf}, {@link #detailOf}
 * (absorbed verbatim from the deleted {@code WorktreeListPanel}, whose
 * tests move with them) and {@link #entriesOf} — the section grouping from
 * plain inputs (the session rows, the raw {@code git worktree list}
 * listing), so "sessions / worktrees / purgable per a given state" is
 * pinned without Swing or the IDE.</p>
 */
public final class SessionsPanel extends JPanel {

    private static final Logger LOG = System.getLogger(SessionsPanel.class.getName());

    /** Sessions live as tabs of the standard Terminal tool window (plan Q7). */
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";

    /** Bounded wait for the delete; a wedged store must not pin a progress task. */
    private static final long DELETE_TIMEOUT_SECONDS = 30;

    private static final String TOOLBAR_GROUP_ID = "Drydock.SessionToolbar";
    private static final String TOOLBAR_PLACE = "Drydock.SessionList";

    private static final String SESSIONS_SECTION = "Sessions";
    private static final String WORKTREES_SECTION = "Worktrees";
    private static final String PURGABLE_SECTION = "Purgable";

    /**
     * The live panel per project, so {@link #refreshFor} can coalesce the
     * post-create/post-checkout refresh into the panel that is already
     * mounted. Weak: a closed project must not pin its panel.
     */
    private static final Map<Project, SessionsPanel> LIVE = new WeakHashMap<>();

    private final Project project;
    private final AgentRegistry agents;
    private final SessionListModel model;
    private final DrydockSessionService sessions;
    private final SessionTabRegistry tabs;
    private final Path repositoryRoot;

    /** One listing service for the panel's lifetime; closed on project disposal. */
    private final WorktreeService worktrees = new WorktreeService();

    private final CombinedAdapter adapter;

    private final JBList<Entry> list;
    private final JPanel statusLine = new JPanel(new BorderLayout(8, 0));
    private final JLabel status = new JLabel("Loading worktrees…");

    /** The last worktree listing (EDT; loads land via invokeLater). */
    private List<Worktree> worktreeListing = List.of();
    /** True while a {@code WorktreeService.list} load is in flight (EDT). */
    private boolean loadingWorktrees;
    /** A refresh requested while a load was in flight — the coalescer. */
    private boolean worktreeRefreshPending;

    SessionsPanel(Project project, AgentRegistry agents, Repository repository, SessionListModel model,
            DrydockSessionService sessions, SessionTabRegistry tabs) {
        super(new BorderLayout());
        this.project = project;
        this.agents = agents;
        this.model = model;
        this.sessions = sessions;
        this.tabs = tabs;
        this.repositoryRoot = repository.root();
        this.adapter = new CombinedAdapter();
        this.list = new JBList<>(adapter);
        list.setCellRenderer(new EntryRenderer());
        list.setEmptyText("No sessions or worktrees for this repository yet.");
        statusLine.setBorder(BorderFactory.createEmptyBorder(2, 6, 6, 6));
        statusLine.add(status, BorderLayout.CENTER);
        add(north(), BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
        wireRowActions();
        // Rebuild when the tool window is shown again: sessions and
        // worktrees created elsewhere (the toolbar's dialogs, plain git in
        // a terminal) otherwise wait for the next lifecycle event.
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                requestRefresh();
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
                // Nothing to stop — the model coalesces onto the EDT and
                // the panel owns no animation.
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
                // Movement alone changes nothing.
            }
        });
        registerLive();
        loadWorktrees();
    }

    // ---- toolbar + status -------------------------------------------------------

    private JComponent north() {
        ActionManager actions = ActionManager.getInstance();
        // The group is declared in plugin.xml (T11) and the dialogs
        // register themselves into it; rendering it while empty is the
        // contract: this panel never hardcodes them.
        AnAction action = actions.getAction(TOOLBAR_GROUP_ID);
        DefaultActionGroup group = action instanceof DefaultActionGroup declared ? declared : new DefaultActionGroup();
        ActionToolbar toolbar = actions.createActionToolbar(TOOLBAR_PLACE, group, true);
        toolbar.setTargetComponent(this);
        JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar.getComponent(), BorderLayout.NORTH);
        north.add(statusLine, BorderLayout.CENTER);
        return north;
    }

    // ---- pure seams (no Swing, no IDE) -----------------------------------------

    /**
     * One rendered worktree row: the worktree as git reported it, whether
     * it is the project the user is in ({@code current}, which disables
     * that row's own "Open as project" action) and the rendered detail
     * line. Absorbed verbatim from the deleted {@code WorktreeListPanel}.
     */
    public record WorktreeRow(Worktree worktree, boolean current, String detail) {

        /** The worktree's directory. */
        public Path path() {
            return worktree().path();
        }

        /**
         * Whether "Force Remove…" may run on this row: never on the main
         * checkout — it is the repository itself.
         */
        public boolean removable() {
            return !worktree().mainCheckout();
        }
    }

    /**
     * The panel's worktree rows from a listing (absorbed from the deleted
     * {@code WorktreeListPanel}): git's order is kept — the main checkout
     * first, the worktrees in git's order — and each row carries whether
     * it is the project the user is in and the rendered detail line.
     */
    public static List<WorktreeRow> rowsOf(List<Worktree> worktrees, Path currentProjectRoot) {
        List<WorktreeRow> rows = new ArrayList<>(worktrees.size());
        for (Worktree worktree : worktrees) {
            rows.add(new WorktreeRow(worktree, worktree.path().equals(currentProjectRoot), detailOf(worktree)));
        }
        return rows;
    }

    /**
     * One worktree's detail line (absorbed from the deleted {@code
     * WorktreeListPanel}): the branch (or why there is none), then the
     * markers whose <em>escapes differ</em> — a locked worktree needs
     * {@code git worktree unlock}, a stale (prunable) one needs
     * {@code git worktree prune}, which silently skips a locked one — so
     * the words stay apart exactly as {@link Worktree}'s fields do.
     */
    public static String detailOf(Worktree worktree) {
        List<String> parts = new ArrayList<>();
        if (worktree.branch().isPresent()) {
            parts.add(worktree.branch().orElseThrow());
        } else if (worktree.detached()) {
            parts.add("detached");
        } else {
            parts.add("no branch");
        }
        if (worktree.mainCheckout()) {
            parts.add("main checkout");
        }
        if (worktree.prunable()) {
            parts.add("stale");
        }
        if (worktree.locked()) {
            parts.add("locked" + worktree.lockReason().map(reason -> ": " + reason).orElse(""));
        }
        return String.join("  ·  ", parts);
    }

    /** The combined list's flat row shape: which section a row renders in. */
    public record Entry(Kind kind, SessionListModel.Row session, WorktreeRow worktree, String title) {

        /** The section a row belongs to; also the renderer's dispatch key. */
        public enum Kind { SECTION, SESSION, WORKTREE, STALE }

        static Entry section(String title) {
            return new Entry(Kind.SECTION, null, null, title);
        }

        static Entry session(SessionListModel.Row row) {
            return new Entry(Kind.SESSION, row, null, null);
        }

        static Entry worktree(WorktreeRow row) {
            return new Entry(Kind.WORKTREE, null, row, null);
        }

        static Entry stale(WorktreeRow row) {
            return new Entry(Kind.STALE, null, row, null);
        }
    }

    /**
     * The combined list's rows from plain inputs (T16's grouping seam):
     * the session rows under a "Sessions" header, every non-stale
     * worktree — the main checkout included — under "Worktrees", and the
     * stale (git-prunable) entries under "Purgable". A section header is
     * emitted only when the section has rows, so the list never carries
     * empty headers.
     */
    public static List<Entry> entriesOf(List<SessionListModel.Row> sessions, List<Worktree> worktrees,
            Path currentProjectRoot) {
        List<Entry> entries = new ArrayList<>();
        if (!sessions.isEmpty()) {
            entries.add(Entry.section(SESSIONS_SECTION));
            for (SessionListModel.Row session : sessions) {
                entries.add(Entry.session(session));
            }
        }
        List<WorktreeRow> live = new ArrayList<>();
        List<WorktreeRow> stale = new ArrayList<>();
        for (WorktreeRow row : rowsOf(worktrees, currentProjectRoot)) {
            (row.worktree().prunable() ? stale : live).add(row);
        }
        if (!live.isEmpty()) {
            entries.add(Entry.section(WORKTREES_SECTION));
            for (WorktreeRow row : live) {
                entries.add(Entry.worktree(row));
            }
        }
        if (!stale.isEmpty()) {
            entries.add(Entry.section(PURGABLE_SECTION));
            for (WorktreeRow row : stale) {
                entries.add(Entry.stale(row));
            }
        }
        return List.copyOf(entries);
    }

    // ---- refresh (coalesced, EDT-state) -----------------------------------------

    /**
     * Asks this project's live panel to refresh (from any thread — e.g.
     * the checkout action's background completion); collapses to nothing
     * when no panel is mounted. The EDT hop keeps the refresh state
     * machines single-threaded.
     */
    public static void refreshFor(Project project) {
        SessionsPanel panel;
        synchronized (LIVE) {
            panel = LIVE.get(project);
        }
        if (panel != null) {
            ApplicationManager.getApplication().invokeLater(panel::requestRefresh, ModalityState.any());
        }
    }

    /** A refresh trigger (EDT): both sources, each coalesced its own way. */
    void requestRefresh() {
        model.requestRefresh();
        refreshWorktrees();
    }

    private void refreshWorktrees() {
        if (loadingWorktrees) {
            worktreeRefreshPending = true; // N triggers collapse into one reload
            return;
        }
        loadWorktrees();
    }

    private void loadWorktrees() {
        loadingWorktrees = true;
        setStatus("Listing worktrees…");
        worktrees.list(repositoryRoot).whenComplete((listing, failure) ->
                ApplicationManager.getApplication().invokeLater(
                        () -> onWorktreesListed(listing, failure), ModalityState.any()));
    }

    private void onWorktreesListed(List<Worktree> listing, Throwable failure) {
        loadingWorktrees = false;
        if (project.isDisposed()) {
            return; // the service closes with the project; nothing left to render
        }
        if (failure != null) {
            // The listing failure surfaces as visible text carrying git's
            // specifics — a failed list must not render as "none" (AC26).
            LOG.log(Level.WARNING, "Listing worktrees failed: " + rootCause(failure));
            setStatus("Could not list worktrees: " + WorktreeErrors.visibleMessage(failure));
        } else {
            worktreeListing = List.copyOf(listing);
            adapter.worktreesChanged();
            setStatus(summaryOf(model.rows().size(), worktreeListing));
        }
        if (worktreeRefreshPending) {
            worktreeRefreshPending = false; // the coalesced follow-up load
            loadWorktrees();
        }
    }

    /** The quiet line under the toolbar once a load lands: counts, no noise. */
    private static String summaryOf(int sessionCount, List<Worktree> worktrees) {
        int stale = 0;
        for (Worktree worktree : worktrees) {
            if (worktree.prunable()) {
                stale++;
            }
        }
        StringBuilder summary = new StringBuilder();
        summary.append(sessionCount).append(sessionCount == 1 ? " session" : " sessions");
        summary.append(" · ").append(worktrees.size() - stale)
                .append(worktrees.size() - stale == 1 ? " checkout" : " checkouts");
        if (stale > 0) {
            summary.append(" · ").append(stale).append(stale == 1 ? " stale worktree, purgable"
                    : " stale worktrees, purgable");
        }
        return summary.toString();
    }

    // ---- row interaction ---------------------------------------------------------

    private void wireRowActions() {
        InputMap inputs = list.getInputMap();
        inputs.put(KeyStroke.getKeyStroke("ENTER"), "activateRow");
        ActionMap actions = list.getActionMap();
        actions.put("activateRow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                activateSelectedRow();
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
                    activateSelectedRow();
                }
            }
        });
    }

    /** The row under the given index, or null when there is none. */
    private Entry entryAt(int index) {
        if (index < 0 || index >= adapter.getSize()) {
            return null;
        }
        return adapter.getElementAt(index);
    }

    private void activateSelectedRow() {
        Entry entry = entryAt(list.getSelectedIndex());
        if (entry != null) {
            activate(entry);
        }
    }

    /** The list's primary gesture per section: focus, open, purge. */
    private void activate(Entry entry) {
        switch (entry.kind()) {
            case SESSION -> focusSession(entry.session());
            case WORKTREE -> openAsProject(entry.worktree());
            case STALE -> purgeStale();
            case SECTION -> {
                // Headers carry no primary action; the Purgable header's
                // context menu holds "Purge All Stale".
            }
        }
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = list.locationToIndex(e.getPoint());
        Entry entry = entryAt(index);
        if (entry == null) {
            return;
        }
        list.setSelectedIndex(index);
        showRowPopup(entry, e.getX(), e.getY());
    }

    private void showRowPopup(Entry entry, int x, int y) {
        switch (entry.kind()) {
            case SESSION -> sessionPopup(entry.session(), x, y);
            case WORKTREE -> worktreePopup(entry.worktree(), x, y);
            case STALE -> stalePopup(x, y);
            case SECTION -> sectionPopup(entry, x, y);
        }
    }

    private void sessionPopup(SessionListModel.Row row, int x, int y) {
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

    private void worktreePopup(WorktreeRow row, int x, int y) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem open = new JMenuItem("Open as Project");
        open.setEnabled(!row.current());
        open.addActionListener(event -> openAsProject(row));
        popup.add(open);
        // A session for a worktree without opening it as a project first:
        // the row's directory keys everything — the repository it
        // registers under (the worktree's own, R7) and the terminal tab's
        // cwd.
        JMenuItem sessionHere = new JMenuItem("New Session Here…");
        sessionHere.addActionListener(event -> SessionDialogs.showNewSession(project, row.path()));
        popup.add(sessionHere);
        JMenuItem remove = new JMenuItem("Force Remove…");
        // The main checkout is the repository itself — never removable.
        remove.setEnabled(row.removable());
        remove.addActionListener(event -> forceRemove(row));
        popup.add(remove);
        popup.addSeparator();
        JMenuItem refresh = new JMenuItem("Refresh");
        refresh.addActionListener(event -> requestRefresh());
        popup.add(refresh);
        popup.show(list, x, y);
    }

    /**
     * The purgable row's one-click action: no confirmation dialog, because
     * there is nothing to lose — the directory is already gone from disk
     * and pruning clears git metadata only (the branch is kept; it
     * outlives its worktree).
     */
    private void stalePopup(int x, int y) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem purge = new JMenuItem("Purge");
        purge.addActionListener(event -> purgeStale());
        popup.add(purge);
        popup.addSeparator();
        JMenuItem refresh = new JMenuItem("Refresh");
        refresh.addActionListener(event -> requestRefresh());
        popup.add(refresh);
        popup.show(list, x, y);
    }

    /** The section headers' menus: only the Purgable section carries one. */
    private void sectionPopup(Entry entry, int x, int y) {
        if (!PURGABLE_SECTION.equals(entry.title())) {
            return;
        }
        JPopupMenu popup = new JPopupMenu();
        JMenuItem purgeAll = new JMenuItem("Purge All Stale");
        purgeAll.addActionListener(event -> purgeStale());
        popup.add(purgeAll);
        popup.show(list, x, y);
    }

    // ---- session actions (T12, unchanged) ----------------------------------------

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

    // ---- worktree actions (absorbed from WorktreeListPanel) ----------------------

    /**
     * The worktree row's primary action (AC25): the one open path reused —
     * a new project frame for the worktree, whether it was just created or
     * has existed for weeks. This row's own project is not offered (it is
     * already open).
     */
    private void openAsProject(WorktreeRow row) {
        if (row.current()) {
            return;
        }
        WorktreeDialogs.openAsProject(row.path());
    }

    /**
     * Force-removes an unneeded worktree
     * ({@code git worktree remove --force}) after an explicit confirmation.
     * Only git refuses what it must (the main checkout is unreachable here —
     * the action is disabled on its row — and the service refuses it again).
     */
    private void forceRemove(WorktreeRow row) {
        int confirmed = Messages.showYesNoDialog(this,
                "Force-remove the worktree at\n" + row.path() + "\n\nUncommitted changes in it are discarded.",
                "Force Remove Worktree", Messages.getQuestionIcon());
        if (confirmed != Messages.YES) {
            return;
        }
        setStatus("Removing " + row.path().getFileName() + "…");
        worktrees.removeForced(repositoryRoot, row.path(), row.worktree().branch())
                .whenComplete((removed, failure) -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (failure != null) {
                        setStatus("Could not remove the worktree: " + WorktreeErrors.visibleMessage(failure));
                        Messages.showErrorDialog(this,
                                WorktreeErrors.visibleMessage(failure), "Force Remove Worktree");
                    } else {
                        setStatus("Removed " + row.path().getFileName() + ".");
                        refreshWorktrees();
                    }
                }, ModalityState.any()));
    }

    /**
     * Purges the stale worktree entries: {@code git worktree prune} via the
     * core's {@link WorktreeService#prune}. Git's prune has no
     * per-worktree form — one pass clears every prunable entry — so both
     * the row's "Purge" and the section's "Purge All Stale" land here and
     * clear the whole Purgable section; the branches those entries held
     * are kept by design (pruning is metadata-only, which is exactly why
     * it needs no confirmation). No progress dialog: the status line
     * carries the busy text and every completion path replaces it.
     */
    private void purgeStale() {
        setStatus("Purging stale worktrees…");
        worktrees.prune(repositoryRoot).whenComplete((unused, failure) ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (failure != null) {
                        setStatus("Could not purge stale worktrees: " + WorktreeErrors.visibleMessage(failure));
                        Messages.showErrorDialog(this,
                                WorktreeErrors.visibleMessage(failure), "Purge Stale Worktrees");
                    } else {
                        setStatus("Purged stale worktree metadata.");
                        refreshWorktrees();
                    }
                }, ModalityState.any()));
    }

    // ---- the combined list model ---------------------------------------------------

    /**
     * Bridges the two sources into one Swing list model: the session rows
     * from {@link SessionListModel} (whose coalesced rebuild fires {@link
     * #rowsChanged}) and the worktree rows from the panel's last listing
     * ({@link #worktreesChanged} after a load lands). Both notifications
     * arrive on the EDT, so Swing events never leave it; the rebuild always
     * recomputes the whole flat list from the two current snapshots —
     * one pass, not one pass per source.
     */
    private final class CombinedAdapter extends AbstractListModel<Entry> implements SessionListModel.Listener {

        private List<Entry> rows = List.of();

        CombinedAdapter() {
            model.addListener(this);
        }

        @Override
        public int getSize() {
            return rows.size();
        }

        @Override
        public Entry getElementAt(int index) {
            return rows.get(index);
        }

        @Override
        public void rowsChanged() {
            rebuild();
        }

        void worktreesChanged() {
            rebuild();
        }

        private void rebuild() {
            rows = entriesOf(model.rows(), worktreeListing, repositoryRoot);
            fireContentsChanged(this, 0, Math.max(rows.size() - 1, 0));
        }
    }

    // ---- rendering ---------------------------------------------------------------

    /** One renderer, three cell components dispatched by {@link Entry.Kind}. */
    private final class EntryRenderer implements ListCellRenderer<Entry> {

        private final HeaderCell header = new HeaderCell();
        private final SessionCell session = new SessionCell();
        private final WorktreeCell worktree = new WorktreeCell();

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry, int index,
                boolean selected, boolean focused) {
            return switch (entry.kind()) {
                case SECTION -> header.forEntry(entry);
                case SESSION -> session.forEntry(list, entry.session(), selected);
                case WORKTREE, STALE -> worktree.forEntry(list, entry.worktree(), selected);
            };
        }
    }

    /** A section header: the title in bold, spaced off the rows above it. */
    private static final class HeaderCell extends JPanel {

        private final JLabel title = new JLabel();

        HeaderCell() {
            super(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(9, 4, 1, 4));
            add(title, BorderLayout.CENTER);
        }

        Component forEntry(Entry entry) {
            title.setText(entry.title());
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            setOpaque(false);
            return this;
        }
    }

    /** One session row: dot, agent label + display name, status text at the right edge. */
    private final class SessionCell extends JPanel {

        private final JLabel dot = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel status = new JLabel();

        SessionCell() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
            JPanel start = new JPanel(new BorderLayout(6, 0));
            start.setOpaque(false);
            start.add(dot, BorderLayout.WEST);
            start.add(name, BorderLayout.CENTER);
            add(start, BorderLayout.CENTER);
            add(status, BorderLayout.EAST);
        }

        Component forEntry(JList<? extends Entry> list, SessionListModel.Row row, boolean selected) {
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
                status.setForeground(dimmed(list));
            }
            return this;
        }
    }

    /** One worktree row: directory name at the left, the detail line dimmed at the right. */
    private static final class WorktreeCell extends JPanel {

        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        WorktreeCell() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
            add(name, BorderLayout.CENTER);
            add(detail, BorderLayout.EAST);
        }

        Component forEntry(JList<? extends Entry> list, WorktreeRow row, boolean selected) {
            name.setText(row.path().getFileName() == null ? row.path().toString()
                    : row.path().getFileName().toString());
            detail.setText(row.detail());
            setToolTipText(row.path() + (row.current() ? "  (this project)" : ""));
            setOpaque(selected);
            if (selected) {
                setBackground(list.getSelectionBackground());
                name.setForeground(list.getSelectionForeground());
                detail.setForeground(list.getSelectionForeground());
            } else {
                setBackground(null);
                name.setForeground(list.getForeground());
                detail.setForeground(dimmed(list));
            }
            return this;
        }
    }

    private static Color dimmed(JList<?> list) {
        Color hint = UIManager.getColor("Label.disabledForeground");
        return hint != null ? hint : list.getForeground();
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

    // ---- status line ------------------------------------------------------------

    /** Shows the message (text only, never a spinner — see the class javadoc). */
    private void setStatus(String message) {
        status.setText(message);
        statusLine.revalidate();
        statusLine.repaint();
    }

    // ---- lifecycle ------------------------------------------------------------

    private void registerLive() {
        synchronized (LIVE) {
            LIVE.put(project, this);
        }
        Disposer.register(project, () -> {
            synchronized (LIVE) {
                LIVE.remove(project, this);
            }
            worktrees.close();
        });
    }

    private static Throwable rootCause(Throwable failure) {
        return failure instanceof CompletionException layered && layered.getCause() != null
                ? rootCause(layered.getCause())
                : failure;
    }
}
