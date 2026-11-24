package app.drydock.editorqa;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.review.PendingQuestions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INV-13/AC-1/AC-5: the editor ask feature and the review ask flow share nothing but a tool-name neighbourhood. */
class EditorAskReviewIsolationTest {

    @TempDir
    Path tmp;

    @Test
    void reviewAskStillAnswersWithNoEditorQaInvolved() {
        List<PendingQuestions.AnsweredAsk> answered = new ArrayList<>();
        PendingQuestions pending = new PendingQuestions(answered::add);
        ManagedSessionId reviewer = ManagedSessionId.newId();
        PendingQuestions.PendingAsk ask = pending.mint("rs_1", "loadConfig", reviewer);
        Optional<PendingQuestions.AnsweredAsk> got = pending.answer(ask.questionId(),
                a -> a.sessionId().equals(reviewer), "It memoizes.");
        assertTrue(got.isPresent());
        assertEquals("rs_1", got.get().ask().scopeId());
        assertEquals(0, pending.size());
        assertEquals(1, answered.size());
    }

    @Test
    void aNormalEditorAskCreatesOnlyAMarkedRowAndHistory() throws Exception {
        EditorAskCoordinatorTest f = new EditorAskCoordinatorTest();
        f.tmp = tmp;
        f.start(null);
        try {
            runNormalAsk(f);
        } finally {
            f.coordinator.close();
        }
    }

    private void runNormalAsk(EditorAskCoordinatorTest f) throws Exception {
        PendingQuestions reviewQuestions = new PendingQuestions(null);
        ManagedAgentSession ordinary = EditorAskCoordinatorTest.candidate(f.checkout("other")).toBuilder()
                .purpose(ManagedAgentSession.SessionPurpose.ORDINARY).build();
        f.catalog.update(s -> s.withSessions(List.of(ordinary)));
        Path root = f.checkout("repo");

        Result r = f.coordinator.submit(EditorAskCoordinatorTest.ask(root, "what is this?")).get(10, TimeUnit.SECONDS);

        assertTrue(r.accepted());
        assertEquals(0, reviewQuestions.size(), "no review question is minted");
        List<ManagedAgentSession> sessions = f.catalog.state().sessions();
        assertEquals(2, sessions.size());
        assertTrue(sessions.contains(ordinary), "the ordinary row is untouched, no PR link written");
        assertEquals(1, sessions.stream().filter(ManagedAgentSession::editorQa).count());
        sessions.stream().filter(ManagedAgentSession::editorQa).forEach(s ->
                assertEquals(PrState.NONE, s.pr().state()));
    }
}
