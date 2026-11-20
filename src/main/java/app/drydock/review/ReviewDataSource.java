package app.drydock.review;

import app.drydock.domain.ManagedSessionId;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Headless source of review data (diff, file sides, excerpts) for one
 * backend. Every operation is asynchronous and returns a typed
 * {@link Result}: a failure is {@link Result.Unavailable}, {@link Result.Stale}
 * or {@link Result.Refused}, never empty text standing in for data. Callers
 * reach an implementation only through {@link ReviewDataSources}, which picks
 * the backend from the verified session/scope.
 */
public interface ReviewDataSource {

    /** Which backend serves a session's data; a remote one never reads the host filesystem. */
    enum Backend { LOCAL, REMOTE }

    /** Outcome of an operation. Exactly one of the four shapes; none carries fabricated data. */
    sealed interface Result<T> {
        record Ok<T>(T value) implements Result<T> {
            public Ok {
                Objects.requireNonNull(value, "value");
            }
        }

        /** The data could not be read (failed read, over limit, connection lost). */
        record Unavailable<T>(String reason) implements Result<T> {
            public Unavailable {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** The data was read but no longer matches the checkout it was captured from. */
        record Stale<T>(String reason) implements Result<T> {
            public Stale {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** The request is not authorized/addressable; nothing was read. */
        record Refused<T>(String reason) implements Result<T> {
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** A few source lines around a line of a file. */
    record Excerpt(String file, int startLine, List<String> lines) {
        public Excerpt {
            Objects.requireNonNull(file, "file");
            lines = List.copyOf(lines);
        }
    }

    /** Which side of a changed file. */
    enum Side { LEFT, RIGHT }

    Backend backend();

    /** The authorized scope for {@code selection} in {@code session}. */
    CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId session, String selection);

    CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope scope, BooleanSupplier cancelled);

    CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
            ReviewDataSnapshot snapshot, String file, Side side);

    CompletableFuture<Result<Excerpt>> excerpt(
            ManagedSessionId session, String relativeFile, int line, int context);

    /** Ok when the snapshot still matches its checkout, otherwise Stale/Unavailable. */
    CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot snapshot);
}
