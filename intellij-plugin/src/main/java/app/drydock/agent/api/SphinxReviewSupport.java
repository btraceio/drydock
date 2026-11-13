package app.drydock.agent.api;

/**
 * Marks that the harness has the sphinx review plugin installed, as probed by
 * its provider (see {@code AgentProvider#sphinxReview}). Carries no
 * invocation style: every harness is sent the same prompt text, and the
 * harness differences live below the prompt line. Claude Code registers
 * sphinx as a plugin with a typed {@code /sphinx} command. Pi ships no
 * {@code /sphinx} command; an unmatched slash line still reaches the model
 * verbatim, verified against pi 1.2.0 and 1.3.0: built-in commands match by
 * exact string or prefix, extension commands only fire when an extension
 * registered the name, and prompt-template expansion returns the original
 * text when no template name matches -- so the submitted line is sent as the
 * user prompt and the model routes to the sphinx skill advertised in its
 * system prompt. One shadowing path remains: a user-defined prompt template
 * named {@code sphinx} matches and expands the line instead (user-owned
 * configuration, accepted).
 *
 * <p>Absence is not modeled here: an empty {@code Optional} from the provider
 * means "not installed", and callers hide sphinx affordances entirely rather
 * than disable them.</p>
 */
public record SphinxReviewSupport() {

    /**
     * The prompt that reviews an open pull request: the single submitted line
     * a freshly launched session receives as its first task. Single-line by
     * contract -- the terminal submission collapses whitespace, but a prompt
     * that reads as one instruction avoids the line break submitting early.
     */
    public String reviewPullRequestPrompt(int number) {
        return "/sphinx review pr " + number;
    }

    /** As {@link #reviewPullRequestPrompt(int)}, for the working directory's own local changes. */
    public String reviewLocalChangesPrompt() {
        return "/sphinx review";
    }
}
