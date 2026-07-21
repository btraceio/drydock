package app.drydock.claude;

import app.drydock.domain.SessionActivity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the generated hook script by actually running it, because it is
 * shell embedded in a Java string: nothing else in the build would catch a
 * quoting or {@code sed} mistake in it, and it only ever runs inside a live
 * claude session where a failure is invisible.
 *
 * <p>Payloads here are trimmed copies of real hook input captured from
 * claude 2.1.215.</p>
 */
class ClaudeHookInstallerTest {

    private static final String SESSION_ID = "11111111-2222-4333-8444-555555555555";

    /** Only needed to construct the watcher; every read here goes through readBlocking(). */
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void shutDownExecutor() {
        EXECUTOR.shutdown();
    }

    private static String payload(String eventName, String extra) {
        return "{\"session_id\":\"" + SESSION_ID + "\",\"transcript_path\":\"/tmp/x.jsonl\","
                + "\"cwd\":\"/tmp\",\"permission_mode\":\"default\",\"hook_event_name\":\"" + eventName + "\""
                + extra + "}";
    }

    /** Runs the installed hook script with {@code stateArg} and the given stdin payload. */
    private static int runHook(ClaudeHookInstaller installer, Path base, String stateArg, String stdin)
            throws IOException, InterruptedException {
        Path script = base.resolve("hooks").resolve("drydock-activity.sh");
        Process process = new ProcessBuilder(List.of("sh", script.toString(), stateArg))
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "hook script did not finish");
        return process.exitValue();
    }

    /** Reads back what the script wrote, through the same watcher the app uses. */
    private static SessionActivity observedActivity(Path base) {
        return new SessionActivityWatcher(base.resolve("activity"), EXECUTOR)
                .readBlocking()
                .getOrDefault(SESSION_ID, SessionActivity.UNKNOWN);
    }

    @Test
    void installIsIdempotentAndWritesBothArtifacts(@TempDir Path base) throws IOException {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);

        installer.install();
        installer.install();

        assertTrue(Files.isRegularFile(installer.settingsFile()));
        assertTrue(Files.isRegularFile(base.resolve("hooks").resolve("drydock-activity.sh")));
        assertTrue(Files.isDirectory(installer.activityDirectory()));
    }

    /**
     * Regression: killing the app leaves SessionEnd unable to run, so a
     * session's state file survives with no process behind it. Observed live --
     * a force-quit left a session stuck reporting NEEDS_ATTENTION, which would
     * have badged on every subsequent launch.
     */
    @Test
    void installPurgesActivityLeftBehindByAPreviousRun(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"permission_prompt\""));
        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));

        // Simulates the next app start.
        installer.install();

        assertEquals(SessionActivity.UNKNOWN, observedActivity(base));
    }

    @Test
    void userPromptSubmitMarksTheSessionBusy(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "busy",
                payload("UserPromptSubmit", ",\"prompt\":\"do a thing\"")));

        assertEquals(SessionActivity.BUSY, observedActivity(base));
    }

    @Test
    void stopMarksTheSessionIdle(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "idle",
                payload("Stop", ",\"stop_hook_active\":false,\"last_assistant_message\":\"done\"")));

        assertEquals(SessionActivity.IDLE, observedActivity(base));
    }

    @Test
    void permissionPromptNotificationRaisesAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"message\":\"Claude needs your permission\""
                        + ",\"notification_type\":\"permission_prompt\"")));

        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));
    }

    @Test
    void idlePromptNotificationRaisesAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"idle_prompt\"")));

        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));
    }

    /** Notifications that are not about a blocked human keep the session merely busy. */
    @Test
    void unrelatedNotificationDoesNotRaiseAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"auth_success\"")));

        assertEquals(SessionActivity.BUSY, observedActivity(base));
    }

    /**
     * Regression: the classifier used to glob for the bare keyword anywhere in
     * the payload, so an unrelated notification whose human-readable text
     * merely mentioned "permission_prompt" would badge a session that was not
     * blocked. Only the notification_type VALUE may decide this.
     */
    @Test
    void keywordInFreeTextDoesNotRaiseAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"message\":\"resolved without a permission_prompt this time\""
                        + ",\"title\":\"idle_prompt mentioned here too\""
                        + ",\"notification_type\":\"auth_success\"")));

        assertEquals(SessionActivity.BUSY, observedActivity(base));
    }

    /** The value is what counts, wherever the field sits in the payload. */
    @Test
    void notificationTypeIsReadRegardlessOfFieldOrder(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"idle_prompt\""
                        + ",\"message\":\"Claude is waiting for your input\"")));

        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));
    }

    @Test
    void sessionEndRemovesTheStateFile(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        runHook(installer, base, "busy", payload("UserPromptSubmit", ""));

        assertEquals(0, runHook(installer, base, "end", payload("SessionEnd", ",\"reason\":\"other\"")));

        assertFalse(Files.exists(installer.activityDirectory().resolve(SESSION_ID)));
        assertEquals(SessionActivity.UNKNOWN, observedActivity(base));
    }

    /** A payload with no session id cannot be attributed, so the hook must do nothing at all. */
    @Test
    void payloadWithoutASessionIdWritesNothing(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "busy", "{\"hook_event_name\":\"Stop\"}"));

        try (var entries = Files.list(installer.activityDirectory())) {
            assertEquals(List.of(), entries.toList());
        }
    }

    /**
     * "Application Support" has a space in it, and the script path is embedded
     * inside a JSON string that is itself a shell command -- the double
     * quoting is easy to get wrong and impossible to notice at runtime.
     */
    @Test
    void settingsCommandSurvivesASpaceInTheInstallPath(@TempDir Path parent) throws Exception {
        Path base = parent.resolve("Application Support").resolve("ClaudeProjectManager");
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        String settings = Files.readString(installer.settingsFile());
        assertTrue(settings.contains("'" + base.resolve("hooks").resolve("drydock-activity.sh") + "'"),
                "script path should be shell-single-quoted inside the JSON command: " + settings);

        // And the script itself still resolves its activity dir from $0.
        assertEquals(0, runHook(installer, base, "busy", payload("UserPromptSubmit", "")));
        assertEquals(SessionActivity.BUSY, observedActivity(base));
    }

    @Test
    void everyHookedEventIsRegistered(@TempDir Path base) throws IOException {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        String settings = Files.readString(installer.settingsFile());
        for (String event : List.of("UserPromptSubmit", "Notification", "Stop", "SessionEnd")) {
            assertTrue(settings.contains("\"" + event + "\""), "missing hook registration for " + event);
        }
    }
}
