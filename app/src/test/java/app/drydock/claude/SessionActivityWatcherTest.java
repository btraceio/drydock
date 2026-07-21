package app.drydock.claude;

import app.drydock.domain.SessionActivity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionActivityWatcherTest {

    /** These tests drive readBlocking() directly, so the executor is only here to satisfy the constructor. */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void shutDownExecutor() {
        EXECUTOR.shutdown();
    }

    private static SessionActivityWatcher watcherOn(Path directory) {
        return new SessionActivityWatcher(directory, EXECUTOR);
    }

    private static void writeState(Path directory, String sessionId, String word) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(sessionId), word);
    }

    @Test
    void readsStateWordsKeyedByClaudeSessionId(@TempDir Path dir) throws IOException {
        Path activity = dir.resolve("activity");
        writeState(activity, "sess-busy", "busy");
        writeState(activity, "sess-idle", "idle");
        writeState(activity, "sess-waiting", "attention");

        Map<String, SessionActivity> states = watcherOn(activity).readBlocking();

        assertEquals(SessionActivity.BUSY, states.get("sess-busy"));
        assertEquals(SessionActivity.IDLE, states.get("sess-idle"));
        assertEquals(SessionActivity.NEEDS_ATTENTION, states.get("sess-waiting"));
    }

    @Test
    void missingDirectoryYieldsNoActivityRatherThanFailing(@TempDir Path dir) {
        assertEquals(Map.of(), watcherOn(dir.resolve("never-created")).readBlocking());
    }

    /** The hook writes to a dot-prefixed temp file before renaming; a half-written one must not be read. */
    @Test
    void ignoresTheHookScriptsInFlightTempFiles(@TempDir Path dir) throws IOException {
        Path activity = dir.resolve("activity");
        writeState(activity, ".sess-partial.tmp.123", "attention");

        assertEquals(Map.of(), watcherOn(activity).readBlocking());
    }

    @Test
    void ignoresUnrecognizedAndOversizedContent(@TempDir Path dir) throws IOException {
        Path activity = dir.resolve("activity");
        writeState(activity, "sess-garbage", "not-a-state");
        writeState(activity, "sess-huge", "attention".repeat(100));

        assertEquals(Map.of(), watcherOn(activity).readBlocking());
    }

    @Test
    void acknowledgingClearsTheAttentionBadgeButLeavesOtherSessionsAlone(@TempDir Path dir) throws IOException {
        Path activity = dir.resolve("activity");
        writeState(activity, "sess-seen", "attention");
        writeState(activity, "sess-unseen", "attention");
        SessionActivityWatcher watcher = watcherOn(activity);

        watcher.acknowledge("sess-seen");
        Map<String, SessionActivity> states = watcher.readBlocking();

        assertEquals(SessionActivity.IDLE, states.get("sess-seen"));
        assertEquals(SessionActivity.NEEDS_ATTENTION, states.get("sess-unseen"));
    }

    /**
     * The badge must come back when Claude blocks a SECOND time. Acknowledgement
     * is therefore released as soon as the underlying state moves on, rather
     * than suppressing that session forever.
     */
    @Test
    void attentionBadgeReturnsAfterTheStateMovesOnAndBlocksAgain(@TempDir Path dir) throws IOException {
        Path activity = dir.resolve("activity");
        writeState(activity, "sess", "attention");
        SessionActivityWatcher watcher = watcherOn(activity);
        watcher.acknowledge("sess");
        assertEquals(SessionActivity.IDLE, watcher.readBlocking().get("sess"));

        writeState(activity, "sess", "busy");
        assertEquals(SessionActivity.BUSY, watcher.readBlocking().get("sess"));

        writeState(activity, "sess", "attention");
        assertEquals(SessionActivity.NEEDS_ATTENTION, watcher.readBlocking().get("sess"));
    }

    @Test
    void forgetRemovesTheSessionsStateFile(@TempDir Path dir) throws Exception {
        Path activity = dir.resolve("activity");
        writeState(activity, "sess", "attention");
        SessionActivityWatcher watcher = watcherOn(activity);

        // The delete is async (it must not run on the caller's FX thread).
        watcher.forget("sess").get(10, TimeUnit.SECONDS);

        assertTrue(watcher.readBlocking().isEmpty());
    }
}
