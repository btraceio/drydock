package app.drydock.ui;

import app.drydock.app.SessionManager;
import app.drydock.domain.ManagedClaudeSession;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The worktree-finish lifecycle (worktree handoff, section B), extracted
 * from {@code MainWorkspace} (see docs/plans/workspace-split-design.md):
 * the worktree session header (context line, ↑ahead/dirty/PR chips), the
 * state-aware Finish panel with its pre-panel inspection and PR-state
 * reconciliation, and the three finish actions. Merge and delete run
 * directly via {@link WorktreeService} -- trivial, deterministic git
 * operations that don't need an agent in the loop; only "create PR" is
 * still handed off to the Claude session in the terminal, with a
 * {@code PauseTransition}-based confirmation poll since {@code gh pr
 * create} needs the user's own gh auth.
 *
 * <p>Collaborators are injected: the services doing the actual git/gh
 * work, the modal layer the panels show through, and callbacks into the
 * workspace ({@code openTab} -- the liveness lookup every async completion
 * guards on; {@code onSessionsChanged}; {@code onSessionDeleted}). All
 * methods run on the FX Application Thread; async completions hop back via
 * {@code Platform.runLater} exactly as before the extraction.</p>
 */
final class WorktreeLifecycleController {

    private static final Logger LOG = System.getLogger(WorktreeLifecycleController.class.getName());

    /** Handoff polling caps: every 4s, up to 5 minutes; on timeout the Finish button quietly returns. */
    private static final Duration HANDOFF_POLL_INTERVAL = Duration.seconds(4);
    private static final int HANDOFF_POLL_MAX_ATTEMPTS = 75;

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
    private final Function<ManagedClaudeSession, Optional<Repository>> repositoryFor;
    /** Invoked (on the FX Application Thread) after any session/PR state change the sidebar must reflect. */
    private final Runnable onSessionsChanged;
    /** Invoked after a deleted worktree's session row is removed (delegates to {@code MainWorkspace.noteSessionDeleted}). */
    private final Consumer<ManagedSessionId> onSessionDeleted;

    /** The app shell's modal layer; wired late by DrydockApplication via {@code MainWorkspace.setModalLayer}. */
    private ModalLayer modalLayer;

    WorktreeLifecycleController(SessionManager sessionManager, GitStatusService gitStatusService,
                                GhCliService ghCliService, WorktreeService worktreeService,
                                Function<ManagedSessionId, OpenSessionTab> openTab,
                                Function<ManagedClaudeSession, Optional<Repository>> repositoryFor,
                                Runnable onSessionsChanged, Consumer<ManagedSessionId> onSessionDeleted) {
        this.sessionManager = sessionManager;
        this.gitStatusService = gitStatusService;
        this.ghCliService = ghCliService;
        this.worktreeService = worktreeService;
        this.openTab = openTab;
        this.repositoryFor = repositoryFor;
        this.onSessionsChanged = onSessionsChanged;
        this.onSessionDeleted = onSessionDeleted;
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
        ManagedClaudeSession session = sessionById(sessionId).orElse(null);
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

    private Optional<ManagedClaudeSession> sessionById(ManagedSessionId sessionId) {
        return sessionManager.sessions().stream().filter(s -> s.id().equals(sessionId)).findFirst();
    }

    private static String branchNameOf(GitStatus status) {
        return status.branch() instanceof GitBranchState.OnBranch onBranch ? onBranch.name() : "(detached)";
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
    private void showFinishPanel(ManagedSessionId sessionId, Path worktreeRoot, String branch, String base) {
        ManagedClaudeSession session = sessionById(sessionId).orElse(null);
        if (session == null || modalLayer == null) {
            return;
        }
        // The pre-panel inspection (git status + change summary + gh pr
        // view) can take seconds; show a busy modal IMMEDIATELY so the
        // Finish click visibly did something, then swap in the real panel.
        Region busy = busyModal("Inspecting worktree & checking PR state…");
        modalLayer.show(busy);

        CompletableFuture<Optional<GhCliService.PrInfo>> prRefresh =
                ghCliService.isAvailable()
                        ? ghCliService.viewPr(worktreeRoot, branch)
                        : CompletableFuture.completedFuture(Optional.empty());

        record StatusAndSummary(GitStatus status, GitChangeSummary summary) { }
        record Inspection(GitStatus status, GitChangeSummary summary, Optional<GhCliService.PrInfo> prInfo) { }
        gitStatusService.getStatus(worktreeRoot)
                .thenCombine(gitStatusService.getChangeSummary(worktreeRoot, base)
                                .exceptionally(ex -> new GitChangeSummary(0, List.of())),
                        StatusAndSummary::new)
                .thenCombine(prRefresh, (pair, prInfo) -> new Inspection(pair.status(), pair.summary(), prInfo))
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

                    ManagedClaudeSession current = sessionById(sessionId).orElse(null);
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
                            prInfo.flatMap(GhCliService.PrInfo::url), Optional.of(summary), status.dirty());
                    FinishWorktreePanel panel = new FinishWorktreePanel(context, new FinishWorktreePanel.Actions() {
                        @Override
                        public void mergeIntoBase() {
                            handoffMerge(sessionId, worktreeRoot, branch, base);
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

    // ---- Merge/delete run directly; only PR creation is a Claude hand-off ----

    private void handoffMerge(ManagedSessionId sessionId, Path worktreeRoot, String branch, String base) {
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab == null) {
            return;
        }
        Repository repository = sessionById(sessionId).flatMap(repositoryFor).orElse(null);
        if (repository == null) {
            return;
        }
        tab.showHandoffRunning("Merging…");
        worktreeService.mergeIntoBase(repository.root(), branch)
                .whenComplete((v, ex) -> Platform.runLater(() -> {
                    if (openTab.apply(sessionId) == null) {
                        return;
                    }
                    if (ex != null) {
                        tab.restoreFinishButton();
                        UiErrors.show("Could not merge '" + branch + "' into '" + base + "'", ex);
                        return;
                    }
                    tab.showHandoffDone("Merged");
                    refreshWorktreeChipsLater(sessionId, worktreeRoot, base);
                    restoreFinishLater(tab, sessionId);
                }));
    }

    private void handoffCreatePr(ManagedSessionId sessionId, Path worktreeRoot, String branch) {
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab == null) {
            return;
        }
        tab.showHandoffRunning("Claude is opening a PR…");
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
        worktreeService.remove(repository.root(), worktreeRoot, Optional.of(branch))
                .whenComplete((v, ex) -> Platform.runLater(() -> {
                    if (openTab.apply(sessionId) == null) {
                        return;
                    }
                    if (ex != null) {
                        tab.restoreFinishButton();
                        UiErrors.show("Could not remove the worktree", ex);
                        return;
                    }
                    tab.showHandoffDone("Removed");
                    PauseTransition removeRow = new PauseTransition(Duration.seconds(1.2));
                    removeRow.setOnFinished(e -> sessionManager.deleteSession(sessionId)
                            .whenComplete((v2, ex2) -> Platform.runLater(() -> {
                                onSessionDeleted.accept(sessionId);
                                onSessionsChanged.run();
                            })));
                    removeRow.play();
                }));
    }

    private void applyPrState(ManagedSessionId sessionId, PrState state, Optional<Integer> number) {
        sessionManager.updatePrState(sessionId, state, number);
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab != null) {
            tab.updatePrChip(state, number);
        }
        onSessionsChanged.run();
    }

    private void refreshWorktreeChipsLater(ManagedSessionId sessionId, Path worktreeRoot, String base) {
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab != null) {
            refreshWorktreeChips(tab, sessionId, worktreeRoot, base);
        }
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
