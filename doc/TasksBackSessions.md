# Design: tasks back the sessions launched from New Task

Status: draft, no code. Every statement about current behaviour names the
code that shows it.

## Problem

A session launched from the New Task dialog has no task. From that dialog a
task exists only when the user chooses "save" instead of "launch" (tasks are
also created through the task MCP tools, which this design does not
change). Three things follow:

- **Mirroring needs a task.** Mirror addresses (GitHub issues, Jira tickets)
  are stored on the task (`TaskDocument.mirrors`). `TaskMirroring.mirrorsOfSession`
  reads them through the task that claims the session and returns an empty
  list for a session no task claims. A session started with "launch" cannot
  be mirrored.
- **Two paths for one intent.** The dialog collects the same inputs for both
  outcomes and returns either a launch choice or a saved plan
  (`NewTaskLauncherResult.isLaunch()`, `TaskLauncher` around line 996).
- **No dependency or review record.** A task carries `after` references, a
  status, a `done_by` and a review pause. A launched session has none of
  these, so it cannot be part of a dependency graph.

## What the code does today

| Fact | Where |
| --- | --- |
| "Save" creates the task through the task service and stores the body as notes. It registers no session, creates no worktree and spawns no agent. | `TaskLauncher.saveTask` (javadoc and body) |
| "Start" on an existing task launches through the same chain as an ad-hoc launch, with a list of `TaskLaunchBinding`. An empty list is the "byte-identical no-binding chain". | `TaskLauncher.start(..., List<TaskLaunchBinding>)` |
| A binding runs before anything spawns: it reserves the run receipt and writes the session link and the `started` status into the task. A refused bind prevents the launch. A failed launch removes the link only while the task is still `started` (a later done or review is never undone), and sets the task back to `open` only when no other session remains. | `TaskLaunchBinding` class javadoc, `startable`, `reconcileFailed` |
| The tree attaches a task to the first of its live sessions whose row the window shows; the inbox then lists a started task under that session instead of as a row of its own. A live session whose row this window does not show attaches nothing. | `DelayedTaskRows.attachedSession`, `InboxModel.place` |
| A launch on a workspace box (reached when the dialog's box checkbox is set) refuses a task-bound launch: "A task-bound launch cannot run on a workspace box". | `TaskLauncher.remoteLaunchRefusal`, `startRemote` |
| The ssh-remote arm (`startOnSshRemote`, taken when the project is remote) takes no bindings: `start` calls it without them and nothing refuses a non-empty list, so a binding passed to it is dropped silently. | `TaskLauncher.start`, remote-project branch |
| Other session sources call `SessionDialogs.launchSession`, which takes a `List<TaskLaunchBinding>`. The worktree review session passes an empty list, so it has no task. | `SessionDialogs.launchSession`, `WorktreeReview` (call at line 536) |
| A terminal session runs no agent and cannot be babysat; a babysitter cannot itself be babysat. How a babysitter is launched was not audited for this draft. | `BabysitStartRefusal` |

## Proposal

1. **A New Task launch creates its task first.** Order of events on the
   launch chain, off the EDT: create the task as `saveTask` does, then run
   the existing binding for that task, then spawn. The task is therefore
   `started` and linked to the session before the agent runs, which is the
   guarantee `TaskLaunchBinding` already documents.
2. **Adopt on demand.** Any other session gets a "Make task" action. It
   creates a task from the session's name in the session's repository and
   records the session as one of its `sessions`. Babysitters, review
   sessions and terminal sessions never do this on their own.
3. **Mirroring stays an explicit choice.** Backing a session with a task
   makes it mirrorable. It does not mirror it. The mirror flows
   (`TaskMirroring`) stay user-triggered.

## Design

### Launch path

- The dialog's launch result already carries a title-like `Choice.name` and
  the project; the save result (`NewTaskPlan`) carries `title`, `body`, `after`,
  `doneBy` and the project root and name. `saveTask` passes the title,
  `after` and project to `tasks.create` and stores the body as notes; it does
  not pass `doneBy`. The launch path needs the same fields as the save path, so the
  dialog returns one plan that both outcomes share. The save-only fields
  (`after`, notes) are optional on launch.
- Task creation reuses `TaskLauncher.saveTask(SharedProjectsService,
  DelayedTaskService, NewTaskPlan)`. Binding reuses `TaskLaunchBinding`, so
  no new write path to the task store is added.
- Failure order (decided). If task creation fails, the launch does not
  start and the failure shows as the save path shows it today. There is no
  unbacked fallback, so a launch has one rule. If the launch fails after the
  task exists, `TaskLaunchBinding.reconcileFailed` returns the task to `open`,
  and the user keeps a ready task they can start again. This matches how a
  failed start of an existing task behaves.

### Remote launches (the main obstacle)

- A workspace-box launch refuses task-bound launches by design
  (`remoteLaunchRefusal`), and the ssh-remote arm has no binding parameter.
  "Every launch is backed" is false until each arm either supports binding
  or is excluded.
- Options, in order of preference:
  1. Keep both remote arms unbacked in the first version. A remote launch
     creates no task; "Make task" adopts it afterwards. The new task-creation
     step must skip both arms explicitly: the box arm would refuse the
     binding, but the ssh arm would drop it and leave a task that no session
     ever links to.
  2. Create the task before a remote launch and link the session after the
     remote session id is known. This needs the receipt and reconcile rules
     of `TaskLaunchBinding` redone for a session that does not exist locally
     before it spawns. It is a separate piece of work.
- The first version takes option 1. The rule "launch from New Task creates
  a task" then holds for local launches only, and the dialog says so where a
  remote launch is chosen.

### Adopting an existing session

- "Make task" writes a task with status `started` and the session id in
  `sessions`, through the task service, not by editing task files.
- The task's repository is the session's repository as the session catalog
  records it. When it does not resolve to a registered project, the action
  is refused with the reason (`SharedProjectResolver` shows the resolution
  rules).
- A session that already has a claiming task offers no action.

### Sessions that stay unbacked

Review sessions (the worktree review passes an empty binding list), terminal
sessions, babysitters and remote launches (version one) have no task. The inbox already handles a session without a task:
`DelayedTaskRows.attachedSession` returns no match and the row stays a plain
session row. No inbox change is required for them.

### Mirroring consequences

- A backed session shows its task's mirrors through `mirrorsOfSession`, the
  same as a session started from a saved task.
- The mirror flows copy a task and its prerequisite closure
  (`TaskMirroring` javadoc). A task created at launch has only the
  prerequisites the launch supplies, so with none a mirror covers that task
  alone.
- A repository-level default for "mirror new tasks" is out of scope here.
  Adding it later does not change this design.

## Risks

- **Task store churn.** Every launch from the dialog writes a task file.
  Tasks left `started` by abandoned sessions accumulate. The existing
  interrupted state (`TaskState`: a started task with no live session) and
  the drop action cover cleanup, but the volume rises.
- **Slower launch.** The create and bind run on the chain before the spawn.
  The save path already waits on `tasks.initialize()` with a timeout
  (`PREPARE_TIMEOUT_SECONDS`), so a stalled store now delays a launch it did
  not delay before.
- **Store availability.** A launch fails when the task store cannot
  initialise, because a failed task creation blocks the launch (decided).
  Today only task features depend on the store, so a broken store now stops
  every New Task launch.

## Open questions

1. Should a task created at launch honour the dialog's `doneBy`? `saveTask`
   does not pass it to `tasks.create` today.
2. Should the task title come from `Choice.name`, or from a separate title
   field in the dialog? The dialog's current fields were not audited for
   this draft.
3. Should "Make task" offer to attach the session to an existing open task
   instead of always creating one?
4. What does an existing user's history look like? Sessions started before
   this change stay unbacked. Whether a one-time "adopt all running sessions"
   action is worth building is open.
