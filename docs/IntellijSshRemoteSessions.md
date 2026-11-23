# SSH remote sessions in the IntelliJ plugin

**Status:** implemented. A repository that lives on another machine — reached over the
user's ambient SSH (`~/.ssh/config`, agent, keys; nothing stored or managed) — can be
registered from the Drydock tool window and run Claude or Terminal sessions on that host.
This is the plugin's SSH remote repositories feature
(originating in `docs/superpowers/specs/2026-07-21-ssh-remote-repos-design.md`);
it is a separate arm
from the workspace-box arm (`docs/WorkspacePersistentSessions.md`), which is tmux-backed
and survives the IDE. An ssh-remote session's agent dies with its ssh client.

## The add flow

The tree or inbox context menu's empty-space selection offers **Add Remote Repository…**:
a host (editable combo, seeded from `~/.ssh/config` `Host` aliases — `Include` is not
followed), a repo path on that host, and Add. Validation is a real ssh round-trip —
`git rev-parse --show-toplevel` in BatchMode through `GitStatusService` — and the resolved
toplevel is what registers. Failures classify from exit code and stderr, with the app's
wording: host key not accepted (run `ssh <host>` once), auth failure, git not on the
host's non-interactive PATH, not a git repository, unreachable. A duplicate
(host+path already registered) is refused on the placeholder root's canonical form.

Registration writes a `SharedProject` carrying the `SshRemote` and its deterministic
placeholder root (`/.drydock-remote/<percent-encoded host>/<remote path>`) into the
shared projects file. The per-install repository the
session paths key on is registered on first use with the same `SshRemote` carried over;
remote-ness is always derived from that repository, never guessed.

## The degraded session contract

Remote sessions run the provider's remote branch: `ssh -t` with keep-alives
(`ServerAliveInterval=30`, `CountMax=6`), the remote command doing `cd <remote path>` and
`exec claude`. The contract is deliberately reduced, and every part is a consequence of
the agent running on another machine:

- **No MCP.** Drydock's MCP server is a Mac-loopback process a remote agent cannot
  reach; a launch that requests host-path resources is refused, never silently dropped.
- **No eval.** The eval routing is a local side channel; an eval session is refused with
  a message naming why. (The app silently dropped eval on remote; a refusal is the honest
  form of the same constraint.)
- **No session id at create.** The provider's remote branch emits plain `claude`, so the
  row persists without a bound id and the resume opens the provider's own picker — the
  app's shipped contract for plain SSH remotes. A later `--resume <id>` resumes by id
  when one was bound.
- **Resume skips the local probes** (no `Files.notExists` on the placeholder, no
  transcript look-up, no late bind); a vanished remote conversation surfaces as the
  provider's own error in the terminal.
- **Ends settle from the status alone.** The ssh client's exit code is not the agent's,
  so nothing records it, and no liveness probe runs — the agent does not outlive its ssh
  client, so the SIGHUP the end delivers is the stop. A spontaneous exit renders as
  "Session ended — resume to reconnect" (the app's connection-lost rendering), because a
  dropped connection reads the same as a finished agent.
- **No activity data.** The activity monitor cannot see a remote agent (OQ10); remote
  surfaces say where the agent runs and never claim working/idle.
- **Fresh start is refused** for a remote row (the conversation lives on the host), as
  are transfer and the Done flow; Stop ends the ssh client and Forget removes the row.
- **The console's local cwd is the user home** — never the placeholder, which is not a
  local path.

## New task flow on a remote project

The dialog pins Work in to the repository itself (there are no local worktrees to scan
and none are offered), gates the agent picker to remote-capable providers (Claude and
Terminal; Pi and Codex are shown disabled with "Doesn't support remote sessions"), and
hides the eval offer. No project window opens for a remote launch: the console is an ssh
client shown as a tab in the window the launch came from, like the workspace-box arm.
The Start ▸ split button enables remote project entries; the former "opens only in the
Drydock app" restriction is gone.

## Divergences from the app

- **No git indicators, no 30 s poller.** The app polls remote repos' git status for its
  sidebar indicators; the plugin has no git-indicator surface at all (its tree shows PR
  and CI badges, not dirty state), so there is nothing to route remotely and no poller
  was built. Remote projects are skipped by every local git join in the gather.
- **Worktrees, diffs, Review and PR/issue listings do not apply** to a remote project
  row: the tree shows the project and its sessions, and nothing else.

## Verification

Unit and platform tests cover the pure decisions: registration idempotence and the
placeholder scheme (`SharedProjectsServiceTest`), the remote carried into the per-install
repository (`ProjectRepositoryServiceTest`), the tree's remote rows and their sessions
(`TaskTreeModelTest`), the split button's enabled remote entries
(`NewSessionSplitActionTest`), the remove question's host:path wording
(`TaskActionsTest`), the strip/tab copy (`AgentStripTextTest`), and the launch chain's
refusals and settle are covered by the service-level suite. No live-SSH end-to-end run
has been performed against a real remote host (the same status the workspace-box doc
records for its live-box checks).
