package app.drydock.tools;

import app.drydock.config.UserConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the locator against a redirected {@code user.home} (so the
 * config override read/write lands in a temp dir) and an explicit-path
 * seam. PATH-based discovery is not forced either way: the override and
 * explicit tiers sit above it and are authoritative, so every assertion
 * here is deterministic regardless of what the host has installed.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ToolLocatorTest {

    @TempDir
    Path dir;

    private String originalUserHome;

    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        UserConfig.flushPendingSaves();
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
        UserConfig.flushPendingSaves();
    }

    private Path executable(String name) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        path.toFile().setExecutable(true);
        return path;
    }

    @Test
    void explicitPathIsAuthoritativeWhenValid() throws IOException {
        Path pi = executable("pi-under-test");
        assertEquals(Optional.of(pi), new ToolLocator(ExternalTool.PI, pi).locate());
    }

    @Test
    void explicitInvalidPathReportsNotFoundAndDoesNotFallBack() {
        Path missing = dir.resolve("no-such-pi");
        ToolLocator locator = new ToolLocator(ExternalTool.PI, missing);
        assertTrue(locator.locate().isEmpty());
        assertTrue(locator.describeSearched().contains("no-such-pi"));
    }

    @Test
    void configOverrideIsConsultedBeforeThePath() throws IOException {
        Path claude = executable("claude-under-test");
        UserConfig.updateAsync(config -> config.withToolPath("claude", Optional.of(claude))).join();

        assertEquals(Optional.of(claude), new ToolLocator(ExternalTool.CLAUDE).locate());
    }

    @Test
    void clearingTheOverrideInvalidatesTheCachedDiscovery() throws IOException {
        Path pi = executable("pi-under-test");
        UserConfig.updateAsync(config -> config.withToolPath("pi", Optional.of(pi))).join();

        ToolLocator locator = new ToolLocator(ExternalTool.PI);
        assertEquals(Optional.of(pi), locator.locate(), "primed with the override in place");

        // The save's epoch bump must make the SAME locator instance
        // re-discover: the temp dir is not on the PATH and not a
        // well-known location, so the override was the only way to it.
        UserConfig.updateAsync(config -> config.withToolPath("pi", Optional.empty())).join();
        assertNotEquals(Optional.of(pi), locator.locate(), "a cleared override must not pin the old path");
    }

    @Test
    void anOverrideThatIsNotAnExecutableFileIsSkipped() throws IOException {
        Path notExecutable = dir.resolve("claude-not-executable");
        Files.writeString(notExecutable, "text only");
        UserConfig.updateAsync(config -> config.withToolPath("claude", Optional.of(notExecutable))).join();

        ToolLocator locator = new ToolLocator(ExternalTool.CLAUDE);
        Optional<Path> resolved = locator.locate();
        // Either found elsewhere (PATH/well-known) or not found, but never
        // the non-executable file, and the searched description owns it.
        resolved.ifPresent(path -> assertNotEquals(notExecutable, path));
        assertTrue(locator.describeSearched().contains(notExecutable.toString()));
    }

    @Test
    void unknownConfigKeysArePreservedButNeverConsulted() throws IOException {
        Path fake = executable("whatever");
        UserConfig.updateAsync(config -> config.withToolPath("not-a-tool", Optional.of(fake))).join();

        assertEquals(Optional.of(fake), UserConfig.load().toolOverride("not-a-tool"),
                "an unknown key survives the round-trip");
        Optional<Path> resolved = new ToolLocator(ExternalTool.GIT).locate();
        resolved.ifPresent(path -> assertNotEquals(fake, path));
    }

    @Test
    void overrideServiceRefusesANonExecutablePathWithoutWritingIt() throws Exception {
        Path notExecutable = dir.resolve("git-not-executable");
        Files.writeString(notExecutable, "text only");

        ToolOverrideService.Outcome outcome = ToolOverrideService
                .saveAsync(ExternalTool.GIT, Optional.of(notExecutable)).get(5, TimeUnit.SECONDS);

        assertInstanceOf(ToolOverrideService.Outcome.Refused.class, outcome);
        assertTrue(UserConfig.load().toolOverride("git").isEmpty(),
                "a refused save must leave the config byte-unchanged");
    }

    @Test
    void overrideServiceSavesAValidPathAndReportsTheResolution() throws Exception {
        Path git = executable("git-under-test");

        ToolOverrideService.Outcome outcome = ToolOverrideService
                .saveAsync(ExternalTool.GIT, Optional.of(git)).get(5, TimeUnit.SECONDS);

        ToolOverrideService.Outcome.Saved saved = assertInstanceOf(
                ToolOverrideService.Outcome.Saved.class, outcome);
        assertEquals(Optional.of(git), saved.resolved());
        assertEquals(Optional.of(git), UserConfig.load().toolOverride("git"));

        // And clearing it through the service works too.
        ToolOverrideService.Outcome cleared = ToolOverrideService
                .saveAsync(ExternalTool.GIT, Optional.empty()).get(5, TimeUnit.SECONDS);
        assertInstanceOf(ToolOverrideService.Outcome.Saved.class, cleared);
        assertTrue(UserConfig.load().toolOverride("git").isEmpty());
    }

    @Test
    void overrideServiceLoadReportsBothOverrideAndResolution() throws Exception {
        ToolOverrideService.Snapshot before = ToolOverrideService
                .loadAsync(ExternalTool.CODEX).get(5, TimeUnit.SECONDS);
        assertTrue(before.override().isEmpty());

        Path codex = executable("codex-under-test");
        ToolOverrideService.saveAsync(ExternalTool.CODEX, Optional.of(codex)).get(5, TimeUnit.SECONDS);

        ToolOverrideService.Snapshot after = ToolOverrideService
                .loadAsync(ExternalTool.CODEX).get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of(codex), after.override());
        assertEquals(Optional.of(codex), after.resolved());
    }
}
