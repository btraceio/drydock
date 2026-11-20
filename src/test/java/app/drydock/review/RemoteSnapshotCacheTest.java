package app.drydock.review;

import app.drydock.git.DiffScope;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.RemoteSnapshotCache.Expect;
import app.drydock.review.RemoteSnapshotCache.Lease;
import app.drydock.review.RemoteSnapshotCache.PutResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteSnapshotCacheTest {
    private static final long MIB = 1L << 20;

    /** The fingerprint encodes the size the test sizer reports, so sizes are exact without real allocations. */
    private static ReviewDataSnapshot snap(String scope, long bytes) {
        return new ReviewDataSnapshot(scope, ReviewDataSource.Backend.REMOTE, "co", 1, DiffScope.BASE,
                new ReviewBase("main", ReviewBase.Origin.PULL_REQUEST), "b", "h", "b", "fp" + bytes,
                new UnifiedDiff(List.of()), Map.of(), Instant.EPOCH);
    }

    private static RemoteSnapshotCache cache() {
        return new RemoteSnapshotCache(s -> Long.parseLong(s.fingerprint().substring(2)));
    }

    private static boolean present(RemoteSnapshotCache c, String session, String scope) {
        return c.contains(session, scope);
    }

    private static Expect expect(long bytes) {
        return new Expect(1, "co", "main", "fp" + bytes, true);
    }

    @Test
    void sessionBytesExactStoresAndPlusOneEvictsOnlyUnleased() {
        var c = cache();
        assertEquals(PutResult.STORED, c.put("s", snap("a", 64 * MIB)));
        assertEquals(PutResult.STORED, c.put("s", snap("b", 64 * MIB)));
        assertEquals(128 * MIB, c.totalBytes());
        assertEquals(PutResult.STORED, c.put("s", snap("c", 1)));
        assertFalse(present(c, "s", "a"), "LRU a evicted for the count limit");
        assertEquals(PutResult.TOO_LARGE, c.put("s", snap("big", 128 * MIB + 1)));
        assertEquals(PutResult.STORED, c.put("t", snap("exact", 128 * MIB)));
    }

    @Test
    void leasedEntryIsNeverEvicted() {
        var c = cache();
        c.put("s", snap("a", 100 * MIB));
        c.put("s", snap("b", 1));
        Lease held = c.lease("s", "a", expect(100 * MIB)).orElseThrow();
        assertEquals(PutResult.STORED, c.put("s", snap("c", 20 * MIB)), "b is unleased and evicted");
        assertEquals(PutResult.NO_CAPACITY, c.put("s", snap("d", 29 * MIB)), "plus-one over 128 MiB with a leased 100 MiB");
        assertEquals(120 * MIB, c.totalBytes(), "refusal evicted nothing");
        assertTrue(present(c, "s", "a"));
        held.close();
        assertEquals(PutResult.STORED, c.put("s", snap("d", 29 * MIB)), "released entries are evictable again");
    }

    @Test
    void aggregateExactStoresAndPlusOneEvictsOtherSessionsLru() {
        var c = cache();
        for (String s : List.of("s1", "s2", "s3")) {
            c.put(s, snap("x", 128 * MIB));
        }
        assertEquals(PutResult.STORED, c.put("s4", snap("x", 128 * MIB)));
        assertEquals(512 * MIB, c.totalBytes());
        assertEquals(PutResult.STORED, c.put("s5", snap("y", 1)));
        assertFalse(present(c, "s1", "x"), "oldest session entry evicted for the aggregate");
        List<Lease> held = new ArrayList<>();
        for (String s : List.of("s2", "s3", "s4")) {
            held.add(c.lease(s, "x", expect(128 * MIB)).orElseThrow());
        }
        assertEquals(PutResult.STORED, c.put("s6", snap("x", 128 * MIB)), "unleased s5 evicted to fit exactly");
        assertEquals(512 * MIB, c.totalBytes());
        held.add(c.lease("s6", "x", expect(128 * MIB)).orElseThrow());
        assertEquals(PutResult.NO_CAPACITY, c.put("s7", snap("x", 1)), "plus-one: everything left is leased");
        held.forEach(Lease::close);
    }

    @Test
    void realSizerCountsSourceBytes() {
        var c = new RemoteSnapshotCache();
        var s = new ReviewDataSnapshot("a", ReviewDataSource.Backend.REMOTE, "co", 1, DiffScope.BASE,
                new ReviewBase("main", ReviewBase.Origin.PULL_REQUEST), "b", "h", "b", "fp",
                new UnifiedDiff(List.of()), Map.of("p", new ReviewDataSnapshot.SourceFile(
                        new ReviewDataSnapshot.Content.Absent(), new ReviewDataSnapshot.Content.Text(new byte[100]))),
                Instant.EPOCH);
        assertEquals(PutResult.STORED, c.put("s", s));
        assertEquals(101, c.totalBytes());
    }

    private static RemoteSnapshotCache.Lease leased(RemoteSnapshotCache c) {
        c.put("s", snap("a", 10));
        return c.lease("s", "a", expect(10)).orElseThrow();
    }

    @Test
    void leaseRefusesAnyMismatchedExpectation() {
        var c = cache();
        c.put("s", snap("a", 10));
        assertTrue(c.lease("s", "a", expect(10)).isPresent());
        assertTrue(c.lease("s", "a", new Expect(2, "co", "main", "fp10", true)).isEmpty());
        assertTrue(c.lease("s", "a", new Expect(1, "other", "main", "fp10", true)).isEmpty());
        assertTrue(c.lease("s", "a", new Expect(1, "co", "dev", "fp10", true)).isEmpty());
        assertTrue(c.lease("s", "a", new Expect(1, "co", "main", "fp11", true)).isEmpty());
        assertTrue(c.lease("s", "a", new Expect(1, "co", "main", "fp10", false)).isEmpty());
        assertTrue(c.lease("t", "a", expect(10)).isEmpty());
    }

    @Test
    void eachTriggerDropsUnleasedEntries() {
        List<java.util.function.Consumer<RemoteSnapshotCache>> triggers = List.of(
                c -> c.invalidateGeneration("s", 2), c -> c.invalidateCheckout("s", "other"),
                c -> c.invalidateRef("s", "dev"), c -> c.invalidateContent("s", "fp11"),
                c -> c.revokeScope("s", "a"), c -> c.removeSession("s"));
        for (var trigger : triggers) {
            var c = cache();
            c.put("s", snap("a", 10));
            trigger.accept(c);
            assertFalse(present(c, "s", "a"));
            assertEquals(0, c.totalBytes());
        }
    }

    @Test
    void matchingStateInvalidatesNothingAndOtherSessionsAndScopesSurvive() {
        var c = cache();
        c.put("s", snap("a", 10));
        c.put("s", snap("b", 10));
        c.put("t", snap("a", 10));
        c.invalidateGeneration("s", 1);
        c.invalidateCheckout("s", "co");
        c.invalidateRef("s", "main");
        c.invalidateContent("s", "fp10");
        c.revokeScope("s", "b");
        assertTrue(present(c, "s", "a"));
        assertFalse(present(c, "s", "b"));
        assertTrue(present(c, "t", "a"));
    }

    @Test
    void leasedEntryInvalidatedIsRetainedStaleNeverFreshAndDroppedOnLastRelease() {
        var c = cache();
        var first = leased(c);
        var second = c.lease("s", "a", expect(10)).orElseThrow();
        c.invalidateContent("s", "fp11");
        assertTrue(first.stale() && second.stale());
        assertEquals(10, c.totalBytes(), "retained while leased");
        assertEquals("fp10", first.snapshot().fingerprint(), "retained view still readable");
        assertTrue(c.lease("s", "a", expect(10)).isEmpty(), "stale is never served as fresh");
        first.close();
        first.close();
        assertTrue(present(c, "s", "a"), "one holder remains; double close does not release twice");
        second.close();
        assertFalse(present(c, "s", "a"));
        assertEquals(0, c.totalBytes());
    }

    @Test
    void removeSessionKeepsLeasedEntryStaleUntilReleased() {
        var c = cache();
        var l = leased(c);
        c.removeSession("s");
        assertTrue(l.stale());
        l.close();
        assertEquals(0, c.totalBytes());
    }
}
