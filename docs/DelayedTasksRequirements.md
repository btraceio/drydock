# Delayed tasks — requirements (IntelliJ plugin)

Status: decisions confirmed except the items under "Open". Research:
`DelayedTasksResearch.md`.

## Terms

- **Task**: the definition of the work. One markdown file.
- **Session**: one run of a task in an agent harness (Claude, Codex, pi).
- **Prerequisite** of T: a task that must be done before T.
- **Follow-up** of T: a task that has T as a prerequisite. Computed, never stored.
- **Ready**: an open task whose prerequisites are all done or dropped.
- **Segment**: the open tasks of one start plan that belong to one project.
- **Stage**: the segments that can run in parallel because none of them waits
  for another open segment.

## Decisions

| # | Decision |
|---|---|
| D1 | Own task model. No dependency on IntelliJ Tasks & Contexts (unbundled in 2026.2). |
| D2 | Per-user store: `~/.drydock/tasks/<repository-id>/<task-id>.md`. The same for all worktrees and branches. |
| D3 | Relations are in YAML front matter, one direction only: `after:` on the dependent task. Follow-ups are computed. |
| D4 | Task id = title slug + short random suffix = file name stem, e.g. `fix-login-redirect-k3f9`. The file is not renamed when the title changes. Ids are unique across all projects. `<project>:<id>` is also accepted (P2). |
| D5 | The existing dialog keeps starting sessions. A "save as task for later" option writes a task file and starts nothing. The action is renamed to "New Session…". The `intellij.tasks` package and `Task*` classes keep their names; they are renamed in a separate commit later. |
| D6 | Status changes: Drydock (started, link to session), Session Done dialog (mark done), the agent through Drydock MCP tools, and manual file edits. |
| D7 | Start in one project = one session. The plan contains all open prerequisites in that project (all levels, dependency order) as work, done prerequisites as context, and direct follow-ups as "out of scope, keep in mind". |
| D8 | Cross-project start: Drydock splits the open prerequisites into segments (one per project) and orders them in stages (P4). A plan dialog shows the stages and fixes the launch choices (agent, mode, branch, window) of every segment. On confirmation Drydock starts stage 1, all segments in parallel. When a stage is done, Drydock starts the next stage by itself, and finally the target task (P4). Each session stays a normal single-project Drydock session. |
| D9 | Tasks subnode under each project: open tasks, ready first, then blocked with a "blocked by" hint; age shown, stale ones flagged; done and dropped hidden behind the filter ribbon. |
| D10 | A started task leaves the Tasks list and shows under its session row (one child row per task in the chain). It stays there while the session exists, also when the session is idle or stopped. If the session is removed without Done, the unfinished tasks go back to Tasks, flagged "interrupted", with a link to the last session. |
| D11 | A blocked task whose prerequisite is in a session shows "blocked by X (in session Y)", with an action to go to that session. |
| D12 | The target task is the last stage and starts by itself like the others. A task that is not part of a confirmed plan never starts by itself; when it becomes ready, Drydock shows "X is ready" with a one-click Start. |
| D15 | Order inside one project is the harness's job: the segment runs as one session, and its plan uses the harness's own mechanism (Claude Tasks with `blockedBy`, pi-subagents workflow, ordered steps for Codex). Drydock does not order work inside a session. |
| D13 | A cross-project prerequisite counts as done when it is marked done (agent or Done dialog). Its branch does not need to be merged. The dependent session gets the prerequisite's worktree as an extra directory: `--add-dir` for Claude and Codex, the path in the prompt for pi. |
| D14 | The agent may create tasks through the MCP tools (for example follow-ups it finds). |
| D16 | Who may close a task: front matter `done_by: agent` or `done_by: human`, default `human`. With `human`, the agent's `task_update(done)` stores `status: review`; the plan does not advance, and a balloon "X awaits your confirmation" offers Confirm (done, plan continues) and Reopen (open, plan halts). Human confirmation = Session Done dialog, Confirm (balloon or tree), or a manual edit. The plan dialog shows a per-task "agent may close" check box and writes changes back to the task files. |
| D17 | Harness guidance: a short paragraph in the MCP handshake for every session with the task tools, plus a full skill (tools and planning) shipped in the plugin and passed only at launch (P7a). |
| N1 | Non-goal: no general coordinator. Drydock has exactly one rule: in a plan the user confirmed, "stage done" starts the next stage. Inside a project the harness orders the work (D15). No retries, no priorities, no scheduling of tasks outside a confirmed plan. |

## Details

### P1. File format

```markdown
---
title: Fix login redirect
status: open            # open | started | review | done | dropped
done_by: human          # agent | human (default human)
created: 2026-10-29T10:12:00Z
after: [api-schema-x81c, auth-lib:token-refresh-a2b0]
sessions: []            # session ids, appended by Drydock
---
Free markdown. [[api-schema-x81c]] links are clickable but create no relation.
```

- Only `title` is required. A missing `status` means `open`. A missing
  `created` uses the file's creation time.
- "Interrupted" is not stored. It is computed: `status: started` and no
  session in `sessions:` exists in the catalog any more.
- Unknown keys are kept as they are when Drydock rewrites the front matter.
- Drydock rewrites only the front matter, never the body.

### P2. Cross-project references (confirmed)

- The id generator checks every store, so a bare id resolves in any project.
- `<project>:<id>` is also accepted. `<project>` is the project's display name
  as a slug. On ambiguity, the bare id wins if it is unique; otherwise the
  reference shows as broken.

### P3. Graph rules (confirmed)

- A reference to a missing task shows as broken. It blocks start until it is
  removed or the task exists again.
- A cycle shows as an error on every task in it, and blocks start.
- Dropped prerequisites count as satisfied.

### P4. Staged cross-project plan (D8, D12, D13)

When the user starts T and some open prerequisite of T is in another project:

1. Take the open prerequisites of T at all levels.
2. Group them by project into segments. Within a segment, order by
   dependency (D7).
3. Build the segment graph: segment S waits for segment R when a task in S has
   a prerequisite in R. A cycle between segments blocks start (P3).
4. Stage 1 = segments that wait for no open segment. Stage n+1 = segments
   whose waits are all in stages 1..n. T's own segment is the last stage.
5. The plan dialog shows the stages and fixes a launch choice per segment
   (agent, mode, branch, window). Launch goes through the existing
   `TaskLauncher.start(Project, Inputs, Choice)`; the dialog only produces
   the choices.
6. On confirmation Drydock starts stage 1, all segments in parallel.
7. Trigger: every task of the running stage is `done` or `dropped`. An agent
   can set `done` only on `done_by: agent` tasks; on the others its "done"
   becomes `review` and waits for the user (D16). Drydock watches
   the store for file changes. Then it starts the next stage, and T when no
   stage is left.
8. Halt: if a task of the running stage becomes "interrupted", or the graph
   under the plan changes (new open prerequisite, cycle, broken reference),
   Drydock stops the plan and raises a balloon that explains why.
9. Window: sessions open in the window the plan was started from; if it is
   closed, in any open Drydock window.
10. IDE not running when a stage completes: at the next startup a balloon
    offers "Continue plan". Drydock does not launch while windows restore.
11. Each later session gets the worktrees of the done prerequisites it
    depends on as extra directories (D13).
12. The plan is visible on the target task ("stage 2/3 running") and has a
    Cancel action.
13. The plan is stored in its own file under `~/.drydock/tasks/plans/`, with
    a single writer (repository rule: one writer for persistent state).

A prerequisite's worktree must stay while a dependent session uses it. The
Done dialog of a prerequisite session warns before it removes such a worktree.

### P5. Creating and editing

- New Session dialog: "Save as task for later" check box. When checked, the
  start-related fields are disabled and the OK button says "Save Task".
- "New Task…" action on the Tasks node and in its context menu: title, body,
  prerequisites (picker with search across projects).
- Double-click on a task opens its file in the IDE editor (Markdown plugin).
- The tree updates on file changes (VFS listener on the store), including
  edits made outside the IDE.
- `[[id]]` navigation: a `GotoDeclarationHandler` (S1). Preview clicks are not
  covered in v1.

### P6. Start in one project

- "Start Session…" opens the New Session dialog prefilled with the composed
  plan as instructions and the task title as the session name. The user can
  edit before starting.
- Plan format per harness:
  - Claude: ordered steps plus an instruction to create Claude Tasks with
    `blockedBy` that mirror the chain.
  - pi: a pi-subagents workflow when the segment has more than one task (the
    workflow feature comes from the `pi-subagents` package, 0.81.0 here, on
    pi 1.3.0). Exact shape to define in the plan.
  - Codex: plain ordered steps.
- When the session starts: every task in the segment gets `status: started`
  and the session id in `sessions:`.

### P7. MCP tools for the agent

- `task_get(id)`, `task_list(project?)`, `task_update(id, status, note?)`,
  `task_create(title, body?, after?, project?)`.
- Reads span projects; status writes affect only tasks assigned to the calling
  session. Creation may target another registered project and defaults to human
  validation. A policy suggestion in prose does not change `done_by`.
- `task_update` changes only `status` (`done` becomes `review` when
  `done_by: human`, D16) and appends a note to the body under a
  `## Session notes` heading. Dropping is human-only: MCP cannot set `dropped`
  or change `done_by`/relations, so it cannot satisfy a dependency by dropping it.

### P7a. Harness guidance (D17)

- MCP handshake: a short paragraph, added by `McpServer.instructionsFor` for
  every session whose router serves the task tools (same mechanism as the
  `session_rename` and `session_handoff` paragraphs).
- Skill: shipped in the plugin, passed only at launch: Claude
  `--plugin-dir <path>`, pi `--skill <path>`. Codex: a per-launch option was
  not found (`codex --help`, 0.163.0); until one is found, Codex gets the
  handshake paragraph only. Nothing is written to the user's global harness
  configuration.
- One shared text with a short harness-specific part.
- Content, tools: read the task and the plan; `done_by` and what
  `task_update(done)` does (review on `human` tasks); session notes; create
  follow-ups with `task_create` (title, `after:`, suggested `done_by`); never
  edit task files directly.
- Content, planning: break a large request into tasks with `after:` relations
  and a sensible `done_by`; record discovered work as a follow-up task instead
  of doing it; map the plan to the harness mechanism (Claude Tasks with
  `blockedBy`, pi-subagents workflow, ordered steps for Codex).

### P8. Done (confirmed)

- The Session Done dialog lists the session's tasks with check boxes to mark
  them done (default: checked).
- Inside one segment, the harness pauses dependent work while a prerequisite
  awaits review; independent work may continue. Confirm updates status and
  offers a return to the session, but does not inject another harness prompt.
  Stage advancement remains automatic when the whole stage is satisfied.

### P9. Launch recovery and retained context

- Failed or ambiguous launches halt the plan; no automatic retry. Every
  unfinished plan requires Continue after an IDE restart. Reconcile the
  persisted session id before starting anything again.
- Retain internal run receipts under `~/.drydock/tasks/runs/<session-id>.json`
  after catalog deletion: task ids, repository/worktree, launch phase and
  context consumers. Task files retain `sessions: [id, ...]`.
- A manually-completed task with no run supplies visibly labeled definition-only
  context. A recorded prerequisite directory that is missing halts launch.
- These are filesystem-accessible files, not a security boundary against an
  agent that ignores the skill and edits them directly.

## Spikes

- S1 (done, 2026-10-29, IU 263.7031.23, platform test): a Markdown file in a
  temporary directory outside the project (`ProjectFileIndex.isInContent` =
  false) gets Markdown PSI. `[[b-cd34]]` parses as `[` +
  `MarkdownShortReferenceLink(MarkdownLinkLabel "[b-cd34]")` + `]`, with no
  reference of its own at that offset. A platform `GotoDeclarationHandler`
  that matches `[[id]]` in the text resolves it to the target file
  (`GotoDeclarationAction.findAllTargetElements` returned the file). The
  handler needs no dependency on the Markdown plugin. Not checked: Ctrl+hover
  underline and click in a running IDE, clicks in the Markdown preview, and
  whether a Markdown inspection flags `[b-cd34]` as an unresolved link label.

## Out of scope for v1

- Sessions that cover more than one project.
- Bridge to IntelliJ "Open Task" (`TaskRepositoryType`).
- Sync, sharing, or export of tasks to GitHub or Jira.
