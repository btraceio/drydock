package app.drydock.handoff;

import java.util.List;
import java.util.Objects;

/**
 * What drydock knows about the forked work without asking anyone.
 *
 * <p>Derived fresh at fork time, so it is available and correct even when the
 * outgoing session is wedged, rate-limited or dead -- which is the case the
 * fork exists for. This is the floor under every seed: a fork whose brief is
 * stale or missing entirely is still bounded by current mechanical truth.</p>
 */
public record ForkFacts(String branch, String baseBranch, List<String> commitSubjects,
                        List<String> changedFiles, List<String> openIntents) {

    public ForkFacts {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(baseBranch, "baseBranch");
        commitSubjects = List.copyOf(Objects.requireNonNull(commitSubjects, "commitSubjects"));
        changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
        openIntents = List.copyOf(Objects.requireNonNull(openIntents, "openIntents"));
    }
}
