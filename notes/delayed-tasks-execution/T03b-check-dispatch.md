# Fresh checker dispatch: T03b delta on TaskFilePatch.java

Cwd: /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi
Read AGENTS.md (repo rules), plan T03b in docs/plans/DelayedTasksImplementationPlan.md,
full contract /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/T03b-contract.md
(its "Fresh checker" section is your charter).

Situation: the T03b coder failed on a transport error (malformed SSE JSON,
second occurrence) mid-run WITHOUT producing its report; the delta itself is in
the tree. The coordinator took over the missing verification steps directly:
- Delta: intellij-plugin/src/main/java/app/drydock/tasks/TaskFilePatch.java now
  546 lines; diff vs frozen baseline
  /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/TaskFilePatch-T03b-before.java
  is 201 lines (within the <=300 target).
- Gradle regression via approved adapter: 29 task tests executed green
  (compileCoreJava and :test executed; log
  intellij-plugin/build/logs/delayed-tasks-98NIkWsh.log, BUILD SUCCESSFUL).
- Coordinator-written smoke (no coder report exists to read):
  /Users/jaroslav.bachorik/go/src/github.com/DataDog/.worktrees/drydock-sneaky-fluffy-corgi/notes/delayed-tasks-execution/T03b-smoke-NuHGuZBc/Smoke.java
  passed 23/23, SMOKE-OK, exit 0; full log
  intellij-plugin/build/logs/T03b-smoke-y6rlrIYR.log.

Your job (read-only, no Gradle, no edits, no git mutation, no agents): check
ONLY the T03b delta against the task block and current code. Verify in source:
newFile canonical order/created-optional/flow lists/quoted scalars/body
verbatim/reparse guard; appendNote as a SEPARATE pure body insertion (heading
create incl. unterminated-body/heading cases, section-end placement before the
next heading, blank-line paragraph separation between notes, CRLF integrity,
blank-note and embedded-break refusal); T03a guard/quoting reuse (no second
serializer); no scope creep (no T03c fixture, no unrelated methods, no
metadata changes hidden in appendNote); delta <=~300 lines, class under ~1000.
Read the smoke harness and confirm its expectations actually pin the required
behavior (not tautologies); confirm regression log/XML counts. Report whether
the evidence above holds. Findings that the smoke missed are the valuable ones.

Memories: consult brief via untouched script
/Users/jaroslav.bachorik/go/src/github.com/DataDog/profiling-rt-ai-plugins/cairn-pi/skills/cairn-memory/investigation.py brief --paths intellij-plugin/src/main/java/app/drydock/tasks/TaskFilePatch.java
(find-t03a-metadata-preservation-checks, find-yaml-compose-gotchas). No graph edits.

Return <=10 lines via your configured output: VERDICT pass|defects; one line per
defect with file:line, wrong behavior, required outcome; confirmed counts/log
evidence; accepted deviations; memory ids. Do not review the full feature,
T03a metadata (already passed), or claim T03c complete.
