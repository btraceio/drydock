package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Platform-backed checks for values that must survive {@code DialogWrapper.init()}. */
public class NewTaskDialogPlatformTest extends BasePlatformTestCase {

    public void testImplementationInstructionsSurviveDialogInitialization() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting",
                Optional.of("https://github.com/acme/profiler/issues/42"), List.of(), List.of(),
                Optional.of(Instant.parse("2026-10-20T10:15:30Z")));
        NewTaskPreset preset = NewTaskPreset.implement(issue);
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty(), preset);
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(preset.instructions(), choice.get().instructions());
        assertEquals(AgentKind.PI, choice.get().agent());
    }

    public void testTriageStartsWithFullAccessInTheMainCheckout() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(), List.of(), List.of(),
                Optional.empty());
        NewTaskPreset preset = NewTaskPreset.triage(issue);
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty(), preset);
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(SessionMode.WRITES, choice.get().mode());
        assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, choice.get().workIn());
        assertEquals(preset.instructions(), choice.get().instructions());
    }

    public void testProjectMainCheckoutEntryCannotCreateAWorktree() {
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(
                    getProject(), inputs(), Optional.empty(), NewTaskPreset.mainCheckout());
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, choice.get().workIn());
    }

    public void testNameIsInferredFromTheDescriptionAndThePresetNameWins() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(), List.of(), List.of(),
                Optional.empty());
        AtomicReference<NewTaskPlan.Choice> inferred = new AtomicReference<>();
        AtomicReference<NewTaskPlan.Choice> presetNamed = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog plain = new NewTaskDialog(getProject(), inputs(), Optional.empty());
            try {
                plain.diagSetInstructions("- Fix the parser\nThen keep it short.");
                inferred.set(plain.choice());
            } finally {
                Disposer.dispose(plain.getDisposable());
            }
            NewTaskDialog implement = new NewTaskDialog(getProject(), inputs(), Optional.empty(),
                    NewTaskPreset.implement(issue));
            try {
                presetNamed.set(implement.choice());
            } finally {
                Disposer.dispose(implement.getDisposable());
            }
        });

        assertEquals("Fix the parser", inferred.get().name());
        assertEquals("#42 Fix allocation accounting", presetNamed.get().name());
    }

    public void testBlankDescriptionStartsAsAChat() {
        AtomicReference<ValidationInfo> validation = new AtomicReference<>();
        AtomicReference<NewTaskPlan.Choice> blank = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty());
            try {
                validation.set(dialog.doValidate());
                blank.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        // A blank description no longer refuses: it names the task "New task",
        // works in the main checkout, and tells the agent nothing -- a chat.
        assertEquals(null, validation.get());
        assertEquals("New task", blank.get().name());
        assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, blank.get().workIn());
        assertEquals(Optional.empty(), blank.get().instructions());
        assertEquals(Optional.empty(), NewTaskPlan.initialPrompt(blank.get()));
    }

    public void testWorkInFollowsTheDescriptionUntilTheUserPicks() {
        AtomicReference<NewTaskPlan.WorkIn> blank = new AtomicReference<>();
        AtomicReference<NewTaskPlan.WorkIn> described = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty());
            try {
                blank.set(dialog.choice().workIn());
                dialog.diagSetInstructions("Fix the parser");
                described.set(dialog.choice().workIn());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, blank.get());
        assertEquals(NewTaskPlan.WorkIn.NEW_WORKTREE, described.get());
    }

    public void testModelDefaultsToEmptyAndIsCarriedOnTheChoice() {
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty(), NewTaskPreset.empty());
            try {
                assertEquals(Optional.empty(), dialog.choice().model());
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(Optional.empty(), choice.get().model());
    }

    private static NewTaskPlan.Inputs inputs() {
        return inputs(kind -> ModelTiers.EMPTY, kind -> true);
    }

    private static NewTaskPlan.Inputs inputs(Function<AgentKind, ModelTiers> lastUsedTiers,
                                             Predicate<AgentKind> supportsSubagents) {
        Path root = Path.of("/tmp/profiler");
        SharedProject project = SharedProject.local(RepositoryId.newId(), root, "profiler", Instant.EPOCH);
        Agent pi = new Agent() {
            @Override public AgentKind kind() { return AgentKind.PI; }
            @Override public String displayName() { return "Pi"; }
            @Override public boolean isAvailable() { return true; }
            @Override public String describeSearched() { return "test"; }
        };
        return new NewTaskPlan.Inputs(project, "profiler", Optional.empty(), List.of(pi), AgentKind.PI,
                "origin/main", List.of("origin/main"), List.of("origin"), Set.of(), Path.of("/tmp"), false,
                kind -> Optional.empty(), kind -> List.of(), lastUsedTiers, supportsSubagents);
    }

    public void testTriageTiersComposeTheSubagentModelRuleWhenTheHarnessDispatchesSubagents() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(), List.of(), List.of(),
                Optional.empty());
        ModelTiers remembered = new ModelTiers(Optional.of("strong-m"), Optional.of("mid-m"), Optional.of("low-m"));
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(),
                    inputs(kind -> remembered, kind -> true), Optional.empty(), NewTaskPreset.triage(issue));
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(Optional.of(remembered), choice.get().tiers());
        String rule = choice.get().subagentModelRule().orElseThrow();
        assertTrue(rule.contains("exactly `strong-m`"));
        assertTrue(rule.contains("exactly `mid-m`"));
        assertTrue(rule.contains("exactly `low-m`"));
        assertTrue(rule.startsWith("When you dispatch subagents you MUST"));
        // The triage role mapping spent the placeholders.
        assertFalse(rule.contains("{STRONG}") || rule.contains("{MID}") || rule.contains("{LOW}"));
    }

    public void testHiddenTierPickersRecordNothingWhenTheHarnessHasNoSubagents() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(), List.of(), List.of(),
                Optional.empty());
        ModelTiers remembered = new ModelTiers(Optional.of("strong-m"), Optional.empty(), Optional.empty());
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(),
                    inputs(kind -> remembered, kind -> false), Optional.empty(), NewTaskPreset.triage(issue));
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        // The rows are hidden: nothing to record (the remembered tiers stay),
        // and rules that bind nobody are noise.
        assertEquals(Optional.empty(), choice.get().tiers());
        assertEquals(Optional.empty(), choice.get().subagentModelRule());
    }

    public void testAPlainNewTaskCarriesNoTierRuleEvenWithRememberedTiers() {
        ModelTiers remembered = new ModelTiers(Optional.of("strong-m"), Optional.of("mid-m"), Optional.of("low-m"));
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(),
                    inputs(kind -> remembered, kind -> true), Optional.empty());
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(Optional.empty(), choice.get().tiers());
        assertEquals(Optional.empty(), choice.get().subagentModelRule());
    }
}
