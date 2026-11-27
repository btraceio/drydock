package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storage contract of {@link PrGroups}: one JSON array under one
 * property, a lenient read that skips malformed entries (including invalid
 * label filters), and the id-keyed mutations the add/edit/delete actions
 * build on.
 */
class PrGroupsTest {

    private static final RepositoryId ID = new RepositoryId(UUID.randomUUID());

    private static PrGroup group(String id, String name, String filter) {
        return new PrGroup(id, name, filter);
    }

    @Test
    void groupsRoundTripUnderOnePropertyKey() {
        PrGroups groups = new PrGroups(List.of(
                group("g-1", "Profiling", "comp: profiling & !tag: wip"),
                group("g-2", "Quoted \"ops\"", "\"a & b\" | c")));

        Map<String, String> store = new HashMap<>();
        groups.write(store::put, ID);

        assertEquals(Set.of("drydock.github." + ID + ".prGroups"), store.keySet());
        assertEquals(groups, PrGroups.read(store::get, ID));
    }

    @Test
    void anEmptyListPersistsAsAnEmptyArrayAndReadsBackEmpty() {
        Map<String, String> store = new HashMap<>();
        PrGroups.EMPTY.write(store::put, ID);

        assertTrue(store.get("drydock.github." + ID + ".prGroups").contains("[]"));
        assertEquals(PrGroups.EMPTY, PrGroups.read(store::get, ID));
    }

    @Test
    void nothingStoredReadsEmptyAndUnreadableJsonReadsEmpty() {
        assertEquals(PrGroups.EMPTY, PrGroups.read(key -> null, ID));
        assertEquals(PrGroups.EMPTY, PrGroups.read(key -> "  ", ID));
        assertEquals(PrGroups.EMPTY, PrGroups.read(key -> "not json at all", ID));
        assertEquals(PrGroups.EMPTY, PrGroups.read(key -> "{\"id\": \"g-1\"}", ID));
    }

    @Test
    void aMalformedOrInvalidFilterEntryIsSkippedAndDuplicateIdsKeepTheirFirst() {
        String stored = "[{\"id\": \"g-1\", \"name\": \"Profiling\", \"filter\": \"comp: profiling\"},"
                + "{\"id\": \"g-2\", \"name\": \"No filter\"},"
                + "{\"id\": \"g-3\", \"name\": \"Broken syntax\", \"filter\": \"comp: a & (unclosed\"},"
                + "{\"name\": \"No id\", \"filter\": \"bug\"},"
                + "{\"id\": \"g-1\", \"name\": \"Duplicate\", \"filter\": \"other\"},"
                + "\"not an object\"]";

        PrGroups read = PrGroups.read(key -> stored, ID);

        assertEquals(List.of(group("g-1", "Profiling", "comp: profiling")), read.groups());
    }

    @Test
    void withAddedAppendsWithAFreshId() {
        PrGroups groups = PrGroups.EMPTY
                .withAdded("Profiling", "comp: profiling")
                .withAdded("Bugs", "type: bug fix & !wip");

        assertEquals(2, groups.groups().size());
        assertEquals("Profiling", groups.groups().get(0).name());
        assertEquals("Bugs", groups.groups().get(1).name());
        assertTrue(groups.byId(groups.groups().get(0).id()).isPresent());
        assertEquals("comp: profiling", groups.byId(groups.groups().get(0).id()).orElseThrow().filter());
    }

    @Test
    void withReplacedKeepsPositionAndId() {
        PrGroups groups = PrGroups.EMPTY.withAdded("Profiling", "comp: profiling").withAdded("Bugs", "bug");
        String firstId = groups.groups().get(0).id();

        PrGroups replaced = groups.withReplaced(group(firstId, "Runtime", "comp: profiling | comp: jfr"));

        assertEquals(2, replaced.groups().size());
        assertEquals(group(firstId, "Runtime", "comp: profiling | comp: jfr"), replaced.groups().get(0));
        assertEquals("Bugs", replaced.groups().get(1).name());
    }

    @Test
    void withoutRemovesOnlyTheNamedGroup() {
        PrGroups groups = PrGroups.EMPTY.withAdded("Profiling", "comp: profiling").withAdded("Bugs", "bug");
        String firstId = groups.groups().get(0).id();

        assertEquals(List.of("Bugs"),
                groups.without(firstId).groups().stream().map(PrGroup::name).toList());
        assertTrue(groups.without("no-such-id") == groups);
    }

    @Test
    void aGroupWithoutIdNameOrValidFilterIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PrGroup(" ", "Profiling", "bug"));
        assertThrows(IllegalArgumentException.class, () -> new PrGroup("g-1", "  ", "bug"));
        assertThrows(IllegalArgumentException.class, () -> new PrGroup("g-1", "Profiling", "  "));
        assertThrows(IllegalArgumentException.class, () -> new PrGroup("g-1", "Profiling", "bug &"));
    }

    @Test
    void anEditOfAGroupNoLongerStoredSavesNothing() {
        PrGroups groups = PrGroups.EMPTY.withAdded("Profiling", "comp: profiling");
        String a = groups.groups().get(0).id();
        PrGroups deleted = groups.without(a);

        assertEquals(Optional.empty(), deleted.withEdited(group(a, "Renamed", "bug")));
    }

    @Test
    void anEditReplacesInPlaceOnTheFreshListAndKeepsTheOthers() {
        PrGroups groups = PrGroups.EMPTY
                .withAdded("A", "a")
                .withAdded("B", "b")
                .withAdded("C", "c");
        PrGroup a = groups.groups().get(0);
        PrGroup b = groups.groups().get(1);
        PrGroup c = groups.groups().get(2);
        PrGroup otherB = group(b.id(), "Other", "o");
        PrGroups fresh = groups.withReplaced(otherB);

        Optional<PrGroups> edited = fresh.withEdited(group(a.id(), "Renamed", "r"));

        assertTrue(edited.isPresent());
        assertEquals(List.of(group(a.id(), "Renamed", "r"), otherB, c), edited.get().groups());
    }
}
