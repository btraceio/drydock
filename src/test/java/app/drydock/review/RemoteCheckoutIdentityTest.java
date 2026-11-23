package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.review.RemoteCheckoutIdentity.Evidence;
import app.drydock.review.RemoteCheckoutIdentity.Owner;
import app.drydock.review.RemoteCheckoutIdentity.Resolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RemoteCheckoutIdentityTest {
    private static Owner owner(String box, String main, String origin) {
        var binding = new WorkspaceBoxBinding(box, main);
        return new Owner(RepositoryId.newId(), ManagedSessionId.newId(), binding,
                binding.sshRemote().host(), Optional.ofNullable(origin));
    }

    private static Path token(Owner o, String checkout) {
        return new SshRemote(o.sshAlias(), checkout).placeholderRoot();
    }

    private static Evidence evidence(String main, String checkout, String stat, String origin) {
        return new Evidence(checkout, main + "/.git", stat, "c-" + stat,
                "worktree " + main + "\nHEAD abc\n\nworktree " + checkout + "\nHEAD def\n",
                Optional.ofNullable(origin));
    }

    @Test
    void collidingPathSuffixesStayBoundToTheirOwnRepositories() {
        var ids = new RemoteCheckoutIdentity();
        var a = owner("box-a", "/work/app", "git@github.com:DataDog/a.git");
        var b = owner("box-b", "/work/app", "git@github.com:DataDog/b.git");
        var ta = token(a, "/work/app/wt");
        var tb = token(b, "/work/app/wt");
        assertNotEquals(ta, tb);
        ids.claim(ta, a);
        ids.claim(tb, b);
        // a foreign main checkout presented before any pin exists is refused on membership alone
        assertInstanceOf(Resolution.Unavailable.class,
                ids.verify(ta, evidence("/work/other", "/work/app/wt", "1", null)));
        var ra = assertInstanceOf(Resolution.Verified.class,
                ids.verify(ta, evidence("/work/app", "/work/app/wt", "1", "https://github.com/datadog/a")));
        assertEquals(a.repository(), ra.owner().repository());
        assertInstanceOf(Resolution.Unavailable.class,
                ids.verify(tb, evidence("/work/app", "/work/app/wt", "9", "git@github.com:DataDog/a.git")));
    }

    @Test
    void reconnectAndRepeatedVerificationPreserveIdsAndPin() {
        var ids = new RemoteCheckoutIdentity();
        var o = owner("box-a", "/work/app", null);
        var t = token(o, "/work/app");
        ids.claim(t, o);
        var ev = evidence("/work/app", "/work/app", "1", null);
        assertInstanceOf(Resolution.Verified.class, ids.verify(t, ev));
        ids.claim(t, o); // reconnect / port / base / head change: same ids re-claim
        var again = assertInstanceOf(Resolution.Verified.class, ids.verify(t, ev));
        assertEquals(o.repository(), again.owner().repository());
        assertEquals(o.session(), again.owner().session());
    }

    @Test
    void changedRootOrGitDirIdentityIsUnavailableUntilRevalidated() {
        var ids = new RemoteCheckoutIdentity();
        var o = owner("box-a", "/work/app", null);
        var t = token(o, "/work/app");
        ids.claim(t, o);
        assertInstanceOf(Resolution.Verified.class, ids.verify(t, evidence("/work/app", "/work/app", "1", null)));
        var moved = evidence("/work/app", "/work/app", "2", null);
        assertInstanceOf(Resolution.Unavailable.class, ids.verify(t, moved));
        assertInstanceOf(Resolution.Unavailable.class, ids.verify(t, moved));
        assertInstanceOf(Resolution.Verified.class, ids.revalidate(t, moved));
        assertInstanceOf(Resolution.Verified.class, ids.verify(t, moved));
    }

    @Test
    void incompatibleOriginAndNonMemberCheckoutAreUnavailable() {
        var ids = new RemoteCheckoutIdentity();
        var o = owner("box-a", "/work/app", "git@github.com:DataDog/a.git");
        var t = token(o, "/work/app");
        ids.claim(t, o);
        assertInstanceOf(Resolution.Unavailable.class,
                ids.verify(t, evidence("/work/app", "/work/app", "1", "git@github.com:DataDog/z.git")));
        var stranger = new Evidence("/work/app", "/g", "1", "1", "worktree /work/app\n", Optional.empty());
        assertInstanceOf(Resolution.Verified.class, ids.verify(t, stranger));
        var notListed = new Evidence("/work/app", "/g", "1", "1", "worktree /work/zzz\n", Optional.empty());
        assertInstanceOf(Resolution.Unavailable.class, ids.revalidate(t, notListed));
    }

    @Test
    void duplicateLiveClaimIsRefusedUntilReleased() {
        var ids = new RemoteCheckoutIdentity();
        var first = owner("box-a", "/work/app", null);
        var t = token(first, "/work/app");
        ids.claim(t, first);
        var rival = new Owner(RepositoryId.newId(), ManagedSessionId.newId(), first.box(),
                first.sshAlias(), Optional.empty());
        assertThrows(IllegalStateException.class, () -> ids.claim(t, rival));
        assertEquals(first, ids.ownerOf(t).orElseThrow());
        ids.release(t, rival.session()); // a foreign session cannot release it
        assertEquals(first, ids.ownerOf(t).orElseThrow());
        ids.release(t, first.session());
        ids.claim(t, rival);
        assertEquals(rival, ids.ownerOf(t).orElseThrow());
    }

    @Test
    void nonTokenPathsAndOversizedWorktreeListsAreRefused() {
        var ids = new RemoteCheckoutIdentity();
        var o = owner("box-a", "/work/app", null);
        assertThrows(IllegalArgumentException.class, () -> ids.claim(Path.of("/work/app"), o));
        var big = new StringBuilder("worktree /work/app\n");
        for (int i = 0; i < RemoteCheckoutIdentity.MAX_WORKTREES; i++) big.append("worktree /w").append(i).append('\n');
        var t = token(o, "/work/app");
        ids.claim(t, o);
        assertInstanceOf(Resolution.Unavailable.class,
                ids.verify(t, new Evidence("/work/app", "/g", "1", "1", big.toString(), Optional.empty())));
    }
}
