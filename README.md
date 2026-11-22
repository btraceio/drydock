# Drydock

Drydock is an [IntelliJ platform](https://plugins.jetbrains.com) plugin that
runs agent CLI sessions (`claude`, `codex`, `pi`) against git worktrees from
inside the IDE. Its UX model: **one IDE window (project) per worktree**, the
agent console as a pinned editor tab in that window, and a durable session
catalog shown through IDE-native surfaces (an **Agents ▾** widget in the main
toolbar of every window, and an **Agents** tab on the Welcome screen).

## Features

- **Agent sessions in editor tabs** — the real agent CLI runs in an embedded
  terminal (`PtyCommandLine → KillableProcessHandler → TerminalExecutionConsole`),
  hosted by a custom `FileEditorProvider`. Opening a project starts nothing;
  only an explicit start does. Closing a window stops its agents.
- **Worktree-per-window flow** — new task worktrees are created from the IDE,
  each opened as its own project window with live branch and dirty-state
  awareness.
- **Git & GitHub awareness** — repository status, review (branch-vs-main scope
  in the Commit tool window, findings as gutter cards in the diff viewer), and
  the finish flow: merge locally or push + PR. Checked-out pull requests are
  named as the PRs they are, whoever opened them.
- **MCP tools for your sessions** — sessions Drydock starts can call back into
  the plugin: read and reply to the review comments you left on a diff, create
  worktrees and open sessions in them (bounded; a session an agent started
  cannot spawn further ones), and list registered repositories and running
  sessions.
- **Task trees** — delayed tasks with worktree placement, stacked-PR execution
  of task trees, and babysitting sessions that watch a PR while you are away.
- **Remote repositories over SSH** — register a repo on a remote host via
  ambient SSH auth; sessions run the agent on the host. Requires `git` and the
  agent CLI on the host's non-interactive PATH, a POSIX login shell, and an
  already-accepted host key.
- **Workspace boxes (experimental opt-in)** — run agent sessions on a remote
  Datadog workspace machine with persistent tmux-backed sessions.

## Building

The repository root is the plugin's Gradle build (IntelliJ Platform Gradle
Plugin 2.x, resolving IntelliJ IDEA Ultimate 2026.3 — about 1.5 GB on first
use). The build needs JDK 25 (the IDE's JBR floor):

```sh
./gradlew test buildPlugin   # the CI build
./gradlew runIde             # sandbox IDE
./gradlew verifyPlugin       # locally, before a release (extracts ~4 GB per IDE)
```

The source layout keeps the headless core provably platform-free:

- `src/main/java` — the `core` source set: headless core, zero IntelliJ
  classes on its compile classpath (proven by `compileCoreJava`, not by grep).
- `src/platform/java` + `src/platform/resources` — the `main` source set: the
  IDE glue; `plugin.xml` lives here.
- `src/test/java` — both the core suite (JUnit 5) and the platform glue tests
  (JUnit 3-style `BasePlatformTestCase`, Vintage engine), one `test` task.

## State

- `~/.drydock/` — the registered-projects list (`projects.json`, shared and
  locked across IDE configurations), user config (`config.json`), and the
  task store.
- The IDE's config directory (`<ide config>/drydock/`) — each IDE
  configuration's own state: sessions, review annotations, MCP tokens.

## CI

`.github/workflows/intellij-plugin.yml` runs the test suite and `buildPlugin`
on a Linux runner for every push to `main` and every pull request.
