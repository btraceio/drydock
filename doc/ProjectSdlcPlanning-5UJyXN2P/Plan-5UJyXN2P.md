# Implementation plan: Project SDLC workflows with managed remote review infrastructure

meta: expander=cairn-expander (corrected revision), contract=Contract-5UJyXN2P.md (amended; no OPEN-n; check-contract passed), baseline HEAD bec9e0f7. Planning only: no source/test/build/remote action performed. Every `- files:` entry is a REPOSITORY-ROOT-relative path (expanded from short forms; no dispatch-time prefix expansion is needed): `intellij-plugin/src/main/java/app/drydock/` (core), `intellij-plugin/src/platform/java/app/drydock/intellij/` (platform), `intellij-plugin/src/test/java/app/drydock/` (tests), `intellij-plugin/src/main/resources/drydock/remote/` (new bundled Python resources), plus the two repository-root exceptions `AGENTS.md` (T37) and `doc/ProjectSdlcPlanning-5UJyXN2P/AcceptanceEvidence-5UJyXN2P.md` (T76). Prose inside tasks uses class names only. Line estimates are estimates INCLUDING tests; tasks are sliced at contract-settled interfaces (refactor-only extraction tasks precede behaviour tasks), own at most 5 files, depend only on tasks whose interfaces they compile against, and no two tasks sharing a file are unordered (checked mechanically when waves were computed: 76 tasks, 0 unordered shared-file pairs). Corrections to the first revision: E1 resolved to core `app.drydock.review.ReviewIntent` (T23); F1 provider ownership (T29-T33, T64-T65); F2 ReviewModelDialog (T19); F3 resource-owner production composition (T14, T34/T35, T61); F4 shared snapshot cache (T51) and permit/commit-guard seams (T54-T56); F5 re-slicing; F6 paths, citations and gate working directories.

Citation convention: `project-sdlc-workflows/find-*` are investigation-TRAIL node ids (`btraceio--drydock/drydock-jb-giddy-bronze-falcon/project-sdlc-workflows/...`, personal trail, planning authority only, no `check:` command). `kb/find-*` are committed repository memories. A fresh `investigation.py brief project-sdlc-workflows --paths <provider and session-service files>` and `brief review-instructions` returned "No matching learnings"; no memory dead end constrains these tasks beyond the contract.

## Tasks

### T1 Strict JSON parse entry point
- files: intellij-plugin/src/main/java/app/drydock/state/json/JsonParser.java, intellij-plugin/src/test/java/app/drydock/state/json/JsonParserStrictTest.java
- depends: none
- invariants: INV-3, INV-6, INV-39
- change: add `JsonParser.parseStrict(String,int maxDepth)` on the existing value model: reject duplicate members, non-RFC whitespace/number forms, invalid Unicode scalars, depth over the cap; `parse(String)` untouched (~150 lines incl. test).
- tests: JsonParserStrictTest drives production parseStrict (duplicates, leading zeros, lone surrogates, depth 32/33); existing JsonParserWriterTest stays green.
- done when: AC-2 strict-syntax cases pass and legacy `parse` output is unchanged.
- memories: project-sdlc-workflows/find-approved-scope

### T2 Headless workflow value types
- files: intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowTarget.java, intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowResolution.java, intellij-plugin/src/main/java/app/drydock/sdlc/SelectedWorkflow.java, intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowBundle.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowValueTypesTest.java
- depends: none
- invariants: INV-2, INV-8, INV-9
- change: new `app.drydock.sdlc` types: WorkflowTarget (5 leaves), sealed WorkflowResolution {Missing, Selected, Invalid(problem)}, SelectedWorkflow (RepositoryId, target, source descriptor, prompt text, bundle, digest), WorkflowBundle (normalized relative entries, hashes, exec bit, relative links, measured counts). Defensive copies; no Path/secret/handle (~250 lines incl. test).
- tests: WorkflowValueTypesTest: value semantics, Invalid never Optional.empty, no absolute path inside a bundle.
- done when: AC-2 Invalid-vs-Missing typing and the AC-7 immutability prerequisite verified; types import no Swing/IntelliJ.
- memories: project-sdlc-workflows/find-approved-scope

### T3 Workflow config decoder
- files: intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowConfigDecoder.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowConfigDecoderTest.java
- depends: T1, T2
- invariants: INV-3, INV-4, INV-8
- change: decode `.sdlc/config.json` via parseStrict: integer version 1, namespace containers with unknown-key rejection, only the five selected leaves, exactly one of skill/file/text, include array rules, `skill` must name a `SKILL.md` file, null/blank/type errors, unselected-leaf isolation, generic `actions.implement/triage/review` rejected (~280 lines incl. test).
- tests: WorkflowConfigDecoderTest: schema matrix, Unicode, duplicates, unrelated invalid leaf ignored, malformed root invalidates every consuming invocation.
- done when: AC-1 (missing leaf is Missing, PR/local independent) and AC-2 pass.
- memories: project-sdlc-workflows/find-approved-scope

### T4 Workflow limits and path/link selection plan
- files: intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowLimits.java, intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowPathSelector.java, intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowSelectionPlan.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowPathSelectorTest.java
- depends: T2
- invariants: INV-5, INV-6, INV-7, INV-8
- change: WorkflowLimits holds every settled ceiling. WorkflowPathSelector does only path logic with java.nio.file: `toRealPath` of the registered root, lexical escape rejection, manual DirectoryStream enumeration with NOFOLLOW_LINKS attributes, `readSymbolicLink` chains (depth 32), contained-link rewrite, destination-in-selected-set rule, `.git`/special-file rejection, logical-to-canonical mapping for linked parents. Returns an immutable WorkflowSelectionPlan (entries, link text, counts checked against limits during enumeration) and reads NO file bytes (~280 lines incl. test).
- tests: WorkflowPathSelectorTest on temporary trees: escapes, prefix tricks, absolute/URL/tilde, broken/cyclic links, linked-parent layout, entry-count and path-length exact/plus-one, special files; never asserts hostile-race immunity.
- done when: AC-4 (static containment half) and AC-5 (count/depth/path-length half) pass.
- memories: project-sdlc-workflows/find-approved-scope

### T5 Bounded byte capture with change detection
- files: intellij-plugin/src/main/java/app/drydock/sdlc/BoundedWorkflowCapture.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowCaptureTest.java
- depends: T4
- invariants: INV-5, INV-6, INV-7
- change: consume a WorkflowSelectionPlan: bounded ByteChannel reads (NOFOLLOW_LINKS where supported; absence of the option is not a rejection and a real open/permission error is never Missing) counted during consumption against 128 KiB prompt / 8 MiB file / 32 MiB aggregate, overlapping-entry dedupe with conflicting bytes/type rejection, before/after identity/size/mtime checks of root/ancestors/links/files and directory entry-set comparison, repeated lexical/canonical validation around each open. A detected change refuses with a retryable source-changed error and releases partials. Builds the immutable WorkflowBundle with hashes and exec bits. No SecureDirectoryStream/native/Python/process (~270 lines incl. test).
- tests: WorkflowCaptureTest: exact/plus-one bytes, huge binary, overlapping includes, injected controlled change refusal, provider-without-SecureDirectoryStream case; never claims hostile-race immunity.
- done when: AC-4 (change-detection half), AC-5 (byte half), AC-6 (capture half) pass.
- memories: project-sdlc-workflows/find-approved-scope

### T6 ProjectWorkflowResolver
- files: intellij-plugin/src/main/java/app/drydock/sdlc/ProjectWorkflowResolver.java, intellij-plugin/src/main/java/app/drydock/sdlc/Cancellation.java, intellij-plugin/src/test/java/app/drydock/sdlc/ProjectWorkflowResolverTest.java
- depends: T3, T5
- invariants: INV-2, INV-5, INV-6, INV-9
- change: `resolve(SharedProject, WorkflowTarget, Cancellation)`: canonicalize the registered root, read only its config (unreadable/dangling/probe failure => Invalid, absent => Missing), decode then select+capture, 15 s deadline and cancel; no session/PR/remote/parent/global lookup (~250 lines incl. test).
- tests: ProjectWorkflowResolverTest: conflicting configs in distinct roots, per-target independence, source edited after Selected leaves the snapshot stable while the next resolve is fresh, deadline/cancel.
- done when: AC-3 (resolver half), AC-5 (deadline/cancel), AC-7 pass.
- memories: project-sdlc-workflows/find-approved-scope

### T7 WorkflowPromptComposer
- files: intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowPromptComposer.java, intellij-plugin/src/main/java/app/drydock/sdlc/ComposedPrompt.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowPromptComposerTest.java
- depends: T2
- invariants: INV-10, INV-13, INV-14, INV-15, INV-16
- change: pure composer over SelectedWorkflow plus immutable target/model/integration inputs: free-form replaces only CLARIFY_FIRST, issue replaces steps/quality/DoD/role strategy, keeps identity/title framing and `TASK_SEPARATOR` bytes, role-neutral conditional tiers only on subagent harnesses, tracks generated seed vs edited text and provenance (~280 lines incl. test).
- tests: WorkflowPromptComposerTest: task bytes after the separator unchanged, no built-in clarify/quality/role text, edits survive fallback/harness switch.
- done when: AC-8, AC-10 and AC-12 composer halves pass.
- memories: project-sdlc-workflows/find-preamble-applicability

### T8 NewTaskPlan and NewTaskPreset applicability
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskPlan.java, intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskPreset.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/NewTaskPlanTest.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/NewTaskPresetTest.java
- depends: T7
- invariants: INV-10, INV-11, INV-12, INV-13, INV-24
- change: explicit workflow-applicability descriptor on NewTaskPreset (free-form/main/empty eligible; segmentStart, conflict-resolution, saved/planned excluded); `NewTaskPlan.initialPrompt` delegates configured composition to the composer; `isSlashOneLiner` and blank handling untouched; unconfigured output byte-identical (~250 lines incl. tests).
- tests: unconfigured prompts byte-identical, native/blank bypass with a resolver spy never called, Pi separator, excluded presets unchanged.
- done when: AC-8, AC-9 (plan half), AC-10, AC-24 pass.
- memories: project-sdlc-workflows/find-preamble-applicability

### T9 Materializer boundary types
- files: intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowMaterializationRequest.java, intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowMaterializer.java, intellij-plugin/src/main/java/app/drydock/sdlc/MaterializedWorkflow.java, intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowResourceLease.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowMaterializerContractTest.java
- depends: T2, T6, T23
- invariants: INV-30, INV-32, INV-49
- change: request (invocation id/generation, SelectedWorkflow, destination LOCAL|LOCAL_EVAL|WORKSPACE_BOX, SessionMode, optional review context built from core ReviewIntent fields); `materialize(request, Cancellation)` consumes the ONE `app.drydock.sdlc.Cancellation` type produced by T6 (no second cancellation type; T6 precedes this task) and the T23 ReviewIntent value success/typed-failure/cancelled; MaterializedWorkflow with destination-typed references (remote never a host Path); lease with single owner, idempotent release, pending-to-retained transfer (~220 lines incl. test).
- tests: contract test with a fake materializer: identical request for both destinations, no host Path/receipt/taskGuidance in WORKSPACE_BOX output, lease transfers once.
- done when: AC-27 typed handoff passes with fakes (not remote transfer proof).
- memories: project-sdlc-workflows/find-approved-scope

### T10 Private staging layout writer
- files: intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowStagingLayout.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowStagingLayoutTest.java
- depends: T5, T9
- invariants: INV-6, INV-7, INV-31
- change: write a WorkflowBundle into a caller-supplied private per-session directory: preserved tree, relative links, executable bits, read-only publication, separate generated instruction file, atomic rename publish; never touches the checkout or global harness state; removes partial output on any failure (~200 lines incl. test).
- tests: layout/hash parity, executable/link metadata, no process/shell calls, partial-output cleanup.
- done when: AC-6 (materializer half) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T11 LocalWorkflowMaterializer
- files: intellij-plugin/src/main/java/app/drydock/sdlc/LocalWorkflowMaterializer.java, intellij-plugin/src/test/java/app/drydock/sdlc/LocalWorkflowMaterializerTest.java
- depends: T10, T9
- invariants: INV-6, INV-31, INV-32
- change: WorkflowMaterializer for LOCAL and LOCAL_EVAL: allocates the namespace under plugin-owned per-session staging, calls WorkflowStagingLayout, returns destination references (host path for LOCAL, eval-namespace reference for LOCAL_EVAL) plus a pending lease; honours cancellation and generation so a late completion only releases (~220 lines incl. test).
- tests: cancel/late-completion cleanup, retained-byte ceiling refusal without partial launch, local vs eval reference shape.
- done when: AC-26 (materializer half) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T12 Resource manifest journal
- files: intellij-plugin/src/main/java/app/drydock/sdlc/ResourceManifestJournal.java, intellij-plugin/src/test/java/app/drydock/sdlc/ResourceManifestJournalTest.java
- depends: T9
- invariants: INV-32, INV-37, INV-51
- change: plugin-owned atomic journal of leases (session, bundle id, local bytes, remote bytes, cleanup-debt records, pending-vs-retained state) with flush and startup recovery that classifies abandoned pending namespaces; NOT an ApplicationState writer and never touches state.json (~230 lines incl. test).
- tests: atomic replace, torn-write recovery, debt records survive restart, abandoned pending detection.
- done when: AC-26 (manifest recovery) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T13 SessionWorkflowResourceOwner
- files: intellij-plugin/src/main/java/app/drydock/sdlc/SessionWorkflowResourceOwner.java, intellij-plugin/src/test/java/app/drydock/sdlc/SessionWorkflowResourceOwnerTest.java
- depends: T12
- invariants: INV-32, INV-33, INV-37, INV-51
- change: single application-owned owner API: accept successful/ambiguous leases (pending owners release all others), account local+remote copies jointly against 16 bundles/256 MiB per session and 1 GiB aggregate, refuse (never evict) at the cap, `requireRetained(session)` for Resume, async release/cleanup on deletion, retry of remote cleanup debt, flush/recover through the T12 journal (~270 lines incl. test).
- tests: fault-injected transfer, cap exhaustion refuses without eviction, debt retry, deletion releases only its namespace, detach/next invocation never releases.
- done when: AC-26 (owner half) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T14 Resource owner production composition
- files: intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java, intellij-plugin/src/platform/java/app/drydock/intellij/session/SessionEnds.java, intellij-plugin/src/test/java/app/drydock/intellij/DrydockPluginServiceTest.java, intellij-plugin/src/test/java/app/drydock/intellij/session/SessionEndsTest.java
- depends: T13
- invariants: INV-32, INV-33, INV-37
- change: DrydockPluginService constructs the ONE SessionWorkflowResourceOwner (under baseDir, journal injected) and exposes `workflowResources()`; startup recovery runs off EDT after the state store loads; shutdown flushes it with individually exception-isolated closes. SessionEnds: session removal/Forget calls owner release (local first, remote debt recorded); terminal detach, console close and Stop-without-Forget do not delete retained resources. The ApplicationStateStore single-writer path is untouched (~200 lines incl. tests).
- tests: DrydockPluginServiceTest: owner constructed once, recovery off EDT, shutdown flush isolated from other failures; SessionEndsTest: Forget releases only its namespace, detach/Stop keep, startup restores.
- done when: AC-26 (restarted/deleted lifetimes in production wiring) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T15 Invocation state machine and source label
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/WorkflowInvocationState.java, intellij-plugin/src/platform/java/app/drydock/intellij/tasks/WorkflowSourceLabel.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/WorkflowInvocationStateTest.java
- depends: T2
- invariants: INV-16, INV-17, INV-19
- change: pure state machine preparing/ready/waiting-fallback/accepted/cancelled/committed with generation tokens and immutable input capture; WorkflowSourceLabel renders "Drydock default" / "Project <leaf>: <path or inline text>" / edited / default-once / not-registered text without credentials or raw remote coordinates (~200 lines incl. test).
- tests: transitions, stale generation rejection, label strings for every source/provenance case.
- done when: AC-11 (provenance indicator) and AC-13 (state half) pass.
- memories: project-sdlc-workflows/find-approved-scope

### T16 Workflow invocation controller
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/WorkflowInvocationController.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/WorkflowInvocationControllerTest.java
- depends: T6, T11, T13, T15
- invariants: INV-4, INV-18, INV-19
- change: platform controller: background executor, progress shown before work, resolver then materializer, exactly two invalid-source choices (default-once, cancel), progress cleared on success/failure/exception/timeout/refusal/cancel/dispose, late results only release their pending lease, lease handoff to the T13 owner on accepted launch/delivery or ambiguous send (~280 lines incl. test).
- tests: fake resolver/materializer with latches: out-of-order completion, exception, cancel, dispose, default-once, thread assertions (no EDT work).
- done when: AC-11, AC-13, AC-14 controller halves pass.
- memories: project-sdlc-workflows/find-approved-scope

### T17 NewTaskDialog source indicator and Start flow
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/NewTaskDialog.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/NewTaskDialogPlatformTest.java
- depends: T8, T15, T16
- invariants: INV-14, INV-16, INV-17
- change: dialog shows the T15 source label, calls the T16 controller on Start, retains edited text and model/tier selections across harness switches and late preparation; blank/native bypass shows its two notices and validates nothing; prose transition resolves normally (~250 lines incl. test).
- tests: NewTaskDialogPlatformTest: bypass-to-prose, late preparation cannot overwrite edits, default-once indicator, model/tier state survives.
- done when: AC-9, AC-11, AC-12, AC-13 (dialog half) pass.
- memories: project-sdlc-workflows/find-preamble-applicability

### T18 TaskLauncher wiring
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskLauncher.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskLauncherWorkflowTest.java
- depends: T17
- invariants: INV-2, INV-14, INV-38
- change: narrow delegation only: resolve registered identity via `plugin.sharedProjects().byRoot(...)` (register-first on accepted Start only), hand the four GitHub/Jira Implement/Triage entry points to the controller; no policy/packaging body in TaskLauncher (1,733 lines/91,598 bytes baseline; no net growth) (~200 lines incl. test).
- tests: four issue entry points, conflicting IDE/session/PR config vs registered checkout, synthetic project ID never used, size check.
- done when: AC-3, AC-10 (launch half), AC-14 pass.
- memories: project-sdlc-workflows/find-preamble-applicability

### T19 ReviewModelDialog source indicator
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/ReviewModelDialog.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/ReviewModelDialogTest.java
- depends: T15, T16
- invariants: INV-14, INV-16, INV-17
- change: the existing review dialog shows the T15 effective project/default source (and default-once/edited provenance) without adding a prompt editor; selected model/tier state preserved; running-session Run text says a new model cannot change an already-running agent (~150 lines incl. test).
- tests: ReviewModelDialogTest: label per source, model/tier selection survives refresh, running-session note.
- done when: AC-12 (ReviewModelDialog half) and AC-13 (review dialog half) pass.
- memories: project-sdlc-workflows/find-approved-scope

### T20 Integration-only review instructions renderer
- files: intellij-plugin/src/main/java/app/drydock/review/ReviewInstructions.java, intellij-plugin/src/test/java/app/drydock/review/ReviewInstructionsTest.java
- depends: none
- invariants: INV-29
- change: extract an integration-only renderer (tour/sections authoring, grounding/teaching, finding anchors, validation repair) sharing clauses with forScope/forSections; no delegation/quality-gate/model-role text; Sphinx and maintenance callers unchanged (~200 lines incl. test).
- tests: existing public forms byte-identical; the new renderer lacks forbidden methodology; TOUR checks vs SECTIONS none; semantic comparison against validators.
- done when: AC-23 passes.
- memories: kb/find-learning-science-authoring-rules, kb/find-check-answerability-gap

### T21 review_scope include=instructions in the router
- files: intellij-plugin/src/main/java/app/drydock/mcp/McpToolRouter.java, intellij-plugin/src/main/java/app/drydock/mcp/McpSessionContext.java, intellij-plugin/src/test/java/app/drydock/mcp/McpToolRouterReadTest.java, intellij-plugin/src/test/java/app/drydock/mcp/McpToolRouterReviewTest.java
- depends: T20
- invariants: INV-27, INV-28, INV-38
- change: default `McpSessionContext.reviewIntegration(scope,caller)`; accept `instructions` in the include grammar; router calls it only after requireLiveSession/requireScope; first page only `{version:1,mode,text}`, 32 KiB cap charged to the hunk budget, `instructionsOverBudget` flag, descriptor text. Narrow delegation only in McpToolRouter (2,084 lines baseline) (~180 lines incl. tests).
- tests: no opt-in unchanged, first-page-only, over-budget returned whole, foreign/unknown/ended/revoked refused through real dispatch, caller cannot set mode.
- done when: AC-21, AC-22 (router half) pass.
- memories: project-sdlc-workflows/find-approved-scope

### T22 Invocation-mode binding and workspace context
- files: intellij-plugin/src/main/java/app/drydock/mcp/ReviewInvocationBindings.java, intellij-plugin/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java, intellij-plugin/src/test/java/app/drydock/mcp/ReviewInvocationBindingsTest.java
- depends: T21
- invariants: INV-28, INV-57
- change: ephemeral binding keyed (ManagedSessionId, scopeId) with generation/target/ReviewTour.Kind, installed at commit, serialized per session, rolled back only for known no-send, removed on revocation/removal/close; WorkspaceMcpSessionContext implements `reviewIntegration` from the binding, else stored `tourOf(scope.id())`, else TOUR (~220 lines, minimal growth of a 913-line class).
- tests: generation lifecycle, rollback, no cross-session leak, rebinding grants no addressability.
- done when: AC-22 passes.
- memories: project-sdlc-workflows/find-approved-scope

### T23 Core ReviewIntent value
- files: intellij-plugin/src/main/java/app/drydock/review/ReviewIntent.java, intellij-plugin/src/test/java/app/drydock/review/ReviewIntentTest.java
- depends: none
- invariants: INV-20, INV-21, INV-53
- change: new public immutable platform-free `app.drydock.review.ReviewIntent` (E1 resolution): RepositoryId, checkout `Path` identity or opaque remote placeholder, optional ManagedSessionId, target LocalChanges|PullRequest(PullRequestTarget), origin, requested ReviewTour.Kind. Imports no IntelliJ/Swing/JavaFX type; the existing platform `ReviewSelection` is not touched (~150 lines incl. test).
- tests: ReviewIntentTest: value semantics, explicit-local default, invalid PR target rejected, no incidental PR inference helper exists.
- done when: AC-15 (core value half) and the AC-16 prerequisite pass.
- memories: project-sdlc-workflows/find-approved-scope

### T24 Carry ReviewIntent through the platform entry points
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/PullRequestReview.java, intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabOpener.java, intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewWorkbenchEditor.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabOpenerTest.java
- depends: T23
- invariants: INV-20, INV-21, INV-53
- change: PR row -> PullRequestReview.openInWorkbench -> ReviewTabOpener/workbench context -> ReviewWorkbenchEditor.Host.startReview construct and pass core ReviewIntent; missing context means explicit local; incidental `pr-N`/scope.pr never creates intent; the existing editor `ReviewSelection`, registry, diff and line keys untouched (~230 lines incl. test).
- tests: ReviewTabOpenerTest with conflicting PR/local text; existing ReviewSelectionTest, ReviewSelectionActionPlatformTest, ReviewGutterCommentsTest stay green; ReviewScopeRegistryTest, SessionReviewScopesTest, ReviewDiffsTest outputs unchanged.
- done when: AC-15 and AC-16 pass.
- memories: project-sdlc-workflows/find-approved-scope

### T25 ReviewRequest workflow delivery
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewRequest.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewRequestTest.java
- depends: T11, T13, T16, T22, T23
- invariants: INV-19, INV-22, INV-26
- change: ReviewRequest takes a ReviewIntent, authorizes the actual session/scope (exact-scope binding seam; foreign/running-owner refuses), materializes through the request boundary, installs the invocation binding immediately before commit, and calls AgentExecutionRegistry.submitPrompt once with a short single-line directive pointing at the staged generated instruction file; DELIVERED/ambiguous/no-console reported, never retried; default requests keep the existing single-line form with mode support (~280 lines incl. test).
- tests: exactly one submit, no second agent, ESC/newline text staged byte-exact, ambiguous retains lease, known no-send rolls back binding and releases lease.
- done when: AC-17 (request half), AC-18, AC-20 pass.
- memories: project-sdlc-workflows/find-approved-scope

### T26 WorktreeReview explicit Run
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/WorktreeReview.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/WorktreeReviewTest.java
- depends: T24, T25
- invariants: INV-18, INV-22, INV-23
- change: move WorktreeReview's `toRealPath` out of its EDT entry into controller preparation; explicit workbench Run on an existing session is a new invocation calling ReviewRequest (policy, mode, authorized scope, one submit); pure focus/reveal/join stays non-invoking; idle-tree race text is "Focused existing session; no new review sent"; the selected source is shown in the existing progress/result surface (~250 lines incl. test).
- tests: exactly one submit with unconfigured policy, no second agent, race label, progress cleared on every path, origin prevents the race swallowing a workbench Run.
- done when: AC-14 (WorktreeReview path), AC-17, AC-18 pass.
- memories: project-sdlc-workflows/find-approved-scope

### T27 TaskActions Quick Review local intent
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskActions.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskActionsReviewTest.java
- depends: T15, T25
- invariants: INV-18, INV-22, INV-23
- change: `TaskActions.reviewChanges` originates a local ReviewIntent unless the human explicitly selected a PR and calls the ReviewRequest boundary; Quick Review/existing-session feedback shows the T15 source label and result without a new editor (~150 lines incl. test).
- tests: local-intent capture, Quick Review progress/source feedback, single submit, race label.
- done when: AC-15 (local origin) and AC-17 (Quick Review half) pass.
- memories: project-sdlc-workflows/find-approved-scope

### T28 Fresh review launch channel
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/PullRequestReview.java, intellij-plugin/src/platform/java/app/drydock/intellij/actions/SessionDialogs.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/PullRequestReviewTest.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewRequestBackendTest.java
- depends: T16, T24, T25
- invariants: INV-12, INV-24, INV-25
- change: fresh configured local review resolves before spawn and refuses without mandatory tools/scope/data/output; preserves the fresh LOCAL DEFAULT-only no-override path (empty prompt, bare session, visible complaint, no "review ran" claim); Sphinx fixed/native prompts and maintenance untouched; remote/existing-session never use the exception (~220 lines incl. tests).
- tests: configured vs no-config vs default-once captures with zero-review-send assertions; edited methodology not relabelled default; Sphinx unchanged.
- done when: AC-19, AC-24 pass.
- memories: project-sdlc-workflows/find-approved-scope

### T29 LocalWorkflowAccess API in launch contexts
- files: intellij-plugin/src/main/java/app/drydock/agent/api/LocalWorkflowAccess.java, intellij-plugin/src/main/java/app/drydock/agent/api/CreateContext.java, intellij-plugin/src/main/java/app/drydock/agent/api/ResumeContext.java, intellij-plugin/src/test/java/app/drydock/agent/api/LocalWorkflowAccessTest.java
- depends: T9
- invariants: INV-14, INV-31, INV-34
- change: optional typed `LocalWorkflowAccess` (read roots, eval-namespace reference) as an additional record component defaulting to absent, kept through delegating constructors so every existing arity compiles; separate from `LaunchResources.taskGuidance`/receipt and never merged into LaunchResources (~150 lines incl. test).
- tests: default absent on all old constructors, access never aliases LaunchResources fields, record copy semantics.
- done when: AC-25 (API half) passes and all existing provider tests still compile.
- memories: project-sdlc-workflows/find-approved-scope

### T30 Claude host provider consumption
- files: intellij-plugin/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java, intellij-plugin/src/test/java/app/drydock/agent/providers/claude/ClaudeAgentProviderTest.java
- depends: T29
- invariants: INV-14, INV-31, INV-34
- change: `buildCreateCommand`/`buildResumeCommand` union the LocalWorkflowAccess read root into the host `--add-dir` rendering through a separate path from `addDirFlags(launchResources())`; the `requestsHostPathResource` refusals for task resources and eval stay unchanged (~120 lines incl. test).
- tests: create and resume command capture with/without access; task-resource and eval refusals unchanged; no `--plugin-dir`/guidance added.
- done when: AC-25 (Claude host half) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T31 Claude eval namespace mount
- files: intellij-plugin/src/main/java/app/drydock/agent/providers/claude/internal/ClaudeEvalContainer.java, intellij-plugin/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java, intellij-plugin/src/test/java/app/drydock/agent/providers/claude/internal/ClaudeEvalContainerTest.java, intellij-plugin/src/test/java/app/drydock/agent/providers/claude/ClaudeAgentProviderTest.java
- depends: T30
- invariants: INV-14, INV-31, INV-34
- change: new narrow `ClaudeEvalContainer.wrap` overload mounting only the per-session workflow namespace read-only (empty at launch/resume), called by the provider's eval arm only when LocalWorkflowAccess is present; no generic host-resource bypass; the task-resource eval refusal remains first (~200 lines incl. tests).
- tests: eval mount argv capture, namespace-only mount, task-resource refusal unchanged; actual eval execution reported separately if performed.
- done when: AC-25 (eval half) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T32 Codex host provider consumption
- files: intellij-plugin/src/main/java/app/drydock/agent/providers/codex/CodexAgentProvider.java, intellij-plugin/src/test/java/app/drydock/agent/providers/codex/CodexAgentProviderTest.java
- depends: T29
- invariants: INV-14, INV-31, INV-34
- change: create and resume add the LocalWorkflowAccess read root to the `addDirFlags` rendering via a separate typed path; existing launchResources behaviour unchanged (~100 lines incl. test).
- tests: create/resume command capture with/without access; task-resource behaviour unchanged.
- done when: AC-25 (Codex half) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T33 Pi compatibility assessment test
- files: intellij-plugin/src/test/java/app/drydock/agent/providers/pi/PiAgentProviderWorkflowAccessTest.java
- depends: T29
- invariants: INV-14, INV-31
- change: assessment: Pi has no add-dir flag and `LaunchResources.extraDirectories` is only rendered as a path in the prompt, so reading a staged absolute path works through its ordinary filesystem behaviour under unchanged permissions (ASM-3). NO production Pi change and NO invented CLI flag; only a compatibility test is added (~80 lines).
- tests: command lines for create/resume with LocalWorkflowAccess are byte-identical to those without it; no new flag; existing PiAgentProvider tests unchanged.
- done when: AC-25 (Pi compatibility) passes.
- memories: project-sdlc-workflows/find-approved-scope

### T34 DrydockSessionService launch-preparation extraction (refactor only)
- files: intellij-plugin/src/platform/java/app/drydock/intellij/session/DrydockSessionService.java, intellij-plugin/src/platform/java/app/drydock/intellij/session/LocalLaunchPreparation.java, intellij-plugin/src/test/java/app/drydock/intellij/session/DrydockSessionServiceTest.java
- depends: T14
- invariants: INV-38, INV-62
- change: behaviour-preserving move of the existing local launch resource/context assembly out of DrydockSessionService (2,449 lines/131,942 bytes baseline) into the new LocalLaunchPreparation, with the existing create/resume/in-session resource tests extended FIRST; asserts no behaviour change and net shrinkage of the service (~220 lines moved, ~60 test).
- tests: existing create/resume/task-resource refusal fixtures green before and after; size check not above baseline.
- done when: AC-45 (local compatibility) and the INV-62 size baseline hold.
- memories: project-sdlc-workflows/find-approved-scope

### T35 Local workflow handoff into launches
- files: intellij-plugin/src/platform/java/app/drydock/intellij/session/LocalLaunchPreparation.java, intellij-plugin/src/platform/java/app/drydock/intellij/session/DrydockSessionService.java, intellij-plugin/src/test/java/app/drydock/intellij/session/DrydockSessionServiceTest.java
- depends: T14, T16, T29, T30, T32, T34, T78
- invariants: INV-32, INV-34, INV-38, INV-62
- change: LocalLaunchPreparation carries the optional typed LocalWorkflowAccess to CreateContext/ResumeContext separately from task receipts/guidance, registers the lease with the T13 owner on accepted launch, and calls `requireRetained` before a Resume; DrydockSessionService gets only one-line wiring and still no net growth (~200 lines incl. test).
- tests: create/resume/in-session resource-access fixtures, lease transfer once, missing retained resource refused visibly, size check.
- done when: AC-25 and AC-26 (session half) pass.
- memories: project-sdlc-workflows/find-approved-scope

### T36 Managed SSH-forward ProcessRunner API
- files: intellij-plugin/src/main/java/app/drydock/process/ProcessRunner.java, intellij-plugin/src/test/java/app/drydock/process/ProcessRunnerManagedForwardTest.java
- depends: none
- invariants: INV-37, INV-40
- change: `ProcessRunner.startSshForward(argv,options)` returns an AutoCloseable handle with exit notification, 64 KiB/stream redacted bounded drains, destroy then force after 2 s and reader join within 2 s, no overall lifetime; `run`/`CommandRunner` semantics and the LSP exception untouched (~230 lines incl. test).
- tests: fake child/clock: connect refusal, early exit, cancel, keepalive loss, drain bounds, reader joins.
- done when: AC-30 (lifecycle half) passes with no 180 s/240 s cycling.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T37 Forward argv builder and AGENTS.md exception
- files: intellij-plugin/src/main/java/app/drydock/process/SshCommandBuilder.java, intellij-plugin/src/test/java/app/drydock/process/SshCommandBuilderTest.java, AGENTS.md
- depends: T36
- invariants: INV-37, INV-40
- change: SshCommandBuilder builds the exact forward argv (BatchMode, ExitOnForwardFailure, ConnectTimeout=5, ServerAlive 3/2, `-R 127.0.0.1:<remote>:127.0.0.1:<ide>`, ControlMaster/ControlPath off, no PTY, argv only); the repository-root `AGENTS.md` gains only the authorized no-overall-lifetime exception for the managed forward beside the existing LSP exception (~120 lines incl. test).
- tests: exact argv assertions and no-shell/no-PTY checks.
- done when: AC-30 (argv half) passes; the AGENTS.md diff shows only the narrow exception.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T38 Bridge framing and caps
- files: intellij-plugin/src/main/resources/drydock/remote/bridge_framing.py, intellij-plugin/src/test/java/app/drydock/workspace/BridgeFramingProtocolTest.java
- depends: none
- invariants: INV-46
- change: Python-stdlib newline JSON-RPC reader/writer: 1 MiB input line cap, 4 MiB output cap, depth 32, duplicate-member/malformed/batch/invalid-Unicode rejection without dispatch, numeric/string ID and notification-silence preservation, stderr-only diagnostics (~200 lines incl. test).
- tests: runs the helper through ProcessRunner with bounded lifetime: partial/multiple/oversize lines, binary/Unicode, malformed JSON/depth/duplicates/IDs, notifications.
- done when: AC-33 (framing half) and AC-44 (stdio half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T39 Bridge manifest reread, HTTP and no-replay
- files: intellij-plugin/src/main/resources/drydock/remote/drydock_mcp_bridge.py, intellij-plugin/src/test/java/app/drydock/workspace/RemoteBridgeProtocolTest.java
- depends: T38
- invariants: INV-45, INV-46
- change: bridge main: manifest reopened before EVERY request with ownership/schema/size/atomic-replacement validation, one POST per message (connect/read 5/35 s), no replay after timeout/disconnect (including reads), superseded-generation responses discarded as outcome-unknown, internal reconnect initialize with launch-nonce headers whose response is consumed internally, no secret in argv/env/logs (~280 lines incl. test).
- tests: RemoteBridgeProtocolTest against loopback fake MCP servers: 204/401/timeout/EOF, atomic manifest replacement, refreshed port/token without restart, dropped response never repeats.
- done when: AC-33 and AC-34 (bridge half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T40 Remote workflow stager helper
- files: intellij-plugin/src/main/resources/drydock/remote/workflow_stage.py, intellij-plugin/src/test/java/app/drydock/workspace/RemoteWorkflowStageHelperTest.java
- depends: none
- invariants: INV-50
- change: Python unpacker with NO extractall: rejects duplicate/absolute/traversal/special/unknown entries, enforces counts/depth/bytes and the 40 MiB wire cap, streams to exclusive no-follow files, verifies hashes and link containment, publishes atomically by descriptor-relative operations in a verified 0700 namespace (~280 lines incl. test).
- tests: runs the real helper on temporary trees with hand-built archives: traversal, duplicates, zip bomb, symlink-parent swap, permissions, limit+one.
- done when: AC-38 (helper half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T41 Workflow archive encoder
- files: intellij-plugin/src/main/java/app/drydock/sdlc/WorkflowArchiveEncoder.java, intellij-plugin/src/test/java/app/drydock/sdlc/WorkflowArchiveRoundTripTest.java
- depends: T9, T40
- invariants: INV-50
- change: encode the captured WorkflowBundle (no re-crawl) into a bounded ZIP (40 MiB) plus entry/hash/link/exec metadata matching the T40 manifest format (~200 lines incl. test).
- tests: round trip through the real encoder and the real T40 helper in temporary trees: hash/layout/links/exec bits, limit+one.
- done when: AC-37 (hash/layout half) and AC-38 (encoder half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T42 Remote bounded file-read helper
- files: intellij-plugin/src/main/resources/drydock/remote/review_data.py, intellij-plugin/src/test/java/app/drydock/workspace/RemoteReviewDataHelperTest.java
- depends: none
- invariants: INV-54, INV-55, INV-56
- change: enumerated operations only: contained descriptor-relative no-follow file reads, bounded base64 responses (8 MiB file, 4 MiB excerpt, 64 KiB window), working-byte hashes, never `.git` as source, no arbitrary-path API (~220 lines incl. test).
- tests: runs the helper on real temporary trees: symlink/traversal/path-option attacks, binary/CRLF bytes, concurrent-edit hash mismatch, exact/plus-one bounds.
- done when: AC-41 (read half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T43 Remote Git capture helper
- files: intellij-plugin/src/main/resources/drydock/remote/review_git.py, intellij-plugin/src/test/java/app/drydock/workspace/RemoteReviewGitHelperTest.java
- depends: T42
- invariants: INV-54, INV-55, INV-56
- change: ref/status/hash capture and untracked inclusion via a private COPY of the real index plus GIT_INDEX_FILE and intent-to-add, cleaned on all exits; 1 MiB status, 10 s command, 64 MiB snapshot bounds; never mutates the real index/refs/checkout (~250 lines incl. test).
- tests: executes on real temporary Git repos: staged/unstaged/untracked, detached HEAD, rename/binary/CRLF, real index and ref hashes unchanged.
- done when: AC-40 (helper-execution half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T44 Launch wrapper
- files: intellij-plugin/src/main/resources/drydock/remote/launch_wrapper.py, intellij-plugin/src/test/java/app/drydock/workspace/LaunchWrapperTest.java
- depends: none
- invariants: INV-36, INV-44, INV-47
- change: wrapper writes a private receipt (nonce, intended nonempty conversation id, PID, boot id, start ticks) then execs the exact Claude command; refuses an empty conversation id; no install or SSH config edit (~150 lines incl. test).
- tests: runs the wrapper in a temp dir: receipt fields, exec lineage, empty-id refusal, no secret in argv/env.
- done when: AC-36 (wrapper half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T45 RemoteEnrollmentProbe
- files: intellij-plugin/src/main/java/app/drydock/workspace/RemoteEnrollmentProbe.java, intellij-plugin/src/main/java/app/drydock/workspace/RemoteEnrollmentEvidence.java, intellij-plugin/src/test/java/app/drydock/workspace/RemoteEnrollmentProbeTest.java
- depends: T44
- invariants: INV-44, INV-47, INV-48
- change: probe validates tmux ancestry, PID start identity, cmdline/config path and bridge ancestry against the receipt through CommandRunner/BoxShell; typed ready/unavailable/unverifiable; legacy proven only from actual command evidence, inaccessible/malformed => unverifiable; Python/Git/tmux/Claude-flag/forwarding preflight reported separately, never auto-installed (~250 lines incl. test).
- tests: fixture /proc data: legacy, enrolled, PID reuse, malformed, inaccessible; zero install calls.
- done when: AC-36 (evidence half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T46 ReviewDataSource boundary
- files: intellij-plugin/src/main/java/app/drydock/review/ReviewDataSource.java, intellij-plugin/src/main/java/app/drydock/review/ReviewDataSnapshot.java, intellij-plugin/src/main/java/app/drydock/review/ReviewDataSources.java, intellij-plugin/src/test/java/app/drydock/review/ReviewDataSourcesTest.java
- depends: none
- invariants: INV-52, INV-57
- change: typed async `resolve/refresh/contents/excerpt/validateCurrent` returning typed unavailable/stale/refused results (never empty text); ReviewDataSnapshot (scopeId, checkout identity, generation, DiffScope, base/head/left commits, fingerprint, UnifiedDiff, source bytes/metadata, capture time); ReviewDataSources dispatches by verified session/scope so no snapshot consumer derives a filesystem path from ReviewScope.diffRoot alone (~230 lines incl. test).
- tests: dispatch by session kind, unknown scope refused, no local fallthrough for a remote scope, absent-side vs failed-read vs binary typing.
- done when: AC-39 (dispatch half) and AC-41 (typed-unavailable half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T47 RemoteCheckoutIdentity
- files: intellij-plugin/src/main/java/app/drydock/review/RemoteCheckoutIdentity.java, intellij-plugin/src/test/java/app/drydock/review/RemoteCheckoutIdentityTest.java
- depends: none
- invariants: INV-52, INV-53
- change: maps opaque `SshRemote.placeholderRoot()` tokens to catalog RepositoryId/ManagedSessionId/WorkspaceBoxBinding/SSH alias/canonical checkout and common-git strings and stat identities; membership from bounded `git worktree list --porcelain` evidence handed in as values, origin-coordinate comparison, first-verified fingerprint pin, refusal of foreign/duplicate rebinding; tokens never name a filesystem location (~220 lines incl. test).
- tests: two repos with colliding path suffixes, reconnect/base/head/port change preserves ids, changed root/git-dir identity unavailable pending revalidation, duplicate live claim refused.
- done when: AC-39 (identity half) passes; ReviewScopeRegistryTest parity unchanged.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T48 LocalReviewDataSource adapter
- files: intellij-plugin/src/main/java/app/drydock/review/LocalReviewDataSource.java, intellij-plugin/src/test/java/app/drydock/review/LocalReviewDataSourceTest.java
- depends: T46
- invariants: INV-57, INV-21
- change: adapter that wraps the existing local DiffService/ReviewContents services behind ReviewDataSource with no behaviour change (~150 lines incl. test).
- tests: output equals the direct local calls for scope id, diff bytes, hunk IDs and line keys.
- done when: AC-16 and AC-45 (local data parity) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T49 Git query-rule extraction (refactor only)
- files: intellij-plugin/src/main/java/app/drydock/git/DiffService.java, intellij-plugin/src/main/java/app/drydock/git/GitStatusService.java, intellij-plugin/src/main/java/app/drydock/git/GitReviewQueryRules.java, intellij-plugin/src/test/java/app/drydock/git/DiffServiceTest.java, intellij-plugin/src/test/java/app/drydock/git/DiffServiceUntrackedTest.java
- depends: none
- invariants: INV-54, INV-21
- change: extract narrow reusable base-scoring/default-base/query construction and a metadata-aware parse overload (staged/untracked flags) from DiffService/GitStatusService into GitReviewQueryRules with the existing tests extended FIRST; no local behaviour change (~250 lines moved/new incl. tests).
- tests: DiffServiceTest/DiffServiceUntrackedTest and any GitStatusService fixtures unchanged-green before and after; tie order, default origin/HEAD then main/master/trunk/develop, stacked base.
- done when: AC-40 (rule-sharing prerequisite) passes and local outputs are byte-identical.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T50 RemoteReviewQueryBuilder
- files: intellij-plugin/src/main/java/app/drydock/review/RemoteReviewQueryBuilder.java, intellij-plugin/src/test/java/app/drydock/review/RemoteReviewQueryBuilderTest.java
- depends: T49
- invariants: INV-54, INV-56
- change: builds the bounded remote Git argv list via `SshCommandBuilder.remoteGitCommand` with `--end-of-options`/`--` for every positional, using GitReviewQueryRules for WORKING_TREE/WORKTREE/BASE (merge-base, pinned HEAD right side, rename old path left); no shell, fetch, checkout or reset (~200 lines incl. test).
- tests: argv assertions for each DiffScope, hostile branch/path arguments starting with `-`, no mutating subcommand.
- done when: AC-40 (query half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T51 RemoteSnapshotCache
- files: intellij-plugin/src/main/java/app/drydock/review/RemoteSnapshotCache.java, intellij-plugin/src/test/java/app/drydock/review/RemoteSnapshotCacheTest.java
- depends: T46
- invariants: INV-55, INV-56, INV-60
- change: the ONE shared MCP+workbench snapshot cache (never per-view/per-MCP copies): two snapshots and 128 MiB per session, 512 MiB aggregate, lease-aware LRU eviction only when unleased, invalidation on connection generation/checkout identity/ref/content/scope revocation/session removal, ephemeral and not a persistent writer (~230 lines incl. test).
- tests: exact/plus-one session and aggregate bytes, leased entry never evicted, invalidation per trigger, stale retained marker.
- done when: AC-41 (cache accounting half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T52 RemoteReviewDataSource
- files: intellij-plugin/src/main/java/app/drydock/review/RemoteReviewDataSource.java, intellij-plugin/src/test/java/app/drydock/review/RemoteReviewDataSourceTest.java
- depends: T42, T43, T46, T47, T50, T51
- invariants: INV-54, INV-55, INV-56, INV-57
- change: ReviewDataSource over CommandRunner/BoxShell using the T50 argv builder and the T42/T43 helpers: pins object IDs/hashes, before/after capture compare with one retry then STALE, 16 MiB diff/2,048 files/10 s command/30 s overall ceilings, base64 source decode with size/hash validation, cache through the T51 cache; never a local Path/Git branch (~280 lines incl. test).
- tests: paired Git fixtures compare DiffService/GitStatusService/ReviewContents against it with the real parser and helper: staged/unstaged, untracked, stacked base, detached HEAD, rename/binary/CRLF, stable hunk IDs/line keys, over-limit => explicit unavailable.
- done when: AC-40 and AC-41 pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T53 McpServer remote transport bounds
- files: intellij-plugin/src/main/java/app/drydock/mcp/McpServer.java, intellij-plugin/src/main/java/app/drydock/mcp/RemoteMcpTransportPolicy.java, intellij-plugin/src/test/java/app/drydock/mcp/McpServerTest.java
- depends: none
- invariants: INV-46, INV-56
- change: constructor-injected policy: body limit+1 read before parse (1 MiB), response 4 MiB, JSON depth 32, 16 application-wide/4 per-session concurrency, 35 s deadline, batch rejection, no redirects; default policy keeps the local endpoint's behaviour (~230 lines incl. test).
- tests: exact/plus-one, blocked handler, malformed unauthenticated traffic, no token in logs; local endpoint tests unchanged.
- done when: AC-44 (server bounds half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T54 Remote authorization permit and initialize observer seam
- files: intellij-plugin/src/main/java/app/drydock/mcp/RemoteAuthorizationPermit.java, intellij-plugin/src/main/java/app/drydock/mcp/McpServer.java, intellij-plugin/src/test/java/app/drydock/mcp/RemoteAuthorizationPermitTest.java
- depends: T53
- invariants: INV-28, INV-35, INV-45, INV-46
- change: `RemoteAuthorizationPermit` (session, credential generation, revocation check, `commitIfCurrent(Supplier)` guard) is captured by McpServer right after the authenticated token resolve and bound for the duration of that request's dispatch in a request-scoped holder that context delegates read; second seam is the authenticated-initialize observer carrying the launch nonce/PID headers. Both are constructor-injected through the T53 policy; the local endpoint leaves them absent. The permit holds a reference to the owner's per-session/generation authorization fence (a small interface supplied by the owner; T59 implements one shared fence per session/generation, the same one revocation marks); `commitIfCurrent` takes that fence's lock and holds it through the supplier's return, requires permit.sessionId == the authenticated session, and never calls code that acquires another permit or revokes. Permit is checked before dispatch; commit-time checking is added by T56 (store guards) and T79 (rename) (~240 lines incl. test).
- tests: stale-generation request refused before dispatch, revoked permit refuses commit, a fixture fence proves revocation marks invalid under the fence alone and cannot invert with a state-store-style inner lock, no state leaks across virtual-thread requests, observer sees only authenticated initialize, local endpoint unchanged.
- done when: AC-35 (credential/revocation half) and AC-44 (permit half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T55 Remote backend composition seam
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewBackend.java, intellij-plugin/src/platform/java/app/drydock/intellij/review/RemoteMcpComposition.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewBackendTest.java
- depends: T22, T54
- invariants: INV-34, INV-43, INV-57
- change: narrow factory exposing the SAME AnnotationStore/TourStore/PendingQuestions/SelectionAsks/ReviewScopeRegistry, plus an independent McpSessionRegistry/McpServer/router serving exactly REVIEW_TOOLS_AND_RENAME with Spawn.FORBIDDEN; no TaskTools, TASK_INSTRUCTIONS or guidance. RemoteMcpComposition declares nested `RemoteRenamer.rename(ManagedSessionId, String validatedTitle, RemoteAuthorizationPermit) -> CompletableFuture<RenameOutcome>` (supplied separately by production wiring; the local BiFunction renamer is never used remotely). Its adapter to WorkspaceMcpSessionContext's existing BiFunction captures the authenticated request's T54 permit on the dispatch thread BEFORE calling RemoteRenamer and passes it as an ordinary argument; it never recovers a permit from a thread-local after the async boundary, never captures one at construction, never substitutes an unguarded callback. Initiation is NOT a commit point: the actual guarded commit is T79's; a lost response after commit reports outcome-unknown, never a replay (~220 lines incl. test).
- tests: remote tools/list and direct task_*/worktree_create/session_start refused; local tool surface unchanged; one store writer; the adapter passes the dispatch-time permit of the authenticated session (distinct permits for two concurrent requests, no thread-local read after an executor hop); local BiFunction renamer never invoked by the remote endpoint.
- done when: AC-35 (surface half) and AC-45 (dual-endpoint regression) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T56 Remote McpSessionContext delegates and commit guard
- files: intellij-plugin/src/main/java/app/drydock/mcp/WorkspaceMcpSessionContext.java, intellij-plugin/src/main/java/app/drydock/mcp/RemoteReviewContextDelegates.java, intellij-plugin/src/test/java/app/drydock/mcp/RemoteReviewContextDelegatesTest.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewBackendEndToEndTest.java
- depends: T22, T46, T54, T55
- invariants: INV-35, INV-52, INV-57, INV-28
- change: inject ReviewDataSource, liveness (proven tmux/protocol evidence, 10 s refresh, 15 s fail-closed) and authorization delegates so reviewDiff/currentReviewBase/baseBranch/excerpt/integration never reach host Path/Git; RemoteReviewContextDelegates wraps every annotation/tour write in the current request's permit `commitIfCurrent` (data reads happen outside the guard); logic stays in the delegate so WorkspaceMcpSessionContext keeps headroom (~280 lines incl. tests).
- tests: ReviewBackendEndToEndTest remote variants: stale generation cannot commit, foreign scope indistinguishable, no local fallthrough, mutation count 1, detached enrolled agent keeps tools.
- done when: AC-34 (store commit half), AC-35, AC-42 (MCP half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T57 RemoteForwardOwner (transport ownership and readiness)
- files: intellij-plugin/src/platform/java/app/drydock/intellij/remote/RemoteForwardOwner.java, intellij-plugin/src/test/java/app/drydock/intellij/remote/RemoteForwardOwnerTest.java
- depends: T36, T37, T54
- invariants: INV-40, INV-41, INV-42
- change: owns one isolated forward handle per managed remote session via `ProcessRunner.startSshForward`, bounded port probe (3 bind retries), authenticated-initialize readiness over the real forward within 15 s, exit/keepalive failure invalidates readiness, and closes handle/readers/timers on every partial-prepare/cancel/owner-close race (~250 lines incl. test).
- tests: fake clock/child: refusal, early exit, cancel races, owner-close race, two sessions one box and two boxes get distinct ports and exact handle counts.
- done when: AC-30 (owner half) and AC-31 (isolation half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T58 Private namespace manifest helper
- files: intellij-plugin/src/main/resources/drydock/remote/namespace_manifest.py, intellij-plugin/src/test/java/app/drydock/workspace/NamespaceManifestHelperTest.java
- depends: none
- invariants: INV-45, INV-50
- change: bundled Python-stdlib helper establishing the verified 0700 `$HOME/.local/share/drydock/<installationId>/sessions/<ManagedSessionId>/` namespace by descriptor-relative no-follow operations and publishing 0600 manifests (exclusive no-follow temp, fsync, atomic rename only while the supplied generation is current); the helper consumes ONLY a scoped pending-file reference (argv carries a path inside the verified private namespace, never a token; no stdin payload, no env): it opens that pending file no-follow, validates owner, regular-file type, mode 0600, size bound, schema and generation, then fsyncs and atomically publishes, removing the pending file on every failure (~230 lines incl. test).
- tests: real helper in temp HOME: wrong owner/permission/symlinked namespace refused, pending file with wrong owner/type/mode/oversize/bad schema/stale generation refused and removed, atomic replace, no secret in argv/env.
- done when: AC-33 (atomic manifest half) and AC-38 (namespace half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T59 RemoteCredentialGenerations
- files: intellij-plugin/src/platform/java/app/drydock/intellij/remote/RemoteCredentialGenerations.java, intellij-plugin/src/test/java/app/drydock/intellij/remote/RemoteCredentialGenerationsTest.java
- depends: T45, T54, T58
- invariants: INV-42, INV-45, INV-46
- change: per-session credential/port/IDE-epoch generation state: fresh unpredictable epoch and token per publish, revoke old tokens on transition, backoff 1/2/4/8/16/30 s with generation fencing, publish only after current actual enrollment evidence, late prior-epoch completions cannot republish, publication follows the accepted private-file route: write the manifest to a private local 0600 file (exclusive create, deleted in finally), bounded `BoxShell.copy`/SCP into a verified remote private pending file under the session namespace, then run the T58 helper via CommandRunner with only the scoped pending-file reference; local and remote pending credentials are cleaned on every failure/cancel (remote cleanup failure recorded as debt); the manifest is reread per request by the bridge, generation fencing preserved. No stdin payload, no CommandRunner/ProcessRunner API change, no token in argv/env/logs/UI. Also implements the shared per-session/generation authorization fence consumed by T54 permits: revoke/rotate marks the old generation invalid under the fence alone, releases it, then does token cleanup/file/network work (~290 lines incl. test).
- tests: fake clock: backoff schedule, late completion discard, revoke-on-loss, refreshed manifest keeps the same launch nonce, log capture contains no token; fake SCP/CommandRunner/real helper: argv/env carry only the pending-file reference, local 0600 file deleted and remote pending removed on copy/helper failure and cancel; a permit obtained before revoke cannot commit after it and revocation takes no lock held by a committer.
- done when: AC-31 (token counts), AC-33 (credential refresh) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T60 RemoteReviewService
- files: intellij-plugin/src/platform/java/app/drydock/intellij/remote/RemoteReviewService.java, intellij-plugin/src/test/java/app/drydock/intellij/remote/RemoteReviewServiceTest.java
- depends: T51, T52, T55, T56, T57, T59
- invariants: INV-40, INV-41, INV-42
- change: application-scoped facade: start/close/flush, `ensure(session)`/`release(session, outcome)` composing T57 forwards, T59 generations and the T55/T56 remote backend; its constructor takes the real T52 RemoteReviewDataSource over the T51 RemoteSnapshotCache plus the `RemoteMcpComposition.RemoteRenamer`, so the remote endpoint serves actual data and `ensure` is the single ensure-forward-plus-credentials-plus-protocol boundary; outcomes distinguish detach, confirmed death, Stop and Forget; local task endpoint never receives remote credentials (~200 lines incl. test).
- tests: detach keeps transport, death/Stop/Forget release, final eligible session closes last transport, partial-prepare/cancel/close races leave zero handles, `ensure` yields a live ready forward with a published current-generation credential and the shared snapshot/data source (no interface-only fake).
- done when: AC-31 (ownership) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T61 Remote lifecycle wiring
- files: intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java, intellij-plugin/src/platform/java/app/drydock/intellij/session/SessionEnds.java, intellij-plugin/src/test/java/app/drydock/intellij/DrydockPluginServiceTest.java, intellij-plugin/src/test/java/app/drydock/intellij/session/SessionEndsTest.java
- depends: T14, T60, T79, T51, T52, T57, T59
- invariants: INV-33, INV-41, INV-37
- change: DrydockPluginService constructs the production RemoteSnapshotCache (T51), RemoteReviewDataSource (T52, the same cache instance), RemoteForwardOwner (T57), RemoteCredentialGenerations (T59) and RemoteReviewService (T60), injecting `plugin::renameRemoteFromAgent` (T79) as the RemoteRenamer, exposes it as `remoteReview()`, and starts/closes/flushes it next to the T14 owner with individually exception-isolated shutdown (tmux never killed by shutdown); SessionEnds routes detach/death/Stop/Forget to distinct RemoteReviewService outcomes (console exit is not death) (~220 lines incl. tests).
- tests: application disposal and SessionEndsTest observe exact handle/token counts; detach keeps, Forget revokes locally even when box deletion fails; one ApplicationState writer unchanged; production composition test: the service's data source and cache are the real instances (shared cache identity), the renamer is the plugin callback.
- done when: AC-31 (console-close/final-close half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T62 RemoteWorkflowPublication
- files: intellij-plugin/src/platform/java/app/drydock/intellij/remote/RemoteWorkflowPublication.java, intellij-plugin/src/test/java/app/drydock/intellij/remote/RemoteWorkflowPublicationTest.java
- depends: T9, T40, T41
- invariants: INV-50, INV-30
- change: private pending directory, `BoxShell.copy` of the T41 archive and run of the T40 stager via CommandRunner (60 s copy, 90 s total, 10 s probes); no tokens in argv; late completion after cancel cannot publish; returns destination-typed remote references only (~230 lines incl. test).
- tests: fake SCP/CommandRunner with the real encoder and stager: hash/layout parity, partial copy, cancel race, limit+one, no host Path in result.
- done when: AC-37 and AC-38 (transfer half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T63 Remote WorkflowMaterializer
- files: intellij-plugin/src/platform/java/app/drydock/intellij/remote/RemoteWorkflowMaterializer.java, intellij-plugin/src/test/java/app/drydock/intellij/remote/RemoteWorkflowMaterializerTest.java
- depends: T9, T13, T62
- invariants: INV-30, INV-49, INV-51
- change: WORKSPACE_BOX implementation of the same WorkflowMaterializationRequest boundary over T62; pending lease, transfer to the T13 owner only on accepted/ambiguous delivery, remote cleanup debt into the owner, local+remote bytes charged jointly, no receipt/taskGuidance/host Path in output (~200 lines incl. test).
- tests: unreachable-cleanup debt then reconnect delete, caps include remote copies, cancellation releases, same request as the local materializer.
- done when: AC-27 (remote side), AC-38 (owner half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T64 RemoteLaunchSupport API in launch contexts
- files: intellij-plugin/src/main/java/app/drydock/agent/api/RemoteLaunchSupport.java, intellij-plugin/src/main/java/app/drydock/agent/api/CreateContext.java, intellij-plugin/src/main/java/app/drydock/agent/api/ResumeContext.java, intellij-plugin/src/test/java/app/drydock/agent/api/RemoteLaunchSupportTest.java
- depends: T29
- invariants: INV-34, INV-49
- change: typed `RemoteLaunchSupport` with independently optional review-connection support (MCP-config path, protocol/launch identity) and workflow access (remote reference text, never a host Path) plus preparation leases, added as another defaulted record component through delegating constructors; separate from LaunchResources/McpAccess (~150 lines incl. test).
- tests: each component optional independently, absent by default, no host Path accepted.
- done when: AC-27 and AC-32 (API half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T65 Claude persistent remote arms
- files: intellij-plugin/src/main/java/app/drydock/agent/providers/claude/ClaudeAgentProvider.java, intellij-plugin/src/test/java/app/drydock/agent/providers/claude/ClaudeAgentProviderMcpFlagTest.java, intellij-plugin/src/test/java/app/drydock/agent/providers/claude/ClaudeAgentProviderTaskGuidanceTest.java
- depends: T31, T39, T44, T64
- invariants: INV-34, INV-43, INV-49, INV-64
- change: `persistentCreateCommand/persistentResumeCommand` consume RemoteLaunchSupport only when present: launch wrapper plus bridge MCP JSON path, narrow `--add-dir` for the private remote namespace; host McpAccess/LaunchResources/receipt refusals still run first; remote comments updated; no `--plugin-dir`/task guidance remotely; same stored agentSessionId (~220 lines incl. tests).
- tests: create/resume capture with/without each component; host-resource refusals unchanged; empty conversation id refused.
- done when: AC-32 (provider half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T66 RemoteLaunchPreparation
- files: intellij-plugin/src/platform/java/app/drydock/intellij/session/RemoteLaunchPreparation.java, intellij-plugin/src/test/java/app/drydock/intellij/session/RemoteLaunchPreparationTest.java
- depends: T45, T59, T60, T63, T64
- invariants: INV-10, INV-36, INV-44, INV-64
- change: focused helper that obtains the optional review connection by calling the T60 `RemoteReviewService.ensure(session)` boundary (live forward + current credentials + protocol state; never declares optional MCP prepared without an ensured forward) and returns separately typed ready/unavailable/unverifiable results for core launch, workflow resources, optional review connection/enrollment, data and attached delivery; optional-MCP failure is nonblocking for a valid task/issue launch or same-ID task Resume; unusable selected/retained resources, missing resume ID and core failures stay hard; capability-qualified title guidance when session_rename is unavailable; proven-legacy explanation, never hot enrollment or restart (~260 lines incl. test).
- tests: one case per matrix row (fresh/Resume, configured/default, attached/detached/legacy, post-spawn enrollment failure) asserting consumed fields, launch/send counts, zero auto-install calls.
- done when: AC-32 (preflight half), AC-36, AC-49 (helper half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T67 DrydockSessionService remote wiring
- files: intellij-plugin/src/platform/java/app/drydock/intellij/session/DrydockSessionService.java, intellij-plugin/src/test/java/app/drydock/intellij/session/DrydockSessionServiceTest.java
- depends: T35, T61, T65, T66
- invariants: INV-62, INV-64, INV-36
- change: `startRemoteLaunch/resumeRemoteClaimed` call RemoteLaunchPreparation (constructed with the T61 production RemoteReviewService) and hand RemoteLaunchSupport to the provider; wiring only, no net growth of the service (baseline from T34/T35 not exceeded) (~120 lines incl. test).
- tests: create/resume through the real service seam capture the typed support, stage-by-stage failures visible, post-spawn enrollment failure leaves the process alive, size check.
- done when: AC-32 (service half), AC-36, AC-49 pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T68 RemoteReviewCapability
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/RemoteReviewCapability.java, intellij-plugin/src/test/java/app/drydock/intellij/review/RemoteReviewCapabilityTest.java
- depends: T45, T60
- invariants: INV-35, INV-48, INV-58
- change: capability query returning typed protocol-confirmed ready/unavailable/unverifiable for transport, enrollment, scope/data/output separate from attached-delivery state; detached enrolled agents keep tools while Run needs an attached console; no auto-reattach or queue (~170 lines incl. test).
- tests: attached, detached, legacy, unverifiable, UNKNOWN-liveness cases; zero sender calls.
- done when: AC-18 (capability half) and AC-43 (readiness half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T69 ReviewRequest remote gating
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewRequest.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewRequestRemoteTest.java
- depends: T22, T25, T63, T68
- invariants: INV-25, INV-48, INV-58, INV-61
- change: remote and existing-session Review (configured or default) hard-refuse without protocol-confirmed enrollment, scope/data/output and attached console; known-unsent resources released; default-once never repairs transport; no queue/replay/send-keys/auto-reattach (~170 lines incl. test).
- tests: detached/legacy/unverifiable refusals with zero sender calls, attached success exactly one, remote materializer lease release on refusal.
- done when: AC-18 (remote half), AC-43 (Run half), AC-49 pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T70 RemoteWorkbenchSnapshots
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/RemoteWorkbenchSnapshots.java, intellij-plugin/src/test/java/app/drydock/intellij/review/RemoteWorkbenchSnapshotsTest.java
- depends: T46, T51
- invariants: INV-59, INV-60, INV-55
- change: converts T46/T51 snapshot values into read-only snapshot-backed contents for the existing ReviewDiffRequests/WorkbenchState (never writable local stand-ins or registered local files); retained failed snapshot marked explicitly stale (~200 lines incl. test).
- tests: same-named registered local file never opened/read; stale marker; absent side vs failed read.
- done when: AC-42 (read-only source half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T71 RemoteReviewWorkbenchController
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/RemoteReviewWorkbenchController.java, intellij-plugin/src/test/java/app/drydock/intellij/review/RemoteReviewWorkbenchControllerTest.java
- depends: T23, T52, T70
- invariants: INV-57, INV-59, INV-60
- change: loads/selects/refreshes through ReviewDataSource, labels remote target/freshness, coalesced 5 s poll only while visible, generation-fences every completion, disables approval/requests when stale, stops pollers/listeners on disposal (~260 lines incl. test).
- tests: out-of-order refresh/reconnect/cancel/dispose, pollers stop, no repaint of another session.
- done when: AC-42 (workbench half), AC-43 (refresh half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T72 Refresher and verdict dispatch
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabRefresher.java, intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewVerdictCommands.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabRefresherTest.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewVerdictCommandsTest.java
- depends: T46, T48, T71
- invariants: INV-55, INV-57, INV-60
- change: route refresh, selection contents/base/head and verdict writes through ReviewDataSource dispatch with local adapters preserving behaviour; verdict commands call fresh `validateCurrent` before existing store writes (~220 lines incl. tests).
- tests: local behaviour unchanged; remote stale generation refuses approval; refreshed content mismatch refuses digest-dependent write.
- done when: AC-41 (fresh-validation approval refusal) and AC-42 pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T73 ReviewTabProvider minimal delegation
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabProvider.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabProviderTest.java
- depends: T72
- invariants: INV-60, INV-62
- change: minimal delegation of remote loading/selection/lifecycle to the T71 controller in ReviewTabProvider (3,006 lines/144,948 bytes baseline): no net growth, no embedded packaging/data/lifecycle policy (~100 lines incl. test).
- tests: local behaviour unchanged, remote scope delegates, listeners disposed, size check.
- done when: AC-42 (provider half) passes and the INV-62 baseline holds.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T74 Workbench entry wiring
- files: intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewTabOpener.java, intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewWorkbenchEditor.java, intellij-plugin/src/test/java/app/drydock/intellij/review/ReviewTabOpenerRemoteTest.java
- depends: T24, T26, T71, T73
- invariants: INV-58, INV-59, INV-62
- change: remote typed workbench context branches BEFORE WorktreeService/ReviewTabScopes/toRealPath/document-save paths; reveal-only (works detached, never invokes); local-only affordances (open-in-editor, gutter, Sphinx, host GitHub submit) disabled for remote scope; a fake local window is never opened (~170 lines incl. test).
- tests: reveal never invokes or enrolls; detached reveal works; local path calls unchanged.
- done when: AC-43 (reveal half) passes.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T75 Remote session row entry
- files: intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskActions.java, intellij-plugin/src/platform/java/app/drydock/intellij/tasks/TaskTreePanel.java, intellij-plugin/src/test/java/app/drydock/intellij/tasks/TaskTreeRemoteRevealTest.java
- depends: T27, T74
- invariants: INV-38, INV-58, INV-62
- change: remote session row/Changes link gets "Open in Review Workbench" delegating to the T74/T71 path; TaskTreePanel (2,727 lines/133,190 bytes baseline) gets no net growth (~100 lines incl. test).
- tests: row entry exists for remote sessions, reveal-only, detached reveal, local rows unchanged, size check.
- done when: AC-43 (UI half) and AC-45 (UI half) pass.
- memories: project-sdlc-workflows/find-remote-tool-surface

### T76 Acceptance evidence and final gates
- files: doc/ProjectSdlcPlanning-5UJyXN2P/AcceptanceEvidence-5UJyXN2P.md
- depends: T18, T19, T28, T35, T61, T67, T69, T75
- invariants: INV-1, INV-39, INV-63
- change: record planning-vs-executed checks separately; run and record the Verification gates; record the real-workspace gates (AC-46, AC-47) with versions/scenarios/results or mark the feature unverified as blocking evidence debt; check helper/owner sizes and repository rules (no code change).
- tests: none new; executes the verification commands below.
- done when: AC-28 (`compileCoreJava compileJava`, size inspection), AC-29, AC-45, AC-46, AC-47 and AC-48 evidence recorded.
- memories: project-sdlc-workflows/find-approved-scope

### T77 AgentRenameMutation shared policy
- files: intellij-plugin/src/main/java/app/drydock/app/AgentRenameMutation.java, intellij-plugin/src/test/java/app/drydock/app/AgentRenameMutationTest.java
- depends: none
- invariants: INV-65
- change: stateless `AgentRenameMutation.apply(ApplicationStateStore, ManagedSessionId, String validatedTitle) -> McpSessionContext.RenameOutcome` extracted behaviour-identically from the applyAgentRename update body: inside the same `store.update` transform find the row or fail without recreation, refuse namePinned, UNCHANGED on exact stored-title equality, same-repository collision via `PromptSafety.foldForComparison(storedName).equalsIgnoreCase(validatedTitle)`, else replace only that session's display name (RENAMED); no I/O, events, persistence of its own or authorization locks (~170 lines incl. test).
- tests: real ApplicationStateStore: missing row not recreated, pinned refused, unchanged, same-repository collision vs other-repository allowed, renamed; pin/collision cases mirror the existing local rename cases; single transform per call.
- done when: AC-50 (shared-policy half) passes.
- memories: none

### T78 Local applyAgentRename delegates to the shared policy
- files: intellij-plugin/src/platform/java/app/drydock/intellij/session/DrydockSessionService.java, intellij-plugin/src/test/java/app/drydock/intellij/session/DrydockSessionServiceTest.java
- depends: T34, T77
- invariants: INV-65, INV-62
- change: `applyAgentRename` keeps its `initialization.thenApplyAsync` scheduling, future/executor/error behaviour and local `renameFromAgent` routing, but its update body calls `AgentRenameMutation.apply`; `DrydockSessionsListener.publishChanged` still fires only for RENAMED; net shrinkage of the service (~80 lines incl. test).
- tests: existing local rename regression cases (pin, unchanged, collision, missing row, publication only on RENAMED) unchanged and green; size not above the T34 baseline.
- done when: AC-50 (local regression half) and the INV-62 baseline hold.
- memories: none

### T79 Application-owned detached remote rename callback
- files: intellij-plugin/src/platform/java/app/drydock/intellij/DrydockPluginService.java, intellij-plugin/src/test/java/app/drydock/intellij/DrydockPluginServiceTest.java
- depends: T14, T54, T77
- invariants: INV-65, INV-28, INV-35
- change: `renameRemoteFromAgent(ManagedSessionId id, String validatedTitle, RemoteAuthorizationPermit permit) -> CompletableFuture<RenameOutcome>` using the plugin's existing initialization, executor and authoritative `stateStore()`, with no AgentExecutionRegistry execution, project session service or console. It requires permit.sessionId == id, takes the permit as an explicit argument into the queued closure, and in the EXECUTING `thenApplyAsync` callback rechecks it and calls `permit.commitIfCurrent(() -> AgentRenameMutation.apply(stateStore(), id, validatedTitle))`, enclosing the whole synchronous `stateStore.update` including the in-memory swap (permit fence -> state lock order). Remote I/O, persistence flush, listener publication and cleanup stay outside both locks; `publishChanged` runs only for RENAMED after the guard; absent/foreign/revoked/stale permit fails closed; the existing local `renameFromAgent` is untouched (~230 lines incl. test).
- tests: DrydockPluginServiceTest with a real ApplicationStateStore: (1) detached rename with NO AgentExecutionRegistry execution succeeds; (2) latch after scheduling but before the guarded update, revoke the captured permit, release => state unchanged and zero publication; (3) completed commit, response dropped, revoke/reconnect => exactly one mutation, retained new name, no replay; (4) wrong-session/foreign/absent/stale permit and missing row refused without recreation; (5) concurrent human pin/collision race; (6) bounded latch/lock-order fixture completes with no permit/state inversion and the listener runs outside both locks; assertions observe state and mutation counts, not callback invocation counts.
- done when: AC-50 passes; AC-34 (rename commit half) passes.
- memories: none

## Waves
- W1: T1, T2, T20, T23, T36, T38, T40, T42, T44, T46, T47, T49, T53, T58, T77
- W2: T3, T4, T7, T15, T21, T24, T37, T39, T43, T45, T48, T50, T51, T54
- W3: T5, T8, T22, T52, T57, T59, T70
- W4: T6, T55, T71
- W5: T9, T56, T72
- W6: T10, T12, T29, T41, T60, T73
- W7: T11, T13, T30, T32, T33, T62, T64, T68
- W8: T14, T16, T31, T63
- W9: T17, T19, T25, T34, T65, T66, T79
- W10: T18, T26, T27, T28, T61, T69, T78
- W11: T35, T74
- W12: T67, T75
- W13: T76

## Verification
All commands below are future gates for the coordinator; none was run in planning except the three metaplan commands. Gradle commands run from the `intellij-plugin/` directory with its `./gradlew`; metaplan commands run from the repository (worktree) root where `doc/` lives.
- `cd intellij-plugin && ./gradlew test --tests 'app.drydock.sdlc.*' --tests 'app.drydock.state.json.*'` (T1-T13, T41)
- `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.tasks.*' --tests 'app.drydock.intellij.review.ReviewRequest*'` (T8, T15-T19, T24-T28)
- `cd intellij-plugin && ./gradlew test --tests 'app.drydock.mcp.*' --tests 'app.drydock.review.*' --tests 'app.drydock.git.*' --tests 'app.drydock.app.AgentRenameMutationTest'` (T20-T23, T46-T56, T77)
- `cd intellij-plugin && ./gradlew test --tests 'app.drydock.process.*' --tests 'app.drydock.workspace.*' --tests 'app.drydock.agent.*'` (T29-T33, T36-T45, T58, T64, T65)
- `cd intellij-plugin && ./gradlew test --tests 'app.drydock.intellij.*'` (T14, T34, T35, T57, T59-T63, T66-T75, T78, T79)
- Platform gate: `cd intellij-plugin && ./gradlew test` (hosts JUnit 5 and Vintage BasePlatformTestCase under JBR 25; do not invent a platformTest task) and `cd intellij-plugin && ./gradlew compileCoreJava compileJava`, then source/diff size inspection against the INV-38/INV-62 baselines (TaskTreePanel 2,727/133,190, TaskLauncher 1,733/91,598, McpToolRouter 2,084/114,589, ReviewTabProvider 3,006/144,948, DrydockSessionService 2,449/131,942, WorkspaceMcpSessionContext 913/44,225; each new helper below ~1,000 lines/50 KB).
- Contract gate (from the repository root): `python3 /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/metaplan-pi/skills/metaplan/metaplan.py check-contract doc/ProjectSdlcPlanning-5UJyXN2P/Contract-5UJyXN2P.md`
- Plan gate (from the repository root): `python3 /Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/metaplan-pi/skills/metaplan/metaplan.py check-plan --contract doc/ProjectSdlcPlanning-5UJyXN2P/Contract-5UJyXN2P.md doc/ProjectSdlcPlanning-5UJyXN2P/Plan-5UJyXN2P.md`
- Escalation accounting (from the repository root): the same metaplan.py with `escalations doc/ProjectSdlcPlanning-5UJyXN2P/Plan-5UJyXN2P.md`; expected count 0.
- Remote gates (manual prerequisites; never provisioned by this plan): an already-available authorized workspace box with Linux /proc, Python 3, Git, tmux, a Claude executable supporting the preflighted flags, SSH loopback forwarding and the user's noninteractive SSH auth (ASM-4, ASM-7, ASM-8, ASM-11). T76 records in AcceptanceEvidence: source isolation, bundled script/resource reference, authenticated `review_scope(include=instructions)`, tour and sections output visible in the workbench, detach continuity, attached explicit Run, cleanup (AC-46); two isolated sessions, foreign credential denial, forwarding/Python prerequisite failure, legacy manual Stop/Resume with the same conversation ID (AC-46); same tmux Claude PID/start identity, conversation ID and bridge across detach, forward loss and IDE restart with before/after mutation counts and unreachable cleanup debt then reconnection/deletion (AC-47). If unavailable the feature is recorded as UNVERIFIED blocking evidence debt, never narrowed or relabelled local-only.
- Portable containment limitation (AC-4, INV-5): tests prove normal escapes and OBSERVED changes are rejected; no gate may claim adversarial concurrent replacement can never cause outside-root access. Post-approval real intended-IDE/JBR filesystem tests include a provider without SecureDirectoryStream.
- Fresh-local-default exception (AC-19, AC-49): T28 tests distinguish a fresh LOCAL DEFAULT-only no-override launch (empty review prompt, bare session, visible complaint, no "review ran" claim) from every configured, existing-session and remote Review, which refuse without delivery.
- Optional-MCP matrix (AC-49): T66/T67 tests cover every row; optional review transport/enrollment/data failure never blocks a valid original task/issue launch or same-ID task Resume, while unusable selected/retained workflow resources, a missing resume conversation ID and core launch failures stay hard.

## Constraint trace
- INV-1: honored — records planning-only status separately from executed checks and blocks completion on local plus real remote evidence; owning tasks: T76
- INV-2: honored — resolve config only from the registered canonical checkout, never synthetic IDs, session/PR/remote content or user-global settings; owning tasks: T2, T6, T18
- INV-3: honored — exact strict schema; Missing vs Invalid typed in T2; owning tasks: T1, T3
- INV-4: honored — invalid selected config offers only default-once or cancel; no silent default; owning tasks: T3, T16
- INV-5: honored — portable lexical/canonical containment with before/after change refusal; no native/SecureDirectoryStream/local-Python dependency and no hostile-race claim; owning tasks: T4, T5, T6
- INV-6: honored — ceilings enforced during enumeration, reads and staging; owning tasks: T1, T4, T5, T6, T10, T11
- INV-7: honored — skill directory, includes, contained links and exec metadata preserved without execution; owning tasks: T4, T5, T10
- INV-8: honored — no discovery/install/expansion/.git/whole-repo copy; owning tasks: T2, T3, T4
- INV-9: honored — per-invocation snapshot; fresh resolve per invocation; owning tasks: T2, T6
- INV-10: honored — task bytes after TASK_SEPARATOR preserved; capability-qualified remote title guidance; owning tasks: T7, T8, T66
- INV-11: honored — native/blank bypass never calls resolver or stages; owning tasks: T8
- INV-12: honored — newTask.prePrompt applies to eligible free-form only; owning tasks: T8, T28
- INV-13: honored — configured method replaces issue methodology; owning tasks: T7, T8
- INV-14: honored — naming/mode/permission/model/eval gates unchanged; no authority from project text; owning tasks: T7, T17, T18, T19, T29, T30, T31, T32, T33
- INV-15: honored — role-neutral conditional tiers; owning tasks: T7
- INV-16: honored — edits survive; provenance indicators; owning tasks: T7, T15, T17, T19
- INV-17: honored — indicators in NewTaskDialog and ReviewModelDialog, two-choice fallback; owning tasks: T15, T17, T19
- INV-18: honored — background work, progress cleared on every path; owning tasks: T16, T26, T27
- INV-19: honored — generation fencing; owning tasks: T15, T16, T25
- INV-20: honored — explicit core ReviewIntent separate from incidental metadata; editor ReviewSelection untouched; owning tasks: T23, T24
- INV-21: honored — no change to diff/registry/line keys; owning tasks: T23, T24, T48, T49
- INV-22: honored — explicit Run submits once; no second agent; owning tasks: T25, T26, T27
- INV-23: honored — reveal/focus non-invoking, truthful race label; owning tasks: T26, T27
- INV-24: honored — Sphinx/replies/maintenance/saved/planned unchanged; owning tasks: T8, T28
- INV-25: honored — before-spawn channel; local default-only exception only for fresh local; remote/existing-session hard refuse; owning tasks: T28, T69
- INV-26: honored — submitPrompt guards kept, staged references, no retry; owning tasks: T25
- INV-27: honored — opt-in first-page instructions; owning tasks: T21
- INV-28: honored — authorization after live-session/scope checks, generation-fenced binding and permit; owning tasks: T21, T22, T54, T56
- INV-29: honored — integration-only text; owning tasks: T20
- INV-30: honored — immutable bundle separate from destination references; no host Path/receipt/guidance; owning tasks: T9, T62, T63
- INV-31: honored — private staging; typed eval mount only; owning tasks: T10, T11, T29, T30, T31, T32, T33
- INV-32: honored — single transfer, cleanup, retention, flush/recovery; owning tasks: T9, T11, T12, T13, T14, T35
- INV-33: honored — detach/dialog close/next invocation never delete; no replay; owning tasks: T13, T14, T61
- INV-34: honored — local task resources unchanged; remote review+rename only, Spawn.FORBIDDEN; owning tasks: T29, T30, T31, T32, T35, T55, T64, T65
- INV-35: honored — typed real-evidence capability; no local fallback; owning tasks: T54, T56, T68
- INV-36: honored — legacy manual Stop/Resume, same conversation ID, no auto-restart/replay; owning tasks: T44, T66, T67
- INV-37: honored — single ApplicationState writer; ProcessRunner exception only for the managed forward; owning tasks: T12, T13, T14, T36, T37, T61
- INV-38: honored — extraction/minimal wiring in oversized owners; owning tasks: T18, T21, T34, T35, T75
- INV-39: honored — production-seam verification, planned vs executed separated; owning tasks: T1, T76
- INV-40: honored — managed forward inside ProcessRunner lifecycle API; owning tasks: T36, T37, T57, T60
- INV-41: honored — application-scoped isolated transports; owning tasks: T57, T60, T61
- INV-42: honored — readiness proof and race closure; owning tasks: T57, T59, T60
- INV-43: honored — independent registry, Spawn.FORBIDDEN; owning tasks: T55, T65
- INV-44: honored — separate preflights, no auto-install; owning tasks: T44, T45, T66
- INV-45: honored — private atomic manifests, no secrets in argv/env; owning tasks: T39, T54, T58, T59
- INV-46: honored — framing, caps, no replay; owning tasks: T38, T39, T53, T54, T59
- INV-47: honored — evidence-based enrollment only; owning tasks: T44, T45
- INV-48: honored — manual Stop then same-ID Resume; owning tasks: T45, T68, T69
- INV-49: honored — typed WORKSPACE_BOX payload; owning tasks: T9, T63, T64, T65
- INV-50: honored — bounded hashed archive and descriptor-relative publication; owning tasks: T40, T41, T58, T62
- INV-51: honored — retained remote resources, joint accounting, debt; owning tasks: T12, T13, T63
- INV-52: honored — verified identity binding; owning tasks: T46, T47, T56
- INV-53: honored — stable identity and explicit target selection; owning tasks: T23, T24, T47
- INV-54: honored — remote Git data without index/ref mutation; owning tasks: T42, T43, T49, T50, T52
- INV-55: honored — pinned/stale/absent-side semantics; owning tasks: T42, T43, T51, T52, T70, T72
- INV-56: honored — remote numeric bounds; owning tasks: T42, T43, T50, T51, T52, T53
- INV-57: honored — shared stores and ReviewDataSource dispatch; owning tasks: T22, T46, T48, T52, T55, T56, T71, T72
- INV-58: honored — process readiness separate from attached delivery; owning tasks: T68, T69, T74, T75
- INV-59: honored — real workbench output with read-only remote source; owning tasks: T70, T71, T74
- INV-60: honored — generation invalidation and pollers stop; owning tasks: T51, T70, T71, T72, T73
- INV-61: honored — default review still needs mandatory transport; owning tasks: T69
- INV-62: honored — extraction, no net growth; owning tasks: T34, T35, T67, T73, T74, T75
- INV-63: honored — unit, platform and real-workspace gates; owning tasks: T76
- INV-64: honored — failure matrix; optional MCP never blocks task launch; owning tasks: T65, T66, T67
- INV-65: honored — detached remote rename via application-owned renameRemoteFromAgent over the authoritative store with shared AgentRenameMutation; the captured permit is passed explicitly and the whole stateStore.update is guarded in the executing callback (permit fence -> state lock); owning tasks: T77, T78, T79

## Acceptance trace (AC -> tasks whose done-when discharges it)
- AC-1: T3
- AC-2: T1, T2, T3
- AC-3: T6, T18
- AC-4: T4, T5
- AC-5: T4, T5, T6
- AC-6: T5, T10
- AC-7: T2, T6
- AC-8: T7, T8
- AC-9: T8, T17
- AC-10: T7, T8, T18
- AC-11: T15, T16, T17
- AC-12: T7, T17, T19
- AC-13: T15, T16, T17, T19
- AC-14: T16, T18, T26
- AC-15: T23, T24, T27
- AC-16: T23, T24, T48
- AC-17: T25, T26, T27
- AC-18: T25, T26, T68, T69
- AC-19: T28
- AC-20: T25
- AC-21: T21
- AC-22: T21, T22
- AC-23: T20
- AC-24: T8, T28
- AC-25: T29, T30, T31, T32, T33, T35
- AC-26: T11, T12, T13, T14, T35
- AC-27: T9, T63, T64
- AC-28: T76
- AC-29: T76
- AC-30: T36, T37, T57
- AC-31: T57, T59, T60, T61
- AC-32: T64, T65, T66, T67
- AC-33: T38, T39, T58, T59
- AC-34: T39, T56, T79
- AC-35: T54, T55, T56
- AC-36: T44, T45, T66, T67
- AC-37: T41, T62
- AC-38: T40, T41, T58, T62, T63
- AC-39: T46, T47
- AC-40: T43, T49, T50, T52
- AC-41: T42, T46, T51, T52, T72
- AC-42: T56, T70, T71, T72, T73
- AC-43: T68, T69, T71, T74, T75
- AC-44: T38, T53, T54
- AC-45: T34, T48, T55, T75, T76
- AC-46: T76
- AC-47: T76
- AC-48: T76
- AC-49: T66, T67, T69
- AC-50: T77, T78, T79

## Resolved escalation provenance
Planning history, not an open item: the first plan revision declared one escalation (E1) because the contract's proposed `ReviewSelection` collided with the existing editor type at `intellij-plugin/src/platform/java/app/drydock/intellij/review/ReviewSelection.java`. The planner's narrow contract amendment named the new value `app.drydock.review.ReviewIntent` (core, platform-free; INV-20), expanded as T23 (value) and T24 (platform carry). Historical count 1, resolved 1, outstanding 0. Final-seam corrections (S1-S3) are expanded without new escalation: (S1) the earlier plan wording that rename initiation under `commitIfCurrent` is the commit point is WITHDRAWN as an unauthorized weakening; the amended contract's detached path is expanded as T77 (shared `AgentRenameMutation`), T78 (local delegation, behaviour unchanged), T79 (`DrydockPluginService.renameRemoteFromAgent`, permit passed explicitly, whole `stateStore.update` guarded in the executing callback, permit fence -> state lock), T54/T59 (shared owner fence), T55 (`RemoteRenamer` adapter capturing the dispatch-time permit) and T61 (production binding of `plugin::renameRemoteFromAgent`). Scheduling is never a commit. (S2) T58/T59 follow the accepted private-file route (local 0600 staging -> bounded BoxShell.copy/SCP -> verified remote private pending file -> helper validates and atomically publishes); no stdin payload and no CommandRunner/ProcessRunner API change. (S3) T9 now depends on T6 (the single Cancellation type) and T23; T60 composes the real T51/T52 cache and data source; T66 calls the T60 ensure boundary; T67 depends on T61; T61 constructs and injects the production cache/data source/forward owner/generations. Pi needs no production change (T33). The unordered shared-file check and the NEW-type producer-before-consumer check were re-run by script over the recomputed waves with zero findings (consumers flagged were pre-existing types merely edited by earlier tasks).

fidelity: pass — S1-S3 are expanded as named by the amended contract and the coordinator's seam brief, without weakening INV-65 or any other constraint; every INV (1-65) has an owning task and every AC (1-50) a done-when; no rejected alternative is reintroduced and no unnamed product or architecture decision is made. Not an implementation approval; real-workspace AC-46/AC-47 remain blocking evidence debt and nothing was built or tested.
