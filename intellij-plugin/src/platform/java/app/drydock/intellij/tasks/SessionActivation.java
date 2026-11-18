package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Explicit activation of a session (Enter, double-click), keyed by the stable
 * {@link ManagedSessionId} and carrying the intent the user saw when acting.
 * A running session is shown ({@link TaskNavigator#switchTo}); a stopped one
 * is started or resumed ({@code AgentConsoles.resumeOrStart}, guards
 * unchanged). The id is re-resolved at invocation, never the clicked row:
 * a Show captured while the agent ran, whose agent ended meanwhile, is
 * reported and never becomes a Start or Resume; a deleted id fails honestly.
 */
public final class SessionActivation {

    private static final Logger LOG = System.getLogger(SessionActivation.class.getName());

    /** What activating does, as the user saw it when they acted. */
    public enum Intent {
        /** The agent runs: bring its console to the front. */
        SHOW,
        /** Nothing runs: start a fresh agent or resume the conversation. */
        START_OR_RESUME
    }

    /** What an activation did. */
    public enum Outcome {
        SHOWN, STARTED_OR_RESUMED,
        /** A captured Show whose agent ended meanwhile: nothing was started. */
        ENDED,
        /** The id is no longer in the catalog. */
        DELETED
    }

    /** The label of an activation; {@code Resume}/{@code Start} only once probed. */
    public enum Label {
        SHOW("Show"), RESUME("Resume"), START("Start"), START_OR_RESUME("Start or Resume"), GONE("Deleted");

        private final String text;

        Label(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public record Seams(Executor background, Executor edt,
                        Supplier<List<ManagedAgentSession>> catalog, Predicate<ManagedSessionId> running,
                        Predicate<ManagedAgentSession> hasConversation,
                        Consumer<ManagedAgentSession> show, BiConsumer<Project, ManagedAgentSession> start,
                        BiConsumer<Project, String> inform) {
    }

    private final Seams seams;

    public SessionActivation(Seams seams) {
        this.seams = seams;
    }

    /** The production wiring: plugin executor, state store catalog, execution registry. */
    public static SessionActivation production() {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        return new SessionActivation(new Seams(plugin.executor(),
                task -> ApplicationManager.getApplication().invokeLater(task, ModalityState.nonModal()),
                () -> plugin.stateStore().state().sessions(),
                id -> AgentExecutionRegistry.getInstance().isRunning(id),
                AgentConsoles::hasConversation, TaskNavigator::switchTo, AgentConsoles::resumeOrStart,
                (project, message) -> Messages.showInfoMessage(project, message, "Drydock")));
    }

    /** The intent for the row as it is now; empty when the id is not in the catalog. Cheap: no probe. */
    public Optional<Intent> intentOf(ManagedSessionId id) {
        return find(id).map(session -> seams.running().test(id) ? Intent.SHOW : Intent.START_OR_RESUME);
    }

    /** Pure: the label of an intent, refined by a probe answer for a stopped session. */
    public static Label labelOf(Intent intent, Optional<Boolean> resumable) {
        if (intent == Intent.SHOW) {
            return Label.SHOW;
        }
        return resumable.map(yes -> yes ? Label.RESUME : Label.START).orElse(Label.START_OR_RESUME);
    }

    /**
     * Activates {@code id} on the EDT with the {@code captured} intent. The
     * id is re-resolved now; see the class comment for the two refusals.
     */
    public Outcome activate(Project project, ManagedSessionId id, Intent captured) {
        Optional<ManagedAgentSession> session = find(id);
        if (session.isEmpty()) {
            seams.inform().accept(project, "This session no longer exists.");
            return Outcome.DELETED;
        }
        if (seams.running().test(id)) {
            seams.show().accept(session.get());
            return Outcome.SHOWN;
        }
        if (captured == Intent.SHOW) {
            seams.inform().accept(project, "\"" + session.get().displayName() + "\" has ended. "
                    + "Use Resume to start it again.");
            return Outcome.ENDED;
        }
        seams.start().accept(project, session.get());
        return Outcome.STARTED_OR_RESUMED;
    }

    /**
     * The label for {@code id}, resolved off the EDT because the resumability
     * probe blocks. {@code busy(true)} is called at once, on the caller's
     * thread, and {@code busy(false)} on the EDT before {@code done}, on
     * every path: running, deleted, a probe that throws, and a rejected
     * executor.
     */
    public void describe(ManagedSessionId id, Consumer<Boolean> busy, Consumer<Label> done) {
        busy.accept(true);
        try {
            seams.background().execute(() -> {
                Label label = Label.START_OR_RESUME;
                try {
                    label = resolveLabel(id);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, () -> "Could not probe session " + id + ": " + e);
                }
                finish(busy, done, label);
            });
        } catch (RejectedExecutionException e) {
            finish(busy, done, Label.START_OR_RESUME);
        }
    }

    private Label resolveLabel(ManagedSessionId id) {
        Optional<ManagedAgentSession> session = find(id);
        if (session.isEmpty()) {
            return Label.GONE;
        }
        if (seams.running().test(id)) {
            return Label.SHOW;
        }
        return labelOf(Intent.START_OR_RESUME, Optional.of(seams.hasConversation().test(session.get())));
    }

    private void finish(Consumer<Boolean> busy, Consumer<Label> done, Label label) {
        try {
            seams.edt().execute(() -> {
                try {
                    busy.accept(false);
                } finally {
                    done.accept(label);
                }
            });
        } catch (RuntimeException e) {
            busy.accept(false);
        }
    }

    private Optional<ManagedAgentSession> find(ManagedSessionId id) {
        return seams.catalog().get().stream().filter(session -> session.id().equals(id)).findFirst();
    }
}
