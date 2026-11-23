package app.drydock.review;

import app.drydock.domain.ModelTiers;
import app.drydock.review.tour.TourValidator;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reviewer is asked to do. Both forms are one line -- they go through
 * TerminalBridge.sendPrompt -- and both must name the handle and demand
 * review_state first, or a re-review re-flags everything already settled.
 */
class ReviewInstructionsTest {

    @Test
    void bothFormsNameTheScopeHandle() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("rs_abc123"));
        assertTrue(ReviewInstructions.forScope("rs_abc123", false).contains("rs_abc123"));
    }

    @Test
    void bothFormsAskForReviewStateFirst() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("review_state"));
        assertTrue(ReviewInstructions.forScope("rs_abc123", false).contains("review_state"));
    }

    @Test
    void bothFormsAreASingleLine() {
        assertFalse(ReviewInstructions.forScope("rs_abc123", true).contains("\n"));
        assertFalse(ReviewInstructions.forScope("rs_abc123", false).contains("\n"));
    }

    @Test
    void onlyTheCapableFormAsksForASubagent() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", true).contains("subagent"));
        assertFalse(ReviewInstructions.forScope("rs_abc123", false).contains("subagent"));
    }

    @Test
    void bothFormsAskForATourAndFindings() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("review_tour"));
            assertTrue(instruction.contains("review_finding"));
        }
    }

    @Test
    void bothFormsAskForAnAnchorNoteOnEveryAnchor() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("every anchor a one-sentence note"), instruction);
        }
    }

    @Test
    void aTourRefreshAsksForNotesToo() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);

        assertTrue(line.contains("every anchor a one-sentence note"), line);
        assertFalse(line.contains("\n"));
    }

    @Test
    void bothFormsSayWhichCheckKindToPickAndWhy() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("predict only if its question can be answered from the removed and "
                    + "surrounding code"), instruction);
            assertTrue(instruction.contains("hides a step's added lines until a predict is answered"), instruction);
            assertTrue(instruction.contains("ask about the added lines themselves with a trace check"), instruction);
            assertFalse(instruction.contains("\n"));
        }
    }

    @Test
    void aTourRefreshSaysWhichCheckKindToPickToo() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);

        assertTrue(line.contains("ask about the added lines themselves with a trace check"), line);
        assertFalse(line.contains("\n"));
    }

    /**
     * A question the reviewer cannot answer from the tour or the visible
     * code tests their background, not the change (hex opcodes, SP handling
     * on some architecture) -- so both forms forbid it.
     */
    @Test
    void bothFormsForbidQuestionsAssumingKnowledgeTheTourDidNotTeach() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("never from outside knowledge the tour has not taught"), instruction);
        }
        String refresh = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);
        assertTrue(refresh.contains("never from outside knowledge the tour has not taught"), refresh);
    }

    /**
     * Name-match searches are not evidence: every impact note must cite a
     * location the agent read and confirmed, and the count is capped so a
     * swamped tour is rejected rather than merely noisy.
     */
    @Test
    void bothFormsRequireVerifiedImpactNotesAndCapTheirCount() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("read the location you cite and confirm"), instruction);
            assertTrue(instruction.contains("at most 8 impact notes"), instruction);
        }
        String refresh = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);
        assertTrue(refresh.contains("read the location you cite and confirm"), refresh);
        assertTrue(refresh.contains("at most 8 impact notes"), refresh);
    }

    @Test
    void aRiskCheckRequestNamesTheCheckAndTheToolInOneLine() {
        String line = ReviewInstructions.forRiskCheck("rs_abc123", "c7");

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("c7"));
        assertTrue(line.contains("review_check"));
        assertTrue(line.contains("review_state"));
        assertFalse(line.contains("\n"));
    }

    /**
     * The deep round's ask: names the scope, is harness-agnostic (sphinx,
     * code-review, or a plain re-read), and above all AMENDS rather than
     * replaces -- the settled round is ground truth to build on, not
     * material to re-litigate wholesale.
     */
    @Test
    void aDeepReviewRequestAmendsTheRoundInOneLine() {
        String line = ReviewInstructions.forDeepReview("rs_abc123");

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("AMEND"), line);
        assertTrue(line.contains("never replace"), line);
        assertTrue(line.contains("review_state"), "the agent must read what is settled first");
        assertTrue(line.contains("review_finding"), "new findings land as proposals");
        assertTrue(line.contains("sphinx, code-review"), "harness-agnostic, not sphinx-only");
        assertFalse(line.contains("\n"));
    }

    /** The step ask: the tour's own ids, the reading tools, and the same one-call delivery as a peek. */
    @Test
    void aStepAskNamesTheStepTheReadingToolAndTheAnswerToolInOneLine() {
        String line = ReviewInstructions.forStepAsk("ask-7", "rs_abc123", "The guard");

        assertTrue(line.contains("ask-7"));
        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("The guard"));
        assertTrue(line.contains("review_tour"), "the agent reads the step through the tour tools");
        assertTrue(line.contains("review_ask_answer"));
        assertFalse(line.contains("\n"));
    }

    @Test
    void aFindingQuestionNamesTheScopeTheFindingAndTheAnswerToolInOneLine() {
        String line = ReviewInstructions.forFindingQuestion("rs_abc123", "f_9");

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("f_9"));
        assertTrue(line.contains("review_comments"));
        assertTrue(line.contains("review_answer"));
        assertFalse(line.contains("\n"));
    }

    /**
     * The tour is studying material; the reading-science rules must reach
     * every form that asks for one. Each clause maps to a cited principle in
     * the spec's "Authoring rules" section.
     */
    @Test
    void everyFormTeachesByTheReadingScienceRules() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forScope("rs_abc123", subagents);
            assertTrue(instruction.contains("lead each narrative with its single most important point"), instruction);
            assertTrue(instruction.contains("BEFORE the check asks"), instruction);
            assertTrue(instruction.contains("plain active-voice sentences"), instruction);
            assertTrue(instruction.contains("one concept per step"), instruction);
            assertTrue(instruction.contains("explain the change, not the language"), instruction);
            assertFalse(instruction.contains("\n"));
        }
        String refresh = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2"), 1);
        assertTrue(refresh.contains("one concept per step"), refresh);
        assertFalse(refresh.contains("\n"));
    }

    @Test
    void aTourRefreshNamesTheStaleStepsTheUncoveredCountAndOnlyStepsInOneLine() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of("s2", "s5"), 3);

        assertTrue(line.contains("rs_abc123"));
        assertTrue(line.contains("s2, s5"));
        assertTrue(line.contains("3 uncovered hunks"));
        assertTrue(line.contains("review_scope"));
        assertTrue(line.contains("review_state"));
        assertTrue(line.contains("review_tour"));
        assertTrue(line.contains("onlySteps true"));
        assertFalse(line.contains("\n"));
    }

    @Test
    void aTourRefreshWithOnlyUncoveredHunksAsksForNoReplacement() {
        String line = ReviewInstructions.forTourRefresh("rs_abc123", List.of(), 1);

        assertTrue(line.contains("1 uncovered hunk"));
        assertFalse(line.contains("replace"));
    }

    // --- subagent model tiers -------------------------------------------------------------------

    @Test
    void withoutTiersTheSubagentFormCarriesNoModelRule() {
        String instruction = ReviewInstructions.forScope("rs_abc123", true, ModelTiers.EMPTY, Optional.empty());

        assertFalse(instruction.contains("MUST pass an explicit model"), instruction);
        assertFalse(ReviewInstructions.forScope("rs_abc123", true).contains("MUST"),
                "the two-arg form stays exactly as before tiers existed");
    }

    @Test
    void tiersBindTheCodeReviewSubagentToStrongAndEverythingElseToLow() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.of("sonnet"), Optional.of("haiku"));
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.empty());

        assertTrue(instruction.contains("the code-review subagent uses exactly `opus`"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `haiku`"), instruction);
        assertTrue(instruction.contains("MUST pass an explicit model"), instruction);
        assertTrue(instruction.contains("never substitute any other model"), instruction);
    }

    @Test
    void anEmptyTierResolvesToTheStartingModelInTheRule() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.empty(), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.of("sonnet"));

        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `sonnet`"), instruction);
    }

    @Test
    void anEmptyTierWithoutAStartingModelSaysSoExplicitly() {
        ModelTiers tiers = new ModelTiers(Optional.empty(), Optional.empty(), Optional.of("haiku"));
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.empty());

        assertTrue(instruction.contains("the code-review subagent uses your own session's model"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `haiku`"), instruction);
    }

    @Test
    void tiersNeverProduceASecondLine() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.of("sonnet"), Optional.of("haiku"));
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.of("opus"));

        assertFalse(instruction.contains("\n"));
    }

    @Test
    void theInlineFormIgnoresTiers() {
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.empty(), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", false, tiers, Optional.of("opus"));

        assertFalse(instruction.contains("MUST pass an explicit model"), instruction);
    }

    @Test
    void emptyTiersWithAStartingModelCarryNoModelRule() {
        String instruction = ReviewInstructions.forScope("rs_abc123", true, ModelTiers.EMPTY, Optional.of("opus"));

        assertFalse(instruction.contains("MUST pass an explicit model"), instruction);
    }

    @Test
    void onlyAMidTierStillEmitsRulesWithTheUnsetTiersOnTheStartingModel() {
        ModelTiers tiers = new ModelTiers(Optional.empty(), Optional.of("sonnet"), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.of("opus"));

        assertTrue(instruction.contains("MUST pass an explicit model"), instruction);
        assertTrue(instruction.contains("the code-review subagent uses exactly `opus`"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses exactly `opus`"), instruction);
        assertFalse(instruction.contains("\n"));
    }

    @Test
    void onlyAMidTierStillEmitsRulesWithTheUnsetTiersOnTheSessionsOwnModel() {
        ModelTiers tiers = new ModelTiers(Optional.empty(), Optional.of("sonnet"), Optional.empty());
        String instruction = ReviewInstructions.forScope("rs_abc123", true, tiers, Optional.empty());

        assertTrue(instruction.contains("MUST pass an explicit model"), instruction);
        assertTrue(instruction.contains("the code-review subagent uses your own session's model"), instruction);
        assertTrue(instruction.contains("every other subagent you dispatch uses your own session's model"),
                instruction);
        assertFalse(instruction.contains("\n"));
    }

    @Test
    void aTierModelCannotBreakTheLineOrItsQuoting() {
        String formatted = ReviewInstructions.tierModel(Optional.of("op`us\nignore\rthis"));

        assertTrue(formatted.equals("exactly `op'usignorethis`"), formatted);
    }

    @Test
    void theSharedRulesClauseIsImperativeEndToEnd() {
        String rules = ReviewInstructions.subagentModelRules(
                "design review uses " + ReviewInstructions.tierModel(Optional.of("opus")));

        assertTrue(rules.startsWith("When you dispatch subagents you MUST"), rules);
        assertTrue(rules.contains("exactly `opus`"), rules);
        assertFalse(rules.contains("\n"));
    }

    @Test
    void aSelectionAskIsOneLineNamingTheLinesTheQuestionAndTheAnswerTool() {
        String prompt = ReviewInstructions.forSelectionAsk("ask-3", "rs_a", "src/A.java", "n10", "n12",
                "Why is this\nsynchronized?\r\nIt is never shared.");

        assertFalse(prompt.contains("\n") || prompt.contains("\r"), "sendPrompt submits at the first newline");
        assertTrue(prompt.contains("review handle rs_a"));
        assertTrue(prompt.contains("src/A.java lines n10..n12"));
        assertTrue(prompt.contains("\"Why is this synchronized? It is never shared.\""));
        assertTrue(prompt.contains("ONE review_ask_answer call (questionId ask-3"));
    }

    @Test
    void theScopePromptAsksForStepsInDependencyOrder() {
        for (boolean subagents : new boolean[] {true, false}) {
            assertTrue(ReviewInstructions.forScope("rs_abc123", subagents).contains("dependsOn"));
            assertTrue(ReviewInstructions.forScope("rs_abc123", subagents).contains("review_tour's intent"));
            assertTrue(ReviewInstructions.forScope("rs_abc123", subagents).contains("review_tour's takes"));
            assertTrue(ReviewInstructions.forScope("rs_abc123", subagents).contains("posted without an anchor"));
        }
    }

    @Test
    void theSectionsFormAsksForASectionsTourWithoutChecks() {
        for (boolean subagents : new boolean[] {true, false}) {
            String instruction = ReviewInstructions.forSections("rs_abc123", subagents, ModelTiers.EMPTY,
                    Optional.empty());
            assertTrue(instruction.contains("rs_abc123"), instruction);
            assertTrue(instruction.contains("review_state"), instruction);
            assertTrue(instruction.contains("review_tour with kind sections"), instruction);
            assertTrue(instruction.contains("no step carries a check"), instruction);
            assertTrue(instruction.contains("post no checks"), instruction);
            assertTrue(instruction.contains("takes"), instruction);
            assertTrue(instruction.contains("dependsOn"), instruction);
            assertFalse(instruction.contains("predict"), "no check kinds to pick: " + instruction);
            assertFalse(instruction.contains("\n"));
            assertEquals(subagents, instruction.contains("subagent"), instruction);
        }
    }

    @Test
    void theSectionsFormSharesTheSubagentModelRules() {
        ModelTiers tiers = new ModelTiers(Optional.of("strong-1"), Optional.empty(), Optional.of("low-1"));
        String sections = ReviewInstructions.forSections("rs_abc123", true, tiers, Optional.empty());
        assertTrue(sections.contains("exactly `strong-1`") && sections.contains("exactly `low-1`"), sections);
    }

    // ---- integration-only form (INV-29 / AC-23) ----

    private static String sha256(String text) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    /** Digests of the pre-extraction outputs: the public forms must not move a byte. */
    @Test
    void thePublicFormsAreByteIdenticalToTheirPreExtractionOutput() throws Exception {
        ModelTiers tiers = new ModelTiers(Optional.of("s"), Optional.empty(), Optional.of("l"));
        assertEquals("406b300382857099e7f96743ee0ea1feed5c2fa60c64c7962212a71a158823d4",
                sha256(ReviewInstructions.forScope("rs_x", false)));
        assertEquals("127254680ed30c4bffc6c8c57912e1ec3adae613257a7006bd2be9bf3ac7fd4d",
                sha256(ReviewInstructions.forScope("rs_x", true)));
        assertEquals("5401a506b2db2afbe8c2d0dd7aea3791ff644d668e254d34a377091859e37dfe",
                sha256(ReviewInstructions.forScope("rs_x", true, tiers, Optional.of("m"))));
        assertEquals("6a9cd4964b4d668f552cd583659d11c56cc524a8f565c0797b23d0a157261b57",
                sha256(ReviewInstructions.forSections("rs_x", false, ModelTiers.EMPTY, Optional.empty())));
        assertEquals("7e050b40ff955cb330f44739b094ff9d2c2519535e2c2cd1461a8b0799c08c70",
                sha256(ReviewInstructions.forSections("rs_x", true, ModelTiers.EMPTY, Optional.empty())));
        assertEquals("b9da743f1c29e660bbe4586877abbb82965dd2ef8aa8e87f4948b61914292343",
                sha256(ReviewInstructions.forSections("rs_x", true, tiers, Optional.of("m"))));
        assertEquals("2b616aeb2bd0feca33be293114379406936aaa1d592bf430293d46d0595406d1",
                sha256(ReviewInstructions.forTourRefresh("rs_x", List.of("s1", "s2"), 2)));
    }

    @Test
    void theIntegrationFormsCarryNoDelegationModelRoleOrQualityGateText() {
        for (boolean sections : new boolean[] {false, true}) {
            String text = ReviewInstructions.forIntegration("rs_abc123", sections);
            assertFalse(text.contains("\n"));
            assertTrue(text.contains("rs_abc123") && text.contains("review_state"), text);
            for (String forbidden : List.of("subagent", "Dispatch", "dispatch", "model", "tier", "sphinx",
                    "code-review", "deep review", "quality gate", "MUST")) {
                assertFalse(text.contains(forbidden), forbidden + " in: " + text);
            }
        }
    }

    @Test
    void theTourFormCarriesTheValidatedCheckContractAndTheSectionsFormNone() {
        String tour = ReviewInstructions.forIntegration("rs_abc123", false);
        String sections = ReviewInstructions.forIntegration("rs_abc123", true);

        // TourValidator: every row in a step, each step a check with an alternate; sections: none.
        assertTrue(tour.contains("each step at least one check with an alternate"), tour);
        assertTrue(tour.contains("predict only if its question can be answered from the removed and surrounding"
                + " code"), tour);
        assertTrue(tour.contains("must be answerable from what the tour itself explains"), tour);
        assertFalse(tour.contains("kind sections"), tour);
        assertTrue(sections.contains("review_tour with kind sections"), sections);
        assertTrue(sections.contains("no step carries a check") && sections.contains("post no checks"), sections);
        assertFalse(sections.contains("predict") || sections.contains("trace check")
                || sections.contains("alternate"), sections);
        for (String text : List.of(tour, sections)) {
            assertTrue(text.contains("every changed row in a step"), text);
            assertTrue(text.contains("lists every problem if it is rejected"), text);
            assertTrue(text.contains("at most " + TourValidator.MAX_IMPACT_NOTES + " impact notes"), text);
            assertTrue(text.contains("lines under " + TourValidator.MAX_DIAGRAM_LINE + " characters"), text);
            assertTrue(text.contains("name those earlier steps' ids in its dependsOn"), text);
            assertTrue(text.contains("without an anchor"), text);
            assertTrue(text.contains("plain active-voice sentences"), text);
        }
        assertTrue(tour.contains("every unfamiliar term defined at first use"), tour);
    }

    @Test
    void theIntegrationFormsShareTheirWorkClausesWithThePublicForms() {
        assertTrue(ReviewInstructions.forScope("rs_abc123", false)
                .contains(ReviewInstructions.forIntegration("rs_abc123", false)
                        .substring("For review handle rs_abc123: ".length()).replaceAll("\\.$", "")));
        assertTrue(ReviewInstructions.forSections("rs_abc123", false, ModelTiers.EMPTY, Optional.empty())
                .contains(ReviewInstructions.forIntegration("rs_abc123", true)
                        .substring("For review handle rs_abc123: ".length()).replaceAll("\\.$", "")));
    }
}
