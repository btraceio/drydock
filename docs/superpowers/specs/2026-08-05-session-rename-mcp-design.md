# A session names itself after the work it turns out to be doing

A drydock session's tab is labelled with its `displayName`, which is
derived from the branch at creation time and then never changes unless a
human retypes it. Branch names are chosen before the work is understood,
so within an hour the tab rail reads `feat/tab_title_mcp`,
`fix/nre-again`, `spike-3` — a row of labels that say where the work
lives and nothing about what it is.

The agent inside each of those tabs knows what the work is. This design
gives it a tool to say so.

## What it writes

`session_rename` writes the session's real `displayName`: the same field
the human's inline rename writes, the same field the sidebar tree,
`sessions_list`, and every confirm dialog read. There is no second
"agent subject" field layered over it. One session, one name, everywhere.

The mechanism is already in place — `MainWorkspace.renameSession` →
`SessionManager.renameSession` → `ApplicationState`, and
`OpenSessionTab.setDisplayName` repaints the tab. This design adds a
caller, a guard, and a reason for the agent to call it.

## The tool

```
session_rename(title: string)
```

One required argument. No session selector: the tool always renames **the
caller's own** session, resolved from the MCP token like every other tool
in `McpToolRouter`. A session started via `session_start` renames itself,
never its parent — the parent's tab is not the child's to relabel.

Validation happens at the router, in the order the other mutating tools
use:

1. `requireLiveSession(caller)` — a token that outlives its `claude`
   process renames nothing.
2. `PromptSafety.checkInboundText(title, "title")` — refuses control
   characters. The title reaches a JavaFX `Label`, and can later reach a
   terminal through the same paths any agent-authored text does.
3. Whitespace collapsed, then trimmed. A blank result is refused:
   `ManagedAgentSession`'s constructor already forbids a blank
   `displayName`, and the router must not hand it one.
4. Longer than **60 characters** is refused, not truncated. Truncation
   would silently discard the model's own words and teach it nothing; a
   refusal makes it rewrite. The tab label is capped at 160px (roughly 22
   characters) and ellipsizes on its own, so 60 is not about the tab — it
   is about keeping the sidebar rows and confirm dialogs readable.

## The pin

The human's rename wins, permanently.

`ManagedAgentSession` gains a `boolean namePinned` component — the same
shape as the existing `branchCreatedHere`: a policy flag that a guard
consults before acting.

- `SessionManager.renameSession(id, name)`, which serves the tab's inline
  rename field and the Rename dialog, sets `namePinned` **true**.
- A new `SessionManager.renameSessionFromAgent(id, name)` refuses when
  `namePinned` is true, and leaves it **false** when it succeeds. The
  agent may therefore keep re-titling itself as the work turns — right up
  until the moment a human cares enough to type a name.
- Two named methods rather than one method with an origin parameter: the
  pin rule is then visible at the call site instead of inside a branch.

The flag is persisted with the rest of the session record. Older state
files have no such field and decode as `false` — an unpinned session,
which is the correct reading of "nobody has renamed this yet".

There is no un-pin: no gesture, no menu item, no UI of any kind. A human
who changes their mind still has the rename field they always had.

When the tool is refused the router raises the ordinary
`McpToolException`:

> This session was named by the human ('…'); drydock will not rename it.

Naming the current title in the refusal is deliberate and leaks nothing —
it is the caller's own session, and the agent can already read that name
from `sessions_list`.

## Making the agent call it

`McpServer.initializeResult` today returns `protocolVersion`,
`capabilities` and `serverInfo`. It does not return the MCP spec's
`instructions` field, which Claude Code injects into the session's system
prompt. That absence is why no drydock tool is ever called proactively:
an agent reads a tool description only when it is already looking for a
tool, and nothing points it at this one.

So `instructions` is added, saying roughly:

> Drydock hosts this session in a tab a human is watching. As soon as you
> know what the work actually is, call `session_rename` with a short
> title naming the work — not the branch. Re-title it if the work turns
> out to be something else. If the human has named the session, the call
> is refused; leave it alone.

The tool's own description repeats the "as soon as you know" trigger, for
the agent that finds the tool by other means.

This field is shared infrastructure, not a fixture of this feature. Every
drydock tool added later can be introduced there.

## Seam

`McpSessionContext` gains:

```java
/** Outcome of a rename attempt, carrying the name in force either way. */
record RenameResult(boolean renamed, String currentName) {
}

/** Renames the caller's own session, unless the human pinned the name. */
RenameResult renameSession(ManagedSessionId caller, String title) throws McpToolException;
```

`currentName` is what makes the refusal message possible without a second
lookup: on refusal it is the name the human typed, on success it is the
title just stored.

`WorkspaceMcpSessionContext` implements it by hopping to the FX thread
with a timeout, exactly as its other mutating methods do — a wedged FX
thread must fail the call rather than hold the HTTP handler open.
`FakeMcpSessionContext` gains a settable pin so the router tests can
drive both outcomes without JavaFX.

A returned result rather than an exception from the context: "the human
named it" is an expected outcome of a legal call, and the message the
agent sees belongs with the other refusal messages in the router.

## Testing

- **Router** (`McpToolRouterSessionRenameTest`): happy path; blank title;
  title over 60 characters; embedded control character; dead session;
  pinned session refused with the name in the message.
- **SessionManager**: a human rename pins; an agent rename after that is
  refused and the name is unchanged; an agent rename before that succeeds
  and does *not* pin, so a second agent rename also succeeds.
- **ApplicationState**: `namePinned` survives a save/load round trip; a
  state file written before this change decodes with `namePinned` false.
- **McpServerTest**: the initialize result carries a non-empty
  `instructions` string.

## Open item for implementation

Every rename today originates on the FX thread, from a control the human
touched. An agent-initiated rename arrives from an HTTP handler thread.
The implementation must confirm that it reaches the open tab through the
existing session-change path (`MainWorkspace.java:592`,
`open.setDisplayName(session.displayName())`) rather than only updating
persisted state — and if it does not, route it so that it does. This is a
verification step, not an unresolved design question: the requirement is
that the tab relabels live, without the human reopening anything.

## What this deliberately does not do

- No separate agent-authored subtitle, tooltip, or second line on the tab.
- No history of previous titles.
- No renaming of any session but the caller's own.
- No un-pin affordance.
