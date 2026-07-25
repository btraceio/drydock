package app.drydock.config;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserConfigTest {

    @Test
    void loadReturnsEmptyWhenTheConfigFileIsMissing(@TempDir Path dir) {
        UserConfig config = UserConfig.load(dir.resolve("config.json"));

        assertTrue(config.worktreesDirectory().isEmpty());
    }

    @Test
    void loadReadsTheConfiguredWorktreesDirectory(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{\"worktreesDirectory\": \"" + dir.resolve("wt") + "\"}");

        UserConfig config = UserConfig.load(configFile);

        assertEquals(Optional.of(dir.resolve("wt").toAbsolutePath().normalize()), config.worktreesDirectory());
    }

    @Test
    void loadIgnoresMalformedJsonInsteadOfThrowing(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{not valid json");

        UserConfig config = UserConfig.load(configFile);

        assertTrue(config.worktreesDirectory().isEmpty());
    }

    @Test
    void loadIgnoresATopLevelJsonArrayInsteadOfThrowing(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "[1, 2, 3]");

        UserConfig config = UserConfig.load(configFile);

        assertTrue(config.worktreesDirectory().isEmpty());
    }

    @Test
    void loadIgnoresANonStringWorktreesDirectory(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{\"worktreesDirectory\": 42}");

        UserConfig config = UserConfig.load(configFile);

        assertTrue(config.worktreesDirectory().isEmpty());
    }

    @Test
    // No parallelism is configured today (see the note above the saveAsync
    // tests below), but this reads the developer's real ~/.drydock/config.json
    // via "user.home" -- the same global the saveAsync tests below repoint --
    // so it stays behind the same lock rather than depending on that staying
    // true.
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void loadAsyncReturnsTheSameResultAsLoadOffTheCallingThread() throws Exception {
        UserConfig config = UserConfig.loadAsync().get();

        assertEquals(UserConfig.load(), config);
    }

    @Test
    void savedConfigLoadsBackUnchanged(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        assertEquals(Optional.of(Path.of("/tmp/worktrees")), UserConfig.load(configFile).worktreesDirectory());
    }

    @Test
    void saveCreatesTheParentDirectory(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("nested").resolve(".drydock").resolve("config.json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        assertTrue(Files.exists(configFile));
    }

    @Test
    void saveOverwritesAMalformedFileAndLeavesNoTempBehind(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{ this is not json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        assertEquals(Optional.of(Path.of("/tmp/worktrees")), UserConfig.load(configFile).worktreesDirectory());
        try (var entries = Files.list(tempDir)) {
            assertEquals(List.of("config.json"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void savePreservesMembersItDoesNotKnowAbout(@TempDir Path tempDir) throws Exception {
        // The file is human-editable and may grow keys this build predates;
        // rewriting it must not silently delete the user's other settings.
        // A plain substring check would still pass if the value were
        // mangled or the key duplicated, so parse the result and check the
        // actual structure instead.
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{\"worktreesDirectory\":\"/old\",\"somethingElse\":42}");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        String written = Files.readString(configFile);
        JsonValue parsed = JsonParser.parse(written);
        JsonObject root = assertInstanceOf(JsonObject.class, parsed, written);
        assertEquals(new JsonNumber("42"), root.get("somethingElse"), written);
        assertEquals(new JsonString(Path.of("/tmp/worktrees").toString()), root.get("worktreesDirectory"), written);
        assertEquals(1, written.split("worktreesDirectory", -1).length - 1,
                "worktreesDirectory must appear exactly once: " + written);
    }

    @Test
    void savingAnEmptyConfigClearsTheDirectory(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        UserConfig.save(UserConfig.empty(), configFile);

        assertEquals(Optional.empty(), UserConfig.load(configFile).worktreesDirectory());
    }

    // ---- saveAsync / flushPendingSaves ----
    //
    // saveAsync always writes to UserConfig.defaultConfigFile(), which is
    // derived from the "user.home" system property, so these tests point
    // that property at a @TempDir for their duration. PENDING_SAVE and
    // SAVE_EXECUTOR are static -- shared by the whole test JVM -- so every
    // test below flushes in a finally block before restoring the property
    // and letting its @TempDir be deleted; otherwise a write queued by one
    // test could still be in flight (or land after) when the next test's
    // directory no longer exists.

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void racingSaveAsyncCallsLeaveTheLastValueOnDiskAfterFlush(@TempDir Path tempDir) throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            UserConfig.saveAsync(new UserConfig(Optional.of(Path.of("/tmp/worktrees-A"))));
            UserConfig.saveAsync(new UserConfig(Optional.of(Path.of("/tmp/worktrees-B"))));
            UserConfig.flushPendingSaves();

            assertEquals(Optional.of(Path.of("/tmp/worktrees-B")),
                    UserConfig.load(UserConfig.defaultConfigFile()).worktreesDirectory());
        } finally {
            UserConfig.flushPendingSaves();
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void flushPendingSavesWaitsForEveryQueuedSaveBeforeReturning(@TempDir Path tempDir) throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            int calls = 50;
            for (int i = 0; i < calls; i++) {
                UserConfig.saveAsync(new UserConfig(Optional.of(Path.of("/tmp/worktrees-" + i))));
            }
            UserConfig.flushPendingSaves();

            // If flushPendingSaves returned before every queued save had
            // actually finished writing, a straggler could still overwrite
            // the file after this point with something other than the
            // last-issued value -- so the file's content read right here,
            // with no waiting or retrying, must already be final.
            assertEquals(Optional.of(Path.of("/tmp/worktrees-" + (calls - 1))),
                    UserConfig.load(UserConfig.defaultConfigFile()).worktreesDirectory());
        } finally {
            UserConfig.flushPendingSaves();
            System.setProperty("user.home", originalUserHome);
        }
    }
}
