package app.drydock.projects;

import app.drydock.domain.RepositoryId;
import app.drydock.testing.TestPaths;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A second process for {@link SharedProjectsFileTest}: appends
 * {@code count} projects to the shared list at {@code file}, one locked
 * update each, roots prefixed with {@code prefix}. Only the OS file lock
 * stands between it and the test's own writes.
 */
public final class SharedProjectsWriterProcess {

    private SharedProjectsWriterProcess() {
    }

    public static void main(String[] args) throws Exception {
        SharedProjectsFile shared = new SharedProjectsFile(Path.of(args[0]));
        String prefix = args[1];
        int count = Integer.parseInt(args[2]);
        for (int i = 0; i < count; i++) {
            Path root = TestPaths.abs("/w/" + prefix + "-" + i);
            String name = prefix + i;
            shared.update(list -> {
                List<SharedProject> next = new ArrayList<>(list);
                next.add(SharedProject.local(RepositoryId.newId(), root, name, Instant.EPOCH));
                return next;
            });
        }
    }
}
