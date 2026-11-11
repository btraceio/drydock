# Wrap-up: stacked-PR execution of task trees (2026-11-10)

## Question
How should Drydock execute a task tree so the work lands as stacked pull
requests — one branch per task, claimable follow-ups — instead of one session
per segment on one branch?

## Current best answer
Implemented on the fancy-dewy-meerkat worktree (branch of drydock), spec in
`docs/StackedPrExecution.md`. Decisions confirmed with the operator in chat:
S1 hybrid (linear chain = one session landing each task on its own stacked
branch; forks split into one session per child), S2 dependents start when the
prerequisite's branch exists (review gate holds statuses, not stacking), S3 one
draft PR per pushed task branch (base = prerequisite's `dd/<id>` branch, roots
base on the default branch; agent pushes, Drydock opens the PR), S5 stacking is
per-repository (cross-repo keeps the D8 stage model). Task
`drydock-execute-task-trees-as-stacked-prs-stacked-branch-per-6s9d` is in
REVIEW awaiting human confirmation; nothing is committed.

## Settled findings (graph nodes)
- `find-stacked-pr-execution-design` — the decisions and as-built shape:
  `StackUnits` decomposition (maximal chains, forks split, merges stack on
  first prerequisite), `StackBranches` (`dd/<task-id>`),
  `PlanLaunch.stacks` persistence (codec v1; member always written, legacy
  files synthesize one root unit per repo), per-unit `PlanDriver` requests,
  `StackLaunch.launchUnit` (10 s poll, 4 h branch-wait timeout), stacked
  prompts for Claude/Codex/pi, `GitHubStackPrService` + `StackPrService`
  watcher, both start flows wired, guidance skill v2.
- `find-planlaunch-codec-roundtrip-optional-members` — optional member you
  synthesize on read must always be written (byte-stable round-trip).
- `find-plugin-nested-outcome-import-gotcha` — gh write services: Outcome
  subtypes are outer-class members; `X.Outcome.Created` imports don't exist.
- `find-intellij-plugin-standalone-gradle-build` — plugin tests run from
  `intellij-plugin/` (root build only has `:app`), JDK 25 toolchain.
- `find-task-guidance-version-bump-touchpoints` — SKILL.md edits need
  `CURRENT_VERSION` bump + three `"1"` literals in `TaskGuidanceTest`.

## Refuted dead-ends
None this session.

## Open questions
- `q-task-claim-policy-d12-conflict` — agent `task_claim` launch policy
  (immediate vs. confirm balloon vs. scoped to confirmed stacks). Blocks S4.

## Immediate next step
Human review of the working tree (18 files, uncommitted). On confirmation:
commit in logical chunks per the repo's commit convention, then implement S4
(claimable follow-ups, task
`stacked-prs-claimable-follow-ups-task-claim-mcp-tool-tasks-u-yr8o`) starting
from the launch-primitive that exists (`StackLaunch.launchUnit`), after the
task_claim policy question is decided.
