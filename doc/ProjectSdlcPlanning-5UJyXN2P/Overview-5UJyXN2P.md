# Project SDLC workflows — implementation approval brief

## Status

Requirements and design are settled. The implementation plan passed mechanical gates and coordinator semantic/fidelity review. **The user approved implementation with bounded coding/checker agents and no commits or pushes.** Execution packets and captured-test tooling are prepared; production verification is tracked separately from these planning results.

Authoritative detail: [Requirements](Requirements-5UJyXN2P.md), [Contract](Contract-5UJyXN2P.md), [Plan](Plan-5UJyXN2P.md). [Usage](Usage-5UJyXN2P.md) records measured agent usage and the recovery/correction history.

## Proposed implementation

| Workstream | Deliverable |
| --- | --- |
| Project workflow configuration | Registered-checkout `.sdlc/config.json`; skill/file/text definitions and declared supporting resources; target-specific issue, PR, local-change and free-form pre-prompt selection; explicit default/cancel and portable path containment. |
| Launch and review integration | Preserve edits, target context, permissions and explicit models; show source; wire existing host/eval providers; expose integration-only `review_scope` instructions; make explicit Run review submit once while reveal remains non-invoking. |
| Remote execution infrastructure | Isolated workflow delivery, Python bridge, private generation-fenced credentials, independently IDE-owned SSH tunnel, enrollment/readiness and reconnect without restart or mutation replay. |
| Remote review and verification | Actual remote diff/source snapshots through shared backend/workbench stores, detached tools/data, explicit attached prompt delivery, lifecycle/authorization tests and real-workspace end-to-end gates. |

The plan has **79 bounded task slices across 13 dependency waves**, covering **180 source/test/document ownership paths**. This is substantially larger than a configuration-reader change: the included remote review infrastructure accounts for much of the scope. These are execution slices, not 79 separate product features. Each task owns at most five files; repeated writers are ordered. Runtime estimates/LOC targets are not measurements. Independent writers must use isolated worktrees; otherwise execute tasks sequentially in this session's checkout. No commit/push authority is implied.

## Important behavior boundaries

- `newTask.prePrompt` applies to free-form prose only. Native slash commands remain unchanged; empty input opens plain chat.
- `actions.issue.implement` and `.triage`, `actions.pullRequest.review`, and `actions.localChanges.review` are independent; missing definitions use the corresponding Drydock default.
- PR versus local policy follows explicit review intent, not incidental branch metadata or internal scope kind. Current diff contents stay unchanged.
- Invalid selected configuration requires explicit one-time default or cancellation. User edits survive and source indicators remain truthful.
- Local path handling is portable Java containment in a trusted checkout, not a guarantee against an adversary racing filesystem replacement. No new native dependency or OS restriction.
- Remote tools survive terminal detach while the IDE remains open. A new human-triggered review prompt still requires reattaching the console and retrying explicitly.
- Optional MCP failure does not prevent a valid task/issue launch or same-conversation resume. Review requires its tools/data. Required resources and conversation identity remain hard prerequisites.
- Legacy live remote agents lacking the bridge are not hot-enrolled or restarted automatically. Existing Stop/Resume controls are the manual route.
- Keep the existing fresh local DEFAULT-only empty-prompt/bare-session/visible-complaint exception; configured review never executes naked methodology without its integration.
- Remote tools are review plus rename, not saved-task/spawn tools. Remote rename uses the existing authoritative state owner and checks the captured permit around the actual synchronous state swap, including when the console is detached.
- No standalone changes, saved-task workflow changes, new remote providers, remote PR-cloning/provisioning UI, settings editor or global skill installation.

## Verification and cost

Planning gates: `check-contract` PASS, `check-plan` PASS, unresolved escalation count **0**. Independent task audit: **0** unordered shared-file writers, valid dependency-wave ordering, known new-type producer dependencies present, maximum **5** owned files/task. Earlier review defects are resolved in the final contract/plan; the T77 focused test selector was corrected to include its `app.drydock.app` package.

Implementation gates remain unrun: focused parser/path/composer/controller/provider/MCP tests; complete IntelliJ test and compilation gates; actual filesystem checks; and an authorized existing Linux workspace proving resource delivery, real review output, two-session isolation, detach, tunnel loss, IDE restart with the same surviving agent, no lost-response mutation replay, legacy resume and cleanup. Mock-only success is not completion. If the real workspace gate cannot run, report blocking verification debt rather than imply remote support is verified.

Expected resource cost is explicit, not benchmarked: each enrolled remote session owns an SSH forwarding process and a Python MCP bridge; remote probes and visible-workbench refresh add bounded I/O. Workflow retention and snapshot caches have per-session/aggregate ceilings in the contract. Measure real behavior during acceptance; no performance improvement is claimed.

Recovered planning-subagent cost is **at least USD 6.99**; the first interrupted run and parent-session/web usage are excluded. See the full ledger, not an inferred total. Recommended implementation review focus: credential/generation and actual-commit authorization, retained-resource cleanup, remote source identity/freshness, and cancellation across async UI/launch boundaries.

## Approved execution

Implement against this plan using bounded coding/checker agents, with staged verification and no commits or pushes unless separately requested. Writers are serialized in this checkout. Execution artifacts are in `../ProjectSdlcExecution-Hi6B8bhj/`.
