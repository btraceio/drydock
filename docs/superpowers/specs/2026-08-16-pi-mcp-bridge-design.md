# Pi stops being the harness drydock cannot talk to

A Pi tab is named after its branch and stays that way until a human retypes
it. That is the visible symptom. The cause is larger: `PiAgentProvider`
declares `McpDelivery.NONE`, `SessionManager.mcpAccessFor` short-circuits on
`NONE` before minting anything, and so a Pi session has no token, no endpoint
and no tools. Not `session_rename` — none of them. `session_handoff`,
`review_finding`, `worktree_create` are all equally out of reach, and the
`instructions` string that tells an agent to name its own tab is delivered at
MCP `initialize`, so a Pi session is never even told the tool it does not have
exists.

The provider's Javadoc explains the `NONE` as permanent:

> Pi has no MCP support at all, and by design: its README says so outright
> ("No MCP. Build CLI tools with READMEs, or build an extension that adds MCP
> support") […] So this is not a gap to wait out.

That is still true, and this design does not wait it out. It takes the second
half of pi's own sentence at its word and builds the extension.

## What the spike could not have known

`docs/superpowers/specs/2026-07-24-pi-spike-findings.md` was written against pi
**0.71.1**, packaged as `@mariozechner/pi-coding-agent`. Installed today:
**0.84.1**, packaged as `@earendil-works/pi-coding-agent`. The extension API is
the part that moved, and the pieces this design needs are all older than the
spike — they were simply not what the spike was looking for:

| capability | since | what it gives us |
|---|---|---|
| `pi.registerTool()` with full context | 0.35.0 | a tool the model can call |
| `promptSnippet` / `promptGuidelines` | 0.55.4 | tool text in the system prompt |
| dynamic registration after startup | 0.55.4 | register once the endpoint answers |
| `session_info_changed` event | 0.80.3 | observe `/name` typed by the human |
| `--session-id <id>` (create if missing) | 0.76.0 | *adjacent; see "What was cut"* |

Four facts were verified against the installed 0.84.1 rather than read off the
docs. Each was a load-bearing assumption, and each is reproducible from the
appendix:

1. **A raw MCP `inputSchema` is a valid `parameters` value.** `registerTool`
   documents typebox, but a plain JSON Schema object was accepted, validated,
   and the model called the tool with correct arguments. This is what makes a
   *generic* proxy possible — no per-tool code, no schema translation.
2. **`-e <absolute path outside the project>` loads without a trust prompt.**
   No `--approve`, no interactive gate, in a non-interactive run.
3. **`pi.on()` tolerates an unknown event name** rather than throwing, so a
   forward-compatible subscription costs nothing on an older pi.
4. **Extensions read `process.env`**, and `fetch` plus `node:*` built-ins are
   available.

## The shape

Drydock ships a TypeScript extension. The extension is a small MCP client. On
load it reads drydock's endpoint and token from the config file drydock
already writes, calls `initialize` and `tools/list`, and registers every tool
the server advertises as a pi tool whose `execute` forwards to `tools/call`.

```
pi process
  └─ drydock-mcp.ts  ──POST /mcp──▶  McpServer ──▶ McpToolRouter ──▶ SessionManager
       registerTool(session_rename)      (JSON-RPC 2.0, X-Drydock-Session-Token)
       registerTool(session_handoff)
       registerTool(review_finding)  …
```

Nothing in `McpServer`, `McpToolRouter` or `McpSessionRegistry` changes. Pi
becomes an ordinary client of the transport that already exists, which is the
whole point of choosing this over reading titles out of pi's transcript: the
tool set is not enumerated anywhere in the extension, so a tool added to the
router next month appears in Pi with no Pi-side change at all.

### Delivery is `CONFIG_FILE`, not a new variant

Pi's needs turn out to be exactly what `McpDelivery.CONFIG_FILE` already
describes — "drydock writes an owner-only JSON config and the launch command
points at it". `mcpAccessFor` writes that file today via
`McpConfigWriter.writeFor`, owner-only, with the endpoint URL and the
`X-Drydock-Session-Token` header value in it. The extension reads the same
file Claude is handed.

So `McpDelivery` gains no fourth constant and `SessionManager` is untouched.
`PiAgentProvider.mcpDelivery()` changes from `NONE` to `CONFIG_FILE`, and its
Javadoc changes from "this is not a gap to wait out" to a description of the
bridge.

The coupling to be honest about: that JSON is Claude-shaped
(`mcpServers.drydock.url` / `.headers`), and the extension will parse those two
paths. Both ends are ours and the shape is stable, so a second writer format
is not worth its weight now. If it ever chafes, `McpConfigWriter` grows a
neutral variant and the extension reads that instead.

### The launch command

```
env -u PI_CODING_AGENT DRYDOCK_MCP_CONFIG='<state>/mcp/<id>.json' \
  pi -e '<state>/pi/drydock-mcp.ts'
```

and the same prefix and `-e` on the resume forms (`pi --session <id>` /
`pi --resume`).

The config path is on the command line, and that is deliberate: **the token is
not**. `AgentCommands.envPrefixFromFiles` exists precisely because a token in
argv is readable by `ps` for the life of the tab's `login` parent — a live fork
run proved it, 21 seconds after launch. A *path* is not a secret, which the
same Javadoc says outright, and Codex already puts the endpoint URL on its
command line.

That Javadoc does, however, claim `envPrefixFromFiles` is "deliberately the
only way to set a variable here". Pi needs to set one variable to a literal
path, so `AgentCommands` gains one general form —

```java
envPrefix(List<String> scrub, Map<String, String> literals, Map<String, Path> fromFiles)
```

— with both existing methods delegating to it, and the Javadoc **amended
rather than quietly contradicted**: the rule is that a *credential* is never an
argument, not that no value ever is. Getting that wording wrong is how the next
person puts a token back on the command line. The `literals` map is documented
as for non-secret values only, and the provider tests assert no built command
contains the token.

### Where the extension file lives

`PiExtensionInstaller`, modelled directly on `ClaudeHookInstaller`: the
extension source is a Java text block, written to `<stateDirectory>/pi/
drydock-mcp.ts` on startup, rewritten unconditionally every launch, owner-only.
No jar-resource extraction, no temp file, no silent fallback — a font-scaling
bug in this repo already taught us what a temp file with a silent fallback
costs. If the write fails, the flag is omitted and the session launches with no
drydock tools, which is what happens today anyway.

## What the extension does

**Load.** Read `DRYDOCK_MCP_CONFIG`. Absent or unreadable → register nothing
and return. A pi session with no drydock tools must still be a working pi
session; this extension may never be the reason a tab fails to start.

**Handshake.** `initialize`, then `tools/list`. Both on the load path, both
with a short timeout. Any failure → register nothing, log to stderr, return.
The server is on `127.0.0.1` and already running, so this is a guard rather
than an expected path.

**Registration.** For each advertised tool: `name`, `description` and
`inputSchema` map onto `name`, `description` and `parameters` unchanged.
`label` is derived from the name. `execute` POSTs `tools/call` with the same
arguments and returns the server's `content` array as the tool result, mapping
`isError: true` onto a pi tool error so a refusal reads as a refusal rather
than as success with sad text in it.

**Instructions.** The `instructions` string from `initialize` is what makes an
agent name its own tab, and it must reach the model. It is appended to the
system prompt via `before_agent_start` (`systemPrompt: event.systemPrompt +
"\n\n" + instructions`), chained so other extensions' contributions survive.
This keeps `McpServer.INSTRUCTIONS` the single source of that text for all
three harnesses. Per-tool `promptGuidelines` was the alternative and is worse:
the text names two tools, so it would either duplicate or fragment.

**Keeping pi's own name in sync.** After a `session_rename` call that drydock
accepts, the extension calls `pi.setSessionName(effectiveTitle)` — the title
from the *outcome*, not the one requested, since drydock may have refused.
Pi's `/resume` picker then agrees with the tab. This must not echo: setting the
name fires `session_info_changed`, so the extension remembers the last name it
set and ignores the event that matches it.

**Relaying `/name`.** A human typing `/name` inside pi fires the same event.
The extension forwards it as an ordinary `session_rename` call. It does **not**
claim human intent, and so does not pin: the extension's word is
indistinguishable from the agent's on that channel, and a pin an agent can
forge is a pin that stops meaning "a human chose this". Drydock's own inline
rename stays the only thing that pins.

## Version gating

`PiCapabilities`, mirroring `ClaudeCapabilities`: provider-internal, derived
from the `pi --version` string `PiVersionProbe` already reads, exposing
`supportsExtensionTools()` — true at **≥ 0.55.4**, the release that made
dynamic `registerTool` and the prompt fields work. Below that, no `-e` flag and
no `DRYDOCK_MCP_CONFIG`; the session launches exactly as it does today.

`session_info_changed` (0.80.3) is *not* part of that gate. Denying every tool
to a 0.7x pi over one convenience would be disproportionate, and fact 3 above
says the subscription is inert on a pi that does not know the event. Between
0.55.4 and 0.80.3, Pi gets the full tool set and no `/name` relay.

`AgentCapabilities` is not involved and does not change. Its three fields are
`supportsRemote`, `supportsResume` and `version`; MCP delivery is not among
them, by the record's own design note that provider-internal flag detail stays
inside the provider. Pi keeps returning `new AgentCapabilities(false, true,
version)`.

## Failure modes

| when | behaviour |
|---|---|
| pi older than 0.55.4 | launch unchanged; no tools; no flag on the command line |
| extension file write failed | flag omitted; session launches without tools |
| `DRYDOCK_MCP_CONFIG` unset/unreadable | extension registers nothing, session fine |
| `initialize`/`tools/list` fails | extension registers nothing, session fine |
| `tools/call` fails mid-session | that tool call errors; other tools keep working |
| token revoked (session ended) | calls 401; tools error; nothing crashes |

The invariant across all of them: **a broken bridge degrades to today's Pi**,
never to a session that will not start.

## Security

- The token never enters argv. Only the config path does, and the file is
  `rw-------` in an `rwx------` directory (`McpConfigWriter`).
- The token is attribution, not isolation — `McpSessionRegistry` says so, and
  nothing here widens that. A pi extension runs as the same uid that could
  already read the file.
- Fan-out depth stays 1. `Spawn.FORBIDDEN` is enforced server-side in the
  router against the calling token, so a proxied `session_start` is refused by
  exactly the same code path that refuses Claude's.
- `PromptSafety` validation is server-side and unchanged; a title arriving via
  Pi is validated identically to one arriving via Claude.
- The extension is regenerated from a constant on every launch, so a tampered
  copy on disk survives at most until the next start. It is not signed; a
  process that can rewrite it as this user can already do worse.

## Testing

Java side, unit:

- `PiAgentProviderTest` — command shape with and without `McpAccess`, on
  create and both resume forms; the flag absent below the version gate; the
  token never appearing literally in any built command.
- `PiCapabilitiesTest` — version-string parsing at the 0.55.4 boundary,
  including junk and pre-release strings.
- `PiExtensionInstallerTest` — file written, permissions owner-only, rewrite
  idempotent, failure surfaces rather than silently disabling.

Extension side: the proxy is TypeScript and this repo has no JS test harness.
Rather than invent one, it is verified by a scripted end-to-end run against a
live drydock endpoint, which is also the first implementation step — everything
above rests on the proxy actually completing a `tools/call`, and that is the
one claim in this document not yet demonstrated. The appendix's method works:
`pi --offline -e <ext> -p "<instruction naming the tool>"`, then read the
`toolCall` and `toolResult` records back out of the session `.jsonl`.

Manual, on-screen: a Pi tab renaming itself during real work, and `/name`
inside pi moving the tab.

## What was cut

**Reading the title out of pi's transcript** (option A in the brainstorm).
Pi's `--name`/`/name`/`setSessionName` all append a `session_info` record to
the session `.jsonl` that `PiSessionStore` already opens, so drydock could tail
for it with no token and no HTTP. It was cut because it solves only the title,
cannot distinguish an agent rename from a human one, and gives the agent no
error round-trip — the tool would report success while `applyAgentRename`
silently refused a `PINNED` or `COLLIDED` write. Its ingest reader would have
been deleted by this design. The one piece worth keeping — calling
`setSessionName` so pi's picker agrees — is kept.

**A shell CLI the agent runs via bash.** Pi exports `PI_SESSION_ID` and
`PI_SESSION_FILE` into every bash tool call, so a script could identify its own
session cleanly. Harness-agnostic and genuinely tempting for a future agent
with no extension API, but it puts drydock calls in the transcript as shell
noise and depends entirely on prompt compliance.

**A transcript marker the agent prints.** Zero install, and it would work.
Also pollutes visible output and relies on a model formatting a sentinel
correctly on every attempt.

**`--mode rpc`.** Drydock hosts a terminal; it does not drive pi
programmatically.

**Moving Pi to `PRESET` ids.** 0.84.1's `--session-id <id>` creates the session
if missing, which would retire the snapshot-and-claim discovery dance for Pi
entirely. It is a real and separate improvement, it makes the session→file
mapping exact, and it is nothing to do with the bridge. Its own spec.

## Appendix: what was actually run

All against pi 0.84.1 at `/usr/local/bin/pi`, in a throwaway `--session-dir`,
`--offline`, non-interactive `-p`, with a local model.

- `pi --name "Hello Title"` → first-line `session` record followed by
  `{"type":"session_info",…,"name":"Hello Title"}`.
- An extension registering `session_rename` with `parameters` as a typebox
  `Type.Object` → model called it; `setSessionName` landed a `session_info`
  record.
- The same with `parameters` as a **raw JSON Schema object** → tool call
  recorded as `session_rename {"title": "Raw schema works"}`, result
  `GOT:{"title":"Raw schema works"}`. This is fact 1.
- The same extension loaded as `-e /tmp/<file>.ts` from a cwd elsewhere, with
  no `--approve` → loaded, no prompt. This is fact 2.
- `pi.on("totally_bogus_event_name", …)` → returned normally. This is fact 3.
- `process.env.DRYDOCK_MCP_URL` read inside the extension → value present.
  This is fact 4.

Not yet demonstrated, and first to build: the proxy completing a real
`tools/call` against a running `McpServer` with a minted token.
