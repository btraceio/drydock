package app.drydock.ui.review;

import app.drydock.review.ReviewScope;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The checkout gate (spec §6): what a pull request with no session shows
 * instead of a board.
 *
 * <p>A gate rather than a convenience. A PR with no checkout has no worktree,
 * so it has no session, so it has no agent -- there is nothing for a reviewer
 * to run in. The two ways forward are therefore genuinely different, and both
 * are offered: start a worktree and a session, or read the patch with the
 * agent actions unavailable.</p>
 *
 * <p>The commands are shown verbatim because the reader may want to run them
 * themselves. They are the commands drydock actually runs -- notably
 * <em>not</em> the handoff's {@code gh pr checkout --worktree}, which is not
 * a flag {@code gh} has (see {@code PrCheckoutService}).</p>
 */
final class ReviewCheckoutGate extends VBox {

    /** What the gate can ask its host to do. */
    interface Host {
        /** {@code Start session & review ▸}: worktree, checkout, session, scope grant. */
        void startSessionAndReview(ReviewScope scope);

        /** {@code Read the patch only}: the PR's diff, with no worktree and no agent. */
        void readPatchOnly(ReviewScope scope);

        /**
         * The agent-launch line for {@code scope}'s repository -- the command
         * {@code Start session & review ▸} will really run, which is the
         * repository's own agent and not necessarily Claude. Answers on the FX
         * thread; building it can touch the filesystem (locating the
         * executable), so it is a callback rather than a return value.
         */
        void launchCommandPreview(ReviewScope scope, Consumer<String> onReady);
    }

    private final Host host;
    private final Label title = new Label();
    private final Label detail = new Label();
    private final Label commands = new Label();
    private final Button startButton = new Button("Start session & review ▸");
    private final Button readOnlyButton = new Button("Read the patch only");
    private final Label progressLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label failureLabel = new Label();
    private final HBox actions = new HBox(10);
    private final HBox progressRow = new HBox(8);

    private ReviewScope scope;

    ReviewCheckoutGate(Host host) {
        this.host = host;
        getStyleClass().add("review-gate");
        setAlignment(Pos.CENTER);
        setSpacing(10);

        title.getStyleClass().add("review-placeholder-title");
        detail.getStyleClass().add("review-placeholder-detail");
        detail.setWrapText(true);
        detail.setMaxWidth(560);
        commands.getStyleClass().add("review-placeholder-mono");

        startButton.getStyleClass().addAll("review-verdict-action", "primary");
        startButton.setOnAction(e -> start());
        readOnlyButton.getStyleClass().add("review-verdict-action");
        readOnlyButton.setOnAction(e -> readPatchOnly());
        actions.setAlignment(Pos.CENTER);
        actions.getChildren().setAll(startButton, readOnlyButton);

        progress.setPrefSize(14, 14);
        progressLabel.getStyleClass().add("review-placeholder-detail");
        progressRow.setAlignment(Pos.CENTER);
        progressRow.getChildren().setAll(progress, progressLabel);
        setProgressVisible(false);

        failureLabel.getStyleClass().add("review-gate-failure");
        failureLabel.setWrapText(true);
        failureLabel.setMaxWidth(560);
        failureLabel.setVisible(false);
        failureLabel.setManaged(false);

        VBox.setVgrow(this, Priority.ALWAYS);
        getChildren().setAll(title, detail, commands, actions, progressRow, failureLabel);
    }

    /** Points the gate at {@code newScope}, clearing any previous progress or failure. */
    void setScope(ReviewScope newScope) {
        this.scope = newScope;
        int number = newScope.pr().map(ReviewScope.PullRequestRef::number).orElse(0);
        title.setText(number > 0
                ? "PR #" + number + " has no session yet"
                : "This scope has no checkout yet");
        detail.setText("Reviewing it starts a worktree and a session, so an agent can read the diff "
                + "and answer questions about it. Reading the patch needs neither, but the agent "
                + "actions stay unavailable.");
        // The launch line names whichever agent this repository uses, so it
        // is filled in asynchronously -- a hard-coded "claude" here was wrong
        // for every repository whose agent is not Claude.
        String checkoutLines = "git worktree add --detach <worktree>\n"
                + "gh pr checkout " + (number > 0 ? number : "<n>") + " --branch pr-"
                + (number > 0 ? number : "<n>") + "   (run inside the worktree)\n";
        commands.setText(checkoutLines + "…");
        host.launchCommandPreview(newScope, command -> {
            // Late answers for a scope the gate has moved on from must not
            // overwrite the line it is showing now.
            if (newScope.equals(scope)) {
                commands.setText(checkoutLines + command);
            }
        });
        setProgressVisible(false);
        clearFailure();
        startButton.setDisable(false);
        readOnlyButton.setDisable(false);
    }

    private void start() {
        if (scope == null) {
            return;
        }
        // The click must visibly do something before the result arrives: the
        // checkout is a network fetch and can take a while.
        clearFailure();
        setProgressVisible(true);
        progressLabel.setText("Creating the worktree and checking the pull request out…");
        startButton.setDisable(true);
        readOnlyButton.setDisable(true);
        host.startSessionAndReview(scope);
    }

    private void readPatchOnly() {
        if (scope == null) {
            return;
        }
        clearFailure();
        setProgressVisible(true);
        progressLabel.setText("Reading the pull request patch…");
        startButton.setDisable(true);
        readOnlyButton.setDisable(true);
        host.readPatchOnly(scope);
    }

    boolean isShowing(ReviewScope candidate) {
        return scope != null && scope.id().equals(candidate.id());
    }

    /** Reports a failed checkout; the gate returns to its offer so it can be retried. */
    void showFailure(String message) {
        setProgressVisible(false);
        failureLabel.setText("⚠ " + message);
        failureLabel.setVisible(true);
        failureLabel.setManaged(true);
        startButton.setDisable(false);
        readOnlyButton.setDisable(false);
    }

    private void clearFailure() {
        failureLabel.setVisible(false);
        failureLabel.setManaged(false);
    }

    private void setProgressVisible(boolean visible) {
        progressRow.setVisible(visible);
        progressRow.setManaged(visible);
    }

    /** A read-only patch view: the diff, and a note that the agent actions need a session. */
    static Region readOnlyBanner(int prNumber) {
        Label label = new Label("Reading PR #" + prNumber + " without a checkout — "
                + "no session is bound, so the agent actions are unavailable.");
        label.getStyleClass().add("review-readonly-banner");
        label.setWrapText(true);
        HBox box = new HBox(label);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("review-readonly-banner-row");
        return box;
    }
}
