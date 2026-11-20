package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.tasks.SessionActivation.Intent;
import app.drydock.intellij.tasks.SessionActivation.Label;
import app.drydock.intellij.tasks.SessionActivation.Outcome;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionActivationTest {

    private static ManagedAgentSession session(ManagedSessionId id, String name) {
        AgentBinding binding = new AgentBinding(AgentKind.PI, Optional.of("conv"), Optional.empty(),
                SessionMode.WRITES);
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), name, binding,
                SessionWorkspace.inRepository(Path.of("/wt/x")), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build();
    }

    /** Mutable world plus a log of what was done; executors run inline unless queued. */
    private static final class World {
        final List<ManagedAgentSession> catalog = new ArrayList<>();
        final Set<ManagedSessionId> running = new HashSet<>();
        final List<String> log = new ArrayList<>();
        Predicate<ManagedAgentSession> probe = s -> true;
        Executor background = Runnable::run;

        SessionActivation activation() {
            return new SessionActivation(new SessionActivation.Seams(task -> background.execute(task), Runnable::run,
                    () -> List.copyOf(catalog), running::contains, s -> probe.test(s),
                    (project, s) -> log.add("show " + s.displayName()),
                    (project, s) -> log.add("start " + s.displayName()),
                    (project, message) -> log.add("inform " + message)));
        }
    }

    @Test
    void aRunningSessionIsShownNeverStarted() {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();
        world.catalog.add(session(id, "A"));
        world.running.add(id);

        assertEquals(Optional.of(Intent.SHOW), world.activation().intentOf(id));
        assertEquals(Outcome.SHOWN, world.activation().activate(null, id, Intent.SHOW));
        assertEquals(List.of("show A"), world.log);
    }

    @Test
    void aStoppedSessionIsStartedOrResumed() {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();
        world.catalog.add(session(id, "A"));

        assertEquals(Optional.of(Intent.START_OR_RESUME), world.activation().intentOf(id));
        assertEquals(Outcome.STARTED_OR_RESUMED, world.activation().activate(null, id, Intent.START_OR_RESUME));
        assertEquals(List.of("start A"), world.log);
    }

    @Test
    void aShowWhoseAgentEndedWhileResolvingDoesNotBecomeAStart() {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();
        world.catalog.add(session(id, "A"));
        // Captured as a Show while the agent ran; it has since ended.
        assertEquals(Outcome.ENDED, world.activation().activate(null, id, Intent.SHOW));
        assertTrue(world.log.size() == 1 && world.log.get(0).startsWith("inform \"A\" has ended"), world.log.toString());
    }

    @Test
    void aCapturedStartThatFoundTheAgentRunningShowsInstead() {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();
        world.catalog.add(session(id, "A"));
        world.running.add(id);

        assertEquals(Outcome.SHOWN, world.activation().activate(null, id, Intent.START_OR_RESUME));
        assertEquals(List.of("show A"), world.log);
    }

    @Test
    void aDeletedIdFailsHonestly() {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();

        assertEquals(Optional.empty(), world.activation().intentOf(id));
        assertEquals(Outcome.DELETED, world.activation().activate(null, id, Intent.START_OR_RESUME));
        assertEquals(List.of("inform This session no longer exists."), world.log);
    }

    @Test
    void regroupedRowsActOnTheCurrentSessionOfTheSameId() {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();
        world.catalog.add(session(ManagedSessionId.newId(), "other"));
        world.catalog.add(session(id, "old name"));
        SessionActivation activation = world.activation();
        world.catalog.clear(); // regrouped and renamed after the click captured it
        world.catalog.add(session(id, "new name"));
        world.catalog.add(session(ManagedSessionId.newId(), "other"));

        activation.activate(null, id, Intent.START_OR_RESUME);

        assertEquals(List.of("start new name"), world.log);
    }

    @Test
    void labelsFollowIntentAndProbe() {
        assertEquals("Show", SessionActivation.labelOf(Intent.SHOW, Optional.of(true)).text());
        assertEquals(Label.RESUME, SessionActivation.labelOf(Intent.START_OR_RESUME, Optional.of(true)));
        assertEquals(Label.START, SessionActivation.labelOf(Intent.START_OR_RESUME, Optional.of(false)));
        assertEquals("Start or Resume",
                SessionActivation.labelOf(Intent.START_OR_RESUME, Optional.empty()).text());
    }

    @Test
    void describeShowsProgressAtOnceAndClearsItBeforeTheLabel() {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();
        world.catalog.add(session(id, "A"));
        List<Runnable> queued = new ArrayList<>();
        world.background = queued::add;
        List<String> events = new ArrayList<>();

        world.activation().describe(id, busy -> events.add("busy " + busy), label -> events.add(label.name()));

        assertEquals(List.of("busy true"), events, "progress is visible before the probe runs");
        queued.get(0).run();
        assertEquals(List.of("busy true", "busy false", "RESUME"), events);
    }

    @Test
    void progressIsClearedOnEveryCompletionPath() {
        assertEquals(List.of("busy true", "busy false", "START"), describe(w -> w.probe = s -> false, true));
        assertEquals(List.of("busy true", "busy false", "START_OR_RESUME"),
                describe(w -> w.probe = s -> { throw new IllegalStateException("cli"); }, true));
        assertEquals(List.of("busy true", "busy false", "GONE"), describe(w -> { }, false));
        assertEquals(List.of("busy true", "busy false", "SHOW"), describe(w -> w.running.addAll(
                w.catalog.stream().map(ManagedAgentSession::id).toList()), true));
        assertEquals(List.of("busy true", "busy false", "START_OR_RESUME"), describe(w -> w.background = task -> {
            throw new RejectedExecutionException("shut down");
        }, true));
    }

    private static List<String> describe(Consumer<World> setup, boolean inCatalog) {
        World world = new World();
        ManagedSessionId id = ManagedSessionId.newId();
        if (inCatalog) {
            world.catalog.add(session(id, "A"));
        }
        setup.accept(world);
        List<String> events = new ArrayList<>();
        world.activation().describe(id, busy -> events.add("busy " + busy), label -> events.add(label.name()));
        return events;
    }
}
