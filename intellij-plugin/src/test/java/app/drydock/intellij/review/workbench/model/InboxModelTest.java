package app.drydock.intellij.review.workbench.model;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link InboxModel}: the spec §7 tab rules, ordering, scope, counts, the challenged badge and auto-advance. */
class InboxModelTest {

    @Test
    void theTabsReadTriageStatusAndTheGitHubLink() {
        assertEquals(InboxModel.Tab.TO_TRIAGE, InboxModel.tabOf(finding("a", "A.java", "n1", Triage.PROPOSED)));
        assertEquals(InboxModel.Tab.OUTBOX, InboxModel.tabOf(finding("a", "A.java", "n1", Triage.CONFIRMED)));
        assertEquals(InboxModel.Tab.DONE, InboxModel.tabOf(finding("a", "A.java", "n1", Triage.DISMISSED)));
        for (AnnotationStatus past : List.of(AnnotationStatus.SENT, AnnotationStatus.ADDRESSED,
                AnnotationStatus.RESOLVED, AnnotationStatus.FIXED)) {
            assertEquals(InboxModel.Tab.DONE,
                    InboxModel.tabOf(finding("a", "A.java", "n1", Triage.CONFIRMED).withStatus(past)), past.name());
        }
        assertEquals(InboxModel.Tab.DONE, InboxModel.tabOf(finding("a", "A.java", "n1", Triage.CONFIRMED)
                .withGithub(new ReviewAnnotation.GitHubComment(7, "https://x/7", false))),
                "posted to the PR");
    }

    @Test
    void aTabListsItsCardsInReviewOrderAndCountsEveryTab() {
        List<ReviewAnnotation> findings = List.of(
                finding("b2", "B.java", "n20", Triage.PROPOSED),
                finding("a9", "A.java", "n9", Triage.PROPOSED),
                finding("a3", "A.java", "n3", Triage.PROPOSED),
                finding("c1", "C.java", "n1", Triage.CONFIRMED),
                finding("d1", "D.java", "n1", Triage.DISMISSED));

        InboxModel.Snapshot inbox = InboxModel.snapshot(findings, InboxModel.Tab.TO_TRIAGE,
                InboxModel.Scope.ALL, Optional.empty());

        assertEquals(List.of("a3", "a9", "b2"), ids(inbox), "file, then line");
        assertEquals(3, inbox.count(InboxModel.Tab.TO_TRIAGE));
        assertEquals(1, inbox.count(InboxModel.Tab.OUTBOX));
        assertEquals(1, inbox.count(InboxModel.Tab.DONE));
    }

    @Test
    void theFileScopeListsAndCountsOneFileAndIsTheWholeReviewWithoutASelection() {
        List<ReviewAnnotation> findings = List.of(
                finding("a1", "A.java", "n1", Triage.PROPOSED),
                finding("b1", "B.java", "n1", Triage.PROPOSED),
                finding("b2", "B.java", "n2", Triage.CONFIRMED));

        InboxModel.Snapshot file = InboxModel.snapshot(findings, InboxModel.Tab.TO_TRIAGE,
                InboxModel.Scope.FILE, Optional.of("B.java"));
        assertEquals(List.of("b1"), ids(file));
        assertEquals(1, file.count(InboxModel.Tab.OUTBOX));
        assertEquals(Optional.of("B.java"), file.file());

        InboxModel.Snapshot noSelection = InboxModel.snapshot(findings, InboxModel.Tab.TO_TRIAGE,
                InboxModel.Scope.FILE, Optional.empty());
        assertEquals(List.of("a1", "b1"), ids(noSelection));
    }

    @Test
    void aFindingIsChallengedWhileTheReviewersQuestionIsTheLastWord() {
        ReviewAnnotation asked = finding("a", "A.java", "n1", Triage.PROPOSED)
                .withReply(new ReviewAnnotation.Message("You", Instant.EPOCH, "Why?"));
        ReviewAnnotation answered = asked.withReply(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "Because."));

        assertTrue(InboxModel.challenged(asked));
        assertFalse(InboxModel.challenged(answered));
        assertFalse(InboxModel.challenged(finding("a", "A.java", "n1", Triage.PROPOSED)), "no reply yet");
        assertFalse(InboxModel.challenged(asked.withTriage(Triage.CONFIRMED)), "only cards awaiting triage");
    }

    @Test
    void afterADecisionTheNextCardIsTheOneThatFollowedElseTheOneBefore() {
        assertEquals(Optional.of("c"), InboxModel.nextAfter(List.of("a", "b", "c"), "b", List.of("a", "c")));
        assertEquals(Optional.of("b"), InboxModel.nextAfter(List.of("a", "b", "c"), "c", List.of("a", "b")));
        assertEquals(Optional.empty(), InboxModel.nextAfter(List.of("a"), "a", List.of()));
        assertEquals(Optional.of("x"), InboxModel.nextAfter(List.of("a"), "q", List.of("x")), "unknown id: the first");
    }

    @Test
    void theEmptyInboxCountsZeroEverywhere() {
        InboxModel.Snapshot empty = InboxModel.Snapshot.empty(InboxModel.Tab.OUTBOX, InboxModel.Scope.ALL);
        for (InboxModel.Tab tab : InboxModel.Tab.values()) {
            assertEquals(0, empty.count(tab));
        }
        assertTrue(empty.cards().isEmpty());
    }

    private static List<String> ids(InboxModel.Snapshot inbox) {
        return inbox.cards().stream().map(card -> card.finding().id()).toList();
    }

    private static ReviewAnnotation finding(String id, String file, String key, Triage triage) {
        return new ReviewAnnotation("s1", id, file, key, key, Severity.BLOCKING, Confidence.HIGH,
                Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body")), Optional.empty(),
                AnnotationStatus.OPEN, Optional.empty(), false, triage, Optional.empty());
    }
}
