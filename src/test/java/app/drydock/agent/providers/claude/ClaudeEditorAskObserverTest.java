package app.drydock.agent.providers.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.drydock.agent.providers.claude.internal.ClaudeEditorAskObserver;
import app.drydock.agent.providers.claude.internal.ClaudeHookInstaller;
import app.drydock.agent.spi.EditorAskTurnObserver.Event;
import app.drydock.agent.spi.EditorAskTurnObserver.Kind;
import app.drydock.agent.spi.EditorAskTurnObserver.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeEditorAskObserverTest {

    private static final String NONCE = "launch-0001";
    private static final String MARKER = "editor-11111111-2222-4333-8444-555555555555";
    private static final String OTHER = "editor-aaaaaaaa-2222-4333-8444-555555555555";
    private static final String S = "sess-main";

    @TempDir Path dir;
    private Path log;
    private ClaudeEditorAskObserver observer;

    @BeforeEach
    void setUp() {
        log = dir.resolve("events.log");
        observer = new ClaudeEditorAskObserver(NONCE, log, true);
    }

    private void line(String nonce, String event, String session, String ntype, int agent, String markers)
            throws IOException {
        String l = String.join("\t", nonce, event, session, ntype, String.valueOf(agent), markers) + "\n";
        Files.writeString(log, l, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void ev(String event, String session, String ntype) throws IOException {
        line(NONCE, event, session, ntype, 0, "");
    }

    private void submit(String session, String markers) throws IOException {
        line(NONCE, "UserPromptSubmit", session, "", 0, markers);
    }

    private List<Kind> kinds() {
        return observer.read(0).events().stream().map(Event::kind).toList();
    }

    @Test
    void stopThenIdlePromptSettles() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        ev("Stop", S, "");
        assertEquals(List.of(Kind.BOUND), kinds());
        ev("Notification", S, "idle_prompt");
        Observation o = observer.read(0);
        assertEquals(List.of(Kind.BOUND, Kind.SETTLED), kinds());
        assertEquals(S, o.events().get(1).conversationId());
        assertEquals(o.events().get(1).sequence(), o.cursor());
        assertEquals(List.of(), observer.read(o.cursor()).events());
    }

    @Test
    void stopThenContinueInvalidatesCandidate() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        ev("Stop", S, "");
        ev("PreToolUse", S, "");           // blocking Stop hook: work continues
        ev("Notification", S, "idle_prompt");
        assertEquals(List.of(Kind.BOUND), kinds());
        ev("Stop", S, "");
        ev("Notification", S, "idle_prompt");
        assertEquals(List.of(Kind.BOUND, Kind.SETTLED), kinds());
    }

    @Test
    void permissionPromptNeverSettlesAndInvalidates() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        ev("Stop", S, "");
        ev("Notification", S, "permission_prompt");
        ev("Notification", S, "idle_prompt");
        assertEquals(List.of(Kind.BOUND), kinds());
    }

    @Test
    void idlePromptWithoutStopOrBeforeStopDoesNotSettle() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        ev("Notification", S, "idle_prompt");
        assertEquals(List.of(Kind.BOUND), kinds());
    }

    @Test
    void foreignSessionSubagentNonceAndStaleAreIgnored() throws IOException {
        submit("old", MARKER + ",");       // before arming: stale
        observer.arm(MARKER);
        line("other-nonce", "UserPromptSubmit", "x", "", 0, MARKER + ",");
        assertEquals(List.of(), kinds());
        submit(S, MARKER + ",");
        ev("Stop", "foreign", "");
        ev("Notification", "foreign", "idle_prompt");
        line(NONCE, "Stop", S, "", 1, "");   // subagent Stop is no candidate
        ev("Notification", S, "idle_prompt");
        assertEquals(List.of(Kind.BOUND), kinds());
    }

    @Test
    void markerMustBeExactAndUnambiguous() throws IOException {
        observer.arm(MARKER);
        submit(S, OTHER + ",");
        submit(S, MARKER + "," + OTHER + ",");
        assertEquals(List.of(), kinds());
    }

    @Test
    void anotherPromptInTheSessionPoisonsTheTurn() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        ev("Stop", S, "");
        submit(S, OTHER + ",");
        ev("Stop", S, "");
        ev("Notification", S, "idle_prompt");
        assertEquals(List.of(Kind.BOUND), kinds());
    }

    @Test
    void unverifiedVersionFailsClosed() throws IOException {
        ClaudeEditorAskObserver o = new ClaudeEditorAskObserver(NONCE, log, false);
        o.arm(MARKER);
        submit(S, MARKER + ",");
        assertTrue(o.read(0).unavailable().isPresent());
        assertTrue(o.read(0).events().isEmpty());
    }

    @Test
    void armOnceAndPartialLineIgnored() throws IOException {
        observer.arm(MARKER);
        assertThrows(IllegalArgumentException.class, () -> observer.arm(MARKER));
        Files.writeString(log, NONCE + "\tUserPromptSubmit\t" + S + "\t\t0\t" + MARKER + ",");
        assertEquals(List.of(), kinds());
        observer.close();
        assertTrue(observer.read(0).unavailable().isPresent());
    }

    @Test
    void incrementalReadsWaitForCompleteUtf8LinesAndReplayCachedEvents() throws Exception {
        observer.arm(MARKER);
        Files.write(log, (NONCE + "\tUserPromptSubmit\tsess-").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        byte[] snowman = "☃".getBytes(StandardCharsets.UTF_8);
        Files.write(log, new byte[] {snowman[0], snowman[1]}, StandardOpenOption.APPEND);
        assertTrue(observer.read(0).events().isEmpty());

        Files.write(log, new byte[] {snowman[2]}, StandardOpenOption.APPEND);
        Files.writeString(log, "\t\t0\t" + MARKER + ",\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        ev("Stop", "sess-☃", "");
        ev("Notification", "sess-☃", "idle_prompt");
        Observation first = observer.read(0);
        assertEquals(List.of(Kind.BOUND, Kind.SETTLED), first.events().stream().map(Event::kind).toList());
        assertEquals("sess-☃", first.events().get(0).conversationId());
        assertEquals(first.events(), observer.read(0).events());
    }

    @Test
    void malformedAndForeignPhysicalLinesConsumeTheArmFence() throws IOException {
        Files.writeString(log, "malformed\n", StandardCharsets.UTF_8);
        line("other-nonce", "UserPromptSubmit", "foreign", "", 0, MARKER + ",");
        long fence = observer.arm(MARKER);
        assertEquals(2, fence);
        submit(S, MARKER + ",");
        assertEquals(List.of(3L), observer.read(0).events().stream().map(Event::sequence).toList());
    }

    @Test
    void armingAnotherMarkerIngestsPendingLinesForExistingTurnsBeforeItsFence() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        ev("Stop", S, "");

        long secondFence = observer.arm(OTHER);
        assertEquals(2, secondFence);
        ev("Notification", S, "idle_prompt");

        List<Event> events = observer.read(0).events();
        assertEquals(List.of(Kind.BOUND, Kind.SETTLED), events.stream().map(Event::kind).toList());
        assertEquals(List.of(MARKER, MARKER), events.stream().map(Event::marker).toList());
        assertEquals(List.of(1L, 3L), events.stream().map(Event::sequence).toList());
    }

    @Test
    void totalByteBoundIsEnforcedAcrossIncrementalAppends() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        long cursor = observer.read(0).cursor();
        assertEquals(1, cursor);
        int bound = 1 << 20;
        long size = Files.size(log);
        String padding = "x".repeat((int) (bound - size - 1)) + "\n";
        Files.writeString(log, padding, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertTrue(observer.read(cursor).unavailable().isEmpty(), "exactly the byte limit is accepted");
        Files.writeString(log, "x", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Observation over = observer.read(cursor);
        assertTrue(over.events().isEmpty());
        assertTrue(over.unavailable().isPresent());
        assertEquals(cursor, over.cursor());
    }

    @Test
    void armRefusesAnOverBoundLogWithoutKeepingAPhantomMarker() throws IOException {
        Files.writeString(log, "x".repeat((1 << 20) + 1), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> observer.arm(MARKER));
        Files.writeString(log, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals(0, observer.arm(MARKER));
    }

    @Test
    void truncationAfterCommittedEvidenceStaysUnavailableWithoutAdvancingCursor() throws IOException {
        observer.arm(MARKER);
        submit(S, MARKER + ",");
        assertEquals(List.of(Kind.BOUND), kinds());
        Files.writeString(log, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        Observation first = observer.read(1);
        assertTrue(first.events().isEmpty());
        assertTrue(first.unavailable().isPresent());
        assertEquals(1, first.cursor());
        assertTrue(observer.read(1).unavailable().isPresent());
    }

    @Test
    void unreadableArmRefusesWithoutKeepingAPhantomMarker() throws Exception {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "file");
        Path inaccessibleLog = blocker.resolve("events.log");
        ClaudeEditorAskObserver unreadable = new ClaudeEditorAskObserver(NONCE, inaccessibleLog, true);

        assertTrue(unreadable.read(0).unavailable().isPresent());
        assertThrows(IllegalStateException.class, () -> unreadable.arm(MARKER));

        Files.delete(blocker);
        Files.createDirectory(blocker);
        Files.createFile(inaccessibleLog);
        assertEquals(0, unreadable.arm(MARKER), "failed arm must not retain a phantom marker");
    }

    @Test
    void launchPathsAreSideEffectFreeAndCloseDeletesOnlyNonceArtifacts() throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(dir.resolve("base"));
        installer.install();
        ClaudeHookInstaller.EditorAskLaunchHooks paths = installer.editorAskLaunchPaths(NONCE);
        assertFalse(Files.exists(paths.settingsFile().getParent()));
        assertEquals(paths, installer.installEditorAskLaunch(NONCE));
        ClaudeHookInstaller.EditorAskLaunchHooks sibling = installer.installEditorAskLaunch("launch-0002");
        ClaudeEditorAskObserver launchObserver = ClaudeEditorAskObserver.forLaunch(paths, true);
        launchObserver.close();
        launchObserver.close();
        assertFalse(Files.exists(paths.eventLog()));
        assertFalse(Files.exists(paths.settingsFile()));
        assertFalse(Files.exists(paths.settingsFile().getParent()));
        assertTrue(Files.isDirectory(sibling.settingsFile().getParent()));
        assertTrue(Files.isDirectory(dir.resolve("base/editor-ask")));
        assertTrue(Files.isRegularFile(dir.resolve("base/hooks/drydock-editor-ask.sh")));
        assertTrue(Files.isRegularFile(installer.settingsFile()));
    }

    @Test
    void ordinaryHooksUnchangedAndLaunchSettingsAddRecorder() throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(dir.resolve("base"));
        installer.install();
        String ordinary = Files.readString(installer.settingsFile());
        assertFalse(ordinary.contains("editor-ask"));
        ClaudeHookInstaller.EditorAskLaunchHooks h = installer.installEditorAskLaunch(NONCE);
        assertEquals(ordinary, Files.readString(installer.settingsFile()));
        String launch = Files.readString(h.settingsFile());
        assertTrue(launch.contains("drydock-activity.sh") && launch.contains("drydock-editor-ask.sh"));
        assertTrue(launch.contains(NONCE));
        assertThrows(IllegalArgumentException.class, () -> installer.installEditorAskLaunch("a/../b"));
    }

    @Test
    void recorderScriptWritesObservableLines() throws Exception {
        ClaudeHookInstaller installer = new ClaudeHookInstaller(dir.resolve("base"));
        installer.install();
        ClaudeHookInstaller.EditorAskLaunchHooks h = installer.installEditorAskLaunch(NONCE);
        Path script = dir.resolve("base/hooks/drydock-editor-ask.sh");
        ClaudeEditorAskObserver o = ClaudeEditorAskObserver.forLaunch(h, true);
        o.arm(MARKER);
        run(script, h, "UserPromptSubmit", "{\"session_id\":\"" + S + "\",\"prompt\":\"Answer " + MARKER + " ok\"}");
        run(script, h, "Stop", "{\"session_id\":\"" + S + "\",\"stop_hook_active\":false}");
        run(script, h, "Notification", "{\"session_id\":\"" + S + "\",\"notification_type\":\"idle_prompt\"}");
        assertEquals(List.of(Kind.BOUND, Kind.SETTLED),
                o.read(0).events().stream().map(Event::kind).toList());
        assertFalse(Files.readString(h.eventLog()).contains("Answer"));
    }

    private static void run(Path script, ClaudeHookInstaller.EditorAskLaunchHooks h, String event, String payload)
            throws Exception {
        Process p = new ProcessBuilder("sh", script.toString(), event, h.nonce(), h.eventLog().toString())
                .redirectErrorStream(true).start();
        p.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue());
    }
}
