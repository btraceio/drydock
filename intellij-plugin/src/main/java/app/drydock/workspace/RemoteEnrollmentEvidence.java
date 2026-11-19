package app.drydock.workspace;

import java.util.List;
import java.util.Objects;

/**
 * Value vocabulary of {@link RemoteEnrollmentProbe} (INV-44, INV-47): the launch
 * receipt and observation to validate, the three-way verdict, and the separately
 * reported preflight checks. A legacy process is only ever
 * {@link Verdict.Unavailable} with {@link Reason#LEGACY_PROVEN}.
 */
public final class RemoteEnrollmentEvidence {

    private RemoteEnrollmentEvidence() {
    }

    /** The receipt the launch wrapper wrote right before exec (T44). */
    public record Receipt(String nonce, String conversationId, long pid, String bootId, long startTicks) {
        public Receipt {
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(bootId, "bootId");
        }
    }

    /** What to validate; {@code receipt} is null when no launch wrapper ran. */
    public record Target(String host, String tmuxSession, long agentPid, String configPath,
                         long bridgePid, String observedNonce, Receipt receipt) {
        public Target {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(tmuxSession, "tmuxSession");
            Objects.requireNonNull(configPath, "configPath");
        }
    }

    /** Why enrollment is definitively unavailable. */
    public enum Reason {
        /** Actual command line carries no MCP config (INV-48). */
        LEGACY_PROVEN,
        /** The process no longer exists. */
        PROCESS_GONE
    }

    /** Ready / unavailable / unverifiable — never conflated (INV-47). */
    public sealed interface Verdict {
        record Ready() implements Verdict {
        }

        record Unavailable(Reason reason, String detail) implements Verdict {
        }

        record Unverifiable(String detail) implements Verdict {
        }
    }

    public enum Status { OK, MISSING, FAILED, UNVERIFIABLE }


    public record Check(String name, Status status, String detail) {
    }

    public record Preflight(List<Check> checks) {
        public Preflight {
            checks = List.copyOf(checks);
        }

        public Check check(String name) {
            return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        }
    }
}
