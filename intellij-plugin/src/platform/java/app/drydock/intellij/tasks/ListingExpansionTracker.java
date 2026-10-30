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
 * Which of the tree's lazy parts the user opened in this panel, plus the
 * guard that keeps the panel's own tree restoration from counting as such
 * an open. An open is a fetch trigger for the parts whose data is fetched
 * lazily (the GitHub node, a Jira group, the worktree and pull-request
 * sections) and pure UI state for the ones whose children always travel
 * (the Issues and Jira container nodes).
 *
 * <p>The tree is rebuilt from scratch on every pass, and the panel then
 * re-expands the eager and requested nodes with {@code Tree.expandPath} —
 * which fires the very {@code treeWillExpand} a user's click fires.
 * Unguarded, a pass through a view that auto-expands recorded every
 * project as user-requested, so returning to Active/All kept fetching and
 * expanding every listing. {@link #runProgrammatic(Runnable)} suppresses
 * recording while the panel restores its own state, and is exception-safe:
 * a failing restoration still clears the guard.</p>
 *
 * <p>Opening a GitHub node or a Jira group records fetch intent and asks
 * for one rebuild — the next gather queries only what was opened. Opening
 * the Issues section or the Jira node records state only: their children
 * (the other node, the group definitions) travel without any fetch, so
 * there is nothing to request.</p>
 *
 * <p>Closing a lazy node forgets its record, so the next rebuild's
 * restoration keeps it closed. Without this, every rebuild — activity
 * events arrive roughly every second — re-opened every node the user had
 * ever opened, making one node's load look as if it had opened all the
 * others. Closing a container also forgets its lazy descendants' records:
 * a recorded child would re-open the closed container through the restore
 * walk's ancestor expansion. The fetched listing is not dropped here: with
 * the record gone the next gather stops fetching it, and the listing
 * cache's own TTL makes a quick re-open instant.</p>
 *
 * <p>A Jira group's record lasts only while a completed gather still
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
    /** Main roots whose lazy sections the user opened in this panel. */
    private final Set<Path> worktrees = ConcurrentHashMap.newKeySet();
    private final Set<Path> pullRequests = ConcurrentHashMap.newKeySet();
    /** Main roots whose Issues section the user opened: restore state only, never a fetch. */
    private final Set<Path> issueSections = ConcurrentHashMap.newKeySet();
    /** Main roots whose GitHub node the user opened: the fetch trigger for that project's issue listing. */
    private final Set<Path> githubIssues = ConcurrentHashMap.newKeySet();
    /** Main roots whose Jira node the user opened: restore state only, the groups travel anyway. */
    private final Set<Path> jiraNodes = ConcurrentHashMap.newKeySet();
    /**
     * Jira groups the user opened here, keyed by their project's root and the
     * group's stable id: the fetch trigger for that group's query; pruned to
     * the groups each completed gather still defines ({@link
     * #retainDefinedJiraGroups}).
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

    /**
     * The roots whose lazily fetched section of {@code kind} the user opened
     * here. Only WORKTREES and PULL_REQUESTS are tracked by kind; the Issues
     * section's state is {@link #requestedIssueSections()} and its fetch
     * triggers are {@link #requestedGitHubIssues()} and {@link
     * #requestedJiraGroups()} (restore state: {@link #requestedJiraNodes()}).
     *
     * @throws IllegalArgumentException for any other kind
     */
    Set<Path> requested(TaskNode.SectionKind kind) {
        return switch (kind) {
            case WORKTREES -> worktrees;
            case PULL_REQUESTS -> pullRequests;
            default -> throw new IllegalArgumentException("No lazily fetched listing for section " + kind);
        };
    }

    /** The main roots whose GitHub node the user opened here (the GitHub issue fetch triggers). */
    Set<Path> requestedGitHubIssues() {
        return githubIssues;
    }

    /** The Jira groups the user opened here, as (main root, group id). */
    Set<GroupKey> requestedJiraGroups() {
        return jiraGroups;
    }

    /** The main roots whose Issues section the user opened here (restore state only). */
    Set<Path> requestedIssueSections() {
        return issueSections;
    }

    /** The main roots whose Jira node the user opened here (restore state only). */
    Set<Path> requestedJiraNodes() {
        return jiraNodes;
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
     * A real expansion event, whatever caused it: records the opened node
     * and — for the parts whose data is fetched lazily — asks for one
     * rebuild, except while the panel restores its own expansions, which
     * is not intent. EDT (Swing fires this synchronously from the
     * expanding gesture).
     */
    @Override
    public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        Object component = event.getPath().getLastPathComponent();
        if (!(component instanceof DefaultMutableTreeNode node)) {
            return;
        }
        Optional<Path> root = projectRootOf.apply(event.getPath());
        if (node.getUserObject() instanceof TaskNode.GitHubNode) {
            root.ifPresent(r -> {
                if (!programmatic && githubIssues.add(r)) {
                    requester.requestRebuild(false);
                }
            });
        } else if (node.getUserObject() instanceof TaskNode.JiraGroupRow group) {
            root.ifPresent(r -> {
                if (!programmatic && jiraGroups.add(new GroupKey(r, group.group().id()))) {
                    requester.requestRebuild(false);
                }
            });
        } else if (node.getUserObject() instanceof TaskNode.JiraNode) {
            // The groups travel with the gather whether or not the node is
            // open; opening it only unfolds them.
            if (!programmatic) {
                root.ifPresent(jiraNodes::add);
            }
        } else if (node.getUserObject() instanceof TaskNode.Section section
                && section.kind() == TaskNode.SectionKind.ISSUES) {
            // The section's children are the container nodes themselves —
            // opening it fetches nothing.
            if (!programmatic) {
                root.ifPresent(issueSections::add);
            }
        } else if (node.getUserObject() instanceof TaskNode.Section section
                && (section.kind() == TaskNode.SectionKind.WORKTREES
                    || section.kind() == TaskNode.SectionKind.PULL_REQUESTS)) {
            Path r = root.orElse(null);
            if (r != null && !programmatic && requested(section.kind()).add(r)) {
                requester.requestRebuild(false);
            }
        }
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
        Object component = event.getPath().getLastPathComponent();
        if (programmatic || !(component instanceof DefaultMutableTreeNode node)) {
            return;
        }
        Path root = projectRootOf.apply(event.getPath()).orElse(null);
        if (root == null) {
            return;
        }
        switch (node.getUserObject()) {
            case TaskNode.GitHubNode ignored -> githubIssues.remove(root);
            case TaskNode.JiraGroupRow group -> jiraGroups.remove(new GroupKey(root, group.group().id()));
            case TaskNode.JiraNode ignored -> closeJiraUnder(root);
            case TaskNode.Section section -> closeSection(section, root);
            default -> { }
        }
    }

    /** Forgets the Jira node's open record and every group record under it. */
    private void closeJiraUnder(Path root) {
        jiraNodes.remove(root);
        jiraGroups.removeIf(key -> key.root().equals(root));
    }

    /** Forgets a closed section's record — and, for Issues, its lazy children's. */
    private void closeSection(TaskNode.Section section, Path root) {
        switch (section.kind()) {
            case ISSUES -> {
                issueSections.remove(root);
                githubIssues.remove(root);
                closeJiraUnder(root);
            }
            case PULL_REQUESTS -> pullRequests.remove(root);
            case WORKTREES -> worktrees.remove(root);
            default -> { }
        }
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
}
