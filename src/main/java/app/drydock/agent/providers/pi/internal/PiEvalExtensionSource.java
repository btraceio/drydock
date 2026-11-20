package app.drydock.agent.providers.pi.internal;

/** Applies Drydock's eval-account header without coupling it to MCP delivery. */
public final class PiEvalExtensionSource {

    private PiEvalExtensionSource() {
    }

    public static final String SOURCE = """
            // Managed by Drydock -- regenerated on launch; do not edit.
            import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

            export default function (pi: ExtensionAPI) {
              if (process.env.DRYDOCK_EVAL !== "1") return;
              const ids = new Set<string>(["anthropic"]);
              try {
                const os = require("node:os");
                const fs = require("node:fs");
                const path = [os.homedir(), ".pi", "agent", "models.json"].join("/");
                const providers = JSON.parse(fs.readFileSync(path, "utf8"))?.providers;
                if (Array.isArray(providers)) {
                  for (const provider of providers) if (provider?.id) ids.add(String(provider.id));
                } else if (providers && typeof providers === "object") {
                  for (const id of Object.keys(providers)) ids.add(id);
                }
              } catch (e: any) {
                console.warn("drydock: could not read models.json for eval routing;", e?.message ?? e);
              }
              for (const id of ids) {
                try {
                  pi.registerProvider(id, { headers: { "x-target-account": "eval" } } as any);
                } catch (e: any) {
                  console.warn("drydock: eval-account routing skipped for provider", id, ";", e?.message ?? e);
                }
              }
            }
            """;
}
