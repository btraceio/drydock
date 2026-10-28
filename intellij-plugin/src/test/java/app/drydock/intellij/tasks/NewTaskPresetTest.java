package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenIssue;

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
