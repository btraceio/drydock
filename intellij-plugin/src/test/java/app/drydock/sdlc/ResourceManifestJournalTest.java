package app.drydock.sdlc;

import app.drydock.sdlc.ResourceManifestJournal.Debt;
import app.drydock.sdlc.ResourceManifestJournal.Recovery;
import app.drydock.sdlc.ResourceManifestJournal.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceManifestJournalTest {

    @TempDir
    Path tmp;

    private ResourceManifestJournal journal() {
        return ResourceManifestJournal.open(tmp.resolve("sdlc").resolve("manifest.rmj"));
    }

    @Test
    void flushReplacesAtomicallyAndLeavesNoTempFile() throws IOException {
        ResourceManifestJournal j = journal();
        j.putPending("s 1", "b/1", 10, 20);
        j.flush();
        String first = Files.readString(tmp.resolve("sdlc/manifest.rmj"));
        assertTrue(j.retain("s 1", "b/1"));
        j.flush();
        assertFalse(first.equals(Files.readString(tmp.resolve("sdlc/manifest.rmj"))));
        try (Stream<Path> s = Files.list(tmp.resolve("sdlc"))) {
            assertEquals(1, s.count());
        }
        assertEquals(State.RETAINED, ResourceManifestJournal.recover(tmp.resolve("sdlc/manifest.rmj"))
                .retained().get(0).state());
    }

    @Test
    void retainTransfersExactlyOnce() {
        ResourceManifestJournal j = journal();
        assertFalse(j.retain("s", "b"));
        j.putPending("s", "b", 1, 2);
        assertTrue(j.retain("s", "b"));
        assertFalse(j.retain("s", "b"));
    }

    @Test
    void tornWriteIsReportedCorruptNotTrusted() throws IOException {
        ResourceManifestJournal j = journal();
        j.putPending("s", "b", 1, 2);
        j.flush();
        Path f = tmp.resolve("sdlc/manifest.rmj");
        byte[] all = Files.readAllBytes(f);
        Files.write(f, Arrays.copyOf(all, all.length - 10));
        Recovery r = ResourceManifestJournal.recover(f);
        assertTrue(r.corrupt());
        assertTrue(r.retained().isEmpty() && r.abandonedPending().isEmpty());
        Files.writeString(f, new String(all, StandardCharsets.UTF_8).replace("\t2\t", "\t3\t"));
        assertTrue(ResourceManifestJournal.recover(f).corrupt());
        assertFalse(ResourceManifestJournal.recover(tmp.resolve("absent")).corrupt());
    }

    @Test
    void debtAndRetainedLeaseSurviveRestartAndPendingIsAbandoned() throws IOException {
        ResourceManifestJournal j = journal();
        j.putPending("s", "kept", 5, 6);
        j.retain("s", "kept");
        j.putPending("s", "crashed", 7, 8);
        j.addDebt(new Debt("s", "remote:/ns/x", "unreachable\thost"));
        j.flush();
        Path f = tmp.resolve("sdlc/manifest.rmj");
        Recovery r = ResourceManifestJournal.recover(f);
        assertEquals("kept", r.retained().get(0).bundleId());
        assertEquals(6, r.retained().get(0).remoteBytes());
        assertEquals("crashed", r.abandonedPending().get(0).bundleId());
        assertEquals(new Debt("s", "remote:/ns/x", "unreachable\thost"), r.debts().get(0));

        ResourceManifestJournal next = ResourceManifestJournal.open(f);
        next.adopt(r);
        assertEquals(1, next.leases().size());
        assertEquals(1, next.debts().size());
        next.clearDebts("s");
        next.remove("s", "kept");
        next.flush();
        Recovery after = ResourceManifestJournal.recover(f);
        assertTrue(after.retained().isEmpty() && after.debts().isEmpty() && after.abandonedPending().isEmpty());
    }
}
