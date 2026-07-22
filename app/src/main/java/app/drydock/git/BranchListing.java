package app.drydock.git;

import java.util.List;
import java.util.Objects;

/**
 * The raw result of {@link GitStatusService#listBranches}: every branch ref
 * plus the repository's remote names. The remote names are not decoration --
 * a remote may itself contain a slash ({@code git remote add team/fork}), so
 * they are the only way to split {@code team/fork/feature/x} into its remote
 * and its local name. {@link BranchCatalog#localName(BranchRef)} consumes
 * them to split such names correctly.
 */
public record BranchListing(List<BranchRef> branches, List<String> remotes) {

    public BranchListing {
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        remotes = List.copyOf(Objects.requireNonNull(remotes, "remotes"));
    }
}
