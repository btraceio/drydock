package app.drydock.review;

import app.drydock.domain.ManagedSessionId;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Mints, resolves and revokes {@link ReviewScope} handles, and records which
 * sessions may address which scopes (Review MCP schema §0).
 *
 * <p>{@code McpSessionRegistry} binds an MCP caller to one session; the
 * Review destination spans repositories. This registry is the bridge: an
 * agent may touch the scope its own session is bound to, <em>plus</em> any
 * scope a human explicitly granted it by pressing "Run review". That grant
 * is what lets a session's agent review a worktree that is not its own.</p>
 *
 * <p>Minting is <strong>idempotent on identity</strong> -- kind, repository
 * root, worktree, base, head and PR number. The queue is reassembled every
 * time repositories or worktrees change, and a fresh id per assembly would
 * break everything keyed by {@code (scopeId, id)}: findings, threads,
 * drafts and verdicts would all be orphaned by a background rescan. The id
 * a scope was first minted with therefore survives until it is explicitly
 * revoked (the item left the queue).</p>
 *
 * <p>Thread-safe: the queue service assembles off the FX thread, the MCP
 * router resolves on its own executor, and the UI reads on the FX
 * thread.</p>
 */
public final class ReviewScopeRegistry {

    /** Crockford base32 keeps schema-compatible opaque, case-insensitive ids. */
    private static final char[] BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Identity of a scope, independent of its minted id and of any bound session. */
    private record Identity(ReviewScope.Kind kind, Path repoRoot, Optional<Path> worktree,
                            String base, String head, Optional<Integer> pr) {
        static Identity of(ReviewScope scope) {
            return new Identity(scope.kind(), normalized(scope.repoRoot()),
                    scope.worktree().map(Identity::normalized), scope.base(),
                    scope.head(), scope.pr().map(ReviewScope.PullRequestRef::number));
        }

        private static Path normalized(Path path) {
            return path.toAbsolutePath().normalize();
        }
    }

    private final byte[] scopeIdSecret;
    private final Map<String, ReviewScope> byId = new ConcurrentHashMap<>();
    private final Map<Identity, String> idByIdentity = new ConcurrentHashMap<>();
    private final Map<String, Set<ManagedSessionId>> grants = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Creates a registry with a fresh secret for callers that do not persist review data. */
    public ReviewScopeRegistry() {
        this(newSecret());
    }

    /**
     * Creates a registry whose scope handles are deterministic for this
     * secret. Application startup obtains the secret from {@link
     * AnnotationStore}, so a persisted annotation remains addressable after
     * a restart without exposing repository paths in the handle.
     */
    public ReviewScopeRegistry(byte[] scopeIdSecret) {
        Objects.requireNonNull(scopeIdSecret, "scopeIdSecret");
        if (scopeIdSecret.length < 16) {
            throw new IllegalArgumentException("scopeIdSecret must contain at least 128 bits");
        }
        this.scopeIdSecret = scopeIdSecret.clone();
    }

    /**
     * Mints a handle for {@code spec}, or returns the existing handle when a
     * scope with the same identity is already registered. The {@code id}
     * carried by {@code spec} is ignored -- callers describe what they want
     * reviewed and this registry owns identity (see the class note on
     * idempotence).
     *
     * <p>An existing handle is <em>updated</em> in place from {@code spec}:
     * a rescan that discovers the scope now has a bound session must not
     * leave the registry serving the sessionless value it minted first.</p>
     */
    public ReviewScope mint(ReviewScope spec) {
        Objects.requireNonNull(spec, "spec");
        Identity identity = Identity.of(spec);
        String id = idByIdentity.computeIfAbsent(identity, this::newId);
        ReviewScope scope = new ReviewScope(id, spec.kind(), spec.repoRoot(), spec.worktree(),
                spec.base(), spec.head(), spec.pr(), spec.sessionId());
        ReviewScope previous = byId.put(id, scope);
        if (!scope.equals(previous)) {
            notifyChanged(id);
        }
        return scope;
    }

    /**
     * Builds a spec with a placeholder id. {@link #mint} replaces it, so no
     * caller has to invent one.
     */
    public static ReviewScope spec(ReviewScope.Kind kind, Path repoRoot, Optional<Path> worktree,
                                   String base, String head, Optional<ReviewScope.PullRequestRef> pr,
                                   Optional<ManagedSessionId> sessionId) {
        return new ReviewScope("rs_pending", kind, repoRoot, worktree, base, head, pr, sessionId);
    }

    public Optional<ReviewScope> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** Every live scope, in mint order. */
    public List<ReviewScope> scopes() {
        List<ReviewScope> live = new ArrayList<>();
        for (Map.Entry<Identity, String> entry : new LinkedHashMap<>(idByIdentity).entrySet()) {
            ReviewScope scope = byId.get(entry.getValue());
            if (scope != null) {
                live.add(scope);
            }
        }
        return List.copyOf(live);
    }

    /**
     * Drops the handle and every grant against it -- the item left the
     * queue. Findings keyed by this id are not touched here: the store owns
     * their lifetime, and revoking a handle for an item that later comes
     * back remains addressable under its stable identity-derived id. The
     * store owns the lifetime of its review data, while this registry owns
     * whether an agent may currently address it.
     */
    public void revoke(String id) {
        if (id == null) {
            return;
        }
        ReviewScope removed = byId.remove(id);
        grants.remove(id);
        idByIdentity.values().removeIf(id::equals);
        if (removed != null) {
            notifyChanged(id);
        }
    }

    /** Lets {@code sessionId}'s agent address {@code scopeId} ("Run review" on someone else's worktree). */
    public void grant(String scopeId, ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (!byId.containsKey(scopeId)) {
            throw new IllegalArgumentException("No such review scope: " + scopeId);
        }
        grants.computeIfAbsent(scopeId, key -> ConcurrentHashMap.newKeySet()).add(sessionId);
        notifyChanged(scopeId);
    }

    /** Withdraws a grant made by {@link #grant}; the scope's own bound session is unaffected. */
    public void revokeGrant(String scopeId, ManagedSessionId sessionId) {
        Set<ManagedSessionId> granted = grants.get(scopeId);
        if (granted != null && granted.remove(sessionId)) {
            notifyChanged(scopeId);
        }
    }

    /**
     * Whether {@code sessionId} may address {@code scopeId}: either the
     * scope is bound to that session, or a human granted it. An unknown
     * scope is never addressable -- the MCP router turns that into a tool
     * error rather than a silent empty result.
     */
    public boolean isAddressableBy(String scopeId, ManagedSessionId sessionId) {
        ReviewScope scope = byId.get(scopeId);
        if (scope == null || sessionId == null) {
            return false;
        }
        if (scope.sessionId().filter(sessionId::equals).isPresent()) {
            return true;
        }
        return grants.getOrDefault(scopeId, Set.of()).contains(sessionId);
    }

    /** Sessions explicitly granted {@code scopeId} (excluding its own bound session). */
    public Set<ManagedSessionId> grantsFor(String scopeId) {
        return Set.copyOf(grants.getOrDefault(scopeId, Set.of()));
    }

    /**
     * Subscribes to mint/update/revoke/grant notifications; the returned
     * runnable unsubscribes. Listeners may be called from any thread.
     */
    public Runnable addChangeListener(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyChanged(String scopeId) {
        for (Consumer<String> listener : listeners) {
            listener.accept(scopeId);
        }
    }

    private String newId(Identity identity) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(scopeIdSecret, "HmacSHA256"));
            update(mac, identity.kind().name());
            update(mac, identity.repoRoot().toString());
            update(mac, identity.worktree().map(Path::toString).orElse(""));
            update(mac, identity.base());
            update(mac, identity.head());
            update(mac, identity.pr().map(Object::toString).orElse(""));
            return opaqueId(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create a review scope id", e);
        }
    }

    private static void update(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update((byte) (bytes.length >>> 24));
        mac.update((byte) (bytes.length >>> 16));
        mac.update((byte) (bytes.length >>> 8));
        mac.update((byte) bytes.length);
        mac.update(bytes);
    }

    private static String opaqueId(byte[] digest) {
        StringBuilder out = new StringBuilder("rs_");
        int bit = 0;
        for (int group = 0; group < 18; group++) {
            int value = 0;
            for (int offset = 0; offset < 5; offset++, bit++) {
                value = (value << 1) | ((digest[bit / 8] >>> (7 - (bit % 8))) & 1);
            }
            out.append(BASE32[value]);
        }
        return out.toString();
    }

    private static byte[] newSecret() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return secret;
    }
}
