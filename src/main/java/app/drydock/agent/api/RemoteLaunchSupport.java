package app.drydock.agent.api;

import app.drydock.sdlc.WorkflowResourceLease;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a REMOTE persistent launch may consume beyond its core command: a
 * review connection and workflow access, each independently optional, plus
 * the preparation leases that keep the prepared resources alive. It travels
 * beside, and is never merged into, {@link LaunchResources} or {@link
 * McpAccess}, which stay the host-side surfaces with their own refusal rules.
 *
 * <p>Every reference is remote path text, never a host {@link
 * java.nio.file.Path}, and carries no receipt, task guidance or credential.
 * Neither component implies the other: workflow access may be usable while
 * the review connection is unavailable. Nothing here stages or verifies
 * anything; a provider that cannot serve a present component must refuse.</p>
 */
public record RemoteLaunchSupport(Optional<ReviewConnection> reviewConnection,
                                  Optional<WorkflowAccess> workflowAccess,
                                  List<WorkflowResourceLease> leases) {

    /** Remote MCP config location plus the protocol and launch identity it was prepared for. */
    public record ReviewConnection(String mcpConfigPath, String protocol, String launchIdentity) {
        public ReviewConnection {
            mcpConfigPath = remotePath(mcpConfigPath, "mcpConfigPath");
            protocol = nonBlank(protocol, "protocol");
            launchIdentity = nonBlank(launchIdentity, "launchIdentity");
        }
    }

    /** Remote directories of the private workflow namespace the agent may read. */
    public record WorkflowAccess(List<String> readReferences) {
        public WorkflowAccess {
            Objects.requireNonNull(readReferences, "readReferences");
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String reference : readReferences) {
                unique.add(remotePath(reference, "readReference"));
            }
            if (unique.isEmpty()) {
                throw new IllegalArgumentException("workflow access needs a read reference");
            }
            readReferences = List.copyOf(unique);
        }
    }

    public RemoteLaunchSupport {
        Objects.requireNonNull(reviewConnection, "reviewConnection");
        Objects.requireNonNull(workflowAccess, "workflowAccess");
        leases = List.copyOf(Objects.requireNonNull(leases, "leases"));
    }

    /** Support with only a review connection. */
    public static RemoteLaunchSupport ofReviewConnection(ReviewConnection connection) {
        return new RemoteLaunchSupport(Optional.of(Objects.requireNonNull(connection, "connection")),
                Optional.empty(), List.of());
    }

    /** Support with only workflow access. */
    public static RemoteLaunchSupport ofWorkflowAccess(WorkflowAccess access) {
        return new RemoteLaunchSupport(Optional.empty(), Optional.of(Objects.requireNonNull(access, "access")),
                List.of());
    }

    private static String nonBlank(String value, String name) {
        String stripped = Objects.requireNonNull(value, name).strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return stripped;
    }

    /** Remote POSIX path text: absolute, no backslash (a host Windows path) and no NUL. */
    private static String remotePath(String value, String name) {
        String path = nonBlank(value, name);
        if (!path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must be absolute remote path text: " + path);
        }
        return path;
    }
}
