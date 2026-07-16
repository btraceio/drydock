# Gate 0E: running the real `claude` CLI in the embedded terminal

Plan section 7 "Gate 0E" / section 28 "Task 7". Spike: `app.cpm.terminal.Gate0eSpike`
(run via `./gradlew gate0eSpike -Papp.cpm.gate0e.repo=<throwaway repo>`).

## What this spike does, and does not, prove

Unlike Gate 0D (`docs/native-integration.md`, "Task 6 / Gate 0D"), this spike does
**not** assert hard pass/fail against deterministic output. `claude`'s TUI layout,
timing, and model responses are real, non-deterministic behavior outside this
project's control. Each scripted step instead logs a full `ghostty_surface_read_text`
screen dump with a clear label; the findings below were written by reading those
transcripts (two full runs, both captured under the session scratchpad directory,
not checked in — this document is the durable record).

The throwaway repo used for both runs was created fresh under
`/private/tmp/.../scratchpad/gate0e-test-repo` (never this project's own checkout),
a plain `git init` with one commit, per plan section 21 ("never run repository paths
through a shell string" — the repo path is passed as `ghostty_surface_config_s`'s
`working_directory` field, a plain string, never interpolated into a shell command).

**No permission-bypass flags were passed.** Every prompt sent to the nested session
was deliberately read-only (arithmetic, "list files", "echo an env var") specifically
*because* this dev machine's own global `~/.claude/settings.json` has
`"permissions": {"defaultMode": "auto"}` (see "Environment-specific finding" below) —
so even an auto-approved tool call could only read the throwaway repo, never write to
it or anywhere else.

## Per-checklist-item findings (plan section 7 "Gate 0E")

| Item | Result |
|---|---|
| startup | **Verified.** `claude` starts, renders its full banner (ASCII mark, model/effort line, account, cwd, "Tips for getting started" panel, changelog) inside the embedded surface. |
| workspace trust prompt | **Not observed — environment-specific, see below.** No trust dialog appeared on first launch in the brand-new throwaway repo. |
| login/configuration behaviour | **Verified (already-authenticated case only).** This machine has a real, already-authenticated `claude` install (real user account, real subscription), so the spike could only exercise the "already logged in" path — banner shows `Welcome back Jaroslav!`, account org, and model. The first-run/unauthenticated login flow was **not** exercised (would require a clean `~/.claude`, out of scope for a spike against the real account). |
| multiline prompt editing | **Verified.** `\` followed by Enter inserts a soft newline inside the same input box (confirmed by reading the screen *before* the final Enter — both lines visible stacked in one unsubmitted input box); the following Enter submits both lines as one message, and the model's reply ("Got it — I see both lines as one message.") confirms the CLI itself received it as a single multi-line input, not two messages. |
| permission prompts | **Not observed — environment-specific, see below.** With `defaultMode: auto` globally configured, no interactive permission dialog appeared for the read-only "list files" tool call in either run. |
| streaming output | **Verified.** Screen dumps taken ~1.5s and ~5.5s after submitting "What is 7+5?" show the transient spinner state ("✳ Simmering…") followed by the settled final answer ("⏺ 12" plus "✻ Cogitated for 2s"), i.e. genuinely different intermediate vs. final rendered content, not just a single atomic paint. |
| tool execution display | **Inconclusive in both runs**, for reasons below (see "Incompatibility: dropped Enter keystrokes"), not because tool-execution UI doesn't exist — it does (`⏺` tool-result markers are visible elsewhere in the transcript) but the specific "list files" step never actually submitted before the script moved on. |
| Ctrl+C cancellation | **Incompatibility found — see below.** Sending `ghostty_surface_key` with `GHOSTTY_MODS_CTRL` + the native 'C' keycode (the exact mechanism Gate 0D verified interrupts a foreground `sleep` in a plain shell) did **not** stop a 200-word essay generation; the essay ran to completion regardless. |
| terminal resizing during output | **Verified, at the "doesn't crash" level.** Resizing the host view (900x600 → 1500x900, including the `scale_factor`-adjusted pixel size passed to `ghostty_surface_set_size`) while `claude` was actively streaming a response threw no exception and the surface kept rendering afterward, reflowing to the new width. |
| copy/paste | **Not exercised.** Same limitation as Gate 0D (`docs/manual-terminal-checklist.md`): this spike drives ghostty's C API directly, not the real AppKit responder chain / system pasteboard, so Cmd+C/Cmd+V have no meaningful headless equivalent here. |
| quitting and relaunching | **Partially verified, with a serious incompatibility — see "Incompatibility: closing a surface with a live child process" below.** `/exit` typed as a command worked in isolation when it actually got submitted (confirmed once in run 1, where the concatenated `.../exit` message still triggered `claude`'s own "thinking" spinner on the whole blob, i.e. `/exit` alone would have been recognized had it not been glued onto prior unsent text). Relaunching a fresh `claude` surface in the same repo/host after closing the old one worked with no crash and a normal fresh banner. |
| `claude --resume` | **Verified it runs and renders a picker; found a related data-loss incompatibility — see below.** `claude --resume` in the test repo shows a proper "Resume session" picker UI (search box, project name, "Ctrl+A to show all projects" hint) rather than silently doing nothing. In both runs it reported **"No conversations found in this project"**, despite a real multi-turn session (7+5, the multiline message, the essay) having just run in that exact repo — see the incompatibility below for why. |
| inherited managed settings | **No managed-settings file present on this dev machine** (`/Library/Application Support/ClaudeCode` and `/etc/claude-code` both absent), so there was nothing to inherit; not exercisable here. Documented so a future run on a managed machine knows to re-check. |
| inherited gateway environment variables | **Inconclusive in both runs** — the probe step (spawn `/bin/zsh -l -c "export CPM_GATE0E_PROBE=xyz789_gate0e; exec claude"`, then ask `claude` to `echo $CPM_GATE0E_PROBE` via its bash tool) never got a chance to answer before the script's fixed timeout elapsed, in both runs, for the same dropped-Enter reason below. What **is** independently confirmed: `GhosttySurface.create` passes no explicit environment map to `ghostty_surface_config_s` (there is no such field being set anywhere in this codebase), so the spawned command inherits the ghostty/JVM process's own environment by default (Zig's `std.process.Child` defaults to inheriting the parent's env unless overridden) — meaning a variable exported by a wrapping login shell reaches the `claude` process by ordinary Unix process-environment inheritance, independent of anything this spike or app does. The *chat-level* proof that `claude`'s own bash tool sees it was not obtained; a follow-up run with settle-detection (see below) instead of fixed delays should close this out before Task 8. |

## Environment-specific finding: this dev machine bypasses the trust/permission UI entirely

`~/.claude/settings.json` on this machine has:

```json
{
  "permissions": { "defaultMode": "auto" },
  "skipDangerousModePermissionPrompt": true,
  "skipAutoPermissionPrompt": true
}
```

This is the **user's own pre-existing global configuration**, not anything this
project touched (plan section 21: "never modify managed Claude settings" — this
spike only reads `~/.claude/settings.json` indirectly by launching `claude`, never
writes to it). Its effect on this investigation: the workspace-trust dialog and
per-tool permission prompts that a fresh/default install would show never appeared
in either run, for a brand-new, never-before-seen throwaway repository. This is
worth flagging explicitly for the real application, once built: **CPM cannot assume
a permission or trust prompt will always render** in the embedded terminal — that
UI's presence is entirely a function of the user's own `~/.claude/settings.json`,
which the plan already forbids this project from reading or changing. The
first-run/trust-prompt/interactive-permission-approval code paths could not be
exercised end-to-end on this machine; they should be re-verified manually on a
freshly-configured account (or with a temporary `HOME` pointing at an empty
`~/.claude`) before shipping.

## Incompatibility: dropped Enter keystrokes sent immediately after a screen transition

Observed at least twice across the two runs (once for the "list files" prompt in
run 1 — its Enter never registered, so a later `/exit` typed shortly after got
appended as literal characters onto the *same still-open, unsubmitted* input line
instead of starting a fresh message or executing as a slash command; once for the
env-inheritance probe message in run 2, sent ~1.5s after a fresh `claude` relaunch's
own Enter-to-dismiss-any-dialog step).

In both cases, the sequence was: `surface.sendCharKey(...)` for each character of
the message, then immediately `surface.sendKey(KEY_ENTER, ...)`, scheduled a fixed
number of milliseconds after the *previous* screen transition (a completed response,
or a fresh app relaunch) rather than after confirming the terminal had actually
finished redrawing and was ready to accept input. The result each time was that the
typed text sat correctly in the input box but the Enter keypress itself was not
acted on — not dropped by ghostty (Gate 0D's `sendKey`-based Enter was 100% reliable
against a plain shell), but apparently sometimes not yet consumable by `claude`'s
own ink-based TUI while it is mid-transition (still finishing painting the previous
frame, re-registering its raw-mode stdin handler, etc.).

This is a real, user-visible risk for the eventual application: **a program driving
this terminal via the same synthetic-keyboard codepath a real keystroke would use
still cannot safely assume a fixed delay is a substitute for "the TUI is actually
ready."** A human typing at a normal pace naturally has enough inter-keystroke
latency to avoid this; a scripted/very-fast synthetic input stream does not. A
mitigation was added to this spike (an idempotent "send Enter again" defensive
retry after the first one), but a *correct* fix — polling `readScreenText()` until
the screen has stabilized (unchanged across two consecutive polls) before proceeding
to the next action, rather than a fixed `PauseTransition` delay — was not
implemented here and should be adopted by any future code (this spike or the real
application) that scripts input into `claude`'s TUI. Ordinary human-driven use
through this same JavaFX/ghostty codepath is not expected to hit this, since real
keystrokes are not sent within milliseconds of a screen transition.

## Incompatibility: Ctrl+C did not cancel an in-progress response

Sequence: submitted "Write a 200 word essay about clouds.", waited ~1.2s (during
which the "Kneading…" spinner was visible, confirming generation was in progress),
then sent `surface.sendKey(KEY_C, GHOSTTY_MODS_CTRL, true/false)` — the exact same
call Gate 0D verified reliably delivers `SIGINT`-equivalent behavior to a foreground
`sleep 30` in a plain `/bin/zsh -l` (`docs/manual-terminal-checklist.md`, "Ctrl+C
interrupts a foreground command"). Screen dumps taken immediately after and 2.5s
after the Ctrl+C both still showed the essay actively growing, and it completed in
full a few seconds later ("✻ Brewed for 7s").

Because the identical keystroke mechanism is independently proven to work against a
plain shell, this points at something specific to how `claude`'s TUI (or the
`node`/Ink runtime under it) handles interrupt during generation, not a defect in
ghostty's key delivery. Two plausible, undistinguished-by-this-spike explanations:
(a) `claude`'s interrupt handling requires the keypress to land while its raw-mode
stdin reader has focus/is actively polling, and 1.2s after submit it may still be in
a state where the pty's controlling terminal's `ISIG` byte-0x03 delivery doesn't
propagate the way a shell's job control does; or (b) `claude` intentionally
debounces/ignores a single early Ctrl+C during generation (a "press again to
confirm" pattern some CLIs use) and this spike only ever sent one press per attempt.
**This should be re-verified with a real physical keypress before concluding it is
an application-level bug**, and if reproduced, is a real risk to the plan's "Ctrl+C
cancellation" requirement (section 7 Gate 0E) for the eventual embedded-terminal
UX — flagging it rather than either dismissing it or overstating it as confirmed.

**Update — a real, separate bug found and fixed on the *interactive* keyboard path**
(not this scripted `surface.sendKey` path, which was already correct): a human
physically pressing Ctrl+C in `gate0cSpike` (the one spike wired to real AppKit
keyboard input via `Gate0cSpike.onKeyEvent`) reported it appeared to do nothing, and
the only adjacent log line was an unrelated `key event: special keyCode=51
down=false` (Delete key-up, not Ctrl+C at all — keycode 51 is `kVK_Delete`, not
`kVK_ANSI_C`=8). Root cause: `onKeyEvent` only routed keys in a fixed `SPECIAL_KEYS`
table (arrows, Enter, etc.) through `ghostty_surface_key`; anything else with a
non-empty resolved `characters` string — including a genuine Ctrl+C, whose
`characters` is the raw ETX (0x03) control byte — fell through to
`surface.sendText(characters)`, the **paste-semantics** codepath
(`ghostty_surface_text`). Once a shell/`claude` enables bracketed-paste mode (which
happens quickly after showing a prompt, per the Gate 0D finding on this same paste-
vs-key distinction), that wraps the raw 0x03 byte in `\e[200~...\e[201~` markers,
which is not interpreted as an interrupt. Fixed in `Gate0cSpike.onKeyEvent`: any
keydown/up with the Control or Command modifier active now routes through
`ghostty_surface_key` unconditionally (a modified keypress is a shortcut, never
pasted text), and every key event is now logged unconditionally with non-printable
characters escaped as `\xNN` (previously, a real Ctrl+C landing in the old
`sendText` branch would have logged with the raw control byte embedded in the log
line — effectively invisible, indistinguishable from "no event arrived").

**Re-verified with a real physical keypress (2026-07-16), including the isolating
control test this finding above asked for.** Running `./gradlew gate0cSpike
-Papp.cpm.gate0c.interactive` and physically pressing Ctrl+C:
- The event now arrives and is routed correctly: `key event: RAW keyCode=8
  down=true modifierFlags=0x40101 characters=\x03` -> `key event: special/shortcut
  keyCode=8 down=true mods=2`.
- **Control test: `sleep 30` typed into the live shell, then Ctrl+C pressed once —
  confirmed by the human tester that the shell prompt returned immediately (`sleep`
  was interrupted), not after the full 30s.** This proves the AppKit -> Java ->
  `ghostty_surface_key` -> pty delivery path is correct end-to-end for the
  interactive keyboard, not just the scripted `surface.sendKey` path Gate 0D
  already verified.
- **Same physical Ctrl+C, same session, tested directly against the real `claude`
  CLI running in the foreground: it did not respond** (no visible cancellation).
  Since the identical delivery mechanism demonstrably works one command earlier in
  the same session against `sleep 30`, this confirms explanation (a)/(b) above
  rather than a delivery-pipeline bug: **the problem is specific to how `claude`'s
  TUI (Node/Ink) handles an incoming Ctrl+C, not to ghostty/AppKit/FFM key
  delivery.** This is now a `claude`-side (or Ink-runtime-side) finding, out of
  scope for this project's own native/terminal-embedding code to fix — worth
  reporting upstream if it continues to reproduce, but not a blocker for this
  plan's Gate 0E acceptance criteria the way an actual delivery bug would have
  been.

## Incompatibility: closing a surface with a live `claude` child process kills the whole JVM (root-caused and fixed)

**Root cause found, and it was not what the original report guessed.** The
crash was never really "inside libghostty's or AppKit's own surface/child-process
teardown path" as such — it was that **`stage.close()` never ran this spike's own
`shutdown()`/`GhosttySurface.close()` logic at all**. `javafx.stage.Stage.close()`
does not fire `setOnCloseRequest` (that handler only runs for user/OS-initiated
close requests, e.g. clicking the window's close button) — so every "automated:
closing" scripted step across Gate 0C/0D/0E was calling `stage.close()` directly,
which let JavaFX/AppKit tear down the native view (and, in Gate 0E, the still-live
`claude` child attached to it) completely outside this project's own Java code, with
no graceful shutdown attempted at all. Gate 0D never hit the crash only because its
checklist always drove the shell to a clean `Ctrl+D` exit *before* that direct
`stage.close()` call, not because its shutdown path was actually being exercised
differently.

**Fix applied** (`GhosttySurface.closeGracefully(long, long, Runnable)`,
`app/src/main/java/app/cpm/terminal/ghostty/GhosttySurface.java`): sends a
graceful-exit Ctrl+D and polls `processExited()` on the JavaFX Application Thread
(via `PauseTransition`, non-blocking) for up to a caller-supplied grace period before
falling back to `close()` (`ghostty_surface_free`). All three spikes'
scripted "automated: closing" steps now call `shutdown()` directly instead of
`stage.close()`, and `shutdown()`/`finishShutdown()` are now reentrancy-guarded
(closing the last `Stage` triggers JavaFX's implicit-exit path, which re-invokes
`Application.stop()`, which called `shutdown()` again before this guard existed).

**Verified after the fix:**
- Gate 0D (`./gradlew gate0dSpike`): 12/12 checks pass, **exits 0**, full clean
  `shutdown() -> closeGracefully (already exited) -> close() -> finishShutdown() ->
  stage.close()` sequence logged.
- Gate 0E (`./gradlew gate0eSpike -Papp.cpm.gate0e.repo=<repo>`): the crash — an
  abrupt process death with **zero** shutdown logging — is gone. The full sequence
  now runs and logs: `shutting down` -> `closeGracefully: child process still alive,
  requesting Ctrl+D...` -> (5s grace period; `claude` did not exit on Ctrl+D within
  it) -> `grace period elapsed... forcing close` -> `shutdown complete, no crash`.
  `ghostty_surface_free` on a still-alive child, called deliberately from this
  project's own code with full logging around it, does **not** crash.

**Residual, separate, and much less severe issue, not yet root-caused:** even
after that fully clean, logged shutdown sequence, the raw `java` process itself
(confirmed via a direct `java -cp ...` invocation, bypassing Gradle) still exits
with status 1 — with no exception, no stack trace, no further output of any kind
after `shutdown complete, no crash`. This only reproduces in the forced-close path
(a child that had to be killed rather than exiting on its own); Gate 0D, where the
shell always exits on its own before `close()` runs, exits 0. Hypotheses not yet
confirmed: Zig's `Subprocess.stop()` (`src/termio/Exec.zig`'s `killCommand`) sending
`SIGHUP` and synchronously `wait()`-ing on the child may propagate the child's
signal-terminated status somewhere up through libghostty's own process exit path;
this needs a native-side (Zig/lldb) investigation, not just Java-side logging, to
pin down further. Treat this as a follow-up item — it no longer causes silent,
unlogged process death, which was the actual severity-defining part of the original
finding.

**This also plausibly explains the `claude --resume` finding above** ("No
conversations found in this project" despite a real prior session): if closing a
surface with a live child does not give `claude` a chance to run its own
session-persistence-on-exit logic, a transcript never gets written to disk for
`--resume` to find later. This should be re-tested now that closing no longer skips
the graceful-exit attempt entirely.

**This is a load-bearing finding for the rest of the plan.** Section 29's definition
of v0.1 complete requires "closing and reopening the app does not lose
application-managed session metadata," and plan rule 27.14 requires "a focused
regression test for every native crash or lifecycle bug." The real application's
session-close code (not yet written — this is still a spike) should follow the same
graceful-then-forced pattern as `GhosttySurface.closeGracefully`, and needs its own
focused regression test once that lifecycle code exists, per rule 27.14.

## Running it

```bash
source ~/.sdkman/bin/sdkman-init.sh
export JAVA_HOME=~/.sdkman/candidates/java/23.0.1-tem   # see README.md: Gradle 8.11.1 can't launch under JDK 26 itself
cd /Users/jbachorik/src/olifer

# create a throwaway git repo OUTSIDE this project (never point this at olifer itself):
TESTREPO=/tmp/gate0e-test-repo
rm -rf "$TESTREPO" && mkdir -p "$TESTREPO" && cd "$TESTREPO" && git init -q \
  && git config user.email test@example.com && git config user.name "Gate0E Test" \
  && echo "# test" > README.md && git add -A && git commit -q -m init
cd /Users/jbachorik/src/olifer

./gradlew gate0eSpike -Papp.cpm.gate0e.repo="$TESTREPO"
```

Pass `-Papp.cpm.gate0e.interactive` to leave the window open with a live `claude`
session instead of running the scripted transcript, for a human to drive the rest of
the checklist (real trust-prompt UI on a clean account, real Cmd+C/V, physical
Ctrl+C timing).

**Known current state: this task currently exits non-zero** (Gradle reports the
`JavaExec` process failed), because of the "closing a surface with a live child
process" finding above — the scripted transcript's own `/exit` calls do not
reliably terminate `claude` before the spike closes its window. This is being
surfaced deliberately, not hidden: it is itself the most actionable finding of this
task. Fixing it (bounded wait-for-exit-then-force-close in `Gate0eSpike.shutdown()`,
or in the real application's session-close code once written) is left to a
follow-up task rather than papered over here, per plan rule 5 ("do not replace
failing native integration with mocked success").

## Files

- `app/src/main/java/app/cpm/terminal/Gate0eSpike.java` — the spike itself (scripted transcript, no hard pass/fail assertions, by design — see top of this document).
- `app/src/main/java/app/cpm/terminal/Gate0eSpikeLauncher.java` — same JavaFX-Application-from-classpath indirection as `Gate0cSpikeLauncher`/`Gate0dSpikeLauncher`.
- `app/build.gradle.kts` — `gate0eSpike` Gradle task (`-Papp.cpm.gate0e.repo=<path>` required, `-Papp.cpm.gate0e.claude=<path>` optional override, `-Papp.cpm.gate0e.interactive` for a live human-driven window).
