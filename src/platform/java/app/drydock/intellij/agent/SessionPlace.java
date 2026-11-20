package app.drydock.intellij.agent;

import java.util.Objects;
import java.util.Optional;

/**
 * Where an agent works, in the handoff's words: its repository (the main
 * checkout's name) and branch. A hosted tab, the strip, the attention balloon
 * and the close dialog name an agent by this, never by the window showing it.
 */
public record SessionPlace(String repository, Optional<String> branch) {

    public SessionPlace {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(branch, "branch");
    }

    /** {@code <repo> · <branch>}, or the repository alone. */
    public String label() {
        return branch.map(name -> repository + " · " + name).orElse(repository);
    }

    /** {@code from <repo> ⑂ <branch>}, or {@code from <repo>}. */
    public String provenance() {
        return "from " + branch.map(name -> repository + " ⑂ " + name).orElse(repository);
    }
}
