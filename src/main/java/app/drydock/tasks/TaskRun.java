package app.drydock.tasks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable run receipt row (T27a, D10/D13/P4). A row keeps its session
 * id, worktree, owner-catalog and consumer paths as PATHS, never catalog
 * objects, so provenance survives session catalog deletion. A PREPARE row
 * is a live reservation until a FAILURE or FORGET row releases it; PREPARE
 * without LAUNCH stays live (P4: spawn not having persisted the session id
 * yet never interrupts a pending binding). Rows are guarded on disk
 * ({@link #render}/{@link #parse}) and bounded per task by {@link TaskRuns}
 * through {@link #MAX_ROWS_PER_TASK}.
 */
public record TaskRun(TaskId task, Phase phase, Instant at, String session, Path worktree,
        Path ownerCatalog, List<Path> consumers, String note) {

    /** The launch lifecycle a row records; LAUNCH follows PREPARE, FAILURE and FORGET release. */
    public enum Phase { PREPARE, LAUNCH, FAILURE, FORGET }

    /** Bound on retained rows per task file; the registry drops the oldest beyond it. */
    public static final int MAX_ROWS_PER_TASK = 8;

    /** The first line of every receipt file: the schema name and version. */
    public static final String SCHEMA_LINE = "drydock-run-receipts 1";

    private static final int NOTE_CAP = 160;
    private static final String EMPTY = "-";

    public TaskRun {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(ownerCatalog, "ownerCatalog");
        Objects.requireNonNull(consumers, "consumers");
        session = singleLine(session == null ? "" : session);
        note = singleLine(note == null ? "" : note);
        consumers = consumers.stream()
                .map(consumer -> Objects.requireNonNull(consumer, "consumer").toAbsolutePath().normalize())
                .toList();
        worktree = worktree.toAbsolutePath().normalize();
        ownerCatalog = ownerCatalog.toAbsolutePath().normalize();
    }

    /** True while this row's phase still holds the reservation (PREPARE or LAUNCH). */
    public boolean live() {
        return phase == Phase.PREPARE || phase == Phase.LAUNCH;
    }

    /** The guarded serialized form: whole lines only; {@code end} echoes the task id. */
    List<String> render() {
        List<String> lines = new ArrayList<>();
        lines.add("run " + phase + " " + at + " " + task.text());
        lines.add("session " + (session.isEmpty() ? EMPTY : session));
        lines.add("worktree " + worktree);
        lines.add("owner " + ownerCatalog);
        consumers.forEach(consumer -> lines.add("consumer " + consumer));
        lines.add("note " + (note.isEmpty() ? EMPTY : note));
        lines.add("end " + task.text());
        return lines;
    }

    /**
     * Parses one guarded record; empty when anything is structurally wrong
     * (guard mismatch, unknown keyword, duplicate or missing field, bad
     * phase, timestamp or task id) -- the caller treats it as a torn tail,
     * never as data.
     */
    static Optional<TaskRun> parse(TaskId stem, List<String> lines) {
        try {
            if (lines.size() < 6) {
                return Optional.empty();
            }
            String[] head = lines.get(0).split(" ", 4);
            if (head.length != 4 || !"run".equals(head[0])) {
                return Optional.empty();
            }
            TaskId task = TaskId.of(head[3]);
            if (!task.equals(stem) || !lines.get(lines.size() - 1).equals("end " + task.text())) {
                return Optional.empty();
            }
            String session = null;
            String note = null;
            Path worktree = null;
            Path owner = null;
            List<Path> consumers = new ArrayList<>();
            for (int i = 1; i < lines.size() - 1; i++) {
                String line = lines.get(i);
                if (line.startsWith("session ")) {
                    if (session != null) {
                        return Optional.empty();
                    }
                    session = line.substring(8);
                } else if (line.startsWith("worktree ")) {
                    if (worktree != null) {
                        return Optional.empty();
                    }
                    worktree = Path.of(line.substring(9));
                } else if (line.startsWith("owner ")) {
                    if (owner != null) {
                        return Optional.empty();
                    }
                    owner = Path.of(line.substring(6));
                } else if (line.startsWith("consumer ")) {
                    consumers.add(Path.of(line.substring(9)));
                } else if (line.startsWith("note ")) {
                    if (note != null) {
                        return Optional.empty();
                    }
                    note = line.substring(5);
                } else {
                    return Optional.empty();
                }
            }
            if (session == null || worktree == null || owner == null || note == null) {
                return Optional.empty();
            }
            return Optional.of(new TaskRun(task, Phase.valueOf(head[1]), Instant.parse(head[2]),
                    EMPTY.equals(session) ? null : session, worktree, owner, consumers,
                    EMPTY.equals(note) ? null : note));
        } catch (RuntimeException e) { // bad id, phase, timestamp or path: torn, never thrown
            return Optional.empty();
        }
    }

    /** One line, bounded: a receipt field can never carry a record-breaking line break. */
    private static String singleLine(String text) {
        String flat = text.replaceAll("[\\r\\n\\t]", " ").strip();
        return flat.length() > NOTE_CAP ? flat.substring(0, NOTE_CAP) : flat;
    }
}
