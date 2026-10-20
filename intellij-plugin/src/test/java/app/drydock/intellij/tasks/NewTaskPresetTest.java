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
        String branch = NewTaskPlan.branchFor(preset.name(), Set.of());
        assertEquals("42-fix-allocation", branch);
        assertEquals(Path.of("/worktrees/profiler-42-fix-allocation"),
                NewTaskPlan.folderFor(Path.of("/worktrees"), "profiler", branch));
        assertEquals(SessionMode.WRITES, preset.mode(false));
        assertEquals(SessionMode.WRITES, preset.mode(true), "the hidden read-only state cannot override the preset");
        assertTrue(preset.writableOnly());
    }
}
