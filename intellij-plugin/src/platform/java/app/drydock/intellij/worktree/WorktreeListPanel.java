package app.drydock.intellij.worktree;

import app.drydock.domain.Repository;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import app.drydock.intellij.actions.SessionDialogs;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import com.intellij.ui.components.JBScrollPane;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionException;

/**
 * The Drydock tool window's second content tab (T15, AC25 last sentence):
 * one row per worktree of the project's repository, from the core's
 * {@link WorktreeService#list} — never a second porcelain parser — with a
 * per-row "Open as project" action that reuses T14's open path ({@link
 * WorktreeDialogs#openAsProject}; a worktree opened as its own project then
 * lists that worktree's own sessions, not the main checkout's, which is the
 * rest of AC25).
 *
 * <p>Threading: the panel's own state is touched only on the EDT; the git
 * listing runs on the {@link WorktreeService}'s background executor, and
 * completions hop back with {@code invokeLater}. Refresh is coalesced, not
 * per-event (AGENTS rebuild-the-world rule): a refresh request that arrives
 * while a load is in flight sets a pending flag and collapses into the
 * <em>one</em> reload that runs when the in-flight load lands — N triggers,
 * at most two reloads, never a queue per event. The initial warm-up (plugin
 * init + repository resolution) runs as futures off the EDT and shows
 * progress that every completion path — success, failure, non-git early
 * return — clears (AC12).</p>
 *
 * <p>Wiring: the tab is installed by the nested {@link Installer}, a
 * project-level {@link ToolWindowManagerListener} registered in plugin.xml
 * that adds this panel's content the first time the Drydock tool window is
 * shown. That keeps the entire T15 surface inside this file plus the
 * plugin.xml entry it owns; the sessions factory (T11/T12) is not touched.</p>
 *
 * <p>Pure seams: {@link #rowsOf} and {@link #detailOf} build the rendered
 * rows from a plain {@code List<Worktree>} with no Swing or IDE dependency —
 * {@code WorktreeListPanelTest} drives them against a real temporary git
 * fixture following the core {@code WorktreeServiceTest} pattern.</p>
 */
public final class WorktreeListPanel extends JPanel {

    private static final Logger LOG = System.getLogger(WorktreeListPanel.class.getName());

    private static final String DRYDOCK_TOOL_WINDOW_ID = "Drydock";
    private static final String TAB_NAME = "Worktrees";

    /**
     * The live panel per project, so {@link #refreshFor} can coalesce the
     * post-checkout refresh into the panel that is already mounted. Weak:
     * a closed project must not pin its panel.
     */
    private static final Map<Project, WorktreeListPanel> LIVE = new WeakHashMap<>();

    private final Project project;

    /** One listing service for the panel's lifetime; closed on project disposal. */
    private final WorktreeService worktrees = new WorktreeService();

    private final DefaultListModel<Row> listModel = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(listModel);
    private final JPanel statusLine = new JPanel(new BorderLayout(8, 0));
    private final JLabel status = new JLabel("Loading worktrees…");

    /** True once the repository resolution completed (EDT). */
    private boolean warm;
    /** True while a {@code WorktreeService.list} load is in flight (EDT). */
    private boolean loading;
    /** A refresh requested while {@link #loading} was true — the coalescer. */
    private boolean refreshPending;
    /** The project's repository root once resolved; null before (EDT). */
    private Path repositoryRoot;

    public WorktreeListPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        list.setCellRenderer(new RowRenderer());
        list.setVisibleRowCount(8);
        wireRowActions();
        statusLine.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        beginBusy("Loading worktrees…");
        add(statusLine, BorderLayout.NORTH);
        add(new JBScrollPane(list), BorderLayout.CENTER);
        // Rebuild when the tool window shows this tab again: worktrees
        // created elsewhere (the checkout action, plain git in a terminal)
        // otherwise wait for the next explicit refresh.
        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                requestRefresh();
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
                // Nothing to stop — refreshes are coalesced onto the EDT.
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
                // Movement alone changes nothing.
            }
        });
        registerLive();
        resolveRepository();
    }

    // ---- pure seams (no Swing, no IDE) -----------------------------------------

    /** One rendered row: the worktree as git reported it, plus view flags. */
    public record Row(Worktree worktree, boolean current, String detail) {

        /** The worktree's directory. */
        public Path path() {
            return worktree.path();
        }
    }

    /**
     * The panel's rows from a worktree listing (AC25): git's order is kept —
     * the main checkout first, the worktrees in git's order — and each row
     * carries whether it is the project the user is in ({@code current},
     * which disables that row's own "Open as project" action) and the
     * rendered detail line.
     */
    public static List<Row> rowsOf(List<Worktree> worktrees, Path currentProjectRoot) {
        List<Row> rows = new ArrayList<>(worktrees.size());
        for (Worktree worktree : worktrees) {
            rows.add(new Row(worktree, worktree.path().equals(currentProjectRoot), detailOf(worktree)));
        }
        return rows;
    }

    /**
     * One worktree's detail line: the branch (or why there is none), then
     * the markers whose <em>escapes differ</em> — a locked worktree needs
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

    // ---- refresh (coalesced, EDT-state) -----------------------------------------

    /**
     * Asks this project's live panel to refresh (from any thread — e.g. the
     * checkout action's background completion); collapses to nothing when
     * no panel is mounted. The EDT hop keeps {@link #requestRefresh}'s
     * state machine single-threaded.
     */
    public static void refreshFor(Project project) {
        WorktreeListPanel panel;
        synchronized (LIVE) {
            panel = LIVE.get(project);
        }
        if (panel != null) {
            ApplicationManager.getApplication().invokeLater(panel::requestRefresh, ModalityState.any());
        }
    }

    /** A refresh trigger (EDT). Coalesced: while a load is in flight, at most one more is queued. */
    void requestRefresh() {
        if (!warm) {
            return; // the warm-up completion starts the first load
        }
        if (loading) {
            refreshPending = true; // N triggers collapse into one reload
            return;
        }
        startLoad();
    }

    /** The warm-up: plugin init, then this project's repository (both off-EDT). */
    private void resolveRepository() {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        DrydockStartupActivity.warmup(project)
                .thenComposeAsync(unused -> DrydockStartupActivity.repositories(project).projectRepository(project),
                        plugin.executor())
                .whenCompleteAsync((repository, failure) -> ApplicationManager.getApplication().invokeLater(
                        () -> onRepositoryResolved(repository, failure), ModalityState.any()), plugin.executor());
    }

    private void onRepositoryResolved(Repository repository, Throwable failure) {
        if (project.isDisposed()) {
            return;
        }
        if (failure != null) {
            LOG.log(Level.WARNING, "Worktree list warm-up failed: " + rootCause(failure));
            endBusy("Drydock did not finish loading: " + rootCause(failure).getMessage());
            return;
        }
        if (repository == null) {
            // Default and scratch projects have no base directory; the
            // non-git early return is a message, not an error (AC12 still
            // clears the progress).
            endBusy("This project is not a git repository; Drydock lists worktrees per repository.");
            return;
        }
        repositoryRoot = repository.root();
        warm = true;
        startLoad();
    }

    private void startLoad() {
        loading = true;
        beginBusy("Listing worktrees…");
        worktrees.list(repositoryRoot).whenComplete((listing, failure) ->
                ApplicationManager.getApplication().invokeLater(
                        () -> onListed(listing, failure), ModalityState.any()));
    }

    private void onListed(List<Worktree> listing, Throwable failure) {
        loading = false;
        if (project.isDisposed()) {
            return; // the service closes with the project; nothing left to render
        }
        if (failure != null) {
            // AC26: the listing failure surfaces as visible text carrying
            // git's specifics — a failed list must not render as "none".
            LOG.log(Level.WARNING, "Listing worktrees failed: " + rootCause(failure));
            endBusy("Could not list worktrees: " + WorktreeErrors.visibleMessage(failure));
        } else {
            List<Row> rows = rowsOf(listing, repositoryRoot);
            listModel.clear();
            rows.forEach(listModel::addElement);
            endBusy(rows.isEmpty()
                    ? "No worktrees for this repository yet."
                    : rows.size() == 1 ? "One checkout — this repository. New Worktree… adds another."
                            : rows.size() + " checkouts.");
        }
        if (refreshPending) {
            refreshPending = false; // the coalesced follow-up load
            startLoad();
        }
    }

    // ---- row interaction ---------------------------------------------------------

    private void wireRowActions() {
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
                    openSelectedAsProject();
                }
            }
        });
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = list.locationToIndex(e.getPoint());
        if (index < 0 || index >= listModel.size()) {
            return;
        }
        list.setSelectedIndex(index);
        Row row = listModel.get(index);
        JPopupMenu popup = new JPopupMenu();
        JMenuItem open = new JMenuItem("Open as Project");
        open.setEnabled(!row.current());
        open.addActionListener(event -> openAsProject(row));
        popup.add(open);
        // The direct path the milestone cut as manual-optional: a session
        // for a worktree without opening it as a project first. The row's
        // directory keys everything — the repository it registers under
        // (the worktree's own, R7) and the terminal tab's cwd.
        JMenuItem sessionHere = new JMenuItem("New Session Here…");
        sessionHere.addActionListener(event ->
                SessionDialogs.showNewSession(project, row.path()));
        popup.add(sessionHere);
        JMenuItem remove = new JMenuItem("Force Remove…");
        // The main checkout is the repository itself — never removable.
        remove.setEnabled(!row.worktree().mainCheckout());
        remove.addActionListener(event -> forceRemove(row));
        popup.add(remove);
        JMenuItem refresh = new JMenuItem("Refresh");
        refresh.addActionListener(event -> requestRefresh());
        popup.add(refresh);
        popup.show(list, e.getX(), e.getY());
    }

    private void openSelectedAsProject() {
        int index = list.getSelectedIndex();
        if (index < 0 || index >= listModel.size()) {
            return;
        }
        openAsProject(listModel.get(index));
    }

    /**
     * The row's primary action (AC25): T14's open path, reused — one
     * way to open a worktree as its own project in a new frame, whether it
     * was just created or has existed for weeks. This row's own project is
     * not offered (it is already open).
     */
    private void openAsProject(Row row) {
        if (row.current()) {
            return;
        }
        WorktreeDialogs.openAsProject(row.path());
    }

    /**
     * Force-removes a stale or unneeded worktree
     * ({@code git worktree remove --force}) after an explicit confirmation.
     * Only git refuses what it must (the main checkout is unreachable here —
     * the action is disabled on its row — and the service refuses it again).
     * Stale/prunable entries (a directory deleted on disk) are exactly what
     * this exists for; they surface in the list as "stale".
     */
    private void forceRemove(Row row) {
        int confirmed = Messages.showYesNoDialog(this,
                "Force-remove the worktree at\n" + row.path() + "\n\nUncommitted changes in it are discarded.",
                "Force Remove Worktree", Messages.getQuestionIcon());
        if (confirmed != Messages.YES) {
            return;
        }
        beginBusy("Removing " + row.path().getFileName() + "…");
        worktrees.removeForced(repositoryRoot, row.path(), row.worktree().branch())
                .whenComplete((removed, failure) -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (failure != null) {
                        endBusy("Could not remove the worktree: " + WorktreeErrors.visibleMessage(failure));
                        Messages.showErrorDialog(this,
                                WorktreeErrors.visibleMessage(failure), "Force Remove Worktree");
                    } else {
                        endBusy("Removed " + row.path().getFileName() + ".");
                        requestRefresh();
                    }
                }, ModalityState.any()));
    }

    // ---- rendering ---------------------------------------------------------------

    /** One row: directory name at the left, the detail line dimmed at the right. */
    private static final class RowRenderer extends JPanel implements ListCellRenderer<Row> {

        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        RowRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
            add(name, BorderLayout.CENTER);
            add(detail, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Row> list, Row row, int index,
                boolean selected, boolean focused) {
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

        private static Color dimmed(JList<?> list) {
            Color hint = UIManager.getColor("Label.disabledForeground");
            return hint != null ? hint : list.getForeground();
        }
    }

    // ---- status line ---------------------------------------------------------------

    /**
     * Shows the busy icon with {@code message}. The spinner is the
     * platform's shared {@link AsyncProcessIcon}: paint-driven, animating
     * only while attached and visible, so {@link #endBusy} removing it is
     * the stop path on every branch — no stranded indefinite animation.
     */
    private void beginBusy(String message) {
        // Text only — no spinner. The panel's EDT state machine starts and
        // stops loads around this method; an animated icon here would be an
        // indefinite animation whose lifecycle the panel does not own, and
        // the placeholder text carries the same information (AGENTS.md UI
        // lifecycle rule).
        status.setText(message);
        statusLine.revalidate();
        statusLine.repaint();
    }

    /** Clears the busy text (every completion path) and shows {@code message}. */
    private void endBusy(String message) {
        status.setText(message);
        statusLine.revalidate();
        statusLine.repaint();
    }

    // ---- lifecycle ---------------------------------------------------------------

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

    /**
     * Installs the Worktrees tab in the Drydock tool window (registered in
     * plugin.xml as a project-level listener). {@link
     * ToolWindowManagerListener#toolWindowShown} fires on the EDT every
     * time the tool window becomes visible; the already-installed guard
     * makes the add a one-shot per project, and the guard inspects the
     * mounted components rather than keeping state, so a re-registered
     * project cannot end up with two tabs.
     */
    public static final class Installer implements ToolWindowManagerListener {

        public Installer() {
        }

        @Override
        public void toolWindowShown(@NotNull ToolWindow toolWindow) {
            if (!DRYDOCK_TOOL_WINDOW_ID.equals(toolWindow.getId())) {
                return;
            }
            Project project = toolWindow.getProject();
            ContentManager contents = toolWindow.getContentManager();
            for (Content existing : contents.getContents()) {
                if (existing.getComponent() instanceof WorktreeListPanel) {
                    return; // already installed for this project
                }
            }
            Content worktrees = ContentFactory.getInstance()
                    .createContent(new WorktreeListPanel(project), TAB_NAME, false);
            contents.addContent(worktrees);
        }
    }
}
