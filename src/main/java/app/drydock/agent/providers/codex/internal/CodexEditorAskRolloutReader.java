package app.drydock.agent.providers.codex.internal;

import app.drydock.agent.providers.codex.internal.CodexRolloutStore.HeaderProbe;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore.RolloutMeta;
import app.drydock.agent.spi.EditorAskTurnObserver;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, incremental reader of local interactive Codex rollouts that yields
 * {@link EditorAskTurnObserver} evidence. It is not an activity badge and
 * installs no hook, notify command or trust bypass: it only reads the rollout
 * files Codex writes.
 *
 * <p>Per marked turn it requires, in event order within one rollout: a
 * {@code task_started} for turn T, then an {@code item_completed}
 * {@code UserMessage} of that rollout's exact thread ID and turn T whose
 * structured user input holds the full marker ({@code BOUND}), then the
 * {@code task_complete}/{@code turn_aborted} of turn T ({@code SETTLED}).
 * Assistant, tool and other-session lines are never matched. The thread ID is
 * the rollout that holds the unique user marker; the same marker in two
 * rollouts/turns is ambiguity, reported unavailable.</p>
 *
 * <p>A marker's fence is each file's size when it is armed: bytes before it
 * (an earlier completion after resume) are never evidence. Only complete
 * lines are consumed, so a partial append is re-read from its boundary.
 * Bounds apply to bytes actually read: {@value #MAX_LINE_BYTES} per line,
 * {@value #MAX_SCAN_BYTES} per observation (headers included), {@value #MAX_FILES}
 * candidate files and a deadline. A limit hit, truncation, rotation, a
 * malformed line or ambiguity is {@link Observation#unavailable()}.</p>
 */
public final class CodexEditorAskRolloutReader implements EditorAskTurnObserver {

    public static final int MAX_LINE_BYTES = 1 << 20;
    public static final int MAX_SCAN_BYTES = 8 << 20;
    public static final int MAX_FILES = 128;
    private static final int CHUNK = 64 * 1024;
    private static final int MAX_STARTED_TURNS = 256;

    private final CodexRolloutStore store;
    private final Path cwd;
    private final Instant launchedAt;
    private final Optional<String> resumeThreadId;
    private final Clock clock;
    private final Duration deadline;
    private final String nonce = UUID.randomUUID().toString();

    private final Map<Path, FileState> files = new LinkedHashMap<>();
    private final Map<String, TurnState> turns = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private long sequence;
    private String fault;
    private boolean closed;

    /** Create-mode reader: candidates are interactive rollouts of {@code cwd} created at/after {@code launchedAt}. */
    public CodexEditorAskRolloutReader(CodexRolloutStore store, Path cwd, Instant launchedAt,
                                       Clock clock, Duration deadline) {
        this(store, cwd, launchedAt, Optional.empty(), clock, deadline);
    }

    /** Resume-mode reader: the only candidate is the rollout of the exact {@code resumeThreadId}. */
    public CodexEditorAskRolloutReader(CodexRolloutStore store, Path cwd, String resumeThreadId,
                                       Clock clock, Duration deadline) {
        this(store, cwd, Instant.EPOCH, Optional.of(Objects.requireNonNull(resumeThreadId)), clock, deadline);
    }

    private CodexEditorAskRolloutReader(CodexRolloutStore store, Path cwd, Instant launchedAt,
                                        Optional<String> resumeThreadId, Clock clock, Duration deadline) {
        this.store = Objects.requireNonNull(store, "store");
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.launchedAt = Objects.requireNonNull(launchedAt, "launchedAt");
        this.resumeThreadId = resumeThreadId;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    private enum Phase { ARMED, BOUND, SETTLED, AMBIGUOUS }

    private static final class TurnState {
        final Map<Path, Long> fence = new HashMap<>();
        Phase phase = Phase.ARMED;
        RolloutMeta boundIn;
        String turnId;
    }

    private static final class FileState {
        final RolloutMeta meta;
        final Object fileKey;
        long offset;
        final LinkedHashSet<String> startedTurns = new LinkedHashSet<>();

        FileState(RolloutMeta meta, Object fileKey, long offset) {
            this.meta = meta;
            this.fileKey = fileKey;
            this.offset = offset;
        }
    }

    @Override
    public String launchNonce() {
        return nonce;
    }

    @Override
    public synchronized long arm(String marker) {
        EditorAskRecord.parseId(marker);
        if (closed) {
            throw new IllegalStateException("observer is closed");
        }
        if (turns.containsKey(marker)) {
            throw new IllegalArgumentException("marker already armed: " + marker);
        }
        if (turns.size() >= MAX_ARMED_MARKERS) {
            throw new IllegalStateException("armed-marker bound reached");
        }
        Scan scan = new Scan();
        refreshCandidates(scan);
        TurnState turn = new TurnState();
        for (FileState f : files.values()) {
            long size = sizeOf(f.meta.file());
            turn.fence.put(f.meta.file(), size < 0 ? f.offset : size);
        }
        if (scan.limit != null && fault == null) {
            fault = "fence uncertain: " + scan.limit;
        }
        turns.put(marker, turn);
        return sequence;
    }

    @Override
    public synchronized Observation read(long cursor) {
        if (closed) {
            return new Observation(List.of(), cursor, Optional.of("closed"));
        }
        Scan scan = new Scan();
        boolean pending = turns.values().stream()
                .anyMatch(t -> t.phase == Phase.ARMED || t.phase == Phase.BOUND);
        if (pending) {
            refreshCandidates(scan);
            for (FileState f : new ArrayList<>(files.values())) {
                if (scan.limit != null) {
                    break;
                }
                readFile(f, scan);
            }
        }
        for (Staged st : staged) {
            if (turns.get(st.marker()).phase != Phase.AMBIGUOUS) {
                events.add(new Event(++sequence, st.kind(), st.marker(), st.conversationId()));
            }
        }
        staged.clear();
        List<Event> out = events.stream().filter(e -> e.sequence() > cursor).limit(MAX_EVENTS_PER_READ).toList();
        long next = out.isEmpty() ? cursor : out.get(out.size() - 1).sequence();
        boolean unsettled = turns.values().stream().anyMatch(t -> t.phase != Phase.SETTLED);
        String why = scan.limit != null ? scan.limit : unsettled ? fault : null;
        if (why == null && turns.values().stream().anyMatch(t -> t.phase == Phase.AMBIGUOUS)) {
            why = "ambiguous marker";
        }
        if (why == null && pending) {
            why = missing;
        }
        return new Observation(out, Math.max(cursor, next), Optional.ofNullable(why));
    }

    @Override
    public synchronized void close() {
        closed = true;
        files.clear();
    }

    /** Per-observation byte budget and the first transient limit hit. */
    private final class Scan {
        int bytes;
        String limit;
        final long stopAt = clock.millis() + deadline.toMillis();

        boolean expired() {
            if (limit == null && clock.millis() > stopAt) {
                limit = "deadline exceeded";
            }
            return limit != null;
        }
    }

    // ---- candidates -------------------------------------------------------

    /** Events found during one scan; committed only after every candidate was read, so a duplicate cancels them. */
    private record Staged(Kind kind, String marker, String conversationId) { }

    private final List<Staged> staged = new ArrayList<>();
    private final Set<Path> rejected = new LinkedHashSet<>();
    private String missing;

    private void refreshCandidates(Scan scan) {
        missing = null;
        List<Path> paths;
        if (resumeThreadId.isPresent()) {
            if (!files.isEmpty()) {
                return;
            }
            Optional<Path> found = store.fileForId(resumeThreadId.get());
            if (found.isEmpty()) {
                missing = "resume rollout not found";
                return;
            }
            paths = List.of(found.get());
        } else {
            paths = new ArrayList<>(store.bucketFilesAround(launchedAt));
            paths.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            if (paths.size() > MAX_FILES) {
                scan.limit = "more than " + MAX_FILES + " candidate rollouts";
                paths = paths.subList(0, MAX_FILES);
            }
        }
        for (Path path : paths) {
            if (files.containsKey(path) || rejected.contains(path)) {
                continue;
            }
            if (scan.bytes >= MAX_SCAN_BYTES) {
                scan.limit = "scan budget exhausted";
                return;
            }
            if (scan.expired()) {
                return;
            }
            HeaderProbe probe = store.probeHeader(path, MAX_LINE_BYTES);
            scan.bytes += probe.bytesRead();
            if (probe.tooLong()) {
                fault = "rollout header line over " + MAX_LINE_BYTES + " bytes";
                rejected.add(path);
                continue;
            }
            if (probe.incomplete()) {
                continue;
            }
            Optional<RolloutMeta> meta = probe.meta();
            boolean eligible = meta.isPresent() && CodexRolloutStore.isInteractiveFor(meta.get(), cwd)
                    && (resumeThreadId.isPresent()
                            ? meta.get().id().equals(resumeThreadId.get())
                            : !meta.get().timestamp().isBefore(launchedAt));
            if (!eligible) {
                rejected.add(path);
                continue;
            }
            files.put(path, new FileState(meta.get(), fileKeyOf(path), 0));
        }
    }

    // ---- incremental reading ----------------------------------------------

    private void readFile(FileState f, Scan scan) {
        Path path = f.meta.file();
        long size;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            size = attrs.size();
            if (!Objects.equals(f.fileKey, attrs.fileKey())) {
                fault = "rollout rotated";
                return;
            }
        } catch (NoSuchFileException e) {
            fault = "rollout removed or rotated";
            return;
        } catch (IOException e) {
            fault = "rollout unreadable";
            return;
        }
        if (size < f.offset) {
            fault = "rollout truncated";
            return;
        }
        if (size == f.offset) {
            return;
        }
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            long pos = f.offset;
            long lineStart = f.offset;
            while (pos < size && !scan.expired()) {
                int want = (int) Math.min(Math.min(CHUNK, size - pos), MAX_SCAN_BYTES - scan.bytes);
                if (want <= 0) {
                    scan.limit = "scan budget exhausted";
                    return;
                }
                ByteBuffer buf = ByteBuffer.allocate(want);
                int r = ch.read(buf, pos);
                if (r <= 0) {
                    return;
                }
                scan.bytes += r;
                byte[] data = buf.array();
                int spanStart = 0;
                for (int i = 0; i < r; i++) {
                    if (data[i] == '\n') {
                        int spanLength = i - spanStart;
                        if (acc.size() + spanLength > MAX_LINE_BYTES) {
                            fault = "rollout line over " + MAX_LINE_BYTES + " bytes";
                            return;
                        }
                        acc.write(data, spanStart, spanLength);
                        handleLine(f, lineStart, new String(acc.toByteArray(), StandardCharsets.UTF_8));
                        acc.reset();
                        lineStart = pos + i + 1;
                        f.offset = lineStart;
                        spanStart = i + 1;
                    }
                }
                int trailingLength = r - spanStart;
                if (acc.size() + trailingLength > MAX_LINE_BYTES) {
                    fault = "rollout line over " + MAX_LINE_BYTES + " bytes";
                    return;
                }
                acc.write(data, spanStart, trailingLength);
                pos += r;
            }
        } catch (IOException e) {
            fault = "rollout unreadable";
        }
    }

    private void handleLine(FileState f, long lineStart, String text) {
        if (text.isBlank()) {
            return;
        }
        JsonValue root;
        try {
            root = JsonParser.parse(text);
        } catch (RuntimeException e) {
            fault = "malformed rollout line";
            return;
        }
        if (!(root instanceof JsonValue.JsonObject obj)
                || !"event_msg".equals(str(obj, "type"))
                || !(obj.get("payload") instanceof JsonValue.JsonObject payload)) {
            return;
        }
        String type = str(payload, "type");
        String turnId = str(payload, "turn_id");
        if (type == null) {
            return;
        }
        switch (type) {
            case "task_started" -> {
                if (turnId != null) {
                    f.startedTurns.add(turnId);
                    if (f.startedTurns.size() > MAX_STARTED_TURNS) {
                        f.startedTurns.remove(f.startedTurns.iterator().next());
                    }
                }
            }
            case "item_completed" -> {
                if (payload.get("item") instanceof JsonValue.JsonObject item
                        && "UserMessage".equals(str(item, "type"))) {
                    onUserMessage(f, lineStart, str(payload, "thread_id"), turnId, userText(item));
                }
            }
            case "task_complete", "turn_aborted" -> {
                for (Map.Entry<String, TurnState> e : turns.entrySet()) {
                    TurnState t = e.getValue();
                    if (t.phase == Phase.BOUND && Objects.equals(t.turnId, turnId)
                            && t.boundIn.file().equals(f.meta.file())) {
                        t.phase = Phase.SETTLED;
                        staged.add(new Staged(Kind.SETTLED, e.getKey(), f.meta.id()));
                    }
                }
            }
            default -> { }
        }
    }

    private void onUserMessage(FileState f, long lineStart, String threadId, String turnId, String text) {
        for (Map.Entry<String, TurnState> e : turns.entrySet()) {
            TurnState t = e.getValue();
            Long fence = t.fence.get(f.meta.file());
            if (t.phase == Phase.AMBIGUOUS
                    || fence != null && lineStart < fence || !containsMarker(text, e.getKey())) {
                continue;
            }
            if (!f.meta.id().equals(threadId) || turnId == null || !f.startedTurns.contains(turnId)) {
                fault = "marker without exact thread/turn identity";
                continue;
            }
            if (t.phase == Phase.BOUND || t.phase == Phase.SETTLED) {
                t.phase = Phase.AMBIGUOUS;
                fault = "marker appears in more than one user input";
                continue;
            }
            t.phase = Phase.BOUND;
            t.boundIn = f.meta;
            t.turnId = turnId;
            staged.add(new Staged(Kind.BOUND, e.getKey(), f.meta.id()));
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static String userText(JsonValue.JsonObject item) {
        StringBuilder sb = new StringBuilder();
        if (item.get("content") instanceof JsonValue.JsonArray arr) {
            for (JsonValue v : arr.elements()) {
                if (v instanceof JsonValue.JsonObject part && "text".equals(str(part, "type"))) {
                    String s = str(part, "text");
                    if (s != null) {
                        sb.append(s).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    static boolean containsMarker(String text, String marker) {
        for (int i = text.indexOf(marker); i >= 0; i = text.indexOf(marker, i + 1)) {
            int end = i + marker.length();
            boolean before = i == 0 || !idChar(text.charAt(i - 1));
            boolean after = end == text.length() || !idChar(text.charAt(end));
            if (before && after) {
                return true;
            }
        }
        return false;
    }

    private static boolean idChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private static String str(JsonValue.JsonObject o, String key) {
        return o.get(key) instanceof JsonValue.JsonString s ? s.value() : null;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private static Object fileKeyOf(Path p) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }
}
