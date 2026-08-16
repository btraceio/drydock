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
every Pi tab. Nothing collides *today* — pi's built-ins are `read`, `bash`,
`edit`, `write`, `grep`, `find`, `ls` and drydock's are all `session_*`,
`review_*`, `worktree_create`, `repos_list`, `sessions_list` — so this guards
the generic property above, which is precisely the property that would let a
future router tool break every Pi tab without anyone touching Pi. The extension
refuses to register any name already present in `pi.getAllTools()` and reports
the refusal.

The check is best-effort against built-ins at bind time, not a guarantee: pi
resolves extension-vs-built-in as last-wins but extension-vs-extension as
**first**-wins (`dist/core/extensions/runner.js:281-291`), and mid-session
registration is a supported pattern, so another extension can still shadow a
drydock tool after the check passed. Namespacing to `drydock_*` was the
alternative and is worse: `McpServer.INSTRUCTIONS` names `session_rename` and
`session_handoff` literally, and that text is shared with Claude and Codex.

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

**Two crash rules, first, because everything else assumes the tab is alive.**

*The factory must never throw or reject.* An extension named with `-e` is not
best-effort: when its factory fails, pi prints `Failed to load extension …` plus
`Hint: Start without extensions using "pi -ne"` and **exits 1**. Verified — the
tab does not start. (Auto-discovered extensions are collected as non-fatal
diagnostics instead; `-e` is the strict path, which is the cost of the scoping
choice made above.) So the entire factory body — `readFile`, `JSON.parse`, both
`fetch` calls — sits inside one `try { … } catch { return }`. This is the
invariant, not a style note: without it, a wedged socket does not degrade the
bridge, it stops the human's session from opening. In the verified run no
session `.jsonl` was written before the exit, so drydock's discovery claims
nothing for a process that died; that was checked non-interactively only.

*No floating promises.* pi installs **no `unhandledRejection` listener**
anywhere, and its `uncaughtException` handler is `uncaughtCrash` →
`process.exit(1)`. Since Node 15 the default routes an unhandled rejection
there, so one floating rejection anywhere in this extension also terminates the
tab. Every async call site is awaited inside a `try/catch`, including
fire-and-forget-looking ones in event handlers, and a single `safe()` wrapper is
the only way async work is started.

On 0.84.1 that discipline is provably sufficient for the code we do not own:
pi voids exactly two emits of extension handlers (`agent-session.js:1288` and
`:2290`, the latter inside `setSessionName`), and `ExtensionRunner.emit` cannot
reject — its context object is lazy getters, and every handler call is inside a
per-handler try/catch. That is a fact about one pi version, and the thing a
future release could invalidate, so it belongs here rather than in a reviewer's
notes.

The extension deliberately does **not** install its own
`process.on('unhandledRejection')` guard — that would swallow other extensions'
failures and change pi's crash semantics for code that is not ours.

**Load.** Read `DRYDOCK_MCP_CONFIG`. Absent or unreadable → register nothing
and return. A pi session with no drydock tools must still be a working pi
session.

**Handshake in the factory; registration in `session_start`.** This split is
forced, and getting it wrong is the single easiest way to build a bridge that
never registers anything. pi installs *throwing stubs* for every action method
during extension load — `getAllTools`, `getActiveTools`, `setActiveTools`,
`setSessionName` all raise "Extension runtime not initialized. Action methods
cannot be called during extension loading"
(`dist/core/extensions/loader.js:134-155`), and `ctx.ui` does not exist in the
factory at all, which is where the collision check and every `notify` in the
failure table would otherwise live. `registerTool` is explicitly exempt and
valid during load, but the *check* it needs is not.

So: the `async` factory does the network work only — `initialize` then
`tools/list`, both under a **2-second** budget for the pair (drydock's server is
already listening, since drydock is what launched pi; this bounds a wedged
socket, not a slow start) — and stashes the result, or stashes the failure.
A `pi.on("session_start")` handler then does everything that needs a live
runtime: the collision snapshot, the registration, recording the session file,
and any notification, including a **deferred** one for a load-path failure that
had no `ctx.ui` to report itself at the time.

That handler runs after `bindCore`, so the runtime is live, and still before the
first user turn, so the tools and the `before_agent_start` handler exist for the
turn on which "name your tab as soon as you know the work" is supposed to land.
Registering there also makes pi 0.55.4's "tools registered in `session_start`
and later handlers refresh immediately, without `/reload`" load-bearing, which
the version table records.

**Order inside the handler matters.** Snapshot `pi.getAllTools()` names *before*
registering anything. Afterwards the collision is invisible: `_refreshToolRegistry`
resolves extension-over-built-in by overwriting, so a post-registration
`getAllTools()` shows one `read` — ours — and a name comparison finds nothing
wrong.

On expiry or any failure in either half: register nothing, report, return.

**Registration.** Per advertised tool, `name`, `description` and `inputSchema`
map onto `name`, `description` and `parameters` unchanged. `label` is the name
with underscores replaced by spaces and the first letter capitalised
(`session_rename` → `Session rename`). `promptSnippet` is the first line of
`description` — without it, since 0.59.0, the tools are left out of pi's
"Available tools" section entirely.

**Calls.** `execute` posts `tools/call` under a **45-second** timeout, combined
with the tool's own `AbortSignal` via `AbortSignal.any([signal,
AbortSignal.timeout(45_000)])` — a separate timer racing the fetch is exactly
the floating-promise shape the discipline rule bans.

45 rather than 30, because 30 is the *server's* ceiling, not a margin over it:
`START_SESSION_TIMEOUT_SECONDS` and `HANDOFF_TIMEOUT_SECONDS` are both 30 and
`RENAME_TIMEOUT_SECONDS` is 25, so a 30-second client budget expires at best
simultaneously with the server's own deadline and aborts calls the server is
about to answer.

**An abort is not a failure, and neither is a timeout.** Aborting the fetch
cancels nothing server-side: `applyAgentRename` and `storeHandoff` have already
mutated state under the store's lock by the time a join times out. So an abort
propagates unchanged rather than becoming a tool error — otherwise a human
pressing Esc writes a spurious "session_rename failed" into the transcript — and
a timeout is reported to the model as *unknown outcome, do not retry blindly*,
because `chargeRename` charges every attempt including refused and timed-out
ones.

Then three response rules, each of which the first draft of this spec got
wrong:

- **Check `res.ok` before parsing.** 401/403/405 are sent with an empty body
  (`McpServer.sendEmpty`), so a naive `res.json()` reports "Unexpected end of
  JSON input" instead of "this session has ended". 401 maps to a stable
  "this drydock session has ended" tool error. Post the config's `url`
  **verbatim**: `originAllowed` 403s any `Host` or `Origin` that is not exactly
  `{http://,}{127.0.0.1,localhost}:<port>`, so a canonicalised host yields a
  bodiless 403 that the `res.ok` rule would otherwise turn into a generic error
  with no explanation.
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

**Never rethrow a caught transport error; construct the message.** Drydock never
logs the endpoint because it carries the port (`SessionManager.java:231`), and a
tool error becomes a pi `toolResult`, is written into the session `.jsonl`, and
from there `/share` uploads the transcript as a private gist and `/export`
writes it anywhere.

A rule saying "don't name the URL" is not enough, because Node hands you the
port without being asked. A failed `fetch` has `message === "fetch failed"` —
safe, and the only safe string undici gives you — but `err.cause.message` is
`connect ECONNREFUSED 127.0.0.1:59999`, with `port` as a field, and for a
`localhost` URL the cause is an `AggregateError` with one such entry per address
family. `throw e`, `` `${e}` `` on a cause chain, and `console.error(e)` all leak
it, and so does the obvious act of making "fetch failed" informative by folding
the cause into the message. pi's own error path drops `cause`
(`pi-agent-core/dist/agent-loop.js:476` uses `error.message`), so a constructed
`Error` is transcript-safe. The rule is therefore mechanical: **never read
`err.cause`**; error text names the tool and the HTTP status and nothing else.

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
will never reopen.

**The discriminator is the session file, not the reason code.** The first draft
of this paragraph said "any `session_start` whose reason is not `startup` or
`resume`", which whitelists the one case it exists to catch: in-TUI `/resume` is
`reason: "resume"` and switches conversations, while drydock's own resume form —
`pi --session <id>`, a fresh process — fires `reason: "startup"`. Verified:
`pi --session <existing id> -p …` logs `reason=startup, previousSessionFile=
undefined`. A reason allow-list also tears the bridge down on `/reload`, which
changes nothing. So the extension records `sessionManager.getSessionFile()` when
it registers, and on any later `session_start` compares: **same file, carry on;
different file, stand down.**

Standing down means dropping the tools from the active set and stopping the
`instructions` injection — the latter because `McpServer.INSTRUCTIONS` otherwise
keeps telling the model to call `session_rename` for the rest of the session,
about a tool that is no longer there. Note "unregister" is not available:
`ExtensionAPI` has `registerTool` and `setActiveTools` and **no
`unregisterTool`** anywhere in `dist`. `setActiveTools` takes the whole active
list, so standing down means name-filtering the current `getActiveTools()`, not
passing a list of our own — otherwise it clobbers whatever the user or another
extension had selected.

**Where a failure becomes visible.** `ctx.ui.notify(…, "warning")` — the union
is `"info" | "warning" | "error"`, and `"warn"` is not in it, which nothing in
this repo's toolchain would catch. Notification is the mechanism the silent
failure modes depend on, so the literal matters.

Not stderr: pi starts its UI *before* initialising extensions, by explicit
design ("so `session_start` handlers can use interactive dialogs"), so there is
no pre-TUI window in which stderr is safe. Writing to it injects raw bytes into
the display pi is drawing.

## Version gating

`PiCapabilities`, a provider-internal record, exposes `supportsBridge()`: true
at **≥ 0.80.3**.

That number is a deliberate simplification, and the honest reasoning is worth
recording because the first draft got it wrong twice and the second draft got
it wrong once more. The APIs the extension actually uses bind a floor of roughly
**0.44.0**; the bridge was observed *working* on 0.55.4 and on 0.79.10 — tools
and system-prompt override together — and loading on 0.50.0. The reason to
stand above all of that is not a bug being avoided: it is that 0.80.3 is where
`session_info_changed` arrives, phase 2 needs it, and supporting a range nothing
in drydock's CI will ever exercise buys compatibility we cannot claim. One
number, tested at the top of it.

A second justification appeared in the previous draft and is **withdrawn**: that
0.80.3 "fixed extension tool changes applying without dropping
`before_agent_start` system-prompt overrides". That changelog entry is scoped to
tool changes *during* an agent run; this design registers once, before any run,
so it never reaches that bug — as the working 0.79.10 run demonstrates. The
floor stands on the first reason alone.

Two mechanics the first draft left open:

- **Where it is probed, and what it costs.** `probeCapabilities()` has **no
  production caller today** — only tests. Putting the gate on the launch path
  adds a `pi --version` spawn, with `PiVersionProbe`'s 30-second timeout, to
  every create *and* every resume, inside the `supplyAsync` stage that gates
  surface creation. So the probe runs on the same background path that writes
  the extension file, and **only a successful probe is memoised**. Caching a
  failure would turn one timeout on a busy machine into every Pi tab silently
  losing its tools for the rest of the app run, and would mean installing or
  upgrading pi never takes effect until drydock restarts. Claude's
  `detectCaps()` is uncached for exactly this reason and says so; this is the
  narrower version of that, not a departure from it.
- **`"unknown"` is below the gate.** `PiVersionProbe.probe` returns the literal
  string `"unknown"` on a missing executable, non-zero exit, timeout or
  interrupt. An unparseable version means no `-e` flag and no tools — failing
  conservatively, and, per the previous bullet, retried on the next launch.

Note this is version comparison, which `ClaudeCapabilities` documents itself as
*rejecting* ("rather than assumed from the version string, since flag
availability does not necessarily track a simple version comparison"). Claude
can grep `--help` for a flag; there is no `pi --help` line that says "extension
tools work". The precedent is the shape of the record, not the detection
method, and the difference is deliberate.

### Where the extension file lives

`PiExtensionInstaller` writes the extension — a Java text block — to
`<stateDirectory>/pi/drydock-mcp.ts`.

- **Written once per app run, lazily, on the first Pi launch.** Not because no
  startup seam exists — `AgentProvider.init(ctx)` hands every provider a
  `backgroundExecutor`, and `ClaudeAgentProvider.init` already uses it, so
  `PiAgentProvider.init` could submit the write in one line. The reason is that
  doing so makes every app run pay for a provider the user may never launch, and
  gives `DrydockApplication.stop()` work to await on behalf of a Pi tab that was
  never opened. `ClaudeHookInstaller`'s own seam is genuinely unavailable —
  `installSessionActivityHooks` iterates `agentRegistry.activity(kind)` and
  `PiAgentProvider.activity()` is empty — but that is an observation, not the
  argument. `buildCreateCommand`/`buildResumeCommand` are permitted to block and
  already run off the FX thread, so the installer runs there.
- **Behind a memoised future, not a boolean.** Both builders run on
  `backgroundExecutor`, once per launch, and nothing serialises two tabs opened
  together. A check-then-write boolean lets the second tab either duplicate the
  write or — if the flag is set before the write completes — emit `-e <path>`
  for a file that is not there yet, which is the same intermittent
  no-tools failure the atomic rename was added to prevent, arriving from the
  writer side instead of the reader side. `ClaudeActivityReporter`'s `volatile
  boolean` is safe only because it is set after `install()` returns on the
  single startup thread; that precedent does not transfer to a concurrent
  launch path. A `CompletableFuture` memoised in the provider, completed only
  by a successful write, gives both mutual exclusion and the retry policy: a
  failed write is **not** latched, so the next launch tries again.
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

The version probe and the file write are **two independent memos**, not one
gate: a failed probe must not consume the installer's future, and a failed write
must not discard a good version. Both live on the same background path and both
retry on the next launch.

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
  edits permanently disable the agent's ability to title its own tab. The budget
  exists "to bound an agent looping" and has nothing to say about a person
  renaming. The obvious fix — "give it the exemption `applyHumanHandoff` has" —
  is not available: that method has no exemption to copy, it simply never
  reaches the router, being called from drydock's own Edit dialog. A relay is a
  tool call on the agent's own token, so any exemption reachable from the wire
  is one a looping or hostile agent takes, and the budget bounds something
  concrete (every rename costs a `Platform.runLater` and a turn under the state
  lock). Phase 2 therefore needs a channel drydock can *attribute*, not a flag
  on a tool — which is a design problem, not a parameter.
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
| pi older than 0.80.3, or version `"unknown"` | launch unchanged; no flag, no tools; probe retried next launch |
| extension file write failed | flag not latched; session launches without tools; logged; retried next launch |
| `DRYDOCK_MCP_CONFIG` unset/unreadable | extension registers nothing; session fine |
| handshake fails or exceeds 2s | factory catches and returns; failure reported from the `session_start` handler, which is the first point with a `ctx.ui` |
| a tool name collides with a pi built-in | that tool is not registered; reported; others register. A collision on `session_rename` specifically means the goal silently does not happen while everything else reports healthy |
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
- **A sub-floor Pi still gets a credential written for it.** `mcpAccessFor`
  mints the token and writes `mcp/<id>.json` before the provider builds the
  command, so a pi below 0.80.3 (or one whose version probe failed) gets a live
  session credential on disk that nothing will ever read. This is inherited, not
  new — `SessionManager:193-201` documents the same shape for Claude's
  `supportsMcpConfig` and accepts it deliberately, since the narrower "does this
  binary advertise the flag" question is provider-internal — but raising the
  floor to 0.80.3 widens the affected population, so it is stated rather than
  left to be rediscovered. The file is purged and the token revoked when the
  session ends, so the window is the session's life.
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
reporting rather than hanging; **an extension that throws still letting the tab
start** (the invariant, and the one whose failure is total); `/new`, `/fork`,
`/resume` and `/reload` inside a Pi tab standing the bridge down or not, as
appropriate; and **`-e` loading with no trust prompt in a real interactive tab
in a freshly created worktree**. The last two exist because nothing in them can
be reached non-interactively: every appendix run was `--offline -p`, which never
enters the TUI where `project_trust` is live and where every `session_start`
reason above `startup` comes from.

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

**A drydock-side indicator that Pi now has tools.** No user-visible string
anywhere in the app claims Pi has no MCP — the three stale assertions are all
comments — so nothing has to be corrected, and nothing new is added either. The
human learns the bridge works by watching a tab name itself, and learns it
failed from the pi-side notification. A per-session "tools connected" indicator
would be a better answer for all three harnesses at once, and inventing one for
Pi alone would leave Claude and Codex with the same blind spot. Considered, not
taken, and the reason is scope rather than value.

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

Added in the second review round, each a direct probe of a mechanism this
design newly commits to:

- **Action methods throw during load.** `pi.getAllTools()` / `getActiveTools()`
  / `setSessionName()` inside the factory raise "Extension runtime not
  initialized"; `registerTool` does not. In a `session_start` handler,
  `getAllTools()` returns the built-ins (`read, bash, edit, write, grep, find,
  ls`, plus this machine's `web_search`/`web_fetch`), and late registration
  works — a tool registered there was called by the model in the same run.
- **A throwing `-e` factory exits 1**, printing `Hint: Start without extensions
  using "pi -ne"`. No session `.jsonl` was written before the exit.
- **`pi --session <existing id>` reports `reason=startup`**, with
  `previousSessionFile=undefined` — so drydock's own resume form does not look
  like an in-TUI `/resume`, which is what makes the file-comparison guard
  necessary and a reason allow-list wrong.
- **A failed `fetch` carries the port in `err.cause`**, not in `err.message`
  (`"fetch failed"` / `connect ECONNREFUSED 127.0.0.1:59999`), and an
  `AggregateError` per address family for a `localhost` URL.
- **The whole phase-1 bridge works on 0.79.10**, tools and system-prompt
  override together — which is what withdrew the second half of the version
  floor's justification.

Still undemonstrated, and first to build: the proxy against a **running**
drydock with a minted token. Also undemonstrated, and on the manual checklist
because none of it can be reached from a non-interactive run: `-e` loading with
no trust prompt in a real interactive tab in a fresh worktree; and every
`session_start` reason above `startup` (`new`, `fork`, `resume`, `reload`),
which only a TUI command produces — so the stand-down guard must be exercised by
hand rather than reasoned about again.

## What the adversarial review changed

Three reviewers, two rounds, distinct lenses (mechanism, abuse/failure, spec
quality). Round 2 reviewed the revision, which is where most of the real damage
was found: four of the fixes from round 1 were themselves unbuildable.

**Round 2 — the fixes that were wrong:**

- **The collision check could not run where round 1 put it.** Every action
  method throws during extension load, so `getAllTools()` in the factory would
  have failed the load outright — and, because `-e` is the strict path, taken
  the tab with it. Registration moved to `session_start`, snapshot before
  register.
- **A throwing factory exits pi 1.** Round 1's restated invariant ("never takes
  the tab down") was still false, for a different reason than the one it fixed.
  The factory's own top-level catch is now the invariant.
- **`unregisterTool` does not exist.** "Unregisters its tools" was unbuildable;
  standing down is a filtered `setActiveTools`, and it must stop the
  `instructions` injection too.
- **The stand-down guard whitelisted the case it existed to catch.** In-TUI
  `/resume` switches conversations and reports `reason: "resume"`; drydock's own
  `pi --session <id>` reports `startup`. The predicate is now a session-file
  comparison, which also gets `/reload` right.
- **The version floor's replacement justification was also false** — the 0.80.3
  changelog fix is scoped to tool changes during an agent run, which this design
  never reaches, and the bridge was then demonstrated working on 0.79.10. The
  number stands; that clause is withdrawn.
- **The installer's justification rested on a false premise** — `init(ctx)` does
  hand providers a background executor. The lazy-on-launch choice survives on
  its real reason, and its write-once flag became a memoised future after review
  showed two concurrent launches race a boolean.
- Smaller: `"warn"` is not a valid notify level; there is no pre-TUI window in
  which stderr is safe; `err.cause` leaks the port even when the message does
  not; a 30-second client budget is the server's own ceiling rather than a
  margin over it; a sub-floor Pi still gets a credential written for it.

**Round 1 — findings that changed the design:**

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
