# Webview spike — wrap-up (2026-10-14, promotion + highlighting DONE, uncommitted)

## What this is
Feasibility spike for the web-renderer-in-review-UI idea: embed a real web
renderer in the app so the review UI can display source code in place
(replacing the "⤢ Explorer" jump into the hand-rolled JavaFX explorer).
Direction chosen with the user: **option 2 — native embedded web view**,
macOS WKWebView first (zero installation — WebKit is a macOS system
framework), Windows WebView2 later.

## State: PROMOTED + WIRED (verified; nothing committed)

### Verification evidence
- `webviewSpike` auto-exit round trip PASS ×4.
- `drawerPageSpike` PASS: the PRODUCTION code-viewer page
  (`WebCodeDrawer.renderPageDir` on a real source file) rendered in a real
  WKWebView and reported `renderer:"shiki"` — the highlighter bundle loaded
  and rendered inside WebKit; the target-line scroll reported back (`moved`).
- Interactive run: typing works (Glass does NOT steal keys from the WKWebView
  first responder — no event shim needed), ⌘-shortcuts reach the menu bar,
  focus hand-off acceptable.
- `:app:test` over all `app.drydock.ui.review.*` +
  `OpenSessionTabReviewSubTabTest`: green; `WebCodeDrawerPageTest` (5
  page-generation tests) green.

### Production pieces
- `app/drydock/webview/` (main): `WebviewHostLibrary` (NativeLibraryLocator),
  `WebviewHostBinding` (FFM), `WebviewHost` (facade; FX-thread contract,
  register-once listener, close discipline).
- `app/drydock/ui/review/WebCodeDrawer`: the review tab's code viewer —
  native overlay over an anchor Region (TerminalBridge's layout-marker
  pattern); visibility = open && workspaceVisible && subTabActive; renders
  file → temp page dir (page + bundle; numbered lines, target highlight +
  scroll, line-click bridge); 20k-line cap with a visible notice.
- Syntax highlighting (Shiki, see the section below).
- Wiring: `ReviewDiffColumn` ⤢ button → `CodeViewBridge` (renamed seam,
  same 3-arg signature so the 10 lambda-construction tests were untouched) →
  `SessionReviewView.openInWebViewer` → drawer. Peek promotion fallback also
  rerouted to the viewer. `SessionReviewView.Host`: `openInExplorer` removed,
  `reviewRoot()` added; `ReviewHost` answers from its captured root.
- Visibility sync: `OpenSessionTab.showSubTab` (one sync point before the
  early return) + `OpenSessionTab.setVisible`; disposal via
  `SessionReviewView.close()` → `disposeWebDrawer()`.
- Packaging: dylib added to `prebuiltNativeDylibs` (root build.gradle.kts),
  jbang jar include list (app/build.gradle.kts:265), and the jlink runtime
  image copy list (buildSrc RuntimeImageTask.kt).
- `JavaFxNativeView` widened to public (convention-restricted to the two
  native-boundary facades; javadoc constraint updated, not deleted).
- Spike source set slimmed to `WebviewSpike` + launcher only (smoke harness
  over the production facade); the spike's own binding/facade deleted.
- Test seam: `SessionReviewView.webCodeDrawerOpener` package-private
  BiFunction — the real drawer is a native overlay that cannot exist under
  TestFX; tests swap it to observe the open request (class stayed `final`).

### Native shim (unchanged from spike)
`native-host/DrydockWebviewHost.{h,m}` + `scripts/build-webview-host.sh` +
`buildWebviewHost` task. clang, `-framework WebKit`, min macOS 12, both
arches, same output layout as build-native-host.sh.

## Settled findings
- The terminal-host embedding pattern ports to WKWebView almost mechanically.
- No key/scroll/mouse event machinery needed — web content handles its own
  input through normal AppKit dispatch; Glass does not interfere (interactive
  run, 2026-10-14).
- RSS delta in-JVM ≈ 23–26 MB; WebKit content runs in separate processes NOT
  visible to `ps -p <pid>` — measure the process tree for total footprint.

## Refuted dead ends
- `javafx.web`: aging single-process WebKit, jlink bloat, unproven.
- Monaco workbench rebuild (option 4): editor, not IDE; TS rewrite of
  Drydock overlays + npm toolchain — largest cost, smallest payoff.
- github.dev external browser: kept as a near-free fallback (the "GitHub"
  button), not the embedded solution.

## Next steps (not started)
1. Commit (user's call — nothing committed yet; logical chunks: shim+build,
   main webview package + packaging, highlighter fetch/bundle, review wiring,
   spike slim + tests).
2. Windows WebView2 leg (user deferred): COM shim with the SAME 8 exported
   symbols (PostWebMessageAsJson/WebMessageReceived replaces the script-
   message-handler bridge), `NativeLibraryLocator` windows arch dirs, loader
   STATICALLY linked (WebView2LoaderStatic.lib from the pinned
   Microsoft.Web.WebView2 NuGet — fetched in CI, checksum-verified),
   evergreen runtime NEVER bundled (preinstalled; detect via
   GetAvailableCoreWebView2BrowserVersionString, distinct "not installed"
   error; Fixed Version only for air-gapped deployments).
3. Drawer theming: one dark theme hardcoded; could follow ThemeManager later.
4. Annotation/click integration: the page already posts
   `{event:"lineclick",line:N}` — future findings/annotation work can consume.

## Syntax highlighting (done)
- Shiki (VS Code TextMate grammars), theme github-dark, 24 grammars,
  bundle ≈ 2.5 MB at classpath root `/shiki.iife.js`.
- `app/highlighter/shiki-wrapper.mjs`: renders via `codeToTokens` + own
  per-line HTML — avoids the hast-util-to-html dependency subtree (aliased
  to a loud-failure stub, `hast-to-html-stub.mjs`).
- Gradle `fetchHighlighterDeps` (12 pinned npm tarballs + per-host standalone
  esbuild binary, all sha256-verified — NO node/npm toolchain needed) →
  `buildHighlighterBundle` (esbuild --format=iife) → generated resource dir
  wired into `sourceSets.main.resources`; `processResources` and `jbangJar`
  depend on it.
- Drawer page: code embedded as a JS-safe JSON literal (control chars and
  U+2028/29 escaped — a raw newline would kill the page script); the page
  probes `window.DrydockShiki` and falls back to plain rendering; the ready
  message carries `renderer:"shiki"|"plain"` (the spike's pass criterion).
- Page + bundle live in a per-page temp dir (`loadFileURL` read access is
  directory-scoped); the bundle is extracted from the classpath once per
  process.
