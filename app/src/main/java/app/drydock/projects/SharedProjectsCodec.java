package app.drydock.projects;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The JSON form of the shared projects list:
 * {@code {"schemaVersion":1,"projects":[{"id","root","displayName","addedAt","remote":{"host","path"}}]}},
 * {@code remote} only for remote projects.
 *
 * <p>Two tools, possibly of different versions, read this file, and a user
 * may edit it by hand. So decoding is lenient where it can be: unknown
 * members are ignored and a malformed entry is skipped with a warning,
 * keeping the others. Only a file that is not a JSON object with a
 * {@code projects} array is malformed as a whole.</p>
 */
public final class SharedProjectsCodec {

    private static final Logger LOG = System.getLogger(SharedProjectsCodec.class.getName());

    static final int SCHEMA_VERSION = 1;

    private SharedProjectsCodec() {
    }

    public static String toJson(List<SharedProject> projects) {
        List<JsonValue> entries = new ArrayList<>();
        for (SharedProject project : projects) {
            JsonObject entry = JsonObject.empty()
                    .put("id", new JsonString(project.id().toString()))
                    .put("root", new JsonString(project.root().toString()))
                    .put("displayName", new JsonString(project.displayName()))
                    .put("addedAt", new JsonString(project.addedAt().toString()));
            project.remote().ifPresent(remote -> entry.put("remote", JsonObject.empty()
                    .put("host", new JsonString(remote.host()))
                    .put("path", new JsonString(remote.remotePath()))));
            entries.add(entry);
        }
        return JsonWriter.write(JsonObject.empty()
                .put("schemaVersion", JsonNumber.of(SCHEMA_VERSION))
                .put("projects", JsonArray.of(entries)));
    }

    /**
     * A decoded file: the projects that could be read, how many entries
     * could not, and the schema version it declares (0 when it declares
     * none).
     */
    public record Decoded(List<SharedProject> projects, int skipped, int schemaVersion) {
        public Decoded {
            projects = List.copyOf(projects);
        }
    }

    /** Decodes the list; throws {@link JsonParseException} only when the file as a whole is unusable. */
    public static List<SharedProject> fromJson(String json) {
        return decode(json).projects();
    }

    /** As {@link #fromJson}, also reporting what was skipped and the declared schema version. */
    public static Decoded decode(String json) {
        if (!(JsonParser.parse(json) instanceof JsonObject root)
                || !(root.get("projects") instanceof JsonArray array)) {
            throw new JsonParseException("Expected an object with a \"projects\" array");
        }
        int schemaVersion = root.get("schemaVersion") instanceof JsonNumber number ? number.asInt() : 0;
        List<SharedProject> projects = new ArrayList<>();
        int skipped = 0;
        for (JsonValue element : array.elements()) {
            Optional<SharedProject> project = decode(element);
            if (project.isPresent()) {
                projects.add(project.get());
            } else {
                skipped++;
            }
        }
        return new Decoded(projects, skipped, schemaVersion);
    }

    private static Optional<SharedProject> decode(JsonValue element) {
        try {
            if (!(element instanceof JsonObject entry)) {
                throw new IllegalArgumentException("not an object");
            }
            Optional<SshRemote> remote = Optional.empty();
            if (entry.get("remote") instanceof JsonObject remoteObject) {
                remote = Optional.of(new SshRemote(string(remoteObject, "host"), string(remoteObject, "path")));
            }
            return Optional.of(new SharedProject(RepositoryId.of(string(entry, "id")), Path.of(string(entry, "root")),
                    string(entry, "displayName"), Instant.parse(string(entry, "addedAt")), remote));
        } catch (IllegalArgumentException | DateTimeException e) {
            LOG.log(Level.WARNING, () -> "Skipping a malformed shared project entry: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static String string(JsonObject object, String key) {
        if (object.get(key) instanceof JsonString value) {
            return value.value();
        }
        throw new IllegalArgumentException("missing \"" + key + "\"");
    }
}
