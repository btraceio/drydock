package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.Escalation;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BabysitEscalations}: which escalations are due, claim-once across replays, and the escaped text. */
class BabysitEscalationsTest {

    private static final Instant T = Instant.parse("2026-11-18T10:00:00Z");

    private static ManagedAgentSession session(String name) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/" + name.replace(' ', '-'))), T).build();
    }

    private static Escalation escalation(BabysitPairing pairing, String message, boolean resolved) {
        return new Escalation(UUID.randomUUID(), pairing.watchedId(), pairing.babysitterId(), pairing.prNumber(),
                message, T, resolved);
    }

    private static ApplicationState state(List<ManagedAgentSession> sessions, List<BabysitPairing> pairings,
                                          List<Escalation> escalations) {
        return ApplicationState.empty().withSessions(sessions).withPairings(pairings).withEscalations(escalations);
    }

    @Test
    void theLatestUnresolvedEscalationOfEachActiveEngagementIsDueOnce() {
        ManagedAgentSession watched = session("Fix <the> parser");
        ManagedAgentSession babysitter = session("babysit PR #12");
        ManagedAgentSession gone = session("Gone");
        BabysitPairing active = new BabysitPairing(babysitter.id(), watched.id(), watched.repositoryId(), 12,
                BabysitState.ESCALATED);
        BabysitPairing stopped = new BabysitPairing(ManagedSessionId.newId(), watched.id(), watched.repositoryId(),
                11, BabysitState.STOPPED);
        BabysitPairing orphan = new BabysitPairing(ManagedSessionId.newId(), gone.id(), gone.repositoryId(), 3,
                BabysitState.ESCALATED);
        Escalation first = escalation(active, "first", false);
        Escalation latest = escalation(active, "latest", false);
        Escalation resolved = escalation(active, "resolved", true);
        List<Escalation> escalations = List.of(first, latest, resolved, escalation(stopped, "history", false),
                escalation(orphan, "no row", false));
        ApplicationState state = state(List.of(watched, babysitter), List.of(stopped, active, orphan), escalations);

        List<BabysitEscalations.Due> due = BabysitEscalations.due(state, Set.of());
        assertEquals(List.of(new BabysitEscalations.Due(latest, "Fix <the> parser",
                List.of(first.id(), latest.id()))), due, "one balloon per engagement, covering the burst");
        assertEquals(List.of(), BabysitEscalations.due(state, Set.of(first.id(), latest.id())));

        List<BabysitEscalations.Due> claimed = BabysitEscalations.claim(state);
        assertEquals(due, claimed);
        assertEquals(List.of(), BabysitEscalations.claim(state), "a replayed event raises nothing again");

        Escalation next = escalation(active, "next", false);
        List<Escalation> more = new ArrayList<>(escalations);
        more.add(next);
        assertEquals(List.of(new BabysitEscalations.Due(next, "Fix <the> parser", List.of(next.id()))),
                BabysitEscalations.claim(state(List.of(watched, babysitter), List.of(stopped, active, orphan),
                        more)), "a new escalation is raised by itself");
    }

    @Test
    void theBalloonTextIsEscapedAndShort() {
        ManagedAgentSession watched = session("Fix <the> parser");
        BabysitPairing pairing = new BabysitPairing(ManagedSessionId.newId(), watched.id(), watched.repositoryId(),
                12, BabysitState.ESCALATED);
        Escalation raised = escalation(pairing, "Rebase <b>needs</b> a design call & more\nsecond line", false);
        BabysitEscalations.Due due = new BabysitEscalations.Due(raised, watched.displayName(),
                List.of(UUID.randomUUID(), raised.id()));

        assertEquals("PR #12 needs you: Rebase &lt;b&gt;needs&lt;/b&gt; a design call &amp; more",
                BabysitEscalations.title(due));
        assertEquals("The babysitter of Fix &lt;the&gt; parser escalated: Rebase &lt;b&gt;needs&lt;/b&gt; a design"
                + " call &amp; more (1 earlier, also unresolved)", BabysitEscalations.content(due));
        String longMessage = "x".repeat(200);
        String title = BabysitEscalations.title(new BabysitEscalations.Due(escalation(pairing, longMessage, false),
                "n", List.of()));
        assertTrue(title.endsWith("x…") && title.length() == "PR #12 needs you: ".length()
                + BabysitEscalations.TITLE_SUMMARY_LIMIT + 1, title);
    }
}
