package app.drydock.babysit;

import app.drydock.babysit.BabysitStateFile.Contents;
import app.drydock.babysit.BabysitStateFile.Owner;
import app.drydock.babysit.BabysitStateFile.RerunCharge;
import app.drydock.domain.ManagedSessionId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BabysitStateFileTest {

    private static final Instant AT = Instant.parse("2026-11-18T10:15:30Z");

    @TempDir
    Path dir;

    private Path file() {
        return BabysitStateFile.defaultFile(dir);
    }

    private static Escalation escalation(String message) {
        return new Escalation(UUID.randomUUID(), ManagedSessionId.newId(), ManagedSessionId.newId(), 42, message,
                AT, false);
    }

    @Test
    void theFileLivesInTheBabysitDirectoryBesideNotInsideTheStateFile() {
        assertEquals(dir.resolve("babysit").resolve("babysit-state.json"), BabysitStateFile.defaultFile(dir));
    }

    @Test
    void aMissingFileReadsEmptyAndNotPresent() {
        BabysitStateFile.Snapshot snapshot = new BabysitStateFile(file()).read();

        assertEquals(Contents.empty(), snapshot.contents());
        assertFalse(snapshot.present());
        assertEquals(Optional.empty(), snapshot.problem());
        assertEquals(0L, snapshot.stamp());
    }

    @Test
    void everyMemberRoundTrips() throws Exception {
        Escalation queued = escalation("rebase needs a decision");
        Owner owner = new Owner(4242, AT);
        BabysitStateFile state = new BabysitStateFile(file());

        state.update(contents -> contents.withRerunCount(901, 2).withEscalationQueued(queued)
                .withOwner(Optional.of(owner)));

        Contents read = new BabysitStateFile(file()).read().contents();
        assertEquals(Map.of(901L, 2), read.rerunCounts());
        assertEquals(List.of(queued), read.escalationQueue());
        assertEquals(Optional.of(owner), read.owner());
        String text = Files.readString(file());
        assertTrue(text.contains("\"schemaVersion\""), text);
        assertTrue(text.contains("\"901\""), text);
    }

    @Test
    void aRunIsChargedAtMostThreeTimesAndTheCapWritesNothing() throws Exception {
        BabysitStateFile state = new BabysitStateFile(file());

        assertEquals(new RerunCharge.Charged(1), state.chargeRerun(77, BabysitStateFile.MAX_RERUNS_PER_RUN));
        assertEquals(new RerunCharge.Charged(2), state.chargeRerun(77, BabysitStateFile.MAX_RERUNS_PER_RUN));
        assertEquals(new RerunCharge.Charged(3), state.chargeRerun(77, BabysitStateFile.MAX_RERUNS_PER_RUN));
        byte[] before = Files.readAllBytes(file());

        assertEquals(new RerunCharge.AtCap(3), state.chargeRerun(77, BabysitStateFile.MAX_RERUNS_PER_RUN));
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(new RerunCharge.Charged(1), state.chargeRerun(78, BabysitStateFile.MAX_RERUNS_PER_RUN),
                "the cap is per run");
    }

    @Test
    void aChargeIsOnDiskBeforeItReturns() throws Exception {
        new BabysitStateFile(file()).chargeRerun(5, 3);

        // A fresh instance, as a restarted IDE or the detached watcher would read it.
        assertEquals(1, new BabysitStateFile(file()).read().contents().rerunCount(5));
    }

    @Test
    void aRefundNeverDropsBelowZeroAndDropsTheEntryAtZero() throws Exception {
        BabysitStateFile state = new BabysitStateFile(file());
        state.chargeRerun(5, 3);

        state.refundRerun(5);
        state.refundRerun(5);

        assertEquals(Map.of(), state.read().contents().rerunCounts());
        assertEquals(new RerunCharge.Charged(1), state.chargeRerun(5, 3));
    }

    @Test
    void aNonPositiveRunIdIsAProgrammingError() {
        BabysitStateFile state = new BabysitStateFile(file());

        assertThrows(IllegalArgumentException.class, () -> state.chargeRerun(0, 3));
        assertThrows(IllegalArgumentException.class, () -> state.refundRerun(-1));
    }

    @Test
    void aMalformedFileIsNeverOverwritten() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{not json");
        byte[] before = Files.readAllBytes(file());
        BabysitStateFile state = new BabysitStateFile(file());

        assertTrue(state.read().problem().isPresent());
        assertEquals(Contents.empty(), state.read().contents());
        assertThrows(BabysitStateFileException.class, () -> state.chargeRerun(5, 3));
        assertThrows(BabysitStateFileException.class, () -> state.update(c -> c.withRerunCount(9, 1)));
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test
    void aFileFromANewerSchemaIsReadButNeverRewritten() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {"schemaVersion":2,"rerunCounts":{"5":1},"escalationQueue":[],"somethingNew":true}""",
                StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file());
        BabysitStateFile state = new BabysitStateFile(file());

        BabysitStateFile.Snapshot snapshot = state.read();
        assertEquals(1, snapshot.contents().rerunCount(5), "what can be read is still shown");
        assertTrue(snapshot.problem().orElseThrow().contains("newer"), snapshot.problem().orElseThrow());
        assertThrows(BabysitStateFileException.class, () -> state.chargeRerun(5, 3));
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test
    void malformedEntriesAreSkippedAndTheRestKept() throws Exception {
        Escalation good = escalation("kept");
        Files.createDirectories(file().getParent());
        String goodJson = "{\"id\":\"" + good.id() + "\",\"watchedId\":\"" + good.watchedId()
                + "\",\"babysitterId\":\"" + good.babysitterId() + "\",\"prNumber\":42,\"message\":\"kept\","
                + "\"createdAt\":\"" + AT + "\",\"resolved\":false}";
        Files.writeString(file(), "{\"schemaVersion\":1,"
                + "\"rerunCounts\":{\"5\":2,\"not-a-run\":1,\"6\":0,\"-7\":1,\"8\":\"x\"},"
                + "\"escalationQueue\":[" + goodJson + ",{\"id\":\"bad\"},7],"
                + "\"owner\":{\"pid\":\"x\"}}", StandardCharsets.UTF_8);
        BabysitStateFile state = new BabysitStateFile(file());

        BabysitStateFile.Snapshot snapshot = state.read();
        assertEquals(Optional.empty(), snapshot.problem());
        assertEquals(Map.of(5L, 2), snapshot.contents().rerunCounts());
        assertEquals(List.of(good), snapshot.contents().escalationQueue());
        assertEquals(Optional.empty(), snapshot.contents().owner());
        assertEquals(new RerunCharge.Charged(3), state.chargeRerun(5, 3), "a bad entry does not block writes");
    }

    @Test
    void aMissingSchemaVersionIsReadAsCurrentlyWritable() throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{\"rerunCounts\":{\"5\":1}}");

        assertEquals(new RerunCharge.Charged(2), new BabysitStateFile(file()).chargeRerun(5, 3));
    }

    @Test
    void theStampChangesWithEveryWriteAndNotOnARefusedCharge() throws Exception {
        BabysitStateFile state = new BabysitStateFile(file());
        state.chargeRerun(5, 1);
        long first = state.read().stamp();
        assertNotEquals(0L, first);

        state.chargeRerun(5, 1);
        assertEquals(first, state.read().stamp(), "a refused charge does not touch the file");

        state.update(contents -> contents.withEscalationQueued(escalation("a longer file now")));
        assertNotEquals(first, state.read().stamp());
    }

    @Test
    void concurrentChargesFromTwoInstancesNeverPassTheCap() throws Exception {
        BabysitStateFile first = new BabysitStateFile(file());
        BabysitStateFile second = new BabysitStateFile(file());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<RerunCharge>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < 16; thread++) {
                BabysitStateFile target = thread % 2 == 0 ? first : second;
                futures.add(pool.submit(() -> {
                    go.await();
                    return target.chargeRerun(99, BabysitStateFile.MAX_RERUNS_PER_RUN);
                }));
            }
            go.countDown();
            int charged = 0;
            for (Future<RerunCharge> future : futures) {
                if (future.get() instanceof RerunCharge.Charged) {
                    charged++;
                }
            }
            assertEquals(BabysitStateFile.MAX_RERUNS_PER_RUN, charged);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(BabysitStateFile.MAX_RERUNS_PER_RUN, first.read().contents().rerunCount(99));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void twoSpellingsOfOneFileShareOneJvmLock() throws Exception {
        Path real = Files.createDirectories(dir.resolve("real").resolve("babysit"));
        Path alias = Files.createSymbolicLink(dir.resolve("alias"), dir.resolve("real"));
        BabysitStateFile first = new BabysitStateFile(BabysitStateFile.defaultFile(dir.resolve("real")));
        BabysitStateFile second = new BabysitStateFile(BabysitStateFile.defaultFile(alias));
        assertNotEquals(first.file(), second.file(), "two spellings");
        assertEquals(BabysitStateFile.lockKey(first.file()), BabysitStateFile.lockKey(second.file()));
        assertEquals(BabysitStateFile.lockKey(first.file()),
                BabysitStateFile.lockKey(real.resolve("..").resolve("babysit").resolve("babysit-state.json")));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<RerunCharge>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < 16; thread++) {
                BabysitStateFile target = thread % 2 == 0 ? first : second;
                futures.add(pool.submit(() -> {
                    go.await();
                    return target.chargeRerun(77, BabysitStateFile.MAX_RERUNS_PER_RUN);
                }));
            }
            go.countDown();
            int charged = 0;
            for (Future<RerunCharge> future : futures) {
                // An unshared JVM lock surfaces here as an OverlappingFileLockException.
                if (future.get(10, TimeUnit.SECONDS) instanceof RerunCharge.Charged) {
                    charged++;
                }
            }
            assertEquals(BabysitStateFile.MAX_RERUNS_PER_RUN, charged);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(BabysitStateFile.MAX_RERUNS_PER_RUN, second.read().contents().rerunCount(77));
    }

    @Test
    void twoProcessesChargingTogetherLoseNothing() throws Exception {
        // The JVM-wide lock serialises same-process writers before they reach
        // the OS lock; only a second process exercises the file lock itself.
        String java = ProcessHandle.current().info().command().orElse(null);
        assumeTrue(java != null, "no java executable to start a second process");
        int each = 40;
        Path argFile = dir.resolve("writer.args");
        Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n");
        Process other = new ProcessBuilder(java, "@" + argFile, BabysitStateWriterProcess.class.getName(),
                file().toString(), "1000000", String.valueOf(each))
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("writer.log").toFile())
                .start();
        BabysitStateFile state = new BabysitStateFile(file());
        for (int i = 1; i <= each; i++) {
            assertInstanceOf(RerunCharge.Charged.class, state.chargeRerun(i, 3));
        }
        assertTrue(other.waitFor(60, TimeUnit.SECONDS), "the second writer did not finish");
        assertEquals(0, other.exitValue(), Files.readString(dir.resolve("writer.log")));

        assertEquals(2 * each, state.read().contents().rerunCounts().size());
    }

    @Test
    void contentsRefuseNonPositiveCountsAndOwnersRefuseNonPositivePids() {
        assertThrows(IllegalArgumentException.class,
                () -> new Contents(Map.of(5L, 0), List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new Contents(Map.of(0L, 1), List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Owner(0, AT));
    }
}
