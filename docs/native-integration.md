# Ghostty Native Integration — Findings (Task 2)

This document records what was actually found by inspecting the pinned Ghostty
checkout at `third_party/ghostty`. Every claim below is backed by a specific
file/line in that checkout, or by a command actually run on this machine.
Nothing here is guessed.

## Pinned commit

- Submodule path: `third_party/ghostty`
- Repository: `https://github.com/ghostty-org/ghostty`
- Pinned to tag `v1.3.1`, commit `332b2aefc6e72d363aa93ab6ecfc86eeeeb5ed28`
  (annotated tag object `22efb0be2bbea73e5339f5426fa3b20edabcaa11`, tag date
  2026-03-13).
- Pinned explicitly by commit SHA in `.gitmodules`/the submodule gitlink — not
  tracking a branch.

Rationale for choosing this commit: it is the newest tagged release available
from the upstream repo at the time of this task (checked via
`git ls-remote --tags`), i.e. a recent, stable, released commit rather than an
arbitrary point on `main`.

## Required Zig version — mismatch with installed Zig, and how it was resolved

`third_party/ghostty/build.zig.zon` declares:

```zig
.minimum_zig_version = "0.15.2",
```

`third_party/ghostty/build.zig` enforces this at comptime
(`src/build/zig.zig`, function `requireZig`): it requires
`current.major == required.major`, `current.minor == required.minor`, and
`current.patch >= required.patch`. In other words it requires **exactly Zig
0.15.x (patch ≥ 2)** — not merely "at least 0.15.2".

The installed Homebrew `zig` on this machine is **0.16.0** (`/usr/local/bin/zig`,
formula `zig`). This does **not** satisfy the constraint. This was verified
empirically, not just by reading the version check:

```
$ cd third_party/ghostty && zig build --help
src/build/Config.zig:64:17: error: root source file struct 'process' has no member named 'EnvMap'
env: std.process.EnvMap,
...
src/build/zig.zig:13:9: error: Your Zig version v0.16.0 does not meet the required build version of v0.15.2
```

So the mismatch is twofold: (1) the explicit `requireZig` compile-time check
fails, and (2) even ignoring that, Zig 0.16.0's standard library has already
removed/renamed `std.process.EnvMap`, which `src/build/Config.zig:64` still
uses — so 0.16.0 cannot compile this build script at all.

**Resolution**: installed the exact required version via Homebrew's versioned
formula, without disturbing the existing `zig` 0.16.0 install:

```bash
brew install zig@0.15
```

This installs Zig **0.15.2** (verified: `zig@0.15` formula reports
`stable 0.15.2 (bottled)`) as a keg-only formula at
`/usr/local/opt/zig@0.15/bin/zig`. It does **not** relink `/usr/local/bin/zig`
(the 0.16.0 install remains the default `zig` on `PATH`), avoiding a silent,
system-wide downgrade. Confirmed:

```bash
$ /usr/local/opt/zig@0.15/bin/zig version
0.15.2
$ cd third_party/ghostty && /usr/local/opt/zig@0.15/bin/zig build --help
# succeeds, lists steps (install, lib-vt, run, test, dist, distcheck, ...)
```

**Action required for all future native build tasks (Task 3+)**: any Gradle
task that shells out to `zig build` for libghostty must explicitly invoke
`/usr/local/opt/zig@0.15/bin/zig` (or a `zig` resolved via `PATH="/usr/local/opt/zig@0.15/bin:$PATH"`),
never the bare `zig` on the default `PATH`, which is 0.16.0 and will fail as
shown above. This should be a configurable/discoverable path in the Gradle
build (e.g. a property with a documented override), not hardcoded blindly,
since other developers' machines may have `zig@0.15` linked differently.

## Supported libghostty build command

Ghostty's build is driven entirely by `zig build` (Zig's build system), there
is no separate Makefile-based libghostty target (`Makefile` in the repo root
only wraps translation/doc generation, not the library).

Key facts from `build.zig` and `src/build/Config.zig`:

- Default `app_runtime` is `.none` when the caller doesn't pass `-Dapp-runtime`
  (`src/build/Config.zig:131-134`, default resolved by
  `ApprtRuntime.default(target.result)`).
- **On macOS, when `app_runtime == .none`, `emit_xcframework` defaults to
  `true`** (`src/build/Config.zig:400-408`):
  ```zig
  config.emit_xcframework = b.option(bool, "emit-xcframework", ...) orelse
      builtin.target.os.tag.isDarwin() and
      target.result.os.tag == .macos and
      config.app_runtime == .none and
      (!config.emit_bench and !config.emit_test_exe and !config.emit_helpgen);
  ```
- **`emit_macos_app` defaults to whatever `emit_xcframework` is**
  (`Config.zig:409-413`), meaning a bare `zig build` on macOS will, by
  default, also try to build the full macOS `.app` via `xcodebuild`
  (`src/build/GhosttyXcodebuild.zig`). For our purposes (library only, no
  Xcode-built app), the build must be invoked with `-Demit-macos-app=false`
  to skip that step.
- **Critical finding: on Darwin, the ordinary "install a raw `.dylib`/`.a`"
  code path is explicitly skipped.** In `build.zig` lines ~124-144:
  ```zig
  // Runtime "none" is libghostty, anything else is an executable.
  ...
  // We shouldn't have this guard but we don't currently
  // build on macOS this way ironically so we need to fix that.
  if (!config.target.result.os.tag.isDarwin()) {
      libghostty_shared.installHeader();
      libghostty_shared.install("libghostty.so");
      libghostty_static.install("libghostty.a");
  }
  ```
  The comment is Ghostty's own upstream acknowledgment that this is a known
  gap. **The only artifact macOS actually installs is an `.xcframework`.**
  There is currently no supported `zig build` invocation that produces a
  loose `libghostty.dylib` on macOS — only a static-library xcframework
  bundle. This has a direct consequence for Task 3/4 (FFM smoke test): FFM's
  `SymbolLookup.libraryLookup` needs a dynamically-loadable image (dylib),
  and a `.a` static archive cannot be `dlopen`'d. **We will need our own tiny
  linking step** (a one-file C or Zig shim that statically links
  `libghostty.a` from the xcframework and re-exports the public
  `ghostty_*` symbols as a `.dylib`), or discover/patch a way to get
  `initShared` to install on Darwin. This is a real, confirmed gap — not
  guessed — and must be solved in Task 3, not assumed away.

- The recommended command to build the macOS xcframework, without the Xcode
  app step:
  ```bash
  PATH="/usr/local/opt/zig@0.15/bin:$PATH" \
    zig build -Doptimize=ReleaseFast -Demit-xcframework=true -Demit-macos-app=false
  ```
  This was validated to at least parse/resolve correctly with `zig build --help`
  under 0.15.2 (full build was not run to completion in this task — Task 2 is
  documentation only, per the plan; Task 3 will perform and verify the actual
  build).

- **Architecture handling — directly relevant to our dual-arch (arm64 +
  x86_64) requirement.** `src/build/xcframework.zig` defines:
  ```zig
  pub const Target = enum { native, universal };
  ```
  and `GhosttyXCFramework.init` (`src/build/GhosttyXCFramework.zig:17-24`)
  calls `GhosttyLib.initMacOSUniversal`, which explicitly builds **both**
  `aarch64-macos` and `x86_64-macos` static libraries and fuses them into one
  universal (fat) static library via `lipo` (`GhosttyLib.zig:112-133`,
  using `Config.genericMacOSTarget(b, .aarch64)` /
  `Config.genericMacOSTarget(b, .x86_64)`). This means **Ghostty already
  supports building for both Intel and Apple Silicon Mac out of the box** with
  the default `-Dxcframework-target=universal` (the default value,
  `Config.zig:117-121`) — good news for our approved deviation to target both
  architectures. The alternative `-Dxcframework-target=native` builds only the
  host's own architecture (`GhosttyXCFramework.init`'s `macos_native` slice,
  using `Config.genericMacOSTarget(b, null)`, i.e. the Zig build's own host
  triple).
  - Note: the default `universal` xcframework target also includes iOS and
    iOS-simulator slices (`GhosttyXCFramework.zig:26-52`), which we do not
    need. Task 3 should investigate whether to accept the extra iOS build
    cost, or extract just the macOS slice, or invoke `initMacOSUniversal`'s
    equivalent directly rather than the whole xcframework machinery.
  - Minimum macOS deployment target used by Ghostty's own build:
    `osVersionMin(.macos)` returns macOS **13.0** (`src/build/Config.zig:571-580`).

## Produced native artifacts

Per `GhosttyXCFramework.zig` (`XCFrameworkStep.create(... .out_path =
"macos/GhosttyKit.xcframework" ...)`), the build produces:

```
macos/GhosttyKit.xcframework/
  macos-arm64_x86_64/         # universal (lipo'd) static libghostty.a + headers, when -Dxcframework-target=universal
  ios-arm64/                  # not needed for this project
  ios-arm64_x86_64-simulator/ # not needed for this project
```

Each slice embeds the `include/` header directory (`.headers = b.path("include")`,
`GhosttyXCFramework.zig:66-69` and others). The macOS slice is a **static
library** (`libghostty.a`), not a dylib (see the "Critical finding" above).

## Public C headers

Single public header: `third_party/ghostty/include/ghostty.h` (1178 lines),
plus `include/ghostty` (a directory — not yet inspected in depth, likely
umbrella/module-map support) and `include/module.modulemap` (Clang module map
for Swift import). The comment at the top of `ghostty.h` is explicit and
important:

> "Ghostty embedding API. The documentation for the embedding API is only
> within the Zig source files that define the implementations. This isn't
> meant to be a general purpose embedding API (yet) so there hasn't been
> documentation or example work beyond that. The only consumer of this API is
> the macOS app..."

So Ghostty itself states there is **no general-purpose embedding
documentation** beyond the header and its own macOS Swift app source — matches
what was found (see "Embedding examples" below).

Key opaque types and entry points relevant to embedding a terminal surface
(all from `include/ghostty.h`):

```c
typedef void* ghostty_app_t;
typedef void* ghostty_config_t;
typedef void* ghostty_surface_t;
typedef void* ghostty_inspector_t;

int ghostty_init(uintptr_t, char**);                 // must be called once, at process start
ghostty_config_t ghostty_config_new(void);
void ghostty_config_load_default_files(ghostty_config_t);
void ghostty_config_finalize(ghostty_config_t);

ghostty_app_t ghostty_app_new(const ghostty_runtime_config_s*, ghostty_config_t);
void ghostty_app_tick(ghostty_app_t);                // must be pumped on the main thread (see below)
void ghostty_app_free(ghostty_app_t);

ghostty_surface_config_s ghostty_surface_config_new(void);
ghostty_surface_t ghostty_surface_new(ghostty_app_t, const ghostty_surface_config_s*);
void ghostty_surface_free(ghostty_surface_t);
void ghostty_surface_set_size(ghostty_surface_t, uint32_t, uint32_t);
void ghostty_surface_set_focus(ghostty_surface_t, bool);
bool ghostty_surface_key(ghostty_surface_t, ghostty_input_key_s);
void ghostty_surface_text(ghostty_surface_t, const char*, uintptr_t);
bool ghostty_surface_mouse_button(...);
void ghostty_surface_draw(ghostty_surface_t);
bool ghostty_surface_process_exited(ghostty_surface_t);
```

The macOS-specific surface config struct is the crucial one for embedding
(`include/ghostty.h:420-453`):

```c
typedef struct {
  void* nsview;
} ghostty_platform_macos_s;

typedef union {
  ghostty_platform_macos_s macos;
  ghostty_platform_ios_s ios;
} ghostty_platform_u;

typedef struct {
  ghostty_platform_e platform_tag;
  ghostty_platform_u platform;
  void* userdata;
  double scale_factor;
  float font_size;
  const char* working_directory;
  const char* command;
  ghostty_env_var_s* env_vars;
  size_t env_var_count;
  const char* initial_input;
  bool wait_after_command;
  ghostty_surface_context_e context;
} ghostty_surface_config_s;
```

This confirms the "preferred model" from plan Gate 0C is the one Ghostty
actually implements: **libghostty is handed a real `NSView*` pointer
(`nsview`) and renders directly into it** (via Metal internally — see
Renderer below); it does not expose a caller-managed pixel buffer API. There
is no alternative buffer-based rendering entry point in this header at this
pinned commit.

Config load/diagnostics, action callback union (`ghostty_action_s`,
`include/ghostty.h:~825-965`) and the runtime callback struct
(`ghostty_runtime_config_s`, `include/ghostty.h:990-1000`) round out the
"app-level" API surface (clipboard read/write callbacks, close-surface
callback, wakeup callback, and a generic `action_cb` used for a large tagged
union of UI-affecting events: title changes, bell, resize requests, clipboard
requests, etc.). Any Java binding will need to bridge these callbacks back
into Java (as upcall stubs via FFM `Linker.upcallStub`), which is squarely
inside the narrow native-boundary package per plan section 2.4/4.2.

## Required macOS frameworks

No single "linker flags" doc exists; frameworks are declared piecemeal across
the vendored `pkg/macos` Zig package (which wraps AppKit/CoreFoundation/etc.
via `zig-objc`) and a couple of other dependency packages. Verified by
grepping every `.zig` file in the checkout for `linkFramework(...)`:

```
pkg/macos/build.zig:  CoreFoundation, CoreGraphics, CoreText, CoreVideo,
                       QuartzCore, IOSurface, Carbon (conditionally)
pkg/harfbuzz/build.zig: CoreText
src/build/SharedDeps.zig: OpenGL (only when -Drenderer=opengl; not the macOS default)
```

The default renderer on Darwin is Metal
(`src/renderer/backend.zig:20`: `if (target.os.tag.isDarwin()) return .metal;`),
but **no `.zig` file in this checkout calls `linkFramework("Metal")` or
`linkFramework("AppKit")` explicitly.** The only place `AppKit`/`Metal`
linking happens is implicitly through Xcode's own project settings for the
Swift macOS app target (`macos/Ghostty.xcodeproj`), which auto-links the
default macOS SDK frameworks for a Swift app target; the `.pbxproj` itself
only lists one explicit framework file reference, `Carbon.framework`
(grepped `*.framework` occurrences in `project.pbxproj`).

**This is an open, unresolved risk, flagged rather than guessed around**:
when we link `libghostty.a` (or our derived dylib) into our own non-Xcode
build (a small native shim / dylib, per plan section 8), we will very likely
need to explicitly add at least `-framework AppKit -framework Metal` in
addition to the frameworks `pkg/macos` already declares
(CoreFoundation/CoreGraphics/CoreText/CoreVideo/QuartzCore/IOSurface/Carbon),
because Ghostty's Metal renderer and NSView interaction do use AppKit/Metal
Objective-C classes at runtime even though no `.zig` build file references
those frameworks by name (they're resolved as an implicit consequence of
building inside Xcode elsewhere). **This must be verified empirically in
Task 3/4 by actually linking and running**, not assumed from this document
alone — record whatever missing-symbol/framework errors appear, if any.

## Architecture settings

- Default `-Dxcframework-target` is `universal`, which builds and lipo's
  together `aarch64-macos` and `x86_64-macos` static libraries — this already
  matches our approved dual-arch target for v0.1 (see `Config.zig:117-121`
  and `GhosttyLib.zig:112-133`).
- Minimum macOS version enforced by Ghostty's own build: **13.0**
  (`Config.zig` `osVersionMin(.macos)`).
- CPU model: Ghostty explicitly uses the **generic** CPU model per
  architecture (`Config.genericMacOSTarget`, comment: "workaround compilation
  issues on macOS... `b.standardTargetOptions()` returns a more specific cpu
  like `apple_a15`"), i.e. it does not build against a specific narrow CPU
  microarchitecture, favoring portability over microarchitecture-specific
  codegen.

## Embedding examples

- The `example/` directory (`c-vt`, `c-vt-key-encode`, `c-vt-paste`,
  `c-vt-sgr`, `zig-vt`, `zig-vt-stream`, `wasm-*`, `zig-formatter`) **only
  covers `libghostty-vt`**, a separate, smaller terminal-state/VT-parsing
  library (`GhosttyLibVt`, `build.zig:104-119`) with its own build target
  (`zig build lib-vt`). None of these examples touch the full app/surface
  embedding API (`ghostty_app_*`/`ghostty_surface_*`) that we actually need
  for an interactive terminal (spawn a shell, render, handle input).
- **There is no official example of embedding the full libghostty app/surface
  API from C, Swift-external code, or any other consumer besides Ghostty's
  own macOS/iOS Swift apps.** The header comment quoted above states this
  explicitly ("The only consumer of this API is the macOS app"). Our
  reference implementation for correct usage is therefore
  `macos/Sources/Ghostty/Ghostty.App.swift` and
  `macos/Sources/Ghostty/Surface View/SurfaceView_AppKit.swift`.
- Concretely, `SurfaceView_AppKit.swift:390-396` shows the NSView handle being
  passed in:
  ```swift
  let surface = surface_cfg.withCValue(view: self) { surface_cfg_c in
      ghostty_surface_new(app, &surface_cfg_c)
  }
  ```
  and `SurfaceView.swift:691-704` shows how the `nsview`/`uiview` field is
  populated:
  ```swift
  func withCValue<T>(view: SurfaceView, _ body: (inout ghostty_surface_config_s) throws -> T) rethrows -> T {
      config.userdata = Unmanaged.passUnretained(view).toOpaque()
      ...
      nsview: Unmanaged.passUnretained(view).toOpaque()
      ...
  }
  ```
  I.e., the *SurfaceView itself* (an `NSView` subclass) is passed as `nsview`,
  and libghostty renders directly into that view (Metal-backed). This
  confirms we will need a real, addressable `NSView*` to hand to
  `ghostty_surface_new` — JavaFX does not publicly expose one (per plan rule
  27.7, "do not assume JavaFX exposes an NSView through a public API — verify
  it"), so the native macOS host shim described in plan section 8 is very
  likely required, not merely a fallback. This should be treated as
  effectively confirmed necessary, to be validated concretely in Gate 0C.

## Lifecycle and thread constraints

From `macos/Sources/App/macOS/main.swift:5-9`:

```swift
// Initialize Ghostty global state. We do this once right away because the
// CLI APIs require it and it lets us ensure it is done immediately for the
// rest of the app.
if ghostty_init(UInt(CommandLine.argc), CommandLine.unsafeArgv) != GHOSTTY_SUCCESS {
```

`ghostty_init` is called **exactly once, at process startup**, before any
other Ghostty API use — mirrored in `macos/Sources/App/iOS/iOSApp.swift`.

From `macos/Sources/Ghostty/Ghostty.App.swift`:

```swift
wakeup_cb: { userdata in App.wakeup(userdata) },
...
static func wakeup(_ userdata: UnsafeMutableRawPointer?) {
    ...
    DispatchQueue.main.async { state.appTick() }   // appTick() calls ghostty_app_tick(app)
}
```

This establishes the concrete lifecycle/thread contract:

- `ghostty_app_new` is given a `wakeup_cb` runtime callback. Libghostty does
  its own background I/O (reading the PTY, etc.) on its own internal
  thread(s) and calls `wakeup_cb` (from whatever thread it's on — not
  guaranteed to be the main thread) whenever the app needs servicing.
- The host's `wakeup_cb` implementation must marshal back onto the
  **main thread** (`DispatchQueue.main.async` on Apple platforms) and then
  call `ghostty_app_tick(app)` there.
- By implication, **all `ghostty_app_*` and `ghostty_surface_*` calls are
  expected to run on the same single "main" thread** — the same thread that
  owns the AppKit run loop (matches the plan's "narrow native boundary" plus
  section 18 threading model concerns: for our JavaFX app this main thread is
  the **JavaFX Application Thread**, so all libghostty calls from Java must
  be marshaled onto the JavaFX Application Thread, analogous to
  `Platform.runLater`, exactly the way Ghostty's Swift app marshals onto
  `DispatchQueue.main`).
- Numerous other UI-affecting callbacks (`action_cb`, clipboard callbacks,
  close-surface callback) are also dispatched back onto `DispatchQueue.main`
  throughout `Ghostty.App.swift` (multiple `DispatchQueue.main.async` sites
  at lines 435, 1847, 1976, 1983, 2037, 2066, 2089, 2112) — reinforcing that
  **all interaction with `ghostty_app_t`/`ghostty_surface_t` must be
  single-threaded and pinned to the main/UI thread**, and only the raw
  `wakeup_cb` notification itself may arrive on a background thread.
- `ghostty_surface_free`/`ghostty_app_free` presumably must also happen on
  that same main thread and only after all users of the pointer are done;
  this project's native boundary package must add explicit
  create/destroy/ownership tracking per plan rule 27.13
  ("Add cleanup and ownership logic when native objects are introduced, not
  later"). The exact teardown ordering (surface before app; whether
  `ghostty_app_tick` may still fire after `ghostty_surface_free`) is not
  fully documented in the header and should be established empirically in
  Gate 0B/0C/0D, then written up in `docs/architecture.md` as an unresolved
  risk if anything is still unclear after those gates.

## Summary of confirmed vs. open items for Task 3+

Confirmed (verified in the pinned checkout or by direct command):
- Exact pinned commit and how to reference it.
- Zig 0.15.2 (not 0.16.0) is required, and how it was obtained/installed
  without disturbing the existing `zig` on `PATH`.
- The `zig build` invocation shape needed to get a macOS xcframework
  (`-Demit-xcframework=true -Demit-macos-app=false`), and that it defaults to
  `universal` (arm64+x86_64) already.
- Minimum macOS deployment target (13.0) and generic-CPU codegen policy.
- Full public C API surface (`include/ghostty.h`).
- The `nsview` embedding model (confirms the "preferred model" from Gate 0C).
- The `ghostty_init`-once / `wakeup_cb`-to-main-thread lifecycle contract.
- Required frameworks per the vendored `pkg/macos` package.

Open / must be resolved empirically in later tasks, not guessed:
- Whether JavaFX can expose an `NSView*` at all (plan rule 27.7 says this
  must be verified, not assumed) — if not, the AppKit host shim in plan
  section 8 becomes mandatory rather than a fallback, which the embedding
  model found above already makes likely.
- Exact create/destroy ordering and thread-affinity edge cases for
  `ghostty_app_t`/`ghostty_surface_t` teardown — to be established in Gates
  0B–0D.

## Task 4 update: how the `.dylib` gap and framework-linking risk were actually resolved

Both items above ("no supported way to produce a loose `.dylib`" and
"whether extra `-framework AppKit -framework Metal` linking is required")
were resolved empirically in Task 4, and turned out to require a small,
reviewed patch to the vendored submodule rather than an external shim —
see `third_party/patches/ghostty-install-macos-shared-lib.patch` and the
"Building libghostty" section of `README.md` for the full account. Summary:

- A hand-rolled shim `.dylib` (linking `libghostty.a` plus every discoverable
  Zig-cache dependency archive by hand) was attempted first and abandoned:
  Apple's `libtool -static`, used internally by Ghostty's own
  `GhosttyLib.zig` to merge the compiled module with its C dependencies,
  silently **drops archive members it warns are "not 8-byte aligned"** —
  including the object containing the entire public C API. This reproduced
  identically across repeated clean rebuilds (confirmed via `nm -g` /
  `ar -t` member-by-member diffing), so it is a real toolchain defect, not a
  fluke, and not something fixable by relinking the already-broken archive.
- The actually-correct artifact was hiding in plain sight: Ghostty's own
  `build.zig` already builds a properly-linked **shared** library
  (`GhosttyLib.initShared`, using Zig's own linker rather than `libtool`)
  but never installs it on Darwin — its own comment admits this is a bug
  ("We shouldn't have this guard... we need to fix that"). The patch simply
  removes that guard for Darwin.
- Building that shared lib standalone then failed with `undefined symbol:
  _MTLCopyAllDevices` and several `_OBJC_CLASS_$_MTL*` errors — confirming
  the flagged risk was real: nothing in this checkout calls
  `linkFramework("Metal")` or `linkFramework("AppKit")` (pkg/macos's own
  build.zig only covers CoreFoundation/CoreGraphics/CoreText/CoreVideo/
  QuartzCore/IOSurface/Carbon). The patch adds both.
- With both patch hunks applied, `zig build -Dtarget=<arch>-macos
  -Demit-xcframework=false -Demit-macos-app=false` produces a correct,
  fully-linked `libghostty.dylib` per architecture — verified with `nm -g`
  (exports `ghostty_init`, `ghostty_config_new/free`, `ghostty_info`, etc.)
  and by actually calling into it from Java via FFM (see
  `app/src/main/java/app/cpm/terminal/ghostty/GhosttySmokeTest.java`).
