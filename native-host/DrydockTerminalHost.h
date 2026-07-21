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
//     verbatim -- see drydock_terminal_host_key_event_cb below -- and performs
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
#ifndef DRYDOCK_TERMINAL_HOST_H
#define DRYDOCK_TERMINAL_HOST_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *drydock_terminal_host_t;

// Creates an AppKit child NSView and attaches it as a subview of
// `parent_nsview` (an NSView*, e.g. the JavaFX window's content view -- see
// docs/native-integration.md for how that pointer is obtained). The child
// view starts at frame (0,0,0,0) and hidden; call
// drydock_terminal_host_set_frame / drydock_terminal_host_set_visible to make it
// usable.
//
// Returns NULL on failure (e.g. parent_nsview is not a valid NSView*).
drydock_terminal_host_t drydock_terminal_host_create(void *parent_nsview);

// Updates the child view's frame, in the parent view's coordinate space
// (top-left origin, y increasing downward, matching JavaFX). No AppKit
// bottom-left-origin flip is needed by the caller: the host view is a
// flipped NSView (see DrydockTerminalHostKeyForwardingView's isFlipped in the
// .m), so JavaFX-style coordinates pass through unmodified.
void drydock_terminal_host_set_frame(drydock_terminal_host_t host,
                                  double x,
                                  double y,
                                  double width,
                                  double height);

// Returns the NSView* that libghostty (or any other renderer) should treat
// as its target view. Today this is the same view created by
// drydock_terminal_host_create, but callers must go through this accessor
// rather than assuming that, so the shim is free to interpose an internal
// wrapper view later without breaking callers.
void *drydock_terminal_host_content_view(drydock_terminal_host_t host);

// Shows or hides the child view.
void drydock_terminal_host_set_visible(drydock_terminal_host_t host, bool visible);

// Transfers keyboard focus to/from the terminal surface: focused == true
// makes the child view the window's first responder; focused == false
// hands first-responder status back to the window's content view (JavaFX's
// Glass view) so JavaFX controls receive key events again -- see the .m
// for why not makeFirstResponder:nil.
void drydock_terminal_host_set_focused(drydock_terminal_host_t host, bool focused);

// Destroys the child view: removes it from its superview and releases the
// shim's resources. `host` must not be used again after this call.
void drydock_terminal_host_destroy(drydock_terminal_host_t host);

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
// app.drydock.terminal.ghostty, not here. See docs/native-integration.md,
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
typedef void (*drydock_terminal_host_key_event_cb)(void *userdata,
                                                uint16_t key_code,
                                                uint32_t modifier_flags,
                                                int is_key_down,
                                                const char *characters,
                                                size_t characters_len,
                                                const char *unshifted_characters,
                                                size_t unshifted_characters_len);

// Registers (or clears, if callback is NULL) the key-event callback for
// this host. Only one callback may be registered at a time.
void drydock_terminal_host_set_key_event_callback(drydock_terminal_host_t host,
                                               drydock_terminal_host_key_event_cb callback,
                                               void *userdata);

// Raw scrollWheel forwarding (terminal scrollback / TUI mouse scroll).
// `delta_x`/`delta_y` are NSEvent's scrollingDeltaX/Y, pre-multiplied by 2
// for high-precision (trackpad/Magic Mouse) events -- the same subjective
// speed factor Ghostty's own macOS SurfaceView applies. `scroll_mods` is a
// ready-packed ghostty_input_scroll_mods_t (src/input/mouse.zig ScrollMods,
// packed u8): bit 0 = precision, bits 1-3 = momentum phase enum
// (none/began/stationary/changed/ended/cancelled/may_begin), packed here so
// the Java side can hand it to ghostty_surface_mouse_scroll verbatim.
typedef void (*drydock_terminal_host_scroll_event_cb)(void *userdata,
                                                   double delta_x,
                                                   double delta_y,
                                                   uint8_t scroll_mods);

// Registers (or clears, if callback is NULL) the scroll-event callback for
// this host. Only one callback may be registered at a time.
void drydock_terminal_host_set_scroll_event_callback(drydock_terminal_host_t host,
                                                  drydock_terminal_host_scroll_event_cb callback,
                                                  void *userdata);

// Mouse-position forwarding. `x`/`y` are in the host view's coordinate
// space, top-left origin, in points (the same convention Ghostty's own
// macOS SurfaceView uses for ghostty_surface_mouse_pos). Fired on
// mouseMoved (via an always-active tracking area) AND immediately before
// every scroll callback -- terminal programs that enable mouse reporting
// (e.g. claude's TUI) hit-test wheel events against the reported
// position, so scrolls without a fresh position land at (0,0) and get
// dropped. `modifier_flags` is the raw NSEvent modifierFlags.
typedef void (*drydock_terminal_host_mouse_pos_event_cb)(void *userdata,
                                                      double x,
                                                      double y,
                                                      uint32_t modifier_flags);

// Registers (or clears, if callback is NULL) the mouse-position callback
// for this host. Only one callback may be registered at a time.
void drydock_terminal_host_set_mouse_pos_event_callback(drydock_terminal_host_t host,
                                                     drydock_terminal_host_mouse_pos_event_cb callback,
                                                     void *userdata);

// Mouse-button forwarding (text selection, mouse-reporting TUIs). `state`
// and `button` use ghostty's own enum values so the Java side can hand
// them to ghostty_surface_mouse_button verbatim: state 1 = press,
// 0 = release (ghostty_input_mouse_state_e); button 1 = left, 2 = right,
// 3 = middle (ghostty_input_mouse_button_e). A position callback always
// fires immediately before a button callback, so the click lands at the
// right cell. `modifier_flags` is the raw NSEvent modifierFlags.
typedef void (*drydock_terminal_host_mouse_button_event_cb)(void *userdata,
                                                         int state,
                                                         int button,
                                                         uint32_t modifier_flags);

// Registers (or clears, if callback is NULL) the mouse-button callback
// for this host. Only one callback may be registered at a time.
void drydock_terminal_host_set_mouse_button_event_callback(drydock_terminal_host_t host,
                                                        drydock_terminal_host_mouse_button_event_cb callback,
                                                        void *userdata);

#ifdef __cplusplus
}
#endif

#endif // DRYDOCK_TERMINAL_HOST_H
