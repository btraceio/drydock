package app.drydock.intellij.tasks;

import app.drydock.config.UserConfig;
import app.drydock.domain.SessionMode;
import app.drydock.git.BranchCatalog;
import app.drydock.git.BranchCheckout;
import app.drydock.git.BranchNameRules;
import app.drydock.git.BranchRef;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.projects.SharedProject;
import app.drydock.intellij.tasks.NewTaskPlan.FixedWorktree;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.worktree.WorktreeErrors;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.awt.RelativePoint;

import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * {@code Start} on a Branches-section row: the menu and the launch behind
 * each item. A branch a worktree (or the main checkout) holds starts in
 * that worktree — the same New-task flow the Worktrees row offers, since
 * git refuses a second checkout of a branch. A free branch starts in a new
 * worktree created on it first and the dialog then opens fixed to it
 * (create-then-fix, the PR checkout's shape); {@code Sub-branch} skips the
 * creation and cuts a new branch from the row's branch in a new worktree,
 * the dialog's start-point flow.
 *
 * <p>The new worktree is created before the dialog so the task always works
 * where the branch already lives; the user cancelling the dialog leaves an
 * idle worktree the Worktrees section lists, which is the state the user
 * asked for.</p>
 */
final class BranchStart {

    private static final Logger LOG = System.getLogger(BranchStart.class.getName());

    /** Generous: on a repository with submodules the checkout is the slow part. */
    private static final long WORKTREE_TIMEOUT_SECONDS = 180;

    /** One worktree creation per (main checkout, branch) at a time. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * One menu item. {@code subBranch} marks the sub-branch flow: the dialog
     * opens with the row's branch as its start point and names its own new
     * branch, instead of starting on this branch.
     */
    record Item(String text, Optional<String> secondary, OpenIn openIn, SessionMode mode, boolean subBranch) {
        Item {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must not be blank");
            }
            if (secondary == null || openIn == null || mode == null) {
                throw new IllegalArgumentException("null item part");
            }
        }
    }

    private BranchStart() {
    }

    /**
     * Pure: the items for {@code row}, whether its branch is held or free.
     * A held branch mirrors the holder worktree's Start menu — on this
     * window's own worktree, "its own window" is this window, so that item
     * is not offered (the same rule {@link StartMenu} applies). A free
     * branch creates its worktree first; {@code ownWorktree} cannot apply.
     */
    static List<Item> items(TaskNode.BranchRow row, String repo, boolean ownWorktree) {
        Item readOnly = new Item("Read-only, in this window", Optional.empty(), OpenIn.THIS_WINDOW,
                SessionMode.READ_ONLY, false);
        if (row.held()) {
            Item here = new Item("Full access, in this window", Optional.of("Tab opens here, tagged " + repo),
                    OpenIn.THIS_WINDOW, SessionMode.WRITES, false);
            if (ownWorktree) {
                return List.of(here, readOnly, subBranchItem());
            }
            Item own = new Item("Full access, in its own window",
                    Optional.of("Opens " + repo + " · " + row.worktreeBranch()),
                    OpenIn.OWN_WINDOW, SessionMode.WRITES, false);
            return List.of(here, own, readOnly, subBranchItem());
        }
        return List.of(
                new Item("Full access, in a new worktree on this branch",
                        Optional.of("Creates " + repo + " · " + row.worktreeBranch()),
                        OpenIn.THIS_WINDOW, SessionMode.WRITES, false),
                new Item("Full access, in its own window",
                        Optional.of("Creates " + repo + " · " + row.worktreeBranch()),
                        OpenIn.OWN_WINDOW, SessionMode.WRITES, false),
                readOnly,
                subBranchItem());
    }

    private static Item subBranchItem() {
        return new Item("Sub-branch in a new worktree…", Optional.of("Cuts a new branch from this one"),
                OpenIn.THIS_WINDOW, SessionMode.WRITES, true);
    }

    /**
     * The Branches-section row's context-menu actions, owned here so the
     * panel stays lean: the new-worktree-on-branch start (this window and
     * its own-window variant, free branches only — a held branch's New Task
     * is fixed to its worktree by the panel's target resolution) and the
     * sub-branch cut. {@code onWorktreeCreated} runs when a launch created
     * a worktree, so the tree can rescan. EDT construction.
     */
    static Map<TaskMenu.Item, AnAction> actions(Project project, Runnable onWorktreeCreated) {
        AnAction onBranch = new DumbAwareAction("New Task on This Branch…",
                "Create a worktree on this branch and start a task in it, shown here", AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE)
                        instanceof TaskNode.BranchRow row && !row.held());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskNode.BranchRow row) {
                    launch(project, row, new Item("Full access, in a new worktree on this branch",
                            Optional.empty(), OpenIn.THIS_WINDOW, SessionMode.WRITES, false), onWorktreeCreated);
                }
            }
        };
        AnAction onBranchOwnWindow = new DumbAwareAction("New Task on This Branch, in Its Own Window…",
                "Create a worktree on this branch and start a task in it, in its own window",
                AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE)
                        instanceof TaskNode.BranchRow row && !row.held());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskNode.BranchRow row) {
                    launch(project, row, new Item("Full access, in its own window",
                            Optional.empty(), OpenIn.OWN_WINDOW, SessionMode.WRITES, false), onWorktreeCreated);
                }
            }
        };
        AnAction subBranch = new DumbAwareAction("Sub-branch in a New Worktree…",
                "Cut a new branch from this one in a new worktree and start a task there", AllIcons.General.Add) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskActions.present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskNode.BranchRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskNode.BranchRow row) {
                    launch(project, row, new Item("Sub-branch in a new worktree…",
                            Optional.empty(), OpenIn.THIS_WINDOW, SessionMode.WRITES, true), onWorktreeCreated);
                }
            }
        };
        return Map.of(TaskMenu.Item.NEW_TASK_ON_BRANCH, onBranch,
                TaskMenu.Item.NEW_TASK_ON_BRANCH_OWN_WINDOW, onBranchOwnWindow,
                TaskMenu.Item.START_SUB_BRANCH, subBranch);
    }

    /** Shows the menu at {@code where}; {@code chosen} gets the picked item. EDT. */
    static void show(RelativePoint where, List<Item> items, Consumer<Item> chosen) {
        JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<>(null, items) {
            @Override
            public @NotNull String getTextFor(Item item) {
                return item.secondary().map(line -> item.text() + "  —  " + line).orElse(item.text());
            }

            @Override
            public PopupStep<?> onChosen(Item item, boolean finalChoice) {
                return doFinalStep(() -> chosen.accept(item));
            }
        }).show(where);
    }

    /**
     * Launches the picked item for {@code row}. {@code onWorktreeCreated}
     * runs when a new worktree was created, before the dialog opens, so the
     * tree shows it. EDT entry.
     */
    static void launch(Project project, TaskNode.BranchRow row, Item item, Runnable onWorktreeCreated) {
        if (item.subBranch()) {
            TaskLauncher.openFromStartPoint(project, row.mainRoot(), row.branch().name());
            return;
        }
        Optional<Path> holder = row.branch().checkedOutAt();
        if (holder.isPresent()) {
            // The branch already lives somewhere: the dialog fixes to that
            // worktree, the same launch a Worktrees row runs.
            TaskLauncher.open(project, Optional.of(row.mainRoot()),
                    Optional.of(new FixedWorktree(holder.get(), row.worktreeBranch())),
                    NewTaskPreset.start(item.openIn(), item.mode()));
            return;
        }
        createAndOpen(project, row.mainRoot(), repositoryName(row), row, item, onWorktreeCreated);
    }

    /** The registered display name of {@code row}'s project, or its folder's when unregistered. */
    private static String repositoryName(TaskNode.BranchRow row) {
        return DrydockPluginService.getInstance().sharedProjects().byRoot(row.mainRoot())
                .map(SharedProject::displayName)
                .orElse(row.mainRoot().getFileName().toString());
    }

    /**
     * Creates a worktree on the free branch in the background, then opens
     * the dialog fixed to it. Occupancy is re-resolved from a fresh catalog:
     * the gather's listing can be up to a minute old, and git itself is the
     * referee. One creation per branch at a time; a failed creation reports
     * and leaves the tree alone. EDT entry.
     */
    private static void createAndOpen(Project from, Path mainRoot, String repositoryName, TaskNode.BranchRow row,
                                      Item item, Runnable onWorktreeCreated) {
        String key = mainRoot + "\n" + row.worktreeBranch();
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(from,
                "Creating worktree for " + row.worktreeBranch() + "…", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try (GitStatusService git = new GitStatusService(); WorktreeService worktrees = new WorktreeService()) {
                    BranchCatalog catalog = BranchCatalog.load(git, worktrees, mainRoot)
                            .get(WORKTREE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    BranchRef ref = catalog.branches().stream()
                            .filter(candidate -> candidate.name().equals(row.branch().name())
                                    && candidate.remote() == row.branch().remote())
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Branch " + row.branch().name() + " vanished while preparing the worktree"));
                    switch (BranchCheckout.resolve(catalog, ref)) {
                        case BranchCheckout.Outcome.Ready ready -> create(from, mainRoot, ready, repositoryName,
                                item, onWorktreeCreated, indicator);
                        case BranchCheckout.Outcome.Occupied occupied -> report(from,
                                "'" + occupied.ref().name() + "' was just checked out in the worktree at "
                                        + occupied.ref().checkedOutAt().orElseThrow() + "; start it from there.");
                        case BranchCheckout.Outcome.NoSuchBranch missing ->
                                report(from, "No branch named '" + missing.text() + "' remains to start on.");
                        case BranchCheckout.Outcome.Unmintable unmintable -> report(from,
                                "Starting on '" + unmintable.ref().name() + "' would create the local branch '"
                                        + unmintable.localName() + "', which "
                                        + BranchNameRules.shortClause(unmintable.refusal()) + ".");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not create the worktree for " + row.worktreeBranch(), e);
                    report(from, "Drydock could not create the worktree: " + WorktreeErrors.visibleMessage(e));
                }
            }

            @Override
            public void onFinished() {
                IN_FLIGHT.remove(key);
            }
        });
    }

    /**
     * The creation step of a ready classification: the default worktree
     * directory for the branch, created when absent, then the dialog fixed
     * to it. An existing directory is reused only when git lists it as this
     * branch's worktree; any other directory fails the launch with a report
     * — no silent suffixing and no opening a dialog into a folder that is
     * not the branch's, the folder is the user's to free. Background thread.
     */
    private static void create(Project from, Path mainRoot, BranchCheckout.Outcome.Ready ready,
                               String repositoryName, Item item, Runnable onWorktreeCreated,
                               ProgressIndicator indicator)
            throws ExecutionException, InterruptedException, TimeoutException {
        Path folder = WorktreeNaming.defaultDirectory(Path.of(System.getProperty("user.home")),
                UserConfig.load().worktreesDirectory(), repositoryName, ready.localName());
        if (!Files.isDirectory(folder)) {
            if (indicator != null) {
                indicator.setText("Creating worktree " + ready.localName() + "…");
            }
            new GitStatusService()
                    .addWorktreeForBranch(mainRoot, folder, ready.ref(), ready.localName())
                    .get(WORKTREE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } else if (!holdsBranch(mainRoot, folder, ready.localName())) {
            report(from, "The worktree folder " + folder + " already exists and does not hold '"
                    + ready.localName() + "'; free or rename it and start again.");
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            onWorktreeCreated.run();
            TaskLauncher.open(from, Optional.of(mainRoot),
                    Optional.of(new FixedWorktree(folder, ready.localName())),
                    NewTaskPreset.start(item.openIn(), item.mode()));
        }, ModalityState.any());
    }

    /** Whether git lists {@code folder} as a worktree of {@code mainRoot} holding {@code branch}. */
    private static boolean holdsBranch(Path mainRoot, Path folder, String branch)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (WorktreeService worktrees = new WorktreeService()) {
            return worktrees.list(mainRoot).get(WORKTREE_TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                    .anyMatch(worktree -> worktree.path().equals(folder)
                            && worktree.branch().equals(Optional.of(branch)));
        }
    }

    /** EDT: the launch's visible failure, the same surface the other launch paths report through. */
    private static void report(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(
                () -> Messages.showErrorDialog(project, message, "Drydock"), ModalityState.nonModal(),
                project.getDisposed());
    }
}
