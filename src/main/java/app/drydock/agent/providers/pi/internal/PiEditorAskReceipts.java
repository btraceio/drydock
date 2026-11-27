package app.drydock.agent.providers.pi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import app.drydock.agent.spi.EditorAskTurnObserver;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

/**
 * Reads the receipts {@link PiEditorAskExtensionSource} writes for one pi
 * launch and turns them into {@link EditorAskTurnObserver} evidence.
 *
 * <p>Receipts are processed in sequence order with bounded size and depth.
 * The highest safely handled receipt remains as a high-water mark so a
 * restarted extension continues its sequence; older handled files are removed
 * as best effort. Oversize or potentially evidentiary sequence collisions
 * poison the observer until close rather than silently losing a turn.</p>
 *
 * <p>Performs filesystem I/O; call off the UI thread. Cleanup on close is
 * best-effort; a late writer may recreate the private launch directory.</p>
 */
public final class PiEditorAskReceipts implements EditorAskTurnObserver {

    /** Largest receipt file read, in bytes. */
    static final int MAX_RECEIPT_BYTES = 4096;

    /** Most receipt files a directory may hold when scanned. */
    static final int MAX_PENDING_FILES = 1024;

    /** Accepted events retained for cursor re-reads. */
    static final int MAX_RETAINED_EVENTS = 1024;

    private static final Pattern RECEIPT_NAME = Pattern.compile("(\\d{12})\\.json");

    private final Path directory;
    private final String nonce;

    private final Map<String, Long> fences = new HashMap<>();
    private final Map<String, Turn> turns = new HashMap<>();
    private final List<Event> log = new ArrayList<>();
    /** Files whose contents were safely classified and must not be applied twice. */
    private final Set<Long> handled = new HashSet<>();
    /** Highest receipt sequence safely classified; its file is retained as restart evidence. */
    private long scanned;
    /** Sequence of the oldest event still retained, minus one. */
    private long evictedThrough;
    /** Permanent fail-closed state for a receipt that could have carried lost evidence. */
    private String poisonReason;
    private boolean closed;

    private enum Disposition { APPLIED, IGNORED, INVALID, UNCLASSIFIABLE }

    private static final class Turn {
        String conversationId;
        boolean settled;
    }

    /** A fresh launch: new nonce, receipts under {@code directory}. */
    public PiEditorAskReceipts(Path directory) {
        this(directory, UUID.randomUUID().toString());
    }

    public PiEditorAskReceipts(Path directory, String nonce) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.nonce = Objects.requireNonNull(nonce, "nonce");
    }

    /** The receipt directory to hand to the extension as {@code DRYDOCK_EDITOR_ASK_DIR}. */
    public Path directory() {
        return directory;
    }

    @Override
    public String launchNonce() {
        return nonce;
    }

    @Override
    public synchronized long arm(String marker) {
        if (closed) {
            throw new IllegalStateException("observer is closed");
        }
        if (poisonReason != null) {
            throw new IllegalStateException("receipt evidence is unavailable: " + poisonReason);
        }
        EditorAskRecord.parseId(marker);
        if (fences.containsKey(marker)) {
            throw new IllegalArgumentException("marker already armed");
        }
        if (fences.size() >= MAX_ARMED_MARKERS) {
            throw new IllegalStateException("too many armed markers");
        }
        // Everything already on disk predates this turn.
        long fence = scanned;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            int seen = 0;
            for (Path entry : entries) {
                if (++seen > MAX_PENDING_FILES) {
                    throw new IllegalStateException("receipt backlog exceeds bound");
                }
                var match = RECEIPT_NAME.matcher(entry.getFileName().toString());
                if (match.matches()) {
                    fence = Math.max(fence, Long.parseLong(match.group(1)));
                }
            }
        } catch (NoSuchFileException absent) {
            // No receipt directory before the first launch artifact is created.
        } catch (IOException e) {
            throw new IllegalStateException("receipt directory unreadable", e);
        }
        fences.put(marker, fence);
        turns.put(marker, new Turn());
        return fence;
    }

    @Override
    public synchronized Observation read(long cursor) {
        if (closed) {
            return new Observation(List.of(), cursor, Optional.of("observer is closed"));
        }
        Optional<String> unavailable = ingest();
        if (cursor < evictedThrough) {
            return new Observation(List.of(), cursor, Optional.of("cursor older than retained evidence"));
        }
        List<Event> out = new ArrayList<>();
        long next = cursor;
        for (Event event : log) {
            if (event.sequence() > cursor) {
                if (out.size() >= MAX_EVENTS_PER_READ) {
                    break;
                }
                out.add(event);
                next = event.sequence();
            }
        }
        return new Observation(out, next, unavailable);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        fences.clear();
        turns.clear();
        log.clear();
        handled.clear();
        scanned = 0;
        evictedThrough = 0;
        poisonReason = null;
        deleteLaunchDirectory();
    }

    /** Folds pending receipts into the log, lowest sequence first. */
    private Optional<String> ingest() {
        if (poisonReason != null) {
            return Optional.of(poisonReason);
        }
        TreeMap<Long, Path> pending = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            int seen = 0;
            for (Path entry : entries) {
                if (++seen > MAX_PENDING_FILES) {
                    return Optional.of("receipt backlog exceeds bound");
                }
                var match = RECEIPT_NAME.matcher(entry.getFileName().toString());
                if (match.matches()) {
                    pending.put(Long.parseLong(match.group(1)), entry);
                }
            }
        } catch (NoSuchFileException absent) {
            return scanned == 0 ? Optional.empty() : Optional.of("receipt directory unreadable");
        } catch (IOException | RuntimeException e) {
            return Optional.of("receipt directory unreadable");
        }

        // Files removed outside this observer no longer need deletion tracking.
        handled.retainAll(pending.keySet());
        Optional<String> unavailable = Optional.empty();
        for (Map.Entry<Long, Path> receipt : pending.entrySet()) {
            long fileSeq = receipt.getKey();
            if (handled.contains(fileSeq)) {
                continue;
            }

            byte[] bytes;
            try {
                bytes = readBounded(receipt.getValue());
            } catch (IOException | RuntimeException e) {
                unavailable = Optional.of("receipt unreadable");
                break;
            }
            if (bytes == null) {
                poisonReason = "receipt exceeds size bound";
                unavailable = Optional.of(poisonReason);
                break;
            }

            boolean newSequence = fileSeq > scanned;
            Disposition disposition = accept(fileSeq, bytes, newSequence);
            if (disposition == Disposition.UNCLASSIFIABLE) {
                poisonReason = "receipt sequence collision could contain turn evidence";
                unavailable = Optional.of(poisonReason);
                break;
            }
            if (newSequence) {
                // All non-unclassifiable dispositions are safely accounted for.
                scanned = fileSeq;
            }
            handled.add(fileSeq);
        }
        cleanupHandled();
        if (poisonReason != null) {
            return Optional.of(poisonReason);
        }
        return unavailable;
    }

    /** At most {@link #MAX_RECEIPT_BYTES} bytes, or null when the file is larger. */
    private static byte[] readBounded(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_RECEIPT_BYTES + 1);
            return bytes.length > MAX_RECEIPT_BYTES ? null : bytes;
        }
    }

    /** Classifies one receipt; lower/equal sequences may be checked but never applied. */
    private Disposition accept(long fileSeq, byte[] bytes, boolean allowApply) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return Disposition.INVALID;
        }
        JsonValue.JsonObject object;
        try {
            if (!(JsonParser.parseStrict(text, 4) instanceof JsonValue.JsonObject parsed)) {
                return Disposition.INVALID;
            }
            object = parsed;
        } catch (JsonParseException e) {
            return Disposition.INVALID;
        }
        if (!(object.get("nonce") instanceof JsonValue.JsonString receiptNonce)
                || !nonce.equals(receiptNonce.value())) {
            return Disposition.IGNORED;
        }
        if (!(object.get("seq") instanceof JsonValue.JsonNumber sequenceValue)
                || sequenceValue.asDouble() != fileSeq) {
            return Disposition.INVALID;
        }
        Kind kind;
        if (object.get("kind") instanceof JsonValue.JsonString kindValue) {
            kind = switch (kindValue.value()) {
                case "bound" -> Kind.BOUND;
                case "settled" -> Kind.SETTLED;
                default -> null;
            };
        } else {
            kind = null;
        }
        if (kind == null
                || !(object.get("marker") instanceof JsonValue.JsonString markerValue)
                || !(object.get("sessionId") instanceof JsonValue.JsonString sessionValue)) {
            return Disposition.INVALID;
        }
        Event event;
        try {
            event = new Event(fileSeq, kind, markerValue.value(), sessionValue.value());
        } catch (IllegalArgumentException e) {
            return Disposition.INVALID;
        }
        Turn turn = turns.get(markerValue.value());
        Long fence = fences.get(markerValue.value());
        if (turn == null || fence == null || fileSeq <= fence || turn.settled) {
            return Disposition.IGNORED;
        }
        if (!allowApply) {
            return Disposition.UNCLASSIFIABLE;
        }
        if (kind == Kind.BOUND) {
            if (turn.conversationId != null) {
                return Disposition.IGNORED;
            }
            turn.conversationId = sessionValue.value();
        } else {
            if (turn.conversationId == null || !turn.conversationId.equals(sessionValue.value())) {
                return Disposition.IGNORED;
            }
            turn.settled = true;
        }
        log.add(event);
        if (log.size() > MAX_RETAINED_EVENTS) {
            evictedThrough = log.remove(0).sequence();
        }
        return Disposition.APPLIED;
    }

    /** Delete already-classified lower sequences; retain the current high-water file. */
    private void cleanupHandled() {
        List<Long> removable = new ArrayList<>();
        for (long fileSeq : handled) {
            if (fileSeq < scanned) {
                removable.add(fileSeq);
            }
        }
        for (long fileSeq : removable) {
            Path file = directory.resolve(String.format("%012d.json", fileSeq));
            try {
                Files.deleteIfExists(file);
                handled.remove(fileSeq);
            } catch (IOException | RuntimeException ignored) {
                // Keep its known-handled identity so it will not be applied twice.
            }
        }
    }

    private void deleteLaunchDirectory() {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Closing an observer is best effort and must not delete its shared parent.
        }
    }
}
