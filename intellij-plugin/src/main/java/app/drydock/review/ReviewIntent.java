package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.domain.RepositoryId;
import app.drydock.review.tour.ReviewTour;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * What the human deliberately asked to review: which registered repository and checkout, whether the
 * target is the local changes or one explicitly chosen pull request, where the request came from, and
 * the review kind wanted.
 *
 * <p>The value is built only from explicit selections. A {@code pr-N} branch name, a scope's PR
 * metadata, a session's PR association or the process working directory never produce a pull-request
 * target; a request without an explicit PR is {@link LocalChanges}. It grants no authority and carries
 * no diff, scope or UI object, so choosing it never changes which bytes are reviewed.
 */
public record ReviewIntent(RepositoryId repository, Checkout checkout, Optional<ManagedSessionId> session,
        Target target, Origin origin, ReviewTour.Kind kind) {

    public ReviewIntent {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(checkout, "checkout");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(kind, "kind");
    }

    /** Local-changes intent, the explicit default when no pull request was chosen. */
    public static ReviewIntent local(RepositoryId repository, Checkout checkout,
            Optional<ManagedSessionId> session, Origin origin, ReviewTour.Kind kind) {
        return new ReviewIntent(repository, checkout, session, LocalChanges.INSTANCE, origin, kind);
    }

    /** Intent for a pull request the human explicitly selected. */
    public static ReviewIntent pullRequest(RepositoryId repository, Checkout checkout,
            Optional<ManagedSessionId> session, PullRequestTarget pr, Origin origin, ReviewTour.Kind kind) {
        return new ReviewIntent(repository, checkout, session, new PullRequest(pr), origin, kind);
    }

    /** The same intent aimed at another target; every other field is kept. */
    public ReviewIntent withTarget(Target other) {
        return new ReviewIntent(repository, checkout, session, other, origin, kind);
    }

    /** The explicitly selected pull request, empty for local intent. */
    public Optional<PullRequestTarget> pullRequestTarget() {
        return target instanceof PullRequest pr ? Optional.of(pr.target()) : Optional.empty();
    }

    /** The checkout identity: a local directory, or an opaque placeholder for a remote one. */
    public sealed interface Checkout {
    }

    /** A checkout on this machine, identified by its path. */
    public record Local(Path path) implements Checkout {
        public Local {
            Objects.requireNonNull(path, "path");
        }
    }

    /** A remote checkout known only by its deterministic placeholder; never a host path to probe. */
    public record Remote(String placeholder) implements Checkout {
        public Remote {
            Objects.requireNonNull(placeholder, "placeholder");
            if (placeholder.isBlank()) {
                throw new IllegalArgumentException("A remote checkout placeholder must not be blank");
            }
        }
    }

    /** What is reviewed. */
    public sealed interface Target {
    }

    /** The checkout's own changes. */
    public enum LocalChanges implements Target {
        INSTANCE
    }

    /** One explicitly selected pull request; the target is validated by its own constructor. */
    public record PullRequest(PullRequestTarget target) implements Target {
        public PullRequest {
            Objects.requireNonNull(target, "target");
        }
    }

    /** The entry point that produced the request. */
    public enum Origin {
        PULL_REQUEST_ROW, WORKBENCH, LOCAL_TREE, TASK_ACTION
    }
}
