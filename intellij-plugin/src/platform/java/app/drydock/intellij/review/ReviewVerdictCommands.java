package app.drydock.intellij.review;

import app.drydock.git.DiffService;
import app.drydock.git.GitException;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStore;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.SessionReviewScopes;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The tab's mark-reviewed / unmark commands: the write side of the Review
 * tree, kept apart from the read side so the plan's pieces stay
 * independently testable.
 *
 * <p>Both commands re-run the scope's diff through {@link DiffService} with
 * {@link DiffService#REVIEW_CONTEXT_LINES} and compute the hunk digests from
 * <em>that</em> fresh diff, never from the {@link UnifiedDiff.FileDiff} the
 * caller hands in -- the rows the human clicked may predate the click (the
 * file may have changed since the snapshot, AC15's race), and an approval
 * keyed on a digest that no longer describes the code is exactly the bug the
 * snapshot-row check and this re-diff exist to prevent. The passed
 * {@code FileDiff} contributes only its path: the file's identity.</p>
 *
 * <p>A verdict is <em>dated by commits</em> (Q5): {@code base} and {@code
 * head} are resolved to 40-hex commit ids, and an approval whose base or head
 * cannot be resolved is refused rather than stamped with a sentinel -- a
 * verdict dated with something {@code review_recheck} cannot hand to git
 * would poison the very recheck contract this write exists to serve.</p>
 *
 * <p>Threading: never blocks the caller. The diff runs on {@link
 * DiffService}'s own executor; the steps after it (two short {@code git
 * rev-parse} calls and the store writes) run on the injected background
 * executor (the plugin's own executor in production — never
 * {@code ForkJoinPool.commonPool()}) via the returned future, so nothing here runs on the caller's thread -- not even
 * when the diff has already completed. The store writes go to the backend's
 * own {@link AnnotationStore} (one writer), injected by the tab's wiring;
 * this class never constructs one.</p>
 */
public final class ReviewVerdictCommands {

    private static final Logger LOG = System.getLogger(ReviewVerdictCommands.class.getName());

    private final DiffService diffService;
    private final GitStatusService git;
    private final AnnotationStore store;
    private final Executor executor;

    /**
     * @param store the backend's own annotation store -- the same instance
     *              the MCP router writes through, so a human's approval and
     *              an agent's {@code review_recheck} read one truth
     * @param executor runs the blocking steps after the diff (the
     *              {@code rev-parse} calls and the store writes): the
     *              plugin's background executor in production
     */
    public ReviewVerdictCommands(DiffService diffService, GitStatusService git, AnnotationStore store,
                                 Executor executor) {
        this.diffService = Objects.requireNonNull(diffService, "diffService");
        this.git = Objects.requireNonNull(git, "git");
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** What a command did: ok, or refused with the reason shown to the human. */
    public sealed interface Result {

        record Ok() implements Result { }

        record Refused(String reason) implements Result { }
    }

    /**
     * Approves every hunk of {@code file} under the scope, or refuses with a
     * reason the caller can show. Refused, writing nothing, while the file
     * has an open blocking finding, while the base or head cannot be resolved
     * to a commit, or while the file has no text hunks to review. Completes
     * exceptionally (a {@link GitException} in a {@code CompletionException})
     * when the diff itself cannot be read -- that is the tab's diff-failed
     * state, not a review decision.
     */
    public CompletableFuture<Result> markReviewed(ReviewScope scope, UnifiedDiff.FileDiff file) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(file, "file");
        return diff(scope).thenApplyAsync(fresh -> mark(scope, file, fresh), executor);
    }

    /**
     * Removes the file's human approvals ({@code APPROVED}) from the current
     * diff, and nothing else: an agent's own {@code AUTO_APPROVED} and a
     * {@code CHANGES} verdict stay (Q14 -- an unmark must never undo an
     * assertion that was not the human's), and other files' verdicts are
     * untouched. Never refuses; a file with nothing to clear is a no-op.
     */
    public CompletableFuture<Result> unmark(ReviewScope scope, UnifiedDiff.FileDiff file) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(file, "file");
        return diff(scope).thenApplyAsync(fresh -> unmark(scope, file, fresh), executor);
    }

    /**
     * {@code thenApplyAsync}, not {@code thenApply}: with {@code thenApply} a
     * diff that completed before this call attaches would run every step
     * below -- blocking git spawns included -- on the caller's thread, which
     * for the tab is the EDT.
     */
    private CompletableFuture<UnifiedDiff> diff(ReviewScope scope) {
        return diffService.diff(scope.diffRoot(), scope.diffScope(), scope.base(),
                DiffService.REVIEW_CONTEXT_LINES);
    }

    // ---- mark reviewed (AC9, AC11) ------------------------------------------

    /**
     * The steps of the plan in their fixed order: resolve the dating commits
     * first (a verdict that cannot be dated is refused no matter what the
     * findings say), then the blocking finding, then whether there is
     * anything to approve at all, then the writes. Runs on a background
     * executor; blocking git calls are legal here.
     */
    private Result mark(ReviewScope scope, UnifiedDiff.FileDiff file, UnifiedDiff fresh) {
        Optional<String> base = resolveRef(scope, scope.base());
        if (base.isEmpty()) {
            return new Result.Refused("the base of this review ('" + scope.base()
                    + "') does not resolve to a commit right now, so an approval could not be dated; "
                    + "nothing was written");
        }
        String headRef = SessionReviewScopes.headRevision(scope.head());
        Optional<String> head = resolveRef(scope, headRef);
        if (head.isEmpty()) {
            return new Result.Refused("the head of this review ('" + headRef
                    + "') does not resolve to a commit right now, so an approval could not be dated; "
                    + "nothing was written");
        }
        ReviewAnnotation blocker = store.forScope(scope.id()).stream()
                .filter(finding -> finding.file().equals(file.path()))
                .filter(ReviewAnnotation::blocksApproval)
                .findFirst()
                .orElse(null);
        if (blocker != null) {
            return new Result.Refused("cannot approve while the open blocking finding "
                    + blocker.id() + " (" + blocker.displayTitle() + ") is unresolved; "
                    + "nothing was written");
        }
        Set<String> digests = distinctDigests(fresh, file);
        if (digests.isEmpty()) {
            return new Result.Refused("no text changes to review");
        }
        Instant at = Instant.now();
        for (String digest : digests) {
            store.putVerdict(new ReviewVerdict(scope.id(), digest, ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), at, base.get(), head.get()));
        }
        return new Result.Ok();
    }

    // ---- unmark (AC12, Q14) -------------------------------------------------

    /**
     * Clears only what a human gave: an {@code APPROVED} verdict on one of
     * the file's current digests. Runs on a background executor.
     */
    private Result unmark(ReviewScope scope, UnifiedDiff.FileDiff file, UnifiedDiff fresh) {
        for (String digest : distinctDigests(fresh, file)) {
            store.verdict(scope.id(), digest)
                    .filter(verdict -> verdict.decision() == ReviewVerdict.Decision.APPROVED)
                    .ifPresent(verdict -> store.clearVerdict(scope.id(), digest));
        }
        return new Result.Ok();
    }

    // ---- shared ---------------------------------------------------------

    /**
     * The distinct digests of the file's hunks in the fresh diff, in hunk
     * order. Two hunks with byte-identical lines are one digest and get one
     * verdict; an absent file has no hunks and therefore no digests.
     */
    private static Set<String> distinctDigests(UnifiedDiff fresh, UnifiedDiff.FileDiff file) {
        Set<String> digests = new LinkedHashSet<>();
        for (UnifiedDiff.FileDiff candidate : fresh.files()) {
            if (!candidate.path().equals(file.path())) {
                continue;
            }
            for (UnifiedDiff.Hunk hunk : candidate.hunks()) {
                digests.add(HunkDigest.of(candidate.path(), hunk));
            }
        }
        return digests;
    }

    /**
     * One ref to its commit, or empty when git cannot say -- logged, never
     * silent, because an unresolvable base is why a whole scope reads as
     * stale and a reader asking why must be able to find out.
     */
    private Optional<String> resolveRef(ReviewScope scope, String ref) {
        Path root = scope.diffRoot();
        try {
            return git.commitForRefBlocking(root, ref);
        } catch (GitException e) {
            LOG.log(Level.WARNING, "Could not resolve review ref " + ref + " in " + root
                    + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
