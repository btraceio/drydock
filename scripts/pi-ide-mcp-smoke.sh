#!/bin/sh
# Runs the plugin's pi IDE-MCP extension against a mock stdio MCP server.
#
# The only automated check the TypeScript has: pi loads it through jiti, which
# strips types without checking them. Needs pi >= 0.99.0 on PATH and node; no
# model call. It checks that pi loads the extension, starts the server it
# names, and hands the server its env values literally -- pi otherwise expands
# $NAME and runs a leading ! as a command. Re-run after every change to
# PiIdeMcpExtensionSource.
#
# Usage: scripts/pi-ide-mcp-smoke.sh   (from the repository root)
set -eu

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$WORK/ide.ts" <<'EXTRACT'
import sys, pathlib, textwrap
src = "intellij-plugin/src/main/java/app/drydock/agent/providers/pi/internal/PiIdeMcpExtensionSource.java"
body = pathlib.Path(src).read_text().split('"""')[1].split("\n", 1)[1]
pathlib.Path(sys.argv[1]).write_text(textwrap.dedent(body).replace("\\\\", "\\"))
EXTRACT

cat > "$WORK/mock.mjs" <<'MOCK'
// Records the env it was started with, then answers the MCP handshake.
import { createInterface } from "node:readline";
import { writeFileSync } from "node:fs";
writeFileSync(process.argv[2], JSON.stringify({
  path: process.env.IJ_MCP_SERVER_PROJECT_PATH, bang: process.env.BANG }));
const send = (m) => process.stdout.write(JSON.stringify(m) + "\n");
createInterface({ input: process.stdin }).on("line", (line) => {
  const rpc = JSON.parse(line);
  if (rpc.id === undefined) return;
  if (rpc.method === "initialize") send({ jsonrpc: "2.0", id: rpc.id, result: {
    protocolVersion: rpc.params.protocolVersion, capabilities: { tools: {} },
    serverInfo: { name: "mock-ide", version: "1" } } });
  else if (rpc.method === "tools/list") send({ jsonrpc: "2.0", id: rpc.id, result: { tools: [] } });
  else send({ jsonrpc: "2.0", id: rpc.id, error: { code: -32601, message: "no " + rpc.method } });
});
MOCK

# A worktree path pi would rewrite if the extension did not escape it.
PROJECT="$WORK/wt \$HOME \${USER}"
mkdir -p "$PROJECT"
SERVERS=$(python3 -c 'import json, sys; print(json.dumps({"mcpServers": {"jetbrains": {
    "command": sys.argv[1], "args": [sys.argv[2], sys.argv[3]],
    "env": {"IJ_MCP_SERVER_PROJECT_PATH": sys.argv[4], "BANG": "!echo pwned"}}}}))' \
    "$(command -v node)" "$WORK/mock.mjs" "$WORK/env.json" "$PROJECT")

# /mcp outside the TUI prints status without a model call; pi connects
# registered servers at session start, which is all this needs.
(cd "$PROJECT" && DRYDOCK_STDIO_MCP_SERVERS="$SERVERS" pi --no-session -e "$WORK/ide.ts" -p "/mcp") \
    > "$WORK/pi.log" 2>&1 || { echo "FAIL: pi exited non-zero"; cat "$WORK/pi.log"; exit 1; }

[ -f "$WORK/env.json" ] || { echo "FAIL: pi never started the registered server"; cat "$WORK/pi.log"; exit 1; }
python3 - "$WORK/env.json" "$PROJECT" <<'CHECK'
import json, sys
seen = json.load(open(sys.argv[1]))
assert seen["path"] == sys.argv[2], f"project path rewritten: {seen['path']!r}"
assert seen["bang"] == "!echo pwned", f"leading ! not kept literal: {seen['bang']!r}"
print("PASS: server started with its env values literal")
CHECK
