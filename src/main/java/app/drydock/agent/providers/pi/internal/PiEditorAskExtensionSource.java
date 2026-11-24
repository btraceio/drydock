package app.drydock.agent.providers.pi.internal;

/**
 * The editor-ask turn-evidence extension drydock installs into pi ({@code
 * drydock-editor-ask.ts}, via {@code -e}) for a Q&amp;A launch only. Modeled on
 * {@link PiActivityExtensionSource} (same {@code before_agent_start} /
 * {@code agent_settled} events, same {@code ctx.sessionManager.getSessionId()}),
 * but it writes <em>receipts</em>, not state words: one atomically created,
 * never overwritten file per event in {@code DRYDOCK_EDITOR_ASK_DIR}, read by
 * {@link PiEditorAskReceipts}. It is never read through the activity monitor.
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>{@code before_agent_start} whose prompt carries the editor-ask envelope
 *       binds that exact marker (the envelope's {@code questionId}, taken from
 *       the fixed position right after the fixed instruction, so selected text
 *       cannot forge it) to the run, and reports {@code bound} with pi's exact
 *       session ID. A later run start without a marker (a continuation, a retry)
 *       keeps the bound marker.</li>
 *   <li>{@code agent_settled} -- pi's "no retry, compaction or queued
 *       continuation will follow" signal -- reports {@code settled} for the bound
 *       marker once, then forgets it. A UI prompt end, the idle-attention timeout
 *       and one model/tool step are not reported at all.</li>
 *   <li>A run with no marker, or a settle with none bound, reports nothing.</li>
 * </ul>
 *
 * <p>Receipts hold the launch nonce, a sequence, the kind, the marker and the
 * session ID: no credential, prompt, code or answer. The source itself holds no
 * secret; the nonce and directory arrive in the environment, are read once and
 * removed so pi's tools and children do not inherit them.</p>
 *
 * <p>Every write is guarded: evidence must never be able to disrupt the session.
 * A missing or failing extension simply produces no receipts, and the
 * coordinator treats that as missing evidence (it refuses or times out; it never
 * infers an end).</p>
 */
public final class PiEditorAskExtensionSource {

    /** File name under {@code <stateDirectory>/pi/}. */
    public static final String FILE_NAME = "drydock-editor-ask.ts";

    /** Environment variable naming the receipt directory of this launch. */
    public static final String DIR_ENV_VAR = "DRYDOCK_EDITOR_ASK_DIR";

    /** Environment variable carrying the nonsecret launch nonce. */
    public static final String NONCE_ENV_VAR = "DRYDOCK_EDITOR_ASK_NONCE";

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            // Reports editor-ask turn evidence as receipt files in
            // $DRYDOCK_EDITOR_ASK_DIR: the exact turn marker bound to a run, and
            // its settled end.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const ENVELOPE_PREFIX = 'Envelope: {"questionId":"';
            const MARKER = /^editor-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

            export default function (pi: ExtensionAPI) {
              // Read once from the environment and removed so pi's tools and
              // children do not inherit it; kept on globalThis so a reload of
              // this extension inside the same pi process still reports.
              const slot = globalThis as any;
              const key = Symbol.for("drydock.editorAsk");
              if (!slot[key] && process.env.DRYDOCK_EDITOR_ASK_DIR && process.env.DRYDOCK_EDITOR_ASK_NONCE) {
                slot[key] = {
                  dir: process.env.DRYDOCK_EDITOR_ASK_DIR,
                  nonce: process.env.DRYDOCK_EDITOR_ASK_NONCE,
                };
              }
              delete process.env.DRYDOCK_EDITOR_ASK_DIR;
              delete process.env.DRYDOCK_EDITOR_ASK_NONCE;
              const dir: string | undefined = slot[key]?.dir;
              const nonce: string | undefined = slot[key]?.nonce;
              if (!dir || !nonce) return;

              // The marker of the turn currently running, and whether its run
              // has been reported bound. Cleared by the settle.
              let marker: string | undefined;
              let seq = 0;

              const sessionId = (ctx: any): string | undefined => {
                try {
                  const id: unknown = ctx?.sessionManager?.getSessionId?.();
                  return typeof id === "string" && id.length > 0 && id.length <= 256 ? id : undefined;
                } catch {
                  return undefined;
                }
              };

              const markerOf = (event: any): string | undefined => {
                const prompt: unknown = event?.prompt;
                if (typeof prompt !== "string") return undefined;
                const at = prompt.indexOf(ENVELOPE_PREFIX);
                if (at < 0) return undefined;
                const from = at + ENVELOPE_PREFIX.length;
                if (prompt.charAt(from + 43) !== '"') return undefined;
                const id = prompt.slice(from, from + 43);
                return MARKER.test(id) ? id : undefined;
              };

              // One new file per event, never overwriting: write a temp file,
              // then hard-link it to its final zero-padded name (fails if that
              // name exists), so the reader never sees a partial receipt and a
              // restarted extension cannot replace an earlier one.
              const report = (kind: string, id: string, ctx: any): void => {
                const session = sessionId(ctx);
                if (!session) return;
                try {
                  const fs = require("node:fs");
                  fs.mkdirSync(dir, { recursive: true });
                  for (const name of fs.readdirSync(dir)) {
                    const n = /^(\\d{12})\\.json$/.exec(name);
                    if (n && Number(n[1]) > seq) seq = Number(n[1]);
                  }
                  for (let attempt = 0; attempt < 8; attempt++) {
                    seq++;
                    const body = JSON.stringify({ v: 1, nonce, seq, kind, marker: id, sessionId: session });
                    const name = String(seq).padStart(12, "0") + ".json";
                    const tmp = dir + "/" + name + "." + process.pid + ".tmp";
                    fs.writeFileSync(tmp, body, { mode: 0o600 });
                    try {
                      fs.linkSync(tmp, dir + "/" + name);
                      return;
                    } catch (e: any) {
                      if (e?.code !== "EEXIST") return;
                    } finally {
                      try { fs.unlinkSync(tmp); } catch {}
                    }
                  }
                } catch {
                  // Evidence only: never disrupt the session it reports on.
                }
              };

              // A run covers the user prompt and every automatic continuation.
              pi.on("before_agent_start", (event: any, ctx: any) => {
                const found = markerOf(event);
                if (!found) return;
                marker = found;
                report("bound", found, ctx);
              });
              // Settled means no retry, compaction, or queued continuation will
              // follow. This is the only end reported.
              pi.on("agent_settled", (_event: any, ctx: any) => {
                if (!marker) return;
                const done = marker;
                marker = undefined;
                report("settled", done, ctx);
              });
            }
            """;

    private PiEditorAskExtensionSource() {
    }
}
