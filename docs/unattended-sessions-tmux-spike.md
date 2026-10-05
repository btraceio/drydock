# Unattended sessions — tmux persistence spike

**Status:** spike complete (code-level). Flag-guarded, OFF by default
(`-Dapp.drydock.tmux.persistence=true`). Not yet verified in a full GUI
run. 2026-09-25.

## Goal
Let agent sessions (claude, …) keep running after Drydock shuts down, and
reattach to them on restart — so a Drydock binary update doesn't disrupt
sessions, and (eventually, remote arm) an SSH session survives the local
machine turning off.

## Why it isn't a flag flip
The agent process is a child of Drydock's in-process libghostty PTY; when
the JVM exits the child gets SIGHUP and dies. `SessionManager.normalizeLoadedState`
encodes this ("No terminal process survives an app restart" → RUNNING
becomes INACTIVE). There is no libghostty API to detach or hand off a PTY,
and freeing a surface with a live child crashes the JVM. So the survival
layer must be a **separate long-lived process** that owns the PTY.

Drydock's MCP server is in-process on an ephemeral loopback port
(`127.0.0.1:0`), so a session that survives a restart has a stale
`--mcp-config`; claude reads it at startup only. Reattached sessions lose
Drydock MCP tools until the agent is restarted. This is a known, deferred
limitation (not addressed by the spike).

## Approach: tmux as the persistence layer
Launch the agent inside a daemonized tmux session:
`tmux new-session -A -s drydock-<managed-id> -c <wd> '<agent cmd>'`.
The tmux **server** (reparented to PID 1) owns the agent's PTY and
outlives Drydock. Closing the Drydock surface kills only the tmux
**client** (detach); the agent keeps running. On restart the same `-A`
command reattaches; a startup probe flips persisted sessions back to
RUNNING when their tmux session still exists. Proven out-of-band: a
detached tmux session outlives the spawning shell; the server's PPID is 1;
`tmux capture-pane` from a fresh shell reads the live pane.

## What the spike changed (all behind the flag)
- `app.drydock.process.TmuxPersistence` — the single tmux seam: binary
  probe, `sessionName`, `wrapLocalCommand`, `sessionAlive`
  (`tmux has-session`), `detachClient` (`tmux detach-client`). All via
  `ProcessRunner`, 3s timeout, off the FX thread.
- `TerminalSurface.closeWithoutSignal` (+ Ghostty/JediTerm impls) — closes
  without sending Ctrl+D, which tmux would forward into the agent (claude
  sees EOF → exits). The close path detaches the tmux client instead.
- `SessionManager` — wraps the agent command at the create+resume call
  sites (local only; remote deferred); `closeSession` detaches then
  `closeWithoutSignal`; `onSurfaceClosed` keeps a detached RUNNING session
  RUNNING (async-verifies via `sessionAlive`); `revalidateTmuxSessions`
  runs on startup to mark reattachable sessions RUNNING.
- `MainWorkspace.pollForExitedProcesses` — for a tmux-backed surface,
  `processExited` means the tmux client detached/died, not that the agent
  exited; probes `sessionAlive` off the FX thread before settling (alive →
  keep RUNNING + close the dead client tab; dead → markSessionExited).
- `app.drydock.process.TmuxPersistenceSpike` + gradle task
  `tmuxPersistenceSpike` (no native build; SKIP when tmux is absent).

## Verified
- `:app:compileJava` / `:app:compileTestJava` clean.
- `:app:test` (app.drydock.app.* + ui.* + terminal.* + process.*): 727
  tests, 0 failures.
- `./gradlew tmuxPersistenceSpike`: PASS against real tmux 3.6a —
  `wrapLocalCommand` builds the correct string (POSIX-escaped),
  `sessionAlive` true/false correctly, `detachClient` safe with no client.

## Not yet verified (needs a GUI run)
- Full launch → quit Drydock → restart → reattach cycle with a real
  ghostty surface rendering a tmux-attached claude TUI.
- `readScreenText` after a fresh reattach (tmux redraw).
- Ctrl+C through tmux vs claude's Kitty-keyboard negotiation.

## Deferred
- **Remote (SSH) arm:** the same mechanism with the tmux server on the
  remote host (`ssh -t <host> 'tmux new-session -A -s <name> -c <path>
  "<cmd>"'`). Needs the `ManagedSessionId` to reach the provider's SSH
  command builder — today `CreateContext`/`ResumeContext` carry none.
- **MCP on reattach:** a reattached session's Drydock MCP tools are dead
  until the agent restarts. Product decision pending.
- **Bespoke supervisor daemon** (the alternative to tmux): ruled out unless
  tmux hits a wall the GUI run exposes.