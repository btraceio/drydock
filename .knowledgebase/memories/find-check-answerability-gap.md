---
id: find-check-answerability-gap
title: A tour check's question must be answerable from what the tour teaches or the visible code; validation enforces only structure
kind: finding
tags: [review, tour, checks, instructions]
applies_to: [app/src/main/java/app/drydock/review/ReviewInstructions.java, app/src/main/java/app/drydock/review/tour/TourValidator.java]
source: improve-review-tour/find-check-answerability-gap
check: grep -q "CHECK_GROUNDING" app/src/main/java/app/drydock/review/ReviewInstructions.java
status: active
recorded: 2026-10-08
valid_at: 2026-10-08
---


A tour check's question, of any kind (PREDICT, TRACE, RISK), must be
answerable from what the tour itself explains (its narrative and anchor
notes) or from code the reviewer can see around it — never from outside
knowledge the tour has not taught (spec details, encodings, instruction
sets, hardware behaviour). A question the reader cannot answer tests
their background instead of the change and stops the walk.

Where it lives: `ReviewInstructions.CHECK_GROUNDING` (typed on Run
review and tour refresh) and the `review_tour` MCP descriptor's checks
text. `TourValidator` deliberately validates only what is structural
(a PREDICT needs something to read, an impact note needs a real line,
an answer key among the choices): it cannot judge what a question
assumes, and a keyword filter would reject honest questions while
passing gamed ones. Grounding is the agent's contract, not a validator
rule — do not try to move it into validation.
