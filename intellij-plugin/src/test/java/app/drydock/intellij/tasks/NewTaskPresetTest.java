package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewTaskPresetTest {

    @Test
    void githubIssuePrefillsAWorktreeTaskAndStaysWritable() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting",
                Optional.of("https://github.com/acme/profiler/issues/42"), List.of(), List.of(),
                Optional.of(Instant.parse("2026-10-20T10:15:30Z")));

        NewTaskPreset preset = NewTaskPreset.implement(issue);

        assertEquals("#42 Fix allocation accounting", preset.name());
        String prompt = preset.instructions().orElseThrow();
        assertTrue(prompt.startsWith("Implement GitHub issue #42: Fix allocation accounting\n\n"
                + "https://github.com/acme/profiler/issues/42"));
        assertTrue(prompt.contains("brainstorm the viable interpretations"));
        assertTrue(prompt.contains("Produce a design, review it critically"));
        assertTrue(prompt.contains("Produce a separate implementation plan"));
        assertTrue(prompt.contains("Use subagents deliberately"));
        assertTrue(prompt.contains("Give each subagent a bounded deliverable"));
        assertTrue(prompt.contains("Implement in coherent increments, reviewing and testing"));
        assertTrue(prompt.contains("`Closes #42`"));
        assertTrue(prompt.contains("so merging the PR closes the\n   linked issue"));
        assertTrue(prompt.contains("Quality gates (all must pass before completion):"));
        assertTrue(prompt.contains("map every acceptance criterion to implemented behavior and verification"));
        assertTrue(prompt.contains("Do not silently ignore failures, flakes, skipped checks, or untested behavior"));
        assertTrue(prompt.contains("Definition of done:"));
        assertTrue(prompt.contains("the work is not done merely because code was written"));
        assertEquals(SessionMode.WRITES, preset.mode(false));
        assertEquals(SessionMode.WRITES, preset.mode(true), "the hidden read-only state cannot override the preset");
        assertEquals(Optional.of(SessionMode.WRITES), preset.fixedMode());
        assertTrue(preset.instructionsRequired(),
                "Implement must never degrade into an idle agent session");
        assertFalse(NewTaskPreset.empty().instructionsRequired());
    }

    @Test
    void githubIssueTriageValidatesAndPrioritizesTheCurrentReport() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting",
                Optional.of("https://github.com/acme/profiler/issues/42"), List.of(), List.of(), Optional.empty());

        NewTaskPreset preset = NewTaskPreset.triage(issue);

        assertEquals("Triage #42 Fix allocation accounting", preset.name());
        String prompt = preset.instructions().orElseThrow();
        assertTrue(prompt.startsWith("Triage GitHub issue #42: Fix allocation accounting\n\n"
                + "https://github.com/acme/profiler/issues/42"));
        assertTrue(prompt.contains("Treat every behavioral statement in the report as an unverified claim"));
        assertTrue(prompt.contains("still applies to the current source shape"));
        assertTrue(prompt.contains("Propose the smallest meaningful reproducer"));
        assertTrue(prompt.contains("ask the user for approval"));
        assertTrue(prompt.contains("build and run the reproducer"));
        assertTrue(prompt.contains("Impact-Likelihood-Workaround assessment"));
        assertTrue(prompt.contains("recommend an explicit priority"));
        assertEquals(SessionMode.WRITES, preset.mode(false));
        assertEquals(SessionMode.READ_ONLY, preset.mode(true));
        assertEquals(Optional.empty(), preset.fixedMode());
        assertEquals(Optional.of(NewTaskPlan.WorkIn.MAIN_CHECKOUT), preset.fixedWorkIn());
        assertTrue(preset.instructionsRequired());
    }

    @Test
    void startMenuPresetsTheWindowAndFixesOnlyReadOnly() {
        NewTaskPreset readOnlyHere = NewTaskPreset.start(NewTaskPlan.OpenIn.THIS_WINDOW, SessionMode.READ_ONLY);
        NewTaskPreset ownWindow = NewTaskPreset.start(NewTaskPlan.OpenIn.OWN_WINDOW, SessionMode.WRITES);

        assertEquals(NewTaskPlan.OpenIn.THIS_WINDOW, readOnlyHere.openIn());
        assertEquals(Optional.of(SessionMode.READ_ONLY), readOnlyHere.fixedMode());
        assertEquals(NewTaskPlan.OpenIn.OWN_WINDOW, ownWindow.openIn());
        assertEquals(Optional.empty(), ownWindow.fixedMode());
        assertEquals("", ownWindow.name());
    }

    @Test
    void projectMainCheckoutPresetCannotCreateAWorktree() {
        NewTaskPreset preset = NewTaskPreset.mainCheckout();

        assertEquals(Optional.of(NewTaskPlan.WorkIn.MAIN_CHECKOUT), preset.fixedWorkIn());
        assertEquals(NewTaskPlan.OpenIn.THIS_WINDOW, preset.openIn());
        assertEquals(Optional.empty(), preset.fixedMode());
    }

    @Test
    void githubPromptsAreByteIdentical() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting",
                Optional.of("https://github.com/acme/profiler/issues/42"), List.of(), List.of(),
                Optional.of(Instant.parse("2026-10-20T10:15:30Z")));

        // Byte-identical characterization of today's GitHub prompts. The
        // Jira variants below must reuse these fragments, not reword them.
        String expectedImplement = "Implement GitHub issue #42: Fix allocation accounting\n\n"
                + "https://github.com/acme/profiler/issues/42"
                + """


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

                Definition of done:
                - The issue's requirements and acceptance criteria are satisfied and traceable to the final diff.
                - All quality gates above pass, with no known material defect or unresolved review finding.
                - The working tree and commit history contain only intentional, reviewable changes.
                - The pull request explains the behavior, design decisions, risks, and verification, and contains the
                  correct closing keyword for this issue; the work is not done merely because code was written.
                """.formatted("#42");
        assertEquals(expectedImplement, NewTaskPreset.implement(issue).instructions().orElseThrow());

        String expectedTriage = "Triage GitHub issue #42: Fix allocation accounting\n\n"
                + "https://github.com/acme/profiler/issues/42"
                + """


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
        assertEquals(expectedTriage, NewTaskPreset.triage(issue).instructions().orElseThrow());
    }

    @Test
    void jiraIssueImplementsWithTheSameWorkflowAndNoClosingStep() {
        JiraIssue issue = new JiraIssue("PROJ-123", "Fix allocation accounting",
                "https://acme.atlassian.net/browse/PROJ-123", List.of("profiling"),
                Optional.of(Instant.parse("2026-10-20T10:15:30Z")));

        NewTaskPreset preset = NewTaskPreset.implement(issue);

        assertEquals("PROJ-123 Fix allocation accounting", preset.name());
        String prompt = preset.instructions().orElseThrow();
        assertTrue(prompt.startsWith("Implement Jira issue PROJ-123: Fix allocation accounting\n\n"
                + "https://acme.atlassian.net/browse/PROJ-123"));
        assertTrue(prompt.contains("Follow a disciplined issue workflow:"));
        assertTrue(prompt.contains("brainstorm the viable interpretations"));
        assertTrue(prompt.contains("Produce a design, review it critically"));
        assertTrue(prompt.contains("Produce a separate implementation plan"));
        assertTrue(prompt.contains("Use subagents deliberately"));
        assertTrue(prompt.contains("Implement in coherent increments, reviewing and testing"));
        assertTrue(prompt.contains("independent review against the issue, design, and plan."));
        assertTrue(prompt.contains("Quality gates (all must pass before completion):"));
        assertTrue(prompt.contains("Definition of done:"));
        assertTrue(prompt.contains("the work is not done merely because code was written"));
        String lower = prompt.toLowerCase();
        assertFalse(lower.contains("closes"), "no GitHub closing keyword in a Jira prompt");
        assertFalse(lower.contains("closing keyword"));
        assertFalse(lower.contains("6."), "no step 6 in a Jira prompt");
        assertFalse(lower.contains("smart commit"));
        assertEquals(Optional.of(SessionMode.WRITES), preset.fixedMode());
        assertEquals(SessionMode.WRITES, preset.mode(true));
        assertTrue(preset.instructionsRequired());
        assertEquals(NewTaskPreset.implement(new OpenIssue(42, "Fix allocation accounting", Optional.empty(),
                List.of(), List.of(), Optional.empty())).tierRoles(), preset.tierRoles());
    }

    @Test
    void jiraIssueTriageValidatesTheCurrentReportInTheMainCheckout() {
        JiraIssue issue = new JiraIssue("PROJ-123", "Fix allocation accounting",
                "https://acme.atlassian.net/browse/PROJ-123", List.of(), Optional.empty());

        NewTaskPreset preset = NewTaskPreset.triage(issue);

        assertEquals("Triage PROJ-123 Fix allocation accounting", preset.name());
        String prompt = preset.instructions().orElseThrow();
        assertTrue(prompt.startsWith("Triage Jira issue PROJ-123: Fix allocation accounting\n\n"
                + "https://acme.atlassian.net/browse/PROJ-123"));
        assertTrue(prompt.contains("Triage the report against the repository as it exists now:"));
        assertTrue(prompt.contains("Treat every behavioral statement in the report as an unverified claim"));
        assertTrue(prompt.contains("still applies to the current source shape"));
        assertTrue(prompt.contains("Propose the smallest meaningful reproducer"));
        assertTrue(prompt.contains("ask the user for approval"));
        assertTrue(prompt.contains("Impact-Likelihood-Workaround assessment"));
        assertTrue(prompt.contains("recommend an explicit priority."));
        assertEquals(Optional.empty(), preset.fixedMode());
        assertEquals(SessionMode.WRITES, preset.mode(false));
        assertEquals(SessionMode.READ_ONLY, preset.mode(true));
        assertEquals(Optional.of(NewTaskPlan.WorkIn.MAIN_CHECKOUT), preset.fixedWorkIn());
        assertTrue(preset.instructionsRequired());
        assertEquals(NewTaskPreset.triage(new OpenIssue(42, "Fix allocation accounting", Optional.empty(),
                List.of(), List.of(), Optional.empty())).tierRoles(), preset.tierRoles());
    }

    @Test
    void implementAndTriageCarrySubagentTierRoleMappingsOtherPresetsDoNot() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(), List.of(), List.of(),
                Optional.empty());

        Optional<String> implementRoles = NewTaskPreset.implement(issue).tierRoles();
        assertTrue(implementRoles.isPresent());
        assertTrue(implementRoles.get().contains("{STRONG}"), implementRoles.get());
        assertTrue(implementRoles.get().contains("{MID}"), implementRoles.get());
        assertTrue(implementRoles.get().contains("{LOW}"), implementRoles.get());

        Optional<String> triageRoles = NewTaskPreset.triage(issue).tierRoles();
        assertTrue(triageRoles.isPresent());
        assertTrue(triageRoles.get().contains("{STRONG}"), triageRoles.get());
        assertTrue(triageRoles.get().contains("{MID}"), triageRoles.get());
        assertTrue(triageRoles.get().contains("{LOW}"), triageRoles.get());

        // A plain New task names no subagent roles, so it gets no tier rules
        // and no tier pickers.
        assertEquals(Optional.empty(), NewTaskPreset.empty().tierRoles());
        assertEquals(Optional.empty(), NewTaskPreset.mainCheckout().tierRoles());
        assertEquals(Optional.empty(),
                NewTaskPreset.start(NewTaskPlan.OpenIn.THIS_WINDOW, SessionMode.WRITES).tierRoles());
    }
}
