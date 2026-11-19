package app.drydock.babysit;

import app.drydock.babysit.BabysitStateFile.Contents;
import app.drydock.babysit.BabysitStateFile.DetachedEngagement;
import app.drydock.babysit.BabysitStateFile.Detachment;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The detachment in the babysitting state file: its serialization, leniency, and the generation gate. */
class BabysitDetachmentTest {

    private static final Instant AT = Instant.parse("2026-11-18T22:00:00Z");
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path dir;

    private final ManagedSessionId babysitter = ManagedSessionId.newId();
    private final UUID generation = UUID.randomUUID();

    private DetachedEngagement engagement(Optional<String> conversation) {
        return new DetachedEngagement(babysitter, ManagedSessionId.newId(), RepositoryId.newId(), 42,
                Path.of("/repo"), Optional.of("o/r"), Path.of("/wt"), "claude", conversation, Optional.of(HEAD),
                Optional.empty());
    }

    @Test
    void aDetachmentRoundTripsThroughTheFileBesideTheOtherMembers() throws Exception {
        BabysitStateFile file = new BabysitStateFile(dir.resolve("babysit-state.json"));
        Detachment detachment = new Detachment(generation, AT, List.of(engagement(Optional.of("conv")),
                engagement(Optional.empty())));

        file.update(c -> c.withRerunCount(7, 1).withDetachment(Optional.of(detachment)));

        Contents read = file.read().contents();
        assertEquals(Optional.of(detachment), read.detachment());
        assertEquals(1, read.rerunCount(7));
        assertTrue(read.current(generation));
        assertFalse(read.current(UUID.randomUUID()));
    }

    @Test
    void aBadEngagementIsDroppedAloneAndABadDetachmentWhole() throws Exception {
        Path path = dir.resolve("babysit-state.json");
        String good = BabysitStateFile.encode(Contents.empty().withDetachment(Optional.of(
                new Detachment(generation, AT, List.of(engagement(Optional.of("conv")))))));
        String withBad = good.replaceFirst("\"engagements\"\\s*:\\s*\\[", "\"engagements\": [{\"prNumber\": -1},");
        assertFalse(withBad.equals(good), "the bad engagement was inserted");
        Files.writeString(path, withBad);

        Snapshot snapshot = new Snapshot(new BabysitStateFile(path).read());
        assertEquals(1, snapshot.engagements().size());
        assertEquals(Optional.empty(), snapshot.problem());

        Files.writeString(path, good.replace(generation.toString(), "nope"));
        assertEquals(Optional.empty(), new BabysitStateFile(path).read().contents().detachment());
    }

    private record Snapshot(BabysitStateFile.Snapshot read) {
        List<DetachedEngagement> engagements() {
            return read.contents().detachment().orElseThrow().engagements();
        }

        Optional<String> problem() {
            return read.problem();
        }
    }

    @Test
    void anEndIsRecordedOnlyForTheCurrentGeneration() {
        Contents contents = Contents.empty().withDetachment(Optional.of(
                new Detachment(generation, AT, List.of(engagement(Optional.of("conv"))))));

        Contents ended = contents.withEngagementEnded(generation, babysitter, "PR #42 is CLOSED");
        assertEquals(Optional.of("PR #42 is CLOSED"), ended.detachment().orElseThrow().engagements().get(0).ended());

        assertSame(contents, contents.withEngagementEnded(UUID.randomUUID(), babysitter, "stale watcher"));
        assertThrows(IllegalArgumentException.class, () -> new DetachedEngagement(babysitter,
                ManagedSessionId.newId(), RepositoryId.newId(), 42, Path.of("/r"), Optional.empty(), Path.of("/w"),
                "claude", Optional.empty(), Optional.of("not-a-sha"), Optional.empty()));
    }
}
