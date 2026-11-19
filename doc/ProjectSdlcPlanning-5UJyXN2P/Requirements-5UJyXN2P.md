# Project SDLC workflows — approved requirements

Status: user-approved requirements, including the final target-specific namespace amendment. Planning only. Implementation requires a separate plan approval.

## User request and approvals

Initial request:
> I want to add an option to configure prompts to run for actions like:
> - implement
> - triage
> - review
>
> The Drydock provided prompt will still be availble to fall back if nothing else is defined, but user setting will take precedence.
> The user settings will be stored in .sdlc/config.json configuration file (or yaml, whatever is better) per-project, so they can travel with the project and obey the project idea of SDLC

Additional request:
> Hm, when you mentioned it - having the ability to also point the 'new task' pre-prompt to a skill or project defined standard pre-prompt would be usefult

Final amendment:
> one change - 'actions' should be more specific; 'implement' and 'triage' should be associated with issues/tasks, review with PR (but there may also be local changes review so :shrug:)

The user selected: issue namespace for the existing GitHub/Jira actions only; separate pullRequest and localChanges review entries. Saved/planned task actions are unchanged. The user explicitly authorized planner and plan-expander subagents for planning, not implementation.

## Goal

Allow a project's tracked configuration to replace Drydock's workflow methodology and free-form New task pre-prompt, retaining Drydock's integration contracts and defaults when not configured. IntelliJ plugin only. Support local and remote workspace-box launches in this change.

## Configuration and sources

Canonical filename: `.sdlc/config.json`, read only from the registered project's checkout, NOT the session/worktree/PR checkout. No hierarchy or global overrides.

Approved target-specific shape (illustrative paths and text):

```json
{
  "version": 1,
  "newTask": {
    "prePrompt": {"skill": ".agents/skills/task-intake/SKILL.md"}
  },
  "actions": {
    "issue": {
      "implement": {
        "skill": ".agents/skills/implementation/SKILL.md",
        "include": ["engineering/verification"]
      },
      "triage": {"file": ".sdlc/prompts/triage.md"}
    },
    "pullRequest": {
      "review": {"skill": ".agents/skills/code-review/SKILL.md"}
    },
    "localChanges": {
      "review": {"text": "Check correctness, compatibility, and test coverage."}
    }
  }
}
```

Every definition accepts exactly one of `skill`, `file`, or `text`:
- `skill`: repository-relative path to a project SKILL.md; preserve original skill-directory semantics for supporting references. This is following the skill instructions, not emulating all harness-specific frontmatter features or name discovery.
- `file`: repository-relative plain prompt file.
- `text`: inline instructions.
- Optional `include`: explicitly declared repository-relative supporting files/directories, particularly for remote delivery.

All resolved source/include paths must remain within the registered checkout, including symlink resolution. No URLs, environment expansion, shell execution, or installed-skill name discovery in the configuration. Do not modify the project's configuration automatically or install anything globally.

## Entry points and composition

1. Free-form New task uses `newTask.prePrompt`, replacing the built-in clarify-before-coding pre-prompt, NOT the user's task text. This layer does NOT apply to Implement, Triage, Review, or saved/planned task launches.
2. Existing GitHub and Jira Implement use `actions.issue.implement`.
3. Existing GitHub and Jira Triage use `actions.issue.triage`.
4. Ordinary PR reviews use `actions.pullRequest.review`; ordinary local-change reviews use `actions.localChanges.review`. Include Quick Review and explicit in-session Run review. Preserve the selected review presentation mode. Route by the actual reviewed target, not only the current working directory or caller label.
5. PR and local review entries are independent. A missing PR entry does not inherit the local entry or vice versa. They can reference the same skill to share a workflow without inheritance.
6. Explicit Review in Sphinx, review-chat replies, automatic refresh/recheck prompts, saved/planned task launches, and the standalone app remain unchanged.
7. Applicable precedence: per-launch edits > selected project definition > Drydock default. Existing prompt edits must survive any re-resolution/harness change. Do not invent a new general prompt editor.
8. A project definition replaces Drydock's methodology; do not append contradictory built-in clarification, quality gates, mandatory review delegation, or default subagent role assignments. Project owns methodology and delegation strategy.
9. Drydock retains target identity/context, session naming, existing permissions/modes, explicit user model selections, and the output/integration contract required to populate its UI. Configuration text does not grant authority or permissions.
10. No configured source: preserve the applicable existing behavior. In particular the free-form default remains clarify-first and unconfigured issue workflows keep their defaults.

## MCP integration

Extend the existing `review_scope` tool with opt-in integration instructions, provisionally `include=instructions`. These describe Drydock's output contract, separate from review methodology, and support the selected presentation mode. The launch directs the agent to the project workflow and the integration contract; project skills need no Drydock-specific instructions.

Retain existing scope authorization. No new general workflow-execution MCP tool. Consider remote capabilities carefully: do not invent that a remote agent has working Drydock MCP unless production wiring proves it. If preserving this requirement conflicts with the existing remote boundary, escalate the exact missing prerequisite instead of silently dropping remote reviews or the output contract.

## UX, resolution, and errors

- Hand-edit files; no project settings editor in scope.
- Show project/default source and identify the workflow/pre-prompt source at invocation. Preserve existing per-launch prompt editing.
- Resolve afresh for every invocation off the UI thread. Snapshot resolved configuration for the invocation; do not reconfigure running actions automatically.
- Missing configuration/action uses Drydock's default.
- Invalid JSON/schema, blank definitions, unreadable/missing referenced sources, unsupported versions, and invalid paths stop the affected invocation with a visible error.
- Offer explicit default-for-this-invocation or cancellation. Never silently substitute default workflow. Cancellation must not launch an agent/send a prompt or strand progress UI.
- Every async completion path clears progress. Do not weaken existing EDT/FX/process rules.

## Remote workspace-box support

- Included, not deferred to a local-only version.
- Transfer selected SKILL.md's complete skill directory (scripts/references/assets) plus explicit include paths. For file sources transfer the prompt and includes. Text is carried as instructions, with included resources if allowed by the settled design.
- Preserve repository/skill-relative references via isolated remote staging. Do not overwrite remote checkout workflow files.
- Use registered-checkout-selected content, not the remote checkout's potentially different version.
- Validate/bound reading and delivery; configuration is not executable. Determine safe concrete limits and lifecycle in the design, with evidence from existing launch/SSH facilities.
- Fail visibly for transfer problems/unavailable dependencies. Do not silently default or install prerequisites. An agent may run a skill script as permitted by its existing action permissions; loading configuration itself must not execute it.
- Do not promise static discovery of arbitrary script/runtime dependencies or silently transfer the entire repository. Skill directory + explicit extras is the agreed scope.

## Non-goals

- Standalone app feature parity.
- Saved/planned task workflow changes or a task namespace.
- Global settings, parent lookup, worktree overrides, automatic skill-name resolution, YAML support, expression engines, arbitrary shell hooks, auto-installation, or automatic commits.
- Replacing explicit Sphinx actions, review replies, or automatic review maintenance prompts.
- A settings editor, new workflow execution service/MCP command, or broader process-policy enforcement.

## Acceptance criteria

- AC-R1: No-config/missing-action compatibility for every affected entry point; independent PR/local fallback.
- AC-R2: Each supported source resolves from the registered project checkout even when the IDE/session/review uses another worktree; paths cannot escape that root.
- AC-R3: Free-form pre-prompt override replaces only the policy preamble; typed task and mandatory integration survive; issue/review/saved-task flows do not receive it.
- AC-R4: GitHub/Jira issue actions and actual PR/local review targets receive the matching project methodology without the displaced built-in methodology. Target context, permissions, explicit selected models, and review output mode survive.
- AC-R5: Per-launch edits and source indicators remain truthful across preparation, harness switches, retries, and fallback. Read at next invocation without restart; do not mutate an in-flight invocation.
- AC-R6: Invalid selected configuration stops and offers explicit one-time default/cancel. Missing config isn't an error. Cancel/exception paths cannot send, launch, or leave progress stuck.
- AC-R7: review_scope opt-in instructions are authorized, useful for the selected review mode, and separate from methodology; existing response behavior without opt-in stays compatible.
- AC-R8: Remote workflows stage selected skill/file/text resources plus explicit includes, preserve relative references, use the registered source rather than remote contents, retain needed integration, and clean up on success/failure/cancel according to launch lifecycle.
- AC-R9: Existing Sphinx/reply/automatic-review/saved-task/standalone paths are unchanged.
- AC-R10: Tests exercise production seams, not constants against constants. Include parser/path/composition cases, entry-point integration, UI fallback/progress, MCP authorization/mode, and remote transfer/resource cleanup with failure cases. Distinguish unit/platform/real-remote evidence; never imply unperformed verification.

## Known code leads (verify rather than assume)

- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskPreset.java: issue implement/triage workflow bodies and tier-role policy.
- .../tasks/NewTaskPlan.java: initialPrompt, CLARIFY_FIRST, TITLE_GUIDANCE, TASK_SEPARATOR behavior, slash command special case.
- .../tasks/NewTaskDialog.java: editable instructions, harness switch/recomposition, model choices, remote launch selection.
- .../tasks/TaskLauncher.java and TaskTreePanel.java: action launch callers; map all entry points before changing them.
- .../tasks/PullRequestReview.java: ReviewPrompt resolves scope asynchronously; fixed Sphinx channel vs built-in instruction.
- .../tasks/WorktreeReview.java and .../review/ReviewRequest.java: local and existing-session review dispatch.
- intellij-plugin/src/main/java/app/drydock/review/ReviewInstructions.java: methodology and required review output currently interwoven.
- intellij-plugin/src/main/java/app/drydock/mcp/{McpToolRouter,McpServer}.java: review_scope and handshake/tools behavior.
- intellij-plugin/src/main/java/app/drydock/{config/UserConfig,projects/SharedProject,agent/api/LaunchResources}.java: global config is NOT project config; SharedProject root identifies registered checkout.
- intellij-plugin/src/main/java/app/drydock/tasks/TaskGuidance.java and agent providers: existing skills/launch-resource packaging are leads, not authority to alter saved tasks or emulate native skill semantics.
- intellij-plugin/src/main/java/app/drydock/workspace/ and platform workspace/actions/session packages: remote provisioning, SSH, launch-resource constraints require complete-path tracing.

## Repository constraints and planning authority

Read AGENTS.md at this checkout and all applicable nested instructions. Work only at:
`/Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-jb-giddy-bronze-falcon`
branch `drydock/jb-giddy-bronze-falcon`.

Planning agents may read code and write ONLY their bound planning artifacts under this document's randomized directory. No production/test/build changes, no commits/pushes, no build/test execution or network remote provisioning needed for planning. Use /opt/dogbrew/shims/bin/gh if a GitHub call is essential; never change gh authentication.

Consult cairn via:
`python3 /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief <narrow-topic> --paths <relevant-files>`
Capture large output in a randomized file rather than flood context. Do not start an investigation. The initial broad brief matched many irrelevant global investigations; use repository memories and narrow paths. Cite only genuinely consumed/verified memories.

Plan small isolated services/helpers rather than adding policy/I/O to UI owners. Existing size measurements at planning start:
- TaskTreePanel.java: 2,727 lines / 133,190 bytes — already over the ~2,500-line limit. Flag any necessary modification and propose extraction/minimal wiring rather than growth.
- TaskLauncher.java: 1,733 lines / 91,598 bytes.
- McpToolRouter.java: 2,084 lines / 114,589 bytes — preserve its headroom; avoid putting resolution/packaging logic there.
New files should stay under ~1,000 lines / 50 KB; respect existing comment invariants, no fully qualified inline class names, ProcessRunner-only bounded external spawns, one persistent-state writer, and async progress/lifecycle symmetry.

Use metaplan gates and report unresolved decisions rather than silently narrowing the approved requirements. Planner must ground architecture in actual sources and identify remote/MCP portability blockers. Expander must stay within that contract, tasks at most 5 owned files and approximately 300 changed lines, one concern each, with exact tests and invariant/acceptance traces. Sequential ownership of a file across dependency stages is necessary when appropriate; do not claim concurrent writers are safe.


## Recovered approved amendments and planning clarifications

This section records explicit user approvals (remote infrastructure, explicit review-target routing, the managed tunnel lifetime exception, and portable local filesystem containment) and coordinator-settled implementation clarifications. The compatibility details in items 4–7 must be surfaced in the implementation plan for approval; they are not additional user questionnaire approvals. These amendments supersede narrower readings of the earlier requirements. Implementation still requires separate plan approval.

1. **Remote ordinary-review infrastructure is in scope.** Include authenticated remote Drydock MCP plus remote scope, diff, source, backend, and workbench-output wiring for existing workspace-box sessions. A tunnel alone is insufficient. Preserve existing remote-launch offers and gates (currently Claude free-form New task and issue actions). Do not add remote Pi/Codex, a fresh-remote-review launch checkbox to the local PR workbench, or remote PR cloning/provisioning. Minimal explicit PR/local target context and workbench access for existing remote sessions are included. Ordinary local PR review remains reachable.
2. **Route by explicit reviewed target, never `scope.kind` alone.** Carry PR versus local intent through the origin, workbench, start, and session request paths. A PR review selects `actions.pullRequest.review` even when its existing underlying worktree diff includes uncommitted changes. Preserve diff content and identity; incidental branch names, PR association metadata, or the session working directory alone must not select a PR workflow. PR intent requires an explicit valid selection.
3. **MCP survives terminal detach while the IDE remains open.** The tunnel is IDE/application-owned, not console-SSH-owned. The user authorized a narrow AGENTS.md exception: a long-lived SSH tunnel through a ProcessRunner-owned lifecycle API, with bounded connection/readiness, keepalive failure detection, graceful/forced shutdown and reader cleanup, but no fixed overall lifetime timeout. Keep all unrelated one-shot process rules. No ad hoc ProcessBuilder outside ProcessRunner, detached daemon, ControlMaster, or hidden terminal. Console-owned-only tunnels and periodic 180s/240s tunnel rotation are rejected. Do not replay possibly executed mutating MCP calls after loss; credentials and manifests require generation fencing.
4. **Remote tool surface remains narrow.** The remote endpoint/router serves `REVIEW_TOOLS_AND_RENAME` only, with `Spawn.FORBIDDEN`. It must neither advertise nor directly dispatch task/spawn tools, inject `TASK_INSTRUCTIONS`, nor install task guidance. Preserve local backend behavior and remote refusal of task-receipt/host `LaunchResources`. Remote SDLC resources have a distinct typed payload. Project skills cannot assume remote `task_*` support, and Drydock does not statically discover arbitrary skill dependencies. A bundled Python-stdlib stdio bridge with per-request credential refresh and atomically published generation-fenced manifests over the independently managed reverse SSH tunnel is an approved design mechanism; Python3 and SSH forwarding are explicit preflight prerequisites, never auto-installed. Verify framing, revocation, session/scope isolation, detach, restart/port changes, and no mutation replay. Keep secrets out of argv/logs/source indicators; private config/manifest paths are per remote session, never shared credentials.
5. **Legacy live remote agents are not hot-enrolled.** A pre-feature Claude process alive in tmux without the new MCP config cannot acquire it by merely reattaching or updating a manifest. Explain unavailability and provide manual instructions to use the existing Stop and Resume controls, warning that Stop interrupts active work; offer cancellation/dismissal. Do not add a combined destructive migration action, restart automatically, or retain/replay the review request. After explicit Stop and Resume, use the SAME nonempty stored `agentSessionId`; missing identity is a hard refusal, never a replacement conversation. Optional MCP enrollment failure stops review capability visibly, not an otherwise valid original task resume whose retained workflow resources and existing launch prerequisites are usable. There is no hidden mandatory-MCP migration mode; never kill a successfully started task to fake atomic enrollment. Verify enrollment against actual process/launch evidence, not a stale descriptor alone. Distinguish proven absence from inaccessible/malformed enrollment data. Already-enrolled surviving tmux processes reconnect without restart.
6. **Native command/blank-chat compatibility bounds the free-form prose pre-prompt.** A single-line native harness command beginning with `/` is sent byte-for-byte as typed: no pre-prompt, title/tier framing, or task separator. Show “Pre-prompt not applied: harness command”. A blank New task remains chat-only with no initial prompt; a configured pre-prompt alone does not launch a skill. Neither case consumes/validates `newTask.prePrompt` or stages unused bundles, so an unused malformed/missing pre-prompt cannot block them. Prose tasks and issue/review actions retain normal validation and explicit default/cancel errors. Command/blank-to-prose transitions must invalidate the bypass and resolve the applicable definition. No provider-specific command expansion or new two-message orchestration feature.
7. **Explicit Run review is an invocation, not silent focus.** Workbench Run review on an existing session resolves its explicit PR/local target, workflow and presentation, authorizes the actual session/scope, and submits once through the existing prompt-delivery path; it never starts a second agent. This deliberately corrects the previous join-only behavior even without configuration and must be visible at plan approval. Pure reveal/focus/join remains non-invoking. The idle-tree Quick Review already-running race may remain join-only, with truthful “focused existing session; no new review sent” feedback. Detached enrolled agents keep MCP/workbench data, but a new human-triggered request without an attached delivery console explains that Resume/reattach is required; discard the request rather than queue/replay it or add a tmux prompt sender. Require explicit retry after attaching, and never automatically retry ambiguous delivery.
8. **Portable containment is user-approved.** Use supported Java lexical/canonical path and resolved-symlink containment, bounded regular-file reads, no-follow operations where available, and before/after identity/attribute/tree checks. Reject static escapes and detected changes with visible errors. This is normal containment in a trusted registered checkout, not hostile-race-proof confinement or a transactional filesystem snapshot. Do not add a local native/JNA dependency, local Python, global native flags, new OS restriction or mandatory SecureDirectoryStream capability. Preserve selected skill/include layout and limits. The already planned remote Python helper may retain POSIX descriptor-relative safeguards on supported Linux boxes.
9. **Separate workflow-resource failures from optional review tooling.** Fresh New task/issue launches and ordinary same-ID task Resume may proceed with truthful review-tools-unavailable feedback if only optional MCP preparation/enrollment fails. Selected or retained workflow resources, conversation identity and existing core launch prerequisites remain mandatory. Explicit remote or existing-session Review requires ready authorized MCP, scope/data/output and attached human-prompt delivery; a workflow default cannot repair missing capabilities. Preserve the existing fresh LOCAL DEFAULT-only review-launch complaint behavior when no project override is consumed: if integration cannot resolve, do not send a review prompt or claim a review ran; the existing bare-session launch plus visible complaint remains. Configured fresh local review must not execute a naked project methodology without its required integration. No automatic replay or new sender.
