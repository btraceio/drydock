package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The workbench's Files navigator (spec §5): the review's file tree
 * ({@link ReviewTabBrowser}, with its marks and hunk counters) over the
 * {@link WorkbenchState#files()} it is handed, the review's row commands
 * (Mark Reviewed, Unmark, Refresh) on its toolbar and context menu, and a
 * "With findings" toggle that asks for the other
 * {@link WorkbenchState.Filter}. A selection goes to the review's own
 * selection pipeline. EDT.
 */
final class ReviewWorkbenchNavigator extends JPanel implements Disposable {

    static final String WITH_FINDINGS = "With Findings";

    private final ReviewTabBrowser browser;
    private final DumbAwareToggleAction withFindings;
    private volatile WorkbenchState.Filter filter = WorkbenchState.Filter.ALL;

    ReviewWorkbenchNavigator(Project project, Consumer<Object> selection,
                             Consumer<WorkbenchState.Filter> filterChange, List<AnAction> rowCommands) {
        super(new BorderLayout());
        Objects.requireNonNull(filterChange, "filterChange");
        browser = new ReviewTabBrowser(project, selection);
        DefaultActionGroup contextMenu = new DefaultActionGroup();
        for (AnAction command : rowCommands) {
            browser.addToolbarAction(command);
            contextMenu.add(command);
        }
        if (!rowCommands.isEmpty()) {
            browser.addToolbarSeparator();
        }
        browser.getViewer().installPopupHandler(contextMenu);
        withFindings = new WithFindingsToggle(filterChange);
        browser.addToolbarAction(withFindings);
        add(browser, BorderLayout.CENTER);
    }

    /**
     * Selects one finding's row (a gutter click's target); a target for a
     * tree not loaded yet is kept until it is (the browser's own rule).
     */
    void selectFinding(String filePath, String findingId) {
        browser.selectFinding(filePath, findingId);
    }

    /** Shows {@code state}'s files and filter. */
    void render(WorkbenchState state) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchNavigator renders on the EDT");
        }
        filter = state.filter();
        browser.accept(state.files());
    }

    /** The "With findings" toggle on the tree's toolbar. */
    DumbAwareToggleAction withFindingsAction() {
        return withFindings;
    }

    @Override
    public void dispose() {
        browser.dispose();
    }

    private final class WithFindingsToggle extends DumbAwareToggleAction {
        private final Consumer<WorkbenchState.Filter> filterChange;

        WithFindingsToggle(Consumer<WorkbenchState.Filter> filterChange) {
            super(WITH_FINDINGS, "Show only files with open findings", AllIcons.General.Filter);
            this.filterChange = filterChange;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return filter == WorkbenchState.Filter.WITH_FINDINGS;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            filterChange.accept(state ? WorkbenchState.Filter.WITH_FINDINGS : WorkbenchState.Filter.ALL);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
