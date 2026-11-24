package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SshRemote;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.WorktreeNaming;
import app.drydock.intellij.workspace.WorkspaceSettings;
import app.drydock.projects.SharedProject;
import app.drydock.sdlc.MaterializedWorkflow;
import app.drydock.sdlc.ResourceManifestJournal;
import app.drydock.sdlc.SelectedWorkflow;
import app.drydock.sdlc.SessionWorkflowResourceOwner;
import app.drydock.sdlc.WorkflowBundle;
import app.drydock.sdlc.WorkflowMaterializationRequest;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;
import app.drydock.sdlc.WorkflowMaterializer;
import app.drydock.sdlc.WorkflowResolution;
import app.drydock.sdlc.WorkflowResourceLease;
import app.drydock.sdlc.WorkflowTarget;
import app.drydock.tasks.StartPlan;
import app.drydock.tasks.TaskId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;

import javax.swing.JCheckBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.text.JTextComponent;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Platform-backed checks for values that must survive {@code DialogWrapper.init()}. */
public class NewTaskDialogPlatformTest extends BasePlatformTestCase {

    public void testPullRequestEntryPreservesTheExplicitTargetInTheAcceptedChoice() {
        var pr = new OpenPullRequest(898, "Target", "upstream-head", "main", false,
                Optional.empty(), Optional.of("https://github.com/DataDog/java-profiler/pull/898"));
        NewTaskPreset preset = NewTaskPreset.empty().withPullRequest(pr);
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty(), preset);
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
        assertEquals(preset.targetPullRequest(), choice.get().targetPullRequest());
        assertEquals(898, choice.get().targetPullRequest().orElseThrow().number());
    }

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

        assertEquals(Optional.of(preset.launchInstructions(preset.instructions().orElseThrow())),
                choice.get().instructions());
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
        assertEquals(Optional.of(preset.launchInstructions(preset.instructions().orElseThrow())),
                choice.get().instructions());
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

    public void testSettingAModelKeepsStartEnabled() {
        AtomicReference<ValidationInfo> validation = new AtomicReference<>();
        AtomicReference<Boolean> okEnabled = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                assertNull("start enabled before a model is picked", dialog.doValidate());
                dialog.diagSetModel("claude-sonnet-4-5");
                validation.set(dialog.doValidate());
                okEnabled.set(dialog.diagStartEnabled());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertNull("a picked model must not refuse the launch", validation.get());
        assertEquals(Boolean.TRUE, okEnabled.get());
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

    public void testGeneratedBranchAndFolderStayStableAcrossTaskAndLocationChanges() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                NewTaskPlan.Choice original = dialog.choice();
                assertTrue(original.branch(), original.branch().matches("drydock/[a-z]+-[a-z]+-[a-z]+"));
                assertFalse(original.branch().contains("parser"));
                assertEquals(Path.of("/tmp/profiler-" + WorktreeNaming.slug(original.branch())),
                        original.folder());

                dialog.diagSetInstructions("Investigate a completely different task");
                assertEquals(original.branch(), dialog.choice().branch());
                assertEquals(original.folder(), dialog.choice().folder());
                assertEquals("Investigate a completely different task", dialog.choice().name());

                dialog.diagSetInstructions("");
                assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, dialog.choice().workIn());
                dialog.diagSetInstructions("Fix the parser again");
                assertEquals(original.branch(), dialog.choice().branch());
                assertEquals(original.folder(), dialog.choice().folder());

                control(dialog, "mainCheckout", JBRadioButton.class).doClick();
                assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, dialog.choice().workIn());
                control(dialog, "newWorktree", JBRadioButton.class).doClick();
                assertEquals(original.branch(), dialog.choice().branch());
                assertEquals(original.folder(), dialog.choice().folder());
                control(dialog, "readOnly", JBRadioButton.class).doClick();
                assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, dialog.choice().workIn());
                control(dialog, "writes", JBRadioButton.class).doClick();
                assertEquals(original.branch(), dialog.choice().branch());
                assertEquals(original.folder(), dialog.choice().folder());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testManualBranchAndFolderEditsSurviveDescriptionChanges() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                control(dialog, "branch", JBTextField.class).setText("my-branch");
                assertEquals(Path.of("/tmp/profiler-my-branch"), dialog.choice().folder());
                dialog.diagSetInstructions("A different task");
                assertEquals("my-branch", dialog.choice().branch());
                assertEquals(Path.of("/tmp/profiler-my-branch"), dialog.choice().folder());

                control(dialog, "folder", TextFieldWithBrowseButton.class).setText("/tmp/custom-checkout");
                control(dialog, "branch", JBTextField.class).setText("my-other-branch");
                dialog.diagSetInstructions("Another task");
                assertEquals("my-other-branch", dialog.choice().branch());
                assertEquals(Path.of("/tmp/custom-checkout"), dialog.choice().folder());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testIssueImplementationUsesAWhimsicalBranchWithoutChangingItsTitle() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(), List.of(), List.of(),
                Optional.empty());
        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty(),
                    NewTaskPreset.implement(issue));
            try {
                NewTaskPlan.Choice choice = dialog.choice();
                assertEquals(NewTaskPlan.WorkIn.NEW_WORKTREE, choice.workIn());
                assertTrue(choice.branch(), choice.branch().matches("drydock/[a-z]+-[a-z]+-[a-z]+"));
                assertEquals(Path.of("/tmp/profiler-" + WorktreeNaming.slug(choice.branch())), choice.folder());
                assertEquals("#42 Fix allocation accounting", choice.name());
                String prompt = NewTaskPlan.initialPrompt(choice).orElseThrow();
                assertTrue(prompt, prompt.contains("call session_rename"));
                assertTrue(prompt, prompt.contains(choice.instructions().orElseThrow()));
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    private static <T> T control(NewTaskDialog dialog, String name, Class<T> type) {
        try {
            var field = NewTaskDialog.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(dialog));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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

    public void testExistingWorktreeKeepsItsBranchAndFolder() {
        NewTaskPlan.FixedWorktree fixed = new NewTaskPlan.FixedWorktree(Path.of("/tmp/existing"), "existing-branch");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(),
                    inputs(Optional.of(fixed), kind -> ModelTiers.EMPTY, kind -> true), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                assertEquals(NewTaskPlan.WorkIn.EXISTING_WORKTREE, dialog.choice().workIn());
                assertEquals(fixed.branch(), dialog.choice().branch());
                assertEquals(fixed.dir(), dialog.choice().folder());
                dialog.diagSetInstructions("A different task");
                assertEquals(fixed.branch(), dialog.choice().branch());
                assertEquals(fixed.dir(), dialog.choice().folder());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    private static NewTaskPlan.Inputs inputs() {
        return inputs(kind -> ModelTiers.EMPTY, kind -> true);
    }

    private static NewTaskPlan.Inputs inputs(Function<AgentKind, ModelTiers> lastUsedTiers,
                                             Predicate<AgentKind> supportsSubagents) {
        return inputs(Optional.empty(), lastUsedTiers, supportsSubagents);
    }

    private static NewTaskPlan.Inputs inputs(Optional<NewTaskPlan.FixedWorktree> fixed,
                                             Function<AgentKind, ModelTiers> lastUsedTiers,
                                             Predicate<AgentKind> supportsSubagents) {
        return inputs(fixed, List.of(agent(AgentKind.PI)), lastUsedTiers, supportsSubagents);
    }

    private static NewTaskPlan.Inputs inputs(Optional<NewTaskPlan.FixedWorktree> fixed, List<Agent> agents,
                                             Function<AgentKind, ModelTiers> lastUsedTiers,
                                             Predicate<AgentKind> supportsSubagents) {
        Path root = Path.of("/tmp/profiler");
        SharedProject project = SharedProject.local(RepositoryId.newId(), root, "profiler", Instant.EPOCH);
        return new NewTaskPlan.Inputs(project, "profiler", fixed, agents, AgentKind.PI,
                "origin/main", List.of("origin/main"), List.of("origin"), Set.of(), Path.of("/tmp"), false,
                kind -> Optional.empty(), kind -> List.of(), lastUsedTiers, supportsSubagents);
    }

    private static Agent agent(AgentKind kind) {
        return new Agent() {
            @Override public AgentKind kind() { return kind; }
            @Override public String displayName() { return kind.name(); }
            @Override public boolean isAvailable() { return true; }
            @Override public String describeSearched() { return "test"; }
        };
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

    // T19: the "Run on workspace box" offer. Visible only while the
    // opt-in flag is on; enabled only when every gating condition holds;
    // off by default; and a gate that fails clears a checked box, so the
    // value can never ride a choice the conditions no longer permit.

    // Eval mode: the checkbox follows the harness's evalAvailable gate, never
    // rides a remote launch, and a gate that fails clears a checked box.

    public void testEvalModeFollowsTheHarnessGateAndNeverRidesARemoteLaunch() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceSettings.setEnabled(true);
            NewTaskDialog dialog = new NewTaskDialog(getProject(), claudeInputsEval(), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                JCheckBox mode = control(dialog, "evalMode", JCheckBox.class);
                JCheckBox box = control(dialog, "workspaceBox", JCheckBox.class);

                // Claude's docker probe declined eval: the checkbox is hidden
                // and the choice carries no eval.
                assertFalse(mode.isVisible());
                assertFalse(mode.isEnabled());
                assertFalse(mode.isSelected());
                assertFalse(dialog.choice().eval());

                // Pi injects eval through the launch env: the offer returns, off.
                agentButton(dialog, AgentKind.PI).doClick();
                assertTrue(mode.isEnabled());
                assertFalse(mode.isSelected());
                assertFalse(dialog.choice().eval());

                // A checked box rides the choice.
                mode.setSelected(true);
                assertTrue(dialog.choice().eval());

                // The remote arm is Claude-only and never eval: selecting the
                // workspace box clears the checked eval in the same refresh pass.
                agentButton(dialog, AgentKind.CLAUDE).doClick();
                box.setSelected(true);
                assertFalse(mode.isEnabled());
                assertFalse(mode.isSelected());
                assertFalse(dialog.choice().eval());
                assertTrue(dialog.choice().onWorkspaceBox());
            } finally {
                WorkspaceSettings.setEnabled(false);
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testEvalModeIsDisabledAndClearedWhileSavingForLater() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), claudeInputsEval(), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                JCheckBox mode = control(dialog, "evalMode", JCheckBox.class);
                JCheckBox save = control(dialog, "saveForLater", JCheckBox.class);
                agentButton(dialog, AgentKind.PI).doClick();
                mode.setSelected(true);
                assertTrue(dialog.choice().eval());

                // A saved task starts as an ordinary session: saving disables
                // the offer and clears the checked box. (doClick: the save
                // toggle drives refresh through its action listener.)
                save.doClick();
                assertFalse(mode.isEnabled());
                assertFalse(mode.isSelected());
                assertFalse(dialog.choice().eval());

                // Back to launch mode the offer returns, off — never auto-restored.
                save.doClick();
                assertTrue(mode.isEnabled());
                assertFalse(mode.isSelected());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testWorkspaceBoxIsInvisibleAndOffWhileTheFlagIsOff() {
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();
        AtomicReference<JCheckBox> box = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceSettings.setEnabled(false);
            NewTaskDialog dialog = new NewTaskDialog(getProject(), claudeInputs(), Optional.empty());
            try {
                box.set(control(dialog, "workspaceBox", JCheckBox.class));
                choice.set(dialog.choice());
            } finally {
                WorkspaceSettings.setEnabled(false);
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertFalse(box.get().isVisible());
        assertFalse(box.get().isSelected());
        assertFalse(choice.get().onWorkspaceBox());
    }

    public void testWorkspaceBoxDefaultsOffFollowsTheGatesAndRidesTheChoice() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceSettings.setEnabled(true);
            NewTaskDialog dialog = new NewTaskDialog(getProject(), claudeInputs(), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                JCheckBox box = control(dialog, "workspaceBox", JCheckBox.class);
                assertTrue(box.isVisible());
                // Default off, offered for a Claude launch in a new worktree.
                assertTrue(box.isEnabled());
                assertFalse(box.isSelected());
                assertFalse(dialog.choice().onWorkspaceBox());

                // The value round-trips through the choice.
                box.setSelected(true);
                assertTrue(dialog.choice().onWorkspaceBox());

                // A non-Claude agent is no remote launch: the gate clears the
                // checked box the moment the harness switches (INV-16 dialog half).
                agentButton(dialog, AgentKind.PI).doClick();
                assertFalse(box.isEnabled());
                assertFalse(box.isSelected());
                assertFalse(dialog.choice().onWorkspaceBox());

                // Back on Claude the offer returns, but never auto-checked.
                agentButton(dialog, AgentKind.CLAUDE).doClick();
                assertTrue(box.isEnabled());
                assertFalse(dialog.choice().onWorkspaceBox());

                // Both launch targets are servable; the main checkout too.
                control(dialog, "mainCheckout", JBRadioButton.class).doClick();
                assertTrue(box.isEnabled());
                control(dialog, "newWorktree", JBRadioButton.class).doClick();
                assertTrue(box.isEnabled());

                // fish cannot carry the nested POSIX quoting: no offer (ASM-14).
                dialog.diagSetLoginShell("/usr/local/bin/fish");
                assertFalse(box.isEnabled());
                assertFalse(box.isSelected());
                assertFalse(dialog.choice().onWorkspaceBox());
                dialog.diagSetLoginShell("/bin/zsh");
                assertTrue(box.isEnabled());

                // A saved task launches nothing, so the offer steps aside too.
                control(dialog, "saveForLater", JCheckBox.class).doClick();
                assertFalse(box.isEnabled());
                control(dialog, "saveForLater", JCheckBox.class).doClick();
                assertTrue(box.isEnabled());
            } finally {
                WorkspaceSettings.setEnabled(false);
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testWorkspaceBoxIsNotEnabledForAFixedWorktree() {
        NewTaskPlan.FixedWorktree fixed = new NewTaskPlan.FixedWorktree(Path.of("/tmp/existing"), "existing-branch");
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceSettings.setEnabled(true);
            NewTaskDialog dialog = new NewTaskDialog(getProject(), claudeInputs(fixed), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                JCheckBox box = control(dialog, "workspaceBox", JCheckBox.class);
                assertTrue(box.isVisible());
                assertFalse(box.isEnabled());
                assertFalse(dialog.choice().onWorkspaceBox());
            } finally {
                WorkspaceSettings.setEnabled(false);
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testWorkspaceBoxIsNotEnabledForABindingCarryingPreset() {
        StartPlan.Segment segment = new StartPlan.Segment(RepositoryId.newId(), "profiler", List.of());
        StartPlan plan = new StartPlan(TaskId.of("fix-parser"), List.of(segment),
                List.of(new StartPlan.Stage(1, List.of(segment))), List.of(), List.of(), List.of());
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceSettings.setEnabled(true);
            NewTaskDialog dialog = new NewTaskDialog(getProject(), claudeInputs(),
                    Optional.empty(), NewTaskPreset.segmentStart("Fix the parser", plan, segment));
            try {
                JCheckBox box = control(dialog, "workspaceBox", JCheckBox.class);
                assertTrue(box.isVisible());
                assertFalse(box.isEnabled());
                assertFalse(dialog.choice().onWorkspaceBox());
            } finally {
                WorkspaceSettings.setEnabled(false);
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testWorkspaceBoxIsNotEnabledForAnSshRemoteProject() {
        Path root = Path.of("/tmp/profiler");
        SharedProject remote = new SharedProject(RepositoryId.newId(), root, "profiler", Instant.EPOCH,
                Optional.of(new SshRemote("box.example", "/remote/profiler")));
        ApplicationManager.getApplication().invokeAndWait(() -> {
            WorkspaceSettings.setEnabled(true);
            NewTaskDialog dialog = new NewTaskDialog(getProject(),
                    claudeInputs(Optional.empty(), remote), Optional.empty());
            try {
                dialog.diagSetInstructions("Fix the parser");
                JCheckBox box = control(dialog, "workspaceBox", JCheckBox.class);
                assertTrue(box.isVisible());
                assertFalse(box.isEnabled());
                assertFalse(dialog.choice().onWorkspaceBox());
            } finally {
                WorkspaceSettings.setEnabled(false);
                Disposer.dispose(dialog.getDisposable());
            }
        });
    }

    public void testThePreExistingChoiceConstructorStaysLocal() {
        // Today's 13-argument signature keeps every existing construction
        // site compiling, and delegates to a local (false) remote offer.
        NewTaskPlan.Choice today = new NewTaskPlan.Choice("Task", Optional.of("Fix the parser"), AgentKind.CLAUDE,
                SessionMode.WRITES, NewTaskPlan.WorkIn.NEW_WORKTREE, "b", "origin/main", Path.of("/tmp/f"),
                NewTaskPlan.OpenIn.THIS_WINDOW, Optional.empty(), Optional.empty(), Optional.empty());
        assertFalse(today.onWorkspaceBox());

        NewTaskPlan.Choice remote = new NewTaskPlan.Choice("Task", Optional.of("Fix the parser"), AgentKind.CLAUDE,
                SessionMode.WRITES, NewTaskPlan.WorkIn.NEW_WORKTREE, "b", "origin/main", Path.of("/tmp/f"),
                NewTaskPlan.OpenIn.THIS_WINDOW, Optional.empty(), Optional.empty(), Optional.empty(), true);
        assertTrue(remote.onWorkspaceBox());
    }

    private static JBRadioButton agentButton(NewTaskDialog dialog, AgentKind kind) {
        try {
            var field = NewTaskDialog.class.getDeclaredField("agentButtons");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<AgentKind, JBRadioButton> buttons = (Map<AgentKind, JBRadioButton>) field.get(dialog);
            JBRadioButton button = buttons.get(kind);
            if (button == null) {
                throw new AssertionError("No " + kind + " agent button in the dialog");
            }
            return button;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static NewTaskPlan.Inputs claudeInputs() {
        return claudeInputs(Optional.empty(), SharedProject.local(RepositoryId.newId(), Path.of("/tmp/profiler"),
                "profiler", Instant.EPOCH));
    }

    /** As {@link #claudeInputs()}, with eval routable by Pi only (Claude's docker probe declined). */
    private static NewTaskPlan.Inputs claudeInputsEval() {
        Agent claude = agentOf(AgentKind.CLAUDE);
        Agent pi = agentOf(AgentKind.PI);
        return new NewTaskPlan.Inputs(SharedProject.local(RepositoryId.newId(), Path.of("/tmp/profiler"),
                "profiler", Instant.EPOCH), "profiler", Optional.empty(), List.of(claude, pi), AgentKind.CLAUDE,
                "origin/main", List.of("origin/main"), List.of("origin"), Set.of(), Path.of("/tmp"), "", false,
                kind -> Optional.empty(), kind -> List.of(), kind -> ModelTiers.EMPTY, kind -> true,
                kind -> kind == AgentKind.PI);
    }

    private static NewTaskPlan.Inputs claudeInputs(NewTaskPlan.FixedWorktree fixed) {
        return claudeInputs(Optional.of(fixed), SharedProject.local(RepositoryId.newId(), Path.of("/tmp/profiler"),
                "profiler", Instant.EPOCH));
    }

    private static NewTaskPlan.Inputs claudeInputs(Optional<NewTaskPlan.FixedWorktree> fixed, SharedProject project) {
        Agent claude = agentOf(AgentKind.CLAUDE);
        Agent pi = agentOf(AgentKind.PI);
        return new NewTaskPlan.Inputs(project, "profiler", fixed, List.of(claude, pi), AgentKind.CLAUDE,
                "origin/main", List.of("origin/main"), List.of("origin"), Set.of(), Path.of("/tmp"), false,
                kind -> Optional.empty(), kind -> List.of(), kind -> ModelTiers.EMPTY, kind -> true);
    }

    private static Agent agentOf(AgentKind kind) {
        return new Agent() {
            @Override public AgentKind kind() { return kind; }
            @Override public String displayName() { return kind == AgentKind.CLAUDE ? "Claude" : "Pi"; }
            @Override public boolean isAvailable() { return true; }
            @Override public String describeSearched() { return "test"; }
        };
    }

    // ---- Project workflow wiring (T17): source indicator, bypass, Start flow ----

    private static final SelectedWorkflow.SourceDescriptor SOURCE =
            new SelectedWorkflow.SourceDescriptor(SelectedWorkflow.SourceKind.INLINE, "k", Optional.empty());

    /** One dialog's fakes: the UI executor is a queue the test drains on the EDT, materializations complete on demand. */
    private final class Wiring {
        final ExecutorService background = Executors.newCachedThreadPool();
        final Queue<Runnable> uiQueue = new ArrayDeque<>();
        final List<CompletableFuture<WorkflowMaterializer.Result>> materializations = new ArrayList<>();
        final AtomicInteger resolves = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        volatile WorkflowResolution resolution;
        WorkflowInvocationController controller;
        final WorkflowTarget target;

        Wiring(WorkflowTarget target) {
            this.target = target;
            this.resolution = new WorkflowResolution.Selected(selected("project text"));
        }

        SelectedWorkflow selected(String text) {
            return new SelectedWorkflow(RepositoryId.newId(), target, SOURCE, text, WorkflowBundle.empty(), "digest");
        }

        NewTaskDialog.Workflow workflow() throws IOException {
            Path dir = Files.createTempDirectory("t17-dialog-");
            var journal = ResourceManifestJournal.open(dir.resolve("sdlc/manifest.rmj"));
            var owner = new SessionWorkflowResourceOwner(journal, Runnable::run,
                    new SessionWorkflowResourceOwner.Cleaner() {
                        public CompletableFuture<Void> deleteLocal(ResourceManifestJournal.Lease l) {
                            return CompletableFuture.completedFuture(null);
                        }

                        public CompletableFuture<Void> deleteRemote(ResourceManifestJournal.Debt d) {
                            return CompletableFuture.completedFuture(null);
                        }
                    });
            WorkflowMaterializer materializer = (req, cancel) -> {
                var f = new CompletableFuture<WorkflowMaterializer.Result>();
                synchronized (materializations) {
                    materializations.add(f);
                }
                return f;
            };
            return new NewTaskDialog.Workflow(ui -> {
                controller = new WorkflowInvocationController(background, r -> {
                    synchronized (uiQueue) {
                        uiQueue.add(r);
                    }
                }, (t, cancel) -> {
                    resolves.incrementAndGet();
                    return resolution;
                }, materializer, owner, ui, 5_000, "default seed",
                        new WorkflowInvocationState.Input(target.name(), "none"));
                return controller;
            }, kind -> new WorkflowInvocationController.Request(target, Destination.LOCAL, SessionMode.WRITES,
                    Optional.empty()), "profiler",
                    () -> resolution instanceof WorkflowResolution.Selected ? Optional.of(SOURCE) : Optional.empty());
        }

        MaterializedWorkflow materialized(WorkflowMaterializationRequest r) {
            var lease = new WorkflowResourceLease(() -> {
                released.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            return new MaterializedWorkflow(r.invocationId(), r.generation(), "digest", r.destination(), target,
                    r.mode(), List.of(), lease);
        }

        /** Completes materialization {@code index} successfully (its request is the fake's, so any generation). */
        void complete(int index, String seed, long generation) {
            var req = new WorkflowMaterializationRequest("inv" + index, generation, selected(seed),
                    Destination.LOCAL, SessionMode.WRITES, Optional.empty());
            materializations.get(index).complete(new WorkflowMaterializer.Success(materialized(req)));
        }

        /** Runs queued UI work on the EDT until {@code condition} holds. */
        void drainUntil(BooleanSupplier condition) throws InterruptedException {
            for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
                List<Runnable> batch;
                synchronized (uiQueue) {
                    batch = new ArrayList<>(uiQueue);
                    uiQueue.clear();
                }
                batch.forEach(Runnable::run);
                if (!condition.getAsBoolean()) {
                    Thread.sleep(10);
                }
            }
            assertTrue(condition.getAsBoolean());
        }

        boolean materializationStarted(int count) {
            synchronized (materializations) {
                return materializations.size() >= count;
            }
        }
    }

    private void withDialog(Wiring wiring, NewTaskPreset preset, NewTaskPlan.Inputs inputs, DialogBody body) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog;
            try {
                dialog = new NewTaskDialog(getProject(), inputs, Optional.empty(), preset,
                        Optional.of(wiring.workflow()));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            try {
                body.run(dialog);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                Disposer.dispose(dialog.getDisposable());
                wiring.background.shutdownNow();
            }
        });
    }

    private interface DialogBody {
        void run(NewTaskDialog dialog) throws InterruptedException;
    }

    public void testBlankAndNativeCommandBypassShowTheirNoticeValidateNothingThenProseResolvesNormally() {
        Wiring w = new Wiring(WorkflowTarget.FREE_FORM_PRE_PROMPT);
        withDialog(w, NewTaskPreset.empty(), inputs(), dialog -> {
            assertEquals("Pre-prompt not applied: blank chat", dialog.sourceLabelText());
            assertTrue(dialog.diagStartEnabled());
            dialog.diagSetInstructions("/review");
            assertEquals("Pre-prompt not applied: harness command", dialog.sourceLabelText());
            assertTrue(dialog.diagStartEnabled());
            assertEquals(0, w.resolves.get());
            assertTrue(dialog.acceptWorkflowForStart());
            assertTrue(dialog.acceptedWorkflow().isEmpty());
            assertEquals("/review", dialog.choice().instructions().orElseThrow());

            dialog.diagSetInstructions("fix the parser");
            assertFalse(dialog.diagStartEnabled());
            w.drainUntil(() -> w.materializationStarted(1));
            w.complete(0, "project text", 2);
            w.drainUntil(dialog::diagStartEnabled);
            assertEquals("Project profiler: inline text", dialog.sourceLabelText());
            assertEquals(1, w.resolves.get());
            assertEquals("fix the parser", dialog.choice().instructions().orElseThrow());
            assertTrue(dialog.acceptWorkflowForStart());
            assertFalse(dialog.acceptedWorkflow().orElseThrow().edited());
        });
    }

    public void testLatePreparationCannotOverwriteEditsAndTheAcceptedPromptIsTheEdit() {
        Wiring w = new Wiring(WorkflowTarget.ISSUE_TRIAGE);
        OpenIssue issue = new OpenIssue(42, "Fix", Optional.empty(), List.of(), List.of(), Optional.empty());
        withDialog(w, NewTaskPreset.triage(issue), inputs(), dialog -> {
            w.drainUntil(() -> w.materializationStarted(1));
            dialog.diagSetInstructions("my own methodology");
            assertEquals("Edited for this invocation; based on Project profiler: inline text",
                    dialog.sourceLabelText());
            w.complete(0, "late project seed", 2);
            w.drainUntil(dialog::diagStartEnabled);
            // The choice composes the ticket identity back in; the workflow's
            // accepted text stays the methodology alone.
            assertEquals("Triage GitHub issue #42: Fix\n\nmy own methodology",
                    dialog.choice().instructions().orElseThrow());
            assertEquals("Edited for this invocation; based on Project profiler: inline text",
                    dialog.sourceLabelText());
            assertTrue(dialog.acceptWorkflowForStart());
            var accepted = dialog.acceptedWorkflow().orElseThrow();
            assertEquals("my own methodology", accepted.text());
            assertTrue(accepted.edited());
        });
    }

    public void testProjectWorkflowSeedReplacesTheMethodologyButNeverTheTicketIdentity() {
        Wiring w = new Wiring(WorkflowTarget.ISSUE_TRIAGE);
        // The seed the dialog applies is the resolver's prompt text; the
        // materialization only stages the bundle.
        w.resolution = new WorkflowResolution.Selected(w.selected("PROJECT SKILL PROMPT"));
        withDialog(w, triage(), inputs(), dialog -> {
            w.drainUntil(() -> w.materializationStarted(1));
            w.complete(0, "unused", 2);
            w.drainUntil(dialog::diagStartEnabled);
            // The seed replaced the built-in steps in the editor; the choice
            // still composes the ticket identity in front of it.
            assertEquals("Triage GitHub issue #42: Fix\n\nPROJECT SKILL PROMPT",
                    dialog.choice().instructions().orElseThrow());
            assertTrue(dialog.acceptWorkflowForStart());
            assertEquals("PROJECT SKILL PROMPT", dialog.acceptedWorkflow().orElseThrow().text());
        });
    }

    // ---- Pass 2: invalid source, cancel, harness switches, dispose ----

    private NewTaskPreset triage() {
        return NewTaskPreset.triage(new OpenIssue(42, "Fix", Optional.empty(), List.of(), List.of(), Optional.empty()));
    }

    private static final WorkflowResolution INVALID = new WorkflowResolution.Invalid(
            new WorkflowResolution.WorkflowProblem("issue_triage", Optional.empty(),
                    WorkflowResolution.ProblemCategory.SCHEMA, "bad schema"));

    public void testInvalidSourceOffersTheDefaultForThisInvocationOnlyOrCancel() {
        Wiring w = new Wiring(WorkflowTarget.ISSUE_TRIAGE);
        w.resolution = INVALID;
        withDialog(w, triage(), inputs(), dialog -> {
            JButton primary = control(dialog, "primaryAction", JButton.class);
            w.drainUntil(primary::isVisible);
            assertEquals("Use Drydock default for this invocation", primary.getText());
            assertTrue(control(dialog, "workflowNote", JBLabel.class).getText().contains("bad schema"));
            assertFalse(dialog.diagStartEnabled());
            assertTrue(control(dialog, "secondaryAction", JButton.class).isVisible());
            primary.doClick();
            assertTrue(dialog.diagStartEnabled());
            assertFalse(primary.isVisible());
            assertEquals("Drydock default (this time only)", dialog.sourceLabelText());
            assertTrue(dialog.acceptWorkflowForStart());
            assertEquals("default seed", dialog.acceptedWorkflow().orElseThrow().text());
            assertFalse(dialog.acceptedWorkflow().orElseThrow().edited());
        });
        Wiring c = new Wiring(WorkflowTarget.ISSUE_TRIAGE);
        c.resolution = INVALID;
        withDialog(c, triage(), inputs(), dialog -> {
            JButton cancel = control(dialog, "secondaryAction", JButton.class);
            c.drainUntil(cancel::isVisible);
            cancel.doClick();
            assertTrue(c.controller.accept().isEmpty());
            assertFalse(dialog.acceptWorkflowForStart());
        });
    }

    private static JComboBox<?> combo(ModelField field) {
        try {
            var f = ModelField.class.getDeclaredField("combo");
            f.setAccessible(true);
            return (JComboBox<?>) f.get(field);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public void testModelTiersAndEditsSurviveHarnessSwitchesWithStaleCompletionsOutOfOrder() {
        Wiring w = new Wiring(WorkflowTarget.ISSUE_TRIAGE);
        ModelTiers remembered = new ModelTiers(Optional.of("strong-m"), Optional.of("mid-m"), Optional.of("low-m"));
        NewTaskPlan.Inputs two = inputs(Optional.empty(), List.of(agent(AgentKind.PI), agent(AgentKind.CODEX)),
                kind -> remembered, kind -> true);
        withDialog(w, triage(), two, dialog -> {
            w.drainUntil(() -> w.materializationStarted(1));
            dialog.diagSetInstructions("my own methodology");
            JComboBox<?> combo = combo(control(dialog, "modelField", ModelField.class));
            ((JTextComponent) combo.getEditor().getEditorComponent()).setText("my-model");
            agentButton(dialog, AgentKind.CODEX).doClick();
            w.drainUntil(() -> w.materializationStarted(2));
            // The newer preparation completes first; the superseded one arrives after it and is released.
            w.complete(1, "codex seed", 3);
            w.drainUntil(dialog::diagStartEnabled);
            w.complete(0, "stale seed", 2);
            w.drainUntil(() -> w.released.get() == 1);
            assertEquals("Triage GitHub issue #42: Fix\n\nmy own methodology",
                    dialog.choice().instructions().orElseThrow());
            assertEquals(Optional.of(remembered), dialog.choice().tiers());
            agentButton(dialog, AgentKind.PI).doClick();
            assertEquals(Optional.of("my-model"), dialog.choice().model());
            assertEquals(Optional.of(remembered), dialog.choice().tiers());
            assertEquals("Triage GitHub issue #42: Fix\n\nmy own methodology",
                    dialog.choice().instructions().orElseThrow());
            assertTrue(dialog.sourceLabelText().startsWith("Edited for this invocation"));
        });
    }

    public void testDisposeReleasesAnUnacceptedPreparationButNotAHandedOffOne() {
        Wiring w = new Wiring(WorkflowTarget.ISSUE_TRIAGE);
        withDialog(w, triage(), inputs(), dialog -> {
            w.drainUntil(() -> w.materializationStarted(1));
            w.complete(0, "seed", 2);
            w.drainUntil(dialog::diagStartEnabled);
            Disposer.dispose(dialog.getDisposable());
            assertEquals(1, w.released.get());
        });
        Wiring h = new Wiring(WorkflowTarget.ISSUE_TRIAGE);
        withDialog(h, triage(), inputs(), dialog -> {
            h.drainUntil(() -> h.materializationStarted(1));
            h.complete(0, "seed", 2);
            h.drainUntil(dialog::diagStartEnabled);
            assertTrue(dialog.acceptWorkflowForStart());
            Disposer.dispose(dialog.getDisposable());
            assertEquals(0, h.released.get());
            h.controller.launchFailed();
            assertEquals(1, h.released.get());
        });
    }
}
