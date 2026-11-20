package app.drydock.agent.api;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read access to this launch's isolated, plugin-staged workflow resources for
 * a LOCAL host or LOCAL eval launch. It travels beside, and is never merged
 * into, {@link LaunchResources}: task directories and task guidance keep
 * their own refusal rules, so a provider must render this through its own
 * typed path rather than through the {@code launchResources} one.
 *
 * <p>{@code readRoots} are host directories the agent may read (absolute,
 * duplicates collapse to the first occurrence). {@code evalNamespace} is the
 * opaque reference to the per-session namespace a local eval container mounts
 * read-only; it is not a host path and carries no authority by itself. At
 * least one of the two must be present — an access that grants nothing is
 * expressed by the context's component being empty.</p>
 *
 * <p>Nothing here stages, verifies or reads the location, and it grants no
 * tool, mode or permission authority; a provider that cannot serve it must
 * refuse the launch rather than silently drop it.</p>
 */
public record LocalWorkflowAccess(List<Path> readRoots, Optional<String> evalNamespace) {

    public LocalWorkflowAccess {
        Objects.requireNonNull(readRoots, "readRoots");
        Objects.requireNonNull(evalNamespace, "evalNamespace");
        for (Path root : readRoots) {
            if (!Objects.requireNonNull(root, "readRoots element").isAbsolute()) {
                throw new IllegalArgumentException("read root must be absolute: " + root);
            }
        }
        readRoots = readRoots.isEmpty() ? List.of() : List.copyOf(new LinkedHashSet<>(readRoots));
        evalNamespace = evalNamespace.map(String::strip);
        if (evalNamespace.isPresent() && evalNamespace.get().isEmpty()) {
            throw new IllegalArgumentException("evalNamespace must not be blank");
        }
        if (readRoots.isEmpty() && evalNamespace.isEmpty()) {
            throw new IllegalArgumentException("access needs a read root or an eval namespace");
        }
    }
}
