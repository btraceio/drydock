package app.drydock.intellij.tasks;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a task's agent is shown, as its row in the Drydock tool window says
 * it (cross-project tabs, frames F1 and F5). Replaces the MVP's single
 * "this window" fact, now that an agent can be shown in any window, or none.
 */
public sealed interface TaskPlacement {

    /** The row's words for it; empty for an idle row, which keeps its MVP text. */
    String meta();

    /** Shown here, and its worktree is this window's project. */
    record ThisWindow() implements TaskPlacement {
        public String meta() {
            return "this window";
        }
    }

    /** Shown here, hosted from another project or worktree. */
    record Here() implements TaskPlacement {
        public String meta() {
            return "here";
        }
    }

    /** Shown in its own worktree's window. */
    record OwnWindow() implements TaskPlacement {
        public String meta() {
            return "in its own window";
        }
    }

    /** Hosted in some other window, named by that window's repo. */
    record OtherWindow(String repo) implements TaskPlacement {
        public OtherWindow {
            Objects.requireNonNull(repo, "repo");
        }

        public String meta() {
            return "in " + repo + " window";
        }
    }

    /** Running, with no window showing it. */
    record NotShown() implements TaskPlacement {
        public String meta() {
            return "not shown";
        }
    }

    /** Not running; {@code thisWindow} when its worktree is this window's project. */
    record Idle(boolean thisWindow) implements TaskPlacement {
        public String meta() {
            return "";
        }
    }

    /**
     * Pure: {@code hostBase} is the canonical project directory of the window
     * showing the agent (with its repo, {@code hostRepo}), {@code sessionDir}
     * the agent's canonical working directory, {@code thisWindowBase} the
     * asking window's.
     */
    static TaskPlacement of(boolean running, Optional<Path> hostBase, Optional<String> hostRepo, Path sessionDir,
                            Optional<Path> thisWindowBase) {
        if (!running) {
            return new Idle(thisWindowBase.map(sessionDir::equals).orElse(false));
        }
        if (hostBase.isEmpty()) {
            return new NotShown();
        }
        boolean ownWorktree = hostBase.get().equals(sessionDir);
        if (hostBase.equals(thisWindowBase)) {
            return ownWorktree ? new ThisWindow() : new Here();
        }
        return ownWorktree ? new OwnWindow() : new OtherWindow(hostRepo.orElse(hostBase.get().getFileName().toString()));
    }
}
