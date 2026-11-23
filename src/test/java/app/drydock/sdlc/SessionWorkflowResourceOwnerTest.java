package app.drydock.sdlc;

import app.drydock.sdlc.ResourceManifestJournal.Debt;
import app.drydock.sdlc.ResourceManifestJournal.Lease;
import app.drydock.sdlc.SessionWorkflowResourceOwner.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionWorkflowResourceOwnerTest {

    @TempDir
    Path tmp;

    private final List<String> cleaned = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean remoteUp = new AtomicBoolean(true);
    private final SessionWorkflowResourceOwner.Cleaner cleaner = new SessionWorkflowResourceOwner.Cleaner() {
        public CompletableFuture<Void> deleteLocal(Lease l) {
            cleaned.add("local:" + l.session() + "/" + l.bundleId());
            return CompletableFuture.completedFuture(null);
        }

        public CompletableFuture<Void> deleteRemote(Debt d) {
            if (!remoteUp.get()) {
                return CompletableFuture.failedFuture(new IOException("unreachable"));
            }
            cleaned.add("remote:" + d.session() + "/" + d.target());
            return CompletableFuture.completedFuture(null);
        }
    };

    private Path file() {
        return tmp.resolve("sdlc/manifest.rmj");
    }

    private SessionWorkflowResourceOwner owner() {
        return new SessionWorkflowResourceOwner(ResourceManifestJournal.open(file()), Runnable::run, cleaner);
    }

    private WorkflowResourceLease lease(String name) {
        return new WorkflowResourceLease(() -> {
            cleaned.add("lease:" + name);
            return CompletableFuture.completedFuture(null);
        });
    }

    @Test
    void acceptTransfersOnceAndSurvivesRestartForResume() throws Exception {
        SessionWorkflowResourceOwner o = owner();
        WorkflowResourceLease l = lease("a");
        assertEquals(Outcome.ACCEPTED, o.accept("s", "b1", 10, 20, l));
        assertEquals(WorkflowResourceLease.Owner.RETAINED_SESSION, l.owner());
        assertEquals(Outcome.REFUSED_DUPLICATE, o.accept("s", "b1", 10, 20, lease("dup")));
        assertEquals(List.of("lease:dup"), cleaned);
        SessionWorkflowResourceOwner restarted = owner();
        restarted.recover(ResourceManifestJournal.recover(file())).join();
        assertEquals(1, restarted.requireRetained("s").size());
        assertThrows(IllegalStateException.class, () -> restarted.requireRetained("other"));
    }

    @Test
    void faultedJournalFlushRefusesWithoutTransferAndCleansPending() throws IOException {
        Files.createDirectories(tmp.resolve("sdlc"));
        Files.createDirectories(file()); // journal path is a directory: the atomic replace fails
        SessionWorkflowResourceOwner o = owner();
        WorkflowResourceLease l = lease("f");
        assertEquals(Outcome.REFUSED_JOURNAL, o.accept("s", "b", 1, 1, l));
        assertEquals(WorkflowResourceLease.Owner.RELEASED, l.owner());
        assertThrows(IllegalStateException.class, () -> o.requireRetained("s"));
    }

    @Test
    void capExhaustionRefusesAndNeverEvicts() {
        SessionWorkflowResourceOwner o = owner();
        for (int i = 0; i < WorkflowLimits.RETAINED_BUNDLES_PER_SESSION; i++) {
            assertEquals(Outcome.ACCEPTED, o.accept("s", "b" + i, 1, 1, lease("k" + i)));
        }
        WorkflowResourceLease extra = lease("extra");
        assertEquals(Outcome.REFUSED_CAP, o.accept("s", "x", 1, 0, extra));
        assertEquals(WorkflowResourceLease.Owner.RELEASED, extra.owner());
        assertEquals(List.of("lease:extra"), cleaned);
        assertEquals(16, o.requireRetained("s").size());
        // local + remote are one allowance: 200 MiB local + 100 MiB remote exceeds 256 MiB
        assertEquals(Outcome.REFUSED_CAP, o.accept("t", "big", 200L << 20, 100L << 20, lease("big")));
        assertEquals(Outcome.ACCEPTED, o.accept("t", "ok", 128L << 20, 128L << 20, lease("ok")));
        assertEquals(Outcome.REFUSED_CAP, o.accept("t", "one", 1, 0, lease("one")));
    }

    @Test
    void aggregateCapCountsAcrossSessions() {
        SessionWorkflowResourceOwner o = owner();
        for (int i = 0; i < 4; i++) {
            assertEquals(Outcome.ACCEPTED, o.accept("s" + i, "b", 256L << 20, 0, lease("g" + i)));
        }
        assertEquals(Outcome.REFUSED_CAP, o.accept("s9", "b", 1, 0, lease("g9")));
    }

    @Test
    void deletionReleasesOnlyItsNamespaceAndRemoteDebtIsRetried() throws Exception {
        SessionWorkflowResourceOwner o = owner();
        WorkflowResourceLease keep = lease("keep");
        o.accept("keep", "b", 5, 5, keep);
        o.accept("gone", "b", 5, 7, lease("gone"));
        remoteUp.set(false);
        o.deleteSession("gone").join();
        assertEquals(List.of("lease:gone"), cleaned);
        assertEquals(WorkflowResourceLease.Owner.RETAINED_SESSION, keep.owner());
        assertEquals(1, ResourceManifestJournal.recover(file()).debts().size());
        // remote bytes remain accounted while the debt is outstanding
        assertEquals(Outcome.REFUSED_CAP, o.accept("gone2", "b", 1L << 30, 0, lease("fill")));
        o.retryDebts().join();
        assertEquals(1, ResourceManifestJournal.recover(file()).debts().size());
        remoteUp.set(true);
        o.retryDebts().join();
        assertTrue(cleaned.contains("remote:gone/b"));
        assertEquals(0, ResourceManifestJournal.recover(file()).debts().size());
        assertEquals(1, o.requireRetained("keep").size());
        assertFalse(cleaned.contains("lease:keep"));
    }

    @Test
    void recoveredLeaseWithoutMemoryHandleIsDeletedByCleaner() throws Exception {
        owner().accept("s", "b", 1, 0, lease("r"));
        SessionWorkflowResourceOwner restarted = owner();
        restarted.recover(ResourceManifestJournal.recover(file())).join();
        restarted.deleteSession("s").join();
        assertEquals(List.of("local:s/b"), cleaned);
    }

    @Test
    void abandonedPendingIsCleanedAtRecoveryButRetainedIsKept() throws Exception {
        ResourceManifestJournal j = ResourceManifestJournal.open(file());
        j.putPending("s", "pend", 1, 0);
        j.putPending("s", "ret", 1, 0);
        j.retain("s", "ret");
        j.flush();
        SessionWorkflowResourceOwner o = owner();
        o.recover(ResourceManifestJournal.recover(file())).join();
        assertEquals(List.of("local:s/pend"), cleaned);
        assertEquals("ret", o.requireRetained("s").get(0).bundleId());
    }

    @Test
    void acceptingAnotherInvocationNeverReleasesRetained() {
        SessionWorkflowResourceOwner o = owner();
        WorkflowResourceLease first = lease("first");
        o.accept("s", "b1", 1, 0, first);
        o.accept("s", "b2", 1, 0, lease("second"));
        assertEquals(WorkflowResourceLease.Owner.RETAINED_SESSION, first.owner());
        assertTrue(cleaned.isEmpty());
        assertEquals(2, o.requireRetained("s").size());
    }
}
