package app.drydock.review;

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

    private ReviewInstructions() {
    }

    public static String forScope(String scopeId, boolean supportsSubagents) {
        Objects.requireNonNull(scopeId, "scopeId");
        String work = "read review_scope for handle " + scopeId + " with include=sections"
                + ", call review_state first so already-settled findings are not re-flagged, "
                + "then post review_finding and review_tour against that handle; review_tour is validated "
                + "(every changed row in a step, each step at least one check with an alternate) and lists "
                + "every problem if it is rejected, so fix them and post it again";
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
}
