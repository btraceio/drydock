# Work outlives the harness that started it

A drydock session is pinned to the CLI that launched it. `ManagedAgentSession`
carries an `AgentKind`, the provider builds a command line, and from then on
the work and the harness are the same object. When the harness is the thing
that has failed — rate limited, wedged, looping — the work dies with it, and
the only recovery is to start over in another tab and re-explain everything
from memory.

That is one of four reasons to want a different agent on the same work. The
others are wanting a second opinion on accumulated context, wanting to plan in
one tool and implement in another, and simply not wanting any one vendor's CLI
to be the sole custodian of a conversation.

This design gives a session a successor. It does **not** move a conversation
between harnesses: no transcript is translated, no rollout format is parsed,
no request passes through drydock. The successor is *briefed*, not resumed —
it knows it inherited the work and says so.

## What actually has to cross

Most of the state that matters is not in the transcript. It is the working
tree, the diff, the branch and the commits — and drydock already owns all of
those. What the transcript holds that nothing else does is the *reasoning*:
why this approach, what was tried and abandoned, what the human corrected.

So the crossing is a document, not a migration. Drydock never learns Claude's
`.jsonl` layout or Codex's rollout format; `ClaudeConversationSource`,
`CodexRolloutStore` and `PiSessionStore` keep doing exactly what they do
today, which is answer catalogue questions (title, message count, mtime) and
nothing more.

This is deliberately the cheapest thing that serves all four motives, and it
was chosen over two more expensive shapes. See "What was cut" at the end.

## The brief

A `HandoffBrief` is a small record with fixed slots:

| slot | what it holds |
|---|---|
| `goal` | what this session is trying to achieve |
| `approach` | the shape of the current solution |
| `decisions` | choices made and why |
| `ruledOut` | what was tried or considered and rejected, with the reason |
| `corrections` | what the human pushed back on |
| `nextStep` | what the successor should do first |
| `writtenAt` | when it was last written |
| `writtenAtCommit` | the `HEAD` it was written against |

It is stored beside session metadata through `ApplicationStateCodec`, keyed by
`ManagedSessionId` — **not** as a field on `ManagedAgentSession`. That record
is already sixteen fields with a `with*` accessor apiece, and a brief is a
document with its own write cadence, not part of session identity. A session
that has never written one simply has no entry.

`ruledOut` earns its slot separately from `decisions` because it is the part a
successor cannot reconstruct from the tree. A decision leaves evidence in the
code; a dead end leaves nothing, and without it the second harness cheerfully
re-walks the path the first one abandoned.

### Why a living document rather than a log

The brief is maintained *during* the session and replaced wholesale on each
update. There are no merge semantics and no partial writes.

The alternative — the agent appending `decision` / `ruled-out` / `correction`
events that drydock composes into a brief at handoff time — is cheaper per call
and yields a real timeline, but forty entries is a log, not a briefing, and
turning one into the other means owning a summariser and keeping it good.
Wholesale replacement keeps the brief permanently coherent: it reads as a
briefing because it was written as one.

The cost objection to a living brief is that the agent restates everything at
every checkpoint. With fixed slots that is roughly fifteen lines — less than
one file read — and it buys the property that matters most: **the brief exists
before it is needed.** A brief assembled at switch time is unavailable in
exactly the case that motivated the feature, because a rate-limited or wedged
session cannot be asked for anything.

### Why not a file in the worktree

`.drydock/handoff.md` needs no new tool and works with harnesses that have no
MCP at all. It was rejected because it lands in the worktree: it pollutes
every diff and the review rail, drydock cannot tell an agent update from a
human edit or a stale checkout, and it dies with the worktree. It also gives
the agent no reason to write it — a file nobody asks about does not get
written.

## `session_handoff`

```
session_handoff(goal, nextStep, approach?, decisions?, ruledOut?, corrections?)
```

`goal` and `nextStep` are required; the rest are optional and absent slots are
stored absent rather than as empty strings, so the seed can omit a heading
instead of printing an empty one. Every call replaces the whole record — an
omitted optional slot clears it, it does not preserve the previous value.
That is the wholesale-replacement rule stated at argument level, and it is the
one place an implementer would otherwise reasonably guess "merge".

One new MCP tool, following `session_rename` in every respect that already has
a precedent:

- **It is a write.** It joins `AGENT_WRITE_TOOLS` in `McpServer` — the
  explicit set that exists precisely so tools added later are classified on
  purpose rather than by how they were named.
- **It is charged.** A per-session budget with charge-on-attempt and
  refund-only-on-outright-failure, matching `chargeRename`/`refundRename`.
  Refused *outcomes* are charged: each still costs an FX hop and a turn under
  the state lock.
- **It validates before charging.** A malformed brief is the agent's mistake
  to fix, not a spend.
- **It refuses rather than truncates.** Each slot is capped at 2,000
  characters and the record at 8,000; an oversize brief comes back with a
  message naming the slot and the limit. A silently clipped `nextStep` is a
  brief that lies. The numbers are a starting point chosen so a full brief
  costs a fraction of a file read — adjust them on evidence, but keep refusal
  as the behaviour.
- **It explains its refusals**, so an agent can correct without guessing.

Every slot goes through `PromptSafety.checkInboundText`, which permits `\n`,
`\r` and `\t` — brief slots are bodies, not titles, so `checkSessionTitle`'s
one-line rule is wrong for them.

### The injection surface is real and is the reason for that validation

The brief is authored by an agent that reads untrusted diffs, and it becomes
the **seed prompt of a different agent**. That is a wider sink than a finding
body: text that survives into the seed is read by a fresh model with no
history to contradict it, at the moment it is deciding what to do first.

Two mitigations, both structural rather than filtering:

1. The seed marks the brief as *reported by the previous session* and
   distinguishes it from the facts drydock derived itself. The successor is
   told which half is testimony.
2. The handoff is a **UI gesture only** and deliberately not an MCP tool,
   so `session_start`'s "started sessions may not start further sessions"
   restriction stays intact. No agent can hand itself off, and therefore no
   injected brief can either.

### It has to be advertised

`McpServer.INSTRUCTIONS` is injected into the hosted agent's system prompt and
is, per its own comment, "the only thing that makes a tool get called without
the agent already hunting for one — a tool description is read at selection
time, not at the moment the agent learns what its work is."

So `INSTRUCTIONS` gains a sentence: this session may be handed to a different
agent at any time, so keep `session_handoff` current — you are writing for
your successor, not for the human.

## Staleness is measured, and the measurement has verbs

`SessionActivityWatcher` knows when the session last acted; the brief knows
when it was last written and against which commit. The gap is a fact drydock
can state, and it states it in work rather than in clock time: *"brief written
9 commits and 40 changed files ago"* is actionable in a way that *"brief is
two hours old"* is not.

The banner appears when there is no brief at all, or when the session has
committed or changed files since `writtenAtCommit`. Time alone never raises
it: a session idle for a day has a brief that is still perfectly accurate.
Nothing is enforced and nothing is blocked. The banner carries three verbs:

**Refresh** asks the outgoing session to call `session_handoff` now, by
writing a prompt into its terminal through the same path any seeded prompt
takes. It is a request, not a command: the agent may ignore it, and the banner
clears only when a brief actually lands. This is
the human pulling the trigger, not drydock silently degrading, which is why it
does not contradict the living-brief design. It is enabled **only when the
outgoing session is alive and idle**; otherwise it is disabled with the reason
on the control. A Refresh button that appears to work against a dead session
is worse than no button, because the failure is invisible at exactly the
moment the human is deciding whether to trust the brief.

**Edit** opens the brief in a dialog with one field per slot, for the human to
write directly — the same caps apply, but a human edit is never charged
against the session's budget. This is expected to
carry most of the load in practice: the human usually knows precisely what the
successor needs — *"the parser rewrite is a dead end, don't retry it"* — and
can type it faster than any agent round-trip. A human edit stamps `writtenAt`
and `writtenAtCommit` fresh, which clears the warning honestly, because the
brief now is current.

**Hand off anyway** proceeds. The handoff moves nothing, so a stale brief
costs the successor context and never the tree. See the next section.

## The handoff

The successor takes the outgoing session's place. Same repository, same
worktree, same branch, same working tree exactly as it stands — only the
harness changes, and only the conversation is lost. Nothing is copied, so
nothing can be copied wrongly: the uncommitted work that the rescue case
exists to save never moves at all.

The seed handed to the new session is always **the brief plus facts drydock
derives fresh at handoff time**: branch, commit subjects, changed files and
open review intents. That floor needs no cooperation from a dead session and
costs nothing, so a stale brief is always bounded by current mechanical truth
— and when there is no brief at all, the successor is *told* that rather than
quietly starting uninformed.

The operation:

1. Compose the seed and write it to a file in drydock's state directory.
2. Read what the successor inherits from the outgoing session: its title and
   whether a human pinned it, whether drydock created the branch, and its
   eval mode.
3. Delete the outgoing session through `SessionManager.deleteSession`, which
   closes its surface, releases its MCP config and removes its metadata.
4. Start a session on the same worktree with the chosen `AgentKind`, carrying
   the inherited fields, whose prompt is a **single line pointing at the seed
   file**.
5. Rebind the outgoing session's review scopes to the new session.

Steps 1 and 2 come before step 3 because `deleteSession` takes the brief with
the session it describes, and the inherited fields go with it. Step 5 comes
after step 4 because it needs the successor's id.

### Why the seed is a file and the prompt is a pointer

A prompt reaches a session as real keystrokes, and an embedded newline submits
the line before it -- which is why `MainWorkspace.sendTaskWhenReady` collapses
whitespace before typing. Run the composed seed through that and its headings,
blank lines and bullets become one run-on line, which destroys the separation
of testimony from derived facts that the section above calls a mitigation
rather than formatting.

So the structure lives in a file and the prompt is one line pointing at it.
The file is written **outside** the worktree, in drydock's own state
directory: a file in the tree would appear in the successor's first diff and in the
review rail, which is exactly why a worktree file was rejected as the brief's
*home*. This is a delivery artifact for one launch, not the store -- a
distinction worth keeping, because the two look alike and only one of them
belongs in git's view.

It is owner-only, since a brief can quote anything the previous session was
working on. The successor is asked to delete it once read; because that is a
request to an agent rather than a guarantee, drydock also sweeps seeds older
than seven days.

The chosen `AgentKind` may be the same one the outgoing session is running.
Handing off to the same harness is a legitimate rescue — the harness is fine,
that particular process is wedged — and a legitimate second opinion, so
nothing excludes it.

The new session starts with **no brief of its own**. The inherited brief is
in its seed, as testimony; its own `HandoffBrief` entry is created the first
time it calls `session_handoff`. Copying the parent's brief forward would make
a successor that never writes one look current forever, which is the exact
failure the staleness banner exists to catch — and the parent's entry is
deleted with the parent in any case.

### Why the outgoing session is deleted rather than kept

A superseded session left resumable is a trap. Its transcript describes a
worktree that has since moved on under a different agent, and resuming it puts
a confident model with stale context onto live files — the one failure this
design would otherwise introduce, arriving hours later with nothing on screen
to warn the human. So the handoff removes it.

`deleteSession` already draws the line in the right place: it removes
drydock's metadata and the brief, and touches nothing of the harness's own
on-disk transcript. The conversation still exists wherever Claude, Codex or Pi
keeps it. What is gone is drydock's offer to resume it into a tree that is no
longer the one it remembers.

That deletion is also what keeps one worktree to one session.
`SidebarChildren` matches worktrees to sessions one-to-one; a superseded
session sharing a worktree with its successor would take the worktree's row
from it and inflate the header count. Deleting instead of closing means that
case never arises and the sidebar needs no change.

### No lineage

The earlier design carried `forkedFrom: Optional<ManagedSessionId>` and
derived chain depth ("the third harness on this work") by walking the links.
With the parent deleted, that id resolves to nothing, so the handoff stops
writing it. The record component and its persisted member stay — they are a
wire contract and old state files still decode — but nothing new sets them.
Losing the chain is the price of not leaving a resumable trap behind, and the
successor is told it inherited the work by its seed regardless.

### Where it appears

A "Hand off to…" gesture on the session tab, listing
`AgentRegistry.agents()`. Unavailable agents are shown disabled with
`describeSearched()`, exactly as the existing picker does.

It asks for confirmation first, naming what goes: this session's tab and its
conversation are removed and the chosen agent takes over the worktree. The
gesture removes a session rather than adding one, which puts it among
drydock's destructive sequences, and those are confirmed.

### Review scopes move with the work

A `ReviewScope` is bound to a `ManagedSessionId`, and
`ReviewScopeRegistry.isAddressableBy` gates every review tool on that binding.
Deleting the outgoing session would strand its scopes: the findings the human
recorded would still exist and nobody could answer them. A new
`ReviewScopeRegistry.rebind(from, to)` moves them to the successor.

Rebinding rather than revoking is the point. A review is about the worktree's
diff, and the handoff does not change the diff by so much as a line — so the
scope is still exactly as valid as it was a second earlier. Revoking would
throw away the human's findings and make them review the same diff twice,
which is the opposite of what a handoff is for.

## Failure modes

The transplant was the risky part, and it is gone with the sibling worktree —
no files are copied, no branch is created, nothing in git is written at all.
What is left is the one hazard the shared worktree introduces: two agent
processes editing one tree. The design has no defence against that beyond
never creating it, which is why the outgoing session is gone before the
successor starts, and why the two steps are ordered rather than concurrent.

Closing the outgoing surface is not supposed to fail: `closeGracefully` waits
out its grace period and then kills the process. That is true of the JediTerm
surface, which finishes by invoking its completion callback whatever happened.
It is not quite true of the ghostty one, which runs the callback only after
`close()`, and `close()` can throw if freeing the native surface fails — in
which case the callback never runs and the delete never completes. So the
handoff bounds its wait rather than trusting the callback, and a close that
never reports back becomes a stated failure instead of a session that hangs
with no successor and no error.

The step that is expected to fail is the metadata write inside
`deleteSession`. It degrades safely — the surface is closed but the session is
still listed, which is the resumable state this design rejected but not a
damaged one — and the handoff stops there rather than launching the successor,
so one worktree never ends up with two sessions on it. The human sees why and
can try again.

The order buys that safety at the cost of a second window, and it is worth
naming rather than discovering. Once the delete succeeds, a failing launch
leaves no session at all: the outgoing entry and its brief are gone, and no
successor arrived to take their place. The tree, the branch and the commits
are untouched — the work is all still there — but the conversation's metadata
is not. Reversing the two steps would trade that for two agents editing one
worktree, which is the one hazard this design cannot survive, so the order
stays and the window is narrowed instead: everything the launch needs that
can be checked in advance — that a registered repository still owns the
session's checkout, above all — is resolved **before** the delete, so what
remains after it is only the genuinely unpredictable.

Everything else resolves to a plain refusal or a stated fallback:

| case | behaviour |
|---|---|
| outgoing session already dead | proceeds; closing a dead surface is a no-op |
| outgoing session has no commits yet | proceeds; the seed says the branch is unborn |
| metadata write fails during delete | handoff stops; no successor is started |
| surface close never reports back | the wait is bounded; handoff stops and says the removal could not be confirmed |
| a second handoff is started while one is in flight | refused; the first one owns that session until it settles |
| owning repository no longer registered | refused before anything is deleted |
| launch fails after a successful delete | the session is gone and no successor arrived; the tree, branch and commits are untouched, and the failure says so |
| target agent unavailable | never offered; disabled with `describeSearched()` |
| brief absent | proceeds; the seed states that no brief was recorded |
| brief oversize | `session_handoff` refuses with the limit; nothing is stored |
| seed file cannot be written | proceeds; the successor is told no brief reached it |
| outgoing session dead, Refresh pressed | not reachable — the control is disabled with the reason |

## Verification

JUnit, for everything that is logic:

- `HandoffBrief` codec round-trip, **including state files written before this
  feature exists** — old state must still decode.
- Staleness arithmetic against `writtenAtCommit`.
- Seed composition in all three cases (brief present, stale, absent),
  asserting the seed labels which parts drydock derived and which are the
  previous session's testimony.
- `PromptSafety` folding of every slot, and the supplementary-plane tag-block
  case that a `char` loop would miss.
- `session_handoff` argument validation, caps, and charge/refund behaviour.

For the handoff itself, against hand-written seams rather than a real
repository: the successor is launched on the outgoing session's own worktree
and branch; the outgoing session is deleted before the successor starts; a
delete that fails leaves no successor; an already-dead session is handed off
without special-casing; the title, `namePinned`, `branchCreatedHere` and eval
mode reach the successor; `forkedFrom` is not written; a launch that fails
after the delete leaves the session gone rather than half-restored, which is
pinned by a test so the trade-off cannot be silently reversed; and the
outgoing session's review scopes end up addressable by the successor and by
nobody else.

The staleness banner, the disabled-Refresh-with-reason and the handoff's
confirmation are visual state with no headless-FX harness behind them. Per `docs/architecture.md`, those are
checked with a diag script and its `shot:` scene snapshot, and the
implementation report says so plainly rather than claiming "tested".

## What was cut

**Seamless resumption** — replaying the outgoing transcript as the incoming
harness's own turn history, so `/rewind`, compaction and "as I said earlier"
all still work. This is the only bar that forces drydock to understand vendor
transcript formats, and it would put drydock permanently on the hook for an
N×N matrix of undocumented, independently versioned on-disk layouts, where the
hard part is not messages but tool calls, thinking blocks, compaction
boundaries and system prompts that have no counterpart across harnesses.

**The API proxy / uber-harness** — drydock in the request path, owning the
agent loop or at least the wire, so history is natively its own and switching
becomes routing. Once the fidelity bar is "briefed", the proxy buys nothing
for *this* feature: no request needs to sit in drydock's path and no wire
format needs translating.

It is cut but not foreclosed. If it returns it will be for its own reasons —
unified cost accounting, a canonical archive, models no CLI exposes — and it
will get its own spec. This design leaves the door open cheaply: the brief is
a record behind one MCP tool, and a proxy could fill the same record from
richer sources, or supply full transcripts alongside it, without any consumer
of `HandoffBrief` changing.

**The sibling worktree.** An earlier version of this design cut a branch at
the outgoing session's `HEAD`, minted a worktree for it, initialised
submodules and transplanted the dirty tree across, so the outgoing session
kept running untouched. It bought one capability — a second harness working
the same code in isolation while the first carried on — at the cost of the
whole transplant matrix, its rollback path, and a copy of the working tree
that could go wrong in ways the original could not. That capability is gone.
Two harnesses on one piece of work in parallel is a different feature, and if
it is wanted it should be asked for as one rather than arriving as a side
effect of switching harness.

**Resuming the superseded session.** The outgoing session's drydock entry is
deleted, so it cannot be reopened from the sidebar. Its transcript survives in
the harness's own store and can be resumed with that CLI directly, outside
drydock, by a human who knows the tree has moved on. What is deliberately
unavailable is the one-click path back into a worktree that no longer matches
what the conversation remembers.

**Lineage chains.** With the parent deleted there is nothing to point at, so
`forkedFrom` is no longer written and "this is the third harness on this work"
cannot be derived. Nothing in the design consumed it.
