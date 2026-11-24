package app.drydock.agent.providers.claude.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import app.drydock.agent.spi.EditorAskTurnObserver;
import app.drydock.editorqa.EditorAskRecord;

/**
 * Editor-ask turn evidence for Claude, read from the launch-private hook
 * event log written by {@link ClaudeHookInstaller#installEditorAskLaunch}.
 *
 * <p>Each complete physical line is ingested once and applied in order to the
 * turns armed at that time (event sequence = 1-based line number). A
 * {@code UserPromptSubmit} carrying exactly one marker binds that marker to a
 * session. A {@code Stop} is only a candidate: the turn settles through a
 * later same-session {@code idle_prompt}. Intervening same-session work
 * invalidates the candidate, and another prompt poisons the turn. Silence
 * never settles. Unverified hook semantics fail closed as unavailable.</p>
 *
 * <p>The launch log is append-only. Incomplete trailing bytes are reread from
 * the last newline boundary on the next observation. A hook that runs after
 * {@link #close()} may recreate the best-effort-deleted launch artifacts.</p>
 */
public final class ClaudeEditorAskObserver implements EditorAskTurnObserver {

    /** Largest event log read; beyond it the evidence is unavailable. */
    static final long MAX_LOG_BYTES = 1L << 20;
    private static final int MAX_LINE_CHARS = 4096;
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final String IDLE_PROMPT = "idle_prompt";

    private record Line(long seq, String event, String session, String ntype, boolean agent,
                        List<String> markers) {}

    private static final class Turn {
        final long fence;
        String session;
        boolean poisoned;
        boolean settled;
        long candidate;

        Turn(long fence) {
            this.fence = fence;
        }

        Turn(Turn other) {
            fence = other.fence;
            session = other.session;
            poisoned = other.poisoned;
            settled = other.settled;
            candidate = other.candidate;
        }
    }

    private final String nonce;
    private final Path eventLog;
    private final Path launchSettingsFile;
    private final boolean versionVerified;
    private final Map<String, Turn> armed = new HashMap<>();
    private final List<Event> events = new ArrayList<>();
    /** Last committed newline boundary, never including an incomplete line. */
    private long committedOffset;
    /** Last complete physical line sequence, including ignored/malformed lines. */
    private long sequence;
    /** Non-null after a committed source is truncated or replaced. */
    private String brokenReason;
    private Object committedFileKey;
    private boolean closed;

    /**
     * @param versionVerified whether the installed Claude version's hook
     *                        semantics are verified for this evidence; false
     *                        makes every read {@code unavailable}
     */
    public ClaudeEditorAskObserver(String nonce, Path eventLog, boolean versionVerified) {
        this(nonce, eventLog, null, versionVerified);
    }

    private ClaudeEditorAskObserver(String nonce, Path eventLog, Path launchSettingsFile,
                                    boolean versionVerified) {
        if (!NONCE.matcher(Objects.requireNonNull(nonce, "nonce")).matches()) {
            throw new IllegalArgumentException("invalid launch nonce");
        }
        this.nonce = nonce;
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.launchSettingsFile = launchSettingsFile;
        this.versionVerified = versionVerified;
    }

    public static ClaudeEditorAskObserver forLaunch(ClaudeHookInstaller.EditorAskLaunchHooks hooks,
                                                    boolean versionVerified) {
        Objects.requireNonNull(hooks, "hooks");
        return new ClaudeEditorAskObserver(hooks.nonce(), hooks.eventLog(), hooks.settingsFile(), versionVerified);
    }

    @Override
    public String launchNonce() {
        return nonce;
    }

    @Override
    public synchronized long arm(String marker) {
        EditorAskRecord.parseId(marker);
        if (closed) {
            throw new IllegalStateException("observer closed");
        }
        if (armed.containsKey(marker)) {
            throw new IllegalArgumentException("marker already armed");
        }
        if (armed.size() >= MAX_ARMED_MARKERS) {
            throw new IllegalStateException("too many armed markers");
        }
        try {
            ingest();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Claude hook event log unavailable while arming", e);
        }
        armed.put(marker, new Turn(sequence));
        return sequence;
    }

    @Override
    public synchronized Observation read(long cursor) {
        if (closed) {
            return unavailable(cursor, "observer closed");
        }
        if (!versionVerified) {
            return unavailable(cursor, "Claude version not verified for editor-ask turn evidence");
        }
        try {
            ingest();
        } catch (IOException | RuntimeException e) {
            return unavailable(cursor, "Claude hook event log unreadable or over its bound");
        }
        List<Event> out = new ArrayList<>();
        for (Event event : events) {
            if (event.sequence() > cursor) {
                if (out.size() >= MAX_EVENTS_PER_READ) {
                    break;
                }
                out.add(event);
            }
        }
        long next = out.isEmpty() ? cursor : out.get(out.size() - 1).sequence();
        return new Observation(out, next, Optional.empty());
    }

    /** Reads and commits only complete lines appended since the last boundary. */
    private void ingest() throws IOException {
        if (brokenReason != null) {
            throw new IllegalStateException(brokenReason);
        }

        Object currentFileKey;
        try {
            currentFileKey = Files.readAttributes(eventLog, BasicFileAttributes.class)
                    .fileKey();
        } catch (NoSuchFileException absent) {
            if (committedOffset == 0) {
                return;
            }
            brokenReason = "event log disappeared after evidence was committed";
            throw new IllegalStateException(brokenReason, absent);
        }
        if (committedOffset > 0 && committedFileKey != null && currentFileKey != null
                && !committedFileKey.equals(currentFileKey)) {
            brokenReason = "event log was replaced after evidence was committed";
            throw new IllegalStateException(brokenReason);
        }

        long available = MAX_LOG_BYTES + 1 - committedOffset;
        ByteBuffer buffer = ByteBuffer.allocate((int) available);
        Object fileKeyAfterRead;
        try (FileChannel channel = FileChannel.open(eventLog, StandardOpenOption.READ)) {
            if (channel.size() < committedOffset) {
                brokenReason = "event log was truncated after evidence was committed";
                throw new IllegalStateException(brokenReason);
            }
            channel.position(committedOffset);
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer);
                if (count < 0) {
                    break;
                }
            }
            fileKeyAfterRead = Files.readAttributes(eventLog, BasicFileAttributes.class).fileKey();
        } catch (NoSuchFileException absent) {
            if (committedOffset == 0) {
                return;
            }
            brokenReason = "event log disappeared after evidence was committed";
            throw new IllegalStateException(brokenReason, absent);
        }
        if (currentFileKey != null && fileKeyAfterRead != null && !currentFileKey.equals(fileKeyAfterRead)) {
            if (committedOffset > 0) {
                brokenReason = "event log was replaced after evidence was committed";
            }
            throw new IllegalStateException("event log changed while it was being read");
        }
        if (committedOffset > 0 && committedFileKey != null && fileKeyAfterRead != null
                && !committedFileKey.equals(fileKeyAfterRead)) {
            brokenReason = "event log was replaced after evidence was committed";
            throw new IllegalStateException(brokenReason);
        }
        currentFileKey = fileKeyAfterRead;

        int byteCount = buffer.position();
        if (committedOffset + byteCount > MAX_LOG_BYTES) {
            throw new IllegalStateException("log over bound");
        }
        byte[] appended = buffer.array();
        long nextSequence = sequence;
        long nextOffset = committedOffset;
        List<Line> addedLines = new ArrayList<>();
        int spanStart = 0;
        for (int index = 0; index < byteCount; index++) {
            if (appended[index] == '\n') {
                String raw = new String(appended, spanStart, index - spanStart, StandardCharsets.UTF_8);
                Line line = parseLine(++nextSequence, raw);
                if (line != null) {
                    addedLines.add(line);
                }
                nextOffset = committedOffset + index + 1;
                spanStart = index + 1;
            }
        }

        // Stage all FSM transitions before committing parser offsets or events.
        Map<String, Turn> updatedTurns = new HashMap<>();
        for (Map.Entry<String, Turn> entry : armed.entrySet()) {
            updatedTurns.put(entry.getKey(), new Turn(entry.getValue()));
        }
        List<Event> addedEvents = new ArrayList<>();
        for (Line line : addedLines) {
            for (Map.Entry<String, Turn> entry : updatedTurns.entrySet()) {
                applyLine(entry.getKey(), entry.getValue(), line, addedEvents);
            }
        }

        armed.putAll(updatedTurns);
        events.addAll(addedEvents);
        committedOffset = nextOffset;
        sequence = nextSequence;
        if (committedOffset > 0 && currentFileKey != null) {
            committedFileKey = currentFileKey;
        }
    }

    private static void applyLine(String marker, Turn turn, Line line, List<Event> out) {
        if (line.seq() <= turn.fence || turn.settled || turn.poisoned) {
            return;
        }
        if (turn.session == null) {
            if (line.event().equals("UserPromptSubmit") && line.markers().size() == 1
                    && line.markers().get(0).equals(marker)) {
                turn.session = line.session();
                out.add(new Event(line.seq(), Kind.BOUND, marker, line.session()));
            }
            return;
        }
        if (!line.session().equals(turn.session)) {
            return;
        }
        switch (line.event()) {
            case "Stop" -> turn.candidate = line.agent() ? 0 : line.seq();
            case "Notification" -> {
                if (line.ntype().equals(IDLE_PROMPT) && !line.agent() && turn.candidate > 0) {
                    turn.settled = true;
                    out.add(new Event(line.seq(), Kind.SETTLED, marker, turn.session));
                } else if (!line.ntype().equals(IDLE_PROMPT)) {
                    turn.candidate = 0;
                }
            }
            case "UserPromptSubmit" -> {
                turn.candidate = 0;
                turn.poisoned = true;
            }
            default -> turn.candidate = 0;
        }
    }

    /** One record per physical line; malformed/foreign lines still consume a sequence. */
    private Line parseLine(long seq, String raw) {
        if (raw.length() > MAX_LINE_CHARS) {
            return null;
        }
        String[] fields = raw.split("\\t", -1);
        if (fields.length != 6 || !fields[0].equals(nonce)
                || fields[2].isBlank() || fields[2].length() > 256) {
            return null;
        }
        List<String> markers = new ArrayList<>();
        for (String marker : fields[5].split(",")) {
            if (!marker.isEmpty() && !markers.contains(marker)) {
                markers.add(marker);
            }
        }
        return new Line(seq, fields[1], fields[2], fields[3], fields[4].equals("1"), markers);
    }

    private static Observation unavailable(long cursor, String why) {
        return new Observation(List.of(), cursor, Optional.of(why));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        armed.clear();
        events.clear();
        committedOffset = 0;
        sequence = 0;
        brokenReason = null;
        committedFileKey = null;
        deleteBestEffort(eventLog);
        if (launchSettingsFile != null) {
            deleteBestEffort(launchSettingsFile);
        }
        Path launchDirectory = eventLog.getParent();
        if (launchDirectory != null && launchDirectory.getFileName() != null
                && launchDirectory.getFileName().toString().equals(nonce)) {
            deleteBestEffort(launchDirectory);
        }
    }

    private static void deleteBestEffort(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // Lifecycle cleanup must never escape the observer SPI.
        }
    }
}
