# Pi stops being the harness drydock cannot talk to

A Pi tab is named after its branch and stays that way until a human retypes
it. That is the visible symptom. The cause is larger: `PiAgentProvider`
declares `McpDelivery.NONE`, `SessionManager.mcpAccessFor` short-circuits on
`NONE` before minting anything (`SessionManager.java:216`), and so a Pi session
has no token, no endpoint and no tools. Not `session_rename` — none of them.
`session_handoff`, `review_finding`, `worktree_create` are all equally out of
reach, and the `instructions` string that tells an agent to name its own tab is
delivered at MCP `initialize`, so a Pi session is never even told the tool it
does not have exists.

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
the part that moved:

| capability | since | used for |
|---|---|---|
| `pi.registerTool()` with full context | 0.35.0 | a tool the model can call |
| async extension factory, awaited before startup | 0.38.0 | handshake before the first turn |
| `before_agent_start` returning `systemPrompt` | 0.39.0 | injecting drydock's `instructions` |
| `pi.setSessionName()` | 0.44.0 | keeping pi's `/resume` picker in step |
| `promptSnippet` gates the "Available tools" section | 0.59.0 | tools listed in the system prompt |
| `session_info_changed` event | 0.80.3 | *phase 2 only; see Landing order* |
| `--session-id <id>` (create if missing) | 0.76.0 | *adjacent; see "What was cut"* |

Everything load-bearing here was verified against a running pi rather than read
off the docs, and the appendix lists each run. Two results deserve to be in the
body because the design would be impossible without them:

1. **A raw MCP `inputSchema` is a valid `parameters` value.** `registerTool`
   documents typebox, but a plain JSON Schema object is accepted, validated,
   and the model calls the tool with correct arguments. This is not a lucky
   accident: `pi-ai`'s validator branches on the absence of the
   `Symbol.for("TypeBox.Kind")` symbol and runs a JSON-Schema coercion pass
   instead. It is what makes the proxy generic.
2. **The proxy works end to end.** An extension of exactly the shape below —
   async factory, `initialize`, `tools/list`, `registerTool` per tool, `execute`
   posting `tools/call` — drove a `session_rename` to completion against a
   byte-faithful mock of `McpServer`'s wire shape, including a `PINNED` refusal
   surfacing to the model as an error. Against a *mock*: the live server
   remains the first implementation step.

## The shape

Drydock ships a TypeScript extension. The extension is a small MCP client. On
load it reads drydock's endpoint and token from the config file drydock already
writes, calls `initialize` and `tools/list`, and registers each advertised tool
as a pi tool whose `execute` forwards to `tools/call`.

```
pi process
  └─ drydock-mcp.ts  ──POST /mcp──▶  McpServer ──▶ McpToolRouter ──▶ SessionManager
       registerTool(session_rename)      (JSON-RPC 2.0, X-Drydock-Session-Token)
       registerTool(session_handoff)
       registerTool(review_finding)  …
```

Nothing in `McpServer`, `McpToolRouter` or `McpSessionRegistry` changes. Pi
becomes an ordinary client of the transport that already exists.

**Dispatch is generic; one tool has a hook.** No tool list is enumerated in the
extension, so a tool added to the router later appears in Pi with no Pi-side
change. The single exception is `session_rename`, which gets a post-call hook
(below) that knows that tool's result shape. State this plainly rather than
claiming a purity the design then breaks.

**Names are checked before they are registered.** `_refreshToolRegistry` builds
one flat map in which extension tools overwrite built-ins by name with **no
diagnostic** (`dist/core/agent-session.js:1963-1968`), so a router tool named
`read`, `bash`, `edit` or `write` would silently replace pi's own file tools in
every Pi tab. The extension therefore refuses to register any name already
present in `pi.getAllTools()`, and reports the refusal. Namespacing to
`drydock_*` was the alternative and is worse: `McpServer.INSTRUCTIONS` names
`session_rename` and `session_handoff` literally, and that text is shared with
Claude and Codex.

### Delivery is `CONFIG_FILE`, not a new variant

Pi's needs turn out to be exactly what `McpDelivery.CONFIG_FILE` already
describes — "drydock writes an owner-only JSON config and the launch command
points at it". `mcpAccessFor` writes that file today via
`McpConfigWriter.writeFor`, owner-only, holding the endpoint URL and the
`X-Drydock-Session-Token` value. The extension reads the same file Claude is
handed.

So `McpDelivery` gains no fourth constant and `SessionManager` is untouched.
`PiAgentProvider.mcpDelivery()` changes from `NONE` to `CONFIG_FILE`.

Three comments assert the opposite today and all three become false:
`PiAgentProvider`'s Javadoc, `McpDelivery.java:15` ("Pi is here because it has
no MCP support by design"), and `MainWorkspace.java:1849` ("Claude via a config
file, Codex via config overrides, Pi not at all"). `McpAccess.java:18` is
generic and stays true.

The coupling to be honest about: that JSON is Claude-shaped
(`mcpServers.drydock.url` / `.headers`), and the extension parses those two
paths. Both ends are ours and the shape is stable, so a second writer format is
not worth its weight now.

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
envPrefix(List<String> scrub, Map<String, Path> literals, Map<String, Path> fromFiles)
```

— with both existing methods delegating to it, and the Javadoc **amended rather
than quietly contradicted**: the rule is that a *credential* is never an
argument, not that no value ever is. `literals` is typed `Path`, not `String`,
so it cannot become a general-purpose value channel, and **each value is
rendered through `shellQuote`**. That last part is not cosmetic: the state
directory is `~/Library/Application Support/ClaudeProjectManager`, so an
unquoted literal sets the variable to `/Users/x/Library/Application` and tries
to exec `Support/…`. Every default install would fail to launch. A provider
test asserts a base directory containing a space survives.

### Why `-e`, and not pi's three other loading mechanisms

pi's own docs say "Use `pi -e ./path.ts` only for quick tests" and point at
auto-discovery instead, so the choice needs a reason:

- `.pi/extensions/` (project-local) puts drydock's file in the user's worktree,
  which this repo has rejected before for the same reason, and trips pi's
  project-trust prompt.
- `~/.pi/agent/extensions/` (global) attaches drydock's bridge to the user's
  *non-drydock* pi sessions, where the config env var is absent and the tools
  are dead weight.
- The settings `extensions: [...]` array means writing to the user's own
  settings file.

`-e` is the only one scoped to the process drydock launched. Its documented
cost is that `-e` extensions cannot be `/reload`ed, which does not matter for a
file the human never edits.

## What the extension does

**Promise discipline, first, because it is a crash rule.** pi installs **no
`unhandledRejection` listener** anywhere, and its `uncaughtException` handler is
`uncaughtCrash` → `process.exit(1)`. Under Node 24's default, one unhandled
rejection anywhere in this extension **terminates the pi tab**. So: no floating
promises. Every async call site is awaited inside a `try/catch`, including
fire-and-forget-looking ones in event handlers, and a single `safe()` wrapper is
the only way async work is started. The extension deliberately does **not**
install its own `process.on('unhandledRejection')` guard — that would swallow
other extensions' failures and change pi's crash semantics for code that is not
ours.

**Load.** Read `DRYDOCK_MCP_CONFIG`. Absent or unreadable → register nothing
and return. A pi session with no drydock tools must still be a working pi
session.

**Handshake.** The factory is `async`, so pi awaits it before continuing
startup and the tools and the `before_agent_start` handler all exist before the
first turn — the turn on which "name your tab as soon as you know the work" is
supposed to land. `initialize` then `tools/list`, both under a **2-second**
budget for the pair (drydock's server is already listening, since drydock is
what launched pi; this bounds a wedged socket, not a slow start). On expiry or
any failure: register nothing, notify, return.

**Registration.** Per advertised tool, `name`, `description` and `inputSchema`
map onto `name`, `description` and `parameters` unchanged. `label` is the name
with underscores replaced by spaces and the first letter capitalised
(`session_rename` → `Session rename`). `promptSnippet` is the first line of
`description` — without it, since 0.59.0, the tools are left out of pi's
"Available tools" section entirely.

**Calls.** `execute` posts `tools/call`, forwarding its `AbortSignal` into
`fetch` so Esc cancels an in-flight call, under its own **30-second** timeout —
longer than the handshake because `worktree_create` is clone-scale work and
`session_start` joins for 30 seconds server-side.

Then three response rules, each of which the first draft of this spec got
wrong:

- **Check `res.ok` before parsing.** 401/403/405 are sent with an empty body
  (`McpServer.sendEmpty`), so a naive `res.json()` reports "Unexpected end of
  JSON input" instead of "this session has ended". 401 maps to a stable
  "this drydock session has ended" tool error.
- **Errors are thrown, not returned.** `AgentToolResult` is
  `{content, details, usage?, addedToolNames?, terminate?}` — there is **no
  `isError` field**; pi's docs are explicit that "tool `execute` errors must be
  signaled by throwing". So an MCP response with `isError: true` becomes
  `throw new Error(<the response's text>)`. Returning `{…, isError: true}`
  would make a refusal read as success with sad text in it — exactly what the
  rule exists to prevent.
- **Success payloads are double-encoded.** `McpServer.toolCallResult` puts
  `JsonWriter.write(content)` into `content[0].text` **as a string**, so the
  rename outcome is JSON inside JSON: the effective title is
  `JSON.parse(response.content[0].text).title`. Reaching for `result.title`
  yields `undefined`.

**No endpoint URL in any error text.** Drydock never logs the endpoint because
it carries the port (`SessionManager.java:231`), and a proxy's natural error
string is `POST http://127.0.0.1:54321/mcp failed`. That string would become a
pi `toolResult`, be written into the session `.jsonl`, and from there `/share`
uploads the transcript as a gist and `/export` writes it anywhere. Error text
names the tool and the status, never the URL, the headers or the parsed config.

**Keeping pi's own name in step.** After a `session_rename` that drydock
accepts, the extension calls `pi.setSessionName(effectiveTitle)` — the title
from the *outcome*, since drydock may have refused or altered it. Pi's
`/resume` picker then agrees with the tab. This is one-directional: nothing
subscribes to `session_info_changed` in phase 1, so there is no echo to
suppress and no loop to construct.

**The session must still be the session.** `/new`, `/fork` and `/resume` inside
a pi tab tear down the conversation and reload extensions from scratch, while
drydock's tab keeps the `agentSessionId` that `SnapshotClaimDiscovery` claimed
at launch. The reloaded bridge would read the same env var and the same
still-valid token, and a *different* pi conversation would then rename drydock's
tab and write its handoff brief — describing work that `pi --session <old id>`
will never reopen. So the extension records the session file at load and, on a
`session_start` whose reason is not `startup` or `resume`, unregisters its tools
and notifies. Drydock's tab is bound to one pi conversation; when that stops
being true, the bridge stops.

**Where a failure becomes visible.** `ctx.ui.notify(…, "warn")` for anything
after load — pi is a full-screen TUI and writing to stderr mid-session injects
raw bytes into the display pi is drawing. stderr is for the pre-TUI load path
only. This matters because six distinct failure modes degrade silently to
today's Pi, and without a notification the human cannot tell a working bridge
from a broken one.

## Version gating

`PiCapabilities`, a provider-internal record, exposes `supportsBridge()`: true
at **≥ 0.80.3**.

That number is a deliberate simplification, and the honest reasoning is worth
recording because the first draft got it wrong twice. The APIs the extension
actually uses bind a floor of roughly **0.44.0**, and the bridge was *observed
working* on 0.55.4 and loading on 0.50.0. But 0.80.3 is where
`session_info_changed` arrives (phase 2 needs it) and where pi fixed extension
tool changes applying "without dropping `before_agent_start` system-prompt
overrides" — the exact pair this design leans on. Supporting a range nothing in
drydock's CI will ever exercise buys compatibility we cannot claim. One number,
tested at the top of it.

Two mechanics the first draft left open:

- **Where it is probed, and what it costs.** `probeCapabilities()` has **no
  production caller today** — only tests. Putting the gate on the launch path
  would add a `pi --version` spawn, with `PiVersionProbe`'s 30-second timeout,
  to every create *and* every resume. So the version is probed **once per app
  run**, memoised in the provider, on the same background path that writes the
  extension file.
- **`"unknown"` is below the gate.** `PiVersionProbe.probe` returns the literal
  string `"unknown"` on a missing executable, non-zero exit, timeout or
  interrupt. An unparseable version means no `-e` flag and no tools — failing
  conservatively, as `detectCaps()` does for Claude.

Note this is version comparison, which `ClaudeCapabilities` documents itself as
*rejecting* ("rather than assumed from the version string, since flag
availability does not necessarily track a simple version comparison"). Claude
can grep `--help` for a flag; there is no `pi --help` line that says "extension
tools work". The precedent is the shape of the record, not the detection
method, and the difference is deliberate.

### Where the extension file lives

`PiExtensionInstaller` writes the extension — a Java text block — to
`<stateDirectory>/pi/drydock-mcp.ts`.

- **Written once per app run, lazily, on the first Pi launch.** There is no
  startup seam that would reach it: `ClaudeHookInstaller` is invoked only
  through `DrydockApplication.installSessionActivityHooks`, which iterates
  `agentRegistry.activity(kind)`, and `PiAgentProvider.activity()` is empty.
  `AgentProvider.init` is explicitly *not* among the SPI methods permitted to
  block. `buildCreateCommand`/`buildResumeCommand` are, and already run off the
  FX thread, so the installer runs there behind a write-once flag rather than
  adding an SPI hook for one provider.
- **Atomically, modelled on `McpConfigWriter.writeAtomically`** — temp file plus
  rename. Not `ClaudeHookInstaller`, which the first draft cited: that one is a
  plain `Files.writeString` under the umask. Without the rename, a second tab
  launching while a first is `jiti`-loading the same path reads a truncated
  file, and the feature this design exists to deliver disappears
  intermittently.
- **Owner-only**, file and directory (`rw-------` in `rwx------`), like
  `mcp/`. The file holds no secret, but a `-e` path that loads with no trust
  prompt is arbitrary code execution in every Pi tab.
- **On failure, the flag is omitted** and the session launches with no drydock
  tools — logged, not fatal, exactly as `ClaudeActivityReporter` gates
  `settingsFile()` behind an `installed` flag.

One implementation tax worth naming: TypeScript in a Java text block means
every backslash is doubled, as `ClaudeHookInstaller.HOOK_SCRIPT` already does
for its `sed` expressions.

## Landing order

**Phase 1 — the bridge.** `McpDelivery.CONFIG_FILE`, the `AgentCommands`
overload, `PiCapabilities`, `PiExtensionInstaller`, the extension with generic
proxying, the `instructions` injection, and the one-directional
`setSessionName`. This delivers the goal: a Pi tab names itself, and Pi reaches
handoff and review.

**Phase 2 — relaying `/name` into drydock (deferred, and it needs a router
change first).** Forwarding the human's `/name` looked like a detail of the
bridge and is not; it is the highest-risk part of the first draft, and it is
optional relative to the goal:

- It would spend the *agent's* rename budget. `chargeRename` charges refused
  outcomes too and `MAX_RENAMES_PER_SESSION` is 20, so twenty human `/name`
  edits permanently disable the agent's ability to title its own tab. The
  budget exists "to bound an agent looping" and has nothing to say about a
  person renaming. It needs the exemption `applyHumanHandoff` already has for
  the human handoff path.
- A refusal has nowhere to go. The relay is an event handler, not a tool call,
  so `PINNED` and `COLLIDED` reach neither the model nor the human, and pi's
  picker and the tab diverge silently — the exact failure the sync exists to
  prevent.
- pi's name space is strictly larger than drydock's. pi sanitises with
  `replace(/[\r\n]+/g, " ").trim()`; `PromptSafety.checkSessionTitle` refuses
  double quotes, control/format/surrogate code points, stacked combining marks
  and anything over 60 code points. `/name Fix the "auth" bug` is legal in pi
  and refused by drydock.
- `SessionInfoChangedEvent.name` is `string | undefined` — undefined when the
  name is *cleared* — and `session_rename` requires a non-empty title.
- The suppression needed to stop it looping is not a remembered string.
  `setSessionName` emits the *sanitised* name via `getSessionName()`, and emits
  fire-and-forget after returning, so two names in flight oscillate until the
  budget is gone.

None of that is unsolvable. All of it is a second spec.

## Failure modes

| when | behaviour |
|---|---|
| pi older than 0.80.3, or version `"unknown"` | launch unchanged; no flag, no tools |
| extension file write failed | flag omitted; session launches without tools; logged |
| `DRYDOCK_MCP_CONFIG` unset/unreadable | extension registers nothing; session fine |
| handshake fails or exceeds 2s | extension registers nothing; notify; session fine |
| a tool name collides with a pi built-in | that tool is not registered; notify; others register |
| `tools/call` fails or times out | that call throws to the model; other tools keep working |
| token revoked (session ended) | 401 → "this drydock session has ended" tool error |
| human runs `/new`, `/fork`, `/resume` in the tab | tools unregister; notify; no cross-session writes |

The invariant, restated now that it is defensible: **a broken bridge degrades to
today's Pi**. It says so out loud via `ctx.ui.notify`, and it never takes the
tab down — which is a property of the promise discipline above, not a wish.

## Security

- The token never enters argv. Only paths do, and the config file is
  `rw-------` in an `rwx------` directory.
- The token is attribution, not isolation — `McpSessionRegistry` says so, and
  nothing here widens that. `DRYDOCK_MCP_CONFIG` is inherited by subprocesses pi
  spawns, but they run as the same uid that can already read the file, and
  Codex's delivery already puts the token itself in an inherited variable.
- Nothing attacker-controlled reaches the system prompt or the tool list:
  `McpServer.INSTRUCTIONS` is a `private static final` text block and every tool
  description in `toolDescriptors()` is a string literal. No session name, repo
  name, diff text or finding body is interpolated into either.
- The endpoint URL must not reach a tool result, for the transcript-to-gist
  path described above.
- Fan-out: `Spawn.FORBIDDEN` is enforced server-side against the calling token
  in `worktree_create` and `session_start`, so a proxied call is refused by the
  same code that refuses Claude's. The design does **not** claim depth 1 is
  absolute: `SessionManager` re-mints `Spawn.ALLOWED` on resume and documents
  that "depth 1 is a property of the LAUNCH, not of the session". What changes
  here is reach — an agent-started Pi session previously had no token at all,
  and now one human resume promotes it. That is a pre-existing limitation Pi now
  shares, stated rather than asserted away.
- `PromptSafety` validation is server-side and unchanged.
- The extension is regenerated from a constant, so a tampered copy survives at
  most until the next app run. It is not signed; a process that can rewrite it
  as this user can already do worse.

## Testing

Java, unit:

- `PiAgentProviderTest` — command shape with and without `McpAccess`, on create
  and both resume forms; no flag below the version gate or on `"unknown"`; the
  token never appearing literally in any built command; **a state directory
  containing a space surviving into the command**. Note the three existing
  `endsWith("pi")` / `endsWith("pi --session '…'")` / `endsWith("pi --resume")`
  assertions all break and become `contains`.
- `PiCapabilitiesTest` — the 0.80.3 boundary, `"unknown"`, junk and
  pre-release strings.
- `PiExtensionInstallerTest` — file and directory permissions, atomic replace,
  write-once per app run, and that a write failure omits the flag rather than
  failing the launch. (The first draft's test line asserted the opposite of its
  own design; the design wins.)

What is **not** automated, stated plainly rather than waved at:

- The extension is TypeScript that is never **type-checked** — pi loads it
  through `jiti`, which strips types without checking them. `ToolDefinition.
  parameters` is typed `TParams extends TSchema`, so passing a raw JSON Schema
  is a type error that only the absence of a compiler hides. The mechanism is
  verified empirically across 0.50.0–0.84.1 and is a runtime guarantee, not a
  compile-time one. Adding a JS toolchain to this repo for one file is not
  worth it; the trade is recorded, not hidden.
- The whole proxy. There is no `package.json` anywhere outside `third_party/`.
  Bring-up is a scripted run against a live drydock endpoint — that is an
  exercise, not a regression test, and nothing re-runs it.

`docs/manual-terminal-checklist.md` gains entries for: a Pi tab renaming itself
during real work; a refused rename surfacing to the model; a handshake failure
notifying rather than hanging; `/new` inside a Pi tab unregistering the tools;
and — the one gap the appendix's evidence does not cover — **`-e` loading with
no trust prompt in a real interactive tab in a freshly created worktree**. Every
appendix run was `--offline -p`, which never enters the TUI where `project_trust`
is live.

## What was cut

**Reading the title out of pi's transcript.** Pi's `--name`/`/name`/
`setSessionName` all append a `session_info` record to the session `.jsonl` that
`PiSessionStore` already opens, so drydock could tail for it with no token and
no HTTP. Cut because it solves only the title, cannot distinguish an agent
rename from a human one, and gives the agent no error round-trip — the tool
would report success while `applyAgentRename` silently refused a `PINNED` or
`COLLIDED` write.

**A shell CLI the agent runs via bash.** Pi exports `PI_SESSION_ID` and
`PI_SESSION_FILE` into every bash tool call. Harness-agnostic and tempting for a
future agent with no extension API, but it puts drydock calls in the transcript
as shell noise and depends entirely on prompt compliance.

**A transcript marker the agent prints.** Zero install; pollutes visible output
and relies on a model formatting a sentinel correctly every time.

**`--mode rpc`.** Drydock hosts a terminal; it does not drive pi
programmatically.

**A process-level `unhandledRejection` guard in the extension.** Suggested in
review as insurance against the crash rule above. Rejected: it would swallow
other extensions' failures and change pi's crash semantics for code that is not
drydock's. Discipline at every call site, plus a manual check, instead.

**Moving Pi to `PRESET` ids.** 0.84.1's `--session-id <id>` creates the session
if missing, which would retire snapshot-and-claim discovery for Pi and make the
tab-to-conversation binding exact — which is the same binding the `/new` guard
above works around. A real improvement, and its own spec.

## Appendix: what was actually run

Against pi 0.84.1 at `/usr/local/bin/pi` unless noted, in a throwaway
`--session-dir`, `--offline`, non-interactive `-p`, with a local model.

- `pi --name "Hello Title"` → first-line `session` record followed by
  `{"type":"session_info",…,"name":"Hello Title"}`.
- An extension registering `session_rename` with typebox `Type.Object`
  parameters → model called it; `setSessionName` landed a `session_info` record.
- The same with **raw JSON Schema** parameters → `session_rename {"title":
  "Raw schema works"}` recorded, result returned. Reproduced independently; the
  supporting `pi-ai` code path was read at 0.69.0 and 0.73.1, and the 0.55.4
  equivalent used AJV, which takes plain JSON Schema natively.
- `-e /tmp/<file>.ts` from a cwd elsewhere, no `--approve` → loaded, no prompt.
  Reproduced across seven runs.
- `pi.on("totally_bogus_event_name", …)` → returns normally. Also confirmed on
  0.55.4 and 0.50.0.
- `process.env` readable inside the extension; `fetch` and `node:*` available.
- **The full proxy against a mock `McpServer`** (wire shape built from
  `McpServer.java:404-448` and `McpToolRouter.descriptor`): handshake,
  registration, `tools/call`, a `PINNED` refusal thrown and relayed to the model
  verbatim, `before_agent_start` injection observed in the
  `before_provider_request` payload on every turn, and `setSessionName` inside
  `execute` landing a record between the `toolCall` and the `toolResult`.
  Missing config and dead-port config both degraded without killing the run.
- Pi 0.55.4 and 0.50.0 installed side by side to test the floor: both load the
  extension and register tools; 0.55.4 completed a full tool call.

Still undemonstrated, and first to build: the proxy against a **running**
drydock with a minted token. Also undemonstrated: `-e` loading with no trust
prompt in an interactive tab (all runs above were non-interactive), which is why
it is on the manual checklist.

## What the adversarial review changed

Three reviewers, one round, distinct lenses. Twenty-nine findings after
de-duplication; these changed the design rather than the prose:

- **`isError` on a returned tool result does nothing** — pi has no such field;
  errors must be thrown. The first draft specified an API that does not exist.
- **Success payloads are double-encoded**; `outcome.title` needed a `JSON.parse`
  the draft never mentioned.
- **An unhandled rejection kills the pi process.** The draft's stated invariant
  ("never a session that will not start") was false; promise discipline is now
  a rule with a named consequence.
- **Proxied tool names can silently replace pi's built-ins**, which the draft's
  own "any new router tool appears automatically" claim made worse rather than
  better.
- **The `/name` relay was moved to phase 2**, once review established it spends
  the agent's rename budget, has no surface for a refusal, accepts names
  drydock refuses, and cannot be de-echoed with a remembered string.
- **The installer had no seam.** Pi has no `ActivityReporter`, so nothing would
  ever have called it; it is now a lazy write-once on the launch path.
- **"No temp file" specified a data race** — the atomic rename is back, and the
  cited model corrected from `ClaudeHookInstaller` (umask, non-atomic) to
  `McpConfigWriter`.
- **The literals map must `shellQuote`**, or every default install fails on the
  space in `Application Support`.
- **The version gate's stated reason was false** in both halves; the floor moved
  to 0.80.3 with a reason that survives inspection, and `"unknown"` was given a
  side.
- **Three stale comments**, not one, assert Pi has no MCP. A fourth claimed in
  review (`McpAccess.java:18`) was checked and is generic — it stays.
