package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.TaskWorktreeUse;
import app.drydock.domain.SshRemote;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.SessionCommands;
import app.drydock.intellij.agent.SessionCommandHandlers;
import app.drydock.intellij.agent.SessionRename;
import app.drydock.intellij.agent.SessionTransfer;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.intellij.review.ReviewTabOpener;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.worktree.WorktreeErrors;
import app.drydock.review.ReviewIntent;
import app.drydock.review.tour.ReviewTour;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * What the Drydock tool window can do to a task: switch to it, deactivate it,
 * ask its agent to review its changes, deactivate it and close its window,
 * resume it.
 *
 * <p>Deactivating asks first only when the agent is mid-task (not at its
 * prompt), and the question can always be answered "Deactivate anyway". Closing
 * a window loses nothing — IntelliJ saves open files and the worktree stays
 * on disk — so the close itself adds no question of its own; the window's
 * <em>other</em> working agents get the usual close confirmation.</p>
 */
final class TaskActions {

    enum DefaultActivation { SWITCH_TO_RUNNING, RESUME_HERE }

    private static final long REMOVE_TIMEOUT_SECONDS = 120;
    private static final long FLAG_TIMEOUT_SECONDS = 30;

    /**
     * The actions the context menu offers but the toolbar does not: the
     * babysitting commands. They apply to few rows -- a session with an open
     * PR, or either side of an engagement -- so as toolbar buttons they would
     * stand greyed on nearly every selection (four more of them, crowding
     * the buttons people use); the row menu is where they are found.
     */
    static final Set<TaskMenu.Item> MENU_ONLY = EnumSet.of(TaskMenu.Item.BABYSIT_PR,
            TaskMenu.Item.STOP_BABYSITTING, TaskMenu.Item.SHOW_BABYSITTER, TaskMenu.Item.SHOW_WATCHED);

    private TaskActions() {
    }

    /** How an action shows: in a context menu only when it applies; in the toolbar always, greyed when it does not. */
    record Shown(boolean visible, boolean enabled) {
    }

    /**
     * Pure: toolbar buttons stay in place so they do not jump around as the
     * selection changes; applicability gates both. A context menu hides
     * what does not apply, the toolbar only greys it; a separate enabled
     * answer (the busy guard) greys without hiding anywhere.
     */
    static Shown shown(boolean fromContextMenu, boolean applicable, boolean enabled) {
        return new Shown(applicable || !fromContextMenu, applicable && enabled);
    }

    /** {@link #shown} with no separate enabled answer: applicability alone decides. */
    static Shown shown(boolean fromContextMenu, boolean applicable) {
        return shown(fromContextMenu, applicable, true);
    }

    /** Applies {@link #shown} to {@code e}'s presentation. */
    static void present(AnActionEvent e, boolean applicable) {
        present(e, applicable, true);
    }

    /** Applies {@link #shown} with a separate enabled answer: a busy row keeps the action in place, greyed. */
    static void present(AnActionEvent e, boolean applicable, boolean enabled) {
        Shown shown = shown(e.isFromContextMenu(), applicable, enabled);
        e.getPresentation().setVisible(shown.visible());
        e.getPresentation().setEnabled(shown.enabled());
    }

    /**
     * Pure: the lifecycle label of a stopped task — "Resume" only when
     * there is a conversation to resume into, "Start" when the only way
     * forward is a fresh one; the own-window variant names where (A6, A10).
     */
    static String lifecycleText(boolean resumable, boolean ownWindow) {
        return (resumable ? "Resume" : "Start") + (ownWindow ? " in Its Own Window" : "");
    }

    /**
     * Whether no operation is already in flight for {@code row}'s session —
     * the shared busy guard for the tree's mutating actions. Memory-only
     * reads (the registry's O(1) probes, the transfer table, the row's
     * warmed resumable answer), safe on the EDT (A5, A11).
     */
    static boolean notBusy(TaskRow row) {
        return !SessionCommands.busy(SessionCommands.stateOf(row.session(), Optional.of(row.resumable())));
    }

    /**
     * Whether a launch action — Resume/Start in this window or in the
     * task's own — may run for {@code row}: the shared {@link
     * SessionCommands#lifecycleEnabled} policy on the row's current facts,
     * in the tree's global scope (the F1 detached rule never turns a tree
     * action into Show agent). Busy always wins; past that, a stopped
     * session whose agent this build cannot run is held off — it has
     * nothing to start and nothing to resume into — so the tree greys the
     * launch actions exactly as the tab header and strip do. Memory-only
     * reads, safe on the EDT (A5, A11).
     */
    static boolean launchable(TaskRow row) {
        return SessionCommands.lifecycleEnabled(
                SessionCommands.stateOf(row.session(), Optional.of(row.resumable())));
    }

    /**
     * The task and project actions by their {@link TaskMenu.Item} menu
     * identity — one action instance each, shared by the tree's context
     * menu and the tool window's toolbar. The removal action serves every
     * removal entry: its label follows the selection (removalActionName),
     * so the same instance sits under several keys.
     */
    static Map<TaskMenu.Item, AnAction> taskActions() {
        AnAction deactivate = task("Deactivate", "Deactivate this task's agent", AllIcons.Actions.Suspend,
                TaskActions::deactivatable, TaskActions::notBusy,
                (project, row) -> SessionCommandHandlers.deactivate(project, row.session(), false, () -> {}));
        AnAction askReview = task("Ask Agent to Review Changes", "Ask this task's agent to review its changes",
                AllIcons.Actions.Diff, TaskActions::reviewsChanges, TaskActions::reviewChanges);
        // The switch: this window by default — a running console moves here,
        // an idle tab shows here and nothing starts — with the task's own
        // worktree window the explicit alternative.
        AnAction deactivateAndClose = task("Deactivate && Close Window",
                "Deactivate the agent and close its window", AllIcons.Actions.Close,
                TaskActions::closesOwnWindow, TaskActions::notBusy,
                (project, row) -> SessionCommandHandlers.deactivate(project, row.session(), true, () -> {}));
        // Resume in this window, hosted when the task is another
        // worktree's — as New task does; its own window is the
        // explicit alternative.
        AnAction resume = task("Resume", "Resume the task's conversation in this window", AllIcons.Actions.Execute,
                row -> !row.running(), TaskActions::launchable, TaskActions::resumesHere,
                (project, row) -> AgentConsoles.resumeOrStart(project, row.session()));
        AnAction resumeInOwnWindow = task("Resume in Its Own Window",
                "Open the task's own window and resume its conversation there",
                AllIcons.Actions.MoveTo2, TaskActions::resumesElsewhere, TaskActions::launchable,
                TaskActions::resumesInOwnWindow,
                (project, row) -> TaskNavigator.openOwnWindow(row.session(),
                        target -> AgentConsoles.resumeOrStart(target, row.session())));
        AnAction transfer = task("Transfer to…", "Continue this live task with another installed agent",
                AllIcons.Actions.Refresh, row -> SessionTransfer.available(row.session(), row.activity()),
                TaskActions::notBusy,
                (project, row) -> SessionTransfer.show(project, row.session()));
        AnAction rename = task("Rename…", "Rename this session", AllIcons.Actions.Edit, row -> true,
                (project, row) -> SessionRename.show(project, row.session()));
        AnAction flag = task("Flag for Follow-up", "Mark this session as needing your attention later",
                AllIcons.Nodes.Bookmark, row -> true, row -> true,
                (presentation, row) -> presentation.setText(flagText(row.session().flagged())),
                TaskActions::toggleFlag);
        AnAction done = task("Done with This Task…", "Forget the task, and optionally remove its worktree",
                AllIcons.Actions.Checked, row -> true, TaskActions::notBusy,
                (project, row) -> SessionCommandHandlers.done(project, row.session()));
        AnAction removeWorktrees = removeWorktrees();
        AnAction removeProject = removeProject();
        Map<TaskMenu.Item, AnAction> actions = new EnumMap<>(TaskMenu.Item.class);
        actions.put(TaskMenu.Item.ASSOCIATE_PR, task("Associate with PR…",
                "Set or clear the pull request this session targets", AllIcons.Vcs.Vendors.Github,
                row -> true, row -> notBusy(row) && AssociatePullRequest.available(row.session().id()),
                (project, row) -> AssociatePullRequest.show(project, row.session())));
        actions.put(TaskMenu.Item.DEACTIVATE, deactivate);
        actions.put(TaskMenu.Item.SWITCH_TO_TASK, task("Switch to Task",
                "Show the task's agent console in this window; an idle agent is never started",
                AllIcons.Actions.Forward, row -> true,
                (project, row) -> TaskNavigator.switchTo(project, row.session())));
        actions.put(TaskMenu.Item.SWITCH_TO_TASK_IN_OWN_WINDOW, task("Switch to Task in Its Own Window",
                "Raise the task's own worktree window — opening it when closed — and show its agent there",
                AllIcons.Actions.MoveTo2, row -> true,
                (project, row) -> AgentConsoles.jumpToOwnWindow(project, row.session().id(), false)));
        actions.put(TaskMenu.Item.REVIEW_CHANGES, askReview);
        actions.put(TaskMenu.Item.DEACTIVATE_AND_CLOSE, deactivateAndClose);
        actions.put(TaskMenu.Item.RESUME, resume);
        actions.put(TaskMenu.Item.RESUME_IN_OWN_WINDOW, resumeInOwnWindow);
        actions.put(TaskMenu.Item.TRANSFER_TO, transfer);
        actions.put(TaskMenu.Item.RENAME, rename);
        actions.put(TaskMenu.Item.FLAG, flag);
        actions.put(TaskMenu.Item.DONE_WITH_TASK, done);
        actions.put(TaskMenu.Item.REMOVE_WORKTREE, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_STALE_WORKTREES, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_INACTIVE_WORKTREES, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_WORKTREES, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_FROM_DRYDOCK, removeProject);
        actions.put(TaskMenu.Item.BABYSIT_PR, task("Babysit This PR",
                "Start a babysitter session that watches this session's pull request for conflicts and CI failures",
                InboxList.BABYSITTER_ICON, BabysitActions::offersStart,
                row -> notBusy(row) && BabysitActions.available(row.session().id()),
                (presentation, row) -> presentation.setText(BabysitActions.startText(row)),
                BabysitActions::start));
        actions.put(TaskMenu.Item.STOP_BABYSITTING, task("Stop Babysitting",
                "End this babysitting engagement and stop the babysitter's agent", AllIcons.Actions.Suspend,
                BabysitActions::stoppable, row -> BabysitActions.available(row.session().id()),
                BabysitActions::stop));
        actions.put(TaskMenu.Item.SHOW_BABYSITTER, task("Go to Babysitter",
                "Switch to the babysitter watching this session's pull request", AllIcons.Actions.Forward,
                BabysitActions::showsBabysitter,
                (project, row) -> row.babysit().ifPresent(part -> TaskNavigator.switchTo(project, part.counterpart()))));
        actions.put(TaskMenu.Item.SHOW_WATCHED, task("Go to Watched Session",
                "Switch to the session whose pull request this babysitter watches", AllIcons.Actions.Back,
                BabysitActions::showsWatched,
                (project, row) -> row.babysit().ifPresent(part -> TaskNavigator.switchTo(project, part.counterpart()))));
        return actions;
    }

    /**
     * Pure: the map's actions as the toolbar shows them — one button per
     * action instance, in the enum's {@code values()} order, the
     * {@link #MENU_ONLY} ones left out. The removal
     * action is one instance under several menu entries; a raw copy of the
     * map's values would add its button several times.
     */
    static List<AnAction> toolbarActions(Map<TaskMenu.Item, AnAction> actions) {
        List<AnAction> toolbar = new ArrayList<>();
        for (TaskMenu.Item item : TaskMenu.Item.values()) {
            if (MENU_ONLY.contains(item)) {
                continue;
            }
            AnAction action = actions.get(item);
            if (action != null && !toolbar.contains(action)) {
                toolbar.add(action);
            }
        }
        return List.copyOf(toolbar);
    }

    /** Pure: the follow-up toggle's label for a session that is, or is not, flagged now. */
    static String flagText(boolean flagged) {
        return flagged ? "Clear Follow-up Flag" : "Flag for Follow-up";
    }

    /**
     * Flips {@code row}'s follow-up flag. EDT. The write runs under a
     * background progress that ends when it returns; a failure is shown in
     * an error dialog on the EDT.
     */
    static void toggleFlag(Project from, TaskRow row) {
        boolean flagged = !row.session().flagged();
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                flagged ? "Flagging session…" : "Clearing session flag…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DrydockStartupActivity.sessions(from).setFlagged(row.session().id(), flagged)
                            .get(FLAG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    ApplicationManager.getApplication().invokeLater(
                            () -> Messages.showErrorDialog(from,
                                    "Could not change this session's flag: " + WorktreeErrors.visibleMessage(e),
                                    "Drydock"),
                            ModalityState.nonModal(), from.getDisposed());
                }
            }
        });
    }

    /** Whether Ask Agent to Review Changes applies: the agent runs, whatever its mode. Pure. */
    static boolean reviewsChanges(TaskRow row) {
        return row.running();
    }

    /**
     * Whether the tree's Deactivate — its Stop — applies to {@code row}:
     * the agent runs in this IDE, or the row is a workspace-box row whose
     * detached liveness the box last reported ALIVE while no execution here
     * runs it (the restart scenario: the agent lives on in the box's tmux
     * after the IDE comes back). The second case is the same lifecycle
     * verdict every other surface's Stop follows, so the tree offers the
     * one Stop the strip and the tab already do — routed to the same remote
     * handler, never a second kill path. Memory-only, safe on any thread.
     */
    static boolean deactivatable(TaskRow row) {
        return row.running() || remoteStopOffered(row);
    }

    /**
     * Whether the remote Stop applies to an idle row: a workspace-box row
     * whose lifecycle verdict is {@link SessionCommands.Lifecycle#DEACTIVATE}
     * — the box says an agent is there, so the Stop claims a real one.
     * Every other verdict keeps the honest Resume/Start, as everywhere
     * else. A row that runs here, or has no box, answers false before any
     * read, so a local row's applicability is unchanged. Memory-only (the
     * registry and the plugin's liveness map), safe on any thread.
     */
    static boolean remoteStopOffered(TaskRow row) {
        if (row.running() || row.session().workspaceBox().isEmpty()) {
            return false;
        }
        return SessionCommands.lifecycle(SessionCommands.stateOf(row.session(), Optional.of(row.resumable())))
                == SessionCommands.Lifecycle.DEACTIVATE;
    }

    /**
     * Asks {@code row}'s agent to review its changes. EDT. The progress names the review's source
     * before anything is resolved or sent; the request runs under a background progress that ends
     * on every exit, and its result (sent or not) names the same source. A sent prompt only updates
     * the progress text; any other outcome is shown in a warning dialog on the EDT. The intent is
     * explicit local changes: the session's PR association is incidental metadata and never selects
     * the PR method.
     */
    static void reviewChanges(Project from, TaskRow row) {
        ReviewIntent intent = quickReviewIntent(row, Optional.empty());
        ProgressManager.getInstance().run(new Task.Backgroundable(from, quickReviewTitle(row, intent), false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                DrydockPluginService plugin = DrydockPluginService.getInstance();
                AgentExecutionRegistry executions = AgentExecutionRegistry.getInstance();
                ReviewRequest request = ReviewRequest.forBackend(plugin.initialization(), plugin::reviewBackend,
                        executions::isRunning, kind -> plugin.registry().supportsSubagents(kind),
                        executions::submitPrompt);
                QuickReviewResult result = quickReview(request, () -> plugin.reviewBackend().map(backend ->
                                new ReviewRequest.Delivery(backend.scopeAuthorizer(), backend.invocationBindings(),
                                        id -> executions.execution(id).isPresent(), 1L, () -> 1L,
                                        Optional.empty())),
                        intent, row.session());
                if (result.sent()) {
                    indicator.setText(result.text());
                } else {
                    ApplicationManager.getApplication().invokeLater(
                            () -> Messages.showWarningDialog(from, result.text(), "Drydock"),
                            ModalityState.nonModal(), from.getDisposed());
                }
            }
        });
    }

    /** Whether {@code node} is a remote (workspace-box) session row: the only row the remote entry serves. */
    static boolean remoteSession(TaskNode node) {
        return node instanceof TaskRow row && row.session().workspaceBox().isPresent();
    }

    /** The typed workbench context of a remote row: session identity and a safe label, never a path. Pure. */
    static ReviewTabOpener.RemoteContext remoteContext(TaskRow row) {
        return new ReviewTabOpener.RemoteContext(row.session().id(),
                "Remote workspace " + row.session().workspaceBox().orElseThrow().boxName() + " · local changes");
    }

    /**
     * Reveal-only entry for a remote row: hands its context to {@code opener} (production:
     * {@link ReviewTabOpener#openRemote}), which neither invokes, enrolls nor submits and needs no
     * attached console, so a detached session reveals too. A local row is ignored. EDT.
     */
    static void revealRemote(TaskNode node, Consumer<ReviewTabOpener.RemoteContext> opener) {
        if (remoteSession(node)) {
            opener.accept(remoteContext((TaskRow) node));
        }
    }

    /** "Open in Review Workbench" on a remote session row, delegating to {@link ReviewTabOpener#openRemote}. */
    private static final class RemoteEntry {
        static final AnAction ACTION = new DumbAwareAction("Open in Review Workbench",
                "Reveal this remote session's changes in the review workbench; nothing is sent to the agent",
                AllIcons.Actions.Diff) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                present(e, remoteSession(e.getData(TaskTreePanel.SELECTED_NODE)));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Project project = e.getProject();
                if (project != null) {
                    revealRemote(e.getData(TaskTreePanel.SELECTED_NODE), ctx -> ReviewTabOpener.openRemote(project, ctx));
                }
            }
        };
    }

    /** {@code menu} with the remote entry appended for a remote session row; any other node's menu unchanged. */
    static AnAction[] withRemoteReveal(TaskNode node, AnAction[] menu) {
        if (!remoteSession(node)) {
            return menu;
        }
        AnAction[] out = Arrays.copyOf(menu, menu.length + 2);
        out[menu.length] = Separator.getInstance();
        out[menu.length + 1] = RemoteEntry.ACTION;
        return out;
    }

    /** The result text of a Quick Review and whether the prompt was sent. */
    record QuickReviewResult(String text, boolean sent) { }

    /**
     * The intent a Quick Review carries: this row's checkout (a remote row's placeholder, never a
     * probed path), this session, and local changes unless the human explicitly selected
     * {@code selectedPr}. The session's own PR association is deliberately not an input. Pure.
     */
    static ReviewIntent quickReviewIntent(TaskRow row, Optional<PullRequestTarget> selectedPr) {
        ManagedAgentSession session = row.session();
        ReviewIntent.Checkout checkout = session.workspaceBox().isPresent()
                ? new ReviewIntent.Remote(session.workspace().workingDirectory().toString())
                : new ReviewIntent.Local(session.workspace().worktreeRoot()
                        .orElse(session.workspace().workingDirectory()));
        Optional<ManagedSessionId> id = Optional.of(session.id());
        return selectedPr.map(pr -> ReviewIntent.pullRequest(session.repositoryId(), checkout, id, pr,
                        ReviewIntent.Origin.TASK_ACTION, ReviewTour.Kind.TOUR))
                .orElseGet(() -> ReviewIntent.local(session.repositoryId(), checkout, id,
                        ReviewIntent.Origin.TASK_ACTION, ReviewTour.Kind.TOUR));
    }

    /** The progress title: the task and the selected source with its workflow label, shown before dispatch. */
    static String quickReviewTitle(TaskRow row, ReviewIntent intent) {
        return "Starting review of " + row.session().displayName() + " (" + WorktreeReview.sourceOf(Optional.of(intent))
                + ", " + WorkflowSourceLabel.of(WorkflowSourceLabel.Provenance.DEFAULT, "", null) + ")…";
    }

    /**
     * Submits ONE {@link ReviewRequest} for {@code intent} to {@code session}, whatever the delivery
     * wiring or the request does: an absent wiring is NotReady, a thrown failure is a not-sent
     * result. Blocks; never throws.
     */
    static QuickReviewResult quickReview(ReviewRequest request,
                                         Supplier<Optional<ReviewRequest.Delivery>> delivery,
                                         ReviewIntent intent, ManagedAgentSession session) {
        String name = session.displayName();
        ReviewRequest.Outcome outcome;
        try {
            Optional<ReviewRequest.Delivery> wiring = delivery.get();
            outcome = wiring.isEmpty() ? new ReviewRequest.NotReady()
                    : request.request(intent, session, Optional.empty(), wiring.get());
        } catch (RuntimeException e) {
            outcome = new ReviewRequest.Failed(String.valueOf(e.getMessage()));
        }
        Optional<String> problem = ReviewRequest.message(name, outcome);
        return problem.map(text -> new QuickReviewResult(text, false))
                .orElseGet(() -> new QuickReviewResult("Review of " + name + " ("
                        + WorktreeReview.sourceOf(Optional.of(intent)) + ", "
                        + WorkflowSourceLabel.of(WorkflowSourceLabel.Provenance.DEFAULT, "", null)
                        + ") sent to its agent.", true));
    }

    /**
     * Whether Deactivate &amp; Close Window applies: the agent runs, and the window
     * showing it is its own worktree's. A hosted agent's window belongs to
     * another project — closing it would close what the user is working in,
     * with whatever else it shows — and an agent no window shows has none to
     * close. Pure.
     */
    static boolean closesOwnWindow(TaskRow row) {
        return row.running() && (row.placement() instanceof TaskPlacement.ThisWindow
                || row.placement() instanceof TaskPlacement.OwnWindow);
    }

    /**
     * Whether "Resume in Its Own Window" applies: an idle task from another
     * worktree. For this window's own worktree its own window is this one,
     * and plain Resume already does it. Pure.
     */
    static boolean resumesElsewhere(TaskRow row) {
        return !row.running() && row.placement() instanceof TaskPlacement.Idle idle && !idle.thisWindow();
    }

    /** Double-click/Enter follows the primary action: resume idle tasks here, focus running ones. */
    static DefaultActivation defaultActivation(TaskRow row) {
        return row.running() ? DefaultActivation.SWITCH_TO_RUNNING : DefaultActivation.RESUME_HERE;
    }

    /**
     * Sets the Resume action's label and icon from the row: "Resume" only
     * when the task has a conversation to resume into, "Start" otherwise
     * (A6, A10, A11).
     */
    private static void resumesHere(Presentation presentation, TaskRow row) {
        presentation.setText(lifecycleText(row.resumable(), false));
        presentation.setIcon(row.resumable() ? SessionCommands.Lifecycle.RESUME.icon()
                : SessionCommands.Lifecycle.START.icon());
    }

    /** The own-window variant: the same honest label, the move-elsewhere icon. */
    private static void resumesInOwnWindow(Presentation presentation, TaskRow row) {
        presentation.setText(lifecycleText(row.resumable(), true));
        presentation.setIcon(SessionCommands.Command.OWN_PROJECT.icon());
    }

    private interface RowAction {
        void run(Project project, TaskRow row);
    }

    /** Optional per-row presentation beyond show/hide/grey: the lifecycle actions' label and icon. */
    private interface Presenter {
        Presenter NONE = (presentation, row) -> {
        };

        void show(Presentation presentation, TaskRow row);
    }

    private static AnAction task(String text, String description, Icon icon, Predicate<TaskRow> applies,
                                 RowAction action) {
        return task(text, description, icon, applies, row -> true, Presenter.NONE, action);
    }

    private static AnAction task(String text, String description, Icon icon, Predicate<TaskRow> applies,
                                 Predicate<TaskRow> enabled, RowAction action) {
        return task(text, description, icon, applies, enabled, Presenter.NONE, action);
    }

    /**
     * One tree action: shown where {@code applies} holds, greyed while the
     * busy guard ({@code enabled}) or applicability says no, with an optional
     * per-row {@code presenter} beyond that (the lifecycle label). While busy
     * the action is disabled, never hidden — the row is still a task.
     */
    private static AnAction task(String text, String description, Icon icon, Predicate<TaskRow> applies,
                                 Predicate<TaskRow> enabled, Presenter presenter, RowAction action) {
        return new DumbAwareAction(text, description, icon) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskRow row = e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskRow selected ? selected : null;
                present(e, row != null && applies.test(row), row != null && enabled.test(row));
                if (row != null) {
                    presenter.show(e.getPresentation(), row);
                }
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getProject() != null && e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskRow row) {
                    action.run(e.getProject(), row);
                }
            }
        };
    }

    /**
     * "Remove from Drydock" on a project row: takes the project off the
     * shared list — for the standalone app too — and touches nothing else:
     * not the repository, not its worktrees, not running agents. Asks only
     * when some of its agents are running here, to say they keep running.
     */
    private static AnAction removeProject() {
        return new DumbAwareAction("Remove from Drydock", "Take this project off Drydock's list; nothing on disk "
                + "changes", AllIcons.Actions.Close) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof ProjectRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (!(e.getData(TaskTreePanel.SELECTED_NODE) instanceof ProjectRow row) || e.getProject() == null) {
                    return;
                }
                Project project = e.getProject();
                int running = row.running();
                if (running > 0 && Messages.showOkCancelDialog(project,
                        removeQuestion(row.project().displayName(), running, row.project().remote()),
                        "Drydock", "Remove", "Cancel",
                        null) != Messages.OK) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().remove(row.project().id())
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(
                                        project, "Could not remove " + row.project().displayName() + ": "
                                                + failure.getMessage(), "Drydock"),
                                        ModalityState.nonModal(), project.getDisposed());
                            }
                        });
            }
        };
    }

    /**
     * {@code Remove <repo> from Drydock? <n> agents keep running.} A remote
     * (SSH) repository appends the {@code host:path} the files actually live
     * at — never the placeholder root, which names nothing on any machine —
     * the app's remove-confirm wording (spec: SSH remote repositories).
     */
    static String removeQuestion(String repo, int running, Optional<SshRemote> remote) {
        String question = "Remove " + repo + " from Drydock? "
                + running + (running == 1 ? " agent keeps" : " agents keep") + " running.";
        return remote.map(r -> question + " The repository lives at " + r.host() + ":" + r.remotePath()
                        + " and is not touched.")
                .orElse(question);
    }

    /** Removes one idle row, a stale group, or every inactive worktree under a project. */
    private static AnAction removeWorktrees() {
        return new DumbAwareAction("Remove Inactive Worktrees…",
                "Remove inactive worktree folders; branches are kept", AllIcons.General.Remove) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                List<TaskNode> selected = selectedNodes(e);
                List<IdleWorktree> targets = removable(selected);
                present(e, !targets.isEmpty() && e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT) != null
                        && e.getData(TaskTreePanel.WORKTREE_REMOVAL_UI) != null);
                e.getPresentation().setText(removalActionName(selected));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Project project = e.getProject();
                Path root = e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT);
                TaskTreePanel.WorktreeRemovalUi removalUi = e.getData(TaskTreePanel.WORKTREE_REMOVAL_UI);
                List<TaskNode> selected = selectedNodes(e);
                List<IdleWorktree> targets = removable(selected);
                if (project == null || root == null || removalUi == null || targets.isEmpty()) {
                    return;
                }
                confirmAndRemove(project, root, targets,
                        selected.size() == 1 && selected.get(0) instanceof StaleWorktrees, removalUi);
            }
        };
    }

    /** EDT entry point shared by the action and the stale row's visible link. */
    static void confirmAndRemove(Project project, Path root, List<IdleWorktree> targets, boolean staleOnly,
                                 TaskTreePanel.WorktreeRemovalUi removalUi) {
        String question = removalQuestion(targets.size(), staleOnly);
        if (Messages.showOkCancelDialog(project, question, "Remove Worktrees",
                removalButton(targets.size()), "Cancel", AllIcons.General.Warning) == Messages.OK) {
            removeInBackground(project, root, targets, removalUi);
        }
    }

    static List<IdleWorktree> removable(TaskNode node) {
        return switch (node) {
            case ProjectRow row -> row.inactiveWorktrees().stream()
                    .filter(worktree -> !worktree.locked() && !worktree.open()).toList();
            case StaleWorktrees stale -> stale.worktrees();
            case IdleWorktree idle when !idle.locked() && !idle.open() -> List.of(idle);
            case null, default -> List.of();
        };
    }

    /** All selected targets, with parent/child selection overlaps removed. */
    static List<IdleWorktree> removable(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Map<Path, IdleWorktree> unique = new LinkedHashMap<>();
        for (TaskNode node : nodes) {
            List<IdleWorktree> targets = removable(node);
            if (targets.isEmpty()) {
                // A mixed selection must not silently act on just its
                // removable subset.
                return List.of();
            }
            targets.forEach(target -> unique.putIfAbsent(target.worktree(), target));
        }
        return List.copyOf(unique.values());
    }

    private static List<TaskNode> selectedNodes(AnActionEvent event) {
        List<TaskNode> selected = event.getData(TaskTreePanel.SELECTED_NODES);
        if (selected != null) {
            return selected;
        }
        TaskNode lead = event.getData(TaskTreePanel.SELECTED_NODE);
        return lead == null ? List.of() : List.of(lead);
    }

    static String removalActionName(TaskNode node) {
        if (node instanceof IdleWorktree) {
            return "Remove Worktree…";
        }
        if (node instanceof StaleWorktrees) {
            return "Remove Stale Worktrees…";
        }
        return "Remove Inactive Worktrees…";
    }

    static String removalActionName(List<TaskNode> nodes) {
        return nodes != null && nodes.size() == 1
                ? removalActionName(nodes.get(0))
                : "Remove Worktrees…";
    }

    static String removalQuestion(int count, boolean staleOnly) {
        String kind = staleOnly ? "stale" : "inactive";
        return "Remove " + count + " " + kind + (count == 1 ? " worktree" : " worktrees") + "? "
                + "This permanently deletes " + (count == 1 ? "its folder" : "their folders")
                + " and discards any uncommitted and untracked changes. Branches are kept.";
    }

    static String removalButton(int count) {
        return count == 1 ? "Remove" : "Remove " + count;
    }

    private static void removeInBackground(Project project, Path root, List<IdleWorktree> targets,
                                           TaskTreePanel.WorktreeRemovalUi removalUi) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Removing worktrees…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> failures = new ArrayList<>();
                List<Path> removed = new ArrayList<>();
                // T41: the guard's own inputs, read off the EDT once -- the
                // receipts' store root and the live catalog session ids.
                Path storeRoot = DrydockPluginService.getInstance().taskService().root();
                Set<String> liveSessions = DrydockPluginService.getInstance().stateStore().state().sessions()
                        .stream()
                        .map(row -> row.id().toString())
                        .collect(Collectors.toUnmodifiableSet());
                try (WorktreeService worktrees = new WorktreeService()) {
                    for (int i = 0; i < targets.size(); i++) {
                        IdleWorktree target = targets.get(i);
                        indicator.setText("Removing " + target.branch());
                        indicator.setFraction((double) i / targets.size());
                        // T41: claim AFTER the confirmation, recheck under the
                        // claim, delete only when no live dependent session
                        // uses the path; a refusal keeps the folder and is
                        // shown with the other failures.
                        try (TaskWorktreeUse.RemovalClaim claim = TaskWorktreeUse.claimRemoval(
                                target.worktree(), "standalone worktree removal")) {
                            Optional<String> refusal = TaskWorktreeUse.guard(storeRoot, target.worktree(),
                                    liveSessions);
                            if (refusal.isPresent()) {
                                failures.add(target.branch() + ": " + refusal.get());
                                continue;
                            }
                            // The confirmation explicitly accepts discarded
                            // local changes. Branches intentionally survive.
                            worktrees.removeDiscardingChanges(root, target.worktree(), Optional.empty())
                                    .get(REMOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            removed.add(target.worktree());
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            failures.add(target.branch() + ": interrupted");
                            break;
                        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                            failures.add(target.branch() + ": " + WorktreeErrors.visibleMessage(ex));
                        }
                    }
                } finally {
                    try {
                        if (!removed.isEmpty() && !project.isDisposed()) {
                            indicator.setText("Updating task list…");
                            indicator.setFraction(1);
                            Set<Path> removedPaths = Set.copyOf(removed);
                            // Keep the progress task alive until the rows it
                            // removed are gone from the visible tree. The
                            // authoritative rescan below can take several
                            // seconds because it also probes activity.
                            ApplicationManager.getApplication().invokeAndWait(
                                    () -> removalUi.worktreesRemoved(removedPaths), ModalityState.any());
                        }
                    } finally {
                        // Some removals may have succeeded before a later one
                        // failed; always converge the tree on the on-disk state.
                        DrydockSessionsListener.publishChanged();
                    }
                }
                if (!failures.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project,
                            "Some worktrees could not be removed:\n" + String.join("\n", failures), "Drydock"),
                            ModalityState.nonModal(), project.getDisposed());
                }
            }
        });
    }
}
