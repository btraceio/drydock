package app.drydock.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{\"worktreesDirectory\":\"/old\",\"somethingElse\":42}");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        String written = Files.readString(configFile);
        assertTrue(written.contains("somethingElse"), written);
        assertTrue(written.contains("/tmp/worktrees"), written);
    }

    @Test
    void savingAnEmptyConfigClearsTheDirectory(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees"))), configFile);

        UserConfig.save(UserConfig.empty(), configFile);

        assertEquals(Optional.empty(), UserConfig.load(configFile).worktreesDirectory());
    }
}
