package app.drydock.agent.spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.drydock.agent.providers.pi.internal.PiEditorAskReceipts;
import app.drydock.agent.spi.EditorAskTurnObserver.Event;
import app.drydock.agent.spi.EditorAskTurnObserver.Kind;
import app.drydock.agent.spi.EditorAskTurnObserver.Observation;
import app.drydock.editorqa.EditorAskRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The observer contract, exercised on the Pi receipt implementation. */
class EditorAskTurnObserverContractTest {

    private static final String NONCE = "launch-nonce";

    private static void receipt(Path dir, long seq, String kind, String marker, String session, String nonce)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("%012d.json".formatted(seq)),
                "{\"v\":1,\"nonce\":\"%s\",\"seq\":%d,\"kind\":\"%s\",\"marker\":\"%s\",\"sessionId\":\"%s\"}"
                        .formatted(nonce, seq, kind, marker, session));
    }

    private static PiEditorAskReceipts observer(Path dir) {
        return new PiEditorAskReceipts(dir, NONCE);
    }

    @Test
    void eventsAreOrderedAndTheCursorAdvancesMonotonically(@TempDir Path dir) throws Exception {
        PiEditorAskReceipts o = observer(dir);
        String a = EditorAskRecord.newId();
        String b = EditorAskRecord.newId();
        o.arm(a);
        o.arm(b);
        receipt(dir, 1, "bound", a, "s", o.launchNonce());
        receipt(dir, 2, "settled", a, "s", o.launchNonce());
        receipt(dir, 3, "bound", b, "s", o.launchNonce());
        Observation first = o.read(0);
        assertEquals(List.of(1L, 2L, 3L), first.events().stream().map(Event::sequence).toList());
        assertEquals(3, first.cursor());
        assertTrue(o.read(first.cursor()).events().isEmpty());
        assertEquals(3, o.read(first.cursor()).cursor());
        // reading is not consuming: an older cursor replays
        assertEquals(2, o.read(1).events().size());
        receipt(dir, 4, "settled", b, "s", o.launchNonce());
        assertEquals(List.of(4L), o.read(3).events().stream().map(Event::sequence).toList());
    }

    @Test
    void foreignMarkerForeignNonceAndStaleReceiptsAreIgnored(@TempDir Path dir) throws Exception {
        String stale = EditorAskRecord.newId();
        String mine = EditorAskRecord.newId();
        String foreign = EditorAskRecord.newId();
        PiEditorAskReceipts o = observer(dir);
        receipt(dir, 1, "bound", mine, "s", o.launchNonce()); // exists before arming: stale
        long fence = o.arm(mine);
        assertEquals(1, fence);
        receipt(dir, 2, "bound", foreign, "s", o.launchNonce());
        receipt(dir, 3, "bound", mine, "s", "another-launch");
        receipt(dir, 4, "settled", mine, "s", o.launchNonce()); // settle without a bind
        assertTrue(o.read(0).events().isEmpty());
        receipt(dir, 5, "bound", mine, "s", o.launchNonce());
        assertEquals(List.of(5L), o.read(0).events().stream().map(Event::sequence).toList());
        assertThrows(IllegalArgumentException.class, () -> o.arm(mine));
        assertThrows(IllegalArgumentException.class, () -> o.arm("not-a-marker"));
        assertTrue(Files.exists(dir.resolve("000000000005.json")), "the handled high-water receipt stays on disk");
        assertFalse(stale.equals(mine));
    }

    @Test
    void duplicatesAndLateEventsNeverSurfaceTwiceAndOneTerminalOnly(@TempDir Path dir) throws Exception {
        PiEditorAskReceipts o = observer(dir);
        String a = EditorAskRecord.newId();
        o.arm(a);
        receipt(dir, 1, "bound", a, "s", o.launchNonce());
        receipt(dir, 2, "bound", a, "s", o.launchNonce());
        receipt(dir, 3, "bound", a, "other-session", o.launchNonce());
        receipt(dir, 4, "settled", a, "other-session", o.launchNonce());
        receipt(dir, 5, "settled", a, "s", o.launchNonce());
        receipt(dir, 6, "settled", a, "s", o.launchNonce());
        receipt(dir, 7, "bound", a, "s", o.launchNonce());
        List<Event> events = o.read(0).events();
        assertEquals(List.of(Kind.BOUND, Kind.SETTLED), events.stream().map(Event::kind).toList());
        assertEquals(List.of(1L, 5L), events.stream().map(Event::sequence).toList());
        try (var entries = Files.list(dir)) {
            assertEquals(List.of("000000000007.json"), entries.map(path -> path.getFileName().toString())
                    .sorted().toList(), "only the high-water file remains");
        }
    }

    @Test
    void malformedReceiptsAreDroppedButOversizedReceiptsPoisonDurably(@TempDir Path dir) throws Exception {
        PiEditorAskReceipts o = observer(dir);
        String a = EditorAskRecord.newId();
        o.arm(a);
        receipt(dir, 1, "bound", a, "s", o.launchNonce());
        Files.writeString(dir.resolve("000000000002.json"), "{not json");
        Observation beforePoison = o.read(0);
        assertEquals(List.of(Kind.BOUND), beforePoison.events().stream().map(Event::kind).toList());
        assertTrue(beforePoison.unavailable().isEmpty(), "invalid JSON is safely dropped without poisoning");

        Files.writeString(dir.resolve("000000000003.json"), "x".repeat(5000));
        Observation poisoned = o.read(0);
        assertEquals(List.of(Kind.BOUND), poisoned.events().stream().map(Event::kind).toList());
        assertTrue(poisoned.unavailable().isPresent(), "an oversized receipt is unknown, not empty");
        assertTrue(Files.exists(dir.resolve("000000000003.json")), "the offending file remains diagnosable");

        receipt(dir, 4, "settled", a, "s", o.launchNonce());
        Observation repeated = o.read(0);
        assertEquals(poisoned.events(), repeated.events());
        assertTrue(repeated.unavailable().isPresent(), "availability stays poisoned after later good data");
        assertTrue(Files.exists(dir.resolve("000000000004.json")), "higher receipts are not consumed past poison");
        assertThrows(IllegalStateException.class, () -> o.arm(EditorAskRecord.newId()));
    }

    @Test
    void aLateLowerSequenceThatCouldCarryEvidenceIsKeptAndPoisons(@TempDir Path dir) throws Exception {
        PiEditorAskReceipts o = observer(dir);
        String first = EditorAskRecord.newId();
        String second = EditorAskRecord.newId();
        o.arm(first);
        o.arm(second);
        receipt(dir, 1, "bound", first, "s", o.launchNonce());
        receipt(dir, 2, "bound", first, "s", o.launchNonce()); // duplicate, but advances the high-water mark
        assertEquals(List.of(Kind.BOUND), o.read(0).events().stream().map(Event::kind).toList());
        assertFalse(Files.exists(dir.resolve("000000000001.json")));
        assertTrue(Files.exists(dir.resolve("000000000002.json")), "the high-water file is retained");

        receipt(dir, 1, "bound", second, "s", o.launchNonce());
        Observation collision = o.read(1);
        assertTrue(collision.unavailable().isPresent());
        assertTrue(collision.events().isEmpty());
        assertTrue(Files.exists(dir.resolve("000000000001.json")), "unclassified lower receipt is retained");
        assertTrue(o.read(1).unavailable().isPresent(), "sequence collision remains unavailable");
        assertThrows(IllegalStateException.class, () -> o.arm(EditorAskRecord.newId()));
    }

    @Test
    void lateLowerReceiptProvenStaleByFenceDoesNotPoison(@TempDir Path dir) throws Exception {
        String marker = EditorAskRecord.newId();
        PiEditorAskReceipts o = observer(dir);
        receipt(dir, 1, "bound", marker, "old-session", o.launchNonce());
        assertEquals(1, o.arm(marker));
        receipt(dir, 3, "bound", marker, "current-session", o.launchNonce());
        Observation initial = o.read(0);
        assertTrue(initial.unavailable().isEmpty());
        assertEquals(List.of(3L), initial.events().stream().map(Event::sequence).toList());

        // This unknown lower sequence is safe to discard because it predates this marker's fence.
        receipt(dir, 1, "bound", marker, "stale-session", o.launchNonce());
        Observation stale = o.read(0);
        assertTrue(stale.unavailable().isEmpty());
        assertEquals(initial.events(), stale.events());
        assertFalse(Files.exists(dir.resolve("000000000001.json")));

        receipt(dir, 4, "settled", marker, "current-session", o.launchNonce());
        Observation settled = o.read(3);
        assertTrue(settled.unavailable().isEmpty());
        assertEquals(List.of(4L), settled.events().stream().map(Event::sequence).toList());
    }

    @Test
    void retainedHighWaterReceiptLetsARecreatedWriterContinue(@TempDir Path dir) throws Exception {
        String first = EditorAskRecord.newId();
        PiEditorAskReceipts original = new PiEditorAskReceipts(dir, NONCE);
        original.arm(first);
        receipt(dir, 1, "bound", first, "s", NONCE);
        assertEquals(List.of(Kind.BOUND), original.read(0).events().stream().map(Event::kind).toList());
        assertTrue(Files.exists(dir.resolve("000000000001.json")));

        PiEditorAskReceipts afterRestart = new PiEditorAskReceipts(dir, NONCE);
        String next = EditorAskRecord.newId();
        assertEquals(1, afterRestart.arm(next));
        receipt(dir, 2, "bound", next, "s", NONCE);
        assertEquals(List.of(2L), afterRestart.read(0).events().stream().map(Event::sequence).toList());
        assertFalse(Files.exists(dir.resolve("000000000001.json")));
        assertTrue(Files.exists(dir.resolve("000000000002.json")));
    }

    @Test
    void closeRemovesOnlyTheLaunchReceiptTreeAndIsIdempotent(@TempDir Path dir) throws Exception {
        Path sharedParent = dir.resolve("editor-ask");
        Path launch = sharedParent.resolve(NONCE);
        Path sibling = sharedParent.resolve("launch-sibling");
        Files.createDirectories(launch.resolve("nested"));
        Files.writeString(launch.resolve("nested/receipt.json"), "receipt");
        Files.createDirectories(sibling);
        Path siblingCanary = sibling.resolve("keep.txt");
        Files.writeString(siblingCanary, "keep");
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(launch.resolve("sibling-link"), sibling);
            symlinkCreated = true;
        } catch (UnsupportedOperationException | IOException unsupported) {
            // The parent-preservation assertions still cover the non-symlink cleanup path.
        }
        PiEditorAskReceipts o = new PiEditorAskReceipts(launch, NONCE);
        o.close();
        o.close();
        assertFalse(Files.exists(launch));
        assertTrue(Files.isDirectory(sharedParent));
        assertTrue(Files.isDirectory(sibling));
        assertTrue(Files.exists(siblingCanary), "cleanup must not follow a symlink out of the launch tree");
        if (symlinkCreated) {
            assertFalse(Files.exists(launch.resolve("sibling-link")));
        }
    }

    @Test
    void theBacklogAndArmedMarkerBoundsAreEnforced(@TempDir Path dir) throws Exception {
        PiEditorAskReceipts o = observer(dir);
        for (int i = 0; i < EditorAskTurnObserver.MAX_ARMED_MARKERS; i++) {
            o.arm(EditorAskRecord.newId());
        }
        assertThrows(IllegalStateException.class, () -> o.arm(EditorAskRecord.newId()));
        for (int i = 1; i <= 1025; i++) {
            Files.writeString(dir.resolve("%012d.json".formatted(i)), "{}");
        }
        assertTrue(o.read(0).unavailable().isPresent());
    }

    @Test
    void closedObserverRefusesAndSharesNothing(@TempDir Path dir) {
        PiEditorAskReceipts o = observer(dir);
        o.close();
        o.close();
        assertThrows(IllegalStateException.class, () -> o.arm(EditorAskRecord.newId()));
        assertTrue(o.read(0).unavailable().isPresent());
    }

    @Test
    void launchesGetDistinctNonces(@TempDir Path dir) {
        assertFalse(observer(dir).launchNonce().equals(observer(dir).launchNonce()));
    }
}
