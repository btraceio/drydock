package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskLimits;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

/**
 * The optional local {@code editor_ask_answer} seam of {@link McpToolRouter},
 * built like {@link TaskTools}/{@link BabysitTools}: a router without one
 * neither lists nor serves the tool. The router adds nothing but the gate and
 * one delegation; descriptor, validation and the refusal live here.
 *
 * <p>The caller and its credential {@code generation} come from the
 * authenticated request ({@link McpSessionRegistry#authenticate}), never from
 * model arguments. There is deliberately no review scope.</p>
 */
public interface EditorAskAnswerDelegate {

    /** The one tool name this seam adds to a router. */
    String TOOL_NAME = "editor_ask_answer";

    /** Every refusal reads the same: it never echoes the ID, the answer or why. */
    String REFUSAL = "This answer was not accepted.";

    /**
     * Atomically validates the call against the caller's live launch and
     * commits the answer: the lease, marked row, exact execution, attempt
     * state and ID are rechecked at commit. The arguments are already
     * syntactically valid.
     *
     * @param generation the credential generation frozen at authentication
     * @return true only when this call committed the answer; a foreign,
     *         stale, duplicate or unknown call returns false with no mutation
     */
    boolean commit(ManagedSessionId caller, long generation, String questionId, String answer);

    /** The tool descriptor served while a delegate is present. */
    static JsonValue descriptor() {
        return TaskToolCodec.descriptor(TOOL_NAME,
                "Delivers your answer to the editor question you were asked. Call it exactly once "
                        + "with the question's ID and your complete answer; the text is shown to the human.",
                JsonObject.empty()
                        .put("questionId", TaskToolCodec.schemaString("The ID given in the question."))
                        .put("answer", TaskToolCodec.schemaString("Your complete answer, plain text.")),
                "questionId", "answer");
    }

    /**
     * Validates the arguments, then delegates once. Malformed input and a
     * refused commit both raise the same non-echoing refusal.
     */
    static JsonValue invoke(EditorAskAnswerDelegate delegate, ManagedSessionId caller, long generation,
                            JsonValue arguments) throws McpToolException {
        String questionId;
        String answer;
        try {
            JsonObject args = arguments instanceof JsonObject object ? object : JsonObject.empty();
            if (!(args.get("questionId") instanceof JsonString id)
                    || !(args.get("answer") instanceof JsonString text)) {
                throw new McpToolException(REFUSAL);
            }
            questionId = EditorAskRecord.parseId(id.value());
            answer = EditorAskLimits.checkAnswer(text.value());
        } catch (IllegalArgumentException | EditorAskLimits.RejectedInputException e) {
            throw new McpToolException(REFUSAL);
        }
        boolean committed;
        try {
            committed = generation >= 0 && delegate.commit(caller, generation, questionId, answer);
        } catch (RuntimeException e) {
            committed = false; // a failed save is not success; the refusal never echoes the cause
        }
        if (!committed) {
            throw new McpToolException(REFUSAL);
        }
        return JsonObject.empty().put("accepted", new JsonValue.JsonBoolean(true));
    }
}
