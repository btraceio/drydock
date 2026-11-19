# Bounded recovery pass 1 — workflow configuration and local launch integration

## Authority and output

User approved recovery through bounded agent planning passes after the broad planner timed out without a contract. Planning only. No production/test/build changes, builds, remote provisioning, commits, or nested agents. Read Requirements-5UJyXN2P.md in this directory in full, including recovered amendments. Those product decisions are settled: do not reopen them. Read AGENTS.md; respect applicable repository rules.

Write only the bound WorkflowContract-5UJyXN2P.md in this directory (and randomized evidence scratch files here if needed). The coordinator owns cairn. Consult its narrow briefs and the existing project-sdlc-workflows nodes; never start a second investigation.

## Draft-first deliverable

The previous pass spent 39 turns on recon/escalations and saved no contract before its 30-minute deadline. Do not repeat that failure. After reading requirements and the few central source seams, immediately create your bound contract with the known decisions and clearly marked unfinished sections; refine it in place as you inspect further. Save before investigating another subsystem. Final output is a complete metaplan decision contract for THIS SLICE, not a full task list. Run the mechanical contract gate. A partial draft must never claim final pass.

## Your bounded design scope

- Target-specific schema, source parsing, resource bounds and invalid/missing distinction.
- Registered-project checkout lookup, canonical path/symlink containment, applicable selection, snapshot lifetime and skill/file/text/include resource model.
- Prompt policy/integration composition: free-form prePrompt vs issue workflows, default compatibility, slash/blank exceptions, explicit user model selection vs project role strategy, and preservation of edited prompts.
- Source display and explicit default/cancel in existing dialogs/actions; all I/O off EDT; cancellation/late completion fencing.
- Explicit PR/local intent routing through local workbench and run/request paths without changing diff identity/content. Preserve Sphinx and automatic maintenance paths. Include the approved explicit-Run/no-op correction and truthful race handling.
- review_scope include=instructions output contract and its authorization/mode separation.
- Define the SMALL typed integration boundary used by the next pass for remote materialization: immutable selected workflow/bundle description (repository-relative files/includes), target/mode/context, materialized resource reference and cleanup owner. Do not design SSH, Python bridge, remote snapshots or remote review internals.

The whole product scope still includes remote infrastructure. It is not a global non-goal: it is the next design pass. State clearly which interfaces the next pass must implement and which existing constraints it inherits. Assign cross-cutting/local invariants INV-1 through INV-39 and acceptance AC-1 through AC-29; reserve higher numbers for the second pass. Use only as many as genuinely useful.

## Source leads (not a mandate to read entire large files)

- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskPreset.java
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskPlan.java
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskDialog.java
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskLauncher.java and TaskTreePanel.java: inspect action/launch seams, not full file dumps; their size constraints are in Requirements.
- intellij-plugin/src/platform/java/app/drydock/intellij/tasks/WorktreeReview.java and PullRequestReview.java
- intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewRequest.java, ReviewTabOpener.java, SessionReviewScopeResolver.java
- intellij-plugin/src/main/java/app/drydock/review/ReviewInstructions.java
- intellij-plugin/src/main/java/app/drydock/mcp/McpToolRouter.java and ReviewScopeReader-related implementations found by actual symbol search
- intellij-plugin/src/main/java/app/drydock/config/UserConfig.java and state/json; project schema is separate from user-global configuration
- intellij-plugin/src/main/java/app/drydock/projects/SharedProject.java

Read source around exact symbols; do not repeat all earlier whole-repository archaeology. Distinguish existing APIs from proposed ones. Evidence-backed implementation choices are yours to settle; ask supervisor only for a real contradiction with approved requirements, not for routine helper naming. Read tests/build configuration enough to name real verification commands, but do not execute builds/tests.

## Contract format and gates

Read /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/metaplan-pi/skills/metaplan/references/contract-format.md and expansion.md. Script:
`python3 /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/metaplan-pi/skills/metaplan/metaplan.py check-contract <bound-output>`

Goal/non-goals, chosen mechanisms, rejected alternatives with reasons, hard MUST/MUST NOT invariants, explicit dependency directions and interfaces, testable acceptance with 'verified by', assumptions with blast radius/action, open decisions. The next pass must be able to consume it without inventing configuration policy. Use ~250–400 lines if sufficient; do not expand into coder tasks. Final response <=6 lines: path, scope, gate result, risks, next-pass boundary. Do not claim the whole feature contract or verification is complete.
