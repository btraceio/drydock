# Stacked-PR execution of task trees — requirements

Status: S1, S2, S3 and S5 IMPLEMENTED on the stacked-PR branch (see "As
built"); S4 (claimable follow-ups) is recorded as follow-up task
`stacked-prs-claimable-follow-ups-task-claim-mcp-tool-tasks-u-yr8o` and not
implemented. Decisions confirmed in conversation 2026-11-10 (O1–O4 defaults
confirmed by "looks ok"). Extends `DelayedTasksRequirements.md`
(D-numbers referenced as Dxx); that document's task model, store, plan store
and review gate are unchanged except where overridden here.

## Goal

When Drydock executes a task tree (a start plan), the work lands as stacked
pull requests: one branch per task, each branch based on its prerequisite's
branch, one draft PR per task. Follow-up tasks discovered during the run are
claimable: a human or an agent can start them later as new sessions stacked
on their prerequisite's branch, and ready follow-ups start by themselves.

## Decisions

| # | Decision |
|---|----------|
| S1 | Execution unit: hybrid. A linear chain (each task exactly one dependent, single parent) runs as ONE session that lands each task on its own stacked branch inside its worktree. Where the tree forks (2+ dependents), each forked child is its own session on a branch stacked on the parent task's branch tip. |
| S2 | A dependent task's session/branch may start as soon as its prerequisite's branch exists (first commit landed) — the D16 review gate governs plan advancement and task status, not stacking. |
| S3 | Drydock opens one draft PR per task branch automatically. Base = the parent task's branch; a root task's base = the repository's default branch. The PR base follows standard GitHub stacking semantics; Drydock does not rebase anything. |
| S4 | Follow-ups are claimable three ways: a human claims from the Tasks UI; an agent claims through the MCP tools; and a follow-up that becomes ready inside a running stack auto-starts. |
| S5 | Stacking applies within one repository. Cross-repository prerequisites keep today's segment/stage model (D8): a branch cannot stack across repos, so a cross-repo dependent still waits for stage completion and receives the prerequisite worktree as extra directory (D13). |

## Mechanics

### Branch and PR lifecycle

- Branch per task, minted with `BranchNameRules` validation, named from the
  task id (slug + suffix), under one Drydock namespace (proposal: `dd/<id>`).
- Chain sessions create their branches by checking out inside their own
  worktree as they advance (`git checkout -b dd/<child> dd/<parent>`-style);
  Drydock pre-creates nothing for a chain beyond the first branch.
- Forked children get a dedicated worktree checked out at the parent branch
  tip at launch time (`git worktree add`), so a fork never races the chain's
  later commits (the fork cuts the stack at claim/launch time).
- The agent commits and pushes each task branch; when the task is done or
  review and its branch is pushed, Drydock opens the draft PR through the
  new `GitHubStackPrService` (a direct `gh api` write; `GhCliService`
  remains read-only), orchestrated per repository by `StackPrService`.
  PR title = task title, body = task body.
- Auto-start/claim of a follow-up launches a new session on a worktree at the
  prerequisite's branch tip, even while the prerequisite is `review` (S2).

### Who may claim / auto-start scope

- Auto-start applies only to follow-ups inside the executed stack's tree
  (descendants of the plan's tasks). An unrelated task that merely becomes
  ready still follows D12: shown as ready, never started by itself.
- Agent claiming is a new MCP tool (`task_claim`): it registers the calling
  session's intent and Drydock launches the claimed task as its own stacked
  session. The tool refuses when the prerequisite has no branch yet, when the
  task is owned by a live session, or when the graph refuses (P3 rules).
- Human claiming: a Claim/Start action on a follow-up row in the Tasks UI,
  same launch path.

### Interaction with the existing plan model

- The launch unit changes from one session per segment (D7) to one session
  per chain/fork node inside a segment. `StartPlanner`'s closure, refusals
  and segment staging (D8/P3/P4) are reused; a new pass splits each
  single-repository segment's task set into chains and fork points.
- Stage advancement (P4.7) still keys on task statuses; S2 only decouples
  child-launch timing from review within a stack.
- The done-review flow (D16) is unchanged: `done_by: human` tasks still park
  in `review` for confirmation; stacking simply does not wait for it.
- The segment prompt (`TaskPrompt`) gains the stacked-branch instructions:
  branch per task in dependency order, commit and push per task, fork
  children are NOT this session's work.

## As built

- `StackUnits` (pure): the launch-unit decomposition -- maximal linear
  chains, forks split, merges stacking on the first prerequisite. A unit
  waits for its first task's unfinished in-segment prerequisite branches.
- `StackBranches` (pure): the `dd/<task-id>` naming convention (O1),
  validated against the repository's remotes only at mint time.
- `PlanLaunch.StackedUnit` + `stacks` map: the confirmed stack shape,
  persisted per repository in the plan file (codec still schema version 1;
  the member is always written, and a file written before stacked execution
  synthesizes one root unit per repository on read).
- `PlanDriver`: one launch per unit within a stage (the legacy shape is one
  unit per share); `SegmentRequest` carries the unit's base and wait
  branches.
- `TaskPrompt.stackedPrompt`: the per-unit prompt -- branch per task, push
  per task, review gate holding statuses but not branches (S2). The user's
  dialog branch pick is superseded by the stack's own names; the confirmed
  start point, agent, mode and window survive (O-adjacent deviation from
  the T09 instructions-edit contract, deliberate).
- `StackLaunch` (platform): launches a unit's session on a fresh worktree
  whose branch is `dd/<first-task-id>`; a stacked unit's launch waits for
  its wait branches, polling every 10 s with a 4 h bounded timeout (S2).
- Both start flows launch stacks: the single-segment flow via
  `launchStacked`, the cross-project driver via the per-unit production
  launcher.
- `GitHubStackPrService` + `StackPrService` (O2, O4): a draft PR per task
  branch, opened when the branch is PUSHED (an upstream ref exists) and the
  task is done or review; base = the first in-repository prerequisite's
  branch, else the default branch; exactly one attempt per task per
  session, every refusal logged visibly. The agent pushes (O4); Drydock
  never pushes.
- Guidance skill version 2 gained the stacked-execution section.

## Open (to confirm)

- O1: Branch namespace `dd/<task-id>` acceptable? -- CONFIRMED ("looks ok").
- O2: Draft PRs auto-created on first push of each task branch -- correct
  trigger, vs. PR when the task is marked done? -- CONFIRMED; as built, the
  PR opens when the branch is pushed AND the task is done/review, so a
  pushed-but-unfinished branch opens nothing.
- O3: Agent `task_claim` launches immediately, or only with a user-visible
  confirmation balloon (drydock otherwise never lets an agent start work
  without a confirmed plan, D12)? -- OPEN with S4 (not implemented).
- O4: Push responsibility: agent pushes (assumed above) vs. Drydock pushing
  task branches at PR-creation time. -- CONFIRMED: the agent pushes.
