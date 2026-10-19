# IntelliJ plugin: a task-centric Drydock tool window

Status: design, agreed in conversation on 2026-10-19. Supersedes the entry
points of `docs/intellij-mvp-handoff/README.md` (surface 1 "Agents ▾", surface
10 "Welcome screen: Agents tab", and the Tools › Drydock menu). The rest of
that handoff (the agent editor tab and its strip, Needs-you notifications,
the close/quit confirmation, the Done dialog) stays, with the changes below.

## Intent

The plugin today is reached through Tools › Drydock, and its New Agent
Session dialog is about branches and worktrees. Live use showed both are
wrong for how it is meant to be used:

- a menu is a poor home for something used all day; it needs a visible,
  always-there surface;
- the user thinks in **tasks** ("make the tool window task-centric"), not in
  branches and worktrees. Branch and worktree are plumbing: generated,
  editable, but something the user need not know about.

So the plugin becomes **a parallel, agentic-task-centric view of the IDE**,
next to the IDE's own project/branch-centric one: a side tool window listing
the user's registered projects and, under each, its tasks (agent sessions),
from which the user starts tasks, sees what every agent is doing, jumps to
any of them, and stops or cleans them up. The model is the standalone
Drydock app's sidebar of registered projects.

## Goals

1. A **Drydock tool window** (side stripe, every IDE window) showing every
   registered project and its tasks, with live status.
2. **Start a task from it** with a dialog that asks for a human-readable task
   name and pre-generates everything else (branch, start point, worktree
   folder, window).
3. **Switch** to any task: raise or open its window and select its agent tab.
4. **Stop** a task, or **stop it and close its window**, from the tree.
5. **Clean up** a finished task (remove worktree, optionally delete branch)
   with warnings whenever work would be lost, and always a way to go ahead
   anyway.
6. **One list of registered projects shared with the standalone app.**
7. Fix: Resume Conversation opens the agent's tab (done, commit `3a4f6b16`).

## Non-goals (MVP)

- Inferring the task name from the instructions with a small (Haiku-class)
  model. A later step: the user would write only the instructions and get a
  suggested name and branch. Recorded so the dialog's shape does not block it.
- **Resume Conversation…** (adopting an on-disk agent conversation Drydock
  does not know yet) is dropped from the MVP. A list of titles, ids or the
  last few turns is not enough to tell which conversation was doing what;
  it needs a summary written by a small model. That is a later step, using
  the same small-model hook as task-name inference. Resuming a task that
  is already in the tree (Resume on its row or strip) is unaffected.
- Showing the standalone app's sessions in the IDE. Sessions stay with the
  tool that runs them; an agent in the app's terminal cannot be attached to
  an IDE tab.
- Anything on the Welcome screen (no project open).
- The "Agents ▾" toolbar widget (never built; dropped).
- The `<n> files changed` count and the Changes review (handoff surfaces
  7/8) — unchanged by this design, still pending.

## Anti-goals

- **Losing work silently.** Stopping a working agent, removing a worktree
  with anything unmerged, or deleting a branch with unmerged commits never
  happens without a warning that names what is at risk. But a warning is
  never a wall: the user can always go ahead.
- **Polluting the shared project list.** Opening a project in the IDE does
  not register it.
- **Two writers on one state file.** The IDE must never write the
  standalone app's `state.json` (see Constraints).
- **Putting the task name into the agent's prompt.** The name is a label.

## The tool window

Placement: a tool window with id `Drydock`, on the left stripe by default
(like Project). Same content in every IDE window.

```
Drydock                         [＋ New task] [⟳] [⋮]
[ Search tasks and branches                        ]
 btrace isn't in Drydock · Add                        ← only if this window's project is unregistered
 btrace                                         ＋ ⋯
   ● Fix probe leak on retransform   ⑂ fix-probe-leak · Claude
   ⑂ add-jfr-events                             2 tasks
       ● Add JFR events for sampling            Codex · 12m
       ○ Review the JFR events                  Claude · yesterday   [Resume]
   ● Explain the agent loader  RO               main · this window
   ⑂ old-experiment                             no task · Start ▸
 drydock                                        ＋ ⋯
   ● Task-centric tool window        ⑂ task-centric · Claude
```

Agreed (layout "B", chosen from the mockups):

- **Top level: registered projects**, by their main checkout. This window's
  project first, then the rest alphabetically.
- **Under a project: its tasks.** A task's branch shows as secondary grey
  text (`⑂ <branch> · <Agent>`), or `main · this window` and similar. A
  worktree holding **more than one task** becomes a sub-group `⑂ <branch>`
  with its tasks indented beneath. Worktrees found on disk (`git worktree
  list`) **with no task** are listed with a `Start ▸` action, as in the
  standalone app's sidebar.
- **Status dots** as today: blue working, amber needs you, green at prompt,
  hollow not running; `RO` chip for read-only.
- **Search** filters by task name and branch across all projects.
- **Header actions:** `＋ New task` (for this window's project, or the
  selected project), refresh, overflow.
- **Project row:** `＋` (new task in that project) and `⋯` with
  `Remove from Drydock`.
- **Task row:** double-click = switch. Hover/row actions: switch, stop, `⋯`
  (stop & close window, Done with this task…, show branch/folder, resume
  when not running).

### Registering projects

- Deliberate only. If the current window's project (resolved to its main
  checkout) is not registered, the tool window shows one row at the top:
  `<repo> isn't in Drydock · Add`. One click registers it; the row then
  disappears. No other nag.
- `Remove from Drydock` on a project row removes it from the list only —
  never touches the repository, its worktrees, or running agents. If that
  project has running agents, ask first.
- Worktrees are never registered on their own; they appear under their main
  repository by discovery.
- **Starting a task registers its project.** New task (⌃⌥N, or the header
  button) in an unregistered window's project is allowed, and starting the
  task registers the project: starting a task is a deliberate act.
- **Agents never vanish.** Tasks running in this window's project while it
  is unregistered are listed under the `<repo> isn't in Drydock · Add` row.

### First run: seeding the shared list

The shared file is seeded **from the standalone app's list only** (the
`repositories` of the app's `state.json`, local projects resolved to their
main checkout). The plugin's own catalog is **not** merged in: it was filled
by auto-registration and would bring that clutter along. Plugin sessions
whose project is not in the seeded list stay in the plugin's state, hidden
from the tree until the project is added. Adding it brings them back, so
plugin sessions must be matched to shared projects by **canonical main
checkout root** rather than by the plugin's old repository ids.

Which `state.json` seeds it when several app installs exist is left to the
builder (on the author's machine there is one, in the shared
`ClaudeProjectManager` directory). If none exists, the list starts empty. Seeded projects keep the app's
existing repository ids, so the app's sessions stay linked without
rewriting.

## New task dialog

Opened from `＋ New task`, a project row's `＋`, or ⌃⌥N (this window's
project). Opening it for a project other than this window's is normal.

Default view — the user types only the name:

- **Task** (required, focused): human-readable name, e.g. "Task-centric
  tool window". It is the task's label in the tree and on the agent tab.
- **What should the agent do?** (optional, multi-line). **Starts empty and
  stays empty** unless the user types. If empty, the agent starts at its
  own prompt with nothing sent. The task name is never sent as a prompt.
- **Agent**: Claude / Codex / pi; default = the project's last-used agent,
  else the first available.
- **Mode**: Writes / Read-only.
- One summary line: `New branch <slug> from <start point> in <folder> ·
  Change…`

After `Change…` (same dialog, expanded):

- **Branch**: pre-generated slug from the task name, updated live while the
  user types the name, until the user edits it. Must not collide with an
  existing branch (today's "taken branch names" check).
- **From**: start point, default the remote's default branch (e.g.
  `origin/main`); changeable through the IDE's own branch picker (local,
  remote, tags).
- **Folder**: `<base>/<repo>-<slug>` (today's `WorktreeNaming`), editable,
  with a folder chooser. Below it: `Base <base> from Settings › Tools ›
  Drydock · Change base…`.
- **Open in**: New window (default) / This window.

Read-only tasks need no branch or worktree; they run in the project's main
checkout (as today's Read-only + Here). The summary line says so.

**Worktree base** is one shared setting for all projects: the existing
`worktreesDirectory` in `~/.drydock/config.json` (`UserConfig`), which the
standalone app already reads and writes; default `~/dev/wt`. The plugin
exposes it in Settings › Tools › Drydock and in the dialog's `Change base…`.

**Open in This window** closes the current project in this window and opens
the new worktree there. Working agents in the closed window get the existing
"Stop n working agents?" confirmation.

## Switching

Switch (double-click, or row action) raises the task's window and selects
its agent tab, keyboard in the terminal. If the window is not open, open it
(the worktree as its own project window) and select the tab **without
starting the agent**; starting again is an explicit Resume.

## Stopping, closing, cleaning up — the warnings

| Action | Asks when | Says | Proceed option |
|---|---|---|---|
| Stop | the agent is working (not at its prompt) | which agent, that it is mid-task | "Stop anyway" |
| Stop & close window | same as Stop | same; closing the window itself loses nothing (IntelliJ saves files on close, the worktree stays on disk) | "Stop & Close" |
| Done › remove worktree | the worktree has uncommitted files, untracked files, or commits not on the default branch | the counts, e.g. "3 uncommitted files, 2 commits not merged" | "Remove anyway" |
| Done › also delete branch | (tick box in the same dialog) | — | off by default when the branch has unmerged commits; on (or available) when merged |

A clean, merged worktree is removed without asking. An agent at its prompt
is stopped without asking (as today).

## Shared registered-projects list

Agreed: **move only the list of registered projects into one small shared
file of its own**, read and written by both the standalone app and the IDE.

- Location: the user-level Drydock directory that already holds the shared
  `config.json` — proposed `~/.drydock/projects.json` (builder may rename).
- Content: per project, at least its canonical main-checkout root and a
  display name; whatever else both tools need (e.g. added-at). Repository
  settings that are per-tool (last-used agent, etc.) stay in each tool's
  own state.
- Writes are short "lock, re-read, change, write atomically (temp +
  rename)" transactions under an OS file lock on a sibling lock file, so
  neither process overwrites the other's change. Readers tolerate a
  missing or malformed file (empty list + warning, per the lenient-decode
  rule for non-critical data) — but a malformed file is never overwritten
  by a blind save.
- Both tools notice changes made by the other (file watch or re-read on
  focus); the IDE tree updates without a restart.
- **The standalone app changes too:** its repository list reads from and
  writes to the shared file instead of `state.json`'s `repositories`.
  Sessions, tabs, UI state stay in its per-install `state.json`. Its
  sessions still reference repositories by id, so ids must be stable and
  shared across both tools.

## Constraints (verified in the code)

- **The app's state file cannot be shared.** `StateDirectory` gives each
  app install its own `state.json` behind an instance lock, because two
  instances sharing one file wiped each other's sessions. The plugin stores
  its own state under the IDE config path (`DrydockPluginService
  .defaultBaseDir`) and must keep doing so. Only the projects list moves out.
- **Repository identity changes.** Today the plugin's catalog registers a
  worktree as its own repository (R7 in `ProjectRepositoryService`). The
  tree groups by main checkout with worktrees beneath, so the plugin must
  resolve a session's working directory to (main repository, worktree) —
  `ProjectIdentity` already finds the main checkout via `git worktree list`.
- **Core code is shared by copy, and the copies have drifted.**
  `intellij-plugin/src/main/java` is an extracted copy of the app's
  headless core (`app.drydock.*`). It is not identical:
  `diff -rq` shows ~16 differing files, among them `ApplicationStateCodec`,
  `StateDirectory`, `ManagedAgentSession`, `WorktreeService`. *My call:* no
  re-sync as part of this work. The new shared-projects store is new code
  and lands identically in both trees. Changes to existing files (the
  codec dropping `repositories`) are made per side.
- **Repo rules (AGENTS.md):** blocking work (git, filesystem, the shared
  file) off the UI thread with visible progress cleared on every path;
  processes through `ProcessRunner`; one writer per persistent state;
  lenient decoding of non-critical data; dialogs opened from background
  completions use `ModalityState.nonModal()` (commit `6026cf4b`).
- **Platform:** public API only (Plugin Verifier must report no internal
  API usage); target IU 2025.3.3, verified against the latest too.

## Removed

- Tools › Drydock menu: New Agent Session…, Resume Conversation…, New
  Worktree…, Check Out Branch in Worktree…. New task and `Start ▸` on a
  discovered worktree replace the session and worktree actions. Resume
  Conversation… goes with no replacement (see Non-goals). ⌃⌥N remains as the shortcut for New task.
- Auto-registration of every opened project.
- The handoff's Agents ▾ widget and Welcome-screen tab (not built).

## Kept

- The agent editor tab and strip (Stop / Resume · ~Nk tokens / ⋯), the
  dimmed stopped transcript, Needs-you balloons and OS notifications,
  close/quit "Stop n working agents?" — unchanged.
- The Done dialog, extended with the warnings and branch tick box above.

## Edge cases settled after review (my calls)

- **What counts as "not merged".** A branch is merged if its tip is an
  ancestor of the local default branch or of `origin/<default>` as last
  fetched (no fetch is forced), **or** the task's recorded PR state is
  MERGED. The PR state covers squash and rebase merges, which a plain
  ancestry check would flag on every finished task. Uncommitted and
  untracked files always count, whatever the branch state.
- **Done from the tree.** Done is offered for any task, in any window, and
  for a task that is not running. If the task is running, the Stop warning
  comes first. If its window is open, Done closes that window, and the
  window's other agents get the usual close confirmation. On a worktree
  shared by several tasks, Done forgets only this task. "Remove worktree"
  is offered only when no other task uses the worktree; otherwise the
  dialog says which tasks still use it.
- **A second task in an existing worktree.** `Start ▸` on a discovered
  worktree, and `＋` on a worktree sub-group or on a task row's ⋯, open
  the New task dialog with the branch and folder fixed. The summary line
  reads `In existing worktree ⑂ <branch>`, and Change… does not offer
  branch or folder.
- **Writes in the main checkout** stays possible. Under Change…, a "Work
  in" choice offers New worktree (default) or Main checkout, with today's
  warning when a writing agent already runs there.
- **Remote (SSH) projects** registered by the app are stored in the shared
  file with their kind. The IDE hides them.
- **Registration resolves to the main checkout** in both tools. The app's
  "Add repository" on a worktree folder registers its main checkout. This
  changes the app's current behaviour, where `rev-parse --show-toplevel`
  registers the worktree itself.
- **Removing a project** removes it from the shared list, for both tools.
  Each tool keeps its own sessions for that project in its own state,
  hidden. Adding the project again brings them back. The IDE's "running
  agents" check covers only IDE agents; the app's agents for that project
  keep running.

## Left to the builder

- Swing component choices (tree vs list with group rows), exact paddings,
  icons, hover-action rendering.
- The shared file's exact name, schema, and change-notification mechanism,
  within the rules above.
- Order of work and how to split it into commits/PRs.

## Decisions made by me, not discussed explicitly

- The IDE tree shows only IDE sessions (the user agreed sessions stay per
  tool; showing the app's sessions read-only was offered and not taken up).
- Project order: this window's project first, then alphabetical.
- ⌃⌥N opens New task for the current window's project.
- Read-only tasks run in the main checkout and skip branch/worktree.
