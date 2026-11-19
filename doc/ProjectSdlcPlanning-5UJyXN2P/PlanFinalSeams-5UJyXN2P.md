# Final plan seam corrections — no product changes

The coordinator read the entire corrected Plan (842 lines, 76 tasks). Both metaplan gates pass and outstanding escalation count is zero, but the following source-backed seams still invalidate the claimed fidelity pass. Correct these specifically, not the whole architecture.

## S1 Remote rename must work detached and guard the actual state swap

Current evidence:
- `intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java#renameFromAgent` (around295) finds the project through AgentExecutionRegistry.execution(id).host and FAILS without a live execution. Reusing this callback cannot provide approved detached remote `session_rename`.
- `intellij-plugin/src/platform/java/app/drydock/intellij/session/DrydockSessionService.java#applyAgentRename` (around1876) queues an initialization.thenApplyAsync callback, then calls ApplicationStateStore.update. Calling the renamer is NOT its actual mutation commit.
- `intellij-plugin/src/main/java/app/drydock/app/ApplicationStateStore.java#update` runs the pure transform and swaps in-memory state under its existing single lock, then schedules asynchronous persistence. Guarding the WHOLE update invocation inside its executing background callback can cover the actual swap without waiting for disk I/O.
- Contract requires the current-generation permit at actual store/rename commit, with no later stale-generation mutation and no mutation replay. Plan T55/final provenance instead declares initiation the commit point because the existing callback has no abort. That is an unauthorized weakening, not a permissible residual risk.

Planner: settle a narrow application-owned remote rename path independent of console/project ownership, using the SAME authoritative ApplicationStateStore and shared existing pin/collision/title rules. Preserve the local rename callback behavior. Factor/reuse the mutation policy rather than fork it. Explicitly propagate the captured permit into the asynchronous work and guard the actual stateStore.update call (not only scheduling and not only the transform before its return). Define lock ordering so revocation cannot invert state/permit locks. No remote I/O under the short commit guard. The operation must still work for an enrolled/live detached remote agent and refuse foreign/revoked credentials. Amend only relevant contract seams/acceptance so the expander is not choosing architecture.

Expander: assign every needed shared-policy/service/application-wiring file and test, respecting <=5 files/task and dependencies with the other writers. Add a latch test: schedule rename, revoke before background mutation, release latch => state unchanged; allow a completed commit then lost response => count one/no replay. Test detached remote rename without AgentExecutionRegistry execution; do not test only callback invocation counts.

## S2 Follow the existing private-file credential delivery contract

Plan T58 says credentials arrive on stdin and T59 publishes via CommandRunner. `workspace/CommandRunner.run` accepts argv/options/output cap only and delegates to ProcessRunner; it exposes no stdin-payload API. The accepted contract already chose private local 0600 credential staging -> bounded BoxShell.copy -> verified remote private pending file -> helper atomic publication. Keep that route. Do NOT invent a generic stdin transport API/ProcessRunner expansion just for this task or put tokens in argv/environment. Have the helper consume only the scoped pending-file reference and validate owner/type/size/generation before atomic publish; clean local/remote pending credentials on failure. Keep manifest per-request reread and generation fencing.

## S3 Dependency/interface correctness

- T9 WorkflowMaterializer consumes Cancellation, but Cancellation is created by T6 and T9 depends only on T2, so W2 is not compile-ready. Put this small shared cancellation boundary in an earlier owned type task, or give T9 the true prerequisite and recompute waves. If the materializer uses ReviewIntent itself rather than extracted primitive fields, make T23 a prerequisite too. Do not silently invent a second cancellation type.
- T66 RemoteLaunchPreparation needs an actual owner that ensures the independently managed forward plus credentials/protocol state. Explicitly call the contract's RemoteReviewService capability/ensure boundary (T60), not just T59's credential-generation object. Add the true dependency; do not declare optional MCP prepared without arranging a live forward.
- T67/root production wiring must depend on the service composition it consumes (T61 where applicable). Make snapshot/data-source/cache instantiation and injection into the remote service composition explicit; no interface-only fake wired in production.
- Check referenced NEW types vs producer tasks, then recompute task dependency closure and unordered shared-file ownership. Compile-ready means actual type producers precede consumers, not merely that the declared graph is acyclic.

## Return boundary

Planner: target S1 contract clarification (plus confirm S2 existing route), no broad recon and no production edits. Expander: targeted S1–S3 plan corrections, no rewrite of unaffected tasks/requirements. Use edit; save incrementally. Both run their gates. The final plan must not claim early rename scheduling is actual commit or secret stdin exists. No build/tests/provisioning/commits. User implementation approval is still pending.
