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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees")), true), configFile);

        assertEquals(Optional.of(Path.of("/tmp/worktrees")), UserConfig.load(configFile).worktreesDirectory());
    }

    @Test
    void saveCreatesTheParentDirectory(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("nested").resolve(".drydock").resolve("config.json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees")), true), configFile);

        assertTrue(Files.exists(configFile));
    }

    @Test
    void saveOverwritesAMalformedFileAndLeavesNoTempBehind(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{ this is not json");

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees")), true), configFile);

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

        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees")), true), configFile);

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
        UserConfig.save(new UserConfig(Optional.of(Path.of("/tmp/worktrees")), true), configFile);

        UserConfig.save(UserConfig.empty(), configFile);

        assertEquals(Optional.empty(), UserConfig.load(configFile).worktreesDirectory());
    }

    @Test
    void skimDefaultsOnAndSurvivesAWorktreesDirectoryEdit(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        assertTrue(UserConfig.load(configFile).openChangedFilesInSkim(),
                "a missing config still opens changed files folded — that is the delta's default");

        UserConfig.save(new UserConfig(Optional.empty(), false), configFile);
        assertFalse(UserConfig.load(configFile).openChangedFilesInSkim());

        // The read-modify-write the settings modal must do.
        UserConfig existing = UserConfig.load(configFile);
        UserConfig.save(new UserConfig(Optional.of(dir), existing.openChangedFilesInSkim(),
                existing.languageServer()), configFile);
        UserConfig reloaded = UserConfig.load(configFile);
        assertEquals(Optional.of(dir), reloaded.worktreesDirectory());
        assertFalse(reloaded.openChangedFilesInSkim(), "…and the skim preference is still off");
    }

    // ---- languageServer component ----

    @Test
    void languageServerRoundTripsJdtHomeAndJavaHome(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        UserConfig.LanguageServer server = new UserConfig.LanguageServer(
                Optional.of(dir.resolve("jdt.ls")), Optional.of(dir.resolve("jdk")));

        UserConfig.save(new UserConfig(Optional.empty(), true, server), configFile);

        UserConfig.LanguageServer loaded = UserConfig.load(configFile).languageServer();
        assertEquals(Optional.of(dir.resolve("jdt.ls").toAbsolutePath().normalize()), loaded.jdtHome());
        assertEquals(Optional.of(dir.resolve("jdk").toAbsolutePath().normalize()), loaded.javaHome());
    }

    @Test
    void languageServerRoundTripsJdtHomeOnly(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");

        UserConfig.save(new UserConfig(Optional.empty(), true,
                new UserConfig.LanguageServer(Optional.of(dir.resolve("jdt.ls")), Optional.empty())),
                configFile);

        UserConfig.LanguageServer loaded = UserConfig.load(configFile).languageServer();
        assertEquals(Optional.of(dir.resolve("jdt.ls").toAbsolutePath().normalize()), loaded.jdtHome());
        assertTrue(loaded.javaHome().isEmpty(), "an absent javaHome stays absent, not null-in-JSON");
    }

    @Test
    void languageServerDefaultsToUnconfiguredWhenTheFileHasNoComponent(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{\"worktreesDirectory\":\"/tmp/wt\"}");

        UserConfig.LanguageServer loaded = UserConfig.load(configFile).languageServer();

        assertTrue(loaded.jdtHome().isEmpty());
        assertTrue(loaded.javaHome().isEmpty());
        assertEquals(UserConfig.LanguageServer.empty(), loaded);
    }

    @Test
    void anUnconfiguredLanguageServerIsClearedRatherThanWrittenAsAnEmptyObject(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{\"languageServer\":{\"jdtHome\":\"/old\"}}");

        UserConfig.save(new UserConfig(Optional.empty(), true, UserConfig.LanguageServer.empty()), configFile);

        String written = Files.readString(configFile);
        assertFalse(JsonParser.parse(written) instanceof JsonObject root && root.has("languageServer"),
                "no key must be left behind for an unconfigured tier: " + written);
    }

    @Test
    void savePreservesUnknownMembersInsideTheLanguageServerObject(@TempDir Path dir) throws Exception {
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{\"somethingElse\":42,\"languageServer\":"
                + "{\"jdtHome\":\"/old\",\"futureKey\":\"keep\"}}");

        UserConfig.save(new UserConfig(Optional.empty(), true, new UserConfig.LanguageServer(
                Optional.of(dir.resolve("jdt.ls")), Optional.empty())), configFile);

        String written = Files.readString(configFile);
        JsonObject root = assertInstanceOf(JsonObject.class, JsonParser.parse(written), written);
        assertEquals(new JsonNumber("42"), root.get("somethingElse"), written);
        JsonObject server = assertInstanceOf(JsonObject.class, root.get("languageServer"), written);
        assertEquals(new JsonString("keep"), server.get("futureKey"),
                "the hand-editable object keeps members this build does not know about: " + written);
        assertEquals(new JsonString(dir.resolve("jdt.ls").toString()), server.get("jdtHome"), written);
        assertFalse(server.has("javaHome"), written);
    }

    @Test
    void aMalformedLanguageServerIsSkippedNotAConfigFileFailure(@TempDir Path dir) throws Exception {
        // Each shape must leave the OTHER settings intact: a malformed
        // component may never take the whole file down with it.
        Path configFile = dir.resolve("config.json");
        Files.writeString(configFile, "{\"worktreesDirectory\":\"" + dir.resolve("wt")
                + "\",\"languageServer\":\"/not/an/object\"}");
        assertEquals(Optional.of(dir.resolve("wt").toAbsolutePath().normalize()),
                UserConfig.load(configFile).worktreesDirectory());
        assertEquals(UserConfig.LanguageServer.empty(), UserConfig.load(configFile).languageServer());

        Files.writeString(configFile, "{\"worktreesDirectory\":\"" + dir.resolve("wt")
                + "\",\"languageServer\":{\"jdtHome\":42}}");
        assertEquals(Optional.of(dir.resolve("wt").toAbsolutePath().normalize()),
                UserConfig.load(configFile).worktreesDirectory());
        assertEquals(UserConfig.LanguageServer.empty(), UserConfig.load(configFile).languageServer(),
                "a non-string jdtHome member is skipped, not a failure");

        // A string that cannot be a path at all (an embedded NUL) is skipped
        // the same way instead of surfacing InvalidPathException.
        Files.writeString(configFile, "{\"languageServer\":{\"jdtHome\":\"\\u0000\"}}");
        assertEquals(UserConfig.LanguageServer.empty(), UserConfig.load(configFile).languageServer());
    }

    // ---- saveLanguageServerAsync (validated save) ----

    /** A jdt.ls layout validation accepts: config_mac plus exactly one launcher jar. */
    private static Path fakeJdtLsHome(Path dir) throws Exception {
        Path home = dir.resolve("jdt.ls");
        Files.createDirectories(home.resolve("plugins"));
        Files.writeString(home.resolve("config_mac"), "");
        Files.writeString(home.resolve("plugins", "org.eclipse.equinox.launcher_1.6.900.jar"), "");
        return home;
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aRefusedValidationLeavesThePriorFileByteUnchanged(@TempDir Path dir) throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            Path jdtHome = fakeJdtLsHome(dir);
            UserConfig prior = new UserConfig(Optional.of(dir.resolve("wt")), false,
                    new UserConfig.LanguageServer(Optional.of(jdtHome), Optional.of(dir.resolve("jdk"))));
            UserConfig.save(prior, UserConfig.defaultConfigFile());
            String before = Files.readString(UserConfig.defaultConfigFile());

            // No config_mac: refused before the probe is ever consulted, and
            // the injected probe proves no real java runs either way.
            Path notAJdtHome = dir.resolve("elsewhere");
            Files.createDirectories(notAJdtHome);
            AtomicInteger probes = new AtomicInteger();
            UserConfig.LanguageServerSaveResult result = UserConfig.saveLanguageServerAsync(
                    Optional.of(notAJdtHome), home -> {
                        probes.incrementAndGet();
                        return "21.0.2";
                    }).get();

            assertInstanceOf(UserConfig.LanguageServerSaveResult.Refused.class, result);
            assertTrue(((UserConfig.LanguageServerSaveResult.Refused) result).reason().contains("config_mac"));
            assertEquals(0, probes.get(), "a missing config_mac is refused without probing java");
            assertEquals(before, Files.readString(UserConfig.defaultConfigFile()),
                    "a refused save must not touch the file at all");
        } finally {
            UserConfig.flushPendingSaves();
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void anOldJavaRefusesTheSaveButAValidOnePersistsAndPreservesTheRest(@TempDir Path dir) throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            Path jdtHome = fakeJdtLsHome(dir);
            UserConfig prior = new UserConfig(Optional.of(dir.resolve("wt")), false,
                    new UserConfig.LanguageServer(Optional.empty(), Optional.of(dir.resolve("jdk"))));
            UserConfig.save(prior, UserConfig.defaultConfigFile());
            String before = Files.readString(UserConfig.defaultConfigFile());

            UserConfig.LanguageServerSaveResult old = UserConfig.saveLanguageServerAsync(
                    Optional.of(jdtHome), home -> "1.8.0_392").get();
            assertInstanceOf(UserConfig.LanguageServerSaveResult.Refused.class, old);
            assertTrue(((UserConfig.LanguageServerSaveResult.Refused) old).reason().contains("17"));
            assertEquals(before, Files.readString(UserConfig.defaultConfigFile()),
                    "the java version refusal leaves the prior file untouched");

            UserConfig.LanguageServerSaveResult valid = UserConfig.saveLanguageServerAsync(
                    Optional.of(jdtHome), home -> "17.0.9").get();
            assertInstanceOf(UserConfig.LanguageServerSaveResult.Saved.class, valid);

            UserConfig reloaded = UserConfig.load(UserConfig.defaultConfigFile());
            assertEquals(Optional.of(jdtHome.toAbsolutePath().normalize()),
                    reloaded.languageServer().jdtHome());
            assertEquals(Optional.of(dir.resolve("jdk").toAbsolutePath().normalize()),
                    reloaded.languageServer().javaHome(),
                    "the hand-edited javaHome round-trips through the row's save");
            assertEquals(Optional.of(dir.resolve("wt").toAbsolutePath().normalize()),
                    reloaded.worktreesDirectory(), "…and so does every other component");
            assertFalse(reloaded.openChangedFilesInSkim());
        } finally {
            UserConfig.flushPendingSaves();
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void anEmptyJdtHomeDisablesTheTierWithoutValidation(@TempDir Path dir) throws Exception {
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            UserConfig.save(new UserConfig(Optional.empty(), true, new UserConfig.LanguageServer(
                    Optional.of(dir.resolve("configured")), Optional.empty())),
                    UserConfig.defaultConfigFile());

            // A path that does not even exist: would be refused if validated,
            // but clearing the setting has nothing to validate.
            UserConfig.LanguageServerSaveResult result = UserConfig.saveLanguageServerAsync(
                    Optional.empty(), home -> "21.0.2").get();

            assertInstanceOf(UserConfig.LanguageServerSaveResult.Saved.class, result);
            assertTrue(UserConfig.load(UserConfig.defaultConfigFile()).languageServer().jdtHome().isEmpty(),
                    "the tier is off again");
        } finally {
            UserConfig.flushPendingSaves();
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aValidatedSaveIsFifoOrderedAgainstLoads(@TempDir Path dir) throws Exception {
        // Same invariant as loadAsyncObservesAPreviouslyQueuedSave: the
        // validated save runs on the SAME single-thread executor as every
        // load, so a load queued while its (slow, probe-running) save is still
        // in flight can never observe the pre-save value.
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.toString());
        try {
            Path jdtHome = fakeJdtLsHome(dir);
            CountDownLatch probeRunning = new CountDownLatch(1);
            CompletableFuture<UserConfig.LanguageServerSaveResult> save =
                    UserConfig.saveLanguageServerAsync(Optional.of(jdtHome), home -> {
                        probeRunning.countDown();
                        return "21.0.2";
                    });
            assertTrue(probeRunning.await(10, TimeUnit.SECONDS),
                    "the save (and its probe) must be running before the load is queued");

            CompletableFuture<UserConfig> loaded = UserConfig.loadAsync();

            assertInstanceOf(UserConfig.LanguageServerSaveResult.Saved.class, save.get(10, TimeUnit.SECONDS));
            assertEquals(Optional.of(jdtHome.toAbsolutePath().normalize()),
                    loaded.get(10, TimeUnit.SECONDS).languageServer().jdtHome(),
                    "the queued load must land after the save, never before it");
        } finally {
            UserConfig.flushPendingSaves();
            System.setProperty("user.home", originalUserHome);
        }
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
            UserConfig.saveAsync(new UserConfig(Optional.of(Path.of("/tmp/worktrees-A")), true));
            UserConfig.saveAsync(new UserConfig(Optional.of(Path.of("/tmp/worktrees-B")), true));
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
    void loadAsyncObservesAPreviouslyQueuedSave(@TempDir Path tempDir) throws Exception {
        // loadAsync must be ordered against SAVE_EXECUTOR, not race it on an
        // independent thread: commit an edit, then immediately reload (as
        // closing and reopening the settings modal does) and confirm the
        // load never observes the pre-save value.
        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            UserConfig.saveAsync(new UserConfig(Optional.of(Path.of("/tmp/worktrees-fresh")), true));

            UserConfig loaded = UserConfig.loadAsync().get();

            assertEquals(Optional.of(Path.of("/tmp/worktrees-fresh")), loaded.worktreesDirectory());
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
                UserConfig.saveAsync(new UserConfig(Optional.of(Path.of("/tmp/worktrees-" + i)), true));
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
