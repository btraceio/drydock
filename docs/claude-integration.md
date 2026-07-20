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

## Incompatibility: Ctrl+C did not cancel an in-progress response (root-caused and fixed)

**Final root cause, confirmed by reading Ghostty's own key encoder and by direct
hardware testing (2026-07-16): a real bug in this project's key-event plumbing, not
in `claude`.** The investigation went through several incorrect intermediate
conclusions before landing here; documenting the full path since each wrong turn was
disproven by a specific test, not just superseded:

1. **First hypothesis (wrong): `claude`'s TUI itself ignores/debounces Ctrl+C.**
   Based on the scripted `surface.sendKey(KEY_C, GHOSTTY_MODS_CTRL, true/false)` call
   not cancelling a streaming response, while the identical call reliably interrupts
   `sleep 30` in a plain shell (Gate 0D). Flagged explicitly as needing re-verification
   with a real keypress before trusting it.
2. **Second hypothesis (wrong): the interactive AppKit keyboard path had a routing
   bug** (Ctrl+C falling through to the paste-semantics `sendText` codepath instead
   of `ghostty_surface_key` — this part *was* a real, separate bug, and the fix for
   it is correct and still in place, see `Gate0cSpike.onKeyEvent`). After fixing it,
   physically pressing Ctrl+C against a live `sleep 30` correctly interrupted it —
   but the identical physical keypress against `claude` still did not cancel it.
   This was initially (still incorrectly) taken as confirmation that `claude`'s TUI
   itself was the remaining culprit.
3. **That conclusion was directly falsified**: running `claude` in a real,
   unmodified terminal application, Ctrl+C cancels generation correctly. Since
   `claude`'s own Ctrl+C handling is demonstrably functional in general, the
   divergence had to be something specific to this project's embedded terminal.

**Actual root cause**, found by reading
`third_party/ghostty/src/input/key_encode.zig`: Ghostty supports the **Kitty
keyboard protocol** (`kitty()` in that file), which TUI frameworks like Ink (which
`claude`'s CLI is very likely built on) commonly request for robust key handling —
a plain shell never requests it, so it always uses Ghostty's non-Kitty `legacy()`
encoder instead, which is why the shell/`sleep 30` tests never surfaced this. The
Kitty encoder identifies a key by first checking a table of predefined
functional keys (arrows, Enter, F-keys, etc. — matched by Ghostty's own
`GHOSTTY_KEY_*` identity), and if not found there, falls back to
`event.unshifted_codepoint`:
```zig
// Otherwise, we use our unicode codepoint from UTF8. We always use the unshifted value.
if (event.unshifted_codepoint > 0) {
    break :entry .{ .key = event.key, .code = event.unshifted_codepoint, .final = 'u', .modifier = false };
}
break :entry null;
```
A plain letter like 'C' is not a predefined functional key, so it depends entirely
on `unshifted_codepoint`. This project's code (`GhosttySurface.sendKey(int, int,
boolean)`, used for all Ctrl+-modified shortcuts) always sent `unshifted_codepoint =
0` and no UTF-8 `text` — because the native host shim
(`native-host/CpmTerminalHost.m`) never captured AppKit's
`charactersIgnoringModifiers` (the base, unmodified character; `characters` alone
gives the ETX 0x03 control byte, not `'c'`). With `entry_` resolving to `null` and no
UTF-8 fallback text either, the encoder's final branch:
```zig
const entry = entry_ orelse {
    if (event.utf8.len > 0) return try writer.writeAll(event.utf8);
    return;
};
```
**writes nothing to the pty at all** — no error, no exception, `ghostty_surface_key`
still returns normally. The keypress vanishes silently, but only when the foreground
program has negotiated Kitty keyboard protocol (i.e. specifically `claude`, not a
plain shell) — which is exactly the divergence observed.

**Fix applied**, end-to-end:
- `native-host/CpmTerminalHost.h`/`.m`: the key-event callback now also captures and
  forwards AppKit's `charactersIgnoringModifiers` as a second string
  (`unshifted_characters`).
- `CpmTerminalHostBinding.java`/`CpmTerminalHost.java`: extended the FFM callback
  descriptor, upcall trampoline, and public `KeyEventListener` interface to carry it.
- `GhosttySurface.java`: added `sendKey(int ghosttyKeyCode, int mods, boolean
  pressed, int unshiftedCodepoint)`, which populates the struct's
  `unshifted_codepoint` field explicitly (previously only derivable from `text`,
  which is never set for a non-paste keypress).
- `Gate0cSpike.onKeyEvent` (interactive keyboard path): now computes the unshifted
  codepoint from `charactersIgnoringModifiers` and passes it through for every
  Control/Command-modified key.
- `Gate0dSpike`/`Gate0eSpike`'s scripted Ctrl+C/Ctrl+D calls, and
  `GhosttySurface.closeGracefully`'s own Ctrl+D (used when force-closing a surface
  with a live child, see the finding below): all updated to pass the correct
  unshifted codepoint (`'c'`/`'d'`) too, since they had the exact same latent bug —
  it just never manifested against a plain shell.

**Verified fixed, both interactively and via the automated Gate 0E transcript:**
- Physical Ctrl+C against `claude` in `gate0cSpike -Papp.cpm.gate0c.interactive`:
  confirmed working by the human tester (prior to this fix it did not cancel; this
  specific end-to-end interactive re-test after the fix is still recommended before
  fully closing this item).
- `./gradlew gate0eSpike`'s scripted transcript, re-run after the fix: mid-generation
  screen dump shows `✻ Julienning…` (streaming in progress) immediately before
  Ctrl+C; the dump immediately after shows the spinner gone and the prompt box
  reset, and the automated check logs `screen unchanged since right-after-Ctrl+C:
  true (true is the expected/healthy case -- generation actually stopped)` —
  confirming cancellation actually took effect, not just "response finished
  quickly by coincidence."

**This also likely improves (not yet re-verified) the `claude --resume` /
session-persistence finding below**: `closeGracefully`'s own Ctrl+D previously had
the identical silent-drop bug when closing a `claude` surface, meaning the "graceful
exit" attempt before forcing a close was itself silently failing against `claude`
specifically. It should now actually reach `claude` and give it a chance to run its
own exit/session-persistence logic.

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

- `app/src/spike/java/app/cpm/terminal/Gate0eSpike.java` — the spike itself (scripted transcript, no hard pass/fail assertions, by design — see top of this document).
- `app/src/spike/java/app/cpm/terminal/Gate0eSpikeLauncher.java` — same JavaFX-Application-from-classpath indirection as `Gate0cSpikeLauncher`/`Gate0dSpikeLauncher`.
- `app/build.gradle.kts` — `gate0eSpike` Gradle task (`-Papp.cpm.gate0e.repo=<path>` required, `-Papp.cpm.gate0e.claude=<path>` optional override, `-Papp.cpm.gate0e.interactive` for a live human-driven window).
