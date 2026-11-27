package app.drydock.intellij.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService.PullRequestListing;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

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
 * The user-defined pull-request subgroups of one registered Drydock project:
 * named folders under the tree's Pull Requests section, each querying GitHub
 * with its own boolean label filter. Stored as one JSON array under
 * {@code drydock.github.<RepositoryId>.prGroups} beside the GitHub settings
 * ({@link GitHubProjectSettings}).
 *
 * <p>When a project defines no PR groups, its Pull Requests section keeps the
 * flat list of open pull requests. When one or more PR groups are defined,
 * the Pull Requests section shows those groups in definition order followed
 * by a lazy {@code All} folder.</p>
 *
 * <p>The readers are lenient, matching {@link JiraGroups}: an unreadable
 * stored value is read as empty, a malformed entry (including an unparseable
 * label filter) is skipped, and duplicate ids keep their first occurrence.</p>
 */
record PrGroups(List<PrGroup> groups) {

    private static final Logger LOG = System.getLogger(PrGroups.class.getName());

    private static final String PREFIX = "drydock.github.";

    PrGroups {
        groups = List.copyOf(groups);
    }

    /** The always-empty list, so callers never build one. */
    static final PrGroups EMPTY = new PrGroups(List.of());

    /** Lenient read of the stored JSON array; anything unreadable is empty. */
    static PrGroups read(Function<String, String> get, RepositoryId id) {
        String stored = get.apply(PREFIX + id + ".prGroups");
        if (stored == null || stored.isBlank()) {
            return EMPTY;
        }
        List<PrGroup> parsed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            if (!(JsonParser.parse(stored) instanceof JsonArray array)) {
                return EMPTY;
            }
            for (JsonValue element : array.elements()) {
                if (!(element instanceof JsonObject object)
                        || !(object.get("id") instanceof JsonString id1)
                        || !(object.get("name") instanceof JsonString name)
                        || !(object.get("filter") instanceof JsonString filter)) {
                    LOG.log(Level.WARNING, "Skipping a malformed stored PR group");
                    continue;
                }
                try {
                    PrGroup group = new PrGroup(id1.value(), name.value(), filter.value());
                    if (seen.add(group.id())) {
                        parsed.add(group);
                    }
                } catch (IllegalArgumentException e) {
                    LOG.log(Level.WARNING, "Skipping a malformed stored PR group");
                }
            }
        } catch (JsonParseException e) {
            LOG.log(Level.WARNING, "Stored PR groups are not readable JSON; reading none");
            return EMPTY;
        }
        return parsed.isEmpty() ? EMPTY : new PrGroups(parsed);
    }

    /** Writes exactly the one non-secret key; an empty list persists as {@code []}. */
    void write(BiConsumer<String, String> put, RepositoryId id) {
        List<JsonValue> elements = new ArrayList<>();
        for (PrGroup group : groups) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("id", new JsonString(group.id()));
            members.put("name", new JsonString(group.name()));
            members.put("filter", new JsonString(group.filter()));
            elements.add(new JsonObject(members));
        }
        put.accept(PREFIX + id + ".prGroups", JsonWriter.write(JsonArray.of(elements)));
    }

    static PrGroups load(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        return read(properties::getValue, id);
    }

    void save(RepositoryId id) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        write(properties::setValue, id);
    }

    /** The group with this id, if defined. */
    Optional<PrGroup> byId(String groupId) {
        return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
    }

    /** This list plus one new group (fresh id, appended last). */
    PrGroups withAdded(String name, String filter) {
        List<PrGroup> next = new ArrayList<>(groups);
        next.add(new PrGroup(UUID.randomUUID().toString(), name, filter));
        return new PrGroups(next);
    }

    /** This list with {@code updated} in its old position, matched by id; unknown ids append. */
    PrGroups withReplaced(PrGroup updated) {
        List<PrGroup> next = new ArrayList<>();
        boolean found = false;
        for (PrGroup group : groups) {
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
        return new PrGroups(next);
    }

    /**
     * This list with {@code updated} in its old position, or empty when the
     * stored list no longer holds its id: an edit never brings back a group
     * deleted while it was being edited (only {@link #withAdded} creates ids).
     */
    Optional<PrGroups> withEdited(PrGroup updated) {
        return byId(updated.id()).isPresent() ? Optional.of(withReplaced(updated)) : Optional.empty();
    }

    /** This list without the group of {@code groupId}; an unknown id changes nothing. */
    PrGroups without(String groupId) {
        List<PrGroup> next = groups.stream().filter(group -> !group.id().equals(groupId)).toList();
        return next.size() == groups.size() ? this : new PrGroups(next);
    }

    /**
     * One PR subgroup and what its filtered query answered, as the tree model
     * reads it. An empty {@code listing} means there is no answer yet: the
     * subgroup was never opened (shows "Not loaded") or its first query is
     * still running (shows a loading child).
     */
    record Listing(PrGroup group, Optional<PullRequestListing> listing) {
        public Listing {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(listing, "listing");
        }
    }
}
