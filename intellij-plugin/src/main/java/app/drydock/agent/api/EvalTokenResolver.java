package app.drydock.agent.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves an auth token for the eval account, plus its expiry when
 * knowable. The token charges a session's model traffic to the eval
 * account instead of the user's.
 *
 * <p>This is an SPI rather than a hardcoded command so the resolution
 * mechanism is swappable: the default implementation runs a host-side
 * credential helper ({@code ddtool}), but once Drydock grows plugin
 * support a provider-specific plugin can supply this so the eval
 * integration is not bound to a single credential source.</p>
 *
 * <p>Implementations may block (process spawn, network) and MUST be called
 * off the JavaFX application thread. {@link #resolveToken()} is repeatable:
 * a long-running eval session may outlive the first token's TTL, so callers
 * re-invoke this to refresh (e.g. on resume).</p>
 */
public interface EvalTokenResolver {

    /** A resolved auth token and, when the credential carries it, the instant it expires. */
    record ResolvedToken(String token, Optional<Instant> expiry) {
        public ResolvedToken {
            java.util.Objects.requireNonNull(token, "token");
            java.util.Objects.requireNonNull(expiry, "expiry");
        }
    }

    /**
     * Resolves a fresh token. Empty when the credential is unavailable
     * (helper missing, refused, malformed): the caller fails the launch
     * loudly rather than silently shipping an unauthenticated session.
     */
    Optional<ResolvedToken> resolveToken();
}
