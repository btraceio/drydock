package app.drydock.babysit;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One babysitting engagement: {@link #babysitterId()} watches the pull request
 * {@link #prNumber()} of {@link #watchedId()}.
 *
 * <p>Neither id is guaranteed to resolve to a row in {@code
 * ApplicationState.sessions()}: the codec keeps a pairing whose sessions are
 * gone, and ending such an engagement is the monitor's job, not the decoder's.</p>
 *
 * <p>{@link #knownHead()} is the PR's head commit as the monitor last read
 * it from GitHub -- the known base the write guard compares the freshly
 * fetched remote tip against. Empty until the monitor's first successful
 * read; when present it is a full lowercase commit id.</p>
 */
public record BabysitPairing(
        ManagedSessionId babysitterId,
        ManagedSessionId watchedId,
        RepositoryId repositoryId,
        int prNumber,
        BabysitState state,
        Optional<String> knownHead
) {

    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    public BabysitPairing {
        Objects.requireNonNull(babysitterId, "babysitterId");
        Objects.requireNonNull(watchedId, "watchedId");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(knownHead, "knownHead");
        if (babysitterId.equals(watchedId)) {
            throw new IllegalArgumentException("A session cannot babysit itself: " + babysitterId);
        }
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive: " + prNumber);
        }
        if (knownHead.isPresent() && !isCommitId(knownHead.get())) {
            throw new IllegalArgumentException("knownHead must be a full lowercase commit id: " + knownHead.get());
        }
    }

    /** A pairing whose head the monitor has not read yet. */
    public BabysitPairing(ManagedSessionId babysitterId, ManagedSessionId watchedId, RepositoryId repositoryId,
                          int prNumber, BabysitState state) {
        this(babysitterId, watchedId, repositoryId, prNumber, state, Optional.empty());
    }

    /** Whether {@code value} is a full lowercase SHA-1 or SHA-256 commit id, the only shape {@link #knownHead} holds. */
    public static boolean isCommitId(String value) {
        return value != null && COMMIT_ID.matcher(value).matches();
    }

    public BabysitPairing withState(BabysitState newState) {
        return new BabysitPairing(babysitterId, watchedId, repositoryId, prNumber, newState, knownHead);
    }

    public BabysitPairing withKnownHead(String head) {
        return new BabysitPairing(babysitterId, watchedId, repositoryId, prNumber, state, Optional.of(head));
    }

    public boolean involves(ManagedSessionId sessionId) {
        return babysitterId.equals(sessionId) || watchedId.equals(sessionId);
    }
}
