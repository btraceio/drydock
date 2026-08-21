package app.drydock.handoff;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What drydock knows about the handed-off work without asking anyone.
 *
 * <p>Derived fresh at handoff time, so it is available and correct even when
 * the outgoing session is wedged, rate-limited or dead -- which is the case
 * the handoff exists for. This is the floor under every seed: a handoff whose
 * brief is stale or missing entirely is still bounded by current mechanical
 * truth.</p>
 *
 * <p>{@code headCommit} is absent on an unborn branch. The seed says so out
 * loud rather than printing an empty commit list, because a missing section
 * reads as "nothing worth mentioning" and this one means "there is nothing".</p>
 */
public record HandoffFacts(String branch, Optional<String> headCommit, List<String> commitSubjects,
                           List<String> changedFiles, List<String> openIntents) {

    public HandoffFacts {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(headCommit, "headCommit");
        commitSubjects = List.copyOf(Objects.requireNonNull(commitSubjects, "commitSubjects"));
        changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
        openIntents = List.copyOf(Objects.requireNonNull(openIntents, "openIntents"));
    }
}
