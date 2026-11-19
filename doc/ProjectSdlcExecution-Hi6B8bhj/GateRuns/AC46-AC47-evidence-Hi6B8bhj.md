# AC-46 / AC-47 real-workspace gate evidence (in progress)

## Box
- Authorized by user 2026-11-19: workspace `jb1` (user-provisioned; `workspace-jb1`, i-09cc6870997581e47.workspace.infra.dog, x86_64 Linux, devcontainer ddoghq/dd-source). Reachable via SSH; prerequisites verified: Claude Code 2.1.326, Python 3.12.10, tmux 3.6, git 2.56.1. No pre-existing tmux session needed: the plugin manages `drydock-<managedSessionId>` tmux sessions itself (SshCommandBuilder.tmuxSessionName).
- Scenarios to record per AC-46: two isolated sessions; foreign credential/scope denial; forwarding/Python prerequisite failure; legacy manual Stop/Resume with same conversation ID; source isolation; bundled script/resource use; authenticated review_scope(include=instructions); tour+sections/finding output in workbench; detach MCP continuity; attached explicit Run; cleanup.
- AC-47: SAME tmux Claude PID/start identity + conversation ID across console detach, forward loss/recovery, IDE restart; next request succeeds with refreshed manifests, no replay of lost mutating request; unreachable cleanup debt then reconnection/deletion.

## Run log
- (pending — first app launch below)

## Run 1 findings (2026-11-19 14:11-15:15, sandbox IU-2026.3 -> workspace-jb1)

PROVEN so far: user-authorized box jb1; proxy bootstrap OK after dotfiles-sync installed
ddtool/uv on the box; launch refused-chain debugged (box mapping stale name, clone
discovery symlink-blindness, clone-path override); remote session LAUNCHED and WORKS:
tmux drydock-482ca578... running `claude --model sonnet -n ... --session-id 16dd8945...`
with the framed pre-prompt + human prompt delivered; agent responds. This is the first
real remote leg of AC-46.

DEFECTS the gate caught:
1. REVIEW MCP NEVER ATTACHED: box argv has no --mcp-config; box project mcpServers = [].
   Root cause: DrydockPluginService wires RemoteForwardOwner with a STUB probe —
   `(host, exclude) -> { throw new IOException("remote port probe not wired"); }` and a
   readiness lambda returning false (the recorded "real forward probes" ledger item).
   ensurer.ensure() fails -> Capability not ready -> RemoteStage empty -> launch proceeds
   WITHOUT review tools (fail-open per the optional-MCP rule). Blocks: review_scope, tour,
   workbench output, rename, detach continuity — i.e. most of AC-46 and all of AC-47.
2. Session row shows no remote indicator; tooltip says "folder missing" instead of
   remote-host:path (row stores the placeholder root; tooltip reads it as a local folder).
3. Fixed en route (pre-gate environment): workspaces daemon wedge; jb1 provisioning;
   ghostty submodule re-init + SDKROOT=MacOSX26.5.sdk for the native build (zig 0.15 vs
   MacOSX27.2 tbd arm64e.x1); sandbox Start-button modality starvation (nonModal hop
   behind a modal dialog — fixed in WorkflowWiring/WorkflowUi/Ui.modality()).

AC-46/AC-47 verdict: NOT PASSED — blocked on defect 1 (known ledger debt now on the
critical path). Per ASM-11: feature remains unverified; do not narrow scope.
