package app.drydock.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What drydock asks an agent to do when a human presses "Run review".
 *
 * <p>Two forms, because the review reads better out of the author's context
 * than in it. Where the harness has subagents, the review runs in one: it
 * never held the conversation that wrote the code, and the main session's
 * context does not absorb the whole diff. Where it does not, the same work
 * happens inline -- which is what drydock has always done.</p>
 *
 * <p>Both are one line: they are delivered through {@code
 * TerminalBridge.sendPrompt}, which types them into a prompt.</p>
 */
public final class ReviewInstructions {

    /**
     * The ask for anchor notes, shared by the first tour and a refresh. A note
     * is optional on the wire, so an agent that is never asked sends none and
     * the claims drydock draws under the code stay dormant.
     */
    private static final String ANCHOR_NOTES = "give every anchor a one-sentence note: the single claim "
            + "that range supports, as a statement about the code (drydock shows it under those lines "
            + "once the reviewer has answered the step's check, so it may state the answer)";

    /**
     * Which check kind to pick, shared by the first tour and a refresh. Drydock
     * hides a step's added lines until its PREDICT is answered, so a PREDICT
     * about the added lines is a question the reader cannot answer; without
     * this an agent has only the kind names to go on.
     */
    private static final String CHECK_KINDS = "choose each check's kind by what the reviewer can see: predict "
            + "only if its question can be answered from the removed and surrounding code, because drydock "
            + "hides a step's added lines until a predict is answered (and rejects a predict on a step whose "
            + "rows are all added); ask about the added lines themselves with a trace check, which hides "
            + "nothing";

    /**
     * The grounding rule for every check question, shared by the first tour
     * and a refresh. A question the reviewer cannot answer from the tour or
     * the code in front of them tests their background, not the change --
     * and a reader who cannot answer stops walking.
     */
    private static final String CHECK_GROUNDING = "a check's question, of any kind, must be answerable from "
            + "what the tour itself explains (its narrative and anchor notes) or from code the reviewer can "
            + "see around it -- never from outside knowledge the tour has not taught (spec details, encodings, "
            + "instruction sets, hardware behaviour): teach it in the narrative first, or do not ask it";

    /**
     * How a tour teaches, shared by the first tour and a refresh. Each rule
     * is the reading-science literature compressed to something an agent
     * can hold while writing a step; the spec's "Authoring rules" section
     * carries the citations (Mayer's coherence and segmenting; the
     * worked-example effect; Chandler & Sweller on split attention; the
     * CDC/ODPHP plain-language canon; the expertise reversal effect).
     */
    private static final String TEACHING = "write the tour as teaching material: lead each narrative with its "
            + "single most important point, then only what bears on the decision -- no history, no asides; "
            + "explain the change's reasoning in the narrative or an anchor note BEFORE the check asks the "
            + "reviewer to apply it; plain active-voice sentences, one idea each, every unfamiliar term "
            + "defined at first use; one concept per step, in one file's contiguous rows where possible -- "
            + "split a step that carries two; and the reader is an expert in the language but new to this "
            + "change: explain the change, not the language";

    /**
     * What an impact note must be, shared by the first tour and a refresh.
     * Drydock's impact data and the agent's own usage searches are name
     * matches; an unverified match is a link to an unrelated file more
     * often than not, and the tour drowns in them.
     */
    private static final String IMPACT_NOTES = "impact data and any usage search of your own are name matches, "
            + "not resolved references: before writing an impact note, read the location you cite and confirm "
            + "it genuinely references the change's declaration -- a same-named field, method or local in an "
            + "unrelated file is a false link, not an impact, and a step carries at most 8 impact notes";

    private ReviewInstructions() {
    }

    public static String forScope(String scopeId, boolean supportsSubagents) {
        Objects.requireNonNull(scopeId, "scopeId");
        String work = "read review_scope for handle " + scopeId + " with include=sections,impact"
                + ", call review_state first so already-settled findings are not re-flagged, "
                + "then post review_finding and review_tour against that handle; review_tour is validated "
                + "(every changed row in a step, each step at least one check with an alternate) and lists "
                + "every problem if it is rejected, so fix them and post it again; " + ANCHOR_NOTES + "; "
                + CHECK_KINDS + "; " + IMPACT_NOTES + "; " + CHECK_GROUNDING + "; " + TEACHING;
        return supportsSubagents
                ? "Dispatch a code-review subagent to review the changes in this worktree: it must "
                        + work + ". Report only its summary back here."
                : "Review the changes in this worktree with the drydock review tools: " + work + ".";
    }

    /**
     * What drydock asks when a base move has marked approvals stale (spec
     * §9.7). Bounded on purpose: the base delta and the stale hunks, not the
     * change.
     *
     * <p>Says outright that "unaffected" does not clear an approval. An agent
     * should be told the rule rather than left to infer it from what {@code
     * review_recheck} happens to refuse.</p>
     *
     * <p>Only the subagent form, unlike {@link #forScope}: spec §9.7 gives an
     * automatic recheck only to a harness that has subagents, so an inline
     * form here would be a branch nothing could reach.</p>
     */
    public static String forRecheck(String scopeId, String fromBase, String toBase) {
        Objects.requireNonNull(scopeId, "scopeId");
        // Both bases too: they are concatenated, so a null would reach the
        // agent as the literal "null" in a line typed at its prompt.
        Objects.requireNonNull(fromBase, "fromBase");
        Objects.requireNonNull(toBase, "toBase");
        String work = "for handle " + scopeId + ", read what changed between " + fromBase
                + " and " + toBase + ", and for each approved hunk it could affect call "
                + "review_recheck with affected and a one-line why. Marking a hunk affected "
                + "asks the human to read it again; marking one unaffected is advice and "
                + "does not clear their approval";
        return "Dispatch a subagent to recheck stale approvals: " + work
                + ". Report only its summary back here.";
    }

    /**
     * What drydock asks when a reviewer posts a message into a finding's
     * thread (spec §4, "Not sure"): the question itself stays in the thread
     * -- {@code review_comments} carries the messages -- so the prompt
     * carries only the ids, the same shape as {@link #forRiskCheck}. One
     * line, for the same reason: it goes through {@code sendPrompt}, which
     * submits at the first newline.
     */
    public static String forFindingQuestion(String scopeId, String findingId) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(findingId, "findingId");
        return "For review handle " + scopeId + ", the reviewer asked a question in finding " + findingId
                + "'s thread: call review_comments to read it, answer it with review_answer, and revise "
                + "the finding if they have changed your mind";
    }

    /**
     * One line asking the agent to judge a reviewer's free-text answer.
     *
     * <p>Carries only the check id: the answer is the reviewer's own text and
     * reaches the agent through {@code review_state}, not typed into its
     * prompt.</p>
     */
    public static String forRiskCheck(String scopeId, String checkId) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(checkId, "checkId");
        return "For review handle " + scopeId + ", call review_state and read the reviewer's answer to check "
                + checkId + " under tour.awaitingAgent; judge it against the code and call review_check with "
                + "verdict holds, partly or doesNotHold and a one-line reason.";
    }

    /**
     * Asks the agent to bring its tour onto a diff that moved (spec §3,
     * "Staleness"): re-issue {@code staleStepIds} and add steps for the
     * {@code uncoveredHunks} hunks no live step covers, through {@code
     * review_tour} with {@code onlySteps}, so the steps already walked keep
     * their progress. One line, for the same reason as {@link #forRecheck}.
     */
    public static String forTourRefresh(String scopeId, List<String> staleStepIds, int uncoveredHunks) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(staleStepIds, "staleStepIds");
        List<String> asks = new ArrayList<>();
        if (!staleStepIds.isEmpty()) {
            asks.add("replace steps " + String.join(", ", staleStepIds) + " (their code changed)");
        }
        if (uncoveredHunks > 0) {
            asks.add("add steps covering the " + uncoveredHunks + " uncovered hunk"
                    + (uncoveredHunks == 1 ? "" : "s"));
        }
        return "For review handle " + scopeId + ", the diff changed under your tour: call review_scope and "
                + "review_state, then call review_tour with onlySteps true to " + String.join(" and ", asks)
                + "; the steps you do not send keep the reviewer's progress. For the steps you send, "
                + ANCHOR_NOTES + "; " + CHECK_KINDS + "; " + IMPACT_NOTES + "; " + CHECK_GROUNDING + "; "
                + TEACHING + ".";
    }
}
