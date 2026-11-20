package app.drydock.agent.providers.claude.internal;

import app.drydock.activity.SessionActivityWatcher;
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
                .poll()
                .join()
                .getOrDefault(SESSION_ID, SessionActivity.UNKNOWN);
    }

    @Test
    void installIsIdempotentAndWritesBothArtifacts(@TempDir Path base) throws IOException {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);

        installer.install();
        installer.install();

        assertTrue(Files.isRegularFile(installer.settingsFile()));
        assertTrue(Files.isRegularFile(base.resolve("hooks").resolve("drydock-activity.sh")));
        assertTrue(Files.isRegularFile(base.resolve("hooks").resolve("drydock-pr-scan.sh")));
        assertTrue(Files.isDirectory(installer.activityDirectory()));
        assertTrue(Files.isDirectory(installer.prDirectory()));
    }

    /** Runs the installed PR-scan hook script with the given stdin payload (it takes no state argument). */
    private static int runPrScanHook(Path base, String stdin)
            throws IOException, InterruptedException {
        Path script = base.resolve("hooks").resolve("drydock-pr-scan.sh");
        Process process = new ProcessBuilder(List.of("sh", script.toString()))
                .redirectErrorStream(true)
                .start();
        try {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "hook script did not finish");
            return process.exitValue();
        } finally {
            process.destroy();
        }
    }

    /**
     * The scan must catch every phrasing a transcript carries -- an agent's
     * "opened PR #1034 (draft)" report, a raw gh tool result, a URL with a
     * trailing path -- deduplicate them, and write one owner/repo#n line each.
     */
    @Test
    void prScanHookWritesReferencesFromTheTranscript(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        Path transcript = Files.writeString(base.resolve("t.jsonl"), """
                {"type":"assistant","message":{"content":[{"type":"text","text":\
                "Opened PR #1034 (draft) - https://github.com/DataDog/java-profiler/pull/1034"}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","content":\
                "https://github.com/DataDog/java-profiler/pull/1034/files created"}]}}
                {"type":"assistant","message":{"content":[{"type":"text","text":\
                "see also https://github.com/other/repo/pull/7"}]}}
                """);

        assertEquals(0, runPrScanHook(base, payload("Stop",
                ",\"stop_hook_active\":false").replace("/tmp/x.jsonl", transcript.toString())));

        List<String> lines = Files.readAllLines(installer.prDirectory().resolve(SESSION_ID));
        assertEquals(List.of("DataDog/java-profiler#1034", "other/repo#7"), lines);
    }

    /**
     * The session id becomes a file name in the shared pr-links directory: a
     * crafted id carrying a path separator or a dot-prefixed/empty form must
     * be refused before it can reach mkdir/mv/rm (same guard as the pi
     * PR-scan extension's session-id validation).
     */
    @Test
    void prScanHookRefusesAPathUnsafeSessionId(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        Path transcript = Files.writeString(base.resolve("t.jsonl"),
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"https://github.com/DataDog/java-profiler/pull/1034\"}]}}");

        for (String unsafe : List.of("../../etc/passwd", "a/b", ".hidden", "")) {
            assertEquals(0, runPrScanHook(base, payload("Stop", "")
                    .replace("/tmp/x.jsonl", transcript.toString())
                    .replace(SESSION_ID, unsafe)),
                    "an unsafe id must exit 0 without writing: '" + unsafe + "'");
        }

        try (var entries = Files.list(installer.prDirectory())) {
            assertEquals(List.of(), entries.toList(),
                    "no file may appear outside the directory for any unsafe id");
        }
        // The planted escape target is intact: the hook must not have written
        // through ../ to the parent directory.
        assertTrue(Files.notExists(base.resolve("passwd")));
    }

    /** No transcript, or no PR URLs in it: the hook writes nothing and exits 0. */
    @Test
    void prScanHookWithoutReferencesWritesNothing(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        Path transcript = Files.writeString(base.resolve("t.jsonl"),
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"no PRs here\"}]}}");

        assertEquals(0, runPrScanHook(base, payload("Stop", "")
                .replace("/tmp/x.jsonl", transcript.toString())));

        try (var entries = Files.list(installer.prDirectory())) {
            assertEquals(List.of(), entries.toList());
        }
    }

    /** Same rule as the activity purge: no process survives an app restart. */
    @Test
    void installPurgesPrReferencesLeftBehindByAPreviousRun(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        Files.writeString(installer.prDirectory().resolve(SESSION_ID), "DataDog/java-profiler#1034");

        installer.install();

        try (var entries = Files.list(installer.prDirectory())) {
            assertEquals(List.of(), entries.toList());
        }
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
    void preToolUseBackgroundWaitMarksTheSessionWaitingOnBackgroundWork(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "bg",
                payload("PreToolUse", ",\"tool_name\":\"TaskOutput\",\"tool_input\":{\"task_id\":\"t1\"}")));

        assertEquals(SessionActivity.WAITING_BACKGROUND, observedActivity(base));
    }

    /** When the wait tool returns, the PostToolUse hook hands the word back to busy. */
    @Test
    void postToolUseBackgroundWaitReturnsTheSessionToBusy(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        runHook(installer, base, "bg",
                payload("PreToolUse", ",\"tool_name\":\"BashOutput\",\"tool_input\":{\"bash_id\":\"b1\"}"));
        assertEquals(SessionActivity.WAITING_BACKGROUND, observedActivity(base));

        assertEquals(0, runHook(installer, base, "busy",
                payload("PostToolUse", ",\"tool_name\":\"BashOutput\",\"tool_response\":{\"output\":\"done\"}")));

        assertEquals(SessionActivity.BUSY, observedActivity(base));
    }

    /**
     * Claude sends no Notification hook while {@code AskUserQuestion} blocks
     * on the user's answer (anthropics/claude-code #13830), so the pair of
     * tool hooks is what lifts the session out of "busy" for the duration of
     * the question -- and hands the word back when it is answered.
     */
    @Test
    void askUserQuestionToolRaisesAttentionUntilAnswered(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "attention",
                payload("PreToolUse", ",\"tool_name\":\"AskUserQuestion\",\"tool_input\":{\"questions\":[]}")));
        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));

        assertEquals(0, runHook(installer, base, "busy",
                payload("PostToolUse", ",\"tool_name\":\"AskUserQuestion\",\"tool_response\":{}")));
        assertEquals(SessionActivity.BUSY, observedActivity(base));
    }

    /** The generated settings must scope the AskUserQuestion hooks to that tool only. */
    @Test
    void askUserQuestionHooksAreMatcherScopedToThatTool(@TempDir Path base) throws IOException {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        String settings = Files.readString(installer.settingsFile(), StandardCharsets.UTF_8);

        assertEquals(2, settings.split("\"AskUserQuestion\"", -1).length - 1,
                "the matcher must scope exactly the Pre/PostToolUse pair");
        assertEquals(1, settings.split("sh [^\"]*attention", -1).length - 1,
                "attention is written by the AskUserQuestion PreToolUse hook only");
    }

    /** An MCP server asking the user through an elicitation dialog blocks on a human, too. */
    @Test
    void elicitationDialogNotificationsRaiseAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"elicitation_dialog\"")));
        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"elicitation_url_dialog\"")));
        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));
    }

    /** Completing an elicitation is chatter, not a blocked-on-human signal. */
    @Test
    void elicitationCompletionNotificationsDoNotRaiseAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"elicitation_complete\"")));

        assertEquals(SessionActivity.UNKNOWN, observedActivity(base));
    }

    /** The generated settings must scope the bg hooks to the wait tools, not every tool call. */
    @Test
    void backgroundWaitHooksAreMatcherScopedToTheWaitTools(@TempDir Path base) throws IOException {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        String settings = Files.readString(installer.settingsFile(), StandardCharsets.UTF_8);
        String script = base.resolve("hooks").resolve("drydock-activity.sh").toString();

        assertTrue(settings.contains("\"PreToolUse\""));
        assertTrue(settings.contains("\"PostToolUse\""));
        assertEquals(2, settings.split("TaskOutput\\|BashOutput", -1).length - 1,
                "the matcher must scope exactly the Pre/PostToolUse pair");
        assertTrue(settings.contains("sh '" + script + "' bg"));
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

    @Test
    void agentNeedsInputNotificationRaisesAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"agent_needs_input\"")));

        assertEquals(SessionActivity.NEEDS_ATTENTION, observedActivity(base));
    }

    /** Routine notifications without an existing state do not create one. */
    @Test
    void routineNotificationDoesNotCreateActivityState(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"auth_success\"")));

        assertEquals(SessionActivity.UNKNOWN, observedActivity(base));
    }

    /** A routine notification must not overwrite the state written by an in-flight background wait. */
    @Test
    void routineNotificationPreservesBackgroundWaitState(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();
        runHook(installer, base, "bg",
                payload("PreToolUse", ",\"tool_name\":\"TaskOutput\",\"tool_input\":{\"task_id\":\"t1\"}"));
        assertEquals(SessionActivity.WAITING_BACKGROUND, observedActivity(base));

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"notification_type\":\"auth_success\"")));

        assertEquals(SessionActivity.WAITING_BACKGROUND, observedActivity(base));
    }

    /**
     * Regression: the classifier used to glob for the bare keyword anywhere in
     * the payload, so an unrelated notification whose human-readable text
     * merely mentioned "permission_prompt" would badge a session that was not
     * blocked. Only the notification_type VALUE may decide this. A routine
     * type writes nothing at all -- a state word must never be downgraded by a
     * notification, whether the current word is busy, bg, or attention.
     */
    @Test
    void keywordInFreeTextDoesNotRaiseAttention(@TempDir Path base) throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(base);
        installer.install();

        assertEquals(0, runHook(installer, base, "notify",
                payload("Notification", ",\"message\":\"resolved without a permission_prompt this time\""
                        + ",\"title\":\"idle_prompt mentioned here too\""
                        + ",\"notification_type\":\"auth_success\"")));

        assertEquals(SessionActivity.UNKNOWN, observedActivity(base));
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
        String quotedPath = "'" + base.resolve("hooks").resolve("drydock-activity.sh") + "'";
        // The settings file is JSON: on Windows the path's backslashes are
        // JSON-escaped (doubled) inside the string value.
        assertTrue(settings.contains(quotedPath.replace("\\", "\\\\")),
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
