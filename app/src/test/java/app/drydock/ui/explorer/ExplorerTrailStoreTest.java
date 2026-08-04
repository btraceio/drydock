package app.drydock.ui.explorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
