package app.drydock.intellij.tasks;

import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenIssue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        String branch = NewTaskPlan.branchFor(preset.name(), Set.of());
        assertEquals("42-fix-allocation", branch);
        assertEquals(Path.of("/worktrees/profiler-42-fix-allocation"),
                NewTaskPlan.folderFor(Path.of("/worktrees"), "profiler", branch));
        assertEquals(SessionMode.WRITES, preset.mode(false));
        assertEquals(SessionMode.WRITES, preset.mode(true), "the hidden read-only state cannot override the preset");
        assertEquals(Optional.of(SessionMode.WRITES), preset.fixedMode());
        assertTrue(preset.instructionsRequired(),
                "Implement must never degrade into an idle agent session");
        assertFalse(NewTaskPreset.empty().instructionsRequired());
    }

    @Test
    void githubIssueSummaryIsAnalysisOnly() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting",
                Optional.of("https://github.com/acme/profiler/issues/42"), List.of(), List.of(), Optional.empty());

        NewTaskPreset preset = NewTaskPreset.summarize(issue);

        assertEquals("Summarize #42 Fix allocation accounting", preset.name());
        String prompt = preset.instructions().orElseThrow();
        assertTrue(prompt.startsWith("Summarize GitHub issue #42: Fix allocation accounting\n\n"
                + "https://github.com/acme/profiler/issues/42"));
        assertTrue(prompt.contains("Distinguish facts"));
        assertTrue(prompt.contains("from inferences"));
        assertTrue(prompt.contains("Do not modify files, create commits, or begin implementation"));
        assertEquals(SessionMode.READ_ONLY, preset.mode(false));
        assertEquals(SessionMode.READ_ONLY, preset.mode(true));
        assertEquals(Optional.of(SessionMode.READ_ONLY), preset.fixedMode());
        assertTrue(preset.instructionsRequired());
    }
}
