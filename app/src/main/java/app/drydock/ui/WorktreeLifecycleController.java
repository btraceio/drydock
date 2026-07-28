package app.drydock.ui;

import app.drydock.app.SessionManager;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.git.GhCliService;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitChangeSummary;
import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The worktree-finish lifecycle (worktree handoff, section B), extracted
 * from {@code MainWorkspace} (see docs/plans/workspace-split-design.md):
 * the worktree session header (context line, ↑ahead/dirty/PR chips), the
 * state-aware Finish panel with its pre-panel inspection and PR-state
 * reconciliation, and the three finish actions.
 *
 * <p>Merge is merge-and-finish: it merges the branch and then closes the
 * work out (worktree, branch, session, tab, sidebar row), and it lives in
 * {@link MergeAndFinishFlow} -- Claude is only involved when the merge stops
 * on conflicts. Delete shares that flow's destructive tail through {@link
 * WorktreeSessionCleanup}. Only "create PR" is still a blind hand-off to the
 * Claude session in the terminal, with a {@code PauseTransition}-based
 * confirmation poll since {@code gh pr create} needs the user's own gh
 * auth.</p>
 *
 * <p>Collaborators are injected: the services doing the actual git/gh
 * work, the modal layer the panels show through, and callbacks into the
 * workspace ({@code openTab} -- the liveness lookup every async completion
 * re-resolves before touching a header, since a tab can be closed mid-git;
 * {@code onSessionsChanged}; {@code onSessionDeleted}). All
 * methods run on the FX Application Thread; async completions hop back via
 * {@code Platform.runLater} exactly as before the extraction.</p>
 */
final class WorktreeLifecycleController {

    private static final Logger LOG = System.getLogger(WorktreeLifecycleController.class.getName());

    /** Handoff polling caps: every 4s, up to 5 minutes; on timeout the Finish button quietly returns. */
    private static final Duration HANDOFF_POLL_INTERVAL = Duration.seconds(4);
    private static final int HANDOFF_POLL_MAX_ATTEMPTS = 75;
    /**
     * What {@link #branchNameOf} puts where a branch name goes when HEAD is
     * detached. A label, not a branch name -- {@link #startMergeAndFinish}
     * refuses it rather than let it reach git or user copy.
     */
    private static final String DETACHED_LABEL = "(detached)";

    private final SessionManager sessionManager;
    private final GitStatusService gitStatusService;
    private final GhCliService ghCliService;
    private final WorktreeService worktreeService;
    /**
     * The workspace's open-tab lookup: non-null while the session's tab is
     * open, {@code null} once it closed -- the liveness guard every async
     * completion and poll step checks before touching UI.
     */
    private final Function<ManagedSessionId, OpenSessionTab> openTab;
    private final Function<ManagedAgentSession, Optional<Repository>> repositoryFor;
    /** Invoked (on the FX Application Thread) after any session/PR state change the sidebar must reflect. */
    private final Runnable onSessionsChanged;
    /** Invoked after a deleted worktree's session row is removed (delegates to {@code MainWorkspace.noteSessionDeleted}). */
    private final Consumer<ManagedSessionId> onSessionDeleted;
    /** The one "this worktree session is finished" sequence, shared by Delete and merge-and-finish. */
    private final WorktreeSessionCleanup cleanup;
    /** Sessions with a merge-and-finish flow running; a second Finish must not start another. */
    private final Set<ManagedSessionId> mergeInFlight = new HashSet<>();

    /** The app shell's modal layer; wired late by DrydockApplication via {@code MainWorkspace.setModalLayer}. */
    private ModalLayer modalLayer;

    WorktreeLifecycleController(SessionManager sessionManager, GitStatusService gitStatusService,
                                GhCliService ghCliService, WorktreeService worktreeService,
                                Function<ManagedSessionId, OpenSessionTab> openTab,
                                Function<ManagedAgentSession, Optional<Repository>> repositoryFor,
                                Runnable onSessionsChanged, Consumer<ManagedSessionId> onSessionDeleted) {
        this.sessionManager = sessionManager;
        this.gitStatusService = gitStatusService;
        this.ghCliService = ghCliService;
        this.worktreeService = worktreeService;
        this.openTab = openTab;
        this.repositoryFor = repositoryFor;
        this.onSessionsChanged = onSessionsChanged;
        this.onSessionDeleted = onSessionDeleted;
        this.cleanup = new WorktreeSessionCleanup(worktreeService::remove, sessionManager::deleteSession);
    }

    void setModalLayer(ModalLayer modalLayer) {
        this.modalLayer = modalLayer;
    }

    /**
     * Fills a worktree session tab's header: the ◫ context line, the
     * ↑ahead/dirty/PR chips, and the Finish ▸ button. Branch/base resolve
     * asynchronously (worktree checkout vs the repository's main checkout).
     */
    void setupWorktreeHeader(OpenSessionTab tab, ManagedSessionId sessionId, Path worktreeRoot) {
        ManagedAgentSession session = sessionById(sessionId).orElse(null);
        Repository repository = session == null ? null : repositoryFor.apply(session).orElse(null);
        if (session == null || repository == null) {
            return;
        }
        tab.updatePrChip(session.prState(), session.prNumber());
        record Branches(String branch, String base) { }
        gitStatusService.getStatus(worktreeRoot).thenCombine(gitStatusService.getStatus(repository.root()),
                        (worktreeStatus, baseStatus) ->
                                new Branches(branchNameOf(worktreeStatus), branchNameOf(baseStatus)))
                .whenComplete((branches, ex) -> Platform.runLater(() -> {
                    if (ex != null || openTab.apply(sessionId) == null) {
                        return;
                    }
                    tab.configureWorktree(branches.branch(), branches.base(), worktreeRoot,
                            () -> showFinishPanel(sessionId, worktreeRoot, branches.branch(), branches.base()));
                    refreshWorktreeChips(tab, sessionId, worktreeRoot, branches.base());
                }));
    }

    /** Refreshes the ↑ahead + dirty/clean chips from the worktree's current git state. */
    private void refreshWorktreeChips(OpenSessionTab tab, ManagedSessionId sessionId, Path worktreeRoot,
                                      String base) {
        record StatusAndSummary(GitStatus status, GitChangeSummary summary) { }
        gitStatusService.getStatus(worktreeRoot)
                .thenCombine(gitStatusService.getChangeSummary(worktreeRoot, base), StatusAndSummary::new)
                .whenComplete((pair, ex) -> Platform.runLater(() -> {
                    if (ex != null || openTab.apply(sessionId) == null) {
                        return;
                    }
                    tab.updateWorktreeStatus(pair.status().dirty(), pair.summary().commitsAhead());
                }));
    }

    private Optional<ManagedAgentSession> sessionById(ManagedSessionId sessionId) {
        return sessionManager.sessions().stream().filter(s -> s.id().equals(sessionId)).findFirst();
    }

    private static String branchNameOf(GitStatus status) {
        return status.branch() instanceof GitBranchState.OnBranch onBranch ? onBranch.name() : DETACHED_LABEL;
    }

    /**
     * Opens the state-aware Finish panel. The branch's PR state is ALWAYS
     * re-checked first via read-only {@code gh pr view} (when {@code gh} is
     * available) -- not just for sessions already tracked as OPEN: a PR may
     * have been opened outside the app entirely (or merged since), and the
     * panel must not offer "Merge into base"/"Create pull request" for a
     * branch that already has one. The panel then renders the actions for
     * the reconciled state.
     */
    /**
     * Opens the Finish panel for {@code sessionId}, resolving its branch and
     * base first. The entry point a <em>submitted review</em> uses: the
     * review is over, and what follows -- merge, open a PR, delete the
     * worktree -- is the flow that already exists for exactly that.
     */
    void finishAfterReview(ManagedSessionId sessionId, Path worktreeRoot) {
        ManagedAgentSession session = sessionById(sessionId).orElse(null);
        Repository repository = session == null ? null : repositoryFor.apply(session).orElse(null);
        if (session == null || repository == null) {
            return;
        }
        gitStatusService.getStatus(worktreeRoot).thenCombine(gitStatusService.getStatus(repository.root()),
                        (worktreeStatus, baseStatus) ->
                                new String[] { branchNameOf(worktreeStatus), branchNameOf(baseStatus) })
                .whenComplete((branches, ex) -> Platform.runLater(() -> {
                    if (ex == null) {
                        showFinishPanel(sessionId, worktreeRoot, branches[0], branches[1]);
                    }
                }));
    }

    private void showFinishPanel(ManagedSessionId sessionId, Path worktreeRoot, String branch, String base) {
        ManagedAgentSession session = sessionById(sessionId).orElse(null);
        if (session == null || modalLayer == null) {
            return;
        }
        if (mergeInFlight.contains(sessionId)) {
            // A merge-and-finish is running and owns the modal layer; its own
            // progress/result modal is what the user should be looking at. The
            // click still has to do something visible (AGENTS.md): ⌘W'ing the
            // tab mid-flow and reopening the session from the sidebar builds a
            // fresh header with a live Finish ▸, whose click would otherwise
            // land here and vanish. The flow's own finish() restores the button.
            OpenSessionTab running = openTab.apply(sessionId);
            if (running != null) {
                running.showHandoffRunning("Merging…");
            }
            return;
        }
        // The pre-panel inspection (git status + change summary + gh pr
        // view) can take seconds; show a busy modal IMMEDIATELY so the
        // Finish click visibly did something, then swap in the real panel.
        Region busy = busyModal("Inspecting worktree & checking PR state…");
        modalLayer.show(busy);

        // Every probe below goes through AsyncCalls.attempt: the busy modal is
        // already up, and each of these starts with supplyAsync on a service
        // executor that throws RejectedExecutionException synchronously once
        // the app is shutting down. Such a throw would escape showFinishPanel
        // and leave that modal stranded with the native terminals hidden;
        // routed into the chain it reaches the whenComplete below, which
        // closes the modal and reports.
        CompletableFuture<Optional<GhCliService.PrInfo>> prRefresh =
                ghCliService.isAvailable()
                        ? AsyncCalls.attempt(() -> ghCliService.viewPr(worktreeRoot, branch))
                        : CompletableFuture.completedFuture(Optional.empty());

        record StatusAndSummary(GitStatus status, GitChangeSummary summary) { }
        record PrAndClean(Optional<GhCliService.PrInfo> prInfo, boolean worktreeClean) { }
        record Inspection(GitStatus status, GitChangeSummary summary, Optional<GhCliService.PrInfo> prInfo,
                          boolean worktreeClean) { }
        // The merge gate is WorktreeService.isWorktreeClean, not
        // GitStatus.dirty(): only the former ignores dirty submodules, and
        // gating on dirty() would disable merge forever in any repository
        // with a build-patched submodule (Drydock's own included). A failed
        // probe defaults to "clean" -- the flow's own pre-flight asks again
        // authoritatively and refuses with a reason, whereas defaulting to
        // "unclean" would grey the action out and blame the user's changes
        // for a git failure that had nothing to do with them.
        CompletableFuture<Boolean> cleanProbe =
                AsyncCalls.attempt(() -> worktreeService.isWorktreeClean(worktreeRoot))
                        .exceptionally(ex -> true);
        AsyncCalls.attempt(() -> gitStatusService.getStatus(worktreeRoot))
                .thenCombine(AsyncCalls.attempt(() -> gitStatusService.getChangeSummary(worktreeRoot, base))
                                .exceptionally(ex -> new GitChangeSummary(0, List.of())),
                        StatusAndSummary::new)
                .thenCombine(prRefresh.thenCombine(cleanProbe, PrAndClean::new),
                        (pair, extra) -> new Inspection(pair.status(), pair.summary(), extra.prInfo(),
                                extra.worktreeClean()))
                .whenComplete((data, ex) -> Platform.runLater(() -> {
                    if (busy.getParent() == null) {
                        return; // the user dismissed the busy modal; don't pop the panel open later
                    }
                    if (ex != null) {
                        modalLayer.close();
                        UiErrors.show("Could not inspect the worktree", ex);
                        return;
                    }
                    GitStatus status = data.status();
                    GitChangeSummary summary = data.summary();
                    Optional<GhCliService.PrInfo> prInfo = data.prInfo();

                    ManagedAgentSession current = sessionById(sessionId).orElse(null);
                    if (current == null) {
                        modalLayer.close();
                        return;
                    }
                    // Reconcile the observed PR (opened externally, merged
                    // since, number drift) before choosing actions. CLOSED/
                    // UNKNOWN make no lifecycle claim: a closed-unmerged PR
                    // leaves the branch free to merge or re-PR, so the
                    // tracked state stands.
                    if (prInfo.isPresent()) {
                        GhCliService.PrInfo info = prInfo.get();
                        PrState observed = switch (info.state()) {
                            case OPEN -> PrState.OPEN;
                            case MERGED -> PrState.MERGED;
                            case CLOSED, UNKNOWN -> null;
                        };
                        if (observed != null && (observed != current.prState()
                                || !current.prNumber().equals(Optional.of(info.number())))) {
                            current = sessionManager.updatePrState(sessionId, observed,
                                    Optional.of(info.number()));
                            OpenSessionTab tab = openTab.apply(sessionId);
                            if (tab != null) {
                                tab.updatePrChip(current.prState(), current.prNumber());
                            }
                            onSessionsChanged.run();
                        }
                    }

                    FinishWorktreePanel.Context context = new FinishWorktreePanel.Context(
                            branch, base, worktreeRoot, current.prState(), current.prNumber(),
                            prInfo.flatMap(GhCliService.PrInfo::url), Optional.of(summary), status.dirty(),
                            sessionManager.mayDeleteBranchOf(worktreeRoot), data.worktreeClean());
                    FinishWorktreePanel panel = new FinishWorktreePanel(context, new FinishWorktreePanel.Actions() {
                        @Override
                        public void mergeIntoBase() {
                            startMergeAndFinish(sessionId, worktreeRoot, branch, base);
                        }

                        @Override
                        public void createPullRequest() {
                            handoffCreatePr(sessionId, worktreeRoot, branch);
                        }

                        @Override
                        public void deleteWorktree() {
                            handoffDelete(sessionId, worktreeRoot, branch);
                        }

                        @Override
                        public void viewPullRequest(String url) {
                            openInBrowser(url);
                        }
                    }, modalLayer::close);
                    modalLayer.show(panel);
                }));
    }

    /** A small centered busy modal (spinner + message) for pre-panel async inspections. */
    private static Region busyModal(String message) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label label = new Label(message);
        label.getStyleClass().add("finish-action-caption");
        VBox box = new VBox(10, spinner, label);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("modal");
        box.setMaxWidth(320);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        return box;
    }

    // ---- The three finish actions: merge-and-finish, PR hand-off, delete ----

    /**
     * Starts the merge-and-finish flow, at most one per session: the Finish
     * panel closes before its action runs and the flow's modal is
     * dismissible without cancelling the work, so a second click would
     * otherwise run a second cleanup over an already-deleted worktree and
     * report an error after the first flow reported success.
     *
     * <p>{@code base} reaches here from {@code branchNameOf(GitStatus)}, i.e.
     * {@code GitBranchState.OnBranch.name()} -- a SHORT branch name, which is
     * what {@link MergeFinishDecision#forPreflight} compares against {@code
     * git symbolic-ref --short}. A blank {@code branch} is refused outright
     * rather than passed on: the flow's terminal copy interpolates it
     * unguarded, so it would render "branch  kept (already existed)" about a
     * branch nobody named. {@link #DETACHED_LABEL} is refused for the same
     * reason, with copy of its own.</p>
     */
    private void startMergeAndFinish(ManagedSessionId sessionId, Path worktreeRoot, String branch, String base) {
        if (branch == null || branch.isBlank() || base == null || base.isBlank()) {
            LOG.log(Level.WARNING, "Refusing merge-and-finish for session " + sessionId
                    + ": branch=" + branch + " base=" + base);
            return;
        }
        // The same guard for the other non-branch a header label can be: the
        // DETACHED_LABEL sentinel would otherwise be interpolated as if it were
        // a branch, producing "Check out (detached) in the main checkout" or
        // "Branch (detached) no longer exists". Reported rather than silently
        // dropped -- the Finish panel has already closed, so a bare return
        // would leave the click having done nothing at all.
        if (branch.equals(DETACHED_LABEL) || base.equals(DETACHED_LABEL)) {
            MergeFinishDecision.Next.Stopped refusal =
                    MergeFinishDecision.forDetachedHeadLabel(branch.equals(DETACHED_LABEL));
            UiErrors.show("Cannot merge", refusal.headline(), refusal.detail());
            return;
        }
        Repository repository = sessionById(sessionId).flatMap(repositoryFor).orElse(null);
        if (repository == null || modalLayer == null || !mergeInFlight.add(sessionId)) {
            return;
        }
        new MergeAndFinishFlow(worktreeService, sessionManager, modalLayer, cleanup, openTab,
                onSessionsChanged, onSessionDeleted, () -> mergeInFlight.remove(sessionId))
                .start(sessionId, repository.root(), worktreeRoot, branch, base);
    }

    private void handoffCreatePr(ManagedSessionId sessionId, Path worktreeRoot, String branch) {
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab == null) {
            return;
        }
        tab.showHandoffRunning(tab.agentName() + " is opening a PR…");
        tab.sendPrompt("Push this worktree's branch '" + branch + "' to origin (git push -u origin " + branch
                + ") and open a pull request with gh pr create --fill, then report the PR number.");
        if (!ghCliService.isAvailable()) {
            // No gh to observe with: optimistic OPEN (chip without a number)
            // after a grace period, per the agreed fallback.
            PauseTransition optimistic = new PauseTransition(Duration.seconds(30));
            optimistic.setOnFinished(e -> {
                if (openTab.apply(sessionId) == null) {
                    return;
                }
                applyPrState(sessionId, PrState.OPEN, Optional.empty());
                tab.showHandoffDone("PR opened");
                restoreFinishLater(tab, sessionId);
            });
            optimistic.play();
            return;
        }
        pollHandoffResult(sessionId,
                () -> ghCliService.viewPr(worktreeRoot, branch),
                prInfo -> {
                    PrState state = prInfo.state() == GhCliService.PrInfo.PrLifecycle.MERGED
                            ? PrState.MERGED : PrState.OPEN;
                    applyPrState(sessionId, state, Optional.of(prInfo.number()));
                    tab.showHandoffDone("PR opened");
                    restoreFinishLater(tab, sessionId);
                });
    }

    private void handoffDelete(ManagedSessionId sessionId, Path worktreeRoot, String branch) {
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab == null) {
            return;
        }
        Repository repository = sessionById(sessionId).flatMap(repositoryFor).orElse(null);
        if (repository == null) {
            return;
        }
        tab.showHandoffRunning("Removing worktree…");
        // The same destructive sequence merge-and-finish runs: a worktree that
        // survived keeps its session open, and a deleteSession that failed is
        // reported instead of being assumed to have worked. The branch plan is
        // forRequestedDelete, not the merge flow's forBranchDelete -- this
        // delete is what the user asked for outright, so a branch tip that
        // moved is not a reason to refuse it.
        cleanup.run(sessionId, repository.root(), worktreeRoot, branch,
                        MergeFinishDecision.forRequestedDelete(sessionManager.mayDeleteBranchOf(worktreeRoot)))
                .whenComplete((outcome, ex) -> Platform.runLater(() -> {
                    // Re-resolve rather than reuse the captured tab: ⌘W during
                    // `git worktree remove` disposes it, and header updates on a
                    // detached node (one of them arming a 5s PauseTransition)
                    // are work nobody will ever see. The error alert and the
                    // model updates below stay unconditional -- they are not
                    // about a tab watching.
                    OpenSessionTab live = openTab.apply(sessionId);
                    if (ex != null) {
                        restoreFinishIfOpen(live);
                        UiErrors.show("Could not remove the worktree", ex);
                        return;
                    }
                    if (!outcome.worktreeRemoved()) {
                        restoreFinishIfOpen(live);
                        UiErrors.show("Could not remove the worktree", "The worktree was kept",
                                outcome.worktreeKeptReason().orElse("git refused to remove it"));
                        return;
                    }
                    // No "✓ Removed" pill: with the removal now confirmed
                    // synchronously it would be negated in the same FX pulse --
                    // by the tab's removal below, or by restoring the Finish
                    // button when the session survived. The tab disappearing is
                    // the feedback.
                    if (outcome.sessionDeleted()) {
                        onSessionDeleted.accept(sessionId);
                    } else if (live != null) {
                        // The worktree is gone but the session outlived it
                        // (WorktreeSessionCleanup logged why). Saying so beats a
                        // sidebar row that simply never disappears.
                        live.restoreFinishButton();
                        live.showTransientNotice(
                                "⏺ Worktree removed, but the session stayed open — close its tab manually.");
                    }
                    onSessionsChanged.run();
                }));
    }

    /** Puts the header's Finish ▸ back, if there is still a header to put it in. */
    private static void restoreFinishIfOpen(OpenSessionTab tab) {
        if (tab != null) {
            tab.restoreFinishButton();
        }
    }

    private void applyPrState(ManagedSessionId sessionId, PrState state, Optional<Integer> number) {
        sessionManager.updatePrState(sessionId, state, number);
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab != null) {
            tab.updatePrChip(state, number);
        }
        onSessionsChanged.run();
    }

    /** Leaves the ✓ pill visible briefly, then restores the Finish ▸ button. */
    private void restoreFinishLater(OpenSessionTab tab, ManagedSessionId sessionId) {
        PauseTransition pause = new PauseTransition(Duration.seconds(4));
        pause.setOnFinished(e -> {
            if (openTab.apply(sessionId) != null) {
                tab.restoreFinishButton();
            }
        });
        pause.play();
    }

    private <T> void pollHandoffResult(ManagedSessionId sessionId,
                                       Supplier<CompletableFuture<Optional<T>>> probe,
                                       Consumer<T> onConfirmed) {
        pollHandoffStep(sessionId, probe, onConfirmed, 0);
    }

    private <T> void pollHandoffStep(ManagedSessionId sessionId,
                                     Supplier<CompletableFuture<Optional<T>>> probe,
                                     Consumer<T> onConfirmed, int attempt) {
        if (attempt >= HANDOFF_POLL_MAX_ATTEMPTS) {
            OpenSessionTab tab = openTab.apply(sessionId);
            if (tab != null) {
                // Say WHY the pill vanished -- a silent Finish-button return
                // reads as "it worked" when nothing was ever confirmed.
                tab.restoreFinishButton();
                tab.showTransientNotice("⏺ Hand-off not confirmed — check the terminal, then Finish ▸ again.");
            }
            return;
        }
        PauseTransition wait = new PauseTransition(HANDOFF_POLL_INTERVAL);
        wait.setOnFinished(e -> probe.get().whenComplete((result, ex) -> Platform.runLater(() -> {
            if (openTab.apply(sessionId) == null) {
                return; // tab closed mid-handoff; nothing left to update
            }
            if (ex == null && result != null && result.isPresent()) {
                onConfirmed.accept(result.get());
            } else {
                pollHandoffStep(sessionId, probe, onConfirmed, attempt + 1);
            }
        })));
        wait.play();
    }

    /**
     * macOS-native URL open (the app is macOS-only; ProcessBuilder avoids an
     * AWT dependency). The spawn runs off-thread per AGENTS.md -- {@code
     * open} returns instantly on success, so the browser appearing IS the
     * progress indication; a failure surfaces as an error alert.
     */
    private void openInBrowser(String url) {
        Thread.ofVirtual().start(() -> {
            try {
                new ProcessBuilder("open", url).start();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not open " + url, e);
                Platform.runLater(() -> UiErrors.show("Could not open " + url, e));
            }
        });
    }
}
