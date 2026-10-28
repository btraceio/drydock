package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The tab-title, icon and header-action paths all resolve a session by id
 * on every paint or update tick, so the lookup must be O(1) and the index
 * must mirror exactly the current catalog: no rebuild while the catalog is
 * unchanged, one rebuild when it changes, and nothing left over from a
 * catalog a session was removed from. Pure class, no IDE boot.
 */
class CatalogSessionIndexTest {

    private static ManagedAgentSession session(ManagedSessionId id, String name) {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        return new ManagedAgentSession(
                id, RepositoryId.newId(), name,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/wt/" + name), Optional.empty(), false),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    @Test
    void aKnownIdIsFoundAndAnUnknownOneIsNot() {
        CatalogSessionIndex index = new CatalogSessionIndex();
        ManagedAgentSession first = session(ManagedSessionId.newId(), "first");
        ManagedAgentSession second = session(ManagedSessionId.newId(), "second");
        List<ManagedAgentSession> catalog = List.of(first, second);

        assertSame(first, index.find(catalog, first.id()));
        assertSame(second, index.find(catalog, second.id()));
        assertNull(index.find(catalog, ManagedSessionId.newId()));
        assertEquals(1, index.rebuilds());
        assertEquals(2, index.size());
    }

    @Test
    void theSameCatalogInstanceNeverRebuildsAcrossManyLookups() {
        CatalogSessionIndex index = new CatalogSessionIndex();
        List<ManagedAgentSession> catalog = List.of(
                session(ManagedSessionId.newId(), "a"),
                session(ManagedSessionId.newId(), "b"));
        index.find(catalog, unknownId());

        int rebuilds = index.rebuilds();
        for (int i = 0; i < 1000; i++) {
            index.find(catalog, catalog.get(i % catalog.size()).id());
        }
        assertEquals(rebuilds, index.rebuilds(), "the unchanged list must not rebuild");
        assertEquals(catalog.size(), index.size());
    }

    @Test
    void aNewCatalogInstanceRebuildsOnceAndDropsRemovedSessions() {
        CatalogSessionIndex index = new CatalogSessionIndex();
        ManagedAgentSession kept = session(ManagedSessionId.newId(), "kept");
        ManagedAgentSession removed = session(ManagedSessionId.newId(), "removed");
        List<ManagedAgentSession> oldCatalog = List.of(kept, removed);
        index.find(oldCatalog, kept.id());

        // A state write swaps in a new list instance with the removed entry gone.
        ManagedAgentSession added = session(ManagedSessionId.newId(), "added");
        List<ManagedAgentSession> newCatalog = List.of(kept, added);
        int before = index.rebuilds();

        assertSame(kept, index.find(newCatalog, kept.id()));
        assertEquals(before + 1, index.rebuilds(), "a new list instance rebuilds exactly once");
        assertNull(index.find(newCatalog, removed.id()), "a removed session must not be found");
        assertSame(added, index.find(newCatalog, added.id()));
        // And the new instance is itself stable.
        int afterRebuild = index.rebuilds();
        index.find(newCatalog, added.id());
        assertEquals(afterRebuild, index.rebuilds());
        assertEquals(newCatalog.size(), index.size());
    }

    @Test
    void sizeAlwaysMirrorsTheCurrentCatalog() {
        CatalogSessionIndex index = new CatalogSessionIndex();
        // A distinct empty-list instance (List.of() is JDK-canonicalized to
        // the same object as the index's initial snapshot, so it would not
        // count as a new catalog); an ArrayList is always a fresh instance.
        List<ManagedAgentSession> empty = new ArrayList<>();
        assertNull(index.find(empty, unknownId()));
        assertEquals(0, index.size());
        assertEquals(1, index.rebuilds());

        List<ManagedAgentSession> one = List.of(session(ManagedSessionId.newId(), "solo"));
        index.find(one, unknownId());
        assertEquals(1, index.size());

        List<ManagedAgentSession> three = List.of(
                session(ManagedSessionId.newId(), "x"),
                session(ManagedSessionId.newId(), "y"),
                session(ManagedSessionId.newId(), "z"));
        index.find(three, unknownId());
        assertEquals(3, index.size());
        assertEquals(3, index.rebuilds());
    }

    /** A fresh id that is in no catalog, for find calls that only test the rebuild side effect. */
    private static ManagedSessionId unknownId() {
        return ManagedSessionId.newId();
    }
}
