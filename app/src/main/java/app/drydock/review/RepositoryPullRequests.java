package app.drydock.review;

import app.drydock.git.GhCliService;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.WorktreeService;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The open, non-draft pull requests of one repository that have no local
 * worktree -- the sidebar's virtual rows (spec §3.2, §6).
 *
 * <p>A pull request that <em>is</em> checked out is deliberately not here.
 * It is a badge on its worktree's own row, and a PR appearing in both places
 * is how the queue and the sidebar drifted into being two lists of the same
 * thing.</p>
 */
public final class RepositoryPullRequests {

    private static final Logger LOG = System.getLogger(RepositoryPullRequests.class.getName());

    /** Where the listing comes from -- in the app, {@code GhCliService::openPullRequests}. */
    @FunctionalInterface
    public interface Source {
        CompletableFuture<GhCliService.PullRequestListing> forRepository(Path repositoryRoot);
    }

    /** What the sidebar renders for a repository's pull-request group. */
    public sealed interface Outcome {
        /** gh answered; these earned a row (possibly none). */
        record Rows(List<GhCliService.OpenPullRequest> pullRequests) implements Outcome {
            public Rows {
                pullRequests = List.copyOf(pullRequests);
            }
        }

        /** No gh: no group at all. Not an error the reader has to look at. */
        record Absent() implements Outcome { }

        /** gh is present and did not answer: the group says so, and offers a retry. */
        record Unavailable(String message) implements Outcome {
            public Unavailable {
                Objects.requireNonNull(message, "message");
            }
        }
    }

    private final Source source;

    public RepositoryPullRequests(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Scans {@code repositoryRoot}, excluding anything already checked out in {@code worktrees}. */
    public CompletableFuture<Outcome> scan(Path repositoryRoot,
                                           List<WorktreeService.Worktree> worktrees) {
        return source.forRepository(repositoryRoot)
                .handle((listing, failure) -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Could not list pull requests in " + repositoryRoot, failure);
                        return new Outcome.Unavailable("Could not reach gh");
                    }
                    return switch (listing) {
                        case GhCliService.PullRequestListing.Unsupported ignored -> new Outcome.Absent();
                        case GhCliService.PullRequestListing.Failed failed ->
                                new Outcome.Unavailable(failed.message());
                        case GhCliService.PullRequestListing.Listed listed ->
                                new Outcome.Rows(selectable(listed.pullRequests(), worktrees));
                    };
                });
    }

    /**
     * The pure rule: open and non-draft, minus anything a local worktree
     * already holds -- by head branch, and by the {@code pr-<n>} name a
     * drydock checkout gives it.
     */
    public static List<GhCliService.OpenPullRequest> selectable(
            List<GhCliService.OpenPullRequest> open, List<WorktreeService.Worktree> worktrees) {
        Set<String> branches = new LinkedHashSet<>();
        Set<Integer> numbers = new LinkedHashSet<>();
        for (WorktreeService.Worktree worktree : worktrees) {
            worktree.branch().ifPresent(branch -> {
                branches.add(branch);
                PrCheckoutService.pullRequestNumberOf(branch).ifPresent(numbers::add);
            });
        }
        List<GhCliService.OpenPullRequest> selected = new ArrayList<>();
        for (GhCliService.OpenPullRequest pullRequest : open) {
            if (pullRequest.draft()
                    || branches.contains(pullRequest.headRefName())
                    || numbers.contains(pullRequest.number())) {
                continue;
            }
            selected.add(pullRequest);
        }
        return List.copyOf(selected);
    }
}
