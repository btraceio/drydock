// Minimal AppKit child-view host shim (plan section 8, "Native macOS Host
// Shim"). This file is the ONLY place in the repository allowed to touch
// AppKit directly on behalf of the terminal surface; see
// docs/native-integration.md ("Native macOS Host Shim") for the full
// rationale and the exact JavaFX-thread/AppKit-thread contract.
//
// This shim intentionally knows nothing about:
//   - libghostty (no ghostty_*.h include, no ghostty types anywhere here);
//   - sessions, repositories, or any application/domain state;
//   - keyboard shortcut semantics (it forwards raw AppKit event fields
//     verbatim -- see cpm_terminal_host_key_event_cb below -- and performs
//     no interpretation of them).
//
// All functions here MUST be called on the AppKit main thread. On this
// project's JavaFX/macOS setup, the JavaFX Application Thread *is* the
// AppKit main thread (Glass relaunches the process so that the FX
// Application Thread runs as the Cocoa main thread), so calling these from
// code already running on the JavaFX Application Thread (e.g. inside
// Platform.runLater) satisfies this requirement. Do not call these from a
// background/executor thread. See docs/native-integration.md for the
// empirical basis of this claim.
#ifndef CPM_TERMINAL_HOST_H
#define CPM_TERMINAL_HOST_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *cpm_terminal_host_t;

// Creates an AppKit child NSView and attaches it as a subview of
// `parent_nsview` (an NSView*, e.g. the JavaFX window's content view -- see
// docs/native-integration.md for how that pointer is obtained). The child
// view starts at frame (0,0,0,0) and hidden; call
// cpm_terminal_host_set_frame / cpm_terminal_host_set_visible to make it
// usable.
//
// Returns NULL on failure (e.g. parent_nsview is not a valid NSView*).
cpm_terminal_host_t cpm_terminal_host_create(void *parent_nsview);

// Updates the child view's frame, in the parent view's coordinate space
// (top-left origin, y increasing downward -- the caller is responsible for
// any AppKit bottom-left-origin flip; see docs/native-integration.md).
void cpm_terminal_host_set_frame(cpm_terminal_host_t host,
                                  double x,
                                  double y,
                                  double width,
                                  double height);

// Returns the NSView* that libghostty (or any other renderer) should treat
// as its target view. Today this is the same view created by
// cpm_terminal_host_create, but callers must go through this accessor
// rather than assuming that, so the shim is free to interpose an internal
// wrapper view later without breaking callers.
void *cpm_terminal_host_content_view(cpm_terminal_host_t host);

// Shows or hides the child view.
void cpm_terminal_host_set_visible(cpm_terminal_host_t host, bool visible);

// Makes the child view (visible == true) or its window's original first
// responder (visible == false is not a thing here -- see .m) the first
// responder, i.e. transfers keyboard focus to/from the terminal surface.
void cpm_terminal_host_set_focused(cpm_terminal_host_t host, bool focused);

// Destroys the child view: removes it from its superview and releases the
// shim's resources. `host` must not be used again after this call.
void cpm_terminal_host_destroy(cpm_terminal_host_t host);

// --- Deliberate, documented extension beyond the plan's literal section 8
// API list -----------------------------------------------------------------
//
// The plan's suggested API (section 8) has no mechanism for the host view
// to report keyboard input, because Java code cannot subclass NSView to
// override -keyDown:/-flagsChanged:/-keyUp: itself. Something has to bridge
// AppKit's responder-chain key events to Java; the plan's own architecture
// diagram (section 4) places that translation in the "Ghostty adapter"
// layer, not in this shim, so this callback deliberately does zero
// interpretation of the event -- it forwards raw NSEvent-derived scalars
// verbatim (key code, modifier flags, key-down/up, and the resolved
// `characters` string for text input) to a single Java-registered callback.
// Ghostty-specific translation (mapping macOS virtual key codes to
// ghostty_input_key_e, deciding when to call ghostty_surface_text vs.
// ghostty_surface_key, etc.) happens entirely on the Java side in
// app.cpm.terminal.ghostty, not here. See docs/native-integration.md,
// "Why a key-event callback was added to the host shim".
//
// `unshifted_characters` is NSEvent's `charactersIgnoringModifiers` (the
// base character the key would produce with no modifiers held, e.g. "c" for
// a Ctrl+C press whose `characters` is the ETX 0x03 control byte). Ghostty's
// Kitty-keyboard-protocol key encoder (src/input/key_encode.zig's `kitty()`)
// needs this to identify non-functional keys (plain ASCII letters are not in
// its predefined key table) -- without it, a Ctrl+<letter> shortcut is
// silently dropped (not written to the pty at all) whenever the foreground
// program has negotiated Kitty keyboard protocol, even though the legacy
// (non-Kitty) encoding path used by e.g. a plain shell works fine without it.
// See docs/claude-integration.md, "Incompatibility: Ctrl+C did not cancel an
// in-progress response".
typedef void (*cpm_terminal_host_key_event_cb)(void *userdata,
                                                uint16_t key_code,
                                                uint32_t modifier_flags,
                                                int is_key_down,
                                                const char *characters,
                                                size_t characters_len,
                                                const char *unshifted_characters,
                                                size_t unshifted_characters_len);

// Registers (or clears, if callback is NULL) the key-event callback for
// this host. Only one callback may be registered at a time.
void cpm_terminal_host_set_key_event_callback(cpm_terminal_host_t host,
                                               cpm_terminal_host_key_event_cb callback,
                                               void *userdata);

// Raw scrollWheel forwarding (terminal scrollback / TUI mouse scroll).
// `delta_x`/`delta_y` are NSEvent's scrollingDeltaX/Y, pre-multiplied by 2
// for high-precision (trackpad/Magic Mouse) events -- the same subjective
// speed factor Ghostty's own macOS SurfaceView applies. `scroll_mods` is a
// ready-packed ghostty_input_scroll_mods_t (src/input/mouse.zig ScrollMods,
// packed u8): bit 0 = precision, bits 1-3 = momentum phase enum
// (none/began/stationary/changed/ended/cancelled/may_begin), packed here so
// the Java side can hand it to ghostty_surface_mouse_scroll verbatim.
typedef void (*cpm_terminal_host_scroll_event_cb)(void *userdata,
                                                   double delta_x,
                                                   double delta_y,
                                                   uint8_t scroll_mods);

// Registers (or clears, if callback is NULL) the scroll-event callback for
// this host. Only one callback may be registered at a time.
void cpm_terminal_host_set_scroll_event_callback(cpm_terminal_host_t host,
                                                  cpm_terminal_host_scroll_event_cb callback,
                                                  void *userdata);

#ifdef __cplusplus
}
#endif

#endif // CPM_TERMINAL_HOST_H
