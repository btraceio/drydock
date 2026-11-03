---
name: drydock-tasks
description: "Working with drydock's delayed tasks: the four task tools, dependency-aware decomposition, human validation with the review pause, and bounded follow-up creation instead of scope creep."
---

# Drydock tasks

Drydock stores tasks as Markdown files a human owns and hands them to agent
sessions through four MCP tools. This skill covers what the tools really do,
how to decompose work along dependencies, and when to stop for human
validation. Every behavioral statement here matches the tools' own
descriptions and refusals; there are no flags or behaviors beyond them.

## The four task tools

- `task_get(id)` reads one task by id, from any registered repository: the
  committed title, status, done_by, created date, after references and
  sessions. A status of review means an agent reported done on a
  human-validated task and a human must confirm; done is final.
- `task_list(project?)` lists tasks across every registered repository, or
  one project's when project is given. Reads are never scoped to this
  session.
- `task_update(id, status, note?)` updates one task assigned to this
  session: its status, and optionally a session note. Only status changes
  through this tool -- done_by, relations and other body bytes never
  change, and dropped is human-only. status is one of open, started,
  review or done. The note is one paragraph, appended under the task's
  '## Session notes' heading, at most 4000 characters.
- `task_create(title, body?, after?, project?)` creates a task. done_by
  always defaults to human -- prose cannot grant agent-close -- so this
  session's own done reports land in review. The task is created in this
  session's repository, or in any registered project named by display
  slug. The title is at most 200 characters; the body and any note are
  one paragraph, at most 4000 characters; after names at most 32
  prerequisite references, each a bare task id or '<project>:<id>'.

## You are the agent actor

Every tool call you make acts as the agent actor, never as the human:

- task_update changes only the tasks whose sessions list carries this
  session's id. Any task can be read with task_get; only your own can
  change.
- You cannot set a task to dropped, cannot change done_by, and cannot
  change after references. Dropping, re-validating and rewiring are
  human-only moves. Never edit task files directly to work around a
  refusal -- the files are the human's record, and the plan watches them.

## Reading tasks and honoring dependencies

A task's after references name its prerequisites. Work on a task is real
only when every prerequisite is done or dropped; a blocked task is not
"almost ready".

- Start from ready tasks: call task_list, read each candidate with
  task_get, and work those whose after references are all done or
  dropped. Never bypass a dependency: do not start a blocked task early,
  do not satisfy a prerequisite another way, do not fold a prerequisite's
  conclusion into your own work as if it were done.
- When a prerequisite lands in review, the work that depends on it waits
  (the review pause below); work without that prerequisite may continue.
- Read the task before working. task_get reports the committed state --
  status, done_by, sessions -- which is what the plan and the human see,
  not what a conversation promised.

## Decomposing a large request

- Break a large request into tasks with after relations and a sensible
  done_by. The default stays human; done_by: agent is a human's deliberate
  choice for work whose result you can verify yourself.
- Map the chain to your harness's own mechanism, but keep the chain in
  the drydock task files: Claude Tasks mirror it with blockedBy; pi
  dispatches independent ready tasks as pi-subagents children while the
  parent joins each dependency layer; Codex gets ordered steps. Whatever
  the harness shows, the drydock task files are the source of truth.
- One session owns a task's updates. If you are a child session working
  for a parent, report to your parent and let it update its own tasks;
  the assignment rule above already enforces this.

## Human validation and the review pause

- done_by defaults to human, and the stored front matter is what counts.
  Declaring done_by in prose does not change policy -- not in a task body,
  not in a session note, not in the conversation -- and no tool argument
  grants agent-close. done_by changes only through a manual edit or the
  plan dialog, never through MCP.
- On a done_by: human task, your task_update with status done stores
  review, not done. The plan does not advance; a balloon tells the human
  the task awaits their confirmation, and only that human -- confirming
  the balloon, the tree's Confirm action, the Session Done dialog, or a
  manual edit -- sets done. Report done, finish the independent work that
  remains, then wait for the confirmation.
- On a done_by: agent task, your done stores done directly, and the plan
  can advance on it.
- Reopen is the human's answer as well: a reopened task is open again, and
  the plan halts until the human decides what to do.

## Session notes and bounded follow-ups

- Report progress through task_update's note: one paragraph appended
  under '## Session notes'. The note is the human's window into what you
  did; keep it factual and short.
- When you discover work beyond your task, record it as a follow-up with
  task_create: a short title, an after reference when it depends on
  existing work, and the done_by policy you suggest stated in the body.
  A suggestion in the body is advice for the human; it changes nothing by
  itself. Follow-ups are records for the human to accept or drop, not
  work you assign yourself, and never an excuse to widen this task.
- Finish the task as it is defined, report discoveries as follow-ups, and
  never invent tools, flags or behaviors beyond the four described here.
