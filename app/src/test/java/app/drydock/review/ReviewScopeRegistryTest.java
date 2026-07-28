package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Minting identity, grants and revocation (Review MCP schema §0). The
 * idempotence tests are the load-bearing ones: the queue is reassembled on
 * every worktree/repository change, and a fresh handle per assembly would
 * orphan every finding, thread, draft and verdict keyed by {@code
 * (scopeId, …)}.
 */
class ReviewScopeRegistryTest {

    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();

    private static ReviewScope worktreeSpec(String head, Optional<ManagedSessionId> session) {
        return ReviewScopeRegistry.spec(ReviewScope.Kind.WORKTREE, Path.of("/repo"),
                Optional.of(Path.of("/wt/" + head)), "master", head, Optional.empty(), session);
    }

    @Test
    void mintingAssignsAnOpaqueIdInTheDocumentedShape() {
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.empty()));

        assertTrue(scope.id().startsWith("rs_"), scope.id());
        assertEquals(21, scope.id().length(), scope.id());
        assertEquals(Optional.of(scope), registry.byId(scope.id()));
    }

    @Test
    void mintingTheSameIdentityTwiceReusesTheHandle() {
        ReviewScope first = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        ReviewScope again = registry.mint(worktreeSpec("feat/a", Optional.empty()));

        assertEquals(first.id(), again.id());
        assertEquals(1, registry.scopes().size());
    }

    @Test
    void differentIdentitiesGetDifferentHandles() {
        ReviewScope a = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        ReviewScope b = registry.mint(worktreeSpec("feat/b", Optional.empty()));

        assertNotEquals(a.id(), b.id());
        assertEquals(2, registry.scopes().size());
    }

    @Test
    void remintingUpdatesTheStoredValueButKeepsTheId() {
        ReviewScope sessionless = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        ManagedSessionId session = ManagedSessionId.newId();

        ReviewScope bound = registry.mint(worktreeSpec("feat/a", Optional.of(session)));

        assertEquals(sessionless.id(), bound.id());
        assertEquals(Optional.of(session), registry.byId(bound.id()).orElseThrow().sessionId());
    }

    @Test
    void aBoundSessionMayAddressItsOwnScope() {
        ManagedSessionId session = ManagedSessionId.newId();
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.of(session)));

        assertTrue(registry.isAddressableBy(scope.id(), session));
        assertFalse(registry.isAddressableBy(scope.id(), ManagedSessionId.newId()));
    }

    @Test
    void aGrantLetsAnotherSessionReviewAWorktreeThatIsNotItsOwn() {
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        ManagedSessionId reviewer = ManagedSessionId.newId();

        assertFalse(registry.isAddressableBy(scope.id(), reviewer));
        registry.grant(scope.id(), reviewer);

        assertTrue(registry.isAddressableBy(scope.id(), reviewer));
        assertEquals(java.util.Set.of(reviewer), registry.grantsFor(scope.id()));
    }

    @Test
    void revokingAGrantLeavesTheBoundSessionAlone() {
        ManagedSessionId owner = ManagedSessionId.newId();
        ManagedSessionId reviewer = ManagedSessionId.newId();
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.of(owner)));
        registry.grant(scope.id(), reviewer);

        registry.revokeGrant(scope.id(), reviewer);

        assertFalse(registry.isAddressableBy(scope.id(), reviewer));
        assertTrue(registry.isAddressableBy(scope.id(), owner));
    }

    @Test
    void grantingAgainstAnUnknownScopeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.grant("rs_nope", ManagedSessionId.newId()));
    }

    @Test
    void revokingDropsTheHandleAndEveryGrantAgainstIt() {
        ManagedSessionId reviewer = ManagedSessionId.newId();
        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        registry.grant(scope.id(), reviewer);

        registry.revoke(scope.id());

        assertEquals(Optional.empty(), registry.byId(scope.id()));
        assertFalse(registry.isAddressableBy(scope.id(), reviewer));
        assertTrue(registry.scopes().isEmpty());
    }

    @Test
    void anItemThatComesBackAfterRevocationKeepsItsStableHandle() {
        ReviewScope first = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        registry.revoke(first.id());

        ReviewScope second = registry.mint(worktreeSpec("feat/a", Optional.empty()));

        assertEquals(first.id(), second.id());
    }

    @Test
    void registriesWithTheSamePersistedSecretMintTheSameHandle() {
        byte[] secret = new byte[32];
        ReviewScopeRegistry beforeRestart = new ReviewScopeRegistry(secret);
        ReviewScopeRegistry afterRestart = new ReviewScopeRegistry(secret);

        assertEquals(beforeRestart.mint(worktreeSpec("feat/a", Optional.empty())).id(),
                afterRestart.mint(worktreeSpec("feat/a", Optional.empty())).id());
    }

    @Test
    void listenersSeeMintUpdateGrantAndRevoke() {
        List<String> seen = new ArrayList<>();
        Runnable unsubscribe = registry.addChangeListener(seen::add);

        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        registry.mint(worktreeSpec("feat/a", Optional.of(ManagedSessionId.newId())));
        registry.grant(scope.id(), ManagedSessionId.newId());
        registry.revoke(scope.id());
        unsubscribe.run();
        registry.mint(worktreeSpec("feat/b", Optional.empty()));

        assertEquals(List.of(scope.id(), scope.id(), scope.id(), scope.id()), seen);
    }

    @Test
    void remintingAnUnchangedScopeDoesNotNotify() {
        List<String> seen = new ArrayList<>();
        registry.addChangeListener(seen::add);

        ReviewScope scope = registry.mint(worktreeSpec("feat/a", Optional.empty()));
        registry.mint(worktreeSpec("feat/a", Optional.empty()));

        assertEquals(List.of(scope.id()), seen);
    }
}
