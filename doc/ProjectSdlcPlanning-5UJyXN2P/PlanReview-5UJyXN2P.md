# Coordinator plan-fidelity review

Status: NOT READY for user implementation approval. Contract and plan mechanical gates pass, but there is one declared escalation plus concrete ownership/coverage defects. Full Plan-5UJyXN2P.md (462 lines/44,674 bytes) was read. No source/build/test changes were made. Correct only the design naming decision and expansion defects below; do not reopen approved requirements.

## E1 — contract type name collision (planner decision required)

The proposed explicit PR/local workflow target value is named ReviewSelection, but `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewSelection.java` already represents a different editor selection. Plan E1 correctly flags it but still says fidelity pass with blocked tasks.

Settle a distinct name/package, preferably `ReviewIntent` (verify no collision). Decide whether the immutable value belongs in the platform-free `app.drydock.review` package so core boundaries can consume extracted fields without a platform dependency; never change the existing editor ReviewSelection or its callers' semantics. Amend only occurrences referring to the NEW proposed target value in Contract; preserve references to existing editor selections. No behavior change or product question. Then re-run check-contract.

## Expansion corrections (expander ownership)

### F1 — Local provider delivery is not actually owned

T19 says host providers union the new staging root into read context and tests command generation, but its five owned files do not include ANY provider implementation. ClaudeAgentProvider reads c/r.launchResources() at its host add-dir and eval gates, CodexAgentProvider also reads launchResources() in create/resume; the new LocalWorkflowAccess field cannot work simply because CreateContext has it. The contract requires a separate typed workflow path so task-resource/eval refusals are not bypassed. Assign concrete create/resume/provider adapter changes, tests and dependencies for every affected host harness. Pi currently has no generic add-dir path; explicitly assess whether its ordinary filesystem read behavior needs code or only a compatibility test. Do not invent a CLI flag. Split APIs, Claude host/eval, and other harness integration into bounded tasks; no coder may edit files outside ownership.

### F2 — ReviewModelDialog source indicator has no owner

Contract Source/error UX explicitly requires NewTaskDialog AND ReviewModelDialog to identify effective project/default source. Plan wires NewTaskDialog but never owns `PL/tasks/ReviewModelDialog.java`. Give the existing review dialog/controller/source presentation an explicit task and test; cover Quick Review/existing-session feedback through their existing surfaces without adding a new editor. Preserve selected model/tier state and truthful fallback/edited indicators.

### F3 — SessionWorkflowResourceOwner lacks production lifecycle composition

T10 defines an application-owned manifest/lease owner; T20 passes leases to it; T32 starts/closes RemoteReviewService only. Explicitly own construction/injection/recovery/flush/cleanup for the single local+remote SessionWorkflowResourceOwner at the real application/service entry points. Wire session removal/Forget, app shutdown, startup recovery and required-resource Resume checks; terminal detach is NOT resource deletion. Constructor/unit tests on an isolated owner do not prove production wiring. Add focused lifecycle integration tests and actual dependency edges, preserving the one ApplicationState writer.

### F4 — Remote snapshot cache and authorization permits need explicit implementation ownership

Contract settles two snapshots/128MiB per session, 512MiB aggregate, lease-aware eviction and invalidation on source/transport/scope/session changes. T27/37 mention snapshots but do not explicitly assign this shared MCP+UI cache/lease/accounting lifecycle or boundary tests. Give it an explicit owner/task; do not create disconnected per-view and per-MCP caches.

Likewise trace generation-permit capture from authenticated remote HTTP dispatch through context operations to the actual store/rename commit, including async UI rename. Assign every needed server/context/adapter seam and fault test to task files; 'checked atomically at commit' in T30 is not enough if the required call-site/interface is unowned. Keep existing local endpoint behavior unchanged and no mutation replay.

### F5 — Honest task bounds and compile-ready sequencing

The plan says estimates include tests, but several tasks combine a whole new multi-concern subsystem, extraction from existing services and extensive new tests into a nominal ~300 lines. Re-slice especially T4 (path/link selection versus bounded capture), T19 (provider ownership above), T21 (managed process API versus argv/rule wiring), T23 (encoder versus safe remote unpacker), T27 (local query/parser extraction versus remote capture/parity/cache), and T31 (transport ownership/readiness versus credential generation/reconnect). Keep one concern, <=5 actual owned files and approximately 300 total changed lines INCLUDING tests per task. Estimates are estimates, not excuses to skip necessary code/tests. Do not expand into arbitrary microtasks; split at real interfaces already settled by the contract. Refactor extraction before new behavior, with existing behavior tests first. Avoid creating tasks whose declared done state cannot compile because an undeclared later task supplies its interface. Recompute dependency waves and shared-file exclusions.

### F6 — Exact paths, real citations and gate provenance

- Plan's global 'all paths relative intellij-plugin/' makes T21 `AGENTS.md` and T40 `doc/...` point at nonexistent locations. Use unambiguous repository-relative ownership paths or explicitly distinguished repository-root exceptions, and ensure dispatch tooling expands M/PL/TS/RR before handing files to a coder.
- Trail citations `kb/project-sdlc-workflows/find-*` are invalid. Use `project-sdlc-workflows/find-*`; retain `kb/` only for actual committed memories such as `kb/find-check-answerability-gap`.
- E1 is an escalation requiring a contract naming amendment, not a legitimate unmade decision under fidelity pass. Record its resolved provenance/accounting; final outstanding escalation count should be zero. Do not erase the fact it occurred from Usage.
- Contract-gate command under Verification must resolve actual artifact path from the stated working directory; it currently says run from intellij-plugin but uses bare Contract filename outside that directory.
- Match final acceptance mapping to ALL ACs and all MUST/MUST NOT with full force, not merely mention IDs. Include the exact approved fresh-local-default complaint exception, portable containment limitation, optional-MCP task/Resume matrix and real-workspace blocking evidence debt.

## What is already verified

Both metaplan gates exited0. Escalation parser returned one E1 for the ReviewSelection collision. Shared-file ownership in the original nine waves appears disjoint, and repeated file edits generally have dependency paths; recompute after revisions. The final contract now preserves the fresh local default-only bare-session/complaint case. No additional user product decision is needed for the corrections above.

## Return boundary

Planner: narrow contract name/package amendment and gate only; no broad architecture redo.
Expander: correct Plan against that contract and this review, run gates/escalations, append usage/escalation/fidelity accounting without erasing prior failures. Stop and escalate only if a correction truly needs an architectural choice absent from the contract. No implementation/build/tests/provisioning/commits. Coordinator will review the revised plan before asking the user to authorize implementation.
