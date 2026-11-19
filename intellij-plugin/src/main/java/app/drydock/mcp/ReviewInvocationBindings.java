package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.tour.ReviewTour;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The ephemeral, generation-fenced record of how the latest committed review
 * invocation wants its output presented, keyed by the actual authorized
 * {@code (ManagedSessionId, scopeId)} pair.
 *
 * <p>This is presentation state only. It never authorizes anything: the router
 * still runs its live-session and scope checks first, and a binding found for
 * a pair that is no longer addressable is simply never asked for. Rebinding a
 * scope to another session therefore cannot make it addressable -- the
 * binding stays under the old session key until
 * {@link #removeUnlessOwnedBy} drops it. Nothing here is persisted, and no
 * caller argument or configuration can reach it.</p>
 *
 * <p>Every operation takes one lock, so concurrent human requests on a session
 * are serialized, and the generation a request gets is the order it committed
 * in. The latest committed invocation is authoritative.</p>
 */
public final class ReviewInvocationBindings {

    /** What the latest committed invocation asked for. */
    public record Binding(long generation, String target, ReviewTour.Kind kind) {
        public Binding {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Proof of one install, the only thing that can roll it back. */
    public static final class Ticket {
        private final ManagedSessionId sessionId;
        private final String scopeId;
        private final Binding installed;
        private final Optional<Binding> previous;

        private Ticket(ManagedSessionId sessionId, String scopeId, Binding installed, Optional<Binding> previous) {
            this.sessionId = sessionId;
            this.scopeId = scopeId;
            this.installed = installed;
            this.previous = previous;
        }

        public long generation() {
            return installed.generation();
        }
    }

    private record Key(ManagedSessionId sessionId, String scopeId) {
    }

    private final Map<Key, Binding> bindings = new HashMap<>();
    private long lastGeneration;

    /** Installs the binding immediately before the commit; a later install supersedes it. */
    public synchronized Ticket install(ManagedSessionId sessionId, String scopeId, String target,
                                       ReviewTour.Kind kind) {
        Key key = new Key(Objects.requireNonNull(sessionId, "sessionId"), Objects.requireNonNull(scopeId, "scopeId"));
        Binding installed = new Binding(++lastGeneration, target, kind);
        Optional<Binding> previous = Optional.ofNullable(bindings.put(key, installed));
        return new Ticket(sessionId, scopeId, installed, previous);
    }

    /**
     * Undoes one install after a KNOWN no-send. Only the ticket's own generation
     * is rolled back: if a later request has superseded it, or an ambiguous
     * send kept it, nothing changes. Returns whether it was rolled back.
     */
    public synchronized boolean rollback(Ticket ticket) {
        Key key = new Key(ticket.sessionId, ticket.scopeId);
        if (!ticket.installed.equals(bindings.get(key))) {
            return false;
        }
        if (ticket.previous.isPresent()) {
            bindings.put(key, ticket.previous.get());
        } else {
            bindings.remove(key);
        }
        return true;
    }

    /** The binding of exactly this pair; another session's binding is never visible. */
    public synchronized Optional<Binding> bindingOf(ManagedSessionId sessionId, String scopeId) {
        return Optional.ofNullable(bindings.get(new Key(sessionId, scopeId)));
    }

    /** Scope revoked: every session's binding for it goes. */
    public synchronized void removeScope(String scopeId) {
        bindings.keySet().removeIf(key -> key.scopeId().equals(scopeId));
    }

    /** Scope handed to another session: bindings of any other session are dropped. */
    public synchronized void removeUnlessOwnedBy(String scopeId, Optional<ManagedSessionId> owner) {
        bindings.keySet().removeIf(key -> key.scopeId().equals(scopeId) && !owner.filter(key.sessionId()::equals).isPresent());
    }

    /** Session removed or revoked. */
    public synchronized void removeSession(ManagedSessionId sessionId) {
        bindings.keySet().removeIf(key -> key.sessionId().equals(sessionId));
    }

    /** Backend close. */
    public synchronized void clear() {
        bindings.clear();
    }
}
