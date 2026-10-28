package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumabilityCacheTest {

    private static final Path DIR = Path.of("/wt/dd-jmc-fix");

    /** Sessions built the way {@code TaskTreeRendererTest} builds them. */
    private static ManagedAgentSession session(ManagedSessionId id, String agentSessionId) {
        AgentBinding binding = new AgentBinding(AgentKind.PI, Optional.ofNullable(agentSessionId),
                Optional.empty(), SessionMode.WRITES);
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "Fix chunk memory growth",
                binding, SessionWorkspace.inRepository(DIR), Instant.EPOCH)
                .status(SessionStatus.INACTIVE).build();
    }

    /** A counting fake probe: records how often it was asked and answers from a script. */
    private static final class CountingProbe implements Predicate<ManagedAgentSession> {
        private final boolean[] answers;
        private int asked;

        CountingProbe(boolean... answers) {
            this.answers = answers;
        }

        @Override
        public boolean test(ManagedAgentSession session) {
            boolean answer = answers[Math.min(asked, answers.length - 1)];
            asked++;
            return answer;
        }
    }

    private static final class ThrowingProbe implements Predicate<ManagedAgentSession> {
        int asked;

        @Override
        public boolean test(ManagedAgentSession session) {
            asked++;
            if (asked == 1) {
                throw new IllegalStateException("cli listing failed");
            }
            return true;
        }
    }

    @Test
    void aRemovedSessionIsPruned() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId gone = ManagedSessionId.newId();
        ManagedSessionId stays = ManagedSessionId.newId();
        cache.refresh(List.of(session(gone, "c1"), session(stays, "c2")), Set.of(),
                s -> true);
        assertEquals(2, cache.size());

        Map<ManagedSessionId, Boolean> after = cache.refresh(List.of(session(stays, "c2")), Set.of(),
                s -> true);

        assertEquals(1, cache.size(), "the session that left the catalog must be pruned");
        assertFalse(after.containsKey(gone));
        assertEquals(Boolean.TRUE, after.get(stays));
    }

    @Test
    void aRunningSessionIsDroppedAndNotProbed() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId running = ManagedSessionId.newId();
        CountingProbe probe = new CountingProbe(true);

        Map<ManagedSessionId, Boolean> result = cache.refresh(List.of(session(running, "c1")),
                Set.of(running), probe);

        assertTrue(result.isEmpty(), "a running session is neither probed nor returned");
        assertEquals(0, cache.size(), "a running session is dropped from the cache");
        assertEquals(0, probe.asked, "the probe must never run for a live session");
    }

    @Test
    void aPreviouslyCachedSessionThatStartsRunningIsDropped() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId id = ManagedSessionId.newId();
        cache.refresh(List.of(session(id, "c1")), Set.of(), s -> true);
        assertEquals(1, cache.size());

        cache.refresh(List.of(session(id, "c1")), Set.of(id), s -> true);

        assertEquals(0, cache.size(), "a session that started running is pruned on the next refresh");
    }

    @Test
    void anUnchangedIdIsNotReProbed() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId id = ManagedSessionId.newId();
        ManagedAgentSession stopped = session(id, "c1");
        CountingProbe probe = new CountingProbe(true);

        Map<ManagedSessionId, Boolean> first = cache.refresh(List.of(stopped), Set.of(), probe);
        Map<ManagedSessionId, Boolean> second = cache.refresh(List.of(stopped), Set.of(), probe);

        assertEquals(1, probe.asked, "an unchanged conversation id must not be probed twice");
        assertEquals(Boolean.TRUE, first.get(id));
        assertEquals(Boolean.TRUE, second.get(id));
    }

    @Test
    void aChangedIdIsReProbed() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId id = ManagedSessionId.newId();
        // Same managed session, relaunched: the CLI handed out a new id.
        ManagedAgentSession oldConversation = session(id, "c1");
        ManagedAgentSession newConversation = session(id, "c2");
        CountingProbe probe = new CountingProbe(true, false);

        assertEquals(Boolean.TRUE, cache.refresh(List.of(oldConversation), Set.of(), probe).get(id));
        assertEquals(Boolean.FALSE, cache.refresh(List.of(newConversation), Set.of(), probe).get(id));

        assertEquals(2, probe.asked, "a changed conversation id must be probed again");
    }

    @Test
    void aProbeExceptionLeavesItAbsentThenItIsRetried() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId id = ManagedSessionId.newId();
        ManagedAgentSession stopped = session(id, "c1");
        ThrowingProbe probe = new ThrowingProbe();

        Map<ManagedSessionId, Boolean> failed = cache.refresh(List.of(stopped), Set.of(), probe);

        assertFalse(failed.containsKey(id), "a failed probe answers nothing");
        assertEquals(0, cache.size(), "a failed probe must not leave an entry behind");

        Map<ManagedSessionId, Boolean> retried = cache.refresh(List.of(stopped), Set.of(), probe);

        assertEquals(Boolean.TRUE, retried.get(id), "the next refresh retries the failed probe");
        assertEquals(2, probe.asked);
        assertEquals(1, cache.size());
    }

    @Test
    void clearEmptiesIt() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId id = ManagedSessionId.newId();
        cache.refresh(List.of(session(id, "c1")), Set.of(), s -> true);
        assertEquals(1, cache.size());

        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test
    void sizeNeverExceedsTheStoppedCatalogCount() {
        ResumabilityCache cache = new ResumabilityCache();
        ManagedSessionId a = ManagedSessionId.newId();
        ManagedSessionId b = ManagedSessionId.newId();
        ManagedSessionId c = ManagedSessionId.newId();
        ManagedSessionId live = ManagedSessionId.newId();
        List<ManagedAgentSession> catalog = List.of(session(a, "c1"), session(b, "c2"),
                session(c, "c3"), session(live, "c4"));

        // Three stopped, one live: the cache and the answer both hold three.
        Map<ManagedSessionId, Boolean> result = cache.refresh(catalog, Set.of(live), s -> true);

        assertEquals(3, result.size());
        assertEquals(3, cache.size(), "cache holds at most the stopped sessions");

        // And it shrinks as sessions start running: with every session live it
        // must be empty even though the catalog is unchanged.
        cache.refresh(catalog, Set.of(a, b, c, live), s -> true);
        assertEquals(0, cache.size());
    }
}
