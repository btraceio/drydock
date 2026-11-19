package app.drydock.intellij.remote;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Credential;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Delivery;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Outcome;
import app.drydock.intellij.remote.RemoteCredentialGenerations.Request;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.mcp.RemoteAuthorizationPermit.StaleAuthorizationException;
import app.drydock.workspace.RemoteEnrollmentEvidence.Reason;
import app.drydock.workspace.RemoteEnrollmentEvidence.Verdict;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.workspace.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generation state machine and fence, against a fake delivery seam and a fake clock. */
class RemoteCredentialGenerationsTest {

    private static final ManagedSessionId SID = ManagedSessionId.newId();
    private static final Verdict READY = new Verdict.Ready();

    final List<Credential> delivered = new ArrayList<>();
    final List<Boolean> withdrawn = new ArrayList<>();
    boolean deliverOk = true;
    Runnable duringDeliver = () -> { };
    Instant now = Instant.EPOCH;
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    };
    final RemoteCredentialGenerations gens = new RemoteCredentialGenerations(new Delivery() {
        public boolean deliver(Credential c, BooleanSupplier cancelled) throws IOException {
            delivered.add(c);
            duringDeliver.run();
            return deliverOk;
        }
        public void withdraw(Request r, boolean published) { withdrawn.add(published); }
    }, clock);
    final Request req = new Request("box1", "inst1", SID, "agent-1", "nonce-1", 40123, 1, "/h/helper.py");

    private Outcome publish() throws Exception {
        return gens.publish(req, () -> READY, () -> false);
    }

    private Credential last() {
        return delivered.get(delivered.size() - 1);
    }

    @Test
    void publicConstructorWithoutRouteFailsClosed() throws Exception {
        var g = new RemoteCredentialGenerations((a, o, m) -> null, Path.of("x"), clock);
        assertEquals(Outcome.FAILED, g.publish(req, () -> READY, () -> false));
        assertEquals(0, g.liveTokenCount());
    }

    @Test
    void everyPublishGetsFreshEpochTokenAndRisingGeneration() throws Exception {
        assertEquals(Outcome.PUBLISHED, publish());
        Credential first = last();
        assertEquals(Outcome.PUBLISHED, publish());
        Credential second = last();
        assertEquals(64, second.token().length());
        assertEquals(32, second.epoch().length());
        assertNotEquals(first.token(), second.token());
        assertNotEquals(first.epoch(), second.epoch());
        assertEquals(first.generation() + 1, second.generation());
        assertTrue(gens.authenticate(second.token()).isPresent());
        assertTrue(gens.authenticate(first.token()).isEmpty());
        assertEquals(1, gens.liveTokenCount());
    }

    @Test
    void notReadyEvidencePublishesNothing() throws Exception {
        assertEquals(Outcome.NOT_READY,
                gens.publish(req, () -> new Verdict.Unavailable(Reason.PROCESS_GONE, "x"), () -> false));
        assertTrue(delivered.isEmpty());
        assertEquals(0, gens.liveTokenCount());
    }

    @Test
    void cancelledBeforeDeliveryDeliversNothingAndAuthorizesNothing() throws Exception {
        assertEquals(Outcome.CANCELLED, gens.publish(req, () -> READY, () -> true));
        assertTrue(delivered.isEmpty());
        assertEquals(0, gens.liveTokenCount());
        assertEquals(Duration.ZERO, gens.retryDelay(SID));
    }

    @Test
    void backoffScheduleOnFakeClockResetsOnSuccess() throws Exception {
        deliverOk = false;
        for (long seconds : new long[] {1, 2, 4, 8, 16, 30, 30}) {
            assertEquals(Outcome.FAILED, publish());
            assertEquals(Duration.ofSeconds(seconds), gens.retryDelay(SID));
            assertEquals(Outcome.BACKING_OFF, publish());
            now = now.plusSeconds(seconds);
        }
        assertEquals(0, gens.liveTokenCount());
        deliverOk = true;
        assertEquals(Outcome.PUBLISHED, publish());
        assertEquals(Duration.ZERO, gens.retryDelay(SID));
    }

    @Test
    void lateCompletionOfSupersededGenerationIsDiscardedAndCannotMoveBackoff() throws Exception {
        boolean[] nested = {false};
        duringDeliver = () -> {
            if (!nested[0]) {
                nested[0] = true;
                try {
                    assertEquals(Outcome.PUBLISHED, publish());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                deliverOk = false; // the outer (older) attempt fails late
            }
        };
        assertEquals(Outcome.FAILED, gens.publish(req, () -> READY, () -> false));
        assertEquals(2, last().generation());
        assertEquals(Duration.ZERO, gens.retryDelay(SID));
        assertEquals(1, gens.liveTokenCount());
        assertTrue(gens.authenticate(last().token()).isPresent());
        gens.recordFailure(SID, 1);
        assertEquals(Duration.ZERO, gens.retryDelay(SID));
    }

    @Test
    void lateSuccessOfRevokedGenerationIsDiscarded() throws Exception {
        duringDeliver = () -> gens.revoke(SID);
        assertEquals(Outcome.DISCARDED, publish());
        assertEquals(0, gens.liveTokenCount());
        assertTrue(gens.authenticate(last().token()).isEmpty());
        duringDeliver = () -> { };
        assertEquals(Outcome.PUBLISHED, publish());
        assertTrue(last().generation() > 1);
    }

    @Test
    void revokeInvalidatesTokenAndPermitAndWithdraws() throws Exception {
        publish();
        Credential c = last();
        RemoteAuthorizationPermit permit = gens.permitFor(SID).orElseThrow();
        assertTrue(permit.isCurrent());
        gens.revoke(SID);
        assertFalse(permit.isCurrent());
        assertThrows(StaleAuthorizationException.class, () -> permit.commitIfCurrent(() -> "x"));
        assertTrue(gens.authenticate(c.token()).isEmpty());
        assertTrue(gens.permitFor(SID).isEmpty());
        assertEquals(List.of(true), withdrawn);
    }

    @Test
    void rotationRevokesOldPermit() throws Exception {
        publish();
        RemoteAuthorizationPermit old = gens.permitFor(SID).orElseThrow();
        publish();
        assertFalse(old.isCurrent());
        assertTrue(gens.permitFor(SID).orElseThrow().isCurrent());
    }

    @Test
    void authenticationRejectsWrongShortAndLongTokens() throws Exception {
        publish();
        String t = last().token();
        assertTrue(gens.authenticate(t).isPresent());
        assertTrue(gens.authenticate(t.substring(1)).isEmpty());
        assertTrue(gens.authenticate(t + "0").isEmpty());
        assertTrue(gens.authenticate(t.substring(0, 63) + (t.endsWith("0") ? "1" : "0")).isEmpty());
        assertTrue(gens.authenticate("").isEmpty());
    }

    @Test
    void revocationTakesNoLockHeldByACommitter() throws Exception {
        publish();
        RemoteAuthorizationPermit permit = gens.permitFor(SID).orElseThrow();
        CountDownLatch inCommit = new CountDownLatch(1);
        CountDownLatch revoking = new CountDownLatch(1);
        CompletableFuture<Boolean> committer = CompletableFuture.supplyAsync(() -> permit.commitIfCurrent(() -> {
            inCommit.countDown();
            try {
                revoking.await(10, TimeUnit.SECONDS);
                // The revoker waits on the fence alone; touching owner state here must not deadlock with it.
                Thread.sleep(200);
                return gens.permitFor(SID).isPresent() | gens.liveTokenCount() >= 0;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertTrue(inCommit.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> revoker = CompletableFuture.runAsync(() -> {
            revoking.countDown();
            gens.revoke(SID);
        });
        assertTrue(committer.get(10, TimeUnit.SECONDS));
        revoker.get(10, TimeUnit.SECONDS);
        assertThrows(StaleAuthorizationException.class, () -> permit.commitIfCurrent(() -> "late"));
    }

    // ---- the private-file delivery route, with fake scp/ssh running the real T58 helper on a temp HOME ----

    private static final Path HELPER = Path.of("src/main/resources/drydock/remote/namespace_manifest.py")
            .toAbsolutePath();

    @TempDir
    Path tmp;

    /** A box whose HOME is a temp dir: scp copies into it, ssh runs the joined remote command with sh -c. */
    private final class FakeBox implements CommandRunner {
        final List<List<String>> argvs = new ArrayList<>();
        Path home;
        Path localDir;
        String failMatching;
        Runnable afterScp = () -> { };

        FakeBox() throws Exception {
            home = Files.createDirectory(tmp.resolve("home"), PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            localDir = Files.createDirectory(tmp.resolve("local"), PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        }

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options o, int cap)
                throws IOException, InterruptedException {
            argvs.add(argv);
            String joined = String.join(" ", argv);
            if (failMatching != null && joined.contains(failMatching)) {
                return new ProcessResult(1, "", "boom");
            }
            if (argv.get(0).equals("scp")) {
                String dest = argv.get(argv.size() - 1);
                Files.copy(Path.of(argv.get(argv.size() - 2)), home.resolve(dest.substring(dest.indexOf(':') + 1)));
                afterScp.run();
                return new ProcessResult(0, "", "");
            }
            return ProcessRunner.run(List.of("sh", "-c", argv.get(argv.size() - 1)),
                    new ProcessRunner.Options(null, Duration.ofSeconds(60), true, Map.of("HOME", home.toString())),
                    cap);
        }

        Path ns() {
            return home.resolve(".local/share/drydock/inst1/sessions/" + SID.value());
        }

        List<Path> pendingRemote() throws IOException {
            Path p = ns().resolve("pending");
            try (Stream<Path> s = Files.exists(p) ? Files.list(p) : Stream.empty()) {
                return s.toList();
            }
        }

        long localLeft() throws IOException {
            try (Stream<Path> s = Files.list(localDir)) {
                return s.count();
            }
        }
    }

    private Request realReq() {
        return new Request("box1", "inst1", SID, "agent-1", "nonce-1", 40123, 1, HELPER.toString());
    }

    @Test
    void realRouteDeliversManifestWithoutSecretInArgvAndCleansPending() throws Exception {
        FakeBox box = new FakeBox();
        var g = new RemoteCredentialGenerations(box, box.localDir, clock);
        assertEquals(Outcome.PUBLISHED, g.publish(realReq(), () -> READY, () -> false));
        String manifest = Files.readString(box.ns().resolve("connection.json"));
        String token = manifest.replaceAll(".*\"token\":\"([0-9a-f]+)\".*", "$1");
        assertEquals(64, token.length());
        assertEquals(SID, g.authenticate(token).orElseThrow());
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(box.ns().resolve("connection.json"))));
        assertTrue(box.pendingRemote().isEmpty());
        assertEquals(0, box.localLeft());
        assertTrue(box.argvs.stream().noneMatch(a -> String.join(" ", a).contains(token)), "no token in argv");
        assertTrue(box.argvs.stream().anyMatch(a -> a.get(0).equals("ssh")
                && a.get(a.size() - 1).contains("umask 077") && a.get(a.size() - 1).contains("mkdir")));
        assertTrue(g.cleanupDebt().isEmpty());
    }

    @Test
    void everyFailurePathRemovesLocalAndRemotePendingAndBacksOff() throws Exception {
        for (String failing : List.of("scp", "chmod 600", "python3")) {
            FakeBox box = new FakeBox();
            box.failMatching = failing;
            var g = new RemoteCredentialGenerations(box, box.localDir, clock);
            assertEquals(Outcome.FAILED, g.publish(realReq(), () -> READY, () -> false), failing);
            assertEquals(0, box.localLeft(), failing);
            assertTrue(box.pendingRemote().isEmpty(), failing);
            assertFalse(Files.exists(box.ns().resolve("connection.json")), failing);
            assertEquals(0, g.liveTokenCount(), failing);
            assertEquals(Duration.ofSeconds(1), g.retryDelay(SID), failing);
            tmp.resolve("home").toFile().setWritable(true);
            deleteTree(tmp.resolve("home"));
            deleteTree(tmp.resolve("local"));
        }
    }

    @Test
    void cancelAfterCopyRemovesRemotePendingAndPublishesNothing() throws Exception {
        FakeBox box = new FakeBox();
        boolean[] cancel = {false};
        box.afterScp = () -> cancel[0] = true;
        var g = new RemoteCredentialGenerations(box, box.localDir, clock);
        assertEquals(Outcome.CANCELLED, g.publish(realReq(), () -> READY, () -> cancel[0]));
        assertTrue(box.pendingRemote().isEmpty());
        assertEquals(0, box.localLeft());
        assertFalse(Files.exists(box.ns().resolve("connection.json")));
        assertEquals(0, g.liveTokenCount());
    }

    @Test
    void failedRemoteCleanupIsRecordedAsDebtNamesOnly() throws Exception {
        FakeBox box = new FakeBox();
        box.failMatching = "rm -f";
        var g = new RemoteCredentialGenerations(box, box.localDir, clock);
        assertEquals(Outcome.PUBLISHED, g.publish(realReq(), () -> READY, () -> false));
        assertEquals(1, g.cleanupDebt().size());
        assertTrue(g.cleanupDebt().get(0).startsWith("box1:.local/share/drydock/inst1/sessions/"));
        g.revoke(SID);
        assertEquals(2, g.cleanupDebt().size());
        assertTrue(g.cleanupDebt().stream().anyMatch(d -> d.endsWith("/connection.json")));
        assertTrue(Files.exists(box.ns().resolve("connection.json")), "failed removal leaves the file: debt");
    }

    @Test
    void revokeRemovesPublishedManifestOverSsh() throws Exception {
        FakeBox box = new FakeBox();
        var g = new RemoteCredentialGenerations(box, box.localDir, clock);
        assertEquals(Outcome.PUBLISHED, g.publish(realReq(), () -> READY, () -> false));
        g.revoke(SID);
        assertFalse(Files.exists(box.ns().resolve("connection.json")));
        assertTrue(g.cleanupDebt().isEmpty());
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
