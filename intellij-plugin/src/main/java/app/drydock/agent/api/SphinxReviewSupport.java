package app.drydock.agent.api;

import java.util.Objects;

/**
 * How a harness reaches the sphinx review plugin, as probed by its provider
 * (see {@code AgentProvider#sphinxReview}). Carries the invocation style so
 * the prompt text is built in one place rather than duplicated per caller:
 * Claude Code installs sphinx as a plugin with a typed {@code /sphinx} slash
 * command, while Pi installs it as a package whose skill has no slash
 * template, so a skill request in prose is the only reliable invocation.
 *
 * <p>Absence is not modeled here: an empty {@code Optional} from the provider
 * means "not installed", and callers hide sphinx affordances entirely rather
 * than disable them.</p>
 */
public record SphinxReviewSupport(Invocation invocation) {

    public SphinxReviewSupport {
        Objects.requireNonNull(invocation, "invocation");
    }

    /** The two ways a harness can be told to run sphinx. */
    public enum Invocation {
        /** A typed slash command ({@code /sphinx ...}), as Claude Code plugins register. */
        SLASH_COMMAND,
        /** A prose request the harness routes to the sphinx skill, as Pi does. */
        SKILL_REQUEST
    }

    /**
     * The prompt that reviews an open pull request: the single submitted line
     * a freshly launched session receives as its first task. Single-line by
     * contract -- the terminal submission collapses whitespace, but a prompt
     * that reads as one instruction avoids the line break submitting early.
     */
    public String reviewPullRequestPrompt(int number) {
        return switch (invocation) {
            case SLASH_COMMAND -> "/sphinx review pr " + number;
            case SKILL_REQUEST -> "Use the sphinx skill to review pull request #" + number
                    + " (sphinx review pr " + number + ").";
        };
    }

    /** As {@link #reviewPullRequestPrompt(int)}, for the working directory's own local changes. */
    public String reviewLocalChangesPrompt() {
        return switch (invocation) {
            case SLASH_COMMAND -> "/sphinx review";
            case SKILL_REQUEST -> "Use the sphinx skill to review this checkout's local changes (sphinx review).";
        };
    }
}
