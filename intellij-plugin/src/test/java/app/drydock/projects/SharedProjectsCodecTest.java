package app.drydock.projects;

import app.drydock.domain.RepositoryId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedProjectsCodecTest {

    @Test
    void schemaVersionIsWritten() {
        String json = SharedProjectsCodec.toJson(List.of());

        JsonObject root = (JsonObject) JsonParser.parse(json);
        assertEquals(1, ((JsonNumber) root.get("schemaVersion")).asInt(), json);
    }

    @Test
    void unknownTopLevelAndEntryMembersAreIgnored() {
        RepositoryId id = RepositoryId.newId();
        String json = """
                {"schemaVersion":1,"writtenBy":"a future version","projects":[
                  {"id":"%s","root":"/w/a","displayName":"a","addedAt":"2026-10-19T10:00:00Z","color":"blue"}
                ]}""".formatted(id);

        List<SharedProject> projects = SharedProjectsCodec.fromJson(json);

        assertEquals(List.of(SharedProject.local(id, Path.of("/w/a"), "a", Instant.parse("2026-10-19T10:00:00Z"))),
                projects);
    }
}
