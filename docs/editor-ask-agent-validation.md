# Editor ask agent: live validation runbook

**RELEASE GATE: UNPASSED** — no live run against authorized Pi, Claude or
Codex CLIs has been recorded for this feature. Unit and fixture tests pass
(`./gradlew compileCoreJava compileJava test`), but they do not satisfy this
gate. Report the gate separately from build results. Change the line above to
`RELEASE GATE: PASSED` only after every scenario below has a recorded result.

## How to run

1. Use an isolated checkout, an isolated home/config for each CLI, and a
   throwaway repository. Do not use a real credential-bearing workspace.
2. Record each CLI's `--version` output and the Drydock build id.
3. For each scenario, ask a trivial question (for example "what does this
   method return?") and record the **ordered** event sequence with IDs
   redacted (`ask-A`, `ask-B`, `conv-1`, `exec-1`; never real tokens,
   endpoints, paths or code).
4. Write results to an evidence file, one line per scenario, as
   `<scenario>: PASS` or `<scenario>: FAIL <reason>`, plus the ordering.
5. Run `DRYDOCK_EDITOR_ASK_LIVE=1 DRYDOCK_EDITOR_ASK_LIVE_EVIDENCE=<file>
   ./gradlew test --tests 'app.drydock.editorqa.EditorAskLiveGateTest'`.
   Without both variables the live check is skipped.

## Scenarios

Pi (isolated Pi-only run):

- `pi-direct-tab-turn`: a question typed in the tab completes by receipt.
- `pi-tool-then-continue`: question A calls `editor_ask_answer`, then the
  agent continues; A settles only at the terminal event, not at the call.
- `pi-sequential-b-c`: B and C are submitted while A runs; each is sent only
  after the preceding terminal event.
- `pi-multi-step`: a multi-step turn yields one terminal event.

Claude (isolated Claude-only run):

- `claude-focused`: terminal detected with the tab focused.
- `claude-background`: terminal detected with the tab in the background.
- `claude-blocked-stop`: a blocking Stop hook does not settle the ask.
- `claude-permission-vs-idle`: `permission_prompt` never counts;
  only `idle_prompt` after the last Stop settles the ask.

Codex (isolated Codex-only run):

- `codex-direct-tab-turn`: a tab question completes via the rollout reader.
- `codex-sequential-b-c`: B and C wait for the preceding terminal event.
- `codex-stale-rollout`: an earlier rollout with the same cwd is not adopted.
- `codex-fresh-create`: a fresh create binds the thread of the rollout that
  holds the unique user marker.
- `codex-exact-resume`: resume by exact ID ignores earlier completions.

## Evidence record

| Scenario | CLI version | Redacted ID/event ordering | Result |
|---|---|---|---|
| (all scenarios) | not run | not recorded | UNPASSED |

Missing harness or evidence is an unpassed gate, never a pass.
