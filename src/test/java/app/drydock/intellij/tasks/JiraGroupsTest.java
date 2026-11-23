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
 * The storage contract of {@link JiraGroups}: one JSON array under one
 * property, a lenient read that never invents groups, and the id-keyed
 * mutations the add/edit/delete actions build on.
 */
class JiraGroupsTest {

    private static final RepositoryId ID = new RepositoryId(UUID.randomUUID());

    private static JiraGroup group(String id, String name, String jql) {
        return new JiraGroup(id, name, jql);
    }

    @Test
    void groupsRoundTripUnderOnePropertyKey() {
        JiraGroups groups = new JiraGroups(List.of(
                group("g-1", "Sprint", "project = DRY AND sprint in openSprints()"),
                group("g-2", "Ops \"watch\"", "labels = on-call ORDER BY updated DESC")));

        Map<String, String> store = new HashMap<>();
        groups.write(store::put, ID);

        assertEquals(Set.of("drydock.jira." + ID + ".groups"), store.keySet());
        assertEquals(groups, JiraGroups.read(store::get, ID));
    }

    @Test
    void anEmptyListPersistsAsAnEmptyArrayAndReadsBackEmpty() {
        Map<String, String> store = new HashMap<>();
        JiraGroups.EMPTY.write(store::put, ID);

        // The written form is the writer's own (pretty-printed) JSON; the
        // contract is that it reads back to the same empty list.
        assertTrue(store.get("drydock.jira." + ID + ".groups").contains("[]"));
        assertEquals(JiraGroups.EMPTY, JiraGroups.read(store::get, ID));
    }

    @Test
    void nothingStoredReadsEmptyAndUnreadableJsonReadsEmpty() {
        assertEquals(JiraGroups.EMPTY, JiraGroups.read(key -> null, ID));
        assertEquals(JiraGroups.EMPTY, JiraGroups.read(key -> "  ", ID));
        assertEquals(JiraGroups.EMPTY, JiraGroups.read(key -> "not json at all", ID));
        // A JSON value that is not an array is empty, never broken.
        assertEquals(JiraGroups.EMPTY, JiraGroups.read(key -> "{\"id\": \"g-1\"}", ID));
    }

    @Test
    void aMalformedEntryIsSkippedAndDuplicateIdsKeepTheirFirst() {
        String stored = "[{\"id\": \"g-1\", \"name\": \"Sprint\", \"jql\": \"project = DRY\"},"
                + "{\"id\": \"g-2\", \"name\": \"No query\"},"
                + "{\"name\": \"No id\", \"jql\": \"x\"},"
                + "{\"id\": \"g-1\", \"name\": \"Duplicate\", \"jql\": \"other\"},"
                + "\"not an object\"]";

        JiraGroups read = JiraGroups.read(key -> stored, ID);

        assertEquals(List.of(group("g-1", "Sprint", "project = DRY")), read.groups());
    }

    @Test
    void withAddedAppendsWithAFreshId() {
        JiraGroups groups = JiraGroups.EMPTY.withAdded("Sprint", "project = DRY").withAdded("Bugs", "labels = bug");

        assertEquals(2, groups.groups().size());
        assertEquals("Sprint", groups.groups().get(0).name());
        assertEquals("Bugs", groups.groups().get(1).name());
        assertTrue(groups.byId(groups.groups().get(0).id()).isPresent());
        assertEquals("project = DRY", groups.byId(groups.groups().get(0).id()).orElseThrow().jql());
    }

    @Test
    void withReplacedKeepsPositionAndId() {
        JiraGroups groups = JiraGroups.EMPTY.withAdded("Sprint", "project = DRY").withAdded("Bugs", "labels = bug");
        String sprintId = groups.groups().get(0).id();

        JiraGroups replaced = groups.withReplaced(group(sprintId, "Sprint 2", "sprint = 42"));

        assertEquals(2, replaced.groups().size());
        assertEquals(group(sprintId, "Sprint 2", "sprint = 42"), replaced.groups().get(0));
        assertEquals("Bugs", replaced.groups().get(1).name());
    }

    @Test
    void withoutRemovesOnlyTheNamedGroup() {
        JiraGroups groups = JiraGroups.EMPTY.withAdded("Sprint", "project = DRY").withAdded("Bugs", "labels = bug");
        String sprintId = groups.groups().get(0).id();

        assertEquals(List.of("Bugs"),
                groups.without(sprintId).groups().stream().map(JiraGroup::name).toList());
        // An unknown id changes nothing, and the instance stays shared.
        assertTrue(groups.without("no-such-id") == groups);
    }

    @Test
    void aGroupWithoutIdNameOrJqlIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new JiraGroup(" ", "Sprint", "project = DRY"));
        assertThrows(IllegalArgumentException.class, () -> new JiraGroup("g-1", "  ", "project = DRY"));
        assertThrows(IllegalArgumentException.class, () -> new JiraGroup("g-1", "Sprint", "  "));
    }

    @Test
    void aStoredGroupIsStrippedLikeANewOne() {
        JiraGroups read = JiraGroups.read(key ->
                "[{\"id\": \" g-1 \", \"name\": \" Sprint \", \"jql\": \" project = DRY \"}]", ID);

        assertEquals(List.of(group("g-1", "Sprint", "project = DRY")), read.groups());
    }

    @Test
    void byIdFindsOnlyTheDefinedGroup() {
        JiraGroups groups = JiraGroups.EMPTY.withAdded("Sprint", "project = DRY");
        String id = groups.groups().get(0).id();

        assertEquals(Optional.of(group(id, "Sprint", "project = DRY")), groups.byId(id));
        assertEquals(Optional.empty(), groups.byId("other"));
    }

    @Test
    void anEditOfAGroupNoLongerStoredSavesNothing() {
        JiraGroups groups = JiraGroups.EMPTY.withAdded("Sprint", "project = DRY");
        String a = groups.groups().get(0).id();
        JiraGroups deleted = groups.without(a);

        assertEquals(Optional.empty(), deleted.withEdited(group(a, "Sprint 2", "project = X")));

        // Another group's list is not touched by an edit of the missing one.
        JiraGroup b = group("g-b", "Bugs", "labels = bug");
        JiraGroups onlyB = new JiraGroups(List.of(b));
        assertEquals(Optional.empty(), onlyB.withEdited(group(a, "Sprint 2", "project = X")));
        assertEquals(List.of(b), onlyB.groups());
    }

    @Test
    void anEditReplacesInPlaceOnTheFreshListAndKeepsTheOthers() {
        JiraGroups groups = JiraGroups.EMPTY
                .withAdded("A", "project = A")
                .withAdded("B", "project = B")
                .withAdded("C", "project = C");
        JiraGroup a = groups.groups().get(0);
        JiraGroup b = groups.groups().get(1);
        JiraGroup c = groups.groups().get(2);
        // Another view changed b meanwhile; the edit lands on that fresh list.
        JiraGroup otherB = group(b.id(), "Other", "project = O");
        JiraGroups fresh = groups.withReplaced(otherB);

        Optional<JiraGroups> edited = fresh.withEdited(group(a.id(), "Renamed", "project = R"));

        assertTrue(edited.isPresent());
        assertEquals(List.of(group(a.id(), "Renamed", "project = R"), otherB, c), edited.get().groups());
    }

    @Test
    void withReplacedStillAppendsAnUnknownId() {
        // The resurrection guard lives in withEdited; withReplaced keeps its contract.
        List<JiraGroup> groups = JiraGroups.EMPTY.withReplaced(group("x", "N", "project = N")).groups();

        assertEquals(1, groups.size());
        assertEquals("x", groups.get(0).id());
    }
}
