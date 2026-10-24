package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.intellij.tasks.NewTaskPlan.FixedWorktree;
import app.drydock.intellij.tasks.NewTaskPlan.OpenIn;
import app.drydock.intellij.tasks.NewTaskPlan.WorkIn;
import app.drydock.projects.SharedProject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewTaskPlanTest {

    private static final Path ROOT = Path.of("/w/drydock");

    private static NewTaskPlan.Inputs inputs(Optional<FixedWorktree> fixed, boolean writerActive) {
        return new NewTaskPlan.Inputs(SharedProject.local(RepositoryId.newId(), ROOT, "drydock", Instant.EPOCH),
                "drydock", fixed, List.of(), AgentKind.CLAUDE, "origin/main", List.of("main", "origin/main"),
                List.of("origin"), Set.of("main", "taken-branch"), Path.of("/b"), writerActive,
                kind -> Optional.empty(), kind -> List.of());
    }

    private static Choice choice(String name, Optional<String> instructions, SessionMode mode, WorkIn workIn,
                                 String branch, Path folder) {
        return new Choice(name, instructions, AgentKind.CLAUDE, mode, workIn, branch, "origin/main", folder,
                OpenIn.OWN_WINDOW, Optional.empty());
    }

    @Test
    void branchFollowsFirstThreeWords() {
        assertEquals("task-centric-tool-window",
                NewTaskPlan.branchFor("Task-centric tool window for drydock", Set.of()));
    }

    @Test
    void takenBranchGetsSuffix() {
        assertEquals("fix-the-parser-2", NewTaskPlan.branchFor("Fix the parser", Set.of("fix-the-parser")));
    }

    @Test
    void nameWithoutUsableSlugFallsBackToTask() {
        assertEquals("task", NewTaskPlan.branchFor("!!!", Set.of()));
        assertEquals("opravit-chybu", NewTaskPlan.branchFor("Opravit chybu ž", Set.of()));
        assertEquals("task", NewTaskPlan.branchFor("🚀", Set.of()));
        assertEquals("task-2", NewTaskPlan.branchFor("", Set.of("task")));
    }

    @Test
    void folderUsesBaseRepoAndBranch() {
        assertEquals(Path.of("/b/drydock-x"), NewTaskPlan.folderFor(Path.of("/b"), "drydock", "x"));
    }

    @Test
    void summaryNewWorktreeExactCopy() {
        Choice choice = choice("Task-centric tool window", Optional.empty(), SessionMode.WRITES, WorkIn.NEW_WORKTREE,
                "task-centric-tool-window", Path.of("/b/drydock-task-centric-tool-window"));

        assertEquals("New branch task-centric-tool-window from origin/main in /b/drydock-task-centric-tool-window",
                NewTaskPlan.summary(inputs(Optional.empty(), false), choice));
    }

    @Test
    void summaryReadOnlyExactCopy() {
        Choice choice = choice("Explain", Optional.empty(), SessionMode.READ_ONLY, WorkIn.MAIN_CHECKOUT, "", ROOT);

        assertEquals("Runs in the main checkout of drydock",
                NewTaskPlan.summary(inputs(Optional.empty(), false), choice));
    }

    @Test
    void summaryExistingWorktreeExactCopy() {
        FixedWorktree fixed = new FixedWorktree(Path.of("/wt/drydock-old"), "old-experiment");
        Choice choice = choice("Revive", Optional.empty(), SessionMode.WRITES, WorkIn.EXISTING_WORKTREE,
                "old-experiment", fixed.dir());

        assertEquals("In existing worktree ⑂ old-experiment", NewTaskPlan.summary(inputs(Optional.of(fixed), false),
                choice));
    }

    @Test
    void initialPromptIsEmptyWhenInstructionsBlankEvenWithName() {
        Choice choice = choice("Add a farewell method", Optional.of("  \n "), SessionMode.WRITES, WorkIn.NEW_WORKTREE,
                "add-a-farewell", Path.of("/b/drydock-add-a-farewell"));

        assertEquals(Optional.empty(), NewTaskPlan.initialPrompt(choice));
    }

    @Test
    void initialPromptPrependsClarifyFirst() {
        Choice choice = choice("Farewell", Optional.of("Add farewell() to Greeter.\nKeep it short."),
                SessionMode.WRITES, WorkIn.NEW_WORKTREE, "farewell", Path.of("/b/drydock-farewell"));

        String prompt = NewTaskPlan.initialPrompt(choice).orElseThrow();
        assertTrue(prompt.startsWith("Before writing any code"), prompt);
        assertTrue(prompt.endsWith("Add farewell() to Greeter.\nKeep it short."), prompt);
    }

    @Test
    void refusesBlankName() {
        Choice choice = choice("  ", Optional.empty(), SessionMode.WRITES, WorkIn.NEW_WORKTREE, "x", Path.of("/b/x"));

        assertEquals(Optional.of("Describe what the agent should do."),
                NewTaskPlan.refusal(inputs(Optional.empty(), false), choice, folder -> false));
    }

    @Test
    void nameIsTheFirstNonBlankLineStrippedOfMarkdown() {
        assertEquals("Fix the parser", NewTaskPlan.nameFor("\n  \n- Fix the parser\nThen keep it short."));
        assertEquals("Fix the parser", NewTaskPlan.nameFor("## Fix the parser"));
        assertEquals("Fix the parser", NewTaskPlan.nameFor("1. Fix the parser"));
        assertEquals("Fix the parser", NewTaskPlan.nameFor("- ## Fix the parser"));
        assertEquals("Fix the parser", NewTaskPlan.nameFor("   Fix the parser.   "));
        assertEquals("Fix the parser", NewTaskPlan.nameFor("Fix the parser:  \nthen keep it short"));
    }

    @Test
    void longNameIsCutAtAWordBoundary() {
        assertEquals("Make the worktree pruning pass respect an external filter",
                NewTaskPlan.nameFor("Make the worktree pruning pass respect an external filter that the caller "
                        + "supplies instead of its own"));
    }

    @Test
    void unusableDescriptionFallsBackToNewTask() {
        assertEquals("New task", NewTaskPlan.nameFor("!!!"));
        assertEquals("New task", NewTaskPlan.nameFor("🚀 🚀"));
        assertEquals("New task", NewTaskPlan.nameFor("   \n "));
        assertEquals("New task", NewTaskPlan.nameFor("---"));
        assertEquals("New task", NewTaskPlan.nameFor(null));
    }

    @Test
    void inferredNameNamesTheBranch() {
        assertEquals("fix-the-parser",
                NewTaskPlan.branchFor(NewTaskPlan.nameFor("- Fix the parser\nThen keep it short."), Set.of()));
    }

    @Test
    void refusesTakenBranch() {
        Choice choice = choice("T", Optional.empty(), SessionMode.WRITES, WorkIn.NEW_WORKTREE, "taken-branch",
                Path.of("/b/x"));

        assertEquals(Optional.of("A branch named taken-branch already exists. Choose another name."),
                NewTaskPlan.refusal(inputs(Optional.empty(), false), choice, folder -> false));
    }

    @Test
    void refusesExistingNonEmptyFolder() {
        Path folder = Path.of("/b/drydock-x");
        Choice choice = choice("T", Optional.empty(), SessionMode.WRITES, WorkIn.NEW_WORKTREE, "x", folder);

        assertEquals(Optional.of("/b/drydock-x already exists and is not empty."),
                NewTaskPlan.refusal(inputs(Optional.empty(), false), choice, folder::equals));
    }

    @Test
    void refusesInvalidBranchName() {
        Choice choice = choice("T", Optional.empty(), SessionMode.WRITES, WorkIn.NEW_WORKTREE, "bad..name",
                Path.of("/b/x"));

        assertTrue(NewTaskPlan.refusal(inputs(Optional.empty(), false), choice, folder -> false).isPresent());
    }

    @Test
    void mainCheckoutNeedsNoBranchOrFolder() {
        Choice choice = choice("T", Optional.empty(), SessionMode.READ_ONLY, WorkIn.MAIN_CHECKOUT, "", ROOT);

        assertEquals(Optional.empty(), NewTaskPlan.refusal(inputs(Optional.empty(), false), choice, folder -> true));
    }

    @Test
    void warnsWritesInMainWithActiveWriter() {
        Choice choice = choice("T", Optional.empty(), SessionMode.WRITES, WorkIn.MAIN_CHECKOUT, "", ROOT);

        assertEquals(Optional.of("Another writing agent is active in the main checkout. Two writers can overwrite "
                        + "each other."),
                NewTaskPlan.warning(inputs(Optional.empty(), true), choice));
        assertEquals(Optional.of("A writing agent in the main checkout shares the tree with your own edits."),
                NewTaskPlan.warning(inputs(Optional.empty(), false), choice));
        assertEquals(Optional.empty(), NewTaskPlan.warning(inputs(Optional.empty(), true),
                choice("T", Optional.empty(), SessionMode.READ_ONLY, WorkIn.MAIN_CHECKOUT, "", ROOT)));
    }

    @Test
    void primaryLabel() {
        assertEquals("Start task", NewTaskPlan.primaryLabel(false));
        assertEquals("Start anyway", NewTaskPlan.primaryLabel(true));
    }

    @Test
    void refusesWhenNoAgentIsAvailable() {
        Choice noAgent = new Choice("T", Optional.empty(), null, SessionMode.WRITES, WorkIn.NEW_WORKTREE, "x",
                "origin/main", Path.of("/b/x"), OpenIn.OWN_WINDOW, Optional.empty());

        assertEquals(Optional.of("No agent is installed. Install Claude, Codex or pi to start a task."),
                NewTaskPlan.refusal(inputs(Optional.empty(), false), noAgent, folder -> false));
    }

    @Test
    void refusesARelativeFolder() {
        Choice relative = choice("T", Optional.empty(), SessionMode.WRITES, WorkIn.NEW_WORKTREE, "x", Path.of("."));

        assertEquals(Optional.of("Choose a full folder path for the worktree."),
                NewTaskPlan.refusal(inputs(Optional.empty(), false), relative, folder -> false));
    }

    @Test
    void branchKeepsWordsAroundSlashesAndTheWordWorktree() {
        assertEquals("ci-cd-pipeline-fix", NewTaskPlan.branchFor("CI/CD pipeline fix", Set.of()));
        assertEquals("fix-a-b-parser", NewTaskPlan.branchFor("Fix a/b parser", Set.of()));
        assertEquals("worktree", NewTaskPlan.branchFor("Worktree", Set.of()));
    }

    @Test
    void theDialogDefaultsToThisWindow() {
        assertEquals(OpenIn.THIS_WINDOW, NewTaskPreset.empty().openIn());
        assertEquals(Optional.empty(), NewTaskPreset.empty().fixedMode());
    }
}
