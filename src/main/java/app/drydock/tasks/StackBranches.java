package app.drydock.tasks;

import java.util.Objects;

/**
 * The stacked-branch naming convention (S1 of docs/StackedPrExecution.md):
 * every task of a stack lands on the branch {@code dd/<task-id>}, derived
 * from the task's stable id -- never from a user-authored title. The prefix
 * is one component, so the branch name is {@code dd/<slug-suffix>}; whether
 * a mint is legal in a given repository (refname rules, remote shadowing)
 * is {@code BranchNameRules.check}'s business at mint time, where the
 * repository's remotes are known. Task ids are unique across all projects
 * (D4), so the names cannot collide within a repository's stack.
 */
public final class StackBranches {

    /** The one-component prefix of every stacked task branch. */
    public static final String PREFIX = "dd/";

    private StackBranches() {
    }

    /** The stacked branch of {@code task}: {@code dd/<task-id>}. */
    public static String name(TaskId task) {
        Objects.requireNonNull(task, "task");
        return PREFIX + task.text();
    }

    /** The task id a stacked branch name carries, or null when the name is
      * not a {@code dd/} branch or its remainder is not a valid id. */
    public static TaskId taskOf(String branch) {
        if (branch == null || !branch.startsWith(PREFIX)) {
            return null;
        }
        try {
            return TaskId.of(branch.substring(PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
