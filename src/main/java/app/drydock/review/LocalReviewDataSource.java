package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffService;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.tour.TourFingerprint;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * The local backend behind {@link ReviewDataSource}: a thin wrapper over the
 * existing {@link ReviewScopeRegistry}, {@link DiffService} and the review
 * contents reader, adding no rule of its own. Scope identity and
 * authorization come from the registry, the diff is the unchanged
 * {@code DiffService} call every local reader makes (same root, scope, base,
 * context), and file sides are the contents reader's text, so ids, bytes,
 * hunk ids and line keys equal the direct local calls.
 *
 * <p>The contents reader lives in the platform glue, which core cannot
 * import, hence the {@link Contents} seam it satisfies. Its sides are
 * LF-normalized text, and a failed read is already an empty string there, so
 * this adapter cannot tell an empty file from a failed read; that is the
 * existing local behaviour, kept unchanged.</p>
 */
public final class LocalReviewDataSource implements ReviewDataSource {

    /** The blocking reads the existing local {@code ReviewContents} offers. */
    public interface Contents {
        String leftText(ReviewScope scope, UnifiedDiff.FileDiff file);

        String rightText(ReviewScope scope, UnifiedDiff.FileDiff file);

        Optional<String> baseCommit(ReviewScope scope);

        Optional<String> headCommit(ReviewScope scope);
    }

    private static final String LOCAL_CHECKOUT = "local";

    private final ReviewScopeRegistry registry;
    private final DiffService diffs;
    private final Contents contents;

    public LocalReviewDataSource(ReviewScopeRegistry registry, DiffService diffs, Contents contents) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.diffs = Objects.requireNonNull(diffs, "diffs");
        this.contents = Objects.requireNonNull(contents, "contents");
    }

    @Override
    public Backend backend() {
        return Backend.LOCAL;
    }

    @Override
    public CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId session, String selection) {
        if (!registry.isAddressableBy(selection, session)) {
            return done(new Result.Refused<>("scope is not addressable by this session"));
        }
        return done(registry.byId(selection)
                .<Result<ReviewScope>>map(Result.Ok::new)
                .orElseGet(() -> new Result.Refused<>("scope is not addressable by this session")));
    }

    @Override
    public CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope scope, BooleanSupplier cancelled) {
        if (!scope.diffable()) {
            return done(new Result.Refused<>("scope has no checkout to diff"));
        }
        return diffs.diff(scope.diffRoot(), scope.diffScope(), scope.base(), DiffService.REVIEW_CONTEXT_LINES)
                .thenApply(diff -> cancelled.getAsBoolean()
                        ? new Result.Unavailable<ReviewDataSnapshot>("refresh cancelled")
                        : capture(scope, diff))
                .exceptionally(e -> new Result.Unavailable<>("local diff failed: " + e.getClass().getSimpleName()));
    }

    private Result<ReviewDataSnapshot> capture(ReviewScope scope, UnifiedDiff diff) {
        Optional<String> base = contents.baseCommit(scope);
        Optional<String> head = contents.headCommit(scope);
        if (base.isEmpty() || head.isEmpty()) {
            return new Result.Unavailable<>("base or head commit could not be resolved");
        }
        ReviewBase reviewBase = new ReviewBase(scope.base(),
                scope.baseOrigin().orElse(ReviewBase.Origin.DEFAULT_UNMEASURED));
        return new Result.Ok<>(new ReviewDataSnapshot(scope.id(), Backend.LOCAL, LOCAL_CHECKOUT, 0,
                scope.diffScope(), reviewBase, base.get(), head.get(), base.get(),
                TourFingerprint.of(diff), diff, Map.of(), Instant.now()));
    }

    @Override
    public CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
            ReviewDataSnapshot snapshot, String file, Side side) {
        Optional<ReviewScope> scope = registry.byId(snapshot.scopeId());
        Optional<UnifiedDiff.FileDiff> diffFile = snapshot.diff().files().stream()
                .filter(f -> f.path().equals(file)).findFirst();
        if (scope.isEmpty() || diffFile.isEmpty()) {
            return done(new Result.Refused<>("file is not part of this scope's diff"));
        }
        UnifiedDiff.FileDiff changed = diffFile.get();
        boolean absent = side == Side.LEFT ? "A".equals(changed.kind()) : "D".equals(changed.kind());
        String text = absent ? "" : side == Side.LEFT
                ? contents.leftText(scope.get(), changed) : contents.rightText(scope.get(), changed);
        return done(new Result.Ok<>(absent ? new ReviewDataSnapshot.Content.Absent()
                : new ReviewDataSnapshot.Content.Text(text.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public CompletableFuture<Result<Excerpt>> excerpt(
            ManagedSessionId session, String relativeFile, int line, int context) {
        return done(new Result.Unavailable<>("local excerpts are not served by this adapter"));
    }

    @Override
    public CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot snapshot) {
        Optional<ReviewScope> scope = registry.byId(snapshot.scopeId());
        if (scope.isEmpty()) {
            return done(new Result.Refused<>("scope is not registered"));
        }
        return refresh(scope.get(), () -> false).thenApply(r -> switch (r) {
            case Result.Ok<ReviewDataSnapshot> ok -> ok.value().fingerprint().equals(snapshot.fingerprint())
                    && ok.value().headCommit().equals(snapshot.headCommit())
                    ? new Result.Ok<>(snapshot)
                    : new Result.Stale<>("checkout changed since the snapshot was captured");
            default -> r;
        });
    }

    private static <T> CompletableFuture<Result<T>> done(Result<T> result) {
        return CompletableFuture.completedFuture(result);
    }
}
