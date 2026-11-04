package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.WorkbenchState;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.EventQueue;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The workbench's navigator (spec §5), two tabs over the state it is handed:
 * <ul>
 *   <li><b>Sections</b> ({@link ReviewWorkbenchSections}): the review's
 *   sections; picking one asks for it to be shown.</li>
 *   <li><b>Files</b>: the review's file tree ({@link ReviewTabBrowser}, with
 *   its marks and hunk counters) over {@link WorkbenchState#files()}, the
 *   review's row commands (Mark Reviewed, Unmark, Refresh) on its toolbar and
 *   context menu, and a "With findings" toggle that asks for the other
 *   {@link WorkbenchState.Filter}. A selection goes to the review's own
 *   selection pipeline.</li>
 * </ul>
 * The tab shown is the state's; the tab toggles ask for the other one. EDT.
 */
final class ReviewWorkbenchNavigator extends JPanel implements Disposable {

    static final String WITH_FINDINGS = "With Findings";
    static final String SECTIONS = "Sections";
    static final String FILES = "Files";
    private static final String TOOLBAR_PLACE = "DrydockReviewNavigator";

    private final ReviewTabBrowser browser;
    final ReviewWorkbenchSections sections;
    private final DumbAwareToggleAction withFindings;
    final DefaultActionGroup tabActions;
    private final ActionToolbar tabs;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private volatile WorkbenchState.Filter filter = WorkbenchState.Filter.ALL;
    private volatile WorkbenchState.Navigator tab = WorkbenchState.Navigator.FILES;

    /**
     * {@code tabChange} receives the tab the user picked, {@code sectionChoice}
     * the key of the section the user picked.
     */
    ReviewWorkbenchNavigator(Project project, Consumer<Object> selection,
                             Consumer<WorkbenchState.Filter> filterChange, List<AnAction> rowCommands,
                             Consumer<WorkbenchState.Navigator> tabChange, Consumer<String> sectionChoice) {
        super(new BorderLayout());
        Objects.requireNonNull(filterChange, "filterChange");
        Objects.requireNonNull(tabChange, "tabChange");
        browser = new ReviewTabBrowser(project, selection);
        sections = new ReviewWorkbenchSections(sectionChoice);
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
        body.add(sections, WorkbenchState.Navigator.SECTIONS.name());
        body.add(browser, WorkbenchState.Navigator.FILES.name());
        cards.show(body, tab.name());
        tabActions = new DefaultActionGroup(new TabToggle(WorkbenchState.Navigator.SECTIONS, SECTIONS, tabChange),
                new TabToggle(WorkbenchState.Navigator.FILES, FILES, tabChange));
        tabs = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, tabActions, true);
        tabs.setTargetComponent(this);
        add(tabs.getComponent(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    /**
     * Selects one finding's row (a gutter click's target); a target for a
     * tree not loaded yet is kept until it is (the browser's own rule).
     */
    void selectFinding(String filePath, String findingId) {
        browser.selectFinding(filePath, findingId);
    }

    /** Selects {@code filePath}'s row in the Files tree, which shows its diff. EDT. */
    void selectFile(String filePath) {
        browser.selectFile(filePath);
    }

    /** Shows {@code state}'s files and filter. */
    void render(WorkbenchState state) {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("ReviewWorkbenchNavigator renders on the EDT");
        }
        filter = state.filter();
        browser.accept(state.files());
        sections.render(state);
        if (tab != state.navigator()) {
            tab = state.navigator();
            cards.show(body, tab.name());
            // The toggles read the tab on the toolbar's own schedule; a tab the state chose shows now.
            tabs.updateActionsAsync();
        }
    }

    /** The tab shown. EDT. */
    WorkbenchState.Navigator tab() {
        return tab;
    }

    /** The "With findings" toggle on the tree's toolbar. */
    DumbAwareToggleAction withFindingsAction() {
        return withFindings;
    }

    @Override
    public void dispose() {
        browser.dispose();
    }

    /** One navigator tab; selected while the navigator shows it. */
    private final class TabToggle extends DumbAwareToggleAction {
        private final WorkbenchState.Navigator target;
        private final Consumer<WorkbenchState.Navigator> tabChange;

        TabToggle(WorkbenchState.Navigator target, String text, Consumer<WorkbenchState.Navigator> tabChange) {
            super(text);
            this.target = target;
            this.tabChange = tabChange;
            ReviewWorkbenchEditor.showTextInToolbar(this);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return tab == target;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean selected) {
            if (selected) {
                tabChange.accept(target);
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
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
