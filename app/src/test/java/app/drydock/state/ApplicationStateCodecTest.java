package app.drydock.state;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.RepositoryId;
import app.drydock.state.json.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cosmetic UI fields must decode leniently: a malformed {@code
 * selectedRepositoryId} or {@code expandedRepositoryIds} entry must never
 * make the whole state file look corrupt (which would back it up and drop
 * every repository and session).
 */
class ApplicationStateCodecTest {

    private static final String REPO_ID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_ID = "99999999-8888-7777-6666-555555555555";

    private static String document(String uiJson) {
        return """
                {
                  "schemaVersion": 2,
                  "repositories": [
                    {
                      "id": "%s",
                      "root": "/tmp/repo",
                      "displayName": "repo",
                      "addedAt": "2026-01-01T00:00:00Z",
                      "lastOpenedAt": "2026-01-02T00:00:00Z",
                      "settings": {}
                    }
                  ],
                  "sessions": [],
                  "ui": %s
                }
                """.formatted(REPO_ID, uiJson);
    }

    @Test
    void malformedSelectedRepositoryIdDecodesToNoSelection() {
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(document(
                "{\"selectedRepositoryId\": \"not-a-uuid\", \"sidebarWidth\": 260.0, \"expandedRepositoryIds\": []}")));

        assertEquals(1, state.repositories().size());
        assertTrue(state.ui().selectedRepositoryId().isEmpty());
    }

    @Test
    void malformedExpandedRepositoryIdEntryIsSkippedOthersKept() {
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(document(
                "{\"selectedRepositoryId\": null, \"sidebarWidth\": 260.0,"
                        + " \"expandedRepositoryIds\": [\"not-a-uuid\", \"" + OTHER_ID + "\"]}")));

        assertEquals(1, state.repositories().size());
        assertEquals(1, state.ui().expandedRepositoryIds().size());
        assertTrue(state.ui().expandedRepositoryIds().contains(RepositoryId.of(OTHER_ID)));
    }

    @Test
    void wellFormedUiStillDecodes() {
        ApplicationState state = ApplicationStateCodec.fromJson(JsonParser.parse(document(
                "{\"selectedRepositoryId\": \"" + REPO_ID + "\", \"sidebarWidth\": 300.0,"
                        + " \"expandedRepositoryIds\": [\"" + REPO_ID + "\"]}")));

        assertEquals(RepositoryId.of(REPO_ID), state.ui().selectedRepositoryId().orElseThrow());
        assertEquals(300.0, state.ui().sidebarWidth());
        assertTrue(state.ui().expandedRepositoryIds().contains(RepositoryId.of(REPO_ID)));
    }
}
