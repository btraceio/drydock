package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewDataSource.Backend;
import app.drydock.review.ReviewDataSource.Result;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Dispatches every review-data operation to the one {@link ReviewDataSource}
 * that the <em>verified</em> session/scope belongs to. There is no default
 * backend: an unknown session or scope is refused, and a remote one whose
 * adapter is not registered is refused rather than served by the local
 * adapter, so a remote scope can never fall through to a host path. An adapter
 * failure becomes {@code Unavailable}; no operation throws or yields empty text.
 */
public final class ReviewDataSources {

    /** Authoritative lookup of which backend a session/scope was registered under. */
    public interface Verifier {
        Optional<Backend> backendOfSession(ManagedSessionId session);

        Optional<Backend> backendOfScope(ReviewScope scope);

        Optional<Backend> backendOfScopeId(String scopeId);
    }

    private final Verifier verifier;
    private final Map<Backend, ReviewDataSource> sources = new EnumMap<>(Backend.class);

    public ReviewDataSources(Verifier verifier, Map<Backend, ReviewDataSource> sources) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        sources.forEach((backend, source) -> {
            if (source.backend() != backend) {
                throw new IllegalArgumentException("adapter for " + backend + " reports " + source.backend());
            }
            this.sources.put(backend, source);
        });
    }

    public CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId session, String selection) {
        return dispatch(verifier.backendOfSession(session), "session", s -> s.resolve(session, selection));
    }

    public CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope scope, BooleanSupplier cancelled) {
        return dispatch(verifier.backendOfScope(scope), "scope", s -> s.refresh(scope, cancelled));
    }

    public CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
            ReviewDataSnapshot snapshot, String file, ReviewDataSource.Side side) {
        return dispatchSnapshot(snapshot, s -> s.contents(snapshot, file, side));
    }

    public CompletableFuture<Result<ReviewDataSource.Excerpt>> excerpt(
            ManagedSessionId session, String relativeFile, int line, int context) {
        return dispatch(verifier.backendOfSession(session), "session",
                s -> s.excerpt(session, relativeFile, line, context));
    }

    public CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot snapshot) {
        return dispatchSnapshot(snapshot, s -> s.validateCurrent(snapshot));
    }

    /** A snapshot is served only by the backend its scope is verified under AND that captured it. */
    private <T> CompletableFuture<Result<T>> dispatchSnapshot(
            ReviewDataSnapshot snapshot, Function<ReviewDataSource, CompletableFuture<Result<T>>> op) {
        Optional<Backend> verified = verifier.backendOfScopeId(snapshot.scopeId());
        if (verified.isPresent() && verified.get() != snapshot.backend()) {
            return refused("snapshot backend " + snapshot.backend() + " does not match verified scope backend");
        }
        return dispatch(verified, "scope", op);
    }

    private <T> CompletableFuture<Result<T>> dispatch(
            Optional<Backend> backend, String what,
            Function<ReviewDataSource, CompletableFuture<Result<T>>> op) {
        if (backend.isEmpty()) {
            return refused("unknown " + what + ": not registered with a verified backend");
        }
        ReviewDataSource source = sources.get(backend.get());
        if (source == null) {
            return refused("no " + backend.get() + " review data source registered for this " + what);
        }
        Supplier<CompletableFuture<Result<T>>> call = () -> op.apply(source);
        try {
            CompletableFuture<Result<T>> future = call.get();
            if (future == null) {
                return unavailable(backend.get(), "returned no result");
            }
            return future
                    .thenApply(r -> r != null ? r
                            : new Result.Unavailable<T>(backend.get() + " review data source returned no result"))
                    .exceptionally(e -> new Result.Unavailable<>(
                            backend.get() + " review data source failed: " + e.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(new Result.Unavailable<>(
                    backend.get() + " review data source failed: " + e.getClass().getSimpleName()));
        }
    }

    private static <T> CompletableFuture<Result<T>> unavailable(Backend backend, String what) {
        return CompletableFuture.completedFuture(
                new Result.Unavailable<>(backend + " review data source " + what));
    }

    private static <T> CompletableFuture<Result<T>> refused(String reason) {
        return CompletableFuture.completedFuture(new Result.Refused<>(reason));
    }
}
