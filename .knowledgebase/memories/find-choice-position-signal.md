---
id: find-choice-position-signal
title: Tour choice order is scattered at the agent boundary only; the store's decode must never re-order it
kind: finding
tags: [review, tour, checks, choices, tourcodec, tourstore]
applies_to: [app/src/main/java/app/drydock/review/tour/TourCodec.java, app/src/main/java/app/drydock/review/tour/TourStore.java]
source: improve-review-tour/find-choice-position-signal
check: grep -q "thePersistedFormDecodesWithoutScatteringAgain" app/src/test/java/app/drydock/review/tour/TourCodecTest.java
status: active
recorded: 2026-10-08
valid_at: 2026-10-08
---


Agent-drafted tours reliably put the correct choice FIRST — reliably
enough that the position itself answered the question, so a reader stops
reading the options. `TourCodec.stepsFromAgent` therefore scatters every
graded check's key at the agent boundary: new position
= floorMod(checkId.hashCode() + answer, choices.size()), the choices
rotate so the key moves with its text, the answer index is remapped,
alternates scatter recursively.

Two invariants that must survive any refactor:

- **Agent boundary ONLY.** `TourStore.recordFromJson` decodes persisted
  tours through the same `stepFromJson` path; a stored tour re-scattered
  on load would move the key out from under recorded progress —
  `CheckProgress` holds the reviewer's chosen index, so a load-time
  scatter mis-grades yesterday's answers after a restart.
- **Deterministic per post** (id + answer key), never random: same post,
  same order, or recorded progress breaks the same way.

Scattering is a rotation, so the answer key stays within the choices and
`TourValidator`'s range checks hold unchanged. Prompt-level "vary the
position" was ruled out: agents drift; decode-time scatter is
unconditional.
