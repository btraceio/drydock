package app.drydock.editorqa;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.drydock.domain.ManagedSessionId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

/**
 * The versioned, bounded wire format of one history partition (one
 * checkout's editor asks). Pure encode/decode, no I/O.
 *
 * <p>Shape: {@code {"version":1,"root":<canonical checkout root>,
 * "lastSequence":n,"records":[...]}}. The full canonical root is stored so
 * the reader can verify it against the partition it meant to open: the file
 * name is only a hash of it. {@code lastSequence} is the highest acceptance
 * sequence ever admitted, kept separately from the records so eviction
 * never lets a sequence be reused.</p>
 *
 * <p>Every bound is checked before the work it guards: the byte length
 * before any decoding, the JSON nesting depth by the strict parser, the
 * record count before any record is built, and each record's fields by
 * {@link EditorAskRecord}'s own constructor. A violation is a
 * {@link CorruptPartitionException}; nothing is clipped or repaired.</p>
 */
public final class EditorAskPartitionCodec {

    /** The only format version this codec reads and writes. */
    public static final int VERSION = 1;

    /** JSON nesting limit; a record with an anchor nests well under this. */
    public static final int MAX_DEPTH = 16;

    /** Most records a valid partition can hold: the settled cap plus the unfinished cap. */
    public static final int MAX_RECORDS = EditorAskLimits.MAX_SETTLED_RECORDS + EditorAskLimits.MAX_UNFINISHED_RECORDS;

    private EditorAskPartitionCodec() {
    }

    /** A decoded partition. */
    public record Partition(String root, long lastSequence, List<EditorAskRecord> records) {
        public Partition {
            records = List.copyOf(records);
        }
    }

    /** Why a partition is unreadable: malformed, over a bound, wrong version, or belonging to another root. */
    public static final class CorruptPartitionException extends Exception {

        private static final long serialVersionUID = 1L;

        public CorruptPartitionException(String message) {
            super(message);
        }

        public CorruptPartitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Encodes a partition to UTF-8 JSON bytes, refusing one over {@link EditorAskLimits#MAX_PARTITION_BYTES}. */
    public static byte[] encode(String root, long lastSequence, List<EditorAskRecord> records) {
        EditorAskLimits.checkRoot(root);
        if (records.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("partition holds " + records.size() + " records; the limit is " + MAX_RECORDS);
        }
        List<JsonValue> encoded = new ArrayList<>(records.size());
        for (EditorAskRecord record : records) {
            encoded.add(encodeRecord(record));
        }
        JsonObject doc = JsonObject.empty()
                .put("version", JsonNumber.of(VERSION))
                .put("root", new JsonString(root))
                .put("lastSequence", JsonNumber.of(lastSequence))
                .put("records", JsonArray.of(encoded));
        byte[] bytes = JsonWriter.write(doc).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > EditorAskLimits.MAX_PARTITION_BYTES) {
            throw new IllegalArgumentException("partition is " + bytes.length + " bytes; the limit is "
                    + EditorAskLimits.MAX_PARTITION_BYTES);
        }
        return bytes;
    }

    /**
     * Decodes {@code bytes} and verifies the stored root equals {@code expectedRoot}.
     *
     * @throws CorruptPartitionException on any malformation, bound violation or root mismatch
     */
    public static Partition decode(byte[] bytes, String expectedRoot) throws CorruptPartitionException {
        if (bytes.length > EditorAskLimits.MAX_PARTITION_BYTES) {
            throw new CorruptPartitionException("partition is " + bytes.length + " bytes; the limit is "
                    + EditorAskLimits.MAX_PARTITION_BYTES);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CorruptPartitionException("partition is not valid UTF-8", e);
        }
        try {
            JsonValue parsed = JsonParser.parseStrict(text, MAX_DEPTH);
            if (!(parsed instanceof JsonObject doc)) {
                throw new CorruptPartitionException("partition root is not an object");
            }
            long version = number(doc, "version");
            if (version != VERSION) {
                throw new CorruptPartitionException("unsupported partition version " + version);
            }
            String root = string(doc, "root");
            if (!root.equals(expectedRoot)) {
                throw new CorruptPartitionException("partition belongs to a different checkout than the one requested");
            }
            long lastSequence = number(doc, "lastSequence");
            if (!(doc.get("records") instanceof JsonArray array)) {
                throw new CorruptPartitionException("records is not an array");
            }
            if (array.elements().size() > MAX_RECORDS) {
                throw new CorruptPartitionException("partition holds " + array.elements().size()
                        + " records; the limit is " + MAX_RECORDS);
            }
            List<EditorAskRecord> records = new ArrayList<>(array.elements().size());
            Set<String> ids = new HashSet<>();
            long previous = -1;
            for (JsonValue element : array.elements()) {
                EditorAskRecord record = decodeRecord(element);
                if (!ids.add(record.id())) {
                    throw new CorruptPartitionException("duplicate record ID in partition");
                }
                if (record.acceptanceSequence() <= previous || record.acceptanceSequence() > lastSequence) {
                    throw new CorruptPartitionException("record sequences are not strictly increasing and within lastSequence");
                }
                previous = record.acceptanceSequence();
                records.add(record);
            }
            return new Partition(root, lastSequence, records);
        } catch (CorruptPartitionException e) {
            throw e;
        } catch (RuntimeException e) {
            // JsonParseException, IllegalArgumentException from record validation, NumberFormat/DateTimeParse.
            throw new CorruptPartitionException("partition is malformed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static JsonValue encodeRecord(EditorAskRecord r) {
        JsonObject json = JsonObject.empty()
                .put("id", new JsonString(r.id()))
                .put("sequence", JsonNumber.of(r.acceptanceSequence()))
                .put("owner", new JsonString(r.owner().toString()));
        if (!r.isTab()) {
            EditorAskRecord.TextRange range = r.range();
            JsonObject rangeJson = JsonObject.empty()
                    .put("startOffset", JsonNumber.of(range.startOffset()))
                    .put("endOffset", JsonNumber.of(range.endOffset()))
                    .put("startLine", JsonNumber.of(range.startLine()))
                    .put("startColumn", JsonNumber.of(range.startColumn()))
                    .put("endLine", JsonNumber.of(range.endLine()))
                    .put("endColumn", JsonNumber.of(range.endColumn()));
            json.put("file", new JsonString(r.relativeFile()))
                    .put("range", rangeJson)
                    .put("selectedText", new JsonString(r.selectedText()));
        }
        json.put("question", new JsonString(r.question()))
                .put("askedAt", new JsonString(r.askedAt().toString()))
                .put("status", new JsonString(r.status().name()));
        if (r.failureReason() != null) {
            json.put("failureReason", new JsonString(r.failureReason()));
        }
        if (r.answer() != null) {
            json.put("answer", new JsonString(r.answer()));
        }
        if (r.answeredAt() != null) {
            json.put("answeredAt", new JsonString(r.answeredAt().toString()));
        }
        if (r.anchor() != null && !(r.anchor() instanceof JsonNull)) {
            json.put("anchor", r.anchor());
        }
        return json;
    }

    private static EditorAskRecord decodeRecord(JsonValue value) throws CorruptPartitionException {
        if (!(value instanceof JsonObject o)) {
            throw new CorruptPartitionException("record is not an object");
        }
        // A tab record omits file, range and selectedText together; a partial omission is corrupt.
        boolean tab = !o.has("file") && !o.has("range") && !o.has("selectedText");
        EditorAskRecord.TextRange range = null;
        if (!tab) {
            if (!(o.get("range") instanceof JsonObject rangeJson)) {
                throw new CorruptPartitionException("record range is not an object");
            }
            range = new EditorAskRecord.TextRange(
                    intField(rangeJson, "startOffset"), intField(rangeJson, "endOffset"),
                    intField(rangeJson, "startLine"), intField(rangeJson, "startColumn"),
                    intField(rangeJson, "endLine"), intField(rangeJson, "endColumn"));
        }
        JsonValue anchor = o.get("anchor");
        return new EditorAskRecord(
                string(o, "id"),
                number(o, "sequence"),
                ManagedSessionId.of(string(o, "owner")),
                tab ? null : string(o, "file"),
                range,
                tab ? null : string(o, "selectedText"),
                string(o, "question"),
                Instant.parse(string(o, "askedAt")),
                EditorAskRecord.Status.valueOf(string(o, "status")),
                optionalString(o, "failureReason"),
                optionalString(o, "answer"),
                o.has("answeredAt") ? Instant.parse(string(o, "answeredAt")) : null,
                anchor instanceof JsonNull ? null : anchor);
    }

    private static String string(JsonObject o, String key) throws CorruptPartitionException {
        if (o.get(key) instanceof JsonString s) {
            return s.value();
        }
        throw new CorruptPartitionException("missing or non-string field " + key);
    }

    private static String optionalString(JsonObject o, String key) throws CorruptPartitionException {
        return o.has(key) ? string(o, key) : null;
    }

    private static long number(JsonObject o, String key) throws CorruptPartitionException {
        if (o.get(key) instanceof JsonNumber n) {
            return n.asLong();
        }
        throw new CorruptPartitionException("missing or non-number field " + key);
    }

    private static int intField(JsonObject o, String key) throws CorruptPartitionException {
        long v = number(o, key);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new CorruptPartitionException("field " + key + " is out of int range");
        }
        return (int) v;
    }
}
