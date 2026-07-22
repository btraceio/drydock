package app.drydock.git;

import app.drydock.git.WorktreeService.Worktree;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing rules the create-worktree picker depends on, as pure data:
 * occupancy, shadowing, remote-name splitting, and the text lookup that
 * decides whether the modal creates a branch or checks one out.
 */
class BranchCatalogTest {

    private static Worktree worktree(String path, String branch) {
        return new Worktree(Path.of(path), Optional.of(branch), false, false, false, false);
    }

    @Test
    void occupiedBranchCarriesItsWorktreePath() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.local("main"), BranchRef.local("idle")), List.of()),
                List.of(worktree("/src/olifer", "main")));

        BranchRef main = catalog.lookup("main").orElseThrow();
        assertEquals(Optional.of(Path.of("/src/olifer")), main.checkedOutAt());
        assertFalse(main.available());
        assertTrue(catalog.lookup("idle").orElseThrow().available());
    }

    @Test
    void aPrunableOrLockedWorktreeMarksItsBranchStale() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.local("ghost"), BranchRef.local("held")), List.of()),
                List.of(new Worktree(Path.of("/gone"), Optional.of("ghost"), false, false, true, false),
                        new Worktree(Path.of("/held"), Optional.of("held"), false, false, false, true)));

        assertTrue(catalog.lookup("ghost").orElseThrow().stale());
        assertTrue(catalog.lookup("held").orElseThrow().stale());
    }

    @Test
    void remoteBranchShadowedByASameNamedLocalOneIsDropped() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(
                        BranchRef.local("feature/x"),
                        BranchRef.remote("origin/feature/x"),
                        BranchRef.remote("origin/only-remote")), List.of("origin")),
                List.of());

        List<String> names = catalog.branches().stream().map(BranchRef::name).toList();
        assertEquals(List.of("feature/x", "origin/only-remote"), names);
    }

    @Test
    void localNameStripsTheLongestMatchingRemotePrefix() {
        // A remote may itself contain a slash: stripping the FIRST path
        // segment would yield "fork/feature/x" and create the wrong branch.
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.remote("team/fork/feature/x")),
                        List.of("origin", "team/fork")),
                List.of());

        assertEquals("feature/x", catalog.localName(BranchRef.remote("team/fork/feature/x")));
        assertEquals("feat/y", catalog.localName(BranchRef.local("feat/y")));
    }

    @Test
    void lookupPrefersTheLocalBranchOverASameNamedRemoteTrackingRef() {
        // A local branch may literally be named "origin/foo", which is
        // string-identical to origin's remote-tracking name for "foo".
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(
                        BranchRef.local("origin/foo"),
                        BranchRef.remote("origin/foo")), List.of("origin")),
                List.of());

        assertFalse(catalog.lookup("origin/foo").orElseThrow().remote());
    }

    @Test
    void lookupResolvesABareNameAgainstEachRemote() {
        BranchCatalog catalog = BranchCatalog.merge(
                new BranchListing(List.of(BranchRef.remote("origin/feature/x")), List.of("origin")),
                List.of());

        assertEquals("origin/feature/x", catalog.lookup("feature/x").orElseThrow().name());
        assertEquals("origin/feature/x", catalog.lookup("  origin/feature/x  ").orElseThrow().name());
        assertTrue(catalog.lookup("brand-new-branch").isEmpty());
        assertTrue(catalog.lookup("").isEmpty());
        assertTrue(catalog.lookup(null).isEmpty());
    }
}
