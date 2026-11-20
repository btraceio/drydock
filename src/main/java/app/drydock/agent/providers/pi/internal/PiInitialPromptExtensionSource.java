package app.drydock.agent.providers.pi.internal;

import app.drydock.agent.api.CreateContext;

/**
 * A one-shot Pi extension that submits Drydock's initial task after Pi has
 * mounted its interactive UI and activated the extension runtime.
 *
 * <p>The prompt is base64-encoded in {@code DRYDOCK_PI_INITIAL_PROMPT}. The
 * extension removes that variable while it loads so tools spawned by the
 * session do not inherit the task text. It deliberately submits only on the
 * first {@code session_start}: a later extension reload or in-process session
 * switch must not replay the implementation task.</p>
 *
 * <p>When the prompt carries drydock's framing (composed by
 * {@code NewTaskPlan.initialPrompt} as {@code framing +
 * CreateContext.TASK_SEPARATOR + task}), the two halves travel on separate
 * channels: the task is submitted as the only user message, and the framing
 * rides the run the extension itself submits as a non-displaying custom
 * message returned from {@code before_agent_start} -- custom messages reach
 * the model as user-role content, so the guidance is delivered without being
 * concatenated onto the user's words. A prompt without the separator (an MCP
 * caller's own task, or a bare {@code /} harness command) is submitted
 * as-is.</p>
 */
public final class PiInitialPromptExtensionSource {

    private PiInitialPromptExtensionSource() {
    }

    private static final String TEMPLATE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const PROMPT_ENV = "DRYDOCK_PI_INITIAL_PROMPT";
            const TASK_SEPARATOR = "__TASK_SEPARATOR__";

            export default function (pi: ExtensionAPI) {
              const encoded = process.env[PROMPT_ENV];
              delete process.env[PROMPT_ENV];
              if (!encoded) return;

              let prompt: string;
              try {
                prompt = Buffer.from(encoded, "base64").toString("utf8");
              } catch (e: any) {
                console.warn("drydock: could not decode the initial task;", e?.message ?? e);
                return;
              }

              // Drydock composes framing + TASK_SEPARATOR + task. Split at the
              // first occurrence: the framing half rides the submission run
              // as a custom message, the task half is the user message.
              const cut = prompt.indexOf(TASK_SEPARATOR);
              const framing = cut >= 0 ? prompt.slice(0, cut).trim() : "";
              const task = (cut >= 0 ? prompt.slice(cut + TASK_SEPARATOR.length) : prompt).trim();
              if (!task) {
                if (prompt.trim()) {
                  console.warn("drydock: initial prompt had framing but no task; not submitting");
                }
                return;
              }

              let framingPending = false;
              pi.on("before_agent_start", (event) => {
                if (!framingPending || !framing) return;
                if (event.messageType !== "user") return;
                framingPending = false;
                return {
                  message: { customType: "drydock.task-framing", content: framing, display: false },
                };
              });

              let submitted = false;
              pi.on("session_start", () => {
                if (submitted) return;
                submitted = true;
                // session_start fires after Pi's interactive UI is mounted.
                // Deferring one event-loop turn lets session initialization
                // finish before this starts the model turn. framingPending
                // marks the run this extension itself initiates: the framing
                // custom message rides that run only, never a user-typed
                // one. Only a single-line task starting with '/' is a
                // harness command (mirrors NewTaskPlan.isSlashOneLiner):
                // expansion must be on or the command arrives as dead text
                // -- AgentSession.prompt expands by default,
                // sendUserMessage does not. A multi-line prompt that merely
                // starts with '/' is a framed task, not a command, and must
                // not be expanded.
                setTimeout(() => {
                  framingPending = true;
                  pi.sendUserMessage(task, { expandPromptTemplates: task.startsWith("/") && task.indexOf("\\n") < 0 });
                }, 0);
              });
            }
            """;

    /** The extension source with the separator contract interpolated in. */
    public static final String SOURCE = TEMPLATE.replace("__TASK_SEPARATOR__", CreateContext.TASK_SEPARATOR);
}
