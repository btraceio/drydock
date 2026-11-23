package app.drydock.babysit;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The detached babysitter's request files: names, bounded content, and the drain. */
class BabysitDropBoxTest {

    @TempDir
    Path base;

    @Test
    void onlyDigitRunIdsNameARerunRequest() {
        assertEquals(Optional.of(77L), BabysitDropBox.rerunRunId("rerun-77"));
        assertEquals(Optional.of(77L), BabysitDropBox.rerunRunId("rerun-77.txt"));
        for (String name : List.of("rerun-", "rerun-0", "rerun--7", "rerun-7x", "rerun-1234567890123456789",
                "Rerun-7", "escalation-7")) {
            assertEquals(Optional.empty(), BabysitDropBox.rerunRunId(name), name);
        }
        assertTrue(BabysitDropBox.isEscalation("escalation-design.md"));
        assertFalse(BabysitDropBox.isEscalation("escalation-design.md.tmp"));
    }

    @Test
    void escalationTextIsFlattenedCappedAndNeverBlank() {
        assertEquals(Optional.of("a b\n\tc"), BabysitDropBox.escalationText("  a\u001bb\n\tc \n"));
        assertEquals(Optional.empty(), BabysitDropBox.escalationText(" \n\u0000 "));
        assertEquals(BabysitDropBox.MAX_ESCALATION_CHARS,
                BabysitDropBox.escalationText("x".repeat(10_000)).orElseThrow().length());
        assertEquals(Optional.of("CI / test"), BabysitDropBox.checkName("CI / test\nmore"));
        assertEquals(Optional.empty(), BabysitDropBox.checkName("\n"));
    }

    @Test
    void aReadTakesRequestsInNameOrderDeletesNothingAndOnlyADeleteRemovesThem() throws Exception {
        Path box = BabysitDropBox.directory(base, ManagedSessionId.newId());
        Files.createDirectories(box);
        Files.writeString(box.resolve("escalation-b.md"), "second");
        Files.writeString(box.resolve("escalation-a.md"), "first");
        Files.writeString(box.resolve("escalation-blank.md"), "  ");
        Files.writeString(box.resolve("rerun-12"), "CI / build\n");
        Files.writeString(box.resolve("notes.md"), "not a request");
        Files.createDirectory(box.resolve("rerun-13"));

        BabysitDropBox.Drained drained = BabysitDropBox.read(box);

        assertEquals(List.of("first", "second"), drained.escalations());
        assertEquals(List.of(new BabysitDropBox.RerunRequest(12, Optional.of("CI / build"))), drained.reruns());
        assertEquals(4, drained.requests().size(), "the blank escalation is a request too, to delete");
        assertEquals(drained, BabysitDropBox.read(box), "a read deletes nothing; a re-read has the same ids");

        for (BabysitDropBox.Request request : drained.requests()) {
            BabysitDropBox.delete(request);
        }
        try (var left = Files.list(box)) {
            assertEquals(List.of("notes.md", "rerun-13"),
                    left.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertTrue(BabysitDropBox.read(base.resolve("absent")).requests().isEmpty());
    }

    @Test
    void oneDrainIsBounded() throws Exception {
        Path box = BabysitDropBox.directory(base, ManagedSessionId.newId());
        Files.createDirectories(box);
        for (int i = 0; i < BabysitDropBox.MAX_FILES + 5; i++) {
            Files.writeString(box.resolve(String.format("escalation-%03d.md", i)), "e" + i);
        }

        BabysitDropBox.Drained first = BabysitDropBox.read(box);
        assertEquals(BabysitDropBox.MAX_FILES, first.escalations().size());
        for (BabysitDropBox.Request request : first.requests()) {
            BabysitDropBox.delete(request);
        }
        assertEquals(5, BabysitDropBox.read(box).escalations().size());
    }
}
