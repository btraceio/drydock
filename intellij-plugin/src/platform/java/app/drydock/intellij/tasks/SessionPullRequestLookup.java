package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Checkout;
import app.drydock.projects.SharedProject;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The session tree's session → branch → pull request join ({@link
 * TaskTreeInputs#sessionPullRequests}), for ONE session and outside the
 * tree: the same badge sources (registered local projects whose pull
 * requests are switched on, keyed by canonical main checkout, each with its
 * configured GitHub repository), the same checkout probe and the same match
 * rules, so a session that shows a PR badge resolves to that PR here too.
 * Unlike the tree it waits for {@code gh} instead of serving a cached
 * answer, and caches nothing.
 *
 * <p>Blocking (one {@code git rev-parse}, one {@code gh pr list}): call it
 * on a background executor, never the EDT.</p>
 */
public final class SessionPullRequestLookup {

    private static final Logger LOG = System.getLogger(SessionPullRequestLookup.class.getName());

    /** The tree's own git bound; gh carries its own 15 s timeout inside the listing. */
    private static final long GIT_TIMEOUT_SECONDS = 15;
    private static final long GH_TIMEOUT_SECONDS = 30;

    private SessionPullRequestLookup() {
    }

    /**
     * {@code session}'s pull request among its project's, in any state; empty
     * when the session has an explicit target PR (the join skips those), its
     * directory is gone, git names no branch or no registered project, or
     * {@code gh} lists nothing for it -- a failed listing reads as empty,
     * exactly as on the badge.
     */
    public static Optional<BranchPullRequest> pullRequestOf(ManagedAgentSession session, List<SharedProject> projects,
                                                           WorktreeService worktrees, GhCliService github) {
        Map<ManagedSessionId, BranchPullRequest> matched = TaskTreeInputs.sessionPullRequests(List.of(session),
                badgeSources(projects), directory -> checkoutOf(worktrees, directory),
                source -> listing(github, source));
        return Optional.ofNullable(matched.get(session.id()));
    }

    /**
     * The GitHub repository configured for the registered local project whose
     * main checkout is {@code root}; empty when none is configured (gh then
     * resolves the repository from the checkout's remote) or no project
     * matches. Memory-only.
     */
    public static Optional<String> repositoryOf(Path root, List<SharedProject> projects) {
        Path canonical = RepositoryCatalog.canonicalize(root);
        for (SharedProject project : projects) {
            if (!project.isRemote() && RepositoryCatalog.canonicalize(project.root()).equals(canonical)) {
                return GitHubProjectSettings.load(project.id()).repository();
            }
        }
        return Optional.empty();
    }

    /** The tree's badge sources: local, present projects whose pull requests are enabled. */
    private static Map<Path, TaskTreeInputs.BadgeSource> badgeSources(List<SharedProject> projects) {
        Map<Path, TaskTreeInputs.BadgeSource> sources = new HashMap<>();
        for (SharedProject project : projects) {
            if (project.isRemote()) {
                continue;
            }
            Path root = RepositoryCatalog.canonicalize(project.root());
            if (!Files.isDirectory(root)) {
                continue;
            }
            GitHubProjectSettings settings = GitHubProjectSettings.load(project.id());
            if (settings.pullRequestsEnabled()) {
                sources.put(root, new TaskTreeInputs.BadgeSource(root, settings.repository()));
            }
        }
        return sources;
    }

    private static Optional<Checkout> checkoutOf(WorktreeService worktrees, Path directory) {
        try {
            Checkout found = worktrees.checkoutOf(directory).get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Optional.of(new Checkout(RepositoryCatalog.canonicalize(found.mainCheckout()), found.branch()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.log(Level.DEBUG, () -> "No checkout for " + directory + ": " + e);
            return Optional.empty();
        }
    }

    private static Map<String, BranchPullRequest> listing(GhCliService github, TaskTreeInputs.BadgeSource source) {
        try {
            // Light rows: this lookup matches by number, state and head
            // branch and renders nothing, so the babysit-tool calls and
            // actions that each run it must not each pay the rollup query.
            return github.branchPullRequestRows(source.root(), source.repository())
                    .get(GH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, () -> "Could not list the pull requests of " + source.root() + ": " + e);
            return Map.of();
        }
    }
}
