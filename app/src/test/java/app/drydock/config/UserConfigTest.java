package app.drydock.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
