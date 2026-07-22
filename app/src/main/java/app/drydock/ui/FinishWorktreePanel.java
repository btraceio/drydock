package app.drydock.ui;

import app.drydock.domain.PrState;
import app.drydock.git.GitChangeSummary;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The state-aware Finish panel (worktree handoff, section B): change
 * summary + actions chosen by the session's {@link PrState}. Merge and
 * delete close the panel and run directly (plain {@code git merge} /
 * {@code git worktree remove} + {@code git branch -D}); only "create pull
 * request" hands off to Claude in the session's terminal, since
 * {@code gh pr create} needs the user's own gh auth:
 *
 * <ul>
 *   <li>{@code NONE}: Merge into base · Create pull request · Delete;</li>
 *   <li>{@code OPEN}: no merge/PR -- "Waiting on PR #n" + View PR + Delete;</li>
 *   <li>{@code MERGED}: Delete only.</li>
 * </ul>
 */
final class FinishWorktreePanel extends VBox {

    /** What the panel needs to know; assembled by MainWorkspace before showing. */
    record Context(String branch, String base, Path worktreeRoot, PrState prState,
                   Optional<Integer> prNumber, Optional<String> prUrl,
                   Optional<GitChangeSummary> changeSummary, boolean dirty,
                   boolean branchWillBeDeleted) {

        /**
         * The destructive action's label and caption. A branch drydock did not
         * create outlives its worktree (see {@code BranchOwnership}), so the
         * copy must not promise a {@code git branch -D} that will not run --
         * this is the one place the user is told what is about to be destroyed.
         */
        String deleteTitle() {
            return branchWillBeDeleted ? "Delete worktree & branch" : "Delete worktree";
        }

        String deleteCaption() {
            return branchWillBeDeleted
                    ? "Removes the worktree and deletes " + branch + " directly"
                    : "Removes the worktree — " + branch + " already existed, so it is kept";
        }
    }

    interface Actions {
        void mergeIntoBase();

        void createPullRequest();

        void deleteWorktree();

        void viewPullRequest(String url);
    }

    FinishWorktreePanel(Context context, Actions actions, Runnable onClose) {
        getStyleClass().add("modal");
        setMaxWidth(520);
        setMaxHeight(Region.USE_PREF_SIZE);
        setSpacing(12);

        Label title = new Label("Finish worktree");
        title.getStyleClass().add("modal-title");
        Button close = new Button("×");
        close.getStyleClass().add("icon-button");
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label branchLine = new Label("◫ " + context.branch() + "  →  ⎇ " + context.base());
        branchLine.getStyleClass().add("worktree-context-line");

        getChildren().addAll(header, branchLine, buildSummary(context));

        switch (context.prState()) {
            case NONE -> {
                getChildren().add(action("Merge into " + context.base(),
                        "Runs git merge --no-ff directly — stops on conflicts for you to resolve",
                        "finish-action-accent", () -> runAndClose(actions::mergeIntoBase, onClose)));
                getChildren().add(action("Create pull request",
                        "Hand off to Claude — push branch & open a PR", "finish-action",
                        () -> runAndClose(actions::createPullRequest, onClose)));
                getChildren().add(action(context.deleteTitle(), context.deleteCaption(),
                        "finish-action-destructive", () -> runAndClose(actions::deleteWorktree, onClose)));
            }
            case OPEN -> {
                Label waiting = new Label("Waiting on PR " + context.prNumber().map(n -> "#" + n).orElse(""));
                waiting.getStyleClass().add("finish-waiting-title");
                Label caption = new Label("Merge happens through the pull request — nothing to do here yet.");
                caption.getStyleClass().add("finish-action-caption");
                caption.setWrapText(true);
                VBox waitingBox = new VBox(2, waiting, caption);
                waitingBox.getStyleClass().add("finish-waiting");
                getChildren().add(waitingBox);
                context.prUrl().ifPresent(url -> getChildren().add(action(
                        "View pull request on GitHub", url, "finish-action",
                        () -> actions.viewPullRequest(url))));
                getChildren().add(action(context.deleteTitle(), context.deleteCaption(),
                        "finish-action-destructive", () -> runAndClose(actions::deleteWorktree, onClose)));
            }
            case MERGED -> getChildren().add(action(context.branchWillBeDeleted()
                            ? "Delete merged worktree & branch" : "Delete merged worktree",
                    "The branch is merged; removes the worktree directly",
                    "finish-action-destructive", () -> runAndClose(actions::deleteWorktree, onClose)));
        }
    }

    private static void runAndClose(Runnable action, Runnable onClose) {
        onClose.run();
        action.run();
    }

    private VBox buildSummary(Context context) {
        VBox summaryBox = new VBox(6);
        summaryBox.getStyleClass().add("finish-summary");

        String headline;
        if (context.changeSummary().isPresent()) {
            GitChangeSummary summary = context.changeSummary().get();
            headline = summary.files().size() + (summary.files().size() == 1 ? " file" : " files")
                    + " · +" + summary.totalInsertions() + " · −" + summary.totalDeletions()
                    + " · " + summary.commitsAhead()
                    + (summary.commitsAhead() == 1 ? " commit ahead" : " commits ahead")
                    + (context.dirty() ? " · uncommitted changes" : "");
        } else {
            headline = context.dirty() ? "Uncommitted changes present" : "No change summary available";
        }
        Label headlineLabel = new Label(headline);
        headlineLabel.getStyleClass().add("finish-summary-headline");
        summaryBox.getChildren().add(headlineLabel);

        context.changeSummary().ifPresent(summary -> {
            List<GitChangeSummary.ChangedFile> shown = summary.files().stream().limit(6).toList();
            for (GitChangeSummary.ChangedFile file : shown) {
                Label marker = new Label(file.kind());
                marker.getStyleClass().addAll("finish-file-marker", UiFormats.markerStyleClass(file.kind()));
                Label path = new Label(file.path());
                path.getStyleClass().add("finish-file-path");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                Label stat = new Label("+" + file.insertions() + " −" + file.deletions());
                stat.getStyleClass().add("finish-file-stat");
                HBox row = new HBox(8, marker, path, spacer, stat);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("finish-summary-row");
                summaryBox.getChildren().add(row);
            }
            int hidden = summary.files().size() - shown.size();
            if (hidden > 0) {
                Label more = new Label("… and " + hidden + " more");
                more.getStyleClass().add("finish-action-caption");
                summaryBox.getChildren().add(more);
            }
        });
        return summaryBox;
    }

    private static Region action(String titleText, String captionText, String styleClass, Runnable onAction) {
        Label title = new Label(titleText);
        title.getStyleClass().add("finish-action-title");
        Label caption = new Label(captionText);
        caption.getStyleClass().add("finish-action-caption");
        caption.setWrapText(true);
        VBox box = new VBox(2, title, caption);
        box.getStyleClass().addAll("finish-action-box", styleClass);
        box.setOnMouseClicked(e -> onAction.run());
        return box;
    }
}
