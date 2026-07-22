package app.drydock.git;

import app.drydock.git.WorktreeService.Worktree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Every branch the create-worktree modal may offer, with the occupancy that
 * decides whether it can be checked out -- assembled from
 * {@link GitStatusService#listBranches} and the existing
 * {@link WorktreeService#list}, never from a second porcelain parser.
 *
 * <p>{@link #lookup(String)} is the modal's mode oracle: text that names a
 * branch means "check this out", text that does not means "create it".</p>
 */
public record BranchCatalog(List<BranchRef> branches, List<String> remotes) {

    public BranchCatalog {
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        remotes = List.copyOf(Objects.requireNonNull(remotes, "remotes"));
    }

    /** Loads the refs and the worktree list concurrently and merges them. */
    public static CompletableFuture<BranchCatalog> load(GitStatusService gitStatusService,
                                                        WorktreeService worktreeService, Path repositoryRoot) {
        return gitStatusService.listBranches(repositoryRoot)
                .thenCombine(worktreeService.list(repositoryRoot), BranchCatalog::merge);
    }

    /**
     * Pure merge: fills each local branch's occupancy from {@code worktrees}
     * and drops any remote branch whose local name already exists locally
     * (picking {@code origin/x} when local {@code x} exists is just {@code x}).
     * Local branches sort first, then remotes, each alphabetically.
     */
    public static BranchCatalog merge(BranchListing listing, List<Worktree> worktrees) {
        Map<String, Worktree> byBranch = new HashMap<>();
        for (Worktree worktree : worktrees) {
            worktree.branch().ifPresent(branch -> byBranch.putIfAbsent(branch, worktree));
        }

        List<BranchRef> locals = new ArrayList<>();
        Set<String> localNames = new HashSet<>();
        for (BranchRef ref : listing.branches()) {
            if (ref.remote()) {
                continue;
            }
            localNames.add(ref.name());
            Worktree holder = byBranch.get(ref.name());
            locals.add(new BranchRef(ref.name(), false,
                    holder == null ? Optional.empty() : Optional.of(holder.path()),
                    holder != null && (holder.prunable() || holder.locked())));
        }
        locals.sort(Comparator.comparing(BranchRef::name));

        List<BranchRef> remoteRefs = new ArrayList<>();
        for (BranchRef ref : listing.branches()) {
            // A remote ref is never itself checked out; its local
            // counterpart would be, and those are dropped here.
            if (ref.remote() && !localNames.contains(localName(ref, listing.remotes()))) {
                remoteRefs.add(ref);
            }
        }
        remoteRefs.sort(Comparator.comparing(BranchRef::name));

        List<BranchRef> merged = new ArrayList<>(locals);
        merged.addAll(remoteRefs);
        return new BranchCatalog(merged, listing.remotes());
    }

    /**
     * The local branch name {@code ref} would check out as: a remote ref
     * loses its remote prefix, a local one is unchanged. The <em>longest</em>
     * matching remote wins, because a remote name may itself contain a slash
     * ({@code git remote add team/fork} is legal) -- stripping the first path
     * segment would turn {@code team/fork/feature/x} into {@code fork/feature/x}.
     */
    public String localName(BranchRef ref) {
        return localName(ref, remotes);
    }

    private static String localName(BranchRef ref, List<String> remotes) {
        if (!ref.remote()) {
            return ref.name();
        }
        String longest = null;
        for (String remote : remotes) {
            String prefix = remote + "/";
            if (ref.name().startsWith(prefix) && (longest == null || prefix.length() > longest.length())) {
                longest = prefix;
            }
        }
        return longest == null ? ref.name() : ref.name().substring(longest.length());
    }

    /**
     * Resolves picker text to a branch: an exact local match first (a local
     * branch may literally be named {@code origin/foo}), then an exact
     * remote-tracking match, then the bare name qualified by each remote.
     * Empty means "no such branch" -- i.e. create mode.
     */
    public Optional<BranchRef> lookup(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String needle = text.strip();
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        for (BranchRef ref : branches) {
            if (!ref.remote() && ref.name().equals(needle)) {
                return Optional.of(ref);
            }
        }
        for (BranchRef ref : branches) {
            if (ref.remote() && ref.name().equals(needle)) {
                return Optional.of(ref);
            }
        }
        for (String remote : remotes) {
            String qualified = remote + "/" + needle;
            for (BranchRef ref : branches) {
                if (ref.remote() && ref.name().equals(qualified)) {
                    return Optional.of(ref);
                }
            }
        }
        return Optional.empty();
    }
}
