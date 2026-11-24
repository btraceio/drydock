package app.drydock.agent.providers.codex;

import app.drydock.agent.providers.codex.internal.CodexEditorAskRolloutReader;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;
import app.drydock.agent.spi.EditorAskTurnObserver.Event;
import app.drydock.agent.spi.EditorAskTurnObserver.Kind;
import app.drydock.agent.spi.EditorAskTurnObserver.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexEditorAskRolloutReaderTest {

    private static final String M1 = "editor-11111111-1111-4111-8111-111111111111";
    private static final String M2 = "editor-22222222-2222-4222-8222-222222222222";
    private static final String T1 = "01a00000-0000-7000-8000-000000000001";
    private static final String T2 = "01a00000-0000-7000-8000-000000000002";

    @TempDir Path root;
    private CodexRolloutStore store;
    private Path cwd;
    private Instant launched;
    private int fileSeq;

    @BeforeEach
    void setUp() {
        store = new CodexRolloutStore(root.resolve("sessions"));
        cwd = Path.of("/work/checkout");
        launched = Instant.now().minusSeconds(60);
    }

    private CodexEditorAskRolloutReader create() {
        return new CodexEditorAskRolloutReader(store, cwd, launched, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    private Path rollout(String id, String cwdStr, Instant ts) throws IOException {
        ZonedDateTime d = launched.atZone(ZoneId.systemDefault());
        Path dir = root.resolve(String.format("sessions/%04d/%02d/%02d", d.getYear(), d.getMonthValue(),
                d.getDayOfMonth()));
        Files.createDirectories(dir);
        Path f = dir.resolve(String.format("rollout-x%03d-%s.jsonl", fileSeq++, id));
        Files.writeString(f, "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"cwd\":\"" + cwdStr
                + "\",\"timestamp\":\"" + ts + "\",\"source\":\"vscode\"}}\n");
        return f;
    }

    private Path fresh(String id) throws IOException {
        return rollout(id, cwd.toString(), launched.plusSeconds(1));
    }

    private static void append(Path f, String text) throws IOException {
        Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String started(String turn) {
        return "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_started\",\"turn_id\":\"" + turn + "\"}}\n";
    }

    private static String user(String thread, String turn, String text) {
        return item("UserMessage", "text", thread, turn, text);
    }

    private static String item(String kind, String partType, String thread, String turn, String text) {
        return "{\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"" + thread
                + "\",\"turn_id\":\"" + turn + "\",\"item\":{\"type\":\"" + kind + "\",\"content\":[{\"type\":\""
                + partType + "\",\"text\":\"" + text + "\"}]}}}\n";
    }

    private static String done(String type, String turn) {
        return "{\"type\":\"event_msg\",\"payload\":{\"type\":\"" + type + "\",\"turn_id\":\"" + turn + "\"}}\n";
    }

    @Test
    void bindsThenSettlesInEventOrder() throws IOException {
        var r = create();
        long fence = r.arm(M1);
        Path f = fresh(T1);
        append(f, started("t1") + user(T1, "t1", "Please answer " + M1 + " now"));
        Observation o = r.read(fence);
        assertEquals(1, o.events().size());
        assertEquals(new Event(1, Kind.BOUND, M1, T1), o.events().get(0));
        assertTrue(o.unavailable().isEmpty());
        append(f, done("task_complete", "t1"));
        Observation o2 = r.read(o.cursor());
        assertEquals(new Event(2, Kind.SETTLED, M1, T1), o2.events().get(0));
        assertTrue(o2.unavailable().isEmpty());
        assertEquals(2, r.read(0).events().size(), "same cursor returns the same events");
    }

    @Test
    void abortSettlesToo() throws IOException {
        var r = create();
        r.arm(M1);
        append(fresh(T1), started("t1") + user(T1, "t1", M1) + done("turn_aborted", "t1"));
        assertEquals(Kind.SETTLED, r.read(0).events().get(1).kind());
    }

    @Test
    void assistantAndToolEchoesAreIgnored() throws IOException {
        var r = create();
        r.arm(M1);
        append(fresh(T1), started("t1")
                + item("AgentMessage", "Text", T1, "t1", M1)
                + item("CommandExecution", "text", T1, "t1", M1) + done("task_complete", "t1"));
        assertTrue(r.read(0).events().isEmpty());
    }

    @Test
    void markerInPrefixOfLongerTokenIsNotAMatch() throws IOException {
        var r = create();
        r.arm(M1);
        append(fresh(T1), started("t1") + user(T1, "t1", M1 + "0"));
        assertTrue(r.read(0).events().isEmpty());
    }

    @Test
    void settlementOfAnotherTurnIsNotUsed() throws IOException {
        var r = create();
        r.arm(M1);
        append(fresh(T1), started("t1") + user(T1, "t1", M1) + started("t2") + done("task_complete", "t2"));
        assertEquals(1, r.read(0).events().size());
    }

    @Test
    void wrongThreadIdIsNotBoundAndIsUnavailable() throws IOException {
        var r = create();
        r.arm(M1);
        append(fresh(T1), started("t1") + user(T2, "t1", M1));
        Observation o = r.read(0);
        assertTrue(o.events().isEmpty());
        assertTrue(o.unavailable().isPresent());
    }

    @Test
    void markerInTwoRolloutsIsAmbiguous() throws IOException {
        var r = create();
        r.arm(M1);
        append(fresh(T1), started("t1") + user(T1, "t1", M1));
        append(fresh(T2), started("t9") + user(T2, "t9", M1) + done("task_complete", "t9"));
        Observation o = r.read(0);
        assertTrue(o.unavailable().isPresent());
        assertTrue(o.events().stream().noneMatch(e -> e.kind() == Kind.SETTLED));
    }

    @Test
    void staleAndForeignCwdRolloutsAreNotAdopted() throws IOException {
        var r = create();
        r.arm(M1);
        append(rollout(T1, cwd.toString(), launched.minusSeconds(3600)), started("t1") + user(T1, "t1", M1));
        append(rollout(T2, "/other/checkout", launched.plusSeconds(1)), started("t1") + user(T2, "t1", M1));
        Observation o = r.read(0);
        assertTrue(o.events().isEmpty());
        assertTrue(o.unavailable().isEmpty(), "non-candidates are not a fault");
    }

    @Test
    void partialLineIsKeptAtItsBoundaryUntilCompleted() throws IOException {
        var r = create();
        r.arm(M1);
        Path f = fresh(T1);
        String line = user(T1, "t1", M1);
        append(f, started("t1") + line.substring(0, line.length() / 2));
        assertTrue(r.read(0).events().isEmpty());
        append(f, line.substring(line.length() / 2));
        assertEquals(Kind.BOUND, r.read(0).events().get(0).kind());
    }

    @Test
    void multipleCompleteLinesInOneChunkPreserveEventOrder() throws IOException {
        var r = create();
        r.arm(M1);
        Path f = fresh(T1);
        append(f, started("t1") + user(T1, "t1", M1) + done("task_complete", "t1"));

        Observation o = r.read(0);
        assertEquals(List.of(
                new Event(1, Kind.BOUND, M1, T1),
                new Event(2, Kind.SETTLED, M1, T1)), o.events());
        assertTrue(o.unavailable().isEmpty());
    }

    @Test
    void newlineAtChunkBoundaryPreservesFollowingLineOffsets() throws IOException {
        var r = create();
        r.arm(M1);
        Path f = fresh(T1);
        int chunkSize = 64 * 1024;
        String firstLine = started("t1");
        String fillerPrefix = "{\"type\":\"ignored\",\"pad\":\"";
        String fillerSuffix = "\"}";
        int fillerBodyLength = chunkSize - (int) Files.size(f)
                - firstLine.getBytes(StandardCharsets.UTF_8).length - 1;
        int paddingLength = fillerBodyLength - fillerPrefix.length() - fillerSuffix.length();
        assertTrue(paddingLength >= 0, "fixture header should leave room for a boundary line");
        String ignoredLine = fillerPrefix + "x".repeat(paddingLength) + fillerSuffix + "\n";
        append(f, firstLine + ignoredLine + user(T1, "t1", M1) + done("task_complete", "t1"));

        Observation o = r.read(0);
        assertEquals(List.of(
                new Event(1, Kind.BOUND, M1, T1),
                new Event(2, Kind.SETTLED, M1, T1)), o.events());
        assertTrue(o.unavailable().isEmpty());
    }

    @Test
    void truncationAndRotationAreUnavailable() throws IOException {
        var r = create();
        r.arm(M1);
        Path f = fresh(T1);
        append(f, started("t1") + user(T1, "t1", "hello"));
        r.read(0);
        Files.writeString(f, "{}\n");
        Observation o = r.read(0);
        assertTrue(o.unavailable().orElse("").contains("truncated"));

        var r2 = create();
        r2.arm(M2);
        Path g = fresh(T2);
        append(g, started("t1"));
        r2.read(0);
        Files.delete(g);
        Files.writeString(g, "x".repeat(4096));
        assertTrue(r2.read(0).unavailable().isPresent());
    }

    @Test
    void lineLimitPlusOneIsUnavailableButLimitItselfIsAccepted() throws IOException {
        int max = CodexEditorAskRolloutReader.MAX_LINE_BYTES;
        var ok = create();
        ok.arm(M1);
        Path f = fresh(T1);
        append(f, "x".repeat(max) + "\n" + started("t1") + user(T1, "t1", M1));
        assertEquals(1, ok.read(0).events().size());

        var over = create();
        over.arm(M2);
        Path g = fresh(T2);
        append(g, "x".repeat(max + 1) + "\n" + started("t1") + user(T2, "t1", M2));
        Observation o = over.read(0);
        assertTrue(o.events().isEmpty());
        assertTrue(o.unavailable().orElse("").contains("over"));
    }

    @Test
    void fileCountLimitPlusOneIsUnavailable() throws IOException {
        for (int i = 0; i < CodexEditorAskRolloutReader.MAX_FILES; i++) {
            rollout(String.format("01a00000-0000-7000-8000-%012d", i + 10), "/other", launched.plusSeconds(1));
        }
        var r = create();
        r.arm(M1);
        assertTrue(r.read(0).unavailable().isEmpty(), "exactly the limit is fine");
        rollout(T2, "/other", launched.plusSeconds(1));
        assertTrue(r.read(0).unavailable().orElse("").contains("candidate"));
    }

    @Test
    void scanByteBudgetIsUnavailableThenProgresses() throws IOException {
        var r = create();
        r.arm(M1);
        Path f = fresh(T1);
        String filler = "{\"pad\":\"" + "y".repeat(CodexEditorAskRolloutReader.MAX_LINE_BYTES - 100) + "\"}\n";
        append(f, started("t1") + filler.repeat(9) + user(T1, "t1", M1));
        Observation first = r.read(0);
        assertTrue(first.events().isEmpty());
        assertTrue(first.unavailable().orElse("").contains("budget"));
        Observation second = r.read(0);
        assertEquals(Kind.BOUND, second.events().get(0).kind());
    }

    @Test
    void deadlineIsUnavailable() throws IOException {
        Clock ticking = new Clock() {
            private long now = 1_000_000;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { now += 2000; return Instant.ofEpochMilli(now); }
        };
        var r = new CodexEditorAskRolloutReader(store, cwd, launched, ticking, Duration.ofMillis(1500));
        Path f = fresh(T1);
        r.arm(M1);
        append(f, started("t1") + user(T1, "t1", M1));
        Observation o = r.read(0);
        assertTrue(o.unavailable().orElse("").contains("deadline"));
    }

    @Test
    void resumeIgnoresEarlierCompletionAndOnlyUsesExactThread() throws IOException {
        Path f = rollout(T1, cwd.toString(), launched.minusSeconds(86_400));
        append(f, started("old") + user(T1, "old", M1) + done("task_complete", "old"));
        Path other = rollout(T2, cwd.toString(), launched.minusSeconds(86_400));
        var r = new CodexEditorAskRolloutReader(store, cwd, T1, Clock.systemUTC(), Duration.ofSeconds(30));
        r.arm(M1);
        assertTrue(r.read(0).events().isEmpty(), "pre-fence BOUND/SETTLED never surface");
        append(other, started("n") + user(T2, "n", M1) + done("task_complete", "n"));
        assertTrue(r.read(0).events().isEmpty(), "another thread is ignored");
        append(f, started("new") + user(T1, "new", M1) + done("task_complete", "new"));
        Observation o = r.read(0);
        assertEquals(2, o.events().size());
        assertEquals(T1, o.events().get(1).conversationId());
        assertTrue(o.unavailable().isEmpty());
    }

    @Test
    void armedAfterSettledTurnIgnoresItsCompletion() throws IOException {
        var r = create();
        r.arm(M1);
        Path f = fresh(T1);
        append(f, started("a") + user(T1, "a", M1) + done("task_complete", "a"));
        r.read(0);
        long fence2 = r.arm(M2);
        append(f, started("b") + user(T1, "b", M2));
        Observation o = r.read(fence2);
        assertEquals(1, o.events().size());
        assertEquals(M2, o.events().get(0).marker());
    }
}
