# Controller focus permission

This clarifies the approved presentation/focus fence for T8/T39. It adds no feature, public interface, executor, persistence field, lifecycle action or cap exception.

- A matching epoch is necessary but not sufficient for focus permission. Initial construction, including passive remembered-ID adoption, issues no focus permission: `focusPermitted(epoch())` is false.
- An explicit newer presentation attempt invalidates older queued focus even when its target is unavailable, absent, or not a visible pane. Such a failed attempt changes no model layout, history, remembered ID or selection event; cancellation of stale focus is separate from those values.
- Only an accepted intentional show/focus/previous presentation may issue fresh focus permission. Hide, departure, disposal and non-intentional layout changes revoke it. After departure, both the old token and the current epoch fail permission until another accepted intentional presentation.
- Metadata refresh and availability recovery never grant focus permission. The presentation epoch/permission is independent of metadata result generations and the model's display/history fields.

## Required pins and preservation

The preserved eleven-test draft remains the traceability baseline, not a behavior specification. Keep its meaningful coverage and all unaffected assertions. The following approved-contract corrections are required rather than byte-for-byte preservation of the wrong expectations:

- Passive adoption tests assert `!focusPermitted(epoch())`, not merely an initial epoch value of zero.
- Departure tests reject the current epoch as well as the old token.
- Replace the assertion that a failed request keeps old pending focus with assertions that unavailable show, absent previous and non-visible focus attempts revoke older permission while preserving layout/history/state/events. Rename that test to describe cancellation, retaining its disposed-host coverage.

Retain the immutable original draft and map every case to T8 or T39. The manifest identifies the corrected expectations explicitly; these are stronger safety pins, not dropped tests. Run failing-capable red/mutation checks against the corresponding incorrect behavior. All other scope, size, service-isolation and later integration/manual gates remain binding.
