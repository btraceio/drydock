package app.drydock.intellij.tasks;

import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Which lazy listing sections the user opened in this panel, plus the guard
 * that keeps the panel's own tree restoration from counting as such an open.
 *
 * <p>The tree is rebuilt from scratch on every pass, and the panel then
 * re-expands the eager and requested sections with {@code Tree.expandPath} —
 * which fires the very {@code treeWillExpand} a user's click fires. Unguarded,
 * a pass through the Issues or PRs view (where every section auto-expands)
 * recorded every project as user-requested, so returning to Active/All kept
 * fetching and expanding every listing. {@link #runProgrammatic(Runnable)}
 * suppresses recording while the panel restores its own state, and is
 * exception-safe: a failing restoration still clears the guard.</p>
 */
final class ListingExpansionTracker implements TreeWillExpandListener {

    /** The panel's rebuild scheduler. */
    @FunctionalInterface
    interface RebuildRequester {
        void requestRebuild(boolean changedWorktrees);
    }

    private final RebuildRequester requester;
    private final Function<TreePath, Optional<Path>> projectRootOf;
    /** Main roots whose lazy containers the user opened in this panel. */
    private final Set<Path> worktrees = ConcurrentHashMap.newKeySet();
    private final Set<Path> issues = ConcurrentHashMap.newKeySet();
    private final Set<Path> pullRequests = ConcurrentHashMap.newKeySet();
    /** True only inside {@link #runProgrammatic}: restoration is not user intent. */
    private volatile boolean programmatic;

    ListingExpansionTracker(RebuildRequester requester, Function<TreePath, Optional<Path>> projectRootOf) {
        this.requester = requester;
        this.projectRootOf = projectRootOf;
    }

    /** The roots whose section of {@code kind} the user opened here. */
    Set<Path> requested(TaskNode.SectionKind kind) {
        return switch (kind) {
            case WORKTREES -> worktrees;
            case ISSUES -> issues;
            case PULL_REQUESTS -> pullRequests;
            default -> Set.of();
        };
    }

    /**
     * A real expansion event, whatever caused it: records the opened
     * section's root as user intent and asks for one rebuild — except while
     * the panel restores its own expansions, which is not intent. EDT (Swing
     * fires this synchronously from the expanding gesture).
     */
    @Override
    public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        Object component = event.getPath().getLastPathComponent();
        if (!(component instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof TaskNode.Section section)) {
            return;
        }
        Path root = projectRootOf.apply(event.getPath()).orElse(null);
        if (root == null) {
            return;
        }
        if (!programmatic && record(section.kind(), root)) {
            requester.requestRebuild(false);
        }
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
    }

    /** Runs the panel's own expansion/selection restoration, recording no user intent. */
    void runProgrammatic(Runnable restoration) {
        programmatic = true;
        try {
            restoration.run();
        } finally {
            programmatic = false;
        }
    }

    private boolean record(TaskNode.SectionKind kind, Path root) {
        return switch (kind) {
            case WORKTREES -> worktrees.add(root);
            case ISSUES -> issues.add(root);
            case PULL_REQUESTS -> pullRequests.add(root);
            default -> false;
        };
    }
}
