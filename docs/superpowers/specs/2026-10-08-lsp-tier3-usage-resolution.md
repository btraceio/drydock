# Usage resolution tier 3: a language server behind the provenance seam

Date: 2026-10-08. Status: design — the implementation follows this
document. Evidence lives in the cairn investigation
`lsp-tier3-integration` (seam and process-rule reads, jdt.ls launch and
readiness facts, LSP wire facts, this machine's LSP reality); this
spec cites it as it goes and carries nothing it cannot ground.

## 1. Why

The tour's usage answers have two tiers today (§6.3 of the tour spec):
lexical name matches (`MEASURED`) and scoped tree-sitter binding
(`SCOPED`). Both are honest, and both are blind to exactly the things a
reviewer most wants confirmed: a receiver whose type the diff never
states (the declaration lives in another file or a dependency),
interface dispatch, overloads, type inference. `Provenance.RESOLVED` —
"a language server that has indexed the code" — ships in the enum with
its label and css class, produced by nothing, waiting for this spec.

Tier 3 plugs a language server (Eclipse JDT LS first; the tier-2
walkers already cover Kotlin) into the existing `UsageProvider` seam.
It is **opt-in by configuration**: nothing is downloaded, nothing
starts, and the answers stay exactly as they are until the user points
drydock at a server they installed themselves.

## 2. Decisions already made (do not relitigate)

- **Never auto-download a server.** Discovery is by configuration; the
  tier is off until configured (tier design, `improve-review-tour`).
  This machine has no jdt.ls anywhere — the unconfigured path is not a
  corner case, it is the first-run experience (evidence
  `ev-local-machine`).
- **No LSP4J.** A hand-rolled stdio client, ~400–600 lines; the repo
  has no appetite for the dependency and its annotation processing.
- **The tour never waits on the server.** Every tier-3 answer that is
  not already available is answered by the tier below, immediately.
- **One server per worktree** in v1. Multi-root workspaces exist but
  jdt.ls's own README warns against sharing one `-data` directory
  across unrelated workspaces; per-worktree servers with per-worktree
  `-data` is the shape that cannot cross-contaminate.

## 3. Discovery and configuration

**Where the settings live.** `~/.drydock/config.json` (`UserConfig`) —
the user-editable file, separate from the app's own state. Two keys in
a `languageServer` object:

- `jdtHome` — the unpacked jdt.ls directory: the one containing
  `plugins/` and `config_mac` (drydock is macOS-only; `config_win` and
  `config_linux` are never probed).
- `javaHome` — optional. The JDK the server runs under. Absent means
  the `java` on `PATH` (this machine's JDK is PATH-managed; `/usr/libexec/java_home`
  finds nothing — evidence `ev-local-machine` — so java_home is never
  consulted).

`SettingsModal` gains a "Language server" row: a path field with async
validation, persisted through `UserConfig.saveAsync` exactly like the
worktrees directory. Validation, run on save and again at server start:

1. `jdtHome` resolves and contains `config_mac`;
2. `jdtHome/plugins` contains exactly one
   `org.eclipse.equinox.launcher_*.jar` — the versioned filename is a
   glob, never a literal (`ev-jdtls-launch-readiness`); zero or several
   matches is a validation error naming what was found;
3. the launch java (`javaHome/bin/java`, else `java`) answers
   `java -version` through `ProcessRunner` (short timeout) — jdt.ls
   needs 17+ to run, and the probe's stderr names the version when it
   is too old.

A validation failure refuses the save (the modal shows it inline) and,
if it appears at server start anyway (the directory changed since), the
server never starts and the tier stays off. **The refusal is always
quiet in the review UI**: tiers 1–2 answer, and the peek shows a
one-time-per-session footer hint — "Exact references need a language
server — see Settings" — only on a peek that would have used tier 3
(a `.java` symbol). Nothing about a missing server is ever an error the
reviewer has to dismiss.

## 4. The client

One package, `app.drydock.lsp`, four classes, each well under the
house size limits.

**`LspServerProcess` (~200 lines) — the documented exception.** The
house rule routes every child spawn through `ProcessRunner`, but
`ProcessRunner` is run-to-completion by construction (`waitFor(timeout)`
+ `destroyForcibly()` — a long-lived server dies by contract; evidence
`ev-process-rules-today`). The exception is narrow and written into
`AGENTS.md` with the implementation: **`ProcessRunner` governs
one-shot commands; a language server is the single sanctioned
long-lived child, hosted by `LspServerProcess`** — argument list only,
never a shell, pipes (never a PTY: terminal semantics corrupt the
framing), one reader thread (stdout, framed) and one drain thread
(stderr, logged at DEBUG), no timeout on the process itself, clean
shutdown via the LSP `shutdown` request then `exit` notification, a
bounded `destroyForcibly()` (2 s join) only if that fails, and a
`close()` registered in `DrydockApplication.stop()`'s
exception-isolated closes like every other lifecycle owner. All
one-shot probes the tier needs (`java -version`) still go through
`ProcessRunner`.

**`LspClient` (~400 lines) — framing and correlation.** The base
protocol: `Content-Length: <byte count>\r\n\r\n<UTF-8 JSON>`, where the
count is **bytes, not characters** — the reader reads to `\r\n\r\n`,
parses the header, then reads exactly N bytes; the writer serializes
first, then frames with the byte length of the serialized form
(`ev-lsp-framing`). Requests carry a monotonically increasing id; a
map from id to `CompletableFuture` correlates responses; notifications
(no id) dispatch to the readiness state machine and are otherwise
dropped. Two things a minimal client gets wrong at its peril, both
pinned by tests: a **server→client request must be answered or the
server hangs** — `workspace/configuration` is answered with the
configured `settings.java.*` block (below), `client/registerCapability`
and `window/workDoneProgress/create` with an empty result, everything
unknown with JSON-RPC −32601; and a timed-out query sends
`$/cancelRequest` before giving up, so the server does not keep
working on a dead request. Per-request timeout: 10 s, after which the
caller's tier falls back — the timeout is never the reviewer's problem.

**`JdtServerManager` (~300 lines) — lifecycle and readiness** (§5–6).

**`LspUsageProvider` (~250 lines) — the seam** (§7).

**Launch command**, assembled by `JdtServerManager` from validated
configuration (README shape, evidence `ev-jdtls-launch-readiness`):

```
java -Declipse.application=org.eclipse.jdt.ls.core.id1
     -Dosgi.bundles.defaultStartLevel=4
     -Declipse.product=org.eclipse.jdt.ls.core.product
     -Xmx1G --add-modules=ALL-SYSTEM
     --add-opens java.base/java.util=ALL-UNNAMED
     --add-opens java.base/java.lang=ALL-UNNAMED
     -jar <resolved launcher jar>
     -configuration <jdtHome>/config_mac
     -data <absolute per-worktree workspace dir>
```

The `initialize` request sends `workspaceFolders: [the worktree root]`,
`capabilities` claiming nothing we do not implement (no configuration
listener, no code lenses), and `initializationOptions`:
`extendedClientCapabilities.progressReportProvider: true` (the custom
`language/progressReport` notifications carry the import signal, §6) —
**no `bundles`** (those are debug/test OSGi jars; tier 3 needs none)
— and `settings.java`: `import.gradle.enabled: true`,
`import.gradle.wrapper.enabled: true` (the worktree's own wrapper —
agents' builds already rely on it), `configuration.
updateBuildConfiguration: "automatic"`. The `-data` directory is
`~/.drydock/lsp/<worktree-slug>/`, **kept between runs**: it carries
jdt.ls's import cache, and a warm workspace is the difference between
a seconds and a minutes-long import on the second peek. It is deleted
when the worktree is deleted.

## 5. Lifecycle

One `JdtServerManager` per worktree, owned by the same wiring that owns
the worktree's review scope.

- **Start:** lazily, on the first tier-3-eligible query (a peek or
  callee resolution for a `.java` symbol) in a worktree that has a
  local checkout and a valid configuration. Never eagerly at worktree
  open — an idle worktree pays nothing.
- **Idle stop:** 10 minutes after the last query, `shutdown`/`exit`,
  state discarded. The next query cold-starts (and the `-data` cache
  makes that survivable).
- **Stop:** worktree close and app shutdown, through the same isolated
  `close()` path; a server mid-startup is cancelled, not waited on.
- **Crash:** EOF on the framed reader stream marks the server DOWN;
  in-flight futures complete exceptionally and their callers answer
  from lower tiers. One automatic restart with a 30 s backoff; a second
  crash inside 5 minutes leaves the tier off for that worktree session
  (a WARNING in the log, tiers 1–2 in the UI — a wedged server must
  never wedge the review).
- **Memory:** `-Xmx1G` (the README default). One server per worktree
  means a reviewer walking three checkouts has at most three; the idle
  stop bounds the common case to the one being read.

## 6. Readiness: a state machine, not a boolean

`ServiceReady` (the `language/status` notification) means the server is
**usable**, not that the project is imported — import and build continue
after it (evidence `ev-jdtls-launch-readiness`, from jdt.ls's own
startup code). References answered before import completes are wrong or
empty, so the manager tracks:

```
STARTING → READY → INDEXING → INDEXED
```

- `STARTING → READY`: initialize response + `language/status`
  ServiceReady.
- `READY → INDEXING`: the first `language/progressReport` (or `$/progress`)
  task whose name matches an import/build ("Importing Gradle project",
  "Building…").
- `INDEXING → INDEXED`: every started import/build task has reported
  completion. A task that reports an error, or no import task starting
  within 60 s of ServiceReady (cached workspace, nothing to import),
  ends the state machine honestly: error → `NOT_INDEXED` for the
  session (tier stays off for this server, the hint explains);
  no-task → straight to `INDEXED`. A started task that never reports
  completion is capped at **10 minutes from the first started task** —
  cold Gradle imports with dependency downloads land comfortably
  under it for projects of this scale and the `-data` cache makes warm
  ones seconds, so the cap only decides whether the tier ever turns on
  this session, never what the reviewer sees meanwhile (tiers 1–2 answer
  through all of `INDEXING`) — and expiry ends the same way as an
  error: `NOT_INDEXED` for the session, no late acceptance of a
  completion that arrives after the cap.

Only `INDEXED` servers answer tier-3 queries. Until then — and this is
the whole point of the fallback design — queries are answered
immediately by tiers 1–2. There is **no upgrade mid-peek**: a peek
that asked during `INDEXING` shows the tier-1/2 answer and finishes;
the next peek after `INDEXED` benefits. No polling, no observer
complexity, no UI that changes under the reviewer's eyes.

**Text sync without owning an editor.** Drydock is a reader; agents edit
the worktree, not drydock. But the server answers about *its* buffers:
files changed on disk after import (an agent's edit, a checkout move)
would be answered stale. The policy is **sync-on-query, full text,
capped**: before a query touching file F, if F's disk mtime is newer
than the last sync, the client sends `textDocument/didOpen` (first
time) or `didChange` (full-text) with the current disk contents, and
only then asks. Opened documents are never `didClose`d — their buffer
always mirrors what we last synced, which is what we display; the cap
is one sync per file per query, and files outside the worktree are
never opened. The server's watchers and diagnostics are ignored; we
subscribe to nothing.

## 7. Composition at the seam

`LspUsageProvider` implements `UsageProvider` and wraps the lexical
provider as its fallback. It is constructed at the seam's single
construction site (`TourController.resolveCallees` builds the lexical
provider today — evidence `ev-lsp-seam-today`), behind a factory that
consults configuration and server state: no server → exactly today's
provider, byte for byte.

**Declaration (`declaration(symbol)` for callee resolution).** With the
symbol's occurrence line (line text already carried by the seam), find
the identifier's column and ask `textDocument/definition`. A single
unique result becomes the declaration with `resolvedDeclaration: true`
and `Provenance.RESOLVED`; null, many, or a failure falls through to
the lexical candidate unchanged.

**Usages (the peek's occurrence list).** Ask
`textDocument/references` at the peeked declaration's position
(`includeDeclaration: true`). Every returned location upgrades the
matching occurrence row to `RESOLVED` (the chip and css already exist).
The composition is **upgrade-only**:

- an occurrence the server confirmed → `RESOLVED`;
- an occurrence tier 2 bound but the server did not return → stays
  `SCOPED` (the server excludes comments and strings; tier 2 already
  proved the reference real — no downgrade on a weaker answer);
- a purely lexical occurrence the server did not return → stays
  `LEXICAL` (grep finds comment and string mentions too, which is
  information the reviewer wants; the chip is the honest label).

An empty or null references result is **an answer, not a failure**
(`ev-lsp-framing`) — and precisely because of that, it never erases
lower-tier occurrences. The peek's headline gains the server line —
"N resolved · M scoped · K name matches" — when a server answered;
nothing changes when it did not.

**Re-centring.** When `definition` returns one declaration that differs
from the peek's centre, the peek re-centres on it with the
`RESOLVED` mark — the tier-3 form of the tier-2 unanimous re-centre,
stronger warrant, same rule: the peek shows what the reader asked
about, named by what it actually is.

**Positions.** LSP lines are 0-based; `Usage` lines are 1-based. The
+1/−1 lives in `LspUsageProvider` alone. LSP characters are UTF-16
units — for the Java identifiers this seam queries, ASCII. The column
is derived from the **raw file line** at the queried `Usage`'s line
(one bounded read per query, off the FX thread), never assumed: the
text the seam itself carries is stripped of leading indentation at its
sources (`SymbolPeekService`, `SymbolIndex`, `LexicalUsageProvider` all
`strip()` before storing it), so a column computed from the carried
text would land short of the identifier — inside the token to its left
— and a language server resolves the token *at* the offset, so the
position would answer for the wrong identifier while wearing
`RESOLVED`, the one miss the upgrade-only contract cannot absorb. When
the raw line cannot be read, the carried text is the fallback column —
and then a guard is mandatory: **no definition-derived answer is
accepted unless the declaration line read back contains the symbol as
a whole word**. That guard is applied to every definition acceptance
(the raw-read path too — it costs one `contains` and covers every other
way the position could drift), and it transitively protects the
references query, which is asked at the now-guarded declaration
position.

**Fan-in stays tier 2 in v1.** `OutOfDiffFanIn` runs one budgeted scan
over the whole out-of-diff tree; mixing a server round-trip per changed
declaration into it would couple the scan's cost to the server's
readiness and change its failure modes for a de-noise tier 2 already
handles. The hook is the seam itself: when tier 3 proves out in the
peek, the fan-in can ask the warm server for exact callers per
declaration site — a follow-up, specced then, not now.

## 8. Failure modes

| situation | behaviour |
|---|---|
| not configured | tiers 1–2; one quiet footer hint per session |
| invalid `jdtHome` / launcher glob / `config_mac` | save refused inline; at start, tier stays off, WARNING logged |
| `java` missing or < 17 | probe fails, same as above, version named in log |
| spawn fails | tiers 1–2; retry on next query |
| crash mid-session | in-flight falls back; one restart/30 s backoff; second crash in 5 min → tier off for the session |
| import error / never ends | `NOT_INDEXED`; tier off for this server; hint explains |
| request error response | that query falls back; logged |
| null / empty references | a valid answer; zero upgrades; lower tiers untouched |
| query timeout (10 s) | `$/cancelRequest`; lower tier; logged |
| no local checkout | existing "no checkout to search" reason path; no server |
| worktree closed while starting | startup cancelled; shutdown; released |

Every row ends the same way: the reviewer sees an answer from a lower
tier or the honest reason, never a spinner, never an error dialog
about a language server.

## 9. Testing

No test requires a real jdt.ls — none is installed on this machine
(`ev-local-machine`), and CI must not grow a server dependency.

- **Framing goldens** (`LspClientTest`): byte-exact frames including a
  multi-byte UTF-8 body whose `Content-Length` must count bytes; the
  reader reassembling a body split across stream chunks; `\r\n\r\n`
  handling; a `Content-Length: 0` keepalive.
- **Round-trip against a fake server** (`PipedStream`s): id
  correlation, notification dispatch, the mandatory server→client
  request answers (a `workspace/configuration` that goes unanswered is
  the bug this test exists to prevent), timeout → `$/cancelRequest`.
- **One real-process test** (`LspServerProcessTest`): spawn `cat` as
  the "server", write a frame, read it framed back, clean close on
  EOF, `destroyForcibly` bounded after a hung process — the exception
  class's own contract, pinned the only way that tells the truth.
- **Readiness state machine** (`JdtServerManagerTest`): driven by
  synthetic `language/status` + progress sequences — ServiceReady
  alone never reaches `INDEXED`; import completion does; error →
  `NOT_INDEXED`; no task within 60 s → `INDEXED`.
- **Composition** (`LspUsageProviderTest`): fake client answering
  canned references — upgrade-only (an empty answer removes nothing),
  `SCOPED` never downgraded, re-centring on a unique differing
  definition, declaration fall-through, 0/1-based adaptation.
- **Config validation** (`UserConfigTest` extension): glob resolution
  (zero and several launcher jars refused with what-was-found), missing
  `config_mac`, the java prober injected as a function (no real JDK
  required).

## 10. Out of scope for v1

Kotlin over LSP (tier 2's walkers already bind `.kt`; JetBrains's
kotlin-lsp is still preview-grade — re-spec when it ships stable).
Fan-in on tier 3 (§7). Hover, completion, diagnostics, code actions —
the server offers them; the seam does not need them. Multi-root
workspaces. `didSave`, organize-imports, any write-path operation
(tier 3 is a read-only consumer). Bundles (debug/test jars). LSP4J.
Remote worktrees.
