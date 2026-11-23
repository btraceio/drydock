package app.drydock.tasks;

import app.drydock.domain.RepositoryId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One read-only scan of a task store root (T04, D2/D4). Layout:
 * {@code <root>/<repository-id>/<task-id>.md}, where {@code <repository-id>}
 * is the canonical {@link RepositoryId} text (UUID), plus the reserved
 * metadata directories {@code plans/} and {@code runs/} (P4) compared by
 * exact name: they are never repositories and their contents are never
 * scanned as tasks.
 *
 * <p>Every unusable entry is retained as a per-file failure with its path
 * (checked {@link TaskFormatException}); a bad file never disappears and
 * never stops the scan. No symbolic link is ever followed: attributes are
 * read with {@link LinkOption#NOFOLLOW_LINKS} and
 * {@link BasicFileAttributes#isSymbolicLink()} is checked first (verified on
 * OpenJDK 25.0.3), so a symlinked repository or task file is a per-entry
 * error, and an entry that does not stay inside the root is an error, never
 * a read. A missing {@code created} falls back to the file's real creation
 * time from {@link BasicFileAttributes#creationTime()}; the JDK contract
 * makes an unsupported creation time an implementation default (typically
 * the epoch), and an epoch-or-earlier value is treated as unavailable,
 * leaving {@code created} empty rather than inventing a time (P1).</p>
 *
 * <p>Ids are unique across all projects (D4): two task files claiming the
 * same valid id stem, in the same or different repositories, both become
 * per-file errors and one {@link TaskSnapshot.IdCollision}; neither silently
 * wins, neither is indexed, and a file that cannot be parsed never claims an
 * id. A repository directory with zero task files (an orphan) is still
 * listed as a repository identity but claims no ids. Cost: O(entries + total
 * parsed bytes) per scan; every index is built once, in {@link TaskSnapshot}.</p>
 */
public final class TaskFiles {

    /** Reserved metadata directory names (P4), compared exactly; never repositories, never scanned. */
    public static final Set<String> RESERVED_DIRECTORY_NAMES = Set.of("plans", "runs");

    /** Upper byte bound before reading a task file: 4 UTF-8 bytes per allowed code point. */
    private static final long MAX_FILE_BYTES = TaskFileReader.MAX_FILE_CODE_POINTS * 4L;

    private TaskFiles() {
    }

    /** One candidate task file: discovered and read, not yet collision-checked. */
    private record Candidate(TaskId id, RepositoryId repository, Path path, String source, Instant createdFallback) {
    }

    /** Scans one store root; throws only when the root itself cannot be listed. */
    public static TaskSnapshot scan(Path root) throws IOException {
        Path store = root.toAbsolutePath().normalize();
        List<RepositoryId> repositories = new ArrayList<>();
        List<TaskSnapshot.FileFailure> failures = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Path entry : list(store)) {
            if (RESERVED_DIRECTORY_NAMES.contains(entry.getFileName().toString())) {
                continue;
            }
            BasicFileAttributes attributes = attributes(entry, failures);
            if (attributes == null) {
                continue;
            }
            RepositoryId repository = repositoryId(entry, attributes, failures);
            if (repository != null) {
                repositories.add(repository);
                scanRepository(store, entry, repository, candidates, failures);
            }
        }
        return snapshot(repositories, candidates, failures);
    }

    /** Lists a directory; a mid-iteration failure surfaces as its checked IOException. */
    private static List<Path> list(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            return entries;
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
    }

    /** Entry attributes without following any link, or null after recording the failure. */
    private static BasicFileAttributes attributes(Path entry, List<TaskSnapshot.FileFailure> failures) {
        try {
            return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            failures.add(new TaskSnapshot.FileFailure(entry, new TaskFormatException("cannot read entry attributes", e)));
            return null;
        }
    }

    /** A root entry must be a real directory whose name is the canonical repository id text. */
    private static RepositoryId repositoryId(Path entry, BasicFileAttributes attributes,
            List<TaskSnapshot.FileFailure> failures) {
        String name = entry.getFileName().toString();
        if (attributes.isSymbolicLink()) {
            failures.add(failure(entry, "repository entry is a symbolic link; not followed"));
        } else if (!attributes.isDirectory()) {
            failures.add(failure(entry, "store root entries must be <repository-id> directories, got a file"));
        } else {
            try {
                RepositoryId id = RepositoryId.of(name);
                if (id.toString().equals(name)) {
                    return id;
                }
            } catch (IllegalArgumentException e) {
                // reported by the shared refusal below
            }
            failures.add(failure(entry, "repository directory name is not a canonical repository id: '" + name + "'"));
        }
        return null;
    }

    private static void scanRepository(Path store, Path directory, RepositoryId repository,
            List<Candidate> candidates, List<TaskSnapshot.FileFailure> failures) {
        List<Path> entries;
        try {
            entries = list(directory);
        } catch (IOException e) {
            failures.add(new TaskSnapshot.FileFailure(directory,
                    new TaskFormatException("cannot read repository directory", e)));
            return;
        }
        for (Path entry : entries) {
            scanTaskEntry(store, entry, repository, candidates, failures);
        }
    }

    private static void scanTaskEntry(Path store, Path entry, RepositoryId repository,
            List<Candidate> candidates, List<TaskSnapshot.FileFailure> failures) {
        String name = entry.getFileName().toString();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || !entry.normalize().startsWith(store)) {
            failures.add(failure(entry, "entry does not stay inside the store root"));
            return;
        }
        BasicFileAttributes attributes = attributes(entry, failures);
        if (attributes == null) {
            return;
        }
        if (attributes.isSymbolicLink()) {
            failures.add(failure(entry, "task entry is a symbolic link; not followed"));
            return;
        }
        if (!attributes.isRegularFile()) {
            failures.add(failure(entry, "task files must be regular <task-id>.md files"));
            return;
        }
        TaskId id;
        try {
            id = TaskId.of(name.endsWith(".md") ? name.substring(0, name.length() - ".md".length()) : "");
        } catch (IllegalArgumentException e) {
            failures.add(failure(entry, "task file name is not a valid task id: '" + name + "'"));
            return;
        }
        if (attributes.size() > MAX_FILE_BYTES) {
            failures.add(failure(entry, "task file exceeds " + MAX_FILE_BYTES + " bytes"));
            return;
        }
        String source;
        try {
            source = Files.readString(entry, StandardCharsets.UTF_8);
        } catch (IOException e) {
            failures.add(new TaskSnapshot.FileFailure(entry, new TaskFormatException("cannot read task file", e)));
            return;
        }
        candidates.add(new Candidate(id, repository, entry, source, creationTimeOf(attributes)));
    }

    /**
     * The file's real creation time, or null when the file system provides
     * none: the JDK's unsupported default (epoch or earlier) is treated as
     * unavailable, so no invented time reaches {@code created}.
     */
    private static Instant creationTimeOf(BasicFileAttributes attributes) {
        Instant created = attributes.creationTime().toInstant();
        return created.isBefore(Instant.EPOCH) || created.equals(Instant.EPOCH) ? null : created;
    }

    /** Resolves id collisions (D4), parses the unique survivors, builds the snapshot. */
    private static TaskSnapshot snapshot(List<RepositoryId> repositories, List<Candidate> candidates,
            List<TaskSnapshot.FileFailure> failures) {
        TreeMap<TaskId, List<Candidate>> claims = new TreeMap<>(Comparator.comparing(TaskId::text));
        candidates.forEach(candidate -> claims.computeIfAbsent(candidate.id(), id -> new ArrayList<>())
                .add(candidate));
        List<TaskDocument> documents = new ArrayList<>();
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        Map<String, List<TaskDocument>> bySession = new LinkedHashMap<>();
        List<TaskSnapshot.IdCollision> collisions = new ArrayList<>();
        for (Map.Entry<TaskId, List<Candidate>> claim : claims.entrySet()) {
            List<Candidate> claimsForId = claim.getValue();
            if (claimsForId.size() == 1) {
                Candidate candidate = claimsForId.get(0);
                try {
                    TaskDocument document = TaskFileReader.parse(candidate.id(), candidate.repository(),
                            candidate.source(), candidate.createdFallback()).document();
                    documents.add(document);
                    byId.put(document.id(), document);
                    for (String session : document.sessions()) {
                        bySession.computeIfAbsent(session, text -> new ArrayList<>()).add(document);
                    }
                } catch (TaskFormatException e) {
                    failures.add(new TaskSnapshot.FileFailure(candidate.path(), e));
                }
                continue;
            }
            List<Path> paths = claimsForId.stream().map(Candidate::path)
                    .sorted(Comparator.comparing(Path::toString)).toList();
            collisions.add(new TaskSnapshot.IdCollision(claim.getKey(), paths));
            String all = paths.stream().map(Path::toString).collect(Collectors.joining(", "));
            for (Candidate candidate : claimsForId) {
                failures.add(failure(candidate.path(),
                        "duplicate task id '" + claim.getKey() + "' (D4: unique across all projects): " + all));
            }
        }
        failures.sort(Comparator.comparing(failure -> failure.path().toString()));
        repositories.sort(Comparator.comparing(RepositoryId::toString));
        return new TaskSnapshot(documents, repositories, byId, bySession, failures, collisions);
    }

    private static TaskSnapshot.FileFailure failure(Path path, String reason) {
        return new TaskSnapshot.FileFailure(path, new TaskFormatException(reason));
    }
}
