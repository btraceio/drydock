package app.drydock.tasks;

import app.drydock.domain.RepositoryId;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable result of one store scan (T04): the {@link TaskDocument}s
 * whose ids are unique, the discovered {@link RepositoryId} directories, the
 * indexes by {@link TaskId} and by session reference text, every per-file
 * failure (a bad file never disappears), and the duplicate-id collisions
 * (D4: never a silent winner). Built once per scan by
 * {@link TaskFiles#scan}; every collection is defensively copied here, so
 * nothing the caller still holds can mutate a published snapshot. Documents
 * whose ids collide are excluded from every index and exposed only through
 * {@link #failures()} and {@link #idCollisions()}. Cost: O(files + parsed
 * bytes) per scan, indexes built once.
 */
public record TaskSnapshot(
        List<TaskDocument> documents,
        List<RepositoryId> repositories,
        Map<TaskId, TaskDocument> byId,
        Map<String, List<TaskDocument>> bySessionReference,
        List<FileFailure> failures,
        List<IdCollision> idCollisions
) {

    /** One unusable store entry: its path and the checked reason it is not a task. */
    public record FileFailure(Path path, TaskFormatException failure) {
    }

    /** A task id claimed by more than one file: every claim is listed, none wins. */
    public record IdCollision(TaskId id, List<Path> paths) {
        public IdCollision {
            paths = List.copyOf(paths);
        }
    }

    public TaskSnapshot {
        documents = List.copyOf(documents);
        repositories = List.copyOf(repositories);
        byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        Map<String, List<TaskDocument>> sessions = new LinkedHashMap<>();
        bySessionReference.forEach((text, documentsForText) -> sessions.put(text,
                List.copyOf(documentsForText)));
        bySessionReference = Collections.unmodifiableMap(sessions);
        failures = List.copyOf(failures);
        idCollisions = List.copyOf(idCollisions);
    }

    /** The unique document with this id, or empty when absent or collided. */
    public Optional<TaskDocument> find(TaskId id) {
        return Optional.ofNullable(byId.get(id));
    }
}
