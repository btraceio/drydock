package app.drydock.agent.providers.pi.internal;

/**
 * The activity-reporting extension drydock installs into pi ({@code
 * drydock-activity.ts}, via {@code -e}). It reports the same state words the
 * Claude hook script writes ({@code busy}/{@code idle}/{@code attention},
 * one file per session id under {@code DRYDOCK_ACTIVITY_DIR}), so {@link
 * app.drydock.activity.SessionActivityWatcher} reads both harnesses through
 * one contract and the "needs you" balloon works for pi exactly as for
 * Claude.
 *
 * <p>The state mapping mirrors Claude's observable behavior: a run marks
 * {@code busy}; a blocking user-facing prompt (Claude's {@code
 * permission_prompt}) marks {@code attention} at once; a settled run marks
 * {@code idle} (Claude's {@code Stop}), and only after
 * {@link #IDLE_ATTENTION_MS} of no user input does it escalate to {@code
 * attention} -- Claude turns amber about a minute after finishing a turn
 * (its {@code idle_prompt} notification), not the second it stops, and the
 * badge copy is the same.</p>
 *
 * <p>Every write is guarded and best-effort: a badge must never be able to
 * disrupt the session it reports on.</p>
 */
public final class PiActivityExtensionSource {

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            // Reports this session's state for the "needs you" badge: a state word
            // (busy|idle|attention) in $DRYDOCK_ACTIVITY_DIR/<session id>, the same
            // contract as the Claude hook script.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            // Claude escalates an idle session to attention about a minute after
            // finishing a turn; match that, so a balloon means "still waiting",
            // not "turn ended".
            const IDLE_ATTENTION_MS = 60_000;

            export default function (pi: ExtensionAPI) {
              const dir = process.env.DRYDOCK_ACTIVITY_DIR;
              delete process.env.DRYDOCK_ACTIVITY_DIR;
              if (!dir) return;

              // Monotonic guard: a timer armed by an older settle must never
              // overwrite a newer state (input, a new run, a prompt ended).
              let generation = 0;
              let timer: ReturnType<typeof setTimeout> | undefined;

              const target = (ctx: any): string | undefined => {
                try {
                  const id: unknown = ctx?.sessionManager?.getSessionId?.();
                  return typeof id === "string" && id.length > 0 ? dir + "/" + id : undefined;
                } catch {
                  return undefined;
                }
              };

              const write = (ctx: any, state: string): void => {
                const file = target(ctx);
                if (!file) return;
                try {
                  const fs = require("node:fs");
                  fs.mkdirSync(dir, { recursive: true });
                  // Write-then-rename: the reader polls this directory and must
                  // never observe a half-written state word.
                  const tmp = file + ".tmp." + process.pid;
                  fs.writeFileSync(tmp, state);
                  fs.renameSync(tmp, file);
                } catch {
                  // Cosmetic: never disrupt the session it reports on.
                }
              };

              const clear = (ctx: any): void => {
                const file = target(ctx);
                if (!file) return;
                try {
                  require("node:fs").unlinkSync(file);
                } catch {
                  // Already gone, or never written.
                }
              };

              const armIdle = (ctx: any): void => {
                if (timer) clearTimeout(timer);
                const at = ++generation;
                timer = setTimeout(() => {
                  if (at === generation) write(ctx, "attention");
                }, IDLE_ATTENTION_MS);
                // Never hold pi alive just for the badge.
                timer.unref?.();
              };

              const cancelIdle = (): void => {
                if (timer) {
                  clearTimeout(timer);
                  timer = undefined;
                }
                generation++;
              };

              // A run covers user prompts and every automatic continuation; the
              // user typing marks busy even when it only steers a live run.
              pi.on("before_agent_start", (_event: any, ctx: any) => {
                cancelIdle();
                write(ctx, "busy");
              });
              pi.on("input", (_event: any, ctx: any) => {
                cancelIdle();
                write(ctx, "busy");
              });
              // A blocking user-facing prompt (confirm/select/input/editor) is
              // Claude's permission_prompt: attention immediately.
              pi.on("ui_prompt_start", (_event: any, ctx: any) => {
                cancelIdle();
                write(ctx, "attention");
              });
              pi.on("ui_prompt_end", (_event: any, ctx: any) => {
                write(ctx, "busy");
              });
              // Settled means no retry, compaction, or queued continuation will
              // follow: the run is over and pi is waiting. Idle first, attention
              // only if the user stays away.
              pi.on("agent_settled", (_event: any, ctx: any) => {
                write(ctx, "idle");
                armIdle(ctx);
              });
              // No process survives a shutdown; a state file left behind would
              // badge a dead session until the next startup purge.
              pi.on("session_shutdown", (_event: any, ctx: any) => {
                cancelIdle();
                clear(ctx);
              });
            }
            """;

    private PiActivityExtensionSource() {
    }
}
