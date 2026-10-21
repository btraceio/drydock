package app.drydock.intellij.tasks;

import app.drydock.agent.api.Agent;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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

    public void testSummaryStartsReadOnlyInTheMainCheckout() {
        OpenIssue issue = new OpenIssue(42, "Fix allocation accounting", Optional.empty(), List.of(), List.of(),
                Optional.empty());
        NewTaskPreset preset = NewTaskPreset.summarize(issue);
        AtomicReference<NewTaskPlan.Choice> choice = new AtomicReference<>();

        ApplicationManager.getApplication().invokeAndWait(() -> {
            NewTaskDialog dialog = new NewTaskDialog(getProject(), inputs(), Optional.empty(), preset);
            try {
                choice.set(dialog.choice());
            } finally {
                Disposer.dispose(dialog.getDisposable());
            }
        });

        assertEquals(SessionMode.READ_ONLY, choice.get().mode());
        assertEquals(NewTaskPlan.WorkIn.MAIN_CHECKOUT, choice.get().workIn());
        assertEquals(preset.instructions(), choice.get().instructions());
    }

    private static NewTaskPlan.Inputs inputs() {
        Path root = Path.of("/tmp/profiler");
        SharedProject project = SharedProject.local(RepositoryId.newId(), root, "profiler", Instant.EPOCH);
        Agent pi = new Agent() {
            @Override public AgentKind kind() { return AgentKind.PI; }
            @Override public String displayName() { return "Pi"; }
            @Override public boolean isAvailable() { return true; }
            @Override public String describeSearched() { return "test"; }
        };
        return new NewTaskPlan.Inputs(project, "profiler", Optional.empty(), List.of(pi), AgentKind.PI,
                "origin/main", List.of("origin/main"), List.of("origin"), Set.of(), Path.of("/tmp"), false);
    }
}
