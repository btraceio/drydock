package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;

import java.util.Objects;
import java.util.Optional;

/**
 * Optional values and constraints supplied by a task entry point, and which
 * window the task opens in: this one (a tab, hosted when its worktree is
 * another project's) unless the entry point asks for its own.
 *
 * <p>{@code tierRoles} carries the preset's subagent role→tier mapping as
 * the assignments clause of the prompt's model rules, with the placeholders
 * {@code {STRONG}}, {@code {MID}} and {@code {LOW}} where the tier's model
 * value goes; the dialog substitutes them from the picked tiers when the
 * harness dispatches subagents (see {@code NewTaskDialog}). Presets without
 * a mapping get no tier rules and no tier pickers.</p>
 */
record NewTaskPreset(String name, Optional<String> instructions, Optional<SessionMode> fixedMode,
                     Optional<WorkIn> fixedWorkIn, boolean instructionsRequired, OpenIn openIn,
                     Optional<String> tierRoles) {

    NewTaskPreset {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(fixedMode, "fixedMode");
        Objects.requireNonNull(fixedWorkIn, "fixedWorkIn");
        Objects.requireNonNull(openIn, "openIn");
        Objects.requireNonNull(tierRoles, "tierRoles");
    }

    NewTaskPreset(String name, Optional<String> instructions, Optional<SessionMode> fixedMode,
                  Optional<WorkIn> fixedWorkIn, boolean instructionsRequired, OpenIn openIn) {
        this(name, instructions, fixedMode, fixedWorkIn, instructionsRequired, openIn, Optional.empty());
    }

    static NewTaskPreset empty() {
        return new NewTaskPreset("", Optional.empty(), Optional.empty(), Optional.empty(), false, OpenIn.THIS_WINDOW);
    }

    static NewTaskPreset mainCheckout() {
        return new NewTaskPreset("", Optional.empty(), Optional.empty(), Optional.of(WorkIn.MAIN_CHECKOUT), false,
                OpenIn.THIS_WINDOW);
    }

    /**
     * {@code Start ▸}'s menu (cross-project tabs, frame F1): the window it
     * opens in, and read-only fixed when the item says so; a writing item
     * leaves the mode to the user.
     */
    static NewTaskPreset start(OpenIn openIn, SessionMode mode) {
        return new NewTaskPreset("", Optional.empty(),
                mode == SessionMode.READ_ONLY ? Optional.of(SessionMode.READ_ONLY) : Optional.empty(),
                Optional.empty(), false, openIn);
    }

    /** Steps 1-5 of the shared issue workflow, common to the GitHub and Jira implement presets. */
    private static final String WORKFLOW_STEPS = """
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
            """;

    /** The GitHub-only step 6: a closing keyword that merges the linked issue; {%s} is the issue reference. */
    private static final String GITHUB_CLOSING_STEP = """
            6. Ensure the resulting pull request uses a GitHub closing keyword that targets this issue (for
               example, `Closes %s`, with owner/repository included when needed), so merging the PR closes the
               linked issue. Do not close the issue manually before the PR is merged.
            """;

    private static final String QUALITY_GATES = """
            Quality gates (all must pass before completion):
            - Requirements: map every acceptance criterion to implemented behavior and verification. Resolve all
              material ambiguity; explicitly record any agreed non-goals.
            - Design and plan: complete both review loops with no unresolved blocking findings before implementation.
            - Implementation review: independently review the final diff for correctness, regressions, security,
              maintainability, and repository conventions; resolve every blocking or substantive finding.
            - Verification: add or update automated tests for changed behavior, run the relevant focused and
              regression suites plus the repository's required build/static checks, and record the exact commands
              and results. Do not silently ignore failures, flakes, skipped checks, or untested behavior.
            - Scope and hygiene: leave no unrelated changes, accidental generated files, secrets, debug code, or
              unexplained TODOs. Update documentation, compatibility handling, and migrations when the change
              requires them.
            """;

    /** The first three definition-of-done bullets, common to the GitHub and Jira implement presets. */
    private static final String DONE_COMMON = """
            - The issue's requirements and acceptance criteria are satisfied and traceable to the final diff.
            - All quality gates above pass, with no known material defect or unresolved review finding.
            - The working tree and commit history contain only intentional, reviewable changes.
            """;

    private static final String GITHUB_DONE_PR = """
            - The pull request explains the behavior, design decisions, risks, and verification, and contains the
              correct closing keyword for this issue; the work is not done merely because code was written.
            """;

    /** The Jira variant: no closing keyword, because the Jira integration is read-only. */
    private static final String JIRA_DONE_PR = """
            - The pull request explains the behavior, design decisions, risks, and verification;
              the work is not done merely because code was written.
            """;

    private static final String IMPLEMENT_TIER_ROLES = "design-critique and code-review subagents use {STRONG}; "
            + "research and verification subagents use {MID}; any other subagent you dispatch uses {LOW}";

    /** The triage steps, common to the GitHub and Jira triage presets; begins with the blank separator lines. */
    private static final String TRIAGE_STEPS = """


            Triage the report against the repository as it exists now:
            1. Read the full issue and inspect the relevant current production control flow, data flow,
               configuration, and tests. Treat every behavioral statement in the report as an unverified claim.
            2. Decide whether the report still applies to the current source shape and runtime behavior. Classify
               it as confirmed, partially applicable, stale, or uncertain, and cite the concrete source paths and
               evidence behind that conclusion. Distinguish observed facts from inferences and open questions.
            3. Propose the smallest meaningful reproducer, including its prerequisites, exact steps, expected and
               actual results, and what it would prove or disprove. Before modifying files or running build or
               reproducer commands, ask the user for approval. If approved, build and run the reproducer, record
               the exact commands and results, and clean up generated artifacts unless the user asks to keep them.
            4. Summarize the likely affected code, constraints, dependencies, risks, and sensible next steps.
               Do not implement the production fix or create commits unless the user separately approves that work.
            5. End with an Impact-Likelihood-Workaround assessment: rate and justify each dimension from the
               available evidence, state confidence and remaining unknowns, then recommend an explicit priority.
            """;

    private static final String TRIAGE_TIER_ROLES = "investigation and the confirmed/stale verdict use {STRONG}; "
            + "reproducer work uses {MID}; summary and impact-assessment work uses {LOW}";

    static NewTaskPreset implement(OpenIssue issue) {
        String reference = "#" + issue.number();
        String name = reference + " " + issue.title();
        StringBuilder prompt = new StringBuilder("Implement GitHub issue ")
                .append(reference).append(": ").append(issue.title());
        issue.url().ifPresent(url -> prompt.append("\n\n").append(url));
        prompt.append("\n\n").append(WORKFLOW_STEPS)
                .append(GITHUB_CLOSING_STEP.formatted(reference))
                .append("\n").append(QUALITY_GATES)
                .append("\n").append("Definition of done:\n")
                .append(DONE_COMMON)
                .append(GITHUB_DONE_PR);
        return new NewTaskPreset(name, Optional.of(prompt.toString()), Optional.of(SessionMode.WRITES),
                Optional.empty(), true, OpenIn.THIS_WINDOW, Optional.of(IMPLEMENT_TIER_ROLES));
    }

    static NewTaskPreset triage(OpenIssue issue) {
        String reference = "#" + issue.number();
        String name = "Triage " + reference + " " + issue.title();
        StringBuilder prompt = new StringBuilder("Triage GitHub issue ")
                .append(reference).append(": ").append(issue.title());
        issue.url().ifPresent(url -> prompt.append("\n\n").append(url));
        prompt.append(TRIAGE_STEPS);
        return new NewTaskPreset(name, Optional.of(prompt.toString()), Optional.empty(),
                Optional.of(WorkIn.MAIN_CHECKOUT), true, OpenIn.THIS_WINDOW, Optional.of(TRIAGE_TIER_ROLES));
    }

    static NewTaskPreset implement(JiraIssue issue) {
        String name = issue.key() + " " + issue.summary();
        StringBuilder prompt = new StringBuilder("Implement Jira issue ")
                .append(issue.key()).append(": ").append(issue.summary())
                .append("\n\n").append(issue.url())
                .append("\n\n").append(WORKFLOW_STEPS)
                .append("\n").append(QUALITY_GATES)
                .append("\n").append("Definition of done:\n")
                .append(DONE_COMMON)
                .append(JIRA_DONE_PR);
        return new NewTaskPreset(name, Optional.of(prompt.toString()), Optional.of(SessionMode.WRITES),
                Optional.empty(), true, OpenIn.THIS_WINDOW, Optional.of(IMPLEMENT_TIER_ROLES));
    }

    static NewTaskPreset triage(JiraIssue issue) {
        String name = "Triage " + issue.key() + " " + issue.summary();
        String prompt = "Triage Jira issue " + issue.key() + ": " + issue.summary()
                + "\n\n" + issue.url() + TRIAGE_STEPS;
        return new NewTaskPreset(name, Optional.of(prompt), Optional.empty(),
                Optional.of(WorkIn.MAIN_CHECKOUT), true, OpenIn.THIS_WINDOW, Optional.of(TRIAGE_TIER_ROLES));
    }

    SessionMode mode(boolean readOnlySelected) {
        return fixedMode.orElse(readOnlySelected ? SessionMode.READ_ONLY : SessionMode.WRITES);
    }
}
