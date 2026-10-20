package app.drydock.agent.providers.pi.internal;

/**
 * A one-shot Pi extension that submits Drydock's initial task after Pi has
 * mounted its interactive UI and activated the extension runtime.
 *
 * <p>The prompt is base64-encoded in {@code DRYDOCK_PI_INITIAL_PROMPT}. The
 * extension removes that variable while it loads so tools spawned by the
 * session do not inherit the task text. It deliberately submits only on the
 * first {@code session_start}: a later extension reload or in-process session
 * switch must not replay the implementation task.</p>
 */
public final class PiInitialPromptExtensionSource {

    private PiInitialPromptExtensionSource() {
    }

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const PROMPT_ENV = "DRYDOCK_PI_INITIAL_PROMPT";

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
              if (!prompt.trim()) return;

              let submitted = false;
              pi.on("session_start", () => {
                if (submitted) return;
                submitted = true;
                // session_start fires after Pi's interactive UI is mounted.
                // Deferring one event-loop turn lets session initialization
                // finish before this starts the model turn.
                setTimeout(() => pi.sendUserMessage(prompt), 0);
              });
            }
            """;
}
