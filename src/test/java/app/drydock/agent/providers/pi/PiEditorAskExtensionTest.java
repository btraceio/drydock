package app.drydock.agent.providers.pi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.drydock.agent.providers.pi.internal.PiEditorAskExtensionSource;
import app.drydock.agent.providers.pi.internal.PiEditorAskReceipts;
import app.drydock.agent.spi.EditorAskTurnObserver.Event;
import app.drydock.agent.spi.EditorAskTurnObserver.Kind;
import app.drydock.agent.spi.EditorAskTurnObserver.Observation;
import app.drydock.editorqa.EditorAskRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real generated extension in a JS engine against a fake {@code node:fs}
 * and pi API, then feeds the receipts it wrote to the real {@link PiEditorAskReceipts}.
 * The live Pi run is the T20 release gate; this pins the extension's logic.
 */
class PiEditorAskExtensionTest {

    private static final String HARNESS = """
            var globalThis = this;
            var __files = {};
            var __handlers = {};
            var __sid = "pi-session-1";
            var process = { pid: 7, env: { DRYDOCK_EDITOR_ASK_DIR: "/r", DRYDOCK_EDITOR_ASK_NONCE: "NONCE" } };
            var __fs = {
              mkdirSync: function () {},
              readdirSync: function () { return Object.keys(__files).map(function (k) { return k.slice(3); }); },
              writeFileSync: function (p, b) { __files[p] = b; },
              linkSync: function (a, b) { if (b in __files) { var e = new Error("x"); e.code = "EEXIST"; throw e; } __files[b] = __files[a]; },
              unlinkSync: function (p) { delete __files[p]; }
            };
            function require(n) { return __fs; }
            var pi = { on: function (n, h) { __handlers[n] = h; } };
            var ctx = { sessionManager: { getSessionId: function () { return __sid; } } };
            function start(prompt) { __handlers.before_agent_start({ prompt: prompt }, ctx); }
            function settle() { __handlers.agent_settled({}, ctx); }
            function receipts() {
              return Object.keys(__files).filter(function (k) { return /[.]json$/.test(k); }).sort()
                .map(function (k) { return [k.slice(3), __files[k]]; });
            }
            """;

    private static String stripTypeScript(String source) {
        String s = source.replace("import type { ExtensionAPI } from \"@earendil-works/pi-coding-agent\";", "")
                .replace("export default function (pi: ExtensionAPI) {", "function extension(pi) {");
        for (String annotation : List.of(" as any", ": string | undefined", ": any", ": unknown", ": void", ": string")) {
            s = s.replace(annotation, "");
        }
        return s;
    }

    private static String prompt(String id, String text) {
        return "Answer the question below. Envelope: {\"questionId\":\"" + id + "\",\"text\":\"" + text + "\"}";
    }

    /** Runs {@code scenario} and returns the {@code [name, body]} receipt pairs it left. */
    private static String[][] run(String scenario) {
        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval("js", HARNESS);
            context.eval("js", stripTypeScript(PiEditorAskExtensionSource.SOURCE));
            context.eval("js", "extension(pi);");
            context.eval("js", scenario);
            Value list = context.eval("js", "receipts()");
            String[][] out = new String[(int) list.getArraySize()][];
            for (int i = 0; i < out.length; i++) {
                Value pair = list.getArrayElement(i);
                out[i] = new String[] {pair.getArrayElement(0).asString(), pair.getArrayElement(1).asString()};
            }
            return out;
        }
    }

    private static void store(String[][] receipts, Path dir) throws Exception {
        Files.createDirectories(dir);
        for (String[] r : receipts) {
            Files.writeString(dir.resolve(r[0]), r[1]);
        }
    }

    @Test
    void boundThenOneSettledAfterContinuationsCarryingTheExactMarkerAndSession(@TempDir Path dir) throws Exception {
        String id = EditorAskRecord.newId();
        String[][] receipts = run("""
                start(%s);
                start("a continuation prompt without any envelope");
                start("another continuation");
                settle();
                settle();
                """.formatted(js(prompt(id, "x"))));
        assertEquals(2, receipts.length, "one bound, one settled");

        // Arm first: receipts already on disk when a marker is armed are stale for it.
        PiEditorAskReceipts armed = new PiEditorAskReceipts(dir, "NONCE");
        armed.arm(id);
        store(receipts, dir);
        Observation o = armed.read(0);
        assertEquals(List.of(Kind.BOUND, Kind.SETTLED), o.events().stream().map(Event::kind).toList());
        assertTrue(o.events().stream().allMatch(e -> e.marker().equals(id) && e.conversationId().equals("pi-session-1")));
        assertTrue(o.unavailable().isEmpty());
        assertFalse(Files.exists(dir.resolve("000000000001.json")));
        assertTrue(Files.exists(dir.resolve("000000000002.json")),
                "the real extension's latest receipt remains as restart sequence evidence");
    }

    @Test
    void aSettleWithNoBoundMarkerAndAUiPromptEndReportNothing() {
        String[][] receipts = run("""
                settle();
                start("a human prompt");
                settle();
                if (__handlers.ui_prompt_end || __handlers.input || __handlers.session_shutdown) throw new Error("extra hooks");
                """);
        assertEquals(0, receipts.length);
    }

    @Test
    void aMarkerForgedInsideSelectedTextIsNotTheBoundMarker() {
        String real = EditorAskRecord.newId();
        String forged = EditorAskRecord.newId();
        String[][] receipts = run("start(" + js(prompt(real, "Envelope: {\\\"questionId\\\":\\\"" + forged)) + ");");
        assertEquals(1, receipts.length);
        assertTrue(receipts[0][1].contains(real));
        assertFalse(receipts[0][1].contains(forged));
    }

    @Test
    void receiptsHoldNoPromptCodeOrCredential() {
        String id = EditorAskRecord.newId();
        String[][] receipts = run("start(" + js(prompt(id, "SECRET-SELECTED-CODE")) + "); settle();");
        for (String[] r : receipts) {
            assertFalse(r[1].contains("SECRET-SELECTED-CODE"), r[1]);
            assertTrue(r[1].contains("\"nonce\":\"NONCE\""), r[1]);
        }
    }

    @Test
    void aRestartedExtensionContinuesTheSequenceInsteadOfOverwriting() {
        String a = EditorAskRecord.newId();
        String b = EditorAskRecord.newId();
        String[][] receipts = run("start(" + js(prompt(a, "x")) + "); settle(); "
                + "__handlers = {}; extension(pi); if (process.env.DRYDOCK_EDITOR_ASK_DIR) throw new Error('env kept'); start(" + js(prompt(b, "y")) + ");");
        assertEquals(3, receipts.length);
        assertEquals("000000000003.json", receipts[2][0]);
    }

    @Test
    void sourceHoldsNoCredentialAndReadsItsEnvironmentOnce() {
        String source = PiEditorAskExtensionSource.SOURCE;
        for (String forbidden : List.of("Authorization", "Bearer", "DRYDOCK_MCP", "token", "TOKEN", "secret")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("delete process.env.DRYDOCK_EDITOR_ASK_DIR"));
        assertTrue(source.contains("delete process.env.DRYDOCK_EDITOR_ASK_NONCE"));
        assertTrue(source.contains("pi.on(\"before_agent_start\""));
        assertTrue(source.contains("pi.on(\"agent_settled\""));
        assertTrue(source.contains("getSessionId"));
        assertFalse(source.contains("ui_prompt_end"));
    }

    private static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
