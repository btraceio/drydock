package app.drydock.review;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The one shared, ephemeral cache of captured review snapshots for the MCP
 * tools and the workbench alike. Memory only: it is not a writer of
 * persistent state. Bounds: two snapshots and 128 MiB per session, 512 MiB
 * aggregate. A snapshot a reader holds (a {@link Lease}) is never evicted.
 * Invalidation (connection generation, checkout identity, ref, content, scope
 * revocation, session removal) drops unleased entries at once; a leased entry
 * is retained marked stale, never served as fresh, and dropped on its last
 * release.
 */
public final class RemoteSnapshotCache {
    public static final int MAX_PER_SESSION = 2;
    public static final long MAX_SESSION_BYTES = 128L << 20;
    public static final long MAX_AGGREGATE_BYTES = 512L << 20;

    public enum PutResult { STORED, TOO_LARGE, NO_CAPACITY }

    /** What a reader requires of a cached snapshot; any mismatch means it is not served. */
    public record Expect(long generation, String checkoutId, String ref, String fingerprint, boolean scopeLive) {}

    /** A reader's hold on one snapshot. Close it; closing is idempotent. */
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean closed;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        public ReviewDataSnapshot snapshot() {
            return entry.snapshot;
        }

        /** True once the snapshot was invalidated while held: usable as a retained view, never as fresh data. */
        public boolean stale() {
            synchronized (RemoteSnapshotCache.this) {
                return entry.stale;
            }
        }

        @Override
        public void close() {
            synchronized (RemoteSnapshotCache.this) {
                if (closed) {
                    return;
                }
                closed = true;
                if (--entry.leases == 0 && entry.stale) {
                    remove(entry);
                }
            }
        }
    }

    private static final class Entry {
        final ReviewDataSnapshot snapshot;
        final long bytes;
        long tick;
        int leases;
        boolean stale;

        Entry(ReviewDataSnapshot snapshot, long bytes, long tick) {
            this.snapshot = snapshot;
            this.bytes = bytes;
            this.tick = tick;
        }
    }

    private final ToLongFunction<ReviewDataSnapshot> sizer;
    private final Map<String, List<Entry>> sessions = new HashMap<>();
    private long total;
    private long tick;

    public RemoteSnapshotCache() {
        this(RemoteSnapshotCache::measure);
    }

    RemoteSnapshotCache(ToLongFunction<ReviewDataSnapshot> sizer) {
        this.sizer = sizer;
    }

    /** Retained bytes of a snapshot: source bytes plus diff text. */
    static long measure(ReviewDataSnapshot s) {
        long n = 0;
        for (var e : s.sources().entrySet()) {
            n += e.getKey().length();
            for (var side : List.of(e.getValue().left(), e.getValue().right())) {
                if (side instanceof ReviewDataSnapshot.Content.Text t) {
                    n += t.bytes().length;
                }
            }
        }
        for (var f : s.diff().files()) {
            n += f.path().length();
            for (var h : f.hunks()) {
                n += h.header().length();
                for (var l : h.lines()) {
                    n += l.text().length();
                }
            }
        }
        return n;
    }

    /** Store a snapshot, evicting least-recently-used unleased entries; refuses rather than evict a leased one. */
    public synchronized PutResult put(String session, ReviewDataSnapshot snapshot) {
        Objects.requireNonNull(session, "session");
        long bytes = sizer.applyAsLong(snapshot);
        if (bytes > MAX_SESSION_BYTES || bytes > MAX_AGGREGATE_BYTES) {
            return PutResult.TOO_LARGE;
        }
        List<Entry> mine = sessions.getOrDefault(session, List.of());
        List<Entry> doomed = new ArrayList<>();
        for (Entry e : mine) {
            if (e.leases == 0 && e.snapshot.scopeId().equals(snapshot.scopeId())) {
                doomed.add(e);
            }
        }
        List<Entry> kept = new ArrayList<>(mine);
        kept.removeAll(doomed);
        long sessionBytes = kept.stream().mapToLong(e -> e.bytes).sum();
        long aggregate = total - doomed.stream().mapToLong(e -> e.bytes).sum();
        while (kept.size() + 1 > MAX_PER_SESSION || sessionBytes + bytes > MAX_SESSION_BYTES) {
            Entry lru = lruUnleased(kept);
            if (lru == null) {
                return PutResult.NO_CAPACITY;
            }
            doomed.add(lru);
            kept.remove(lru);
            sessionBytes -= lru.bytes;
            aggregate -= lru.bytes;
        }
        while (aggregate + bytes > MAX_AGGREGATE_BYTES) {
            Entry lru = null;
            for (var s : sessions.values()) {
                Entry c = lruUnleased(s.stream().filter(e -> !doomed.contains(e)).toList());
                if (c != null && (lru == null || c.tick < lru.tick)) {
                    lru = c;
                }
            }
            if (lru == null) {
                return PutResult.NO_CAPACITY;
            }
            doomed.add(lru);
            aggregate -= lru.bytes;
        }
        for (Entry e : doomed) {
            remove(e);
        }
        Entry fresh = new Entry(snapshot, bytes, ++tick);
        sessions.computeIfAbsent(session, k -> new ArrayList<>()).add(fresh);
        total += bytes;
        return PutResult.STORED;
    }

    /** Lease the fresh snapshot of a scope if it still satisfies the expectation; empty means refetch. */
    public synchronized Optional<Lease> lease(String session, String scopeId, Expect expect) {
        if (!expect.scopeLive()) {
            return Optional.empty();
        }
        for (Entry e : sessions.getOrDefault(session, List.of())) {
            var s = e.snapshot;
            if (!e.stale && s.scopeId().equals(scopeId) && s.generation() == expect.generation()
                    && s.checkoutId().equals(expect.checkoutId()) && s.base().ref().equals(expect.ref())
                    && s.fingerprint().equals(expect.fingerprint())) {
                e.leases++;
                e.tick = ++tick;
                return Optional.of(new Lease(e));
            }
        }
        return Optional.empty();
    }

    /** The connection moved to another generation: older snapshots are invalid. */
    public synchronized void invalidateGeneration(String session, long currentGeneration) {
        invalidate(session, e -> e.snapshot.generation() != currentGeneration);
    }

    /** The checkout behind the session changed identity. */
    public synchronized void invalidateCheckout(String session, String currentCheckoutId) {
        invalidate(session, e -> !e.snapshot.checkoutId().equals(currentCheckoutId));
    }

    /** The review base ref changed. */
    public synchronized void invalidateRef(String session, String currentRef) {
        invalidate(session, e -> !e.snapshot.base().ref().equals(currentRef));
    }

    /** The working content changed. */
    public synchronized void invalidateContent(String session, String currentFingerprint) {
        invalidate(session, e -> !e.snapshot.fingerprint().equals(currentFingerprint));
    }

    /** The scope's grant was revoked. */
    public synchronized void revokeScope(String session, String scopeId) {
        invalidate(session, e -> e.snapshot.scopeId().equals(scopeId));
    }

    /** The session is gone: every entry is invalid. */
    public synchronized void removeSession(String session) {
        invalidate(session, e -> true);
    }

    /** Unleased matches are dropped now; leased matches stay, marked stale, until their last release. */
    private void invalidate(String session, Predicate<Entry> doomed) {
        for (Entry e : new ArrayList<>(sessions.getOrDefault(session, List.of()))) {
            if (doomed.test(e)) {
                if (e.leases == 0) {
                    remove(e);
                } else {
                    e.stale = true;
                }
            }
        }
    }

    /** Test view: whether an entry for the scope is retained, fresh or stale. */
    synchronized boolean contains(String session, String scopeId) {
        return sessions.getOrDefault(session, List.of()).stream().anyMatch(e -> e.snapshot.scopeId().equals(scopeId));
    }

    /** Test/diagnostic view: total retained bytes. */
    public synchronized long totalBytes() {
        return total;
    }

    private static Entry lruUnleased(List<Entry> list) {
        Entry best = null;
        for (Entry e : list) {
            if (e.leases == 0 && (best == null || e.tick < best.tick)) {
                best = e;
            }
        }
        return best;
    }

    private void remove(Entry e) {
        for (Iterator<List<Entry>> it = sessions.values().iterator(); it.hasNext(); ) {
            List<Entry> l = it.next();
            if (l.remove(e)) {
                total -= e.bytes;
                if (l.isEmpty()) {
                    it.remove();
                }
                return;
            }
        }
    }
}
