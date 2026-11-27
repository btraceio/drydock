package app.drydock.editorqa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskPartitionCodec.CorruptPartitionException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

class EditorAskPartitionCodecTest {

    static final String ROOT = "/work/repo";
    static final ManagedSessionId OWNER = ManagedSessionId.newId();

    static EditorAskRecord record(long seq, EditorAskRecord.Status status) {
        Instant asked = Instant.parse("2026-01-01T00:00:00Z");
        EditorAskRecord base = new EditorAskRecord(EditorAskRecord.newId(), seq, OWNER, "src/A.java",
                new EditorAskRecord.TextRange(3, 9, 0, 3, 1, 2), "sel\t\u00e9\uD83D\uDE00\n\"q\"", "why?", asked,
                EditorAskRecord.Status.ACCEPTED, null, null, null, null);
        return switch (status) {
            case ANSWERED -> base.answered("full \\ answer\nline", Instant.parse("2026-01-01T00:01:02.345Z"));
            case ACCEPTED -> base;
            default -> base.settled(status, status.isSettled() ? "because" : null).withAnchor(
                    JsonObject.empty().put("offset", JsonNumber.of(3)).put("digest", new JsonString("abc")));
        };
    }

    @Test
    void roundTripPreservesEveryField() throws Exception {
        List<EditorAskRecord> in = List.of(record(1, EditorAskRecord.Status.ANSWERED),
                record(2, EditorAskRecord.Status.AWAITING), record(5, EditorAskRecord.Status.FAILED_NOT_SENT));
        byte[] bytes = EditorAskPartitionCodec.encode(ROOT, 9, in);
        EditorAskPartitionCodec.Partition out = EditorAskPartitionCodec.decode(bytes, ROOT);
        assertEquals(in, out.records());
        assertEquals(9, out.lastSequence());
        assertEquals(Instant.parse("2026-01-01T00:01:02.345Z"), out.records().get(0).answeredAt());
        assertNull(out.records().get(0).anchor());
    }

    @Test
    void tabRecordRoundTripsWithoutFileRangeOrSelection() throws Exception {
        EditorAskRecord tab = new EditorAskRecord(EditorAskRecord.newId(), 4, OWNER, null, null, null, "why?",
                Instant.parse("2026-01-01T00:00:00Z"), EditorAskRecord.Status.ANSWERED, null, "yes",
                Instant.parse("2026-01-01T00:00:01Z"), null);
        List<EditorAskRecord> in = List.of(tab, record(5, EditorAskRecord.Status.ACCEPTED));
        byte[] bytes = EditorAskPartitionCodec.encode(ROOT, 5, in);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(1, text.split("\"selectedText\"", -1).length - 1);
        EditorAskPartitionCodec.Partition out = EditorAskPartitionCodec.decode(bytes, ROOT);
        assertEquals(in, out.records());
        assertNull(out.records().get(0).relativeFile());
        assertNull(out.records().get(0).range());
    }

    @Test
    void wrongRootIsRejected() {
        byte[] bytes = EditorAskPartitionCodec.encode(ROOT, 1, List.of(record(1, EditorAskRecord.Status.ACCEPTED)));
        assertThrows(CorruptPartitionException.class, () -> EditorAskPartitionCodec.decode(bytes, "/other"));
    }

    @Test
    void malformedAndWrongVersionAreRejected() {
        assertThrows(CorruptPartitionException.class,
                () -> EditorAskPartitionCodec.decode("{not json".getBytes(StandardCharsets.UTF_8), ROOT));
        assertThrows(CorruptPartitionException.class,
                () -> EditorAskPartitionCodec.decode(new byte[] {(byte) 0xC3, 0x28}, ROOT));
        String v2 = "{\"version\":2,\"root\":\"" + ROOT + "\",\"lastSequence\":0,\"records\":[]}";
        assertThrows(CorruptPartitionException.class,
                () -> EditorAskPartitionCodec.decode(v2.getBytes(StandardCharsets.UTF_8), ROOT));
    }

    @Test
    void deepNestingIsRejectedNotOverflowed() {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        assertThrows(CorruptPartitionException.class,
                () -> EditorAskPartitionCodec.decode(deep.getBytes(StandardCharsets.UTF_8), ROOT));
    }

    @Test
    void oversizeBytesAndRecordCountAreRejectedBeforeDecoding() {
        assertThrows(CorruptPartitionException.class, () -> EditorAskPartitionCodec.decode(
                new byte[(int) EditorAskLimits.MAX_PARTITION_BYTES + 1], ROOT));
        List<EditorAskRecord> many = new ArrayList<>();
        for (int i = 1; i <= EditorAskPartitionCodec.MAX_RECORDS; i++) {
            many.add(record(i, EditorAskRecord.Status.NO_ANSWER));
        }
        byte[] ok = EditorAskPartitionCodec.encode(ROOT, many.size(), many);
        assertEquals(EditorAskPartitionCodec.MAX_RECORDS, assertDecode(ok).records().size());
        many.add(record(many.size() + 1, EditorAskRecord.Status.NO_ANSWER));
        assertThrows(IllegalArgumentException.class, () -> EditorAskPartitionCodec.encode(ROOT, many.size(), many));
        // a hand-built file with one record too many is refused on read
        JsonObject doc = (JsonObject) JsonParser.parse(new String(ok, StandardCharsets.UTF_8));
        List<JsonValue> elements = new ArrayList<>(((JsonArray) doc.get("records")).elements());
        elements.add(elements.get(0));
        doc.put("records", JsonArray.of(elements));
        String over = JsonWriter.write(doc);
        assertThrows(CorruptPartitionException.class,
                () -> EditorAskPartitionCodec.decode(over.getBytes(StandardCharsets.UTF_8), ROOT));
    }

    @Test
    void invalidRecordFieldsAndOrderingAreRejected() {
        String text = new String(EditorAskPartitionCodec.encode(ROOT, 1,
                List.of(record(1, EditorAskRecord.Status.ACCEPTED))), StandardCharsets.UTF_8);
        assertThrows(CorruptPartitionException.class, () -> EditorAskPartitionCodec.decode(
                text.replace("\"status\": \"ACCEPTED\"", "\"status\": \"NOPE\"").getBytes(StandardCharsets.UTF_8), ROOT));
        assertThrows(CorruptPartitionException.class, () -> EditorAskPartitionCodec.decode(
                text.replace("\"lastSequence\": 1", "\"lastSequence\": 0").getBytes(StandardCharsets.UTF_8), ROOT));
        assertThrows(CorruptPartitionException.class, () -> EditorAskPartitionCodec.decode(
                text.replace("\"id\": \"editor-", "\"id\": \"EDITOR-").getBytes(StandardCharsets.UTF_8), ROOT));
    }

    private static EditorAskPartitionCodec.Partition assertDecode(byte[] bytes) {
        try {
            return EditorAskPartitionCodec.decode(bytes, ROOT);
        } catch (CorruptPartitionException e) {
            throw new AssertionError(e);
        }
    }
}
