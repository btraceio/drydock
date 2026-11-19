package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;
import app.drydock.domain.WorkspaceBoxBinding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Service-side authority for remote review scopes. A scope's
 * {@link SshRemote#placeholderRoot()} is an opaque identity token — never a
 * filesystem location; this class maps it to the catalog repository, managed
 * session, box and the checkout actually observed on the box. All remote
 * facts arrive as values (bounded {@code git worktree list --porcelain}
 * text, canonical path strings, stat identities); nothing here probes a disk
 * or spawns a process. Base/head/credentials/ports are not inputs, so they
 * cannot change identity.
 */
public final class RemoteCheckoutIdentity {
    static final int MAX_WORKTREES = 256;
    private static final String TOKEN_PREFIX = "/.drydock-remote";

    /** The catalog side of a claim; {@code sshAlias} is the connection alias, not a path. */
    public record Owner(RepositoryId repository, ManagedSessionId session, WorkspaceBoxBinding box,
                        String sshAlias, Optional<String> originCoordinate) { }

    /** Remote facts handed in as values; {@code worktreePorcelain} is the raw bounded command output. */
    public record Evidence(String canonicalCheckout, String commonGitDir, String rootStat, String commonDirStat,
                           String worktreePorcelain, Optional<String> originCoordinate) { }

    public record Fingerprint(String canonicalCheckout, String commonGitDir, String rootStat, String commonDirStat) { }

    public sealed interface Resolution {
        record Verified(Owner owner, Fingerprint fingerprint) implements Resolution { }

        record Unavailable(String reason) implements Resolution { }
    }

    private final Map<Path, Owner> claims = new HashMap<>();
    private final Map<Path, Fingerprint> pins = new HashMap<>();

    /** Registers a live claim; re-claiming by the same repository+session (reconnect) is idempotent. */
    public synchronized void claim(Path token, Owner owner) {
        if (!token.startsWith(TOKEN_PREFIX)) {
            throw new IllegalArgumentException("not a remote identity token: " + token);
        }
        Owner existing = claims.get(token);
        if (existing != null && !(existing.repository().equals(owner.repository())
                && existing.session().equals(owner.session()))) {
            throw new IllegalStateException("remote checkout already claimed by another session: " + token);
        }
        claims.put(token, owner);
    }

    public synchronized void release(Path token, ManagedSessionId session) {
        Owner existing = claims.get(token);
        if (existing != null && existing.session().equals(session)) {
            claims.remove(token);
            pins.remove(token);
        }
    }

    public synchronized Optional<Owner> ownerOf(Path token) {
        return Optional.ofNullable(claims.get(token));
    }

    /** Checks the evidence against the claim and the pin; the first verified fingerprint is pinned. */
    public synchronized Resolution verify(Path token, Evidence evidence) {
        return check(token, evidence, false);
    }

    /** Explicit revalidation: like {@link #verify} but a changed fingerprint replaces the pin. */
    public synchronized Resolution revalidate(Path token, Evidence evidence) {
        return check(token, evidence, true);
    }

    private Resolution check(Path token, Evidence ev, boolean repin) {
        Owner owner = claims.get(token);
        if (owner == null) {
            return new Resolution.Unavailable("unclaimed token");
        }
        if (blank(ev.canonicalCheckout()) || blank(ev.commonGitDir())
                || blank(ev.rootStat()) || blank(ev.commonDirStat())) {
            return new Resolution.Unavailable("incomplete checkout evidence");
        }
        if (!new SshRemote(owner.sshAlias(), ev.canonicalCheckout()).placeholderRoot().equals(token)) {
            return new Resolution.Unavailable("token does not name the observed checkout");
        }
        List<String> worktrees = worktreePaths(ev.worktreePorcelain());
        if (worktrees.isEmpty() || !worktrees.get(0).equals(owner.box().boxWorkingPath())
                || !worktrees.contains(ev.canonicalCheckout())) {
            return new Resolution.Unavailable("checkout is not a worktree of the registered repository");
        }
        if (owner.originCoordinate().isPresent() && ev.originCoordinate().isPresent()
                && !normalize(owner.originCoordinate().get()).equals(normalize(ev.originCoordinate().get()))) {
            return new Resolution.Unavailable("origin differs from the registered repository");
        }
        var seen = new Fingerprint(ev.canonicalCheckout(), ev.commonGitDir(), ev.rootStat(), ev.commonDirStat());
        Fingerprint pinned = pins.get(token);
        if (pinned == null || repin) {
            pins.put(token, seen);
        } else if (!pinned.equals(seen)) {
            return new Resolution.Unavailable("checkout identity changed; explicit revalidation required");
        }
        return new Resolution.Verified(owner, seen);
    }

    /** Paths of worktree records, main checkout first; empty when over the bound. */
    static List<String> worktreePaths(String porcelain) {
        var paths = new ArrayList<String>();
        for (String line : porcelain.split("\n", -1)) {
            if (line.startsWith("worktree ")) {
                if (paths.size() >= MAX_WORKTREES) {
                    return List.of();
                }
                paths.add(line.substring("worktree ".length()));
            }
        }
        return paths;
    }

    static String normalize(String coordinate) {
        String s = coordinate.strip().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z+]+://", "").replaceFirst("^[^@/]+@", "").replace(':', '/');
        s = s.replaceFirst("\\.git$", "").replaceFirst("/+$", "");
        return s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
