package app.drydock.ui;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a session actually receives when a human hands findings to it: the
 * exact prompt both asks route through {@code FindingsPrompt}, the refusal
 * of an empty hand-off, and the SENT marking that follows -- and only
 * follows -- a hand-off that did not refuse. All through the static seams
 * on {@link MainWorkspace}, so nothing here needs a JavaFX workspace.
 */
class MainWorkspaceFindingsPromptTest {

    private static final Instant AT = Instant.parse("2026-10-03T10:00:00Z");

    private static ReviewScope scope() {
        return new ReviewScope("rs_1", ReviewScope.Kind.WORKTREE, Path.of("/repo"),
                Optional.of(Path.of("/wt")), "master", "feat",
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ReviewAnnotation finding(String id, String file, String startKey, String title) {
        return new ReviewAnnotation("rs_1", id, file, startKey, startKey,
                Severity.BLOCKING, Confidence.HIGH, Optional.of(title), "Claude", AT,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), true,
                Triage.CONFIRMED, Optional.empty());
    }

    /**
     * The delivery seam: records every prompt handed to it, and asserts
     * nothing was marked SENT while the prompt was still in flight -- the
     * marker must never claim a prompt that was not handed over.
     */
    private static final class RecordingDeliver implements BiFunction<ReviewScope, String, MainWorkspace.Handoff> {
        private final MainWorkspace.Handoff outcome;
        private final List<String> prompts = new ArrayList<>();
        private final List<String> sentIds;

        RecordingDeliver(MainWorkspace.Handoff outcome, List<String> sentIds) {
            this.outcome = outcome;
            this.sentIds = sentIds;
        }

        @Override
        public MainWorkspace.Handoff apply(ReviewScope scope, String prompt) {
            assertTrue(sentIds.isEmpty(),
                    "no finding may be marked SENT before the hand-off: " + sentIds);
            prompts.add(prompt);
            return outcome;
        }
    }

    @Test
    void theFixAskHandsTheSharedBuildersPromptToTheSession() {
        List<String> sentIds = new ArrayList<>();
        RecordingDeliver deliver = new RecordingDeliver(MainWorkspace.Handoff.DELIVERED, sentIds);

        boolean handed = MainWorkspace.askToFix(scope(), "Add the review card",
                List.of(finding("f_1", "src/Main.java", "n10", "Drops the close listener")),
                deliver, finding -> sentIds.add(finding.id()));

        assertTrue(handed);
        assertEquals(List.of("Address these review findings on \"Add the review card\", "
                + "then summarize what you changed: "
                + "[1] src/Main.java n10: Drops the close listener."), deliver.prompts);
        assertEquals(List.of("f_1"), sentIds);
    }

    @Test
    void theSendBackKeepsItsOwnDistinctHeading() {
        List<String> sentIds = new ArrayList<>();
        RecordingDeliver deliver = new RecordingDeliver(MainWorkspace.Handoff.HELD, sentIds);

        boolean handed = MainWorkspace.sendFindingsBack(scope(),
                List.of(finding("f_1", "src/A.java", "n1", "First"),
                        finding("f_2", "src/B.java", "o2", "Second")),
                deliver, finding -> sentIds.add(finding.id()));

        assertTrue(handed);
        assertEquals(List.of("The reviewer sent these blocking findings back. "
                + "Address them, then summarize what you changed: "
                + "[1] src/A.java n1: First. [2] src/B.java o2: Second."), deliver.prompts);
        assertEquals(List.of("f_1", "f_2"), sentIds);
    }

    @Test
    void anEmptyHandOffIsRefusedBeforeAnythingIsBuiltOrSent() {
        List<String> sentIds = new ArrayList<>();
        RecordingDeliver deliver = new RecordingDeliver(MainWorkspace.Handoff.DELIVERED, sentIds);
        List<Boolean> built = new ArrayList<>();
        Supplier<String> promptFor = () -> {
            built.add(true);
            return "built";
        };

        boolean direct = MainWorkspace.handFindings(scope(), promptFor, List.of(), deliver,
                finding -> sentIds.add(finding.id()));
        boolean fix = MainWorkspace.askToFix(scope(), "Subject", List.of(), deliver,
                finding -> sentIds.add(finding.id()));
        boolean back = MainWorkspace.sendFindingsBack(scope(), List.of(), deliver,
                finding -> sentIds.add(finding.id()));

        assertFalse(direct);
        assertFalse(fix);
        assertFalse(back);
        assertTrue(deliver.prompts.isEmpty(), "nothing may reach a session for an empty hand-off");
        assertTrue(sentIds.isEmpty(), "an empty hand-off may mark nothing SENT");
        assertTrue(built.isEmpty(), "the empty refusal comes before the prompt is even built");
    }

    @Test
    void heldAndDeliveredBothMarkSentButARefusalMarksNothing() {
        List<ReviewAnnotation> findings = List.of(
                finding("f_1", "src/A.java", "n1", "First"),
                finding("f_2", "src/B.java", "n2", "Second"));

        for (MainWorkspace.Handoff outcome : MainWorkspace.Handoff.values()) {
            List<String> sentIds = new ArrayList<>();
            RecordingDeliver deliver = new RecordingDeliver(outcome, sentIds);
            boolean handed = MainWorkspace.askToFix(scope(), "Subject", findings,
                    deliver, finding -> sentIds.add(finding.id()));

            if (outcome == MainWorkspace.Handoff.REFUSED) {
                assertFalse(handed, "only a refused hand-off returns false: " + outcome);
                assertTrue(sentIds.isEmpty(), "a refused hand-off may mark nothing SENT");
            } else {
                assertTrue(handed, "HELD and DELIVERED both count as handed over: " + outcome);
                assertEquals(List.of("f_1", "f_2"), sentIds,
                        "SENT marking follows the findings in order: " + outcome);
            }
        }
    }
}
