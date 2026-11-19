# Bounded recovery pass 2 — remote review infrastructure and contract integration

## Authority, inputs, output

Planning only. User authorized bounded design passes after the previous all-in-one planner timed out. Read Requirements-5UJyXN2P.md and completed WorkflowContract-5UJyXN2P.md in this directory, plus checkout AGENTS.md. The recovered amendments are settled, including the narrow long-lived ProcessRunner SSH exception. No production/test/build changes, builds, remote provisioning, commits or nested agents. You may write only bound Contract-5UJyXN2P.md and randomized evidence scratch files in this directory. Coordinator owns cairn; consult only.

Your output is the COMBINED metaplan contract: retain the first slice's settled workflow decisions, interfaces, invariants and acceptance criteria, and add the remote mechanism. Do not re-investigate/rewrite the workflow slice or invent a third product configuration layer. Amend Goal/Non-goals so this is the whole approved feature, not a slice-specific document. Preserve IDs from the first pass; add remote INV ids starting at 40, AC ids starting at 30, and uniquely numbered REJ/ASM entries. Keep the format's section ordering. This is NOT the expanded task list.

## Draft-first requirement

Create the bound combined contract EARLY, copying the first contract's settled content and adding clearly unfinished remote sections, before broad remote recon. Refine in place. A draft may have OPEN markers but cannot claim expandability until resolved. The previous broad pass saved no contract in 39 turns: do not repeat it.

## Settled remote choices — do not ask again

- Existing remote provider/gates only (Claude workspace-box New task/issue paths); no new remote PR launch/provisioning feature or remote Pi/Codex.
- Include complete remote scope/diff/source/backend/workbench support, not just a tunnel.
- PR/local policy follows explicit review target; preserve current diff contents and identity conventions.
- MCP remains usable through terminal detach while IDE is alive. Independent IDE/application owner.
- Long-lived SSH forwarding managed THROUGH ProcessRunner with no fixed overall lifetime, bounded readiness/connectivity/keepalive/shutdown/readers; narrow AGENTS.md amendment is authorized. No console-only tunnel, periodic 180s/240s rotation, ad hoc ProcessBuilder outside ProcessRunner, ControlMaster, detached daemon or hidden terminal.
- Remote endpoint serves REVIEW_TOOLS_AND_RENAME only with Spawn.FORBIDDEN. No task tools/instructions/guidance. Existing task-receipt/host-path LaunchResources remote refusal remains. Remote SDLC resources have a separate typed payload.
- Python-stdlib stdio bridge and atomically published per-session generation manifests are a settled design mechanism, with explicit Python3/SSH-forwarding preflight, no auto-install. Secrets not in argv/logs; private permissions. Credential/port refresh must work for a surviving enrolled tmux agent, backed by tests, not assertion. Never replay possibly completed mutating requests.
- Legacy live agents without MCP config: visible explanation/manual existing Stop+Resume instructions; no forced restart or combined destructive migration. Resume same nonempty stored conversation id or refuse. Descriptor existence alone is not proof of actual enrollment.
- Detached agent can use MCP/read workbench data, but new human review prompt delivery requires existing console Resume/reattach and explicit retry, not a new tmux prompt sender/queue.

## Your architecture scope

1. Managed ProcessRunner transport ownership/lifecycle and reverse-forward readiness, disconnect/reconnect, sharing/isolation and final-session close/shutdown.
2. Remote credentials/bridge/enrollment/session manifest generation fencing, preflight and secure resource staging/cleanup. Materialize the workflow-resource interface settled by pass 1; don't smuggle host task paths to remote.
3. Explicit remote session/repository/checkout identity and authorization; bounded remote diff/source acquisition using existing SSH/CommandRunner facilities where appropriate. Distinguish source bytes, working-tree state, base/head versions and local cached data. Prevent arbitrary host reads, traversal, stale local-source substitution or unrelated remote-session access.
4. Remote review backend and workbench integration, including lifecycle/readiness/invalidation and actual UI output contract. Scope/source identity must remain coherent across reconnect, edits, review requests and restart. Define the interface that local renderers and existing MCP tools consume without duplicating the whole app architecture.
5. Integration with existing start/stop/detach/resume/state ownership, source indicators/errors and explicit default/cancel. If transport failed, don't call a built-in workflow a successful fallback when it needs the same unavailable transport.
6. Unit/platform/real-workspace verification gates, credential authorization and malformed-input bounds, legacy session and no-mutation-replay cases. Real remote gates remain planned, not run.

Read precise production paths/tests to ground these choices; do not scan all source or restate the full existing backend. Use a narrow recon sequence and save after each concern. Source leads from earlier verified reads: DrydockSessionService#startRemoteLaunch and remote resume; ClaudeAgentProvider#persistentCreateCommand/#persistentResumeCommand; SshCommandBuilder; ProcessRunner and workspace CommandRunner/BoxShell; SessionReviewScopeResolver; ReviewBackend; ReviewScope/registry and DiffService/source-reading callers. Inspect other symbols only when their call edges matter.

## Reconciliation and coordinator gate

Preserve the first contract's methodology/authority boundaries. If a proposed remote interface contradicts the first contract, ask supervisor with the exact conflict; don't silently reinterpret it. Architectural choices within these settled rules are yours, not another product questionnaire.

Run metaplan check-contract and resolve OPEN entries. Then call contact_supervisor(reason=need_decision) naming the saved combined contract, gate result, the main interfaces crossing the two slices, and remaining assumptions. WAIT for coordinator semantic/fidelity review before returning completion; the workflow will start the expander after you return. Apply only requested contract corrections, rerun the gate, and return <=6 lines with the bound path, gate result, architecture summary and residual verification risks. No code.

Metaplan directory: /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/metaplan-pi/skills/metaplan. Read its contract-format.md before writing. Cairn script: /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py. Use /opt/dogbrew/shims/bin/gh only if essential; never alter auth. Check oversized owner files and propose minimal wiring/extractions rather than exceeding reviewability limits.
