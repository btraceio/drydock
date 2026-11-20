package app.drydock.tasks;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The receipt file codec (T27a): the reserved {@code runs/} family under
 * the store root, one versioned file per task id
 * ({@code runs/<task-id>.run}), each the schema header plus that task's
 * guarded {@link TaskRun} rows, written atomically (temp + fsync + move,
 * exactly as the task store writes). A torn tail -- a record whose
 * {@code end} guard never landed, or any structural junk -- is detected
 * per record on read and, on release, quarantined to a kept-aside
 * {@code <task-id>.run.torn} sidecar, never deleted; only the affected
 * task's file is touched.
 */
final class TaskRunFiles {

    /** One task file's parse: intact rows plus, when the tail write was torn, its unreadable bytes. */
    record Loaded(List<TaskRun> rows, String tornTail) {
    }

    private final Path runsDir;

    TaskRunFiles(Path storeRoot) {
        this.runsDir = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize()
                .resolve("runs");
    }

    /** The reserved registry directory, listed by the registry's lookups. */
    Path runsDir() {
        return runsDir;
    }

    Path fileOf(TaskId task) {
        return runsDir.resolve(task.text() + ".run");
    }

    static TaskRun latest(Loaded file) {
        List<TaskRun> rows = file.rows();
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    /** Fresh read of one task's file under the lock; a missing file is no rows, no torn tail. */
    Loaded load(TaskId task) throws IOException {
        Path file = fileOf(task);
        if (!Files.isRegularFile(file)) {
            return new Loaded(List.of(), null);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int headerEnd = text.indexOf('\n');
        if (headerEnd < 0 || !text.substring(0, headerEnd).equals(TaskRun.SCHEMA_LINE)) {
            return new Loaded(List.of(), text); // unknown schema: the whole file is the torn tail
        }
        return parseRows(task, text.substring(headerEnd + 1));
    }

    /** Moves a torn tail aside (kept, never deleted) and rewrites the file with the intact rows. */
    Loaded quarantineTorn(TaskId task, Loaded file) throws IOException {
        if (file.tornTail() == null) {
            return file;
        }
        Path target = fileOf(task);
        Path sidecar = target.resolveSibling(target.getFileName() + ".torn");
        Files.createDirectories(runsDir);
        Files.writeString(sidecar, "quarantined " + Instant.now() + "\n" + file.tornTail() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writeAtomically(target, render(file.rows()));
        return new Loaded(file.rows(), null);
    }

    /** Appends one row under the per-task retention bound, atomically. */
    TaskRun append(TaskId task, Loaded file, TaskRun row) throws IOException {
        List<TaskRun> rows = new ArrayList<>(file.rows());
        rows.add(row);
        while (rows.size() > TaskRun.MAX_ROWS_PER_TASK) {
            rows.remove(0); // oldest dropped: bounded file growth
        }
        writeAtomically(fileOf(task), render(rows));
        return row;
    }

    private static String render(List<TaskRun> rows) {
        StringBuilder out = new StringBuilder(TaskRun.SCHEMA_LINE);
        for (TaskRun row : rows) {
            for (String line : row.render()) {
                out.append('\n').append(line);
            }
        }
        return out.append('\n').toString();
    }

    /** Splits guarded records; the first bad or unfinished one starts the torn tail. */
    private static Loaded parseRows(TaskId task, String body) {
        List<TaskRun> rows = new ArrayList<>();
        String[] lines = body.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (start < 0) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("run ")) {
                    start = i;
                } else {
                    return torn(rows, lines, i);
                }
            } else if (line.startsWith("end ")) {
                Optional<TaskRun> row = TaskRun.parse(task, Arrays.asList(lines).subList(start, i + 1));
                if (row.isEmpty()) {
                    return torn(rows, lines, start);
                }
                rows.add(row.get());
                start = -1;
            }
        }
        return start < 0 ? new Loaded(List.copyOf(rows), null) : torn(rows, lines, start);
    }

    private static Loaded torn(List<TaskRun> rows, String[] lines, int from) {
        return new Loaded(List.copyOf(rows),
                String.join("\n", Arrays.asList(lines).subList(from, lines.length)));
    }

    /** A temp file in the target's directory, fsynced, then atomically moved over it (as the store writes). */
    private static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "runs", ".tmp");
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
