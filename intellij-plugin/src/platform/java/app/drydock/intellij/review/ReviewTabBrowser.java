package app.drydock.intellij.review;

import app.drydock.review.Severity;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ui.AsyncChangesBrowserBase;
import com.intellij.openapi.vcs.changes.ui.AsyncChangesTreeModel;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer;
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingPolicyFactory;
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesTreeModel;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.Color;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The Review tab's tree (AC7's rendering data): an
 * {@code AsyncChangesBrowserBase(project, showCheckboxes=false,
 * highlightProblems=false)} whose rows and marks are exactly what
 * {@link ReviewTabModel}'s {@link ReviewTabModel.Snapshot} computes — the
 * browser adds presentation, never data.
 *
 * <p>Two spike gotchas shape the constructor
 * ({@code kb/find-p2-spikes-live-results}): the base constructor does not
 * call {@code init()}, so this one calls it right after {@code super}; and
 * the async tree never builds its model on its own, so after swapping the
 * data this class calls {@code getViewer().rebuildTree()} itself — without
 * that call the tab stays empty with no error anywhere.</p>
 *
 * <p>Grouping is this tab's own, never a {@code changesGroupingPolicy}
 * contribution ({@code kb/dead-grouping-policy-ep-for-sections}: that EP is
 * global and would leak a Drydock policy into every changes tree's Group
 * By menu). The tree is the snapshot's own shape — file rows in the
 * snapshot's path order with finding children — and the platform's
 * grouping factory is ignored. The Changes-browser family itself ships
 * public in {@code intellij.platform.vcs.impl.shared}
 * ({@code kb/find-263-diff-api-internal}), so no module declaration is
 * involved here either.</p>
 *
 * <p>Threading: {@link #accept} may be called from any thread (the
 * provider, T13, hops the refresher's snapshots to the EDT; the store first
 * rule below holds either way) — it stores the snapshot before anything
 * else, then triggers the rebuild on the EDT. {@code buildTree} reads only
 * the latest accepted snapshot: no git, no I/O, no store lock is ever held
 * from here.</p>
 *
 * <p><strong>Finding navigation</strong> (AC5, T8): {@link #selectFinding}
 * selects a finding by its stable file path and finding id — the value
 * keys the rows carry, so a snapshot rebuild cannot invalidate a target
 * the way row identity would. A target recorded before any snapshot has
 * arrived is retained and consumed after the next accept's rebuild; a
 * stale or malformed target changes nothing; and the record and the
 * consumption are atomic against each other, so a navigation click racing
 * a rebuild's completion can never spend one target twice. The pure
 * halves — target retention ({@link TreeState}) and path resolution
 * ({@link #findingPath}) — carry the whole decision, so the
 * exact/stale/malformed matrix is testable without the Swing host.</p>
 */
public final class ReviewTabBrowser extends AsyncChangesBrowserBase {

    /**
     * Who a tree selection is handed to: the selected node's user object —
     * a model {@link ReviewTabModel.FileRow} or
     * {@link ReviewTabModel.FindingRow} — that T13 wires to the diff
     * preview. Footer and helper nodes are not rows and never reach it.
     */
    private final Consumer<Object> selection;

    /** The pure tree state: the latest accepted snapshot, everything the tree renders. */
    private final TreeState tree;

    /**
     * Built by the Review tab's provider (T13). Checkboxes are off (the
     * tab's state is its marks, not the platform's inclusion model) and so
     * is problem highlighting (the tab's problems are finding rows).
     */
    ReviewTabBrowser(Project project, Consumer<Object> selection) {
        // Assigned in the prologue, before super(): the base constructor
        // wires the async tree through getChangesTreeModel(), and the live
        // spike kept the same order for the same reason — its buildTree
        // read its own project field — so nothing a super-constructor-time
        // build could touch is left unset (kb/find-p2-spikes-live-results).
        this.selection = Objects.requireNonNull(selection, "selection");
        this.tree = new TreeState();
        super(project, false, false);
        // The base constructor does not call init(); without this call the
        // browser has no UI at all (kb/find-p2-spikes-live-results).
        init();
        getViewer().getEmptyText().setText("No changes to review");
        getViewer().addTreeSelectionListener(event -> notifySelection());
        // The async tree builds its model only on an explicit rebuild; a
        // raw subclass must trigger it itself, or the tab stays empty with
        // no error anywhere (kb/find-p2-spikes-live-results). This first
        // rebuild renders the empty tree before any snapshot arrives.
        getViewer().rebuildTree();
    }

    @Override
    protected @NotNull AsyncChangesTreeModel getChangesTreeModel() {
        return SimpleAsyncChangesTreeModel.create(this::buildTree);
    }

    /**
     * The tree from the latest accepted snapshot only. The platform's
     * grouping parameter is deliberately ignored — see the class javadoc:
     * this tab owns its grouping, the snapshot's path order, and never
     * contributes a policy of its own.
     */
    private DefaultTreeModel buildTree(ChangesGroupingPolicyFactory grouping) {
        return tree.build();
    }

    /**
     * Hands the tree its next snapshot (the refresher's snapshot consumer,
     * which the provider wires here). The snapshot is stored before the
     * rebuild is triggered, so the rebuild reads the new data and never the
     * previous one; a caller already on the EDT rebuilds immediately, any
     * other caller gets the rebuild hopped there — but the store always
     * happens first. The rebuild's completion is also where a retained
     * navigation target is consumed: it runs once the displayed tree
     * reflects the just-stored snapshot.
     */
    public void accept(ReviewTabModel.Snapshot snapshot) {
        tree.accept(snapshot);
        rebuild(this::applyPendingSelection);
    }

    /**
     * Selects the finding with the given stable file path and finding id
     * (AC5's gutter navigation, T8): if a snapshot is already loaded the
     * selection is applied to the freshly rebuilt tree; if none has
     * arrived yet the target is retained and consumed after the next
     * snapshot's rebuild. A malformed target (null/blank path or id) is
     * never recorded; a stale one — no such file row or finding id in the
     * snapshot it is consumed against — leaves the selection unchanged.
     * Either way the target is spent on its first consumption. EDT is the
     * natural caller thread (the provider's snapshot hops); any other
     * thread gets the rebuild hopped there.
     */
    public void selectFinding(String filePath, String findingId) {
        if (!tree.requestSelection(filePath, findingId)) {
            return; // malformed: nothing recorded, the selection is unchanged
        }
        if (!tree.isLoaded()) {
            return; // no snapshot yet: retained, consumed after the next accept's rebuild
        }
        rebuild(this::applyPendingSelection);
    }

    /**
     * Rebuilds the async tree, running {@code after} once the displayed
     * tree reflects the latest accepted snapshot —
     * {@code requestRefresh(callback)}, the rebuild the base class's
     * {@code rebuildTree()} performs plus its completion callback.
     */
    private void rebuild(Runnable after) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDispatchThread()) {
            getViewer().requestRefresh(after);
        } else {
            application.invokeLater(() -> getViewer().requestRefresh(after));
        }
    }

    /**
     * Applies the pending navigation target, if any, to the freshly rebuilt
     * tree — EDT, after the refresh. The take is the consumption: found or
     * stale, the target is spent exactly once, and a stale one (no path)
     * simply leaves the selection as the rebuild left it.
     */
    private void applyPendingSelection() {
        Optional<ReviewTabOpener.FindingTarget> target = tree.takePendingSelection();
        if (target.isEmpty()) {
            return;
        }
        TreePath path = findingPath(getViewer().getModel().getRoot(),
                target.get().filePath(), target.get().findingId());
        if (path == null) {
            return; // stale against this snapshot: the selection is unchanged
        }
        getViewer().expandPath(path.getParentPath());
        getViewer().setSelectionPath(path);
        getViewer().scrollPathToVisible(path);
    }

    /**
     * Stops the async tree's coroutine scope; the provider calls this from
     * its {@code disposeContent} so a closed tab leaves no tree updating.
     */
    public void dispose() {
        shutdown();
    }

    /**
     * Hands the tree's current selection to the consumer again, unchanged.
     * The provider calls this when the Files tab is shown after a tour anchor
     * took over the diff, so the diff and the Mark commands follow the row
     * the tree shows as selected again. No selection: nothing is handed over.
     */
    void reannounceSelection() {
        notifySelection();
    }

    /** Hands the current selection's row to the consumer; anything else a node can be is not a row. */
    private void notifySelection() {
        TreePath path = getViewer().getSelectionPath();
        if (path == null) {
            return; // the rebuild cleared the selection; there is nothing selected
        }
        if (path.getLastPathComponent() instanceof ChangesBrowserNode<?> node) {
            Object row = selectableRow(node);
            if (row != null) {
                selection.accept(row);
            }
        }
    }

    /**
     * The row a node's selection is handed to the consumer as, or
     * {@code null} for the nodes that are not rows (the footer, the root).
     */
    static Object selectableRow(ChangesBrowserNode<?> node) {
        Object user = node.getUserObject();
        return user instanceof ReviewTabModel.FileRow || user instanceof ReviewTabModel.FindingRow
                ? user
                : null;
    }

    /**
     * The tree path to the finding with the given stable file path and
     * finding id, or {@code null} when this tree holds no such row — the
     * pure half of the navigation selection, so exact, stale and
     * malformed targets are decidable without the Swing host. A file row
     * whose findings do not contain the id is as stale as a missing file:
     * no partial selection, no fallback to the file row.
     */
    static TreePath findingPath(Object root, String filePath, String findingId) {
        if (!(root instanceof TreeNode node)
                || ReviewTabOpener.FindingTarget.of(filePath, findingId).isEmpty()) {
            return null; // malformed input, or no assembled tree: never a selection
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (!(child instanceof FileNode file) || !file.getUserObject().path().equals(filePath)) {
                continue;
            }
            for (int j = 0; j < file.getChildCount(); j++) {
                if (file.getChildAt(j) instanceof FindingNode finding
                        && finding.getUserObject().id().equals(findingId)) {
                    return new TreePath(new Object[] {node, file, finding});
                }
            }
            return null; // the file row is there, its findings are not: stale id
        }
        return null; // no such file row: stale path
    }

    // ---- pure assembly — everything below needs no Swing host, no EDT ----

    /**
     * The browser's tree state minus the Swing host: the latest accepted
     * snapshot, the node assembly from it, and the retained navigation
     * target. This is the seam the accept-then-rebuild ordering is tested
     * through — store first, then every rebuild reads exactly the latest —
     * and the seam the retention rule is tested through: a target recorded
     * before any snapshot waits, and the next accept's consumption spends it.
     *
     * <p>Threading: the pending target is an {@link AtomicReference}, so
     * the record ({@code set} — latest record wins) and the consumption
     * ({@code getAndSet(null)} — exactly once) are atomic against each
     * other: a navigation click recording on one thread while a rebuild's
     * completion takes on another can neither tear the target nor spend
     * it twice, whatever the interleaving.</p>
     */
    static final class TreeState {

        private volatile ReviewTabModel.Snapshot latest;
        private final AtomicReference<ReviewTabOpener.FindingTarget> pendingSelection = new AtomicReference<>();

        void accept(ReviewTabModel.Snapshot snapshot) {
            latest = Objects.requireNonNull(snapshot, "snapshot");
        }

        /** Whether a snapshot has been accepted — the loaded/not-loaded line the retention rule turns on. */
        boolean isLoaded() {
            return latest != null;
        }

        /**
         * Records a navigation selection target: {@code false} — and
         * nothing recorded — for malformed input (null/blank path or id).
         * A later record overwrites an earlier one — atomically, so a
         * concurrent take can never observe a half-recorded state: the
         * latest navigation click is the target that counts.
         */
        boolean requestSelection(String filePath, String findingId) {
            Optional<ReviewTabOpener.FindingTarget> target = ReviewTabOpener.FindingTarget.of(filePath, findingId);
            if (target.isEmpty()) {
                return false;
            }
            pendingSelection.set(target.get());
            return true;
        }

        /**
         * Takes the pending target, clearing it — consumption is exactly
         * once: found or stale, the first take spends it and every later
         * take finds nothing, and the get-and-clear is one atomic step,
         * so two concurrent takers can never both walk away with it.
         */
        Optional<ReviewTabOpener.FindingTarget> takePendingSelection() {
            return Optional.ofNullable(pendingSelection.getAndSet(null));
        }

        /** The tree the latest accepted snapshot dictates; empty before the first accept. */
        DefaultTreeModel build() {
            return treeOf(latest);
        }
    }

    /**
     * Assembles the node tree: one file node per displayed row in the
     * snapshot's path order, each open finding a child node, the resolved
     * findings footer a dim last child.
     */
    static DefaultTreeModel treeOf(ReviewTabModel.Snapshot snapshot) {
        ChangesBrowserNode<?> root = ChangesBrowserNode.createRoot();
        if (snapshot != null) {
            for (ReviewTabModel.FileRow file : snapshot.files()) {
                FileNode node = new FileNode(file);
                for (ReviewTabModel.FindingRow finding : file.findings()) {
                    node.add(new FindingNode(finding));
                }
                file.resolvedFindingsFooter().ifPresent(footer -> node.add(new FooterNode(footer)));
                root.add(node);
            }
        }
        return new DefaultTreeModel(root);
    }

    /**
     * The display data the tree renders, for tests without an EDT: one
     * entry per file row in snapshot order, carrying the row's rendered
     * text and tooltip, its finding children's, and the footer as it
     * appears under the file.
     */
    static List<FileRowDisplay> nodeRows(ReviewTabModel.Snapshot snapshot) {
        return snapshot.files().stream()
                .map(file -> new FileRowDisplay(
                        file.path(),
                        fileText(file),
                        fileTooltip(file),
                        file.findings().stream()
                                .map(finding -> new FindingRowDisplay(
                                        finding.id(), findingText(finding), findingTooltip(finding)))
                                .toList(),
                        file.resolvedFindingsFooter()))
                .toList();
    }

    /** One file row's display data: path, rendered text, tooltip, finding children, footer. */
    record FileRowDisplay(String path, String text, Optional<String> tooltip,
                          List<FindingRowDisplay> findings, Optional<String> footer) {
        public FileRowDisplay {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(tooltip, "tooltip");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
            Objects.requireNonNull(footer, "footer");
        }
    }

    /** One finding child row's display data. */
    record FindingRowDisplay(String id, String text, Optional<String> tooltip) {
        public FindingRowDisplay {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(tooltip, "tooltip");
        }
    }

    /** The file row's text: the path, then the mark glyph (or the no-text phrase). */
    static String fileText(ReviewTabModel.FileRow row) {
        return row.path() + "  " + row.mark().text();
    }

    /** The file row's tooltip: the model's hint — a {@code CHANGES} decision, the stale hint, or none. */
    static Optional<String> fileTooltip(ReviewTabModel.FileRow row) {
        return row.mark().hint();
    }

    /** The finding child's text: severity, title, then {@code :<line>} parsed from the start key. */
    static String findingText(ReviewTabModel.FindingRow row) {
        return row.severity().wireName() + ": " + row.title()
                + row.startLine().map(line -> ":" + line).orElse("");
    }

    /** The finding child's tooltip: the drift hint when its start key is not in the current diff. */
    static Optional<String> findingTooltip(ReviewTabModel.FindingRow row) {
        return row.driftHint();
    }

    /** A finding's icon, by severity. */
    static Icon severityIcon(Severity severity) {
        return switch (severity) {
            case BLOCKING, DEVIATION -> AllIcons.General.Warning;
            case QUESTION -> AllIcons.General.Information;
            case NIT -> AllIcons.General.Note;
        };
    }

    /** A file row: the path plus its mark, the mark colored by its kind. */
    static final class FileNode extends ChangesBrowserNode<ReviewTabModel.FileRow> {

        FileNode(ReviewTabModel.FileRow row) {
            super(row);
        }

        @Override
        public String getTextPresentation() {
            return fileText(getUserObject());
        }

        @Override
        public void render(@NotNull JTree tree, @NotNull ChangesBrowserNodeRenderer renderer,
                           boolean selected, boolean expanded, boolean hasFocus) {
            ReviewTabModel.FileRow row = getUserObject();
            renderer.append(row.path(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            renderer.append("  " + row.mark().text(), markAttributes(row.mark().kind()));
            renderer.setIcon(AllIcons.FileTypes.Text);
            renderer.setToolTipText(fileTooltip(row).orElse(null));
        }
    }

    /** A finding child row: severity, title, {@code :<line>}; the drift hint rides its tooltip. */
    static final class FindingNode extends ChangesBrowserNode<ReviewTabModel.FindingRow> {

        FindingNode(ReviewTabModel.FindingRow finding) {
            super(finding);
        }

        @Override
        public String getTextPresentation() {
            return findingText(getUserObject());
        }

        @Override
        public void render(@NotNull JTree tree, @NotNull ChangesBrowserNodeRenderer renderer,
                           boolean selected, boolean expanded, boolean hasFocus) {
            ReviewTabModel.FindingRow finding = getUserObject();
            renderer.append(findingText(finding), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            renderer.setIcon(severityIcon(finding.severity()));
            renderer.setToolTipText(findingTooltip(finding).orElse(null));
        }
    }

    /** The resolved-findings footer row: dim, and not a selection target. */
    static final class FooterNode extends ChangesBrowserNode<String> {

        FooterNode(String footer) {
            super(Objects.requireNonNull(footer, "footer"));
        }

        @Override
        public String getTextPresentation() {
            return getUserObject();
        }

        @Override
        public void render(@NotNull JTree tree, @NotNull ChangesBrowserNodeRenderer renderer,
                           boolean selected, boolean expanded, boolean hasFocus) {
            renderer.append(getUserObject(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            renderer.setIcon(null);
        }
    }

    private static final SimpleTextAttributes OPEN_FINDINGS_ATTRIBUTES = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD,
            new JBColor(new Color(0xB5, 0x4A, 0x0E), new Color(0xD9, 0x8A, 0x5A)));
    private static final SimpleTextAttributes REVIEWED_ATTRIBUTES = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            new JBColor(new Color(0x36, 0x86, 0x28), new Color(0x7B, 0xB0, 0x6B)));
    private static final SimpleTextAttributes UNREVIEWED_ATTRIBUTES = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY);
    private static final SimpleTextAttributes NO_TEXT_ATTRIBUTES = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY);

    private static SimpleTextAttributes markAttributes(ReviewTabModel.Mark.Kind kind) {
        return switch (kind) {
            case OPEN_FINDINGS -> OPEN_FINDINGS_ATTRIBUTES;
            case REVIEWED -> REVIEWED_ATTRIBUTES;
            case UNREVIEWED -> UNREVIEWED_ATTRIBUTES;
            case NO_TEXT_CHANGES -> NO_TEXT_ATTRIBUTES;
        };
    }
}
