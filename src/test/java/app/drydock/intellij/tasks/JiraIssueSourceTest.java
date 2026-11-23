package app.drydock.intellij.tasks;

import app.drydock.git.JiraConnection;
import app.drydock.git.JiraConnection.Deployment;
import app.drydock.git.JiraIssue;
import app.drydock.git.JiraIssueListing;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The caching, laziness and invalidation behaviour of {@link JiraIssueSource}
 * (spec AC9, Q3, risk 2): every platform touch — the service, the password
 * safe, the clock — is a fake that counts its calls.
 */
class JiraIssueSourceTest {

    private static final Path A = Path.of("/repo/a");
    private static final Path B = Path.of("/repo/b");
    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    /** A clock the test advances by hand. */
    private static final class TestClock implements LongSupplier {
        long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advance(long nanos) {
            this.nanos += nanos;
        }
    }

    /** A fetch that answers from a queue, counts its calls, and only reads the secret when told to. */
    private static final class FakeFetch implements JiraIssueSource.Fetch {
        int calls;
        boolean readSecret;
        final Queue<JiraIssueListing> answers = new ArrayDeque<>();

        @Override
        public CompletableFuture<JiraIssueListing> openIssues(Optional<JiraConnection> connection,
                                                               Supplier<Optional<String>> secret) {
            calls++;
            if (readSecret) {
                secret.get();
            }
            return CompletableFuture.completedFuture(answers.isEmpty() ? listed() : answers.remove());
        }
    }

    /** The owned resource, counting its closes. */
    private static final class Owned implements AutoCloseable {
        int closes;

        @Override
        public void close() {
            closes++;
        }
    }

    /** The secret function, counting reads; the secret itself never leaves the test. */
    private static final class Secrets implements Function<JiraProjectSettings, Optional<String>> {
        int reads;

        @Override
        public Optional<String> apply(JiraProjectSettings settings) {
            reads++;
            return Optional.of("tok-SECRET");
        }
    }

    private static JiraProjectSettings enabled() {
        return new JiraProjectSettings(true, Deployment.CLOUD, "https://acme.atlassian.net",
                Optional.of("me@acme.com"));
    }

    private static JiraGroup group(String id, String jql) {
        return new JiraGroup(id, "Group " + id, jql);
    }

    private static JiraIssueListing listed() {
        return new JiraIssueListing.Listed(List.of(new JiraIssue("PROJ-123", "Fix allocation accounting",
                URI.create("https://acme.atlassian.net/browse/PROJ-123").toString(), List.of("profiling"),
                Optional.of(Instant.parse("2026-10-20T10:15:30Z")))));
    }

    private final TestClock clock = new TestClock();
    private final FakeFetch fetch = new FakeFetch();
    private final Owned owned = new Owned();
    private final Secrets secrets = new Secrets();
    private final JiraIssueSource source = new JiraIssueSource(fetch, owned, secrets, clock::getAsLong);

    @Test
    void aDisabledProjectIsUnsupportedWithNoFetchAndNoSecretRead() {
        JiraProjectSettings disabled = new JiraProjectSettings(
                false, Deployment.CLOUD, "https://acme.atlassian.net", Optional.of("me@acme.com"));

        JiraIssueListing listing = source.issuesOf(A, disabled, group("g1", "assignee = currentUser()")).join();

        assertInstanceOf(JiraIssueListing.Unsupported.class, listing);
        assertEquals(0, fetch.calls);
        assertEquals(0, secrets.reads);
    }

    @Test
    void twoCallsWithinAMinuteFetchOnce() {
        CompletableFuture<JiraIssueListing> first = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        clock.advance(MINUTE - 1);
        CompletableFuture<JiraIssueListing> second = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));

        assertSame(first, second);
        assertInstanceOf(JiraIssueListing.Listed.class, second.join());
        assertEquals(1, fetch.calls);
    }

    @Test
    void aFailedListingIsServedFromTheCacheWithinTheMinute() {
        fetch.answers.add(new JiraIssueListing.Failed("Jira refused the credentials (HTTP 401)"));

        CompletableFuture<JiraIssueListing> first = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        clock.advance(MINUTE - 1);
        CompletableFuture<JiraIssueListing> second = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));

        assertSame(first, second);
        assertInstanceOf(JiraIssueListing.Failed.class, second.join());
        assertEquals(1, fetch.calls);
    }

    @Test
    void advancingTheClockPastAMinuteRefetches() {
        CompletableFuture<JiraIssueListing> first = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        clock.advance(MINUTE + 1);
        CompletableFuture<JiraIssueListing> second = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));

        assertNotSame(first, second);
        assertInstanceOf(JiraIssueListing.Listed.class, second.join());
        assertEquals(2, fetch.calls);
    }

    @Test
    void invalidatingOneRootRefetchesOnlyThatRoot() {
        CompletableFuture<JiraIssueListing> a = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        CompletableFuture<JiraIssueListing> b = source.issuesOf(B, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        assertEquals(2, fetch.calls);

        source.invalidate(A);
        assertNotSame(a, source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL)));
        assertEquals(3, fetch.calls);

        // B was never invalidated, so it is still the cached future.
        assertSame(b, source.issuesOf(B, enabled(), group("g1", JiraConnection.DEFAULT_JQL)));
        assertEquals(3, fetch.calls);
    }

    @Test
    void invalidatingEverythingRefetchesAllRoots() {
        CompletableFuture<JiraIssueListing> a = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        CompletableFuture<JiraIssueListing> b = source.issuesOf(B, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        assertEquals(2, fetch.calls);

        source.invalidate();
        assertNotSame(a, source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL)));
        assertNotSame(b, source.issuesOf(B, enabled(), group("g1", JiraConnection.DEFAULT_JQL)));
        assertEquals(4, fetch.calls);
    }

    @Test
    void aDifferentJqlGetsItsOwnEntry() {
        CompletableFuture<JiraIssueListing> defaultQuery = source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        CompletableFuture<JiraIssueListing> override = source.issuesOf(A, enabled(), group("g1", "project = DRY ORDER BY updated ASC"));

        assertNotSame(defaultQuery, override);
        assertInstanceOf(JiraIssueListing.Listed.class, override.join());
        assertEquals(2, fetch.calls);

        // Each entry is then cached independently of the other.
        assertSame(defaultQuery, source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL)));
        assertSame(override, source.issuesOf(A, enabled(), group("g1", "project = DRY ORDER BY updated ASC")));
        assertEquals(2, fetch.calls);
    }

    @Test
    void theSecretIsReadOnlyWhenTheFetchCallsTheSupplier() {
        // A fetch that never touches the supplier costs no secret read...
        source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        assertEquals(1, fetch.calls);
        assertEquals(0, secrets.reads);

        // ...and a cache hit does not call the fetch at all.
        source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        assertEquals(1, fetch.calls);
        assertEquals(0, secrets.reads);

        // Only a fetch that invokes the supplier reads the secret — once.
        fetch.readSecret = true;
        clock.advance(MINUTE + 1);
        source.issuesOf(A, enabled(), group("g1", JiraConnection.DEFAULT_JQL));
        assertEquals(2, fetch.calls);
        assertEquals(1, secrets.reads);
    }

    @Test
    void closeClosesTheOwnedResource() {
        assertEquals(0, owned.closes);
        source.close();
        assertEquals(1, owned.closes);
    }
}
