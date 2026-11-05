package app.drydock.tasks;

import app.drydock.domain.RepositoryId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One stored task document: the parsed known front-matter fields plus the raw
 * envelope, both immutable. The raw text is the lossless source for the
 * reader and patch layers (P1): {@code rawFrontMatter} is the text between
 * the {@code ---} fences (delimiters excluded), {@code rawBody} is everything
 * after the closing fence, both carried byte for byte; unknown keys, comments
 * and line endings survive any rewrite. This value does no YAML parsing and
 * no disk I/O — the reader (and its creation-time fallback) populates it.
 *
 * <p>Defaults per P1: a missing status is {@link TaskStatus#OPEN} and a
 * missing done_by is {@link DoneBy#HUMAN} (D16). {@code after} entries are
 * stored reference texts (bare ids or {@code <project>:<id>} per P2), not
 * {@link TaskId}s. The repository is the shared registry identity the task
 * is stored under (D2), carried explicitly so a document never travels
 * without it.</p>
 *
 * <p>{@code mirrors} are the web addresses of the GitHub issues and Jira
 * tickets this task was mirrored into, in the order they were created; a
 * missing key is no mirror. The remote side names the task back (see
 * {@link TaskMirror}), so the link runs both ways.</p>
 */
public record TaskDocument(
        TaskId id,
        RepositoryId repository,
        String title,
        TaskStatus status,
        DoneBy doneBy,
        Optional<Instant> created,
        List<String> after,
        List<String> sessions,
        List<String> mirrors,
        String rawFrontMatter,
        String rawBody
) {

    public TaskDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(title, "title");
        if (title.isBlank()) {
            throw new IllegalArgumentException("Task title is required (P1)");
        }
        Objects.requireNonNull(rawFrontMatter, "rawFrontMatter");
        Objects.requireNonNull(rawBody, "rawBody");
        status = status != null ? status : TaskStatus.OPEN;
        doneBy = doneBy != null ? doneBy : DoneBy.HUMAN;
        created = created != null ? created : Optional.empty();
        after = after != null ? List.copyOf(after) : List.of();
        sessions = sessions != null ? List.copyOf(sessions) : List.of();
        mirrors = mirrors != null ? List.copyOf(mirrors) : List.of();
    }

    /** The shape before tasks could be mirrored: no mirror. */
    public TaskDocument(TaskId id, RepositoryId repository, String title, TaskStatus status, DoneBy doneBy,
                        Optional<Instant> created, List<String> after, List<String> sessions,
                        String rawFrontMatter, String rawBody) {
        this(id, repository, title, status, doneBy, created, after, sessions, List.of(), rawFrontMatter, rawBody);
    }

    /**
     * Copy with a new title. The id, and therefore the stored file name,
     * stays the same (D4: the file is not renamed when the title changes);
     * every other field, including the raw envelope, is carried unchanged.
     */
    public TaskDocument withTitle(String newTitle) {
        return new TaskDocument(id, repository, newTitle, status, doneBy, created, after, sessions, mirrors,
                rawFrontMatter, rawBody);
    }
}
