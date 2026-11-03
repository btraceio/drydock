package app.drydock.agent.api;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional resources a launch needs beyond the session's own worktree:
 * extra directories plus the installed task-guidance location. These are
 * per invocation (see {@link CreateContext#launchResources()} and {@link
 * ResumeContext#launchResources()}), never global harness configuration.
 *
 * <p>{@code extraDirectories} are the worktrees of prerequisite tasks in
 * other projects, handed to a dependent session as additional working
 * context: the path is a fact about what this launch may read, not a merge
 * guarantee or a read-only permission. Providers must render each
 * directory through their own surface ({@code --add-dir} for Claude and
 * Codex, a path in the prompt for pi) — this is a required consumer
 * contract for future provider work, not behavior implemented by this
 * record. Duplicates collapse to the first occurrence, so the rendered
 * command is stable.</p>
 *
 * <p>{@code taskGuidance} is the provider-neutral location of the
 * installed harness guidance: a plugin-owned directory the consuming
 * provider reads its own layout from ({@code --plugin-dir} for Claude,
 * {@code --skill} for pi) — again a required consumer contract, not
 * implemented here. Empty means no guidance is installed for this launch.
 * The location only travels through here — nothing in this record
 * installs, verifies or reads it; providers must refuse the launch when
 * they cannot serve a requested resource, rather than silently dropping
 * it.</p>
 */
public record LaunchResources(List<Path> extraDirectories, Optional<Path> taskGuidance) {

    public LaunchResources {
        Objects.requireNonNull(extraDirectories, "extraDirectories");
        Objects.requireNonNull(taskGuidance, "taskGuidance");
        if (extraDirectories.isEmpty()) {
            extraDirectories = List.of();
        } else {
            extraDirectories = List.copyOf(new LinkedHashSet<>(extraDirectories));
        }
    }
}
