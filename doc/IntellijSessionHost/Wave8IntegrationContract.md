# Pane and controller integration contract

Internal clarification of the approved contract for T7/T8. This adds no product feature, executor, retry policy, persistence field, shortcut or terminal ownership change. Existing invariants and all later/manual gates remain binding.

## Memory-only catalog identity

- Add package-private `static ManagedAgentSession AgentConsoleTabPresentation.sessionById(ManagedSessionId)` using the existing single `CatalogSessionIndex` and the existing nonwaiting initialization guard. Like the existing file-based resolver, return null for an unloaded/failed catalog or absent row; do not block or fabricate a domain session.
- Keep `sessionOf(VirtualFile)` compatible and delegate its parsed ID to that common lookup. Do not mint a legacy console file merely to resolve a pane's domain session, duplicate the UI index per pane, or add another cache/service authority.
- T7 owns the small title-provider adjustment together with the pane and new pane-command tests. Lookup behavior/compatibility is pinned in that platform test; existing title tests remain regression coverage.

## Pane facts, effects and focus

- `Host.facts(id)` and ordinary pane/age/visibility refresh are side-effect-free reads of cached immutable results, including FAILED. They do not initiate a loader selection, transcript read or retry.
- The one project controller owns the loader and initiates permitted cold/explicit selection in T40 after T9 establishes the pane/lease/Project substage; catalog refresh is T10. PaneControllerIntegrationContract.md supplies the operator-approved constructor/factory/retry and lifetime details. T7 uses Host facts and applies incoming ID/generation-stamped data on the UI thread. It must not create a second pane-owned loader just because T9 is not built yet.
- Deliver metadata through package-private `SessionPane.applyFacts(SessionFactsLoader.Result)`, which carries the ID needed for validation. A `(facts, generation)` helper may remain private after that check; an external unkeyed delivery must not bypass ID fencing. The controller passes the same keyed result. A late result may update its own retained hidden pane, but must not retarget another pane, mutate history, reopen the host or request focus. Reject obsolete generation, wrong ID, disposed pane/project, and delayed UI application after invalidation.
- Retain `Host.onStripAction` as thin event forwarding: the eventual controller forwards once to the pane's package-private command entry point; the pane binds immutable project/ID/painted intent and calls the existing shared command handlers. Do not implement the policy again in the controller or dispatch twice.
- A testing effect boundary is allowed only around actual side effects, not as a replacement for the pane's policy/target/painted-intent logic. Do not start a real provider even if a test takes the wrong branch. Clearly distinguish any structural/default-wiring pins from runtime provider proof.
- Focus permission/epoch hooks fence delayed focus requests, not permission to refresh cached facts or automatically focus from metadata. Preserve the held pane's own immutable target when it is deliberately clicked.

## Passive restoration and intent history

- Add pure `boolean SessionHostModel.restorePrimary(ManagedSessionId)` for fresh-model initialization: place the remembered ID in primary with neutral focus, without modifying current/previous intent history. Return false and change nothing if the target is known unavailable or any primary/held/current/previous state already exists; otherwise return true. Do not overwrite an active model.
- A project controller may invoke this during initial workspace-state adoption. It does not persist again, publish an intentional-selection event, focus, launch, reattach, or create panes in T8. No held or previous state is restored.
- Availability changes affect availability/slots/focus, not the pair recording the last intentional activations. Preserve an invalidated ID as history data; `showPrevious` reports it unavailable instead of selecting a substitute. Never resolve an actionable target from history alone: use the attached focused/visible slot.
- T38 owns the passive-restore primitive, availability/history separation and their model pins. T8 consumes those tested primitives; it does not edit the model or duplicate its history authority. Keep the pair-preservation constraint comment.

## Task boundaries and verification

- T7 owns three files: SessionPane.java, AgentConsoleTabPresentation.java, and new SessionPaneCommandsPlatformTest.java. No other production source is authorized by this clarification.
- The measured controller/model estimate exceeds the standard bound, so activate model-only T38 (SessionHostModel.java and SessionHostModelTest.java) before T8. T8 then owns only new SessionHostController.java, new SessionHostControllerTest.java and plugin.xml. It remains the core state/epoch/project-service stage; panes, registry subscriptions and editor attachment are later tasks. The measured T8 draft is split into bounded T8 (four core cases) and test-only T39 (seven restored cases), both required before T9. Every original case is mapped; only the explicit stronger focus expectations in Wave9FocusFenceClarification.md change. A T8 substage pass is not full controller-core acceptance.
- The existing pure model is mutable and UI-thread-confined, with getters and Outcome transitions; it does not expose a Model.Layout type. Define the controller's read-only nested `Layout` record with Optional<ManagedSessionId> primary, held, current and previous, plus SessionHostModel.Slot focusedSlot. Cache this value projection when model state changes; layout() returns it without exposing the mutable model or allocating on every read. History fields are data only, never action targets.
- Standard task ceiling remains360; T7 alone has the explicit operator-approved361-line exception for the missing-row/reappearance painted-intent correction and regression, with the same three files/original baseline. Stop/split beyond the applicable ceiling; T6's450 exception does not carry over.
- Add real failing-capable pins for stable-ID lookup/old-file compatibility, wrong/stale facts, no focus/retry from ordinary facts refresh, passive restore without current/previous/focus, and current-session invalidation retaining unavailable history. Existing model/title/strip tests remain regressions.
- If these internal mechanisms conflict with the approved contract or cannot be expanded into compilable bounded work, escalate the exact conflict. No silent interface/policy substitution or disabled test is allowed.
