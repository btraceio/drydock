package app.drydock.agent.providers.pi.internal;

/**
 * The PR-reference-scanning extension drydock installs into pi ({@code
 * drydock-pr-scan.ts}, via {@code -e}). After every settled run (and once at
 * session start, so a resumed session re-reports what its history already
 * contains) it scans the conversation for GitHub PR references --
 * {@code https://github.com/<owner>/<repo>/pull/<n>}, which covers an
 * agent's "opened PR #1034 (draft)" report and a raw {@code gh pr create}
 * tool result alike -- and writes distinct {@code owner/repo#<n>} lines to
 * {@code $DRYDOCK_PR_DIR/<session id>}.
 *
 * <p>The file contract is exactly the Claude hook script's (see {@code
 * ClaudeHookInstaller} {@code PR_SCAN_SCRIPT}), so
 * {@link app.drydock.activity.PrLinkWatcher} reads both harnesses through one
 * format. Repo-blind on purpose: the extension cannot know which repository
 * the session belongs to, so it reports every PR URL it saw and the app-side
 * fold keeps only the session's own repository.</p>
 *
 * <p>Every write is guarded and best-effort: an association convenience must
 * never be able to disrupt the session it reports on.</p>
 */
public final class PiPrScanExtensionSource {

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            // Scans the conversation for GitHub PR references and writes
            // owner/repo#<n> lines to $DRYDOCK_PR_DIR/<session id>, the same
            // contract as the Claude drydock-pr-scan.sh hook.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            const PR_URL = /https:\\/\\/github\\.com\\/[^"'/\\s]+\\/[^"'/\\s]+\\/pull\\/(\\d+)/g;

            export default function (pi: ExtensionAPI) {
              const dir = process.env.DRYDOCK_PR_DIR;
              delete process.env.DRYDOCK_PR_DIR;
              if (!dir) return;

              // Refs already reported, so each scan writes the union and a
              // re-scan of old entries never shrinks the file.
              const seen = new Set<string>();

              const scan = (ctx: any): void => {
                try {
                  const fs = require("node:fs");
                  const path = require("node:path");
                  const id: unknown = ctx?.sessionManager?.getSessionId?.();
                  if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) return;
                  const root = path.resolve(dir);
                  const file = path.resolve(root, id);
                  const relative = path.relative(root, file);
                  if (!relative || relative === ".." || relative.startsWith(".." + path.sep)
                      || path.isAbsolute(relative)) return;
                  const branch: any[] = ctx.sessionManager.getBranch() || [];
                  for (const entry of branch) {
                    const message = entry?.message;
                    if (!message) continue;
                    const blocks = Array.isArray(message.content) ? message.content : [];
                    for (const block of blocks) {
                      const text = typeof block === "string"
                        ? block
                        : (typeof block?.text === "string" ? block.text : "");
                      if (!text) continue;
                      for (const match of text.matchAll(PR_URL)) {
                        // owner/repo is everything between the host and the
                        // last /pull/; the URL may continue past the number.
                        const url: string = match[0];
                        const cut = url.lastIndexOf("/pull/");
                        const rest = url.slice("https://github.com/".length, cut);
                        seen.add(rest + "#" + match[1]);
                      }
                    }
                  }
                  if (seen.size === 0) return;
                  // Bound the file the way the hook script does: at most 64 refs.
                  const refs = [...seen].sort().slice(0, 64);
                  fs.mkdirSync(root, { recursive: true });
                  const tmp = path.join(root, "." + id + ".tmp." + process.pid);
                  // Write-then-rename: the reader polls this directory and
                  // must never observe a half-written reference list.
                  fs.writeFileSync(tmp, refs.join("\\n") + "\\n");
                  fs.renameSync(tmp, file);
                } catch {
                  // Cosmetic: never disrupt the session it reports on.
                }
              };

              pi.on("session_start", (_event: any, ctx: any) => scan(ctx));
              pi.on("agent_settled", (_event: any, ctx: any) => scan(ctx));
            }
            """;

    private PiPrScanExtensionSource() {
    }
}
