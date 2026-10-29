package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.git.JiraIssueListing;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonWriter;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import com.intellij.ide.util.PropertiesComponent;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The user-defined Jira groups of one registered Drydock project: named
 * subnodes of the tree's Issues section, each querying Jira with its own
 * JQL. Stored as one JSON array under {@code drydock.jira.<RepositoryId>.
 * groups} beside the connection settings ({@link JiraProjectSettings}) —
 * a group's name and query are not secrets, so nothing here may ever hold
 * or need the token.
 *
 * <p>Since groups are the only place Jira rows appear, an empty {@code
 * JiraGroups} means the project's Issues section shows no Jira content at
 * all, and the gather sends no Jira request for it.</p>
 *
 * <p>The readers are lenient, in the same spirit as the settings record:
 * a stored value that is not a JSON array of well-formed group objects is
 * read as empty, and a malformed entry inside an otherwise readable array
 * is skipped, never a reason to declare the whole list broken. Duplicate
 * ids keep their first occurrence.</p>
 */
record JiraGroups(List<JiraGroup> groups) {

    private static final Logger LOG = System.getLogger(JiraGroups.class.getName());

    private static final String PREFIX = "drydock.jira.";

    JiraGroups {
        groups = List.copyOf(groups);
    }

    /** The always-empty list, so callers never build one. */
    static final JiraGroups EMPTY = new JiraGroups(List.of());

    /** Lenient read of the stored JSON array; anything unreadable is empty. */
    static JiraGroups read(Function<String, String> get, RepositoryId id) {
        String stored = get.apply(PREFIX + id + ".groups");
        if (stored == null || stored.isBlank()) {
            return EMPTY;
        }
        List<JiraGroup> parsed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            if (!(JsonParser.parse(stored) instanceof JsonArray array)) {
                return EMPTY;
            }
            for (JsonValue element : array.elements()) {
                if (!(element instanceof JsonObject object)
                        || !(object.get("id") instanceof JsonString id1)
                        || !(object.get("name") instanceof JsonString name)
                        || !(object.get("jql") instanceof JsonString jql)) {
                    LOG.log(Level.WARNING, "Skipping a malformed stored Jira group");
                    continue;
                }
                try {
                    JiraGroup group = new JiraGroup(id1.value(), name.value(), jql.value());
                    if (seen.add(group.id())) {
                        parsed.add(group);
                    }
                } catch (IllegalArgumentException e) {
                    LOG.log(Level.WARNING, "Skipping a malformed stored Jira group");
                }
            }
        } catch (JsonParseException e) {
            LOG.log(Level.WARNING, "Stored Jira groups are not readable JSON; reading none");
            return EMPTY;
        }
        return parsed.isEmpty() ? EMPTY : new JiraGroups(parsed);
    }

    /** Writes exactly the one non-secret key; an empty list persists as {@code []}. */
    void write(BiConsumer<String, String> put, RepositoryId id) {
        List<JsonValue> elements = new ArrayList<>();
        for (JiraGroup group : groups) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("id", new JsonString(group.id()));
            members.put("name", new JsonString(group.name()));
            members.put("jql", new JsonString(group.jql()));
            elements.add(new JsonObject(members));
        }
        put.accept(PREFIX + id + ".groups", JsonWriter.write(JsonArray.of(elements)));
    }

    static JiraGroups load(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        return read(properties::getValue, id);
    }

    void save(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        write(properties::setValue, id);
    }

    /** The group with this id, if defined. */
    Optional<JiraGroup> byId(String groupId) {
        return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
    }

    /** This list plus one new group (fresh id, appended last). */
    JiraGroups withAdded(String name, String jql) {
        List<JiraGroup> next = new ArrayList<>(groups);
        next.add(new JiraGroup(UUID.randomUUID().toString(), name, jql));
        return new JiraGroups(next);
    }

    /** This list with {@code updated} in its old position, matched by id; unknown ids append. */
    JiraGroups withReplaced(JiraGroup updated) {
        List<JiraGroup> next = new ArrayList<>();
        boolean found = false;
        for (JiraGroup group : groups) {
            if (group.id().equals(updated.id())) {
                next.add(updated);
                found = true;
            } else {
                next.add(group);
            }
        }
        if (!found) {
            next.add(updated);
        }
        return new JiraGroups(next);
    }

    /**
     * This list with {@code updated} in its old position, or empty when the
     * stored list no longer holds its id: an edit never brings back a group
     * deleted while it was being edited (only {@link #withAdded} creates ids).
     */
    Optional<JiraGroups> withEdited(JiraGroup updated) {
        return byId(updated.id()).isPresent() ? Optional.of(withReplaced(updated)) : Optional.empty();
    }

    /** This list without the group of {@code groupId}; an unknown id changes nothing. */
    JiraGroups without(String groupId) {
        List<JiraGroup> next = groups.stream().filter(group -> !group.id().equals(groupId)).toList();
        return next.size() == groups.size() ? this : new JiraGroups(next);
    }

    /**
     * One group and what its query answered, as the tree model reads it.
     * An empty {@code listing} means the query has not run for this tree
     * state yet (the section was never opened and the view does not ask
     * for listings) — the node shows a loading child, not an empty one.
     */
    record Listing(JiraGroup group, Optional<JiraIssueListing> listing) {
        public Listing {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(listing, "listing");
        }
    }
}
