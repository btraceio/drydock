package app.drydock.babysit;

import app.drydock.domain.ManagedSessionId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The drop box of a babysitter that runs headlessly while the IDE is closed:
 * {@code <baseDir>/babysit/drop/<babysitterId>/}. With no MCP server running
 * the agent cannot call {@code ci_rerun} or {@code babysit_escalate}; the
 * delta it is woken with tells it to write a request file here instead, and
 * the detached watcher drains the box after each headless run (the agent has
 * exited by then, so no file is read half-written).
 *
 * <ul>
 *   <li>{@code escalation-<anything>}: the file's text is one escalation
 *       (control characters other than newline and tab become spaces;
 *       capped at {@link #MAX_ESCALATION_CHARS}). A blank file is dropped.</li>
 *   <li>{@code rerun-<runId>} (optionally {@code .txt}): asks for a rerun of
 *       the failed jobs of workflow run {@code runId}; the first line, when
 *       present, names the check.</li>
 * </ul>
 *
 * <p>The babysitter's identity is the directory, never anything the file
 * claims: the box is per babysitter, so a request cannot name another
 * engagement. Other files are left alone. Reads are bounded (at most {@link
 * #MAX_FILES} requests per read, {@link #MAX_READ_BYTES} per file).</p>
 *
 * <p><b>A request is deleted only once its effect is durable</b>: {@link
 * #read} never deletes; the caller deletes a request ({@link #delete}) once
 * what it asked for is on disk elsewhere (the watcher's queue in the
 * babysitting state file, the plugin's {@code state.json}). A crash in
 * between reads the request again, under the same {@link Request#id()}
 * (derived from the box, the name, the modification time and the text), so
 * an escalation is de-duplicated by id instead of lost or doubled. Blocking.</p>
 */
public final class BabysitDropBox {

    /** Requests one drain takes; the rest wait for the next. */
    public static final int MAX_FILES = 20;

    /** The longest escalation text kept (the MCP tool's message plus detail). */
    public static final int MAX_ESCALATION_CHARS = 4000;

    static final int MAX_READ_BYTES = 16 * 1024;

    private static final int MAX_CHECK_NAME = 200;

    private static final Pattern RERUN = Pattern.compile("rerun-(\\d{1,18})(\\.txt)?");

    private BabysitDropBox() {
    }

    /** A rerun the babysitter asked for. */
    public record RerunRequest(long runId, Optional<String> checkName) {
        public RerunRequest {
            Objects.requireNonNull(checkName, "checkName");
            if (runId <= 0) {
                throw new IllegalArgumentException("runId must be positive: " + runId);
            }
        }
    }

    /**
     * One request file as read, not yet deleted: exactly one of {@code
     * escalation} and {@code rerun} is present, or neither for a blank
     * escalation (which asks for nothing; delete it all the same). {@code
     * id} is stable across reads of the same file.
     */
    public record Request(Path file, UUID id, Optional<String> escalation, Optional<RerunRequest> rerun) {
        public Request {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(escalation, "escalation");
            Objects.requireNonNull(rerun, "rerun");
        }
    }

    /** What one read found, in file-name order. */
    public record Drained(List<Request> requests) {
        public Drained {
            requests = List.copyOf(requests);
        }

        /** The escalation texts, in order. */
        public List<String> escalations() {
            return requests.stream().flatMap(request -> request.escalation().stream()).toList();
        }

        /** The rerun requests, in order. */
        public List<RerunRequest> reruns() {
            return requests.stream().flatMap(request -> request.rerun().stream()).toList();
        }
    }

    /** {@code <baseDir>/babysit/drop/}: every babysitter's box lives below it. */
    public static Path root(Path baseDir) {
        return BabysitStateFile.defaultFile(baseDir).resolveSibling("drop");
    }

    /** {@code <baseDir>/babysit/drop/<babysitterId>/}. */
    public static Path directory(Path baseDir, ManagedSessionId babysitterId) {
        return root(baseDir).resolve(babysitterId.toString());
    }

    /** The run id a request file is named for, or empty when {@code fileName} is no rerun request. */
    public static Optional<Long> rerunRunId(String fileName) {
        Matcher matcher = RERUN.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        long runId = Long.parseLong(matcher.group(1));
        return runId > 0 ? Optional.of(runId) : Optional.empty();
    }

    /** Whether {@code fileName} is an escalation request. */
    public static boolean isEscalation(String fileName) {
        return fileName.startsWith("escalation-") && !fileName.endsWith(".tmp");
    }

    /** {@code raw} as an escalation message: control characters but newline and tab flattened, trimmed, capped; empty when blank. */
    public static Optional<String> escalationText(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_ESCALATION_CHARS + 1));
        for (int i = 0; i < raw.length() && out.length() < MAX_ESCALATION_CHARS; i++) {
            char c = raw.charAt(i);
            out.append(c == '\n' || c == '\t' || !Character.isISOControl(c) ? c : ' ');
        }
        String text = out.toString().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /** The check name on the first line of a rerun request: one line, capped; empty when blank. */
    public static Optional<String> checkName(String raw) {
        String first = raw.lines().findFirst().orElse("").strip();
        if (first.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(BabysitDelta.plain(first.length() > MAX_CHECK_NAME ? first.substring(0, MAX_CHECK_NAME)
                : first));
    }

    /** Reads the requests in {@code directory}, deleting none ({@link #delete}); an absent directory has none. Blocking. */
    public static Drained read(Path directory) throws IOException {
        List<Request> found = new ArrayList<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new Drained(found);
        }
        List<Path> requests = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if ((isEscalation(name) || rerunRunId(name).isPresent())
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    requests.add(entry);
                }
            }
        }
        requests.sort(null);
        for (Path request : requests.subList(0, Math.min(requests.size(), MAX_FILES))) {
            String name = request.getFileName().toString();
            String text;
            long modified;
            try {
                modified = Files.getLastModifiedTime(request, LinkOption.NOFOLLOW_LINKS).toMillis();
                text = readBounded(request);
            } catch (NoSuchFileException gone) {
                continue;
            }
            UUID id = UUID.nameUUIDFromBytes((directory.getFileName() + "/" + name + "/" + modified + "/" + text)
                    .getBytes(StandardCharsets.UTF_8));
            Optional<Long> runId = rerunRunId(name);
            found.add(runId.isPresent()
                    ? new Request(request, id, Optional.empty(), Optional.of(new RerunRequest(runId.get(),
                            checkName(text))))
                    : new Request(request, id, escalationText(text), Optional.empty()));
        }
        return new Drained(found);
    }

    /** Deletes {@code request}'s file, once its effect is durable elsewhere. Blocking. */
    public static void delete(Request request) throws IOException {
        Files.deleteIfExists(request.file());
    }

    private static String readBounded(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return new String(in.readNBytes(MAX_READ_BYTES), StandardCharsets.UTF_8);
        }
    }
}
