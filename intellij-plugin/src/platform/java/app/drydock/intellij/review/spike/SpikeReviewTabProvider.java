package app.drydock.intellij.review.spike;

import app.drydock.git.UnifiedDiff;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.FileStatusFactory;
import com.intellij.openapi.vcs.changes.ui.AsyncChangesBrowserBase;
import com.intellij.openapi.vcs.changes.ui.AsyncChangesTreeModel;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer;
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingPolicyFactory;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider;
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesTreeModel;
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.MouseEvent;

/**
 * SPIKE (P2 spike 1): a Drydock Review tab on the {@code changesViewContent}
 * extension point, built on {@link AsyncChangesBrowserBase} with
 * {@code showCheckboxes=false} and {@link TreeModelBuilder}. The rows carry
 * three fake review marks (reviewed / findings / unreviewed) so the live run
 * answers what the bytecode probe behind {@code find-review-tab-public-api}
 * could not:
 *
 * <ul>
 *   <li>which tool window hosts the tab, and with
 *       {@code isInCommitToolWindow} set, whether it behaves like Local
 *       Changes there;</li>
 *   <li>do file rows with a custom status color and text marks render;</li>
 *   <li>does double-click open the spike 2 diff chain (diff integration).</li>
 * </ul>
 */
final class SpikeReviewTabProvider implements ChangesViewContentProvider {

    private static final Logger LOG = Logger.getInstance(SpikeReviewTabProvider.class);

    static final String TAB_NAME = "Drydock Review (Spike)";

    private final Project project;

    /** Project-area EP: the platform injects the owning project here. */
    SpikeReviewTabProvider(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull JComponent initContent() {
        Browser browser = new Browser(project);
        JBPanel<?> panel = new JBPanel<>(new BorderLayout());
        JBLabel header = new JBLabel("Spike: branch scope vs " + SpikeReviewSupport.BASE_BRANCH
                + " (marks are fake)");
        header.setBorder(new EmptyBorder(4, 8, 4, 8));
        panel.add(header, BorderLayout.NORTH);
        panel.add(browser, BorderLayout.CENTER);

        // The host tool window is a property of the tab name, resolved -- not
        // hard-coded: the Commit window is only one of the possible hosts
        // (find-review-tab-host-varies).
        String toolWindowId = ChangesViewContentManager.getToolWindowIdFor(project, TAB_NAME);
        LOG.info("SpikeReview: tab '" + TAB_NAME + "' hosted in tool window '" + toolWindowId + "'");

        return panel;
    }

    @Override
    public void disposeContent() {
        // The panel owns no external resources; the platform disposes the
        // content it hosts.
    }

    /** A file row with one of the three fake review marks. */
    private record Row(String path, Mark mark) {
        enum Mark { REVIEWED, FINDINGS, UNREVIEWED }
    }

    private static final class Browser extends AsyncChangesBrowserBase {

        private final Project project;

        private Browser(Project project) {
            this.project = project;
            // (project, showCheckboxes, highlightProblems): checkboxes off is
            // the shape the blueprint prescribes for the review tab.
            super(project, false, false);
            init();
            getViewer().getEmptyText().setText("No branch diff could be computed");
            // The async tree builds its model only on an explicit rebuild;
            // SimpleAsyncChangesBrowser triggers this from its own data
            // setters, a raw subclass must do it itself.
            getViewer().rebuildTree();
            new DoubleClickListener() {
                @Override
                protected boolean onDoubleClick(@NotNull MouseEvent event) {
                    TreePath path = getViewer().getSelectionPath();
                    if (path == null) {
                        return false;
                    }
                    Object node = path.getLastPathComponent();
                    if (node instanceof ChangesBrowserNode<?> browserNode
                            && browserNode.getUserObject() instanceof Row) {
                        SpikeReviewDiffAction.openForProject(project);
                        return true;
                    }
                    return false;
                }
            }.installOn(getViewer());
        }

        @Override
        protected @NotNull AsyncChangesTreeModel getChangesTreeModel() {
            return SimpleAsyncChangesTreeModel.create(this::buildTree);
        }

        private DefaultTreeModel buildTree(ChangesGroupingPolicyFactory grouping) {
            UnifiedDiff branchDiff = SpikeReviewSupport.branchDiff(project);
            if (branchDiff == null || branchDiff.files().isEmpty()) {
                LOG.info("SpikeReview: tab tree: no branch diff");
                return TreeModelBuilder.buildEmpty();
            }
            ChangesBrowserNode<?> root = ChangesBrowserNode.createRoot();
            int i = 0;
            for (UnifiedDiff.FileDiff file : branchDiff.files()) {
                if (SpikeReviewSupport.isIdeNoise(file.path())) {
                    continue;
                }
                // Fake marks: every third file reviewed, every fifth with
                // findings -- enough rows of each kind to eyeball rendering.
                Row.Mark mark = i % 3 == 0 ? Row.Mark.REVIEWED
                        : i % 5 == 0 ? Row.Mark.FINDINGS
                        : Row.Mark.UNREVIEWED;
                root.add(new MarkedFileNode(new Row(file.path(), mark)));
                i++;
            }
            LOG.info("SpikeReview: tab tree built with " + i + " file row(s)");
            return new DefaultTreeModel(root);
        }
    }

    /** A row node whose text carries the fake review mark. */
    private static final class MarkedFileNode extends ChangesBrowserNode<Row> {
        private static final Color REVIEWED_COLOR = new JBColor(new Color(0x36, 0x86, 0x28),
                new Color(0x7B, 0xB0, 0x6B));
        private static final Color FINDINGS_COLOR = new JBColor(new Color(0xB5, 0x4A, 0x0E),
                new Color(0xD9, 0x8A, 0x5A));

        private static final FileStatus REVIEWED_STATUS = FileStatusFactory.getInstance()
                .createFileStatus("DRYDOCK_SPIKE_REVIEWED", "Reviewed (spike)", REVIEWED_COLOR);
        private static final FileStatus FINDINGS_STATUS = FileStatusFactory.getInstance()
                .createFileStatus("DRYDOCK_SPIKE_FINDINGS", "Findings (spike)", FINDINGS_COLOR);
        private static final FileStatus PLAIN_STATUS = FileStatusFactory.getInstance()
                .createFileStatus("DRYDOCK_SPIKE_UNREVIEWED", "Unreviewed (spike)", null);

        private MarkedFileNode(Row row) {
            super(row);
        }

        @Override
        public String getTextPresentation() {
            Row row = getUserObject();
            return switch (row.mark()) {
                case REVIEWED -> row.path() + "  ✓ reviewed";
                case FINDINGS -> row.path() + "  ⚑ " + (1 + Math.abs(row.path().hashCode()) % 4) + " findings";
                case UNREVIEWED -> row.path();
            };
        }

        @Override
        public void render(@NotNull JTree tree, @NotNull ChangesBrowserNodeRenderer renderer,
                           boolean selected, boolean expanded, boolean hasFocus) {
            Row row = getUserObject();
            FileStatus status = switch (row.mark()) {
                case REVIEWED -> REVIEWED_STATUS;
                case FINDINGS -> FINDINGS_STATUS;
                case UNREVIEWED -> PLAIN_STATUS;
            };
            renderer.append(getTextPresentation(),
                    new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.getColor()));
            renderer.setIcon(AllIcons.Vcs.Changelist);
            renderer.setToolTipText("Spike mark: " + status.getText());
        }
    }
}
