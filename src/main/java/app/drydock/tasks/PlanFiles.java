package app.drydock.tasks;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * The plan file codec (T33a, P4.13): the reserved {@code plans/} subtree
 * under the store root, one versioned JSON file per plan
 * ({@code plans/<plan-id>.json}, the plan id being its target task id --
 * a target starts once, so one plan per target), written atomically
 * (temp + fsync + move, exactly the receipt codec's discipline). The
 * envelope is the schema version first, then the revision and pause flag
 * the store's compare-and-swap discipline rides on, then the plan's own
 * {@link RunningPlan} wire form, so one file round-trips byte-stably.
 *
 * <p>A file that does not parse, or whose envelope is structurally or
 * semantically invalid, is a torn tail: it blocks only its own plan and
 * can be quarantined ({@link #quarantineTorn}) -- the bytes are kept in a
 * {@code .torn} sidecar and the main file removed, because a plan file is
 * one document with no intact prefix to keep. An unknown or newer schema
 * version is refused loudly with the found version and never silently
 * reparsed; it is not quarantinable, since the bytes are healthy and the
 * version is an operator decision. Reads are bounded: a file over
 * {@link #MAX_FILE_BYTES} is refused before a single byte is read.</p>
 */
final class PlanFiles {

    /** The file-family schema version this codec writes; any other is refused loudly. */
    static final int FILE_SCHEMA_VERSION = 1;

    /** Upper byte bound before reading a plan file; a larger file is a visible refusal. */
    static final long MAX_FILE_BYTES = 4L << 20;

    /**
     * One plan file's read: the loaded record when intact, the torn bytes
     * when the file is torn (quarantinable), and the refusal reason when
     * the file is unusable. All null (record included) means absent.
     */
    record Loaded(PlanStore.PlanRecord record, String tornTail, String refusal) {
    }

    private final Path plansDir;

    PlanFiles(Path storeRoot) {
        this.plansDir = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize()
                .resolve("plans");
    }

    /** The reserved registry directory, ignored by every task scan (TaskFiles). */
    Path plansDir() {
        return plansDir;
    }

    /** The plan file of one plan id (its target task id). */
    Path fileOf(TaskId plan) {
        return plansDir.resolve(plan.text() + ".json");
    }

    /** The OS driver-claim file of one plan id -- a plan family file, never a task lock. */
    Path claimFileOf(TaskId plan) {
        return plansDir.resolve(plan.text() + ".claim");
    }

    /**
     * Fresh read of one plan's file: absent (all null), loaded, torn
     * (bytes plus reason) or refused (reason, no torn bytes -- schema
     * versions and size bounds are healthy-file refusals, kept in place).
     */
    Loaded load(TaskId plan) throws IOException {
        Path file = fileOf(plan);
        if (!Files.isRegularFile(file)) {
            return new Loaded(null, null, null);
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            return new Loaded(null, null, "exceeds " + MAX_FILE_BYTES + " bytes; refusing to read it");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonValue tree;
        try {
            tree = JsonParser.parse(text);
        } catch (JsonParseException e) {
            return torn(text, "does not parse as JSON: " + e.getMessage());
        }
        if (!(tree instanceof JsonObject root)) {
            return torn(text, "is not a JSON object");
        }
        JsonValue version = root.get("schemaVersion");
        if (!(version instanceof JsonNumber number)) {
            return torn(text, "has a missing or non-numeric schemaVersion");
        }
        int found;
        try {
            found = number.asInt();
        } catch (NumberFormatException e) {
            return torn(text, "has a non-integer schemaVersion: " + number.literal());
        }
        if (found != FILE_SCHEMA_VERSION) {
            return new Loaded(null, null, "carries schemaVersion " + found + " (expected "
                    + FILE_SCHEMA_VERSION + "); refusing to reinterpret it (P4.13)");
        }
        try {
            long revision = PlanLaunch.requireLong(root, "revision");
            if (revision < 1) {
                throw new IllegalArgumentException("Field revision must be at least 1, got " + revision);
            }
            boolean paused = PlanLaunch.requireBoolean(root, "paused");
            RunningPlan content = RunningPlan.fromJsonValue(PlanLaunch.requireMember(root, "plan"));
            return new Loaded(new PlanStore.PlanRecord(revision, paused, content), null, null);
        } catch (IllegalArgumentException e) { // structurally valid JSON, unusable plan content
            return torn(text, e.getMessage());
        }
    }

    /** Moves a torn file's bytes aside (kept, never deleted) and removes the main file.
     * Returns whether there was anything torn to quarantine. */
    boolean quarantineTorn(TaskId plan, Loaded file) throws IOException {
        if (file.tornTail() == null) {
            return false;
        }
        Path target = fileOf(plan);
        Path sidecar = target.resolveSibling(target.getFileName() + ".torn");
        Files.createDirectories(plansDir);
        Files.writeString(sidecar, "quarantined " + Instant.now() + "\n" + file.tornTail() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.deleteIfExists(target);
        return true;
    }

    /** The serialized form: fixed key order, so the same record renders byte-identically. */
    static String render(PlanStore.PlanRecord record) {
        return JsonWriter.write(JsonObject.empty()
                .put("schemaVersion", JsonNumber.of(FILE_SCHEMA_VERSION))
                .put("revision", JsonNumber.of(record.revision()))
                .put("paused", new JsonBoolean(record.paused()))
                .put("plan", record.plan().toJsonValue()));
    }

    /** Writes one plan's file atomically (temp + fsync + move, the receipt codec's discipline). */
    void write(TaskId plan, String text) throws IOException {
        writeAtomically(fileOf(plan), text);
    }

    private static Loaded torn(String text, String reason) {
        return new Loaded(null, text, reason);
    }

    /** A temp file in the target's directory, fsynced, then atomically moved over it. */
    private static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "plan", ".json.tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true); // fsync before the move: the rename publishes durable bytes
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) { // the temp file is harmless; the failure below is what matters
            }
            throw e;
        }
    }
}
