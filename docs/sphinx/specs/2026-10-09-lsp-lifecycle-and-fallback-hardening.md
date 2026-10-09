# Spec: lsp-lifecycle-and-fallback-hardening

Source: 20 local review findings (sphinx-address). Design: `.sphinx/address/design.md`, which converged after 2 adversary rounds.

## Problem

The optional JDT tier (usage-resolution tier 3) has five problems.

1. **Run ownership.** `JdtServerManager` cannot tell which server run an async callback belongs to: process exit, initialize response, or server notification. A late callback from a detached run can:
   - close or advance its replacement;
   - consume the shared `expectingExit` flag that belongs to another stop;
   - overwrite DOWN/DISABLED after a crash.
2. **Closed-client errors escape.** Sends on a closed client throw `IllegalStateException` out of the query path (`syncIfStale`), the initialize handshake, and shutdown.
3. **Registry ownership and blocking.** `MainWorkspace.LanguageServerRegistry`:
   - gives providers a fixed manager, which a sibling tab's close permanently kills;
   - races config publication against manager creation;
   - closes managers serially on shared single-thread executors (UserConfig SAVE_EXECUTOR, WorktreeService);
   - builds a throwaway manager just to compute a cache path.
4. **Untrusted input boundaries are unchecked.**
   - LSP framing allocates whatever `Content-Length` says, and buffers headers without limit. A huge value is an OOM that kills the reader without terminating the client.
   - Server URIs are compared to the root lexically, so symlinked roots silently drop answers.
   - A typed settings path can throw `InvalidPathException` on the FX thread.
5. **Composition/UI edges.**
   - The fallback declaration is scanned twice per peek.
   - A lexical override is redundant.
   - The once-per-session hint is consumed even when it does not apply.
   - A Javadoc is detached from its method.

## Correct behaviour

### A. Per-run lifecycle ownership (JdtServerManager)

- **A1 generation.**
  - A `long generation` is guarded by the manager lock. It is incremented:
    - when a start attempt begins (the attempt captures it as `run`);
    - on every detach of the live pair;
    - when a crash of the current run is accepted.
  - Every run-originated callback carries `run`: exit, initialize response, server notification, graceful release. It acts only if the manager is not closed and `run == generation`, checked under the lock.
  - The start's install step uses the same guard. If the attempt is closed or stale, it closes the just-spawned client and process itself.
- **A2 stale = no-op.** A stale callback changes nothing: client/process fields, state, timers, in-flight queries, crash bookkeeping and sync bookkeeping all stay. It closes nothing and sends nothing.
- **A3 intentional stops.**
  - Idle stop, NOT_INDEXED release, initialize-failure release and `close()` detach under the lock before closing outside it. Their exits are stale by construction.
  - `expectingExit` no longer exists.
  - An exit that fires inline while its callback is being registered belongs to the current run and gets the crash policy.
- **A4 crash policy (unchanged).**
  - A current-run exit cancels all timers and fails in-flight queries with `QueryUnavailableException`.
  - The state becomes DOWN on a first crash (backoff 30 s), or DISABLED on a second crash within 5 min.
  - A later initialize failure of the crashed run cannot overwrite DOWN/DISABLED.
- **A5 initialize.**
  - A current-run success marks initialize as responded and may reach READY, in either order relative to ServiceReady.
  - Exactly one `initialized` notification is sent, outside the lock, through the connection that issued the request. Any `IOException` or `RuntimeException` from that send is logged and swallowed.
  - A current-run failure releases only its own pair and leaves NOT_STARTED, which can be retried.
- **A6 never throw from queries or close.**
  - Document sync and the request send are inside one RuntimeException guard. A closed client degrades that query to `Optional.empty()`, so the lower tiers answer.
  - A failed didOpen/didChange is not recorded as synced.
  - The shutdown sequence swallows `IOException | RuntimeException` from `exit`, so client close, process close and owned-scheduler shutdown always run.
- **A7 locking.**
  - State, fields and generation change only under the manager lock, and sync bookkeeping only under the sync lock.
  - No protocol send, wait or close is newly placed under a lock.

### B. Registry ownership, config publication, blocking policy

- **B1.** One registry lock guards:
  - config, closed and configLoaded writes;
  - drains;
  - manager creation;
  - manager removal.

  Key canonicalization runs before the lock. No manager close, cache walk or wait runs under it.
- **B2 no captured manager.**
  - `provider()` (when gated on) creates the worktree's manager object eagerly, exactly as today: at most one per worktree, and no process starts.
  - The returned provider resolves the current manager through the registry once per top-level query (declaration or usagesAnswer). It uses that one manager for the whole query.
  - After a retirement, the next query rebinds to a fresh manager under the current config.
  - When the registry is closed or unconfigured, the provider returns the fallback answer unchanged.
- **B3 config serialization.**
  - Manager creation reads config under the lock, and a config change swaps and drains under the same lock. After `updateConfig` returns, no manager built from the old config is reachable.
  - An unchanged config retires nothing.
  - Every call marks config as loaded, even an unchanged config and even after close. Config is published before the loaded flag.
- **B4 off-thread retirement.**
  - Config change, tab close and worktree removal detach synchronously. Their blocking work runs on registered virtual threads: closes, and for removal, close then cache delete.
  - The caller's executor (FX, UserConfig, WorktreeService) never waits.
  - `updateConfig` and `removeAndDeleteCache` return a `CompletableFuture<Void>` that completes when their work ends.
  - Empty retirement starts no thread and returns a completed future.
  - Removal deletes the cache even when no manager existed, and even after `closeAll`.
- **B5 parallel fan-out.**
  - N managers get N concurrent closers, and every one is attempted even if a sibling fails.
  - Failures are logged at WARNING.
- **B6 bounded shutdown.**
  - `closeAll` marks the registry closed, drains, closes in parallel, then waits for that batch AND every earlier still-running retirement, under one 10 s overall deadline.
  - On timeout it logs a WARNING and returns without interrupting. On interrupt it restores the flag and returns.
  - It then shuts down the file-reader executor. It is idempotent.
  - After it, providers are the lexical instance and existing providers fall back.
- **B7 cache path.**
  - `JdtServerManager.dataDirFor(worktreeRoot, dataRoot)` is a public static pure function. A null `dataRoot` means `~/.drydock/lsp`. It is the single slug/dataDir calculation, and the slug format is unchanged.
  - `JdtServerManager.deleteCacheDir(dataDir)` is public static, idempotent, and never throws.
  - Cache deletion constructs no manager or scheduler.

### C. Canonical containment (LspUsageProvider)

- **C1.** Outgoing positions and URIs stay built from the provider's normalized root.
- **C2.** A canonical root is computed once: `toRealPath()`, falling back to the normalized absolute path.
- **C3.**
  - One containment/relativization helper serves definition acceptance, definition re-centring and reference upgrading. It tries the lexical root first, then the canonical form of the path against the canonical root.
  - Containment and relativization therefore always agree, and no `../` path is ever produced.
- **C4.** An unparseable URI, or a path outside the root in both forms, is a miss: the fallback stands. A missing file falls back to its normalized form.
- **C5.** Canonicalizing continuations run on the file-reader executor, never on the LSP reader thread.

### D. Framing bounds (LspClient)

- **D1.** `Content-Length` must be in 0..64 MiB inclusive. A negative value or 64 MiB + 1 is an `IOException` before any allocation. A malformed value keeps its existing `IOException`. 0 is a keepalive.
- **D2.** A header block of 64 KiB without its CRLFCRLF terminator is an `IOException`.
- **D3 overflow policy.** Any bound violation is fatal for the transport:
  - the client terminates;
  - every pending request fails with `TransportException`;
  - there is no resync or skip;
  - the writer is unchanged.

### E. One fallback declaration per peek

- **E1.** Both internal uses of the fallback declaration go through a single-slot, take-once memo keyed by symbol. A `declaration(s)` followed by `usagesAnswer(s)` scans once.
- **E2.** There is at most one entry. A mismatch or an already-consumed entry rescans. The answer is never stale and never belongs to another symbol.
- **E3.**
  - The shared future is the fallback's own, failures included.
  - The fallback's usagesAnswer is called once per usagesAnswer.
  - The interface and callers are unchanged. Raw-line reads stay uncached.

### F. Lexical default

- **F1.** `LexicalUsageProvider` inherits `UsageProvider.usagesAnswer` and always answers ANSWERED.

### G. Hint claim

- **G1.** The "Exact references need a language server — see Settings" hint shows only when all of these hold:
  - the peek is a .java symbol peek;
  - it has occurrences;
  - none of them is RESOLVED;
  - the owner's "hint applies" supplier returns true.
- **G2.** A failed check leaves the once-per-run flag untouched. The flag is set only when the hint is added.
- **G3.**
  - Production (Review view and Explorer viewer) uses the registry's `hintApplies()`: config loaded, AND not closed, AND no jdtHome. It is a lock-free volatile read.
  - A configured tier, in any manager state, means no hint. Config not yet loaded also means no hint, and the flag is not consumed.
- **G4.** Unwired PeekLayers and Hosts default to "applies", which is today's behaviour. The text and style class are unchanged.

### H. Settings commit

- **H1.**
  - An invalid typed path in the worktrees row or the language-server row is an immediate, retryable, inline refusal. It starts no save, sets no committing state, disables no controls, and leaves `lastCommitted` unchanged.
  - Nothing escapes Enter, focus-loss or `flushPendingEdit()`, so later rows still flush.
- **H2.**
  - Message: "Not a valid path: <reason>", styled `worktree-error`. In the language row it appears in its status label; in the worktrees row, in its hint label.
  - No modal is shown.
  - The worktrees hint is restored to its default on the next commit attempt.
- **H3.** Valid-path flows are unchanged.

### I. Docs

- **I1.** `reviewRootOf`'s Javadoc and method sit above `createOpenSessionTab`'s Javadoc. No behaviour change.

## Constraints

- No server starts at provider construction or from manager creation. Tier 3 stays upgrade-only.
- Nothing blocks FX, SAVE_EXECUTOR or the WorktreeService executor on a close or a cache walk. `closeAll` blocks. It is called from `DrydockApplication.stop()`, i.e. JavaFX `Application.stop` on the FX thread during shutdown. That blocking already exists, and it is now bounded at about 10 s overall instead of N × ~9 s.
- Existing public `LspUsageProvider` constructors and `UsageProvider` implementors stay source-compatible. The new supplier constructor is public.
- MainWorkspace uses `TimeUnit`, not `java.time.Duration`, because it imports `javafx.util.Duration`.
- No production-only test hooks.
- The cache slug/hash stays byte-identical.
- Allocation-light: the generation is a captured `long`, the memo is one atomic slot, and the retiring set is bounded by in-flight work.

## Scope

### Primary fixes

Paths are relative to `app/src/main/java/app/drydock/`.

| Item | Location | Defect class | Fix |
|---|---|---|---|
| F-84a096f28a74 | `lsp/JdtServerManager.java:531` | lifecycle ownership | replace shared `expectingExit` with per-run generation (A1–A3) |
| F-4c7291f6f4f1 | `lsp/JdtServerManager.java:379` | lifecycle ownership | exit callback carries its run; a stale exit is a no-op (A1, A2) |
| F-8ffdd6142fe8 | `lsp/JdtServerManager.java:375` | lifecycle ownership | an old run's late exit must not tear down a newer run (A1–A4) |
| F-529b9793aac5 | `lsp/JdtServerManager.java:420` | unguarded shared read / closed-client send | `initialized` via the issuing connection, guarded (A5) |
| F-44de3afd3bb5 | `lsp/JdtServerManager.java:407` | unguarded shared read | initialize response tied to its run; no unlocked field read (A1, A5) |
| F-e99f01567028 | `lsp/JdtServerManager.java:405` | missing RuntimeException guard | catch `IOException \| RuntimeException` around `initialized` (A5) |
| F-16efd8281ebf | `lsp/JdtServerManager.java:309` | escaping exception | sync inside the query's RuntimeException guard (A6) |
| F-dec2197c4c14 | `lsp/LspClient.java:316` | unbounded allocation | Content-Length 0..64 MiB (D1, D3) |
| F-a6946e8a89fb | `lsp/LspClient.java:300` | unbounded allocation / reader death | reject negative and oversized lengths before allocation (D1, D3) |
| F-e12b80c32329 | `ui/MainWorkspace.java:5183` | captured stale owner / race | per-query manager lookup; lock-serialized creation vs config (B1–B3) |
| F-aae5a2347ee4 | `ui/MainWorkspace.java:650` | blocking on a shared executor | config retirement off SAVE_EXECUTOR (B4) |
| F-e8014a08a1e3 | `ui/MainWorkspace.java:551` | blocking on a shared executor | removal close and cache delete off WorktreeService's executor (B4) |
| F-5c0d70126ffd | `ui/MainWorkspace.java:5585` | serial blocking fan-out | parallel closes with one overall deadline (B5, B6) |
| F-f71589034a5e | `ui/MainWorkspace.java:5480` | throwaway resource for a pure computation | static `dataDirFor`/`deleteCacheDir` (B7) |
| F-21471fe34105 | `ui/MainWorkspace.java:5222` | doc adjacency | move the block (I1) |
| F-bce3a530e858 | `DrydockApplication.java:673` (anchor; edit in `lsp/LspUsageProvider.java`) | path canonicalization | symlink-safe containment (C1–C5) |
| F-eb5e56809fc0 | `lsp/LspUsageProvider.java:147` | redundant work | take-once fallback declaration memo (E1–E3) |
| F-579e4c7ab0f2 | `ui/nav/LexicalUsageProvider.java:79` | dead override | delete it; inherit the default (F1) |
| F-31dff31c7d68 | `ui/nav/PeekLayer.java:300` | premature once-only claim | gate the hint on applicability; claim only when shown (G1–G4) |
| F-c11d3d5d1ce5 | `ui/SettingsModal.java:478` | unchecked exception on FX | inline `InvalidPathException` refusal (H1–H3) |

### Auto-expanded sibling fixes

- **`lsp/JdtServerManager.java`, `attemptStart`'s `clientFactory.connect(..., this::onNotification)`: unbound notification listener (same lifecycle-ownership class).**
  - FLOW: idle stop detaches run A → a query starts run B → A's reader, still in its shutdown grace, delivers a progressReport Error or ServiceReady → B is advanced, or set NOT_INDEXED and released.
  - PRECONDITION: A delivers after B starts.
  - REACHABLE: yes. CONCLUSION: high.
  - Fix: A1/A2.
- **`lsp/JdtServerManager.java`, `shutdownCaptured`: `exit` send catches only IOException (closed-client RuntimeException class).**
  - FLOW: the server dies during the shutdown grace → the reader EOF closes the client → `exit` throws ISE → the process close is skipped, and `close()` throws and skips the owned-scheduler shutdown.
  - REACHABLE: yes. CONCLUSION: medium.
  - Fix: A6.
- **`lsp/JdtServerManager.java`, `onInitializeResponse` error branch: a stale initialize failure overwrites DOWN/DISABLED.**
  - FLOW: a crash while STARTING → DOWN, client closed → the pending initialize fails → NOT_STARTED. This bypasses the backoff and the DISABLED latch.
  - REACHABLE: yes. CONCLUSION: medium.
  - Fix: A1/A4.
- **`lsp/LspClient.java`, `readHeaderBlock`: unbounded header buffer (unbounded-allocation class).**
  - FLOW: stdout without CRLFCRLF grows the buffer per byte.
  - REACHABLE: yes. CONCLUSION: medium.
  - Fix: D2.
- **`ui/SettingsModal.java`, `worktreesRow` commit: unguarded `Path.of(text)` (unchecked-exception-on-FX class).**
  - FLOW: Enter, focus-loss or flush with a NUL path throws on FX and aborts later flushes.
  - REACHABLE: yes. CONCLUSION: low.
  - Fix: H1/H2.

## Assumptions

All of these were resolved at ≥90% confidence.

- The design-pass plans use process-identity guards. A generation counter is used instead because it also covers notifications, which arrive before install. It is the "per-generation state" the findings ask for.
- The three "Reviewer instruction" lines that disagree with their own file, line and body (F-8ffdd, F-44de, F-a694) are ignored. Each item is mapped by its structured fields.
- F-bce3's DrydockApplication anchor is a representative call site and is not edited.
- F-e12b's `removeTab` anchor stays. The fix is in registry/provider binding. Reference counting is out of scope, and a surviving tab's manager may cold-restart after a sibling tab closes.
- `closeAll` waiting for earlier off-thread retirements is in scope. It is the stop-path half of moving closes off-thread, and without it a retirement could be cut off at JVM exit.
- Explorer hint wiring is in scope because Explorer peeks share the same once-per-run flag.
- The worktrees-row sibling uses inline feedback, not `UiErrors.show`. A modal inside a focus-driven commit re-enters.
