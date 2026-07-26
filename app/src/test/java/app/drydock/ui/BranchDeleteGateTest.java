package app.drydock.ui;

import app.drydock.git.WorktreeService.MergeTarget;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The gate in front of {@code git branch -D}. The test that matters is
 * {@link #theTipIsReadAgainRatherThanTakenFromTheRecordedOne()}: every other
 * check here would still pass if the flow handed the recorded tip to
 * {@code forBranchDelete} twice, and so would the end-to-end drift test,
 * which builds both tips itself.
 */
class BranchDeleteGateTest {

    private static final String RECORDED = "1111111111111111111111111111111111111111";
    private static final String MOVED = "2222222222222222222222222222222222222222";

    private final AtomicInteger probes = new AtomicInteger();

    private Supplier probeReturning(String tip) {
        return () -> {
            probes.incrementAndGet();
            return CompletableFuture.completedFuture(new MergeTarget(Optional.of("main"),
                    "9999999999999999999999999999999999999999", Optional.of(tip),
                    MergeTarget.InProgress.NONE));
        };
    }

    /** Local alias so the tests read as prose rather than as a generic signature. */
    private interface Supplier extends java.util.function.Supplier<CompletableFuture<MergeTarget>> { }

    @Test
    void anUnmovedTipMayBeDeleted() throws Exception {
        assertEquals(MergeFinishDecision.BranchDeletePlan.DELETE,
                BranchDeleteGate.plan(true, Optional.of(RECORDED), probeReturning(RECORDED)).get());
        assertEquals(1, probes.get(), "the tip must be read exactly once");
    }

    @Test
    void theTipIsReadAgainRatherThanTakenFromTheRecordedOne() throws Exception {
        // The probe answers with a DIFFERENT tip than the recorded one: a gate
        // that compared the recorded tip with itself would say DELETE here and
        // destroy the commit that moved the branch.
        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_MOVED,
                BranchDeleteGate.plan(true, Optional.of(RECORDED), probeReturning(MOVED)).get());
        assertEquals(1, probes.get());
    }

    @Test
    void aProbeThatFailsIsTreatedAsDrift() throws Exception {
        Supplier failing = () -> {
            probes.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("git is gone"));
        };

        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_MOVED,
                BranchDeleteGate.plan(true, Optional.of(RECORDED), failing).get());
    }

    @Test
    void aProbeThatThrowsSynchronouslyIsTreatedAsDriftRatherThanEscaping() throws Exception {
        // The shutdown case: WorktreeService's calls start with
        // supplyAsync(..., executor), which throws once that executor closes.
        Supplier throwing = () -> {
            probes.incrementAndGet();
            throw new RejectedExecutionException("executor is shut down");
        };

        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_MOVED,
                BranchDeleteGate.plan(true, Optional.of(RECORDED), throwing).get());
    }

    @Test
    void aBranchThatIsNotOursIsKeptWithoutSpawningAProbe() throws Exception {
        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_NOT_OURS,
                BranchDeleteGate.plan(false, Optional.of(RECORDED), probeReturning(RECORDED)).get());
        assertEquals(0, probes.get(), "the answer cannot change the plan, so no git process is spawned");
    }

    @Test
    void aBranchThatVanishedIsKeptRatherThanDeletedBlind() throws Exception {
        Supplier vanished = () -> {
            probes.incrementAndGet();
            return CompletableFuture.completedFuture(new MergeTarget(Optional.of("main"),
                    "9999999999999999999999999999999999999999", Optional.empty(),
                    MergeTarget.InProgress.NONE));
        };

        assertEquals(MergeFinishDecision.BranchDeletePlan.KEEP_MOVED,
                BranchDeleteGate.plan(true, Optional.of(RECORDED), vanished).get());
    }
}
