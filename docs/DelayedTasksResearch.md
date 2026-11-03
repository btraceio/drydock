# Delayed tasks — research notes (IntelliJ plugin)

Terminology: a **task** is the definition of the work. A **session** is one
run of a task in an agent harness (Claude, Codex, pi).

## 1. IntelliJ platform task support

The platform has "Tasks & Contexts" (`com.intellij.platform.tasks`). It is a
separate plugin from "Issue Trackers" (`com.intellij.tasks`).

Verified facts:

- IntelliJ IDEA 2026.2 removed the Task Management and Time Tracking plugins
  from the default distribution because of low usage. Both plugins are still
  on Marketplace.
  Source: https://blog.jetbrains.com/idea/2026/07/whats-fixed-intellij-idea-2026-2/
- In 2026.3, `intellij.platform.tasks` and `intellij.platform.tasks.impl` moved
  from core into the bundled plugin `com.intellij.platform.tasks`.
  Source: https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html
- `TaskManagerImpl` stores its state in the workspace file:
  `@State(name = "TaskManager", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))`
  (`platform/tasks-platform-impl/src/com/intellij/tasks/impl/TaskManagerImpl.java:96`,
  intellij-community master). That is `.idea/workspace.xml`: per user, per
  project, not shareable, not a plain text file per task.
- `TaskManager` has one active task per project: `getActiveTask()`,
  `activateTask(task, clearContext)`. Activation switches the changelist, the
  branch and the open editors of the current window
  (`platform/tasks-platform-api/src/com/intellij/tasks/TaskManager.java:79-81`).
- `LocalTask` holds changelists, branches, shelf name and time-tracking work
  items (`LocalTask.java:18-81`). It has no relation between tasks: no
  prerequisite, no follow-up.
- `TaskManager.createLocalTask(String summary)` takes only a summary.
- Integration point for other trackers: extension point
  `com.intellij.tasks.repositoryType` (`TaskRepositoryType`, `TaskRepository`,
  `Task`). A repository returns remote `Task` objects to the "Open Task" popup.

Assessment (inferred from the facts above):

- The model does not match Drydock. Drydock runs one session per worktree,
  often many in parallel, in other windows. Tasks & Contexts has one active
  task per window and changes that window's branch and editors.
- The feature is not installed by default on the target IDE (2026.3).
  A hard dependency forces a Marketplace install on every user.
- It gives no storage format we want, no dependencies, and no body text.
- The only reusable part is the `TaskRepositoryType` extension point. With it,
  Drydock tasks could appear in the stock "Open Task" popup and commit-message
  templates. That is optional, and it is out of scope for a first version.

What the platform does give us without the Tasks plugin:

- The bundled Markdown plugin: editor, preview, and relative-link navigation
  for `.md` files. (To be verified on 2026.3 before we rely on rename
  refactoring of links.)
- Standard file editing, VFS refresh and undo for task files.

## 2. Comparable tools

| Tool | Storage | Relations | "What can I start now" |
|---|---|---|---|
| Backlog.md | one `.md` per task in the repo | `--dep`, graph, validation | views, CLI, MCP |
| beads (`bd`) | database + sync, not markdown | dependency graph | `bd ready`, `bd blocked` |
| Task Master AI | `tasks.json` canonical, generated text files | dependencies, validation | `task-master next` |
| Spec Kit | `tasks.md` generated from spec and plan | order and phases | no live state |
| Claude Code Tasks | JSON in `~/.claude/tasks/<list-id>/` | `blockedBy` / `blocks` | `TaskList` |
| Obsidian Tasks | inline in markdown | `🆔 id` on blocker, `⛔ id` on blocked | `is not blocked` query |
| Linear | server | blocked by / blocking / related / duplicate | filters |
| Jira | server | link types with inward/outward phrases | JQL |

Sources:
- https://github.com/MrLesk/Backlog.md
- https://www.npmjs.com/package/@beads/bd
- https://www.taskmaster.one/
- https://github.github.com/spec-kit/reference/agentic-sdd.html
- Claude Code docs, tools reference (seen through web search only, page not
  opened directly); data-loss report:
  https://github.com/anthropics/claude-code/issues/78147
- https://github.com/obsidian-tasks-group/obsidian-tasks/blob/main/docs/Getting%20Started/Task%20Dependencies.md
- https://developer.atlassian.com/cloud/jira/platform/issue-linking-model/

Patterns that repeat:

- Store one direction only. Obsidian stores "depends on" on the blocked task.
  Claude Code stores `blockedBy`. The inverse view is computed. Jira's
  inward/outward pairs are a known source of confusion.
- A "ready" query (all prerequisites done) is the main value of relations.
- Markdown-first tools keep metadata in YAML front matter and leave the body
  free.

## 3. UX research on deferred tasks

- Bellotti et al., CHI 2004, "What a To-Do": 287 to-dos from 7 professionals.
  68 % were done within a week. Tasks not done after about two weeks were
  unlikely to be done at all. The authors ask for a way to handle stale,
  low-importance to-dos that nobody deletes.
- Haraty, McGrenere, Tang, GI 2015 / IJHCS 2016: personal task management
  practice changes over time, and people abandon dedicated tools when the
  upkeep cost is higher than the return.
- Jones et al., ASIS&T 2008, "I Give Up!": five factors in abandonment of
  information-management strategies — visibility, integration, co-adoption,
  scalability, return on investment.
- Whittaker, HCI 2005: central to-do lists fail when tasks must be copied out
  of the place they came from.
- Masicampo & Baumeister, JPSP 2011: a concrete plan reduces the intrusive
  thoughts an unfinished goal causes. This is a cognition result; it does not
  say whether the task is done later.

Design consequences (inferred):

- Visibility: tasks must sit in the tree the user already looks at, not in a
  separate window.
- Integration: create a task from where the idea appears (New Task dialog,
  an agent console, an editor selection) with no copying.
- Staleness: show age, and give a cheap "drop" action.
- Low upkeep: a plain file with optional front matter; no required fields.
