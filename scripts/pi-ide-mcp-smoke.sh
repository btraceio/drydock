#!/bin/sh
# Runs the plugin's pi bridge extension (PiMcpExtensionSource) against a mock
# of the IDE's bundled MCP server.
#
# The extension carries both halves of the IDE wiring: the stdio servers
# (registerMcpServer with literal env values) and the five ide_* proxy tools
# plus their system-prompt nudge (registerTool). pi loads it through jiti,
# which strips types without checking them -- this script is the automated
# check. The proxy call-through run makes one real model call, like
# pi-bridge-smoke.sh; everything else is model-free. Re-run after every
# change to PiMcpExtensionSource.
#
# Usage: scripts/pi-ide-mcp-smoke.sh   (from the repository root)
set -eu

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$WORK/ext.ts" <<'EXTRACT'
import sys, pathlib, textwrap
src = "src/main/java/app/drydock/agent/providers/pi/internal/PiMcpExtensionSource.java"
body = pathlib.Path(src).read_text().split('"""')[1].split("\n", 1)[1]
pathlib.Path(sys.argv[1]).write_text(textwrap.dedent(body).replace("\\\\", "\\"))
EXTRACT

# A stdio MCP server shaped like the IDE's: initialize, tools/list with the
# five curated tool names, tools/call logged and answered. Records the env it
# was started with, so the literal-values check can read it back.
cat > "$WORK/mock.mjs" <<'MOCK'
import { createInterface } from "node:readline";
import { writeFileSync, appendFileSync } from "node:fs";
writeFileSync(process.argv[2], JSON.stringify({
  path: process.env.IJ_MCP_SERVER_PROJECT_PATH, bang: process.env.BANG }));
const send = (m) => process.stdout.write(JSON.stringify(m) + "\n");
const schema = { type: "object", properties: { q: { type: "string" } }, required: ["q"] };
const TOOLS = ["search_symbol", "get_symbol_info", "analyze_calls",
               "get_file_problems", "rename_refactoring"]
  .map((name) => ({ name, description: "mock " + name, inputSchema: schema }));
createInterface({ input: process.stdin }).on("line", (line) => {
  const rpc = JSON.parse(line);
  if (rpc.id === undefined) return;
  if (rpc.method === "initialize") send({ jsonrpc: "2.0", id: rpc.id, result: {
    protocolVersion: rpc.params.protocolVersion, capabilities: { tools: {} },
    serverInfo: { name: "mock-ide", version: "1" } } });
  else if (rpc.method === "tools/list") send({ jsonrpc: "2.0", id: rpc.id, result: { tools: TOOLS } });
  else if (rpc.method === "tools/call") {
    appendFileSync(process.argv[3], JSON.stringify({ tool: rpc.params.name, q: rpc.params.arguments?.q }) + "\n");
    if (rpc.params.arguments?.q === "BOOM") {
      send({ jsonrpc: "2.0", id: rpc.id, result: { isError: true, content: [{ type: "text",
        text: "mock says no" }] } });
      return;
    }
    send({ jsonrpc: "2.0", id: rpc.id, result: { content: [{ type: "text",
      text: JSON.stringify({ ok: true, tool: rpc.params.name }) }] } });
  }
  else send({ jsonrpc: "2.0", id: rpc.id, result: {} });
});
MOCK

# A witness extension: at before_agent_start (after the bridge's own handler
# has appended its blocks) it records the declared tool names and the system
# prompt it was handed, so registration and the nudge are checked without a
# model's cooperation.
cat > "$WORK/dumper.ts" <<'DUMPER'
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
export default async function (pi: ExtensionAPI) {
  const out = process.env.DUMPER_OUT as string;
  pi.on("before_agent_start", async (event: any) => {
    const names = (pi.getAllTools() ?? []).map((t: any) => t?.name);
    require("node:fs").writeFileSync(out, JSON.stringify({
      tools: names, prompt: event?.systemPrompt ?? "" }));
  });
}
DUMPER

command -v node > /dev/null 2>&1 || { echo "SKIP: node is not on PATH; the mock server needs it"; exit 2; }
command -v pi > /dev/null 2>&1 || { echo "SKIP: pi is not on PATH"; exit 2; }

# A worktree path pi would rewrite if the extension did not escape it.
PROJECT="$WORK/wt \$HOME \${USER}"
mkdir -p "$PROJECT"
CALLS="$WORK/calls.jsonl"
SERVERS=$(python3 -c 'import json, sys; print(json.dumps({"mcpServers": {"jetbrains": {
    "command": sys.argv[1], "args": [sys.argv[2], sys.argv[3], sys.argv[4]],
    "env": {"IJ_MCP_SERVER_PROJECT_PATH": sys.argv[5], "BANG": "!echo pwned"}}}}))' \
    "$(command -v node)" "$WORK/mock.mjs" "$WORK/env.json" "$CALLS" "$PROJECT")

# Preflight: the call-through run below needs a working model; a missing
# provider must not look like "the bridge is broken". --session-dir keeps
# this out of the user's real pi history.
mkdir -p "$WORK/preflight"
if ! env -u PI_CODING_AGENT pi --session-dir "$WORK/preflight" --offline \
       -p "Reply with exactly: PREFLIGHT-OK" < /dev/null 2>&1 | grep -q "PREFLIGHT-OK"; then
  echo "SKIP: pi has no working model here, so this script cannot judge the proxies"
  exit 2
fi

run_pi() {
  sessions="$1"; prompt="$2"
  rm -rf "$WORK/$sessions"; mkdir -p "$WORK/$sessions"
  (cd "$PROJECT" && DRYDOCK_STDIO_MCP_SERVERS="$SERVERS" DUMPER_OUT="$WORK/dump.json" \
     env -u PI_CODING_AGENT pi --session-dir "$WORK/$sessions" --offline \
     -e "$WORK/ext.ts" -e "$WORK/dumper.ts" -p "$prompt" < /dev/null) \
     > "$WORK/out.txt" 2>&1 || true
}

# Registration and the system-prompt nudge. The adapter connects a
# runtime-registered server lazily -- on first tool call, not at
# registration -- so the mock server is not spawned yet here; only the
# declared proxies and the injected prompt are asserted.
run_pi sessions_reg "Say hi."

[ -f "$WORK/dump.json" ] || { echo "FAIL: the witness extension never ran"; cat "$WORK/out.txt"; exit 1; }
python3 - "$WORK/dump.json" <<'CHECK'
import json, sys
d = json.load(open(sys.argv[1]))
tools = d["tools"]
want = ["ide_search_symbol", "ide_symbol_info", "ide_call_hierarchy",
        "ide_file_problems", "ide_rename"]
missing = [t for t in want if t not in tools]
assert not missing, f"proxies missing from the registry: {missing}; had {len(tools)} tools"
prompt = d["prompt"]
for marker in ["IntelliJ IDE tools", "ide_search_symbol", "tool search in the jetbrains namespace"]:
    assert marker in prompt, f"nudge marker {marker!r} missing from the system prompt"
CHECK
echo "PASS: ide_* proxies declared, nudge injected"

# The call must actually travel: model -> ide_search_symbol proxy ->
# executeTool -> adapter (which now connects the lazy server, spawning it
# with its env) -> stdio mock. This run also proves the env values stayed
# literal: the mock records what it was started with. A model that answers
# without calling is inconclusive, not a failure, as in pi-bridge-smoke.sh.
run_pi sessions_call "Call the ide_search_symbol tool with q set to 'SmokeSymbol'. Then stop."
TRANSCRIPT="$(find "$WORK/sessions_call" -name '*.jsonl' | head -1)"
[ -n "$TRANSCRIPT" ] || { echo "FAIL: no session transcript written"; cat "$WORK/out.txt"; exit 1; }
grep -q '"toolCall"' "$TRANSCRIPT" || {
  echo "INCONCLUSIVE: the model emitted no tool call at all. Re-run once."
  cat "$TRANSCRIPT"; exit 3
}
grep -q '"name": *"ide_search_symbol"' "$TRANSCRIPT" \
  || { echo "FAIL: the model never called ide_search_symbol (was it declared?)"; cat "$TRANSCRIPT"; exit 1; }
[ -f "$WORK/env.json" ] || { echo "FAIL: the proxy call never spawned the IDE mock server"; cat "$WORK/out.txt"; exit 1; }
python3 - "$WORK/env.json" "$PROJECT" <<'CHECK'
import json, sys
seen = json.load(open(sys.argv[1]))
assert seen["path"] == sys.argv[2], f"project path rewritten: {seen['path']!r}"
assert seen["bang"] == "!echo pwned", f"leading ! not kept literal: {seen['bang']!r}"
CHECK
grep -q '"tool":"search_symbol"' "$CALLS" \
  || { echo "FAIL: the proxy never reached the IDE mock (executeTool -> adapter -> stdio broken)";
       cat "$CALLS" 2>/dev/null; cat "$WORK/out.txt"; exit 1; }
echo "PASS: ide_search_symbol call travelled proxy -> adapter -> IDE mock, env values literal"

# The nested isError must reach the model as an ERROR, not as a success
# payload: the mock answers q=BOOM with isError and a known text, and the
# model is asked to relay what the tool said. If callIdeTool stopped throwing
# on isError, the model would see the success shape and the text would never
# appear. Same INCONCLUSIVE contract as above.
run_pi sessions_err "Call the ide_search_symbol tool with q set to 'BOOM'. Then tell me exactly what the tool said."
T_ERR="$(find "$WORK/sessions_err" -name '*.jsonl' | head -1)"
[ -n "$T_ERR" ] || { echo "FAIL: no transcript written for the error run"; cat "$WORK/out.txt"; exit 1; }
grep -q '"toolCall"' "$T_ERR" || {
  echo "INCONCLUSIVE: the model emitted no tool call at all. Re-run once."
  cat "$T_ERR"; exit 3
}
grep -q 'mock says no' "$T_ERR" \
  || { echo "FAIL: the IDE tool's isError never reached the model as an error"; cat "$T_ERR"; exit 1; }
grep -q '"isError":true' "$T_ERR" \
  || { echo "FAIL: pi never recorded the call as an error (deleting the isError throw would go unnoticed)"; cat "$T_ERR"; exit 1; }
echo "PASS: isError from the IDE tool surfaces as a thrown error"
