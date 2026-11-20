package app.drydock.intellij.review;

import app.drydock.intellij.review.RemoteReviewCapability.Advice;
import app.drydock.intellij.review.RemoteReviewCapability.Inputs;
import app.drydock.intellij.review.RemoteReviewCapability.Kind;
import app.drydock.intellij.review.RemoteReviewCapability.Report;
import app.drydock.intellij.review.RemoteReviewCapability.State;
import app.drydock.mcp.RemoteReviewContextDelegates.Evidence;
import app.drydock.workspace.RemoteEnrollmentEvidence.Reason;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteReviewCapabilityTest {

    private static Report assess(Verdict verdict, Evidence liveness, boolean attached) {
        return RemoteReviewCapability.assess(new Inputs(State.ready(), verdict, liveness,
                State.ready(), State.ready(), State.ready(), attached));
    }

    @Test
    void attachedEnrolledLiveAgentIsRunnable() {
        Report r = assess(new Verdict.Ready(), Evidence.ALIVE, true);
        assertTrue(r.toolsReady());
        assertTrue(r.runnable());
        assertEquals(Advice.NONE, r.advice());
    }

    @Test
    void detachedEnrolledAgentKeepsToolsButCannotRun() {
        Report r = assess(new Verdict.Ready(), Evidence.ALIVE, false);
        assertTrue(r.toolsReady());
        assertFalse(r.runnable());
        assertEquals(Kind.UNAVAILABLE, r.delivery().kind());
        assertEquals(Advice.REATTACH_THEN_RETRY, r.advice());
    }

    @Test
    void provenLegacyExplainsManualStopThenResume() {
        Report r = assess(new Verdict.Unavailable(Reason.LEGACY_PROVEN, "no config"), Evidence.ALIVE, true);
        assertEquals(Kind.UNAVAILABLE, r.enrollment().kind());
        assertFalse(r.runnable());
        assertEquals(Advice.STOP_THEN_RESUME, r.advice());
    }

    @Test
    void unverifiableEvidenceIsNotLegacy() {
        Report r = assess(new Verdict.Unverifiable("receipt unreadable"), Evidence.ALIVE, true);
        assertEquals(Kind.UNVERIFIABLE, r.enrollment().kind());
        assertEquals(Advice.CHECK_EVIDENCE_AND_RETRY, r.advice());
        assertFalse(r.runnable());
    }

    @Test
    void unknownLivenessSuppliesNoFreshCapability() {
        Report r = assess(new Verdict.Ready(), Evidence.UNKNOWN, true);
        assertEquals(Kind.UNVERIFIABLE, r.enrollment().kind());
        assertFalse(r.toolsReady());
    }

    @Test
    void confirmedDeathIsUnavailableEvenWithReadyProbe() {
        assertEquals(Kind.UNAVAILABLE, assess(new Verdict.Ready(), Evidence.DEAD, true).enrollment().kind());
    }

    @Test
    void missingTransportOrDataBlocksRunDespiteAttachedConsole() {
        Report r = RemoteReviewCapability.assess(new Inputs(State.unverifiable("forward down"),
                new Verdict.Ready(), Evidence.ALIVE, State.ready(), State.unavailable("ssh"), State.ready(), true));
        assertTrue(r.delivery().isReady());
        assertFalse(r.toolsReady());
        assertFalse(r.runnable());
    }

    @Test
    void queryHasNoSenderSeamSoNothingCanBeSent() {
        // The API takes evidence only: no sender/registry/console parameter exists to call.
        for (var m : RemoteReviewCapability.class.getDeclaredMethods()) {
            for (var p : m.getParameterTypes()) {
                assertFalse(p.getSimpleName().contains("Registry") || p.getSimpleName().contains("Sender"));
            }
        }
    }
}
