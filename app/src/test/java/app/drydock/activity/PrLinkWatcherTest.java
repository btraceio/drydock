package app.drydock.activity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrLinkWatcherTest {

    /** Only needed to construct the watcher; every read here goes through poll(). */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void shutDownExecutor() {
        EXECUTOR.shutdown();
    }

    private static PrLinkWatcher watcher(Path dir) {
        return new PrLinkWatcher(dir, EXECUTOR);
    }

    @Test
    void pollReadsOneReferencePerLine(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("s1"), "DataDog/java-profiler#1034\n");

        Map<String, List<PrLinkWatcher.PrReference>> polled = watcher(dir).poll().join();

        assertEquals(1, polled.size());
        PrLinkWatcher.PrReference ref = polled.get("s1").get(0);
        assertEquals("DataDog", ref.owner());
        assertEquals("java-profiler", ref.repo());
        assertEquals(1034, ref.number());
        assertEquals("datadog/java-profiler", ref.slug());
    }

    /** A missing directory simply means nothing reported yet; it must not fail the poll. */
    @Test
    void pollWithoutADirectoryReportsNothing(@TempDir Path parent) {
        assertTrue(watcher(parent.resolve("pr-links")).poll().join().isEmpty());
    }

    /** The writers' in-flight temp files (dot-prefixed) are not reports. */
    @Test
    void hiddenTempFilesAreSkipped(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".s1.tmp.4242"), "DataDog/java-profiler#1034");

        assertTrue(watcher(dir).poll().join().isEmpty());
    }

    /** An unchanged file reports the same list instance, so callers can skip work by identity. */
    @Test
    void unchangedFilesReportTheSameInstance(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("s1"), "DataDog/java-profiler#1034");
        PrLinkWatcher w = watcher(dir);
        List<PrLinkWatcher.PrReference> first = w.poll().join().get("s1");

        List<PrLinkWatcher.PrReference> second = w.poll().join().get("s1");

        assertSame(first, second);
    }

    /** Same rule as the activity watcher: ids that stopped reporting drop out of the map. */
    @Test
    void removedFilesDropOutOfTheMap(@TempDir Path dir) throws IOException, InterruptedException {
        Files.writeString(dir.resolve("s1"), "DataDog/java-profiler#1034");
        PrLinkWatcher w = watcher(dir);
        w.poll().join();

        Files.delete(dir.resolve("s1"));

        assertTrue(w.poll().join().isEmpty());
    }

    /** A reference learned from a rebound conversation must not survive the rebind. */
    @Test
    void forgetDropsOneAgentSession(@TempDir Path dir) throws IOException, InterruptedException {
        Files.writeString(dir.resolve("s1"), "DataDog/java-profiler#1034");
        PrLinkWatcher w = watcher(dir);
        w.poll().join();

        w.forget("s1");
        // The file delete is best-effort (a swallowed IOException must not
        // break a rebind), so wait out filesystem visibility instead of racing
        // it: the poll reads the directory fresh, and a file still visible
        // there is re-reported by design.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (Files.exists(dir.resolve("s1")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.notExists(dir.resolve("s1")), "forget must remove the reference file");
        assertTrue(w.poll().join().isEmpty());
    }

    /** Malformed lines are skipped, never a reason to reject the whole file. */
    @Test
    void malformedLinesAreSkipped(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("s1"), """
                not a reference
                #1034
                owner/repo/extra#5
                owner/repo#notanumber
                owner/repo#99999999999999999999
                ok/repo#12
                """);

        Map<String, List<PrLinkWatcher.PrReference>> polled = watcher(dir).poll().join();

        assertEquals(List.of(new PrLinkWatcher.PrReference("ok", "repo", 12)), polled.get("s1"));
    }

    /** A file longer than the writers ever produce is not ours; skip it rather than trust it. */
    @Test
    void oversizedFilesAreIgnored(@TempDir Path dir) throws IOException {
        StringBuilder junk = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            junk.append("owner/repo#").append(i).append('\n');
        }
        Files.writeString(dir.resolve("s1"), junk.toString());

        assertTrue(watcher(dir).poll().join().isEmpty());
    }
}
