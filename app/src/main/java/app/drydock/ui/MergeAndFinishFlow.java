package app.drydock.ui;

import app.drydock.app.SessionManager;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.WorktreeService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Merge-and-finish: the Finish panel's "Merge into &lt;base&gt;" from the
 * click to the sidebar row disappearing (see
 * docs/superpowers/specs/2026-07-25-merge-and-finish-design.md).
 *
 * <p>A thin JavaFX shell. Every decision and every string comes from {@link
 * MergeFinishDecision}, and the destructive tail from {@link
 * WorktreeSessionCleanup}, both FX-free and unit-tested; what is left here
 * is the async plumbing and one modal.</p>
 *
 * <p>Two lifecycle rules, both learned from the flow this replaces. First,
 * the flow owns its own liveness: the old {@code handoffMerge} returned
 * silently whenever the session's tab had closed, which -- now that the
 * progress indication is a modal rather than a header pill -- would strand
 * that modal and leave the native terminals hidden. The tab is updated only
 * opportunistically. Second, the modal layer is shared and dismissible, so
 * the flow replaces only a modal it still owns; if the user dismissed it and
 * opened something else, the result degrades to a transient notice rather
 * than yanking their modal away.</p>
 *
 * <p>Single-use: {@link #start} records the session it is finishing in
 * fields the whole flow then reads, so the caller builds one instance per
 * run (and, via its own in-flight guard, at most one per session).</p>
 */
final class MergeAndFinishFlow {

    /** Matches the PR hand-off's cadence and cap: every 4s, up to 5 minutes. */
    private static final Duration POLL_INTERVAL = Duration.seconds(4);
    private static final int POLL_MAX_ATTEMPTS = 75;

    private final WorktreeService worktreeService;
    private final SessionManager sessionManager;
    private final ModalLayer modalLayer;
    private final WorktreeSessionCleanup cleanup;
    private final Function<ManagedSessionId, OpenSessionTab> openTab;
    private final Runnable onSessionsChanged;
    private final Consumer<ManagedSessionId> onSessionDeleted;
    private final Runnable onFinished;

    private ManagedSessionId sessionId;
    private Path repositoryRoot;
    private Path worktreeRoot;
    private String branch;
    private String base;
    private WorktreeService.MergeTarget target;
    private boolean conflictsHandedOff;
    private Optional<String> lastProbeDetail = Optional.empty();
    /** The node this flow currently owns in the shared modal layer. */
    private Region ownModal;

    MergeAndFinishFlow(WorktreeService worktreeService, SessionManager sessionManager, ModalLayer modalLayer,
                       WorktreeSessionCleanup cleanup, Function<ManagedSessionId, OpenSessionTab> openTab,
                       Runnable onSessionsChanged, Consumer<ManagedSessionId> onSessionDeleted,
                       Runnable onFinished) {
        this.worktreeService = worktreeService;
        this.sessionManager = sessionManager;
        this.modalLayer = modalLayer;
        this.cleanup = cleanup;
        this.openTab = openTab;
        this.onSessionsChanged = onSessionsChanged;
        this.onSessionDeleted = onSessionDeleted;
        this.onFinished = onFinished;
    }

    /**
     * FX thread. Shows progress before the first git call, per AGENTS.md.
     *
     * <p>{@code base} must be a SHORT local branch name ({@code main}, not
     * {@code origin/main} or {@code refs/heads/main}): {@link
     * MergeFinishDecision#forPreflight} compares it to {@code git
     * symbolic-ref --short}'s answer with {@code String.equals}, so a
     * qualified name would refuse every merge with "the main checkout is on
     * main, not origin/main".</p>
     */
    void start(ManagedSessionId sessionId, Path repositoryRoot, Path worktreeRoot, String branch, String base) {
        this.sessionId = sessionId;
        this.repositoryRoot = repositoryRoot;
        this.worktreeRoot = worktreeRoot;
        this.branch = branch;
        this.base = base;
        showBusy("Checking the main checkout…");
        // The header's Finish ▸ becomes the flow's pill for the same reason
        // the other hand-offs do it: the modal is dismissible, and a Finish
        // button left looking live would swallow the click behind the
        // controller's in-flight guard with nothing to show for it.
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab != null) {
            tab.showHandoffRunning("Merging…");
        }
        attempt(() -> worktreeService.inspectMergeTarget(repositoryRoot, branch)
                .thenCombine(worktreeService.isWorktreeClean(worktreeRoot), PreflightData::new))
                .whenComplete((data, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        stop(MergeFinishDecision.forFailedInspection(messageOf(ex)));
                        return;
                    }
                    this.target = data.target();
                    apply(MergeFinishDecision.forPreflight(data.target(), base, branch, data.worktreeClean()));
                }));
    }

    private record PreflightData(WorktreeService.MergeTarget target, boolean worktreeClean) { }

    /** FX thread. Executes one decision. */
    private void apply(MergeFinishDecision.Next next) {
        switch (next) {
            case MergeFinishDecision.Next.Merge ignored -> runMerge();
            case MergeFinishDecision.Next.HandOff handOff -> handOff(handOff);
            // KeepWaiting only ever comes from a post-hand-off verdict, which
            // pollAgain answers itself with the attempt count it already has;
            // restarting the count from 0 here is correct precisely because
            // nothing routes a poll's verdict back through this switch.
            case MergeFinishDecision.Next.KeepWaiting ignored -> pollAgain(0);
            case MergeFinishDecision.Next.CleanUp ignored -> runCleanup();
            case MergeFinishDecision.Next.Done done -> done(done);
            case MergeFinishDecision.Next.Stopped stopped -> stop(stopped);
        }
    }

    private void runMerge() {
        showBusy("Merging " + branch + " into " + base + "…");
        attempt(() -> worktreeService.merge(repositoryRoot, branch, target))
                .whenComplete((verdict, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        stop(MergeFinishDecision.forFailedMerge(branch, base, messageOf(ex)));
                        return;
                    }
                    apply(MergeFinishDecision.forVerdict(verdict, repositoryRoot.toString(), branch, base, false));
                }));
    }

    private void handOff(MergeFinishDecision.Next.HandOff handOff) {
        conflictsHandedOff = true;
        showBusy(handOff.headline());
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab == null) {
            // No terminal to hand off to: the merge is open in the main
            // checkout and only the user can finish it.
            stop("Conflicts need resolving", "The merge of " + branch + " into " + base
                    + " is open in the main checkout at " + repositoryRoot
                    + ", but this session's terminal is closed. Resolve it there. Nothing was deleted.");
            return;
        }
        tab.sendPrompt(handOff.prompt());
        pollAgain(0);
    }

    private void pollAgain(int attemptNumber) {
        if (attemptNumber >= POLL_MAX_ATTEMPTS) {
            stop(MergeFinishDecision.forTimeout(branch, base, lastProbeDetail));
            return;
        }
        PauseTransition wait = new PauseTransition(POLL_INTERVAL);
        wait.setOnFinished(e -> attempt(() -> worktreeService.verifyMerge(repositoryRoot, target))
                .whenComplete((verdict, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        // A probe failure is never a verdict: record it for the
                        // timeout message and keep waiting.
                        lastProbeDetail = Optional.of(messageOf(ex));
                        pollAgain(attemptNumber + 1);
                        return;
                    }
                    MergeFinishDecision.Next next =
                            MergeFinishDecision.forVerdict(verdict, repositoryRoot.toString(), branch, base, true);
                    if (next instanceof MergeFinishDecision.Next.KeepWaiting) {
                        pollAgain(attemptNumber + 1);
                        return;
                    }
                    apply(next);
                })));
        wait.play();
    }

    /**
     * The destructive step, gated on one last look at the branch.
     *
     * <p>The verdict that got us here proves a merge commit of the tip
     * recorded at pre-flight is on the base branch -- it says nothing about
     * where the branch points now, and on the hand-off path minutes have
     * passed with the session's Claude sitting in the worktree. So the tip is
     * re-read here, on the FX-free side of the fence, and {@link
     * MergeFinishDecision#forBranchDelete} turns any movement (or a re-read
     * that failed) into a refusal to run {@code git branch -D}. Removing the
     * worktree stays safe either way: the commits are on the branch, which
     * survives.</p>
     */
    private void runCleanup() {
        showBusy("Removing worktree…");
        boolean branchIsOurs = sessionManager.mayDeleteBranchOf(worktreeRoot);
        Optional<String> recordedTip = target.branchTipOid();
        attempt(() -> worktreeService.inspectMergeTarget(repositoryRoot, branch))
                // A re-inspection that failed becomes an unknown tip, not the
                // recorded one: forBranchDelete treats "we could not ask" as
                // drift, so an unreadable repository keeps the branch instead
                // of deleting it on an assumption.
                .handle((fresh, ex) -> ex == null ? fresh.branchTipOid() : Optional.<String>empty())
                .thenCompose(currentTip -> attempt(() -> cleanup.run(sessionId, repositoryRoot, worktreeRoot, branch,
                        MergeFinishDecision.forBranchDelete(branchIsOurs, recordedTip, currentTip))))
                .whenComplete((outcome, ex) -> Platform.runLater(() -> {
                    if (ex != null) {
                        // The merge landed; only the cleanup call failed to
                        // run, so this is a Done, not a ✗.
                        done(MergeFinishDecision.forFailedCleanup(branch, base, conflictsHandedOff,
                                messageOf(ex)));
                        return;
                    }
                    if (outcome.sessionDeleted()) {
                        onSessionDeleted.accept(sessionId);
                    }
                    onSessionsChanged.run();
                    apply(MergeFinishDecision.forCleanup(outcome, branch, base, conflictsHandedOff));
                }));
    }

    // ---- Modal rendering ----------------------------------------------------

    /**
     * A progress render. Deliberately ignores losing the modal layer: there is
     * nothing to tell the user yet, and announcing a stage as if it were a
     * result -- which one shared render path did -- reported
     * "Merge-and-finish finished" three times per flow, the first time before
     * the merge had started.
     */
    private void showBusy(String message) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label label = new Label(message);
        label.getStyleClass().add("finish-action-caption");
        label.setWrapText(true);
        VBox box = new VBox(10, spinner, label);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("modal");
        box.setMaxWidth(360);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        render(box);
    }

    private void done(MergeFinishDecision.Next.Done outcome) {
        renderTerminal(terminalModal(outcome.headline(), outcome.detail(), "merge-flow-headline"),
                outcome.headline());
        finish();
    }

    private void stop(MergeFinishDecision.Next.Stopped stopped) {
        stop(stopped.headline(), stopped.detail());
    }

    private void stop(String headline, String detail) {
        renderTerminal(terminalModal("✗ " + headline, detail, "merge-flow-headline-error"), headline);
        finish();
    }

    /**
     * Both terminal paths end here: restore the header control the flow took
     * over, then release the controller's in-flight guard. On the success path
     * the tab is already gone (the session was deleted before the outcome was
     * rendered), which is why the restore is conditional rather than ordered
     * around the deletion.
     */
    private void finish() {
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab != null) {
            tab.restoreFinishButton();
        }
        onFinished.run();
    }

    private Region terminalModal(String headlineText, String detailText, String headlineStyleClass) {
        Label headline = new Label(headlineText);
        headline.getStyleClass().add(headlineStyleClass);
        headline.setWrapText(true);
        Label detail = new Label(detailText);
        detail.getStyleClass().add("merge-flow-detail");
        detail.setWrapText(true);
        Button done = new Button("Done");
        done.getStyleClass().add("worktree-create-button");
        done.setDefaultButton(true);
        done.setOnAction(e -> modalLayer.close());
        HBox actions = new HBox(done);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(12, headline, detail, actions);
        box.getStyleClass().add("modal");
        box.setMaxWidth(460);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        return box;
    }

    /**
     * A result render: falls back to the tab header when the modal layer is
     * no longer ours, so the outcome is not simply lost. The notice repeats
     * the decision's own headline rather than inventing a summary -- the
     * previous "reopen Finish ▸ for the details" was advice the user could not
     * take, since the in-flight guard refuses Finish while the flow runs and
     * after a success there is no session left to reopen it on.
     */
    private void renderTerminal(Region node, String noticeHeadline) {
        if (render(node)) {
            return;
        }
        OpenSessionTab tab = openTab.apply(sessionId);
        if (tab != null) {
            tab.showTransientNotice("⏺ " + noticeHeadline);
        }
    }

    /**
     * Puts {@code node} in the shared modal layer, but only if this flow
     * still owns what is showing (or nothing is): dismissing the progress
     * modal does not cancel the work, so by the time a result arrives the
     * user may have opened an unrelated modal -- {@code ModalLayer.show}
     * would replace it and drop its {@code onClosed} on the floor.
     *
     * @return whether {@code node} is now showing; {@code false} means the
     *         layer belongs to a modal the user opened themselves
     */
    private boolean render(Region node) {
        boolean ownsLayer = ownModal != null && ownModal.getParent() != null;
        if (ownsLayer || !modalLayer.isShowingModal()) {
            ownModal = node;
            modalLayer.show(node);
            return true;
        }
        ownModal = null;
        return false;
    }

    /**
     * Routes a collaborator's synchronous throw into the {@code whenComplete}
     * branch that already has copy for it (see {@link AsyncCalls}). Real at
     * shutdown: every {@link WorktreeService} call here starts with {@code
     * CompletableFuture.supplyAsync(..., executor)}, which throws {@code
     * RejectedExecutionException} once that executor is shut down -- and an
     * exception escaping {@link #start} would leave the busy modal on screen
     * with the controller's in-flight guard never cleared.
     */
    private static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> call) {
        return AsyncCalls.attempt(call);
    }

    private static String messageOf(Throwable failure) {
        Throwable cause = UiErrors.unwrap(failure);
        return Optional.ofNullable(cause.getMessage()).orElse(cause.getClass().getSimpleName());
    }
}
