# Workflow grouping — design

## Problem

A single piece of work — a feature, a refactor, a bug hunt, an investigation —
often spans several agent sessions across several repositories. Today the only
things linking those sessions are the operator's memory and, within one
lineage, the `forkedFrom` chain. There is no object that says "these four
sessions across three repos are one workflow," and no shared context that
survives a session's context being cleared.

## Goal

A first-class **Workflow** that groups related sessions across repositories and
carries a shared brief belonging to the workflow, not to any one session.

## Non-goals

- Not a multi-repo workspace manager (no bundling of repos, no linked-worktree
  creation). A workflow groups *sessions*, which already live in worktrees.
- Not shared memory / knowledge graph (Alternative C from the research). The
  shared artifact is a brief, the same shape as the per-session `HandoffBrief`.
- Not tags/labels (Alternative A). A session belongs to at most one workflow.

## Entity: `Workflow`

```
record Workflow(
    WorkflowId id,
    String title,                 // human name; not blank
    WorkflowStatus status,        // OPEN | ARCHIVED
    Instant createdAt,
    Instant lastOpenedAt,         // bumped when any member session is opened
    Optional<WorkflowBrief> brief  // the shared context; empty until first written
)
```

- `WorkflowId` is a stable app-assigned id, the same pattern as
  `ManagedSessionId`.
- `status` is `OPEN` by default; `ARCHIVED` hides the workflow from the default
  rail without deleting it. Archiving is reversible. A workflow with no member
  sessions is not auto-deleted (the human may re-add sessions).
- `lastOpenedAt` is the workflow-level analogue of
  `ManagedAgentSession.lastOpenedAt`: the rail sorts workflows by it so the
  active one rises to the top.

### Why the brief is workflow-scoped, not session-scoped

`HandoffBrief` today is keyed by `ManagedSessionId` and replaced wholesale on
every write. It is the testimony of *one* session to its *successor* in the
same worktree. A workflow's brief is different: it is the running narrative of
the whole piece of work, written by whichever member session is active and read
by the next one opened in *any* repo. Promoting it to workflow scope is what
makes a cross-repo handoff work — a session in repo B picks up where the
session in repo A left off without A and B ever sharing a worktree or a
lineage.

### Relationship to `forkedFrom` and the per-session brief

- `forkedFrom` is a *single-parent lineage* field on `ManagedAgentSession` —
  session X was handed off to session Y in the same worktree. It is about
  replacing one agent with another on the same tree. **It is currently
  unused**: `SessionManager.prepareSuccessorSession` does not set it, and its
  own comment states "No lineage is recorded. `outgoing` is deleted as part of
  the same handoff, so a `forkedFrom` pointing at it would resolve to nothing."
  The field stays in the record (it is persisted and decoded), but this design
  does not lean on it — see the fork section below for where workflow
  affiliation is actually inherited.
- The per-session `HandoffBrief` stays too: it is still what a session writes
  for its in-place successor, and it is still the seed a fork is launched with
  (`HandoffSeed.compose` reads the outgoing session's brief).
- `Workflow.brief` is the *cross-session, cross-repo* layer above both. A
  member session may write the workflow brief (the `session_handoff` tool gains
  a workflow target) in addition to, or instead of, its own.

The rule of thumb: `forkedFrom` (when populated) would answer "who had this
worktree before me?"; the per-session brief answers "what did the last agent
on this tree say?"; the workflow brief answers "what is the state of the whole
effort?".

### Fork / handoff workflow destination

A fork (in-place handoff to a different agent) today keeps the successor in
the same worktree and seeds it with the outgoing session's per-session brief.
The workflow adds a choice at fork time of which workflow the successor
belongs to:

- **Same workflow** (default when the source session is affiliated) — the
  successor inherits the source's `workflowId`. Inheritance happens in
  `SessionManager.prepareSuccessorSession`, which already copies a fixed set
  of fields from the outgoing session (displayName, namePinned, worktreeRoot,
  branchCreatedHere, evalMode); `workflowId` joins that set. It does **not**
  ride `forkedFrom`, which is currently never set (see above). The workflow
  brief is **linked**, not copied into the seed: the successor can read it (it
  is surfaced in the successor's context area, labelled as the workflow brief)
  but the seed text stays the per-session brief only. Linking avoids
  duplicating a document the successor can already see and that another
  member session may update while the fork is running.
- **New workflow** — the fork creates a fresh workflow (human names it), the
  successor is its first member, and the source session's `workflowId` is
  unchanged. This is the path when a fork diverges into a separate effort.
- **No workflow** — the successor is unaffiliated (`workflowId` empty),
  regardless of the source's affiliation. This is the path when the fork is
  a one-off that does not belong to any effort.

The per-session brief seed is unchanged in all three cases: it is still the
outgoing session's `HandoffBrief`. Only the workflow affiliation differs.

## `ManagedAgentSession` change

Add one optional field:

```
Optional<WorkflowId> workflowId   // empty = unaffiliated
```

- A session belongs to **at most one** workflow. This keeps the workflow brief
  unambiguous (one narrative, not a merge of several) and matches the mental
  model: "this session is part of *the* billing workflow."
- Setting `workflowId` is a normal mutation via `toBuilder()`, not set-once.
  A session can be moved between workflows or unaffiliated. (Moving it does
  not move its per-session brief — that stays with the session.)
- `forkedFrom` is unchanged (and currently unused — see "Relationship to
  forkedFrom" above). Workflow affiliation is inherited in
  `SessionManager.prepareSuccessorSession`, which copies `workflowId` from
  the outgoing session alongside the fields it already copies
  (displayName, namePinned, worktreeRoot, branchCreatedHere, evalMode). This
  is a one-line addition to that method.

## `ApplicationState` change

```
record ApplicationState(
    List<Repository> repositories,
    List<ManagedAgentSession> sessions,
    List<Workflow> workflows,          // NEW
    WorkspaceUiState ui,
    List<HandoffBrief> handoffBriefs
)
```

- `workflows` is a new top-level list, same persistence cadence as
  `repositories` and `sessions` — one writer, the existing
  `ApplicationStateRepository` single-owner model. No new writer.
- `empty()` adds `List.of()` for workflows; the four `with*` methods each pass
  `workflows` through, plus a new `withWorkflows`.

## `WorkflowBrief`

Same fields as `HandoffBrief` minus `sessionId` (it is workflow-scoped, not
session-scoped) and minus `writtenAtCommit` (dropped below), keeping `author`
and `writtenAt`:

```
record WorkflowBrief(
    String goal,
    String nextStep,
    Optional<String> approach,
    Optional<String> decisions,
    Optional<String> ruledOut,
    Optional<String> corrections,
    Instant writtenAt,
    Author author            // AGENT | HUMAN, reused from HandoffBrief
)
```

`writtenAtCommit` is **dropped** — a workflow spans multiple repos and
branches, so a single `HEAD` is meaningless. Staleness for a workflow brief is
expressed by `lastOpenedAt` / `writtenAt` (elapsed time since anyone touched
it), not by commits-since.

## UI

- The session rail gains a **Workflow** grouping above the per-repo session
  list. A workflow row is collapsible and shows its member sessions (across
  repos) underneath. Unaffiliated sessions stay listed under their repo as
  today.
- Selecting a workflow row shows its brief (read) and an Edit affordance
  (write, same dialog as the per-session brief edit).
- Opening any member session bumps the workflow's `lastOpenedAt` and surfaces
  the workflow brief in the session's context area (the same surface that
  shows the per-session brief today), labelled as the workflow brief.
- Archived workflows are hidden by default and reachable via a new "show
  archived" filter toggle. No such toggle exists in the app today, so this is
  a new control, not a reuse of an existing pattern.

## Lifecycle

- **Create**: human creates a workflow from the rail (title). Optionally seeds
  it with one or more existing sessions.
- **Add/remove members**: drag sessions in/out, or a per-session "part of
  workflow" picker. Adding a session sets its `workflowId`.
- **Write brief**: human edits the brief in the dialog, or an agent writes it
  via the `session_handoff` MCP tool with a workflow target.
- **Archive**: sets status `ARCHIVED`. Member sessions keep their `workflowId`
  (so un-archiving restores the grouping); they are just hidden under the
  archived workflow. A session may be unaffiliated from an archived workflow
  individually.
- **Delete**: a workflow may be deleted outright. Deleting a workflow clears
  `workflowId` on all its members (they become unaffiliated); it never deletes
  sessions.

## MCP surface

The `session_handoff` tool today writes the per-session brief. It gains an
optional target:

- `session_handoff` (no target) — per-session brief, as today.
- `session_handoff --workflow` — writes the calling session's *workflow* brief
  instead. Requires the session to be a workflow member.

No new tool for creating workflows from an agent: workflow creation is a
human act (naming the effort), like naming a session. An agent may write the
brief of an existing workflow it belongs to.

## Persistence & migration

- `workflows` is a new JSON array in the state file. The existing
  lenient-decode rule applies: a missing or malformed `workflows` array is
  skipped (recovered to empty), never a reason to declare the state file
  corrupt — exactly the discipline already used for cosmetic UI fields.
- `ManagedAgentSession.workflowId` is a new optional JSON field; old state
  without it deserializes to `Optional.empty()`.
- No on-disk migration step. The first load after upgrade simply has no
  workflows; everything else is unchanged.

## What this does not change

- `forkedFrom` lineage, per-session `HandoffBrief`, the single-writer state
  model, the one-session-per-worktree invariant, worktree creation, PR
  linking, eval mode.
- A workflow owns no repos and no worktrees. It is purely a grouping of
  sessions plus a shared brief.

## Decisions

1. **Shared brief: single authored document.** The workflow brief is one
   free-text document, written and replaced wholesale, same model as the
   per-session `HandoffBrief`. No roll-up of member briefs.
2. **At most one workflow per session.** A session carries at most one
   `workflowId`. To belong to a second effort, fork the session into the
   second workflow (see the fork destination choice above).
3. **Fork workflow destination: three-way choice, brief linked not copied.**
   A fork offers same-workflow / new-workflow / no-workflow. In the same-workflow
   case the workflow brief is *linked* (the successor reads it from the
   workflow, it is not appended to the seed), so the seed stays the per-session
   brief and the workflow brief stays the single shared narrative.
