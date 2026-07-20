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
  `app/src/spike/java/app/cpm/terminal/ghostty/GhosttySmokeTest.java`).

## Task 5 / Gate 0C: rendering a terminal surface

### Does JavaFX expose a native NSView handle? (plan rule 27.7 — verify, don't assume)

**No, not through any public API.** Verified empirically, not assumed:

- Every public `javafx.stage`/`javafx.scene` class was checked (via
  `javap -p` against `javafx-graphics-26-mac.jar`) for anything resembling a
  native handle accessor. There is none.
- The handle *does* exist one layer down: `com.sun.glass.ui.View#getNativeView()`
  returns it as a `long` (confirmed by reading Glass's mac backend class
  list in the jar). `com.sun.glass.ui.Window#getView()` gets the `View` for
  a given native `Window`, and `Window#getWindows()` lists all currently
  open native windows.
- `com.sun.glass.ui` is a genuinely internal package: inspecting
  `javafx-graphics-26-mac.jar`'s `module-info.class` with
  `javap -verbose` shows it is a **qualified** export, to `javafx.media`,
  `javafx.swing`, and `javafx.web` only — not to this application's
  (unnamed) module. In a modular run this would need
  `--add-exports javafx.graphics/com.sun.glass.ui=ALL-UNNAMED`.
- **This project currently runs JavaFX from the classpath, not the module
  path** (the `org.openjfx.javafxplugin` Gradle plugin puts the JavaFX jars
  on `sourceSets.main.runtimeClasspath`, and `app/build.gradle.kts` has no
  `module-info.java`). In that mode the JVM's module system does not
  enforce qualified exports at all — `com.sun.glass.ui` classes are
  directly importable and callable with **no** `--add-exports` flag needed.
  Verified empirically: adding `--add-exports javafx.graphics/...` to the
  `gate0cSpike` JVM args fails fast with `WARNING: Unknown module:
  javafx.graphics specified to --add-exports` (there is no *named* module
  `javafx.graphics` loaded at all in this run mode) — so that flag was
  removed again. If/when this project modularizes (plan section 6.4), the
  flag will become necessary and this note should be revisited.

**Decision:** per plan rule 27.9 ("prefer a tiny explicit native host shim
over extensive reflection into JavaFX internals"), Glass access is used for
exactly one purpose and nowhere else: obtaining the single `NSView*`
pointer of the current window, in
`app.cpm.terminal.host.JavaFxNativeView` (package-private, called only
from `CpmTerminalHost.createForCurrentWindow()`). All further native work
(child view creation, resize, focus, teardown) goes through the AppKit
host shim below, not more Glass calls.

**Risk (plan rule 15):** `com.sun.glass.ui` is unsupported, undocumented,
internal API. A future JavaFX release could rename, restructure, or remove
it without notice; there is no fallback implemented. `JavaFxNativeView`'s
"most recently created window" heuristic is also only valid for this
single-window spike — a real multi-window app will need a real
`Stage`-to-native-`Window` correlation, deliberately not implemented yet.

### The AppKit host shim (plan section 8)

Implemented at `native-host/CpmTerminalHost.{h,m}`, built by
`scripts/build-native-host.sh` into
`build/native/{macos-x86_64,macos-arm64}/libcpmterminalhost.dylib`, loaded
by `app.cpm.terminal.host.CpmTerminalHostLibrary`/`CpmTerminalHostBinding`
(mirroring the `ghostty` package's loader), and exposed publicly as
`app.cpm.terminal.host.CpmTerminalHost`.

It implements the plan's six suggested functions exactly
(`cpm_terminal_host_create/set_frame/content_view/set_visible/set_focused/destroy`),
plus **one deliberate, documented extension**:
`cpm_terminal_host_set_key_event_callback`. The plan's literal API list has
no way for the host view to report keyboard input (Java code cannot
subclass `NSView` to override `-keyDown:`), so the shim's view overrides
`-keyDown:`/`-keyUp:`/`-flagsChanged:` and forwards the raw AppKit event
fields (key code, modifier flags, down/up, resolved `characters` string)
verbatim to one registered callback — it performs **zero** interpretation
of the event (no shortcut parsing, no ghostty-specific translation), so it
still satisfies section 8's "must not... implement terminal rendering /
parse keyboard shortcuts" constraints. All ghostty-specific key-code
translation (`GHOSTTY_KEY_*` mapping, deciding text vs. special-key calls)
happens in `app.cpm.terminal.Gate0cSpike` / will move to the Ghostty
adapter proper in a later task.

**Threading contract:** every shim function, and every `ghostty_*` call in
this codebase, must run on the AppKit main thread. On this project's
macOS/JavaFX setup, **the JavaFX Application Thread already *is* the AppKit
main thread** — this is a documented property of Glass's Cocoa backend
(it relaunches/reconfigures the process so the FX Application Thread runs
as the process's actual Cocoa main thread, rather than a same-process
worker thread as on other platforms). Practically: everything in
`Gate0cSpike` runs either directly inside `Application#start`/JavaFX
property-change callbacks, or inside `Platform.runLater` (used for the
`ghostty_app_new` wakeup callback, since that fires from an arbitrary
libghostty-internal thread) — never from a raw background/executor thread.
This was not independently re-verified beyond "the spike didn't crash and
AppKit calls succeeded", which is consistent with, but not conclusive
proof of, the thread-identity claim; a `Thread.currentThread()` vs. a
native `[NSThread isMainThread]` cross-check would make this airtight and
is worth adding before Gate 0D.

### Wiring an actual `ghostty_app`/`ghostty_surface` (not just an empty view)

Added `GhosttyAppBinding` (struct layouts + downcall handles for
`ghostty_app_new/free/tick/set_focus` and
`ghostty_surface_config_new/new/free/set_size/set_focus/draw/refresh/text/key/process_exited`),
`GhosttyApp` (owns the `ghostty_app_t`, its `ghostty_config_t`, and the
`ghostty_runtime_config_s` callback table), and `GhosttySurface` (owns one
`ghostty_surface_t`, created against a `CpmTerminalHost`'s content view).

**Struct layout verification methodology:** rather than hand-deriving C
struct offsets/padding by eye (error-prone, and Zig's C ABI compatibility
mode is not something to guess at — plan rule 27.6), a throwaway
`sizeof()`/`_Alignof()` C program was compiled against the pinned
`build/native/include/ghostty.h` to get ground truth:

```text
sizeof(ghostty_action_s)=32 align=8
sizeof(ghostty_target_s)=16 align=8
sizeof(ghostty_runtime_config_s)=64 align=8
sizeof(ghostty_surface_config_s)=88 align=8
sizeof(ghostty_input_key_s)=32 align=8
```

Every `StructLayout` in `GhosttyAppBinding` was cross-checked against these
sizes (all matched the manually-derived field-by-field layout on the first
try, confirming the reasoning was sound, not just declaring a
byte-count-only layout and hoping).

**What is and isn't wired up for Gate 0C:** `ghostty_runtime_config_s` has
six callback fields. Only `wakeup_cb` does something real (it invokes a
caller-supplied `Runnable`, used to schedule `Platform.runLater(() ->
{ app.tick(); surface.draw(); })`). The other five
(`action_cb`, `read_clipboard_cb`, `confirm_read_clipboard_cb`,
`write_clipboard_cb`, `close_surface_cb`) are wired to real, ABI-correct
upcall stubs (never `NULL` — a `NULL` function pointer would very likely
crash the process the first time libghostty tries to invoke one) that are
deliberately no-ops: `action_cb` always returns `false` ("not handled")
without reading its `ghostty_action_s` payload at all, since that payload
is a large tagged union covering every UI action (new window/tab, clipboard
writes, fullscreen, IPC, etc.) that a real application would need to act
on. This is explicitly **not complete** — a real app integration will need
to implement `action_cb` properly (most importantly clipboard writes via
OSC 52 and the close-surface/quit flow), which is out of scope for this
feasibility spike. This is the single biggest functional gap left after
Gate 0C.

### What was verified, and how (since a human wasn't watching the screen)

`./gradlew gate0cSpike` (`app.cpm.terminal.Gate0cSpike`, launched via
`Gate0cSpikeLauncher` — see below) runs an automated sequence
(`-Dapp.cpm.gate0c.autoExit=true`, the Gradle task's default) and exits
with status 0. From one real run's log output:

```text
[gate0c] starting
[gate0c] stage shown
[gate0c] ghostty_init OK
[gate0c] AppKit host view created and attached to JavaFX window's NSView
[gate0c] ghostty_app_new OK
[gate0c] ghostty_surface_new OK (scale=2.0)
[gate0c] resized: 900.0x600.0 logical -> 1800x1200 px
[gate0c] focus set (host + app + surface)
[gate0c] initial ghostty_app_tick + ghostty_surface_draw OK
[gate0c] automated: sent test text (direct API call, not a real keystroke)
[gate0c] automated: osascript synthetic keystroke 'q' exit=0
[gate0c] key event: text="q"
[gate0c] automated: resized stage
[gate0c] resized: 1000.0x600.0 logical -> 2000x1200 px
[gate0c] resized: 1000.0x672.0 logical -> 2000x1344 px
could not create image from display
[gate0c] automated: screencapture exit=1 -> .../build/gate0c-screenshot.png
[gate0c] automated: closing
[gate0c] shutting down
[gate0c] shutdown complete, no crash
```

Mapped against the Gate 0C acceptance criteria (plan section 7):

- **a window opens** — verified (log + process reaches `stage shown` and
  survives to a scripted close, `BUILD SUCCESSFUL` / exit 0).
- **terminal content is rendered** — **partially** verified: `ghostty_surface_new`
  and every `ghostty_surface_draw`/`ghostty_app_tick` call returned/completed
  without throwing or crashing the process, which is strong evidence the
  Metal-backed rendering path libghostty sets up on the handed-in `NSView`
  (see "the `nsview` embedding model" above — confirmed by reading
  `src/renderer/Metal.zig`, which makes the given view layer-hosting with a
  `CAMetalLayer` and relies on ghostty's own draw calls after that) is at
  least not immediately failing. **Not** independently confirmed pixel-by-pixel
  (i.e., that recognizable terminal glyphs actually appear) — see the
  screenshot note below.
- **window resizing updates terminal dimensions** — verified via logs:
  both the initial size and both dimensions of the automated
  `stage.setWidth(1000); stage.setHeight(700)` resize produced correctly
  scaled (`logical * outputScaleX`) calls into `ghostty_surface_set_size`.
- **focus works** — verified indirectly but strongly: the `osascript`
  synthetic keystroke below was delivered to *this* process's key window
  and reached the host view's `-keyDown:` override, which would not happen
  if `cpm_terminal_host_set_focused`/`makeFirstResponder:` had not
  actually taken effect.
- **keyboard input reaches the terminal** — verified with a **real OS-level
  keystroke**, not just a direct API call: the spike's automated sequence
  calls `osascript -e 'tell application "System Events" to keystroke "q"'`
  after bringing its own stage to front, and the log line
  `[gate0c] key event: text="q"` confirms that keystroke actually traveled
  System Events → the real window server → AppKit's responder chain → the
  host shim's `-keyDown:` override → the FFM upcall →
  `Gate0cSpike.onKeyEvent` → `GhosttySurface.sendText`. (The earlier
  `surface.sendText("echo gate0c\r")` call in the same log is a *direct*
  API call used only to exercise `ghostty_surface_text` itself, and is
  explicitly logged as such — it is not evidence of the AppKit key-routing
  path.)
- **the application closes without a crash** — verified: `shutdown
  complete, no crash` logs, and the Gradle task (and therefore the JVM
  process) exits 0.

**What could not be verified without a human:** the screenshot capture
step (`screencapture -x build/gate0c-screenshot.png`) failed with
`could not create image from display` / exit 1 in this run. This was
investigated, not just noted: macOS Screen Recording permission (`tccutil`
service `kTCCServiceScreenCapture`) is required for `screencapture` since
macOS 10.15+, and the process this task ran in does not have it (confirmed
— `sqlite3` access to the local `TCC.db` itself returns "authorization
denied", i.e. the calling process is sandboxed/unprivileged with respect to
TCC, consistent with lacking the grant). This is an environment limitation,
not a code defect: **no screenshot was produced by this task run**; a
human running `./gradlew gate0cSpike -Papp.cpm.gate0c.interactive` locally
(with Screen Recording permission granted once to their terminal app) would
get `build/gate0c-screenshot.png` and should look at it to confirm
recognizable terminal content (a shell prompt, ideally) is actually
rendered, since that is the one acceptance criterion this task could not
close out with certainty.

### Running it

```bash
./gradlew gate0cSpike                              # scripted, auto-exits, safe for CI/agents
./gradlew gate0cSpike -Papp.cpm.gate0c.interactive # leaves the window open for a human
```

`Gate0cSpikeLauncher` exists only because launching an
`Application` subclass directly as the JVM's main class trips JavaFX's
"JavaFX runtime components are missing" module-path detection even when
everything needed is already on the classpath (verified empirically); a
trivial indirection class avoids that check.

## Task 6 / Gate 0D: running an interactive shell

Plan section 7 ("Gate 0D") and section 28 ("Task 6"): spawn `/bin/zsh -l`
inside the Gate 0C terminal surface and work through the manual checklist
headlessly wherever genuinely possible. Full checklist results (with
per-item VERIFIED/UNVERIFIABLE/NOT-YET-RUN status) live in
docs/manual-terminal-checklist.md; this section covers the mechanism and
the notable findings.

### Making Gate 0C's evidence ceiling headless: `ghostty_surface_read_text`

Gate 0C's evidence for "does keyboard input work" topped out at "the call
didn't crash" / a screenshot a human would have to look at. Task 6 adds
`GhosttySurface.readScreenText()`, wrapping `ghostty_surface_read_text`
(struct layouts for `ghostty_text_s`/`ghostty_point_s`/`ghostty_selection_s`
verified the same way as Task 5 -- a throwaway `sizeof()`/`offsetof()` C
program against the pinned header, not hand-derived):

```text
sizeof(ghostty_text_s)=40 align=8         (tl_px_x@0, tl_px_y@8, offset_start@16,
                                            offset_len@20, text@24, text_len@32)
sizeof(ghostty_point_s)=16 align=4        (tag@0, coord@4, x@8, y@12)
sizeof(ghostty_selection_s)=36 align=4    (top_left@0, bottom_right@16, rectangle@32)
```

This lets the spike read back the terminal's actual rendered cell grid as
plain UTF-8 text (via `GHOSTTY_POINT_VIEWPORT`/`_COORD_TOP_LEFT`/`_BOTTOM_RIGHT`,
i.e. "the whole visible viewport") after sending real input, and assert
specific outcomes: a marker string appearing as its own output row (not
just "somewhere on screen" -- see the false-positive note below), `$COLUMNS`
changing after a resize, `ghostty_surface_process_exited()` flipping after
Ctrl+D.

**What this does NOT prove:** `read_text` returns decoded cell *text*, not
pixels -- colour attributes, font shaping/rendering, and exact glyph
positioning are outside what it can confirm. See
docs/manual-terminal-checklist.md for exactly which checklist items this
leaves for a human.

### False-positive risk in "does the marker appear" checks

An early version of this checklist asserted success via
`screenText.contains("MARKER")`. This is unsound: the *typed* command line
itself contains the marker string (e.g. typing `echo GATE0D_MARK1` renders
the literal characters `echo GATE0D_MARK1` on screen) whether or not the
command ever actually executed. A run where Enter did not work at all (see
below) still passed several `contains`-based checks purely because the
marker had been typed, never executed. Fixed by requiring an exact
(trimmed) match against one whole terminal *row* (`hasOutputLine` in
`Gate0dSpike`) -- a command's own output renders on a row by itself,
distinct from the "prompt + typed command" row that precedes it, so an
exact row match is real evidence the shell executed the command.

### A real bug found and fixed: `ghostty_input_key_s.keycode` is a native platform keycode, not a `GHOSTTY_KEY_*` ordinal

The first full automated run of the checklist showed every special key
(Enter, Backspace, arrows, Ctrl+C, Ctrl+D) silently doing nothing or the
wrong thing -- e.g. `[gate0d] DEBUG: Enter press NOT consumed by
ghostty_surface_key` on every attempt, and Backspace producing garbled
command lines instead of deleting characters. Investigated against the
pinned source (not guessed): `src/apprt/embedded.zig`'s
`KeyEvent.core()` resolves the incoming `keycode` field via

```zig
const physical_key = keycode: for (input.keycodes.entries) |entry| {
    if (entry.native == self.keycode) break :keycode entry.key;
} else .unidentified;
```

i.e. `keycode` must be the **native, platform-specific virtual keycode**
(`src/input/keycodes.zig`'s macOS/`native` column), not a `GHOSTTY_KEY_*` C
enum ordinal from `ghostty.h`. `Gate0cSpike`'s original `SPECIAL_KEYS` map
(Task 5) got this wrong -- it translated a real AppKit keycode into a
`GHOSTTY_KEY_*` ordinal and sent *that* as `keycode`, e.g. sending 53
(`GHOSTTY_KEY_BACKSPACE`'s ordinal) for a Backspace press, and macOS
keycode 53 actually means Escape. This was never caught by Gate 0C's
automated run because that run only ever exercised one plain typed
character (`'q'`, via `ghostty_surface_text`, a codepath that never
touches `keycode`).

Verified macOS-native keycodes (cross-checked against
`third_party/ghostty/src/input/keycodes.zig`'s `native` column, not
hand-counted): Enter=36 (0x24), Backspace/Delete=51 (0x33), Tab=48 (0x30),
Escape=53 (0x35), Left=123 (0x7b), Right=124 (0x7c), Down=125 (0x7d),
Up=126 (0x7e), Home=115 (0x73), PageUp=116 (0x74), End=119 (0x77),
PageDown=121 (0x79), the `C` key=8 (0x08), the `D` key=2 (0x02).

Fixed both `Gate0cSpike.SPECIAL_KEYS` (now a `Set` of recognized native
keycodes, passed straight through unmodified -- no translation at all) and
`Gate0dSpike`'s key constants. Re-ran `gate0cSpike` after the fix: still
builds and runs to completion with no crash (regression-checked, though
Gate 0C's own automated sequence never actually exercised a special key
programmatically, only a plain character via a real OS keystroke, so this
regression check is weaker evidence than Gate 0D's).

### A second, related finding: `ghostty_surface_text` is paste semantics, not typing

`Surface.zig`'s `textCallback` (the `ghostty_surface_text` C export) is
documented as: "Sends text as-is to the terminal without triggering any
keyboard protocol. This will treat the input text as if it was pasted from
the clipboard." Once the shell enables bracketed-paste mode (which most
shells, including this machine's zsh, do shortly after the prompt is
ready), further `ghostty_surface_text` calls get wrapped in
`\e[200~...\e[201~` bracketed-paste markers. In an early Gate 0D run this
produced visibly corrupted command lines (`echo WRONGWORD[200~GATE0D_MARK2_RIGHT`,
`zsh: bad pattern: ...`) because the calls simulating "ordinary typing" were
using `ghostty_surface_text`, not the keyboard codepath.

Fixed by adding `GhosttySurface.sendCharKey(codepoint, mods)` /
`sendTypedText(String)`, which go through `ghostty_surface_key` with the
resolved character in the event's `text` field (`keycode` set to
`GHOSTTY_KEY_UNIDENTIFIED`) -- the same codepath a real AppKit `keyDown`
with a resolved character uses per `Surface.zig`'s `encodeKey`. This is
what a production implementation should use for ordinary typed input;
`ghostty_surface_text`/`sendText` should be reserved for an actual paste
operation (Cmd+V from the system pasteboard). Note `Gate0cSpike`'s
production-shaped `onKeyEvent` still calls `surface.sendText(characters)`
for plain typed characters, carried over unchanged from Task 5 -- this is a
known follow-up, not fixed in this task (Gate 0C's own automated check
never enables bracketed paste in the time it runs, so it did not surface
this there; flagging it here for whoever builds the real terminal
integration).

### Final automated result (after fixes)

`./gradlew gate0dSpike`, one full run: **12/12 checks passed**, 0 failed, 0
skipped (vim was present in this environment so its check ran rather than
being skipped). Representative evidence from the log:

```text
[gate0d] [PASS] shell prompt rendering -- terminal viewport has non-blank content after zsh startup
[gate0d] [PASS] ordinary typing + Return -- a terminal row is exactly "GATE0D_MARK1" ...
[gate0d] [PASS] Backspace -- corrected command's output row present, erased word absent anywhere on screen
[gate0d] [PASS] arrow keys (Left x2 before insert) -- output row is "GATE0D_MARK3_AB" ...
[gate0d] [PASS] coloured (SGR) output -- text survives escape parsing -- printf's own output row is exactly the marker ...
[gate0d] [PASS] Unicode (accented char + snowman + emoji) -- echo's own output row exactly matches ... (accent=true snowman=true emoji=true)
[gate0d] [PASS] resizing propagates to the shell ($COLUMNS) -- COLUMNS before=112 after=187 ...
[gate0d] [PASS] vim / alternate-screen TUI launches -- screen content while vim was running looked like vim's alternate-screen UI ...
[gate0d] [PASS] shell usable again after quitting vim -- shell echoed and ran a marker command normally after :q!
[gate0d] [PASS] Ctrl+C interrupts a foreground command -- shell actually ran a new command ~2s after Ctrl+C ...
[gate0d] [PASS] process alive before Ctrl+D -- ghostty_surface_process_exited() is false while zsh is still running
[gate0d] [PASS] Ctrl+D exits the shell (process exit) -- ghostty_surface_process_exited() is true after sending Ctrl+D on an empty prompt line
[gate0d] RESULTS: pass=12 fail=0 skip=0
[gate0d] shutdown complete, no crash
```

The `vim` session's screen content, captured via `read_text` (not a
screenshot -- see the caveats above about what this does and doesn't
prove), included the genuine startup banner text, which is strong evidence
a real `vim` process actually ran in the alternate screen:

```text
~                    VIM - Vi IMproved
~                    version 9.1.1752
~                by Bram Moolenaar et al.
```

### Running it

```bash
./gradlew gate0dSpike                              # scripted, auto-exits, safe for CI/agents
./gradlew gate0dSpike -Papp.cpm.gate0d.interactive # leaves the window (and a live shell) open for a human
```

### What Task 6 did not attempt

Per docs/manual-terminal-checklist.md: Claude Code itself (out of scope --
that is plan section 7's separate Gate 0E), real selection/clipboard
(needs the real macOS pasteboard, and clipboard callbacks are still
no-ops -- a Gate 0C-era gap, not new), Cmd+C/Cmd+V/Option+arrow through the
real AppKit responder chain (this spike drives ghostty's C API directly,
bypassing AppKit -- faithful for keyboard *codepaths* but not real OS-level
gestures), Home/End/Page Up/Page Down (native keycodes verified but not
exercised -- mechanically identical to the already-proven arrow-key check,
just not done here), and anything requiring real hardware/OS state changes
(sleep/wake, external display disconnect, actually changing Retina scale).
