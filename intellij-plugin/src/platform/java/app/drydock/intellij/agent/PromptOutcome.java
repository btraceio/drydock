package app.drydock.intellij.agent;

/** What became of a prompt sent to an agent by {@link AgentExecutionRegistry#submitPrompt}. */
public enum PromptOutcome {
    /**
     * The prompt and its submitting carriage return were handed to the process
     * input and the process was still running right after. Not a confirmation
     * that the agent received the prompt: a PTY does not report a failed or
     * short write.
     */
    DELIVERED,
    /** Nothing was written. */
    REFUSED,
    /**
     * The write threw, or the process had ended right after the write; the
     * prompt was probably not received.
     */
    FAILED
}
