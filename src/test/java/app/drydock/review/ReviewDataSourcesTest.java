package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewDataSource.Backend;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewDataSource.Side;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewDataSourcesTest {

    private static final ManagedSessionId LOCAL_SESSION = ManagedSessionId.newId();
    private static final ManagedSessionId REMOTE_SESSION = ManagedSessionId.newId();

    private static ReviewScope scope(String id) {
        // Colliding path suffix on purpose: only the verified registration decides the backend.
        return new ReviewScope(id, ReviewScope.Kind.BRANCH, Path.of("/repo"), Optional.empty(), "main", "feat",
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ReviewDataSnapshot snapshot(String scopeId, Backend backend) {
        return new ReviewDataSnapshot(scopeId, backend, "checkout", 1, DiffScope.BASE, new ReviewBase("main", ReviewBase.Origin.PULL_REQUEST), "b", "h", "b", "fp",
                new UnifiedDiff(List.of()), Map.of(), Instant.EPOCH);
    }

    /** Records calls; never touches a filesystem. */
    private static final class Fake implements ReviewDataSource {
        final Backend backend;
        final AtomicInteger calls = new AtomicInteger();
        boolean throwing;
        boolean throwingSync;
        boolean nullResult;

        Fake(Backend backend) {
            this.backend = backend;
        }

        @Override public Backend backend() { return backend; }

        private <T> CompletableFuture<Result<T>> ok(T value) {
            calls.incrementAndGet();
            if (throwingSync) {
                throw new IllegalStateException("sync boom");
            }
            if (nullResult) {
                return CompletableFuture.completedFuture(null);
            }
            if (throwing) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
            return CompletableFuture.completedFuture(new Result.Ok<>(value));
        }

        @Override public CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId s, String sel) {
            return ok(scope(backend.name()));
        }

        @Override public CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope s, BooleanSupplier c) {
            return ok(snapshot(s.id(), backend));
        }

        @Override public CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
                ReviewDataSnapshot s, String file, Side side) {
            return ok(new ReviewDataSnapshot.Content.Absent());
        }

        @Override public CompletableFuture<Result<Excerpt>> excerpt(ManagedSessionId s, String f, int l, int c) {
            return ok(new Excerpt(f, l, List.of(backend.name())));
        }

        @Override public CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot s) {
            return ok(s);
        }
    }

    private static final ReviewDataSources.Verifier VERIFIER = new ReviewDataSources.Verifier() {
        @Override public Optional<Backend> backendOfSession(ManagedSessionId s) {
            return s.equals(LOCAL_SESSION) ? Optional.of(Backend.LOCAL)
                    : s.equals(REMOTE_SESSION) ? Optional.of(Backend.REMOTE) : Optional.empty();
        }

        @Override public Optional<Backend> backendOfScope(ReviewScope s) { return backendOfScopeId(s.id()); }

        @Override public Optional<Backend> backendOfScopeId(String id) {
            return switch (id) {
                case "local-scope" -> Optional.of(Backend.LOCAL);
                case "remote-scope" -> Optional.of(Backend.REMOTE);
                default -> Optional.empty();
            };
        }
    };

    private final Fake local = new Fake(Backend.LOCAL);
    private final Fake remote = new Fake(Backend.REMOTE);
    private final ReviewDataSources both = new ReviewDataSources(VERIFIER,
            Map.of(Backend.LOCAL, local, Backend.REMOTE, remote));

    @Test
    void dispatchesBySessionKind() throws Exception {
        assertEquals("LOCAL", ((Result.Ok<ReviewDataSource.Excerpt>) both.excerpt(LOCAL_SESSION, "a", 1, 1).get())
                .value().lines().get(0));
        assertEquals("REMOTE", ((Result.Ok<ReviewDataSource.Excerpt>) both.excerpt(REMOTE_SESSION, "a", 1, 1).get())
                .value().lines().get(0));
        assertEquals(Backend.REMOTE, ((Result.Ok<ReviewDataSnapshot>) both.refresh(scope("remote-scope"), () -> false)
                .get()).value().backend());
        assertEquals(1, local.calls.get());
        assertEquals(2, remote.calls.get());
    }

    @Test
    void unknownSessionAndScopeAreRefusedWithoutCallingAnyAdapter() throws Exception {
        assertInstanceOf(Result.Refused.class, both.resolve(ManagedSessionId.newId(), "x").get());
        assertInstanceOf(Result.Refused.class, both.refresh(scope("nope"), () -> false).get());
        assertInstanceOf(Result.Refused.class, both.validateCurrent(snapshot("nope", Backend.LOCAL)).get());
        assertEquals(0, local.calls.get() + remote.calls.get());
    }

    @Test
    void remoteScopeNeverFallsThroughToLocalAdapter() throws Exception {
        ReviewDataSources localOnly = new ReviewDataSources(VERIFIER, Map.of(Backend.LOCAL, local));
        assertInstanceOf(Result.Refused.class, localOnly.refresh(scope("remote-scope"), () -> false).get());
        assertInstanceOf(Result.Refused.class, localOnly.excerpt(REMOTE_SESSION, "a", 1, 1).get());
        assertEquals(0, local.calls.get());
    }

    @Test
    void snapshotFromAnotherBackendIsRefused() throws Exception {
        // A LOCAL-captured snapshot presented under a scope verified as REMOTE must not reach either adapter.
        assertInstanceOf(Result.Refused.class,
                both.contents(snapshot("remote-scope", Backend.LOCAL), "a", Side.LEFT).get());
        assertEquals(0, local.calls.get() + remote.calls.get());
    }

    @Test
    void adapterFailureIsUnavailableNotEmptyOrThrown() throws Exception {
        remote.throwing = true;
        assertInstanceOf(Result.Unavailable.class, both.excerpt(REMOTE_SESSION, "a", 1, 1).get());
    }

    @Test
    void synchronousThrowAndNullResultAreUnavailableForEveryOperation() throws Exception {
        remote.throwingSync = true;
        assertInstanceOf(Result.Unavailable.class, both.excerpt(REMOTE_SESSION, "a", 1, 1).get());
        assertInstanceOf(Result.Unavailable.class, both.resolve(REMOTE_SESSION, "x").get());
        assertInstanceOf(Result.Unavailable.class, both.refresh(scope("remote-scope"), () -> false).get());
        remote.throwingSync = false;
        remote.nullResult = true;
        ReviewDataSnapshot snap = snapshot("remote-scope", Backend.REMOTE);
        assertInstanceOf(Result.Unavailable.class, both.contents(snap, "a", Side.RIGHT).get());
        assertInstanceOf(Result.Unavailable.class, both.validateCurrent(snap).get());
        assertEquals(5, remote.calls.get());
        assertEquals(0, local.calls.get());
    }

    @Test
    void matchingSnapshotIsServedAndMissingAdapterRefusesSnapshotOps() throws Exception {
        ReviewDataSnapshot snap = snapshot("remote-scope", Backend.REMOTE);
        assertInstanceOf(Result.Ok.class, both.validateCurrent(snap).get());
        assertEquals(1, remote.calls.get());
        ReviewDataSources localOnly = new ReviewDataSources(VERIFIER, Map.of(Backend.LOCAL, local));
        assertInstanceOf(Result.Refused.class, localOnly.contents(snap, "a", Side.LEFT).get());
        assertInstanceOf(Result.Refused.class, localOnly.validateCurrent(snap).get());
        assertEquals(0, local.calls.get());
    }

    @Test
    void adapterMustReportTheBackendItIsRegisteredFor() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewDataSources(VERIFIER, Map.of(Backend.REMOTE, local)));
    }

    private static ReviewDataSnapshot.Content of(byte[] b) {
        return ReviewDataSnapshot.Content.of(b);
    }

    @Test
    void contentTypingSeparatesTextBinaryAndAbsent() {
        assertInstanceOf(ReviewDataSnapshot.Content.Text.class, of("a\r\nb".getBytes()));
        ReviewDataSnapshot.Content.Binary binary = (ReviewDataSnapshot.Content.Binary) of(new byte[] {1, 0, 2});
        assertEquals(3, binary.size());
        assertEquals(64, binary.sha256().length());
        // Empty text is a present empty file, never confused with Absent (a truly missing side).
        assertInstanceOf(ReviewDataSnapshot.Content.Text.class, of(new byte[0]));
        assertNotEquals(new ReviewDataSnapshot.Content.Absent(), of(new byte[0]));
        assertNotEquals(new ReviewDataSnapshot.Content.Absent(), binary);
    }

    @Test
    void nulProbeIsLimitedToTheFirst8000Bytes() {
        byte[] late = new byte[9000];
        java.util.Arrays.fill(late, (byte) 'a');
        late[8000] = 0; // first byte beyond the probe window: still text
        assertInstanceOf(ReviewDataSnapshot.Content.Text.class, of(late));
        late[7999] = 0; // last byte inside the window: binary
        assertInstanceOf(ReviewDataSnapshot.Content.Binary.class, of(late));
        byte[] allText = new byte[20000];
        java.util.Arrays.fill(allText, (byte) 'z');
        assertInstanceOf(ReviewDataSnapshot.Content.Text.class, of(allText));
    }

    @Test
    void contentTextIsDefensivelyCopiedBothWays() {
        byte[] in = {'x'};
        ReviewDataSnapshot.Content.Text text = (ReviewDataSnapshot.Content.Text) of(in);
        in[0] = 'q';
        assertEquals('x', text.bytes()[0]);
        text.bytes()[0] = 'y';
        assertEquals('x', text.bytes()[0]);
    }

    @Test
    void snapshotKeepsBaseProvenanceAndCopiesSources() {
        for (ReviewBase.Origin origin : ReviewBase.Origin.values()) {
            ReviewBase base = new ReviewBase("develop", origin);
            Map<String, ReviewDataSnapshot.SourceFile> src = new java.util.HashMap<>();
            ReviewDataSnapshot s = new ReviewDataSnapshot("s", Backend.LOCAL, "c", 1, DiffScope.BASE, base, "b", "h",
                    "b", "fp", new UnifiedDiff(List.of()), src, Instant.EPOCH);
            assertEquals(origin, s.base().origin());
            assertEquals("develop", s.base().ref());
            src.put("f", new ReviewDataSnapshot.SourceFile(new ReviewDataSnapshot.Content.Absent(),
                    new ReviewDataSnapshot.Content.Absent()));
            assertEquals(0, s.sources().size());
        }
        assertThrows(NullPointerException.class, () -> new ReviewDataSnapshot("s", Backend.LOCAL, "c", 1,
                DiffScope.BASE, null, "b", "h", "b", "fp", new UnifiedDiff(List.of()), Map.of(), Instant.EPOCH));
    }
}
