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
      } else if (title === "SLOW") {
        // Never answers: the client's own budget must end this.
        return;
      } else if (title === "BOOM") {
        res.writeHead(500).end();
      } else if (title === "HANGUP") {
        req.socket.destroy();
      } else if (title === "UNAUTH") {
        // A mid-session revoke: the request's own header was valid (the top
        // check passed), but THIS call is told the token no longer works.
        res.writeHead(401).end();
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

# Checked against every transcript this script produces, not just the first:
# the error paths are exactly where a leak would surface, so a check that only
# looked at the all-success run would never see it.
no_leak() {
  [ -n "$1" ] || return 0
  grep -q '127.0.0.1' "$1" && { echo "FAIL: the endpoint URL leaked into $2"; cat "$1"; exit 1; }
  # Narrowed to the endpoint form: a bare port number can coincidentally
  # appear inside a millisecond timestamp pi writes into the transcript
  # (e.g. port 8765 inside 1786921887651). Any real leak of the form
  # 127.0.0.1:<port> already contains "127.0.0.1", caught by the line above --
  # this is a strict subset of that coverage, not a weaker check.
  grep -q "127.0.0.1:$PORT" "$1" && { echo "FAIL: the port leaked into $2"; cat "$1"; exit 1; }
  return 0
}

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

grep -q '"name": *"session_rename"' "$TRANSCRIPT" \
  || fail "the model never called session_rename (was it registered?)"
# NOT `grep 'Smoke test'`: pi writes the user prompt into the transcript
# verbatim, so that string matches even when nothing was registered.
# -F (fixed string), and the backslashes are literal: the tool result text is
# itself a JSON string, so pi's own JSONL serialization escapes its embedded
# quotes -- the transcript byte-for-byte contains \"outcome\":\"renamed\", not
# the unescaped form (confirmed against real pi 0.84.1 output).
grep -qF '\"outcome\":\"renamed\"' "$TRANSCRIPT" || fail "the tool call never reached the mock"

# The instructions injection, checked through behaviour in a run of its own:
# pi never writes the system prompt to the transcript, so there is nothing to
# grep there, and print mode (dist/modes/print-mode.js) writes only the LAST
# assistant message's text to stdout. The tool-calling run above emits at
# least two assistant messages, so "begin every reply with..." lands on the
# first one, which print mode never shows -- checking it there is a false
# negative waiting to happen, not a check of the injection. A run with no
# tool call has exactly one assistant message, so it is the only run this
# assertion can trust.
rm -rf "$WORK/sessions_instructions"; mkdir -p "$WORK/sessions_instructions"
DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions_instructions" --offline -e "$EXT" \
    -p "Say hello." < /dev/null > "$WORK/out_instructions.txt" 2>&1 || true
grep -q 'DRYDOCK-BRIDGE-OK' "$WORK/out_instructions.txt" || {
  echo "INCONCLUSIVE: the model did not follow the injected instruction."
  echo "That is either a failed before_agent_start injection or an inattentive model."
  echo "To separate them, re-run with the extension's before_agent_start logging its"
  echo "return value to a file (NOT to stderr -- pi is drawing that terminal)."
  cat "$WORK/out_instructions.txt"; exit 3
}
no_leak "$(find "$WORK/sessions_instructions" -name '*.jsonl' | head -1)" "the instructions-run transcript"

# The collision guard, checked by consequence rather than by registry: ask for
# a real read and see whether pi's built-in answers it.
echo "canary-contents-9f3a" > "$WORK/cwd/canary.txt"
rm -rf "$WORK/sessions_read"; mkdir -p "$WORK/sessions_read"
DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions_read" --offline -e "$EXT" \
    -p "Read canary.txt with your read tool and print its contents." \
    < /dev/null > "$WORK/read.txt" 2>&1 || true
T_READ="$(find "$WORK/sessions_read" -name '*.jsonl' | head -1)"
[ -n "$T_READ" ] || { echo "FAIL: no session transcript written for the read guard run"; cat "$WORK/read.txt"; exit 1; }
grep -q '"toolCall"' "$T_READ" || {
  echo "INCONCLUSIVE: the model answered without calling read, so this run cannot judge the guard"
  exit 3
}
grep -q 'canary-contents-9f3a' "$WORK/read.txt" \
  || { echo "FAIL: pi's built-in read did not survive -- the colliding drydock read shadowed it";
       cat "$WORK/read.txt"; exit 1; }
no_leak "$TRANSCRIPT" "the initial transcript"
no_leak "$T_READ" "the read-guard transcript"

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
no_leak "$(find "$WORK/sessions_dead" -name '*.jsonl' | head -1)" "the dead-endpoint transcript"

# A refused rename must reach the model as an error, carrying the server's words.
rm -rf "$WORK/sessions2"; mkdir -p "$WORK/sessions2"
DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions2" --offline -e "$EXT" \
    -p "Call session_rename with the title 'PINNED'. Then tell me exactly what the tool said." \
    < /dev/null > "$WORK/out2.txt" 2>&1 || true
T2="$(find "$WORK/sessions2" -name '*.jsonl' | head -1)"
grep -q 'named by the human' "$T2" || { echo "FAIL: a refusal did not reach the model"; cat "$T2"; exit 1; }
# The text matching above is necessary but not sufficient: it would still pass
# if execute stopped throwing and just returned the same words. The thing that
# actually makes this a refusal -- pi recording the call as an error -- has to
# be checked too, or deleting `if (result?.isError) throw ...` goes unnoticed.
grep -q '"isError":true' "$T2" || { echo "FAIL: the refusal was not recorded as isError:true"; cat "$T2"; exit 1; }
no_leak "$T2" "the refusal transcript"

# The success payload is double-encoded, so details must be the DECODED outcome.
grep -q '"details":{"outcome":"renamed"' "$TRANSCRIPT" \
  || { echo "FAIL: the double-encoded result was not decoded into details"; cat "$TRANSCRIPT"; exit 1; }

# A revoked token (wrong header) must produce the stable 401 message, once.
cat > "$WORK/state/badtoken.json" <<BAD
{"mcpServers":{"drydock":{"url":"http://127.0.0.1:$PORT/mcp","headers":{"X-Drydock-Session-Token":"wrong"}}}}
BAD
rm -rf "$WORK/sessions4"; mkdir -p "$WORK/sessions4"
DRYDOCK_MCP_CONFIG="$WORK/state/badtoken.json" \
  env -u PI_CODING_AGENT pi --session-dir "$WORK/sessions4" --offline -e "$EXT" \
    -p "Say OK." < /dev/null > "$WORK/out4.txt" 2>&1 || true
# The handshake itself 401s, so the tab must start with no tools and no port in sight.
grep -q "$PORT" "$WORK/out4.txt" && { echo "FAIL: the port leaked on the 401 path"; exit 1; }
no_leak "$(find "$WORK/sessions4" -name '*.jsonl' | head -1)" "the bad-token transcript"

# The three rejection arms in call()'s catch, each reachable only once a live
# tool is registered -- unlike PINNED/badtoken above, these need a working
# handshake (config.json) so tools/call itself is the thing that fails.
run_error_case() {
  # $1 = title, $2 = session-dir name, $3 = expected substring, $4 = label
  rm -rf "$WORK/$2"; mkdir -p "$WORK/$2"
  DRYDOCK_MCP_CONFIG="$WORK/state/config.json" \
    env -u PI_CODING_AGENT pi --session-dir "$WORK/$2" --offline -e "$EXT" \
      -p "Call session_rename with the title '$1'. Then tell me exactly what the tool said." \
      < /dev/null > "$WORK/out_$2.txt" 2>&1 || true
  t="$(find "$WORK/$2" -name '*.jsonl' | head -1)"
  [ -n "$t" ] || { echo "FAIL: no session transcript written for $4"; cat "$WORK/out_$2.txt"; exit 1; }
  grep -q '"toolCall"' "$t" || {
    echo "INCONCLUSIVE: the model emitted no tool call for $4."
    exit 3
  }
  grep -qF "$3" "$t" || { echo "FAIL: $4 did not reach the model as \"$3\""; cat "$t"; exit 1; }
  no_leak "$t" "the $4 transcript"
}
run_error_case "BOOM" "sessions_boom" "session_rename: HTTP 500" "the HTTP-500 arm"
run_error_case "HANGUP" "sessions_hangup" "session_rename: transport failure" "the transport-failure arm"
run_error_case "UNAUTH" "sessions_unauth" "session_rename: this drydock session has ended" "the mid-session 401 arm"

# An accepted rename must also set pi's own session name, from the OUTCOME.
grep -q '"type":"session_info"' "$TRANSCRIPT" || { echo "FAIL: pi's session name was not set"; cat "$TRANSCRIPT"; exit 1; }
grep -q '"name":"Smoke test"' "$TRANSCRIPT" || { echo "FAIL: session name did not come from the outcome"; cat "$TRANSCRIPT"; exit 1; }
# A refused rename must NOT set it.
grep -q '"type":"session_info"' "$T2" && { echo "FAIL: a refused rename still renamed the pi session"; cat "$T2"; exit 1; }

echo "PASS: handshake, registration and tools/call all reached the mock"
