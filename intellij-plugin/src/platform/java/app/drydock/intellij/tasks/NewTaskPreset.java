package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenIssue;

import java.util.Objects;
import java.util.Optional;

/** Optional values and constraints supplied by a task entry point. */
record NewTaskPreset(String name, Optional<String> instructions, boolean writableOnly) {

    NewTaskPreset {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(instructions, "instructions");
    }

    static NewTaskPreset empty() {
        return new NewTaskPreset("", Optional.empty(), false);
    }

    static NewTaskPreset implement(OpenIssue issue) {
        String reference = "#" + issue.number();
        String name = reference + " " + issue.title();
        StringBuilder prompt = new StringBuilder("Implement GitHub issue ")
                .append(reference).append(": ").append(issue.title());
        issue.url().ifPresent(url -> prompt.append("\n\n").append(url));
        prompt.append("""


                Follow a disciplined issue workflow:
                1. Read the issue and relevant repository context. If the requirements or acceptance criteria are
                   unclear, brainstorm the viable interpretations and resolve the ambiguity before designing.
                2. Produce a design, review it critically against the issue and repository constraints, and revise it.
                3. Produce a separate implementation plan, review it critically for completeness and test coverage,
                   and revise it before changing code.
                4. Use subagents deliberately where independent research, design critique, code review, or verification
                   can run in parallel. Give each subagent a bounded deliverable, keep tightly coupled implementation
                   with one owner, and reconcile every result into the design or plan before acting on it.
                5. Implement in coherent increments, reviewing and testing the work as you go. Finish with an
                   independent review against the issue, design, and plan.
                6. Ensure the resulting pull request uses a GitHub closing keyword that targets this issue (for
                   example, `Closes %s`, with owner/repository included when needed), so merging the PR closes the
                   linked issue. Do not close the issue manually before the PR is merged.
                """.formatted(reference));
        return new NewTaskPreset(name, Optional.of(prompt.toString()), true);
    }

    SessionMode mode(boolean readOnlySelected) {
        return writableOnly || !readOnlySelected ? SessionMode.WRITES : SessionMode.READ_ONLY;
    }
}
