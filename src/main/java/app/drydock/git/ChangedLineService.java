package app.drydock.git;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ONE shared source of truth for "which lines changed" per diff scope
 * (design handoff section C "Explorer integration"): consumed by BOTH the
 * Review tab and the Explorer viewer, {@link #changedSet} maps each
 * changed file (checkout-relative {@link Path}) to the set of new-file
 * line numbers its diff touches -- added and modified lines that exist in
 * the post-image, i.e. exactly the lines the Explorer marks with the
 * green gutter and the Review diff offers "Open in Explorer at this
 * line" on.
 *
 * <p>Results are cached per (root, scope, base) so the Explorer viewer
 * can consult the map on every opened file without re-running git;
 * {@link #invalidate(Path)} drops a checkout's cache (Review calls it on
 * every explicit re-diff).</p>
 */
public final class ChangedLineService {

    private record Key(Path root, DiffScope scope, String base) {
    }

    private final DiffService diffService;
    private final Map<Key, CompletableFuture<Map<Path, Set<Integer>>>> cache = new ConcurrentHashMap<>();

    public ChangedLineService(DiffService diffService) {
        this.diffService = Objects.requireNonNull(diffService, "diffService");
    }

    /**
     * The changed-line map of {@code checkoutRoot} for {@code scope}
     * (against {@code baseBranch} when the scope is {@link DiffScope#BASE}),
     * computed off-thread and cached until {@link #invalidate(Path)}.
     * A failed diff is not cached, so a later call retries.
     */
    public CompletableFuture<Map<Path, Set<Integer>>> changedSet(Path checkoutRoot, DiffScope scope,
                                                                 String baseBranch) {
        Key key = new Key(checkoutRoot, scope, baseBranch);
        return cache.computeIfAbsent(key, k -> {
            CompletableFuture<Map<Path, Set<Integer>>> future = diffService
                    .diff(checkoutRoot, scope, baseBranch)
                    .thenApply(ChangedLineService::toChangedSet);
            future.whenComplete((map, failure) -> {
                if (failure != null) {
                    cache.remove(k, future);
                }
            });
            return future;
        });
    }

    /** Drops every cached scope of {@code checkoutRoot} (called after a re-diff / handoff completes). */
    public void invalidate(Path checkoutRoot) {
        cache.keySet().removeIf(key -> key.root().equals(checkoutRoot));
    }

    /** New-file line numbers of every ADD row, grouped by relative path. */
    static Map<Path, Set<Integer>> toChangedSet(UnifiedDiff diff) {
        Map<Path, Set<Integer>> byFile = new HashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            Set<Integer> lines = new HashSet<>();
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    if (line.kind() == UnifiedDiff.Line.Kind.ADD) {
                        line.newLine().ifPresent(lines::add);
                    }
                }
            }
            if (!lines.isEmpty()) {
                byFile.put(Path.of(file.path()), Set.copyOf(lines));
            }
        }
        return Map.copyOf(byFile);
    }
}
