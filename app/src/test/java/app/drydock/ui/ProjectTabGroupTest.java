package app.drydock.ui;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProjectTabGroup}: reference-counted badge-slot
 * allocation (distinct colors while the palette holds) and insertion
 * indices that keep same-project tabs contiguous.
 */
class ProjectTabGroupTest {

    private static final RepositorySettings SETTINGS = RepositorySettings.DEFAULT;
    private static final Instant NOW = Instant.now();
    private static final Path ROOT = Path.of("/repo");

    private final Repository repoA = repo("a", UUID.randomUUID());
    private final Repository repoB = repo("b", UUID.randomUUID());
    private final Repository repoC = repo("c", UUID.randomUUID());
    private final Repository repoD = repo("d", UUID.randomUUID());

    private static Repository repo(String name, UUID id) {
        return new Repository(new RepositoryId(id), ROOT, name, NOW, NOW, SETTINGS);
    }

    // ---- Badge-slot allocation ----------------------------------------------

    @Test
    void acquire_twoRepositories_getDistinctSlots() {
        ProjectTabGroup colors = new ProjectTabGroup();
        String a = colors.acquireBadgeStyle(repoA);
        String b = colors.acquireBadgeStyle(repoB);
        assertNotEquals(a, b);
    }

    @Test
    void acquire_sameRepository_reusesItsSlot() {
        ProjectTabGroup colors = new ProjectTabGroup();
        String first = colors.acquireBadgeStyle(repoA);
        String second = colors.acquireBadgeStyle(repoA);
        assertEquals(first, second);
    }

    @Test
    void acquire_upToPaletteSize_getsPairwiseDistinctSlots() {
        ProjectTabGroup colors = new ProjectTabGroup();
        Set<String> styles = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            styles.add(colors.acquireBadgeStyle(repo("r" + i, UUID.randomUUID())));
        }
        assertEquals(8, styles.size());
    }

    @Test
    void release_lastTabOfARepository_freesItsSlotForReuse() {
        ProjectTabGroup colors = new ProjectTabGroup();
        // A takes the lowest free slot (0); B takes the next (1).
        String a = colors.acquireBadgeStyle(repoA);
        colors.acquireBadgeStyle(repoB);
        colors.releaseBadgeStyle(repoA);

        assertEquals(a, colors.acquireBadgeStyle(repoC),
                "the freed slot is the lowest free one, so C must take A's old slot");
    }

    @Test
    void release_oneOfARepositorysTabs_keepsTheSlotHeld() {
        ProjectTabGroup colors = new ProjectTabGroup();
        colors.acquireBadgeStyle(repoA);
        String a = colors.acquireBadgeStyle(repoA);  // two tabs of A
        colors.releaseBadgeStyle(repoA);                            // one tab left

        assertNotEquals(a, colors.acquireBadgeStyle(repoD),
                "A still holds its slot, so D must take another");
    }

    @Test
    void release_neverAcquiredRepository_leavesAllocatedSlotsAlone() {
        ProjectTabGroup colors = new ProjectTabGroup();
        String a = colors.acquireBadgeStyle(repoA);
        colors.releaseBadgeStyle(repoB);  // B was never acquired: no-op
        assertNotEquals(a, colors.acquireBadgeStyle(repoC),
                "releasing never-acquired B must not free A's slot");
    }

    @Test
    void acquire_moreRepositoriesThanPaletteSlots_degradesToValidStyles() {
        ProjectTabGroup colors = new ProjectTabGroup();
        for (int i = 0; i < 8; i++) {
            colors.acquireBadgeStyle(repo("r" + i, UUID.randomUUID()));
        }
        // Palette exhausted; the 9th onward must still get a valid style.
        for (int i = 8; i < 16; i++) {
            String style = colors.acquireBadgeStyle(repo("r" + i, UUID.randomUUID()));
            assertTrue(style.startsWith("project-badge-"), style);
        }
    }

    // ---- Insertion index ------------------------------------------------------

    @Test
    void insertionIndexFor_noRepository_appendsAtEnd() {
        List<String> tabs = List.of("t1", "t2");
        int index = ProjectTabGroup.insertionIndexFor(tabs, Optional.empty(), this::resolve);
        assertEquals(2, index);
    }

    @Test
    void insertionIndexFor_newProject_appendsAtEnd() {
        List<String> tabs = List.of("a1", "a2");
        Map<String, Optional<Repository>> map = Map.of("a1", Optional.of(repoA), "a2", Optional.of(repoA));
        int index = ProjectTabGroup.insertionIndexFor(tabs, Optional.of(repoB), map::get);
        assertEquals(2, index);
    }

    @Test
    void insertionIndexFor_existingProject_goesAfterLastExisting() {
        List<String> tabs = List.of("a1", "b1", "a2");
        Map<String, Optional<Repository>> map = new HashMap<>();
        map.put("a1", Optional.of(repoA));
        map.put("b1", Optional.of(repoB));
        map.put("a2", Optional.of(repoA));
        int index = ProjectTabGroup.insertionIndexFor(tabs, Optional.of(repoA), map::get);
        assertEquals(3, index);
    }

    @Test
    void insertionIndexFor_preservesOrderWithinProjectBlock() {
        List<String> tabs = new ArrayList<>(List.of("a1", "a2"));
        Map<String, Optional<Repository>> map = new HashMap<>();
        map.put("a1", Optional.of(repoA));
        map.put("a2", Optional.of(repoA));

        int first = ProjectTabGroup.insertionIndexFor(tabs, Optional.of(repoA), map::get);
        assertEquals(2, first);
        tabs.add(first, "a3");
        map.put("a3", Optional.of(repoA));

        int second = ProjectTabGroup.insertionIndexFor(tabs, Optional.of(repoA), map::get);
        assertEquals(3, second);
    }

    private Optional<Repository> resolve(String tab) {
        return Optional.empty();
    }
}