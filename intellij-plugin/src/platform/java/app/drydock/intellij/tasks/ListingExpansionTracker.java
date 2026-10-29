package app.drydock.intellij.tasks;

import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Which lazy listing sections and Jira groups the user opened in this
 * panel, plus the guard that keeps the panel's own tree restoration from
 * counting as such an open.
 *
 * <p>The tree is rebuilt from scratch on every pass, and the panel then
 * re-expands the eager and requested nodes with {@code Tree.expandPath} —
 * which fires the very {@code treeWillExpand} a user's click fires. Unguarded,
 * a pass through the Issues or PRs view (where every section auto-expands)
 * recorded every project as user-requested, so returning to Active/All kept
 * fetching and expanding every listing. {@link #runProgrammatic(Runnable)}
 * suppresses recording while the panel restores its own state, and is
 * exception-safe: a failing restoration still clears the guard.</p>
 *
 * <p>A Jira group's expansion is recorded the same way, but is pure UI
 * state: the group's listing was already fetched with the section that
 * shows it, so opening a group re-requests nothing — it only survives the
 * next rebuild. The record lasts only while a completed gather still
 * defines the group: deleting the group, unregistering its project or
 * disabling its Jira connection forgets it, and a failed pass forgets
 * nothing.</p>
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
    /**
     * Jira groups the user opened here, keyed by their project's root and the
     * group's stable id; pruned to the groups each completed gather still
     * defines ({@link #retainDefinedJiraGroups}).
     */
    private final Set<GroupKey> jiraGroups = ConcurrentHashMap.newKeySet();
    /** True only inside {@link #runProgrammatic}: restoration is not user intent. */
    private volatile boolean programmatic;

    /** One user-opened Jira group: the main root of its project and the group's stable id. */
    record GroupKey(Path root, String groupId) {
    }

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

    /** The Jira groups the user opened here, as (main root, group id). */
    Set<GroupKey> requestedJiraGroups() {
        return jiraGroups;
    }

    /**
     * Every Jira group a gather defined, as (main root, group id): the
     * authority for which recorded groups can still be shown. Built from
     * the definitions, never the displayed tree, which a search or a view
     * filters. Pure; package-private so the tests can assert it.
     */
    static Set<GroupKey> definedJiraGroups(Map<Path, List<JiraGroups.Listing>> jiraGroupsByMainRoot) {
        Set<GroupKey> defined = new HashSet<>();
        jiraGroupsByMainRoot.forEach((root, listings) -> listings.forEach(
                listing -> defined.add(new GroupKey(root, listing.group().id()))));
        return defined;
    }

    /**
     * Forgets every recorded Jira group the gather of a completed pass no
     * longer defines: a deleted group, an unregistered or missing project,
     * a disabled Jira connection. A group that is defined stays recorded
     * whatever the view, the search, or whether its listing was fetched.
     * Call only after a successful gather; a failed pass evicts nothing.
     * Background thread; the set is concurrent, so this needs no lock
     * against the EDT's adds.
     */
    void retainDefinedJiraGroups(Map<Path, List<JiraGroups.Listing>> jiraGroupsByMainRoot) {
        jiraGroups.retainAll(definedJiraGroups(jiraGroupsByMainRoot));
    }

    /**
     * A real expansion event, whatever caused it: records the opened
     * section's root as user intent and asks for one rebuild — except while
     * the panel restores its own expansions, which is not intent. A Jira
     * group's open is recorded but re-requests nothing: its listing already
     * travelled with its section. EDT (Swing fires this synchronously from
     * the expanding gesture).
     */
    @Override
    public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        Object component = event.getPath().getLastPathComponent();
        if (!(component instanceof DefaultMutableTreeNode node)) {
            return;
        }
        if (node.getUserObject() instanceof TaskNode.JiraGroupRow group) {
            projectRootOf.apply(event.getPath()).ifPresent(root -> {
                if (!programmatic) {
                    jiraGroups.add(new GroupKey(root, group.group().id()));
                }
            });
            return;
        }
        if (!(node.getUserObject() instanceof TaskNode.Section section)) {
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
