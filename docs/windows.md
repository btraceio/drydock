# Drydock on Windows

This is a how-to for **using** Drydock on Windows. The technical
rationale (why the terminal backend differs, what's intentionally not
on Windows, CI coverage) lives in
[`docs/windows-terminal-spike.md`](windows-terminal-spike.md); the
README has the [project-level overview](../README.md).

## What works

Drydock is usable on Windows for everyday work, with the caveats
below. The features that work today:

- The JavaFX application (sidebar, session list, settings modal, theme
  toggle, worktrees directory picker, MCP server, agent activity
  badges).
- The JediTermFX + pty4j embedded terminal over ConPTY (so the same
  Windows `cmd.exe` you use elsewhere).
- Agent providers (Claude, Codex, Pi, claude-code) — when their CLI
  is on `PATH`. The `app.drydock.diag.command` override can drive a
  terminal-only session without an agent installed.
- Per-repo state, session search, review UI, GitHub PR list, MCP tool
  routing, worktree creation/deletion. All backend-agnostic paths.

## What does not work (yet)

This is the intentional v1 scope, not oversights. Each item is
isolated and a future change can address it without touching the
rest of the project.

- **No `libghostty` (Metal-rendered) terminal.** A future Windows
  Ghostty port is upstream's work, not Drydock's. The JediTermFX
  terminal renders fine, but it is not the libghostty surface.
- **No native host shim.** The macOS AppKit shim
  (`native-host/DrydockTerminalHost.m`) is AppKit and does not build
  on Windows; no Win32 equivalent in v1.
- **No MSIX / signed `.exe` / installer.** v1 is a zipped directory
  with `bin/drydock.bat` — no Windows installer, no code signing, no
  auto-update. A colleague moves the directory wherever they like.
- **No dock-tile label.** Windows taskbar shows the launcher name
  verbatim ("drydock.bat" or however you rename it).
- **Terminal theme hot-reload is a no-op.** JediTermFX theming goes
  through a `SettingsProvider`, not a hot config-file reload. The
  terminal opens dark; switching theme via the toggle does not change
  the terminal colors. Closing the tab and opening a new one picks
  up the current theme.
- **`readScreenText` is unwired.** Returns `""` on the JediTermFX
  backend. The review board / PR diffs flow that uses this is not
  tested on Windows; diffs may show empty. (Not a crash — just empty
  text. The flow is otherwise functional.)
- **No `claude`-on-Windows CI.** CI cannot install the agent CLI.
  Manually verified, not CI-verified.

## Installing

Two ways to get Drydock on a Windows machine.

### Option A — Runtime image (recommended for everyday use)

A teammate can build a self-contained image on any machine with
JDK 26 + the checked-in Gradle wrapper:

```cmd
git clone https://github.com/btraceio/drydock.git
cd drydock
.\gradlew.bat :app:windowsRuntimeImage
```

The image lands at `build\image-windows\`. Copy that directory
anywhere (e.g. `C:\Apps\drydock\`) and launch:

```cmd
C:\Apps\drydock\bin\drydock.bat
```

Or, from the build output:

```cmd
build\image-windows\bin\drydock.bat
```

A first run creates a per-user state file at
`%APPDATA%\drydock\state.json` (the location is configurable through
the Settings modal).

### Option B — Download from CI

Each commit to `main` produces a `drydock-windows-image` artifact
on the `windowsRuntimeImage (Windows)` job. Download it from the run
page (`Actions` → the run → scroll to "Artifacts"). Unzip and
double-click `bin/drydock.bat`. Retention is 7 days.

### Option C — `./gradlew :app:run` (dev loop)

For iterating on the app, not for everyday use. Same checkout, but
launches the JVM from the JDK 26 toolchain instead of a packaged
runtime image:

```cmd
.\gradlew.bat :app:run -Papp.drydock.terminal.backend=jediterm
```

Useful with diagnostic flags:

```cmd
.\gradlew.bat :app:run ^
  -Papp.drydock.terminal.backend=jediterm ^
  -Papp.drydock.diag.repo=C:\path\to\throwaway-repo ^
  -Papp.drydock.diag.autoCreateSession=true ^
  -Papp.drydock.diag.command=echo DRYDOCK_SESSION_OK ^
  -Papp.drydock.diag.autoExitSeconds=15 ^
  -Papp.drydock.diag.stateFile=C:\temp\diag-state.json
```

`-P` is the right flag for diag properties on the `run` task
(`-D` does not reach the forked JVM). The full list of diag
properties lives in
[`DrydockApplication.start()`](../app/src/main/java/app/drydock/DrydockApplication.java).

## What you'll see on first run

1. A JavaFX window opens with a dark theme. No native menu bar
   (Windows uses the standard window chrome).
2. The "no repositories" empty state is shown. Click **Add
   repository** in the sidebar to register a local Git repo.
3. Once a repo is registered, an empty session list shows. Click
   **New session** (or use the app's keyboard shortcut) to open one.
4. The terminal session opens a `cmd.exe` shell via ConPTY. Type
   `dir` to verify; exit with `exit`.

The `agent picker` is in Settings. If a colleague has `claude`,
`codex`, or `pi` on `PATH`, the corresponding row in Settings is
active. The launcher is `app.drydock.launcher.JBangBootstrap` for
`jbang`-style setups.

## Common questions

**Where does state live?** `%APPDATA%\drydock\` — the sidebar widths,
window state, agent settings, session catalog, MCP tool log. There
is no central "settings.json"; the Settings modal writes to the
same directory. To reset, close Drydock and delete that directory.

**Why is the terminal `cmd.exe`, not PowerShell?** The JediTermFX
backend shells out via `cmd.exe /c <command>` for backwards
compatibility with the rest of the app's command model. PowerShell
is not currently selected; an explicit
`-Papp.drydock.terminal.shell=powershell` (or similar) is a
follow-up if needed.

**Why does the launcher say "Drydock" and the taskbar say
"drydock.bat"?** Windows taskbar shows the executable filename;
`drydock.bat` is the launcher. A real `.exe` would let the taskbar
show "Drydock" — a future MSIX / signed-exe packaging pass.

**Does it work over Remote Desktop / a Windows VM in the cloud?**
Yes, in my testing — JavaFX renders to whatever virtual display
RDP / the VM console presents. Performance is the only caveat: a
GPU-accelerated host renders much faster than a remote-display one.

**Does it work in WSL?** The app is a Windows GUI process; it runs
in the Windows side, not inside WSL. A WSL user can run it on
Windows, but the agent CLI (`claude`, `codex`, `pi`) inside WSL is
a separate consideration — point Drydock at the Windows-side
binary, or run the agent inside WSL and tell Drydock to use a
Windows-side shell.

**My repo has no remotes / is a fresh `git init`.** The app does
not require a remote. Click **Add repository**, point at the
local checkout, and the app picks up branches and worktrees from
the local Git state.

## Filing a bug

When something goes wrong on Windows specifically, include:

- The Drydock commit (`git rev-parse HEAD` in the checkout, or the
  `b3c8a21`-style hash from the CI artifact page).
- The JDK version (`java -version` in `C:\Program Files\...` or
  wherever JDK 26 is installed).
- Whether the issue reproduces with `-Papp.drydock.diag.autoExitSeconds=10`
  (so the app exits cleanly, useful for log capture).
- The output of the failed terminal command, if reproducible.

For app-wide questions, the existing GitHub Issues / Discussions
are the right place. Windows-specific quirks that should be in
this doc — please file with the `windows` label.
