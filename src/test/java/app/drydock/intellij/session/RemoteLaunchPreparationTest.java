package app.drydock.intellij.session;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.remote.RemoteForwardOwner.ForwardException;
import app.drydock.intellij.remote.RemoteReviewService.Ready;
import app.drydock.intellij.remote.RemoteReviewService.Session;
import app.drydock.intellij.session.RemoteLaunchPreparation.Attachment;
import app.drydock.intellij.session.RemoteLaunchPreparation.Capability;
import app.drydock.intellij.session.RemoteLaunchPreparation.Invocation;
import app.drydock.intellij.session.RemoteLaunchPreparation.Prepared;
import app.drydock.intellij.session.RemoteLaunchPreparation.Request;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AC-32 (preflight half), AC-36, AC-49 (helper half): one case per remote failure-matrix row. */
class RemoteLaunchPreparationTest {

    private static final Capability READY = new Capability.Ready();
    private static final Capability NONE = new Capability.NotConsumed();

    private final AtomicInteger ensures = new AtomicInteger();
    private ForwardException failure;
    private RuntimeException crash;

    private RemoteLaunchPreparation helper() {
        return new RemoteLaunchPreparation(s -> {
            ensures.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            if (crash != null) {
                throw crash;
            }
            return new Ready(4242, 1, null, null);
        });
    }

    private static Request req(Invocation inv, Attachment att, String resumeId, Capability core, Capability wf,
                               Capability data, boolean bypass) {
        Session s = new Session("box", "inst", ManagedSessionId.newId(), "conv", "nonce", 1, "/h",
                () -> new Verdict.Ready());
        return new Request(inv, att, resumeId, core, wf, data, bypass, s);
    }

    private static Request task(Invocation inv, Attachment att, Capability wf) {
        return req(inv, att, "conv-1", READY, wf, NONE, false);
    }

    @Test
    void freshConfiguredTaskWithForwardFailureLaunchesWithoutToolsAndKeepsWorkflow() {
        failure = new ForwardException("ssh forwarding not permitted");
        Prepared p = helper().prepare(task(Invocation.FRESH_TASK, Attachment.ATTACHED, READY));
        assertTrue(p.proceeds());
        assertEquals(1, ensures.get());
        assertEquals(READY, p.workflow());
        assertEquals(new Capability.Unavailable("ssh forwarding not permitted"), p.review());
        assertEquals("Task started without Drydock review tools: ssh forwarding not permitted", p.notice().get());
        assertEquals(RemoteLaunchPreparation.TOOLS_UNAVAILABLE, p.guidanceNote().get());
        assertFalse(p.titleGuidance());
        assertEquals(NONE, p.data());
        assertEquals(NONE, p.delivery());
    }

    @Test
    void freshDefaultTaskWithEnsuredForwardIsPreparedWithTitleGuidance() {
        Prepared p = helper().prepare(task(Invocation.FRESH_TASK, Attachment.ATTACHED, NONE));
        assertEquals(1, ensures.get());
        assertEquals(READY, p.review());
        assertTrue(p.titleGuidance());
        assertTrue(p.notice().isEmpty());
        assertTrue(p.guidanceNote().isEmpty());
    }

    @Test
    void blankOrNativePromptNeverGetsGuidanceButStillReportsTheOmission() {
        crash = new IllegalStateException("boom");
        Prepared p = helper().prepare(req(Invocation.FRESH_TASK, Attachment.ATTACHED, null, READY, NONE, NONE, true));
        assertTrue(p.proceeds());
        assertInstanceOf(Capability.Unverifiable.class, p.review());
        assertTrue(p.guidanceNote().isEmpty());
        assertTrue(p.notice().isPresent());
    }

    @Test
    void unusableSelectedWorkflowResourcesAndCoreFailuresStayHardWithoutOpeningAForward() {
        Prepared wf = helper().prepare(task(Invocation.FRESH_TASK, Attachment.ATTACHED,
                new Capability.Unavailable("remote python3 missing")));
        assertFalse(wf.proceeds());
        assertEquals("remote python3 missing", wf.blocked().get());
        Prepared core = helper().prepare(req(Invocation.FRESH_TASK, Attachment.ATTACHED, null,
                new Capability.Unverifiable("tmux not verified"), NONE, NONE, false));
        assertEquals("tmux not verified", core.blocked().get());
        assertEquals(0, ensures.get());
    }

    @Test
    void resumeNewProcessRequiresTheStoredConversationIdAndNeverStartsAnother() {
        for (String id : new String[] {null, "", "  "}) {
            Prepared p = helper().prepare(req(Invocation.RESUME_NEW_PROCESS, Attachment.ATTACHED, id, READY,
                    NONE, NONE, false));
            assertFalse(p.proceeds());
            assertTrue(p.blocked().get().contains("no stored conversation ID"));
        }
        assertEquals(0, ensures.get());
    }

    @Test
    void resumeNewProcessProceedsWithoutToolsWhenEnsureFailsAndHardFailsOnRetainedResources() {
        failure = new ForwardException("bind race");
        Prepared ok = helper().prepare(task(Invocation.RESUME_NEW_PROCESS, Attachment.DETACHED, READY));
        assertTrue(ok.proceeds());
        assertEquals(1, ensures.get());
        assertTrue(ok.notice().get().contains("bind race"));
        Prepared hard = helper().prepare(task(Invocation.RESUME_NEW_PROCESS, Attachment.DETACHED,
                new Capability.Unavailable("retained lease gone")));
        assertFalse(hard.proceeds());
        assertEquals(1, ensures.get());
    }

    @Test
    void explicitReviewRefusesOnEveryMissingMandatoryCapabilityWithoutDefaultRepair() {
        Request ok = req(Invocation.EXPLICIT_REVIEW, Attachment.ATTACHED, "c", READY, NONE, READY, false);
        assertTrue(helper().prepare(ok).proceeds());
        Prepared detached = helper().prepare(req(Invocation.EXPLICIT_REVIEW, Attachment.DETACHED, "c", READY, NONE,
                READY, false));
        assertEquals("Resume/reattach the console, then retry review", detached.blocked().get());
        Prepared noData = helper().prepare(req(Invocation.EXPLICIT_REVIEW, Attachment.ATTACHED, "c", READY, NONE,
                new Capability.Unavailable("no git"), false));
        assertEquals("no git", noData.blocked().get());
        failure = new ForwardException("no forward");
        Prepared noFwd = helper().prepare(ok);
        assertEquals("no forward", noFwd.blocked().get());
        assertTrue(noFwd.notice().isEmpty());
        Prepared unv = helper().prepare(req(Invocation.EXPLICIT_REVIEW, Attachment.UNVERIFIABLE, "c", READY, NONE,
                READY, false));
        assertFalse(unv.proceeds());
        assertInstanceOf(Capability.Unverifiable.class, unv.delivery());
    }

    @Test
    void liveLegacyReattachExplainsManualStopThenResumeWithoutEnsuringOrBlocking() {
        Prepared p = helper().prepare(task(Invocation.REATTACH_LIVE, Attachment.LEGACY, READY));
        assertEquals(0, ensures.get());
        assertTrue(p.proceeds());
        assertEquals(RemoteLaunchPreparation.LEGACY_EXPLANATION, p.legacyExplanation().get());
        assertTrue(p.legacyExplanation().get().contains("Stop it, then Resume"));
        assertEquals(NONE, p.review());
        assertFalse(p.titleGuidance());
        Prepared hard = helper().prepare(task(Invocation.REATTACH_LIVE, Attachment.LEGACY,
                new Capability.Unavailable("lease gone")));
        assertFalse(hard.proceeds());
        assertTrue(hard.legacyExplanation().isEmpty());
    }

    @Test
    void unverifiableAttachmentOnLiveReattachIsNotTreatedAsLegacy() {
        Prepared p = helper().prepare(task(Invocation.REATTACH_LIVE, Attachment.UNVERIFIABLE, READY));
        assertTrue(p.legacyExplanation().isEmpty());
        assertEquals(1, ensures.get());
        assertTrue(p.proceeds());
    }

    @Test
    void afterSpawnEnrollmentFailureDowngradesOnlyToolsAndNeverBlocks() {
        RemoteLaunchPreparation h = helper();
        Prepared before = h.prepare(task(Invocation.FRESH_TASK, Attachment.ATTACHED, READY));
        assertEquals(READY, before.review());
        Prepared after = h.afterSpawn(before, new Capability.Unavailable("enroll rejected"), false);
        assertTrue(after.proceeds());
        assertEquals(before.core(), after.core());
        assertEquals(before.workflow(), after.workflow());
        assertEquals(new Capability.Unavailable("enroll rejected"), after.review());
        assertEquals("Task started without Drydock review tools: enroll rejected", after.notice().get());
        assertFalse(after.titleGuidance());
        assertTrue(h.afterSpawn(after, READY, false).review().ready() == false);
        assertTrue(h.afterSpawn(before, READY, false).notice().isEmpty());
        assertTrue(h.afterSpawn(before, new Capability.Unverifiable("x"), true).guidanceNote().isEmpty());
        assertEquals(1, ensures.get());
    }
}
