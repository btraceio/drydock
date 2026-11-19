package app.drydock.intellij.review;

import app.drydock.mcp.RemoteReviewContextDelegates.Evidence;
import app.drydock.workspace.RemoteEnrollmentEvidence.Reason;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;

import java.util.Objects;

/**
 * Headless capability query for a human review on an existing remote session
 * (INV-35, INV-48, INV-58). It only interprets evidence already gathered at the
 * typed boundaries; it holds no sender, queue or console handle, so it can never
 * deliver, reattach or restart anything.
 *
 * <p>Each capability is its own ready/unavailable/unverifiable {@link State}.
 * Attached-console delivery is separate from tool readiness: a detached but
 * enrolled, live agent keeps its tools and data, while {@link Report#runnable()}
 * additionally needs an attached console.</p>
 */
public final class RemoteReviewCapability {

    private RemoteReviewCapability() {
    }

    public enum Kind { READY, UNAVAILABLE, UNVERIFIABLE }

    public record State(Kind kind, String detail) {
        public State {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }

        public static State ready() {
            return new State(Kind.READY, "");
        }

        public static State unavailable(String detail) {
            return new State(Kind.UNAVAILABLE, detail);
        }

        public static State unverifiable(String detail) {
            return new State(Kind.UNVERIFIABLE, detail);
        }

        public boolean isReady() {
            return kind == Kind.READY;
        }
    }

    /** What the user must do next; never performed automatically. */
    public enum Advice { NONE, REATTACH_THEN_RETRY, STOP_THEN_RESUME, CHECK_EVIDENCE_AND_RETRY }

    /** Evidence from the boundaries: transport (ensure), enrollment probe, liveness, data, console. */
    public record Inputs(State transport, Verdict enrollment, Evidence liveness,
                         State scope, State data, State output, boolean attached) {
        public Inputs {
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(enrollment, "enrollment");
            Objects.requireNonNull(liveness, "liveness");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(output, "output");
        }
    }

    public record Report(State transport, State enrollment, State scope, State data, State output,
                         State delivery, Advice advice) {

        /** Tools/data are protocol-confirmed usable, regardless of console attachment. */
        public boolean toolsReady() {
            return transport.isReady() && enrollment.isReady() && scope.isReady()
                    && data.isReady() && output.isReady();
        }

        /** A new human Run may be submitted: tools ready AND an attached console. */
        public boolean runnable() {
            return toolsReady() && delivery.isReady();
        }
    }

    public static Report assess(Inputs in) {
        State enrollment = enrollment(in.enrollment(), in.liveness());
        State delivery = in.attached() ? State.ready()
                : State.unavailable("No attached console: resume/reattach the console, then retry the review");
        Advice advice = adviceFor(in, enrollment);
        return new Report(in.transport(), enrollment, in.scope(), in.data(), in.output(), delivery, advice);
    }

    private static State enrollment(Verdict verdict, Evidence liveness) {
        if (liveness == Evidence.DEAD) {
            return State.unavailable("The agent process is no longer running");
        }
        return switch (verdict) {
            // UNKNOWN liveness supplies no fresh capability, even with a Ready probe.
            case Verdict.Ready r -> liveness == Evidence.ALIVE ? State.ready()
                    : State.unverifiable("Agent liveness cannot be verified");
            case Verdict.Unavailable u -> State.unavailable(u.reason() == Reason.LEGACY_PROVEN
                    ? "Agent was started without review tools (legacy): " + u.detail()
                    : u.detail());
            case Verdict.Unverifiable u -> State.unverifiable(u.detail());
        };
    }

    private static Advice adviceFor(Inputs in, State enrollment) {
        if (in.enrollment() instanceof Verdict.Unavailable u && u.reason() == Reason.LEGACY_PROVEN
                && in.liveness() != Evidence.DEAD) {
            return Advice.STOP_THEN_RESUME;
        }
        boolean toolsUnverifiable = in.transport().kind() == Kind.UNVERIFIABLE
                || enrollment.kind() == Kind.UNVERIFIABLE;
        if (toolsUnverifiable) {
            return Advice.CHECK_EVIDENCE_AND_RETRY;
        }
        return in.attached() ? Advice.NONE : Advice.REATTACH_THEN_RETRY;
    }
}
