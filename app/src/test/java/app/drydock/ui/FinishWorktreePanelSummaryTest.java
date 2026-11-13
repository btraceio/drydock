package app.drydock.ui;

import app.drydock.domain.PrState;
import app.drydock.git.GitChangeSummary;
import app.drydock.testing.FxTest;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Finish panel's summary per PR state. In the MERGED state the ordinary
 * "N commits ahead" summary must be gone: the count is measured against the
 * LOCAL base branch, so after a squash or rebase merge it still reports the
 * PR's commits as ahead of the base -- read next to the delete action, that
 * says "these N commits are unmerged and you are about to lose them", which
 * is exactly wrong. The merged summary names the PR instead.
 */
class FinishWorktreePanelSummaryTest extends FxTest {

    private static final Path WORKTREE = Path.of("/tmp/unused-worktree");

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        TestStages.show(stage, new Scene(new StackPane(), 640, 480));
    }

    private FinishWorktreePanel newPanel(PrState prState, Optional<Integer> prNumber,
                                         Optional<GitChangeSummary> summary, boolean dirty,
                                         boolean worktreeClean) {
        FinishWorktreePanel.Context context = new FinishWorktreePanel.Context(
                "feat/x", "main", WORKTREE, prState, prNumber, Optional.empty(),
                summary, dirty, true, worktreeClean);
        FinishWorktreePanel[] holder = new FinishWorktreePanel[1];
        interact(() -> holder[0] = new FinishWorktreePanel(context, new FinishWorktreePanel.Actions() {
            @Override
            public void mergeIntoBase() {
            }

            @Override
            public void createPullRequest() {
            }

            @Override
            public void deleteWorktree() {
            }

            @Override
            public void viewPullRequest(String url) {
            }
        }, () -> {
        }));
        stage.getScene().setRoot(new StackPane(holder[0]));
        return holder[0];
    }

    @Test
    void aMergedPrNamesThePullRequestInsteadOfTheAheadCount() {
        GitChangeSummary merged = new GitChangeSummary(3, List.of(
                new GitChangeSummary.ChangedFile("M", "src/Main.java", 10, 2)));
        FinishWorktreePanel panel = newPanel(PrState.MERGED, Optional.of(7), Optional.of(merged), false, true);

        String text = textOf(panel);
        assertTrue(text.contains("Merged via pull request #7"), text);
        assertFalse(text.contains("3 commits ahead"), "an ahead count next to a merged PR reads as lost work");
        assertFalse(text.contains("commits ahead"), text);
    }

    @Test
    void theMergedSummaryStillWarnsAboutUncommittedChanges() {
        FinishWorktreePanel panel = newPanel(PrState.MERGED, Optional.of(7), Optional.empty(), true, false);

        String text = textOf(panel);
        assertTrue(text.contains("uncommitted"), text);
    }

    /**
     * Dirty submodule CONTENT is not uncommitted work for the delete path:
     * {@code WorktreeService.isWorktreeClean} ignores it by design (the build
     * patches vendored submodules on every run), so the captions must not
     * promise a confirmation the delete will never show.
     */
    @Test
    void dirtySubmoduleContentAloneDoesNotPromiseAConfirmation() {
        FinishWorktreePanel panel = newPanel(PrState.MERGED, Optional.of(7), Optional.empty(), true, true);

        String text = textOf(panel);
        assertFalse(text.contains("asks before discarding"), text);
        assertTrue(text.contains("removes the worktree directly"), text);
        assertTrue(text.contains("nothing is left to lose"), text);
    }

    @Test
    void anUnmergedBranchKeepsTheChangeSummary() {
        GitChangeSummary ahead = new GitChangeSummary(3, List.of(
                new GitChangeSummary.ChangedFile("M", "src/Main.java", 10, 2)));
        FinishWorktreePanel panel = newPanel(PrState.NONE, Optional.empty(), Optional.of(ahead), false, true);

        String text = textOf(panel);
        assertTrue(text.contains("3 commits ahead"), text);
        assertTrue(text.contains("src/Main.java"), text);
    }

    private static String textOf(FinishWorktreePanel panel) {
        StringBuilder builder = new StringBuilder();
        collectText(panel, builder);
        return builder.toString();
    }

    private static void collectText(Node node, StringBuilder builder) {
        if (node instanceof javafx.scene.control.Labeled labeled) {
            builder.append(labeled.getText()).append('\n');
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, builder);
            }
        }
    }
}
