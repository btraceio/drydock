package app.drydock.ui.explorer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trail's arithmetic (Explorer delta, part 1): browser semantics, pins, eviction. */
class NavigationTrailTest {

    private static Path file(String name) {
        return Path.of("src", name);
    }

    private static void open(NavigationTrail trail, String name) {
        trail.push(file(name), name, 1);
    }

    @Test
    void pushesOneWaypointPerFile() {
        NavigationTrail trail = new NavigationTrail();
        open(trail, "A.java");
        open(trail, "B.java");
        assertEquals(List.of("A.java", "B.java"),
                trail.waypoints().stream().map(NavigationTrail.Waypoint::label).toList());
        assertEquals(1, trail.cursor());
    }

    @Test
    void reopeningTheCurrentFileOnlyUpdatesItsLine() {
        NavigationTrail trail = new NavigationTrail();
        open(trail, "A.java");
        assertFalse(trail.push(file("A.java"), "A.java", 42),
                "re-opening the file already at the cursor is not a navigation");
        assertEquals(1, trail.waypoints().size());
        assertEquals(42, trail.current().orElseThrow().line());
    }

    @Test
    void navigatingFromMidTrailTruncatesTheForwardBranch() {
        NavigationTrail trail = new NavigationTrail();
        open(trail, "A.java");
        open(trail, "B.java");
        open(trail, "C.java");
        trail.back();
        assertEquals("B.java", trail.current().orElseThrow().label());
        open(trail, "D.java");
        assertEquals(List.of("A.java", "B.java", "D.java"),
                trail.waypoints().stream().map(NavigationTrail.Waypoint::label).toList());
        assertFalse(trail.canGoForward());
    }

    @Test
    void backAndForwardWalkTheTrail() {
        NavigationTrail trail = new NavigationTrail();
        open(trail, "A.java");
        open(trail, "B.java");
        assertTrue(trail.canGoBack());
        assertEquals("A.java", trail.back().orElseThrow().label());
        assertFalse(trail.canGoBack());
        assertTrue(trail.back().isEmpty());
        assertEquals("B.java", trail.forward().orElseThrow().label());
        assertTrue(trail.forward().isEmpty());
    }

    @Test
    void rememberLineIsRestoredOnReturn() {
        NavigationTrail trail = new NavigationTrail();
        open(trail, "A.java");
        trail.rememberLine(320);
        open(trail, "B.java");
        assertEquals(320, trail.back().orElseThrow().line());
    }

    @Test
    void evictionDropsTheOldestUnpinnedWaypoint() {
        NavigationTrail trail = new NavigationTrail();
        for (int i = 0; i < NavigationTrail.CAPACITY; i++) {
            open(trail, "F" + i + ".java");
        }
        trail.goTo(0);
        trail.togglePin();
        trail.goTo(trail.waypoints().size() - 1);

        open(trail, "New.java");

        assertEquals(NavigationTrail.CAPACITY, trail.waypoints().size());
        assertEquals("F0.java", trail.waypoints().get(0).label(), "the pin survived");
        assertEquals("F2.java", trail.waypoints().get(1).label(), "F1 was the oldest unpinned");
        assertEquals("New.java", trail.current().orElseThrow().label(),
                "the cursor still points at what was just opened");
    }

    @Test
    void aFullyPinnedTrailStopsEvictingRatherThanBreakingAPin() {
        NavigationTrail trail = new NavigationTrail();
        for (int i = 0; i < NavigationTrail.CAPACITY; i++) {
            open(trail, "F" + i + ".java");
            trail.togglePin();
        }
        open(trail, "New.java");
        assertEquals(NavigationTrail.CAPACITY + 1, trail.waypoints().size());
    }

    @Test
    void restoreClampsTheCursorIntoTheTrail() {
        NavigationTrail trail = new NavigationTrail();
        trail.restore(List.of(new NavigationTrail.Waypoint(file("A.java"), "A.java", 5, true)), 9);
        assertEquals(0, trail.cursor());
        assertEquals(5, trail.current().orElseThrow().line());
        assertTrue(trail.current().orElseThrow().pinned());
    }

    @Test
    void pinTogglesTheCurrentWaypointOnly() {
        NavigationTrail trail = new NavigationTrail();
        open(trail, "A.java");
        open(trail, "B.java");
        assertTrue(trail.togglePin());
        assertFalse(trail.waypoints().get(0).pinned());
        assertTrue(trail.waypoints().get(1).pinned());
        assertFalse(trail.togglePin());
    }
}
