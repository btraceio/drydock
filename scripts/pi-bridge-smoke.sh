#!/bin/sh
# Runs drydock's pi bridge extension against a mock of drydock's MCP server.
#
# This is the only automated check the TypeScript half has: the repo has no JS
# toolchain, and pi loads the extension through jiti, which strips types
# without checking them. Re-run this after every change to PiExtensionSource.
#
# Usage: scripts/pi-bridge-smoke.sh <path-to-extension.ts>
set -eu

EXT="${1:?usage: pi-bridge-smoke.sh <extension.ts>}"
WORK="$(mktemp -d)"
trap 'kill "${MOCK_PID:-}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

PORT=8765

cat > "$WORK/mock.mjs" <<'MOCK'
import { createServer } from "node:http";
import { writeFileSync } from "node:fs";
const PORT = Number(process.argv[2]);
const TOKEN = "smoke-token";
// Mirrors McpServer: JSON-RPC over POST /mcp, tools/call results double-encoded
// into content[0].text, 401 with an empty body when the token is wrong.
const TOOLS = [{
  name: "session_rename",
  description: "Renames this session's own tab, which the human is watching.",
  inputSchema: { type: "object", properties: { title: { type: "string" } }, required: ["title"] },
}, {
  // Collides with a pi built-in on purpose: the guard must refuse this one
  // and leave pi's own file reader intact.
  name: "read",
  description: "A drydock tool that must never shadow pi's built-in read.",
  inputSchema: { type: "object", properties: { path: { type: "string" } }, required: ["path"] },
}];
// Asserting the injection is awkward: pi writes no system prompt into the
// session .jsonl (docs/session-format.md enumerates every entry type and none
// carries one). So the instructions ask for something OBSERVABLE, and the
// script checks the model's reply rather than the transcript.
const INSTRUCTIONS = "Begin every reply with the exact token DRYDOCK-BRIDGE-OK on its own line.";
createServer((req, res) => {
  if (req.headers["x-drydock-session-token"] !== TOKEN) { res.writeHead(401).end(); return; }
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const rpc = JSON.parse(body || "{}");
    const reply = (result) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: rpc.id, result }));
    };
    if (rpc.method === "initialize") {
      reply({ protocolVersion: "2025-06-18", capabilities: { tools: {} },
              serverInfo: { name: "drydock", version: "1.0.0" }, instructions: INSTRUCTIONS });
    } else if (rpc.method === "tools/list") {
      reply({ tools: TOOLS });
    } else if (rpc.method === "tools/call") {
      const title = rpc.params?.arguments?.title ?? "";
      if (title === "PINNED") {
        reply({ content: [{ type: "text", text: "This session was named by the human." }], isError: true });
      } else {
        reply({ content: [{ type: "text",
                 text: JSON.stringify({ outcome: "renamed", title }) }], isError: false });
      }
    } else { reply({}); }
  });
}).on("error", (e) => { console.error("mock failed to bind: " + e.message); process.exit(1); })
  .listen(PORT, "127.0.0.1", () => writeFileSync(process.argv[3], "ready"));
MOCK

command -v node > /dev/null 2>&1 || { echo "SKIP: node is not on PATH; the mock server needs it"; exit 2; }

# Preflight: everything below depends on the model actually calling a tool, so
# a missing/unconfigured provider must not look like "the bridge is broken".
# --session-dir keeps the preflight out of the user's real pi history, and the
# sentinel is specific enough that "tokens" cannot satisfy it.
mkdir -p "$WORK/preflight"
if ! env -u PI_CODING_AGENT pi --session-dir "$WORK/preflight" --offline \
       -p "Reply with exactly: PREFLIGHT-OK" < /dev/null 2>&1 | grep -q "PREFLIGHT-OK"; then
  echo "SKIP: pi has no working model here, so this script cannot judge the bridge"
  exit 2
fi

node "$WORK/mock.mjs" "$PORT" "$WORK/mock.ready" &
MOCK_PID=$!
# Wait for OUR mock, not for whatever else might hold the port: the mock writes
# a sentinel once it is listening, and refuses to start if the port is taken.
i=0
while [ ! -f "$WORK/mock.ready" ]; do
  i=$((i+1)); [ "$i" -lt 50 ] || {
    echo "FAIL: the mock never came up on port $PORT (is something else using it?)"
    exit 1
  }
  sleep 0.2
done

mkdir -p "$WORK/state" "$WORK/sessions" "$WORK/cwd"
cat > "$WORK/state/config.json" <<CONFIG
{"mcpServers":{"drydock":{"url":"http://127.0.0.1:$PORT/mcp","headers":{"X-Drydock-Session-Token":"smoke-token"}}}}
CONFIG

cd "$WORK/cwd"
DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions" --offline -e "$EXT" \
    -p "Call the session_rename tool with the title 'Smoke test'. Then stop." \
    < /dev/null > "$WORK/out.txt" 2>&1 || true

TRANSCRIPT="$(find "$WORK/sessions" -name '*.jsonl' | head -1)"
[ -n "$TRANSCRIPT" ] || { echo "FAIL: no session transcript written"; cat "$WORK/out.txt"; exit 1; }

fail() { echo "FAIL: $1"; echo "--- pi output ---"; cat "$WORK/out.txt"; echo "--- transcript ---"; cat "$TRANSCRIPT"; exit 1; }

# Every assertion below depends on the model CHOOSING to call the tool, which is
# not guaranteed: observed on this machine, a model answered "Done. Tab renamed"
# without calling anything. So distinguish "the tool was not registered" from
# "the model did not use it" before blaming the bridge.
grep -q '"toolCall"' "$TRANSCRIPT" || {
  echo "INCONCLUSIVE: the model emitted no tool call at all."
  echo "Re-run once; if it recurs, check registration directly with:"
  echo "  pi --offline -e <ext> -p 'List your available tools.'"
  echo "and confirm session_rename appears. Only then is this a bridge failure."
  cat "$TRANSCRIPT"; exit 3
}

grep -q '"name": *"session_rename"' "$TRANSCRIPT" || grep -q '"name":"session_rename"' "$TRANSCRIPT" \
  || fail "the model never called session_rename (was it registered?)"
# NOT `grep 'Smoke test'`: pi writes the user prompt into the transcript
# verbatim, so that string matches even when nothing was registered.
# -F (fixed string), and the backslashes are literal: the tool result text is
# itself a JSON string, so pi's own JSONL serialization escapes its embedded
# quotes -- the transcript byte-for-byte contains \"outcome\":\"renamed\", not
# the unescaped form (confirmed against real pi 0.84.1 output).
grep -qF '\"outcome\":\"renamed\"' "$TRANSCRIPT" || fail "the tool call never reached the mock"
# The instructions injection, checked through behaviour: pi never writes the
# system prompt to the transcript, so there is nothing to grep there.
grep -q 'DRYDOCK-BRIDGE-OK' "$WORK/out.txt" || {
  echo "INCONCLUSIVE: the model did not follow the injected instruction."
  echo "That is either a failed before_agent_start injection or an inattentive model."
  echo "To separate them, re-run with the extension's before_agent_start logging its"
  echo "return value to a file (NOT to stderr -- pi is drawing that terminal)."
  cat "$WORK/out.txt"; exit 3
}
# The collision guard, checked by consequence rather than by registry: ask for
# a real read and see whether pi's built-in answers it.
echo "canary-contents-9f3a" > "$WORK/cwd/canary.txt"
rm -rf "$WORK/sessions_read"; mkdir -p "$WORK/sessions_read"
DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions_read" --offline -e "$EXT" \
    -p "Read canary.txt with your read tool and print its contents." \
    < /dev/null > "$WORK/read.txt" 2>&1 || true
T_READ="$(find "$WORK/sessions_read" -name '*.jsonl' | head -1)"
grep -q '"toolCall"' "$T_READ" || {
  echo "INCONCLUSIVE: the model answered without calling read, so this run cannot judge the guard"
  exit 3
}
grep -q 'canary-contents-9f3a' "$WORK/read.txt" \
  || { echo "FAIL: pi's built-in read did not survive -- the colliding drydock read shadowed it";
       cat "$WORK/read.txt"; exit 1; }
grep -q '127.0.0.1' "$TRANSCRIPT" && fail "the endpoint URL leaked into the transcript"

# A dead endpoint must degrade, not kill the tab, and must not leak the port.
# This exercises the FACTORY's catch, which is Task 5's code.
cat > "$WORK/state/dead.json" <<DEAD
{"mcpServers":{"drydock":{"url":"http://127.0.0.1:59999/mcp","headers":{"X-Drydock-Session-Token":"smoke-token"}}}}
DEAD
rm -rf "$WORK/sessions_dead"; mkdir -p "$WORK/sessions_dead"
if ! DRYDOCK_MCP_CONFIG="$WORK/state/dead.json" \
     env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions_dead" --offline -e "$EXT" \
       -p "Say OK." < /dev/null > "$WORK/out_dead.txt" 2>&1; then
  echo "FAIL: a dead endpoint took the tab down"; cat "$WORK/out_dead.txt"; exit 1
fi
grep -q '59999' "$WORK/out_dead.txt" && { echo "FAIL: the port leaked into pi's output"; exit 1; }

echo "PASS: handshake, registration and tools/call all reached the mock"
