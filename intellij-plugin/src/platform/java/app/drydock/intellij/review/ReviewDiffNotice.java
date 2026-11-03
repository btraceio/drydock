package app.drydock.intellij.review;

import com.intellij.diff.util.DiffNotificationProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorNotificationPanel;

import java.util.Objects;
import java.util.Optional;

/**
 * The one-line notice in a review diff's header (spec §2.2) when the right
 * side does not show the project file at the reviewed text: a snapshot from
 * the start, or a file-backed side that was edited while the diff was open.
 * Its Refresh link re-runs the review's diff ({@code ReviewTabProvider}'s
 * manual Refresh, which saves open documents first).
 */
final class ReviewDiffNotice {

    static final String SNAPSHOT_TEXT = "The worktree file differs from the reviewed revision. This side is a"
            + " snapshot: references resolve against the current code, and its lines are not the file's.";

    static final String STALE_TEXT = "The file changed after the review's diff was computed."
            + " Finding cards are hidden until Refresh.";

    static final String REFRESH = "Refresh";

    private ReviewDiffNotice() {
    }

    /** The notice text (pure): a snapshot says so at once; a file-backed side only once it is stale. */
    static Optional<String> text(ReviewDiffContents.RightSide side, boolean stale) {
        Objects.requireNonNull(side, "side");
        if (side == ReviewDiffContents.RightSide.SNAPSHOT) {
            return Optional.of(SNAPSHOT_TEXT);
        }
        return stale ? Optional.of(STALE_TEXT) : Optional.empty();
    }

    /**
     * The request's notification provider. A file-backed request gets a
     * hidden panel that appears when its {@link ReviewDiffContents.Staleness}
     * fires (on the viewer's rediff thread, so the reveal hops to the EDT).
     */
    static DiffNotificationProvider provider(Project project, ReviewDiffContents.RightSide side,
                                             ReviewDiffContents.Staleness staleness) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(staleness, "staleness");
        return viewer -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(EditorNotificationPanel.Status.Warning);
            panel.createActionLabel(REFRESH, () -> ReviewTabProvider.reloadFromChangesLink(project));
            Runnable render = () -> {
                Optional<String> text = text(side, staleness.isStale());
                panel.setText(text.orElse(""));
                panel.setVisible(text.isPresent());
            };
            render.run();
            staleness.onStale(() -> ApplicationManager.getApplication().invokeLater(render));
            return panel;
        };
    }
}
