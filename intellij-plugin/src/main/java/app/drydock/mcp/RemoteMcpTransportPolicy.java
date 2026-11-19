package app.drydock.mcp;

import java.time.Duration;

/**
 * The transport bounds {@link McpServer} enforces on one endpoint, injected at
 * construction. {@link #LOCAL} keeps the loopback endpoint's historical
 * behaviour (unbounded body/response, lenient parse, no deadline, no
 * concurrency caps); {@link #REMOTE} is the strict profile for a transport
 * that carries traffic from outside the user's machine.
 *
 * @param bounded            false means every other component is ignored
 * @param maxBodyBytes       request body limit; one extra byte is read to detect an overrun
 * @param maxResponseBytes   serialized response limit; an oversize answer becomes an explicit error
 * @param maxJsonDepth       strict-parse nesting limit
 * @param maxConcurrent      application-wide in-flight requests
 * @param maxPerSession      in-flight requests per authenticated session
 * @param deadline           per-request dispatch deadline
 * @param rejectBatch        refuse JSON-RPC batch arrays instead of treating them as a parse error
 */
public record RemoteMcpTransportPolicy(boolean bounded, int maxBodyBytes, int maxResponseBytes,
                                       int maxJsonDepth, int maxConcurrent, int maxPerSession,
                                       Duration deadline, boolean rejectBatch) {

    public static final RemoteMcpTransportPolicy LOCAL =
            new RemoteMcpTransportPolicy(false, 0, 0, 0, 0, 0, Duration.ZERO, false);

    public static final RemoteMcpTransportPolicy REMOTE = new RemoteMcpTransportPolicy(
            true, 1024 * 1024, 4 * 1024 * 1024, 32, 16, 4, Duration.ofSeconds(35), true);

    public RemoteMcpTransportPolicy {
        if (bounded && (maxBodyBytes < 1 || maxResponseBytes < 1 || maxJsonDepth < 1
                || maxConcurrent < 1 || maxPerSession < 1 || deadline.isZero() || deadline.isNegative())) {
            throw new IllegalArgumentException("every bound of a bounded policy must be positive");
        }
    }
}
