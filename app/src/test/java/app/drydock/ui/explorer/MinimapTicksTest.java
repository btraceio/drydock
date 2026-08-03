package app.drydock.ui.explorer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delta's "minimap ticks match {@code _changedSet} and finding anchors
 * exactly (write the test)" -- this is that test.
 */
class MinimapTicksTest {

    private static final String SOURCE = """
            class Sidebar {

                int width() {
                    return clamp(raw);
                }

                void onRelease(MouseEvent e) {
                    widthProperty.set(tracker.raw());
                    tracker = null;
                    // filter is never removed
                }

                void layoutChildren() {
                    double w = width();
                }
            }
            """;

    private final SourceOutline outline = SourceOutline.parse(SOURCE);

    private static Set<Integer> lines(List<MinimapTicks.Tick> ticks, MinimapTicks.Kind kind) {
        return ticks.stream().filter(tick -> tick.kind() == kind)
                .map(MinimapTicks.Tick::line).collect(Collectors.toSet());
    }

    @Test
    void everyChangedLineIsCoveredAndNoTickSitsOutsideTheChangedSet() {
        Set<Integer> changed = Set.of(8, 9, 10, 13);
        List<MinimapTicks.Tick> ticks = MinimapTicks.compute(outline, changed, List.of(), Set.of(), Set.of());

        Set<Integer> green = lines(ticks, MinimapTicks.Kind.CHANGED);
        assertEquals(Set.of(8, 13), green, "one tick per contiguous run, at its first line");
        assertTrue(changed.containsAll(green), "no green tick may point outside the changed set");
        for (int line : changed) {
            assertTrue(green.stream().anyMatch(start -> start <= line
                            && changed.contains(line)
                            && runContains(changed, start, line)),
                    "changed line " + line + " is covered by a tick's run");
        }
    }

    private static boolean runContains(Set<Integer> changed, int start, int line) {
        for (int at = start; at <= line; at++) {
            if (!changed.contains(at)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void findingAnchorsGetExactlyOneTickEach() {
        List<ExplorerFinding> findings = List.of(new ExplorerFinding(8, "leak"), new ExplorerFinding(13, "clamp"));
        List<MinimapTicks.Tick> ticks = MinimapTicks.compute(outline, Set.of(), findings, Set.of(), Set.of());
        assertEquals(2, ticks.size());
        assertEquals(Set.of(8, 13), lines(ticks, MinimapTicks.Kind.FINDING));
        assertTrue(ticks.get(0).tooltip().contains("finding: leak"), ticks.get(0).tooltip());
        assertTrue(ticks.get(0).tooltip().contains("onRelease"), "the tooltip names the member");
    }

    @Test
    void ticksAreProportionalToTheFileNotToTheMembers() {
        List<MinimapTicks.Tick> ticks =
                MinimapTicks.compute(outline, Set.of(1, outline.lineCount()), List.of(), Set.of(), Set.of());
        assertEquals(0.0, ticks.get(0).position(), 1e-9);
        assertTrue(ticks.get(1).position() > 0.9, "the last line sits at the bottom of the strip");
    }

    @Test
    void searchHitsAreTheSameLinesTheRailWouldMatch() {
        Set<Integer> hits = MinimapTicks.searchHits(SOURCE, "tracker");
        assertEquals(Set.of(8, 9), hits);
        assertTrue(MinimapTicks.searchHits(SOURCE, "  ").isEmpty(), "a blank query hits nothing");
        assertEquals(Set.of(4), MinimapTicks.searchHits(SOURCE, "CLAMP"), "matching is case-insensitive");
    }

    @Test
    void readMembersGetTheirOwnTicks() {
        List<MinimapTicks.Tick> ticks =
                MinimapTicks.compute(outline, Set.of(), List.of(), Set.of(), Set.of(3));
        assertEquals(1, ticks.size());
        assertEquals(MinimapTicks.Kind.READ, ticks.get(0).kind());
        assertTrue(ticks.get(0).tooltip().endsWith("— read"));
    }

    @Test
    void aLineKeyOnlyResolvesForThePostImage() {
        assertEquals(118, ExplorerFinding.lineOfKey("n118").orElseThrow());
        assertTrue(ExplorerFinding.lineOfKey("o118").isEmpty(), "a deleted line has no row to sit on");
        assertTrue(ExplorerFinding.lineOfKey("nope").isEmpty());
        assertTrue(ExplorerFinding.lineOfKey(null).isEmpty());
    }
}
