package app.drydock.ui.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trail persistence (Explorer delta, part 1: "per-session, persisted"). */
class ExplorerTrailStoreTest {

    private static NavigationTrail.Waypoint waypoint(String path, int line, boolean pinned) {
        return new NavigationTrail.Waypoint(Path.of(path), Path.of(path).getFileName().toString(), line, pinned);
    }

    @Test
    void roundTripsATrailPerSession(@TempDir Path dir) {
        Path file = dir.resolve("explorer-trails.json");
        try (ExplorerTrailStore store = new ExplorerTrailStore(file)) {
            store.save("session-a", new ExplorerTrailStore.Trail(
                    List.of(waypoint("ui/Sidebar.java", 118, true), waypoint("ui/DragTracker.java", 27, false)), 1));
            store.save("session-b", new ExplorerTrailStore.Trail(List.of(waypoint("build.gradle.kts", 3, false)), 0));
        }

        ExplorerTrailStore reloaded = new ExplorerTrailStore(file);
        try {
            ExplorerTrailStore.Trail a = reloaded.load("session-a");
            assertEquals(2, a.waypoints().size());
            assertEquals(1, a.cursor());
            assertEquals(118, a.waypoints().get(0).line());
            assertTrue(a.waypoints().get(0).pinned());
            assertEquals("DragTracker.java", a.waypoints().get(1).label());
            assertEquals(1, reloaded.load("session-b").waypoints().size());
            assertEquals(ExplorerTrailStore.Trail.EMPTY.waypoints(), reloaded.load("nobody").waypoints());
        } finally {
            reloaded.close();
        }
    }

    @Test
    void aMalformedFileIsNotAFailure(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("explorer-trails.json");
        Files.writeString(file, "{ this is not json");
        try (ExplorerTrailStore store = new ExplorerTrailStore(file)) {
            assertTrue(store.load("session-a").waypoints().isEmpty());
        }
    }

    @Test
    void oneMalformedWaypointDoesNotDiscardTheTrail(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("explorer-trails.json");
        Files.writeString(file, """
                {"version":1,"trails":{"s":{"cursor":0,"waypoints":[
                  {"label":"no path here"},
                  {"file":"ui/A.java","label":"A.java","line":7,"pinned":false}
                ]}}}
                """);
        try (ExplorerTrailStore store = new ExplorerTrailStore(file)) {
            ExplorerTrailStore.Trail trail = store.load("s");
            assertEquals(1, trail.waypoints().size());
            assertEquals("A.java", trail.waypoints().get(0).label());
        }
    }

    @Test
    void aWaypointLineKeyRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("explorer-trails.json");
        try (ExplorerTrailStore store = new ExplorerTrailStore(file)) {
            store.save(ExplorerTrailStore.reviewKey("abc"), new ExplorerTrailStore.Trail(List.of(
                    new NavigationTrail.Waypoint(Path.of("ui/A.java"), "Step 3", 12, false, Optional.of("o12")),
                    waypoint("ui/B.java", 4, false)), 0));
        }
        try (ExplorerTrailStore reloaded = new ExplorerTrailStore(file)) {
            List<NavigationTrail.Waypoint> waypoints = reloaded.load("review:abc").waypoints();
            assertEquals(Optional.of("o12"), waypoints.get(0).lineKey());
            assertEquals(Optional.empty(), waypoints.get(1).lineKey());
        }
    }

    @Test
    void aSchemaOneFileWithoutLineKeyDecodesWithAnEmptyKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("explorer-trails.json");
        Files.writeString(file, """
                {"version":1,"trails":{"s":{"cursor":0,"waypoints":[
                  {"file":"ui/A.java","label":"A.java","line":7,"pinned":false}
                ]}}}
                """);
        try (ExplorerTrailStore store = new ExplorerTrailStore(file)) {
            assertEquals(Optional.empty(), store.load("s").waypoints().get(0).lineKey());
        }
    }

    @Test
    void retainKeepsAReviewTrailWhoseSessionIsLive(@TempDir Path dir) {
        Path file = dir.resolve("explorer-trails.json");
        ExplorerTrailStore.Trail trail = new ExplorerTrailStore.Trail(List.of(waypoint("ui/A.java", 1, false)), 0);
        try (ExplorerTrailStore store = new ExplorerTrailStore(file)) {
            store.save("abc", trail);
            store.save("review:abc", trail);
            store.save("review:zzz", trail);
            store.retain(List.of("abc"));
            store.flushPendingSaves();
        }
        try (ExplorerTrailStore reloaded = new ExplorerTrailStore(file)) {
            assertEquals(1, reloaded.load("abc").waypoints().size());
            assertEquals(1, reloaded.load("review:abc").waypoints().size());
            assertTrue(reloaded.load("review:zzz").waypoints().isEmpty());
        }
    }
}
