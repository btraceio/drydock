package app.drydock.ui.explorer;

import app.drydock.git.ChangedLineService;
import app.drydock.git.DiffScope;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The Explorer viewer's diff-overlay state (design handoff section C
 * "Explorer integration"): which {@link DiffScope} the green changed-line
 * gutter reflects, against which base branch, backed by the ONE shared
 * {@link ChangedLineService} the Review tab also consumes. The overlay
 * base is switchable from inside the Explorer ({@link #cycleScope()}).
 */
public final class DiffOverlay {

    private final ChangedLineService changedLineService;
    private final Path checkoutRoot;

    private volatile DiffScope scope = DiffScope.BASE;
    private volatile String baseBranch = "master";

    public DiffOverlay(ChangedLineService changedLineService, Path checkoutRoot) {
        this.changedLineService = changedLineService;
        this.checkoutRoot = checkoutRoot;
    }

    /** Set once the repository main checkout's branch resolves (async, by MainWorkspace). */
    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public DiffScope scope() {
        return scope;
    }

    /** Advances to the next scope (base → working tree → upstream → base). */
    public void cycleScope() {
        scope = switch (scope) {
            case BASE -> DiffScope.WORKING_TREE;
            case WORKING_TREE -> DiffScope.UPSTREAM;
            case UPSTREAM -> DiffScope.BASE;
        };
    }

    /** Human label for the banner: "the diff vs <base>" / "the working-tree diff" / "the diff vs upstream". */
    public String scopeLabel() {
        return switch (scope) {
            case BASE -> "the diff vs " + baseBranch;
            case WORKING_TREE -> "the working-tree diff";
            case UPSTREAM -> "the diff vs upstream";
        };
    }

    /** The current scope's changed-line map (relative path → new-file line numbers), cached by the shared service. */
    public CompletableFuture<Map<Path, Set<Integer>>> changedSet() {
        return changedLineService.changedSet(checkoutRoot, scope, baseBranch);
    }
}
