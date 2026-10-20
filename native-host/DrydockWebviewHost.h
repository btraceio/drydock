// Minimal AppKit shim embedding a WKWebView (macOS system framework, nothing
// to bundle or install) as a child view of the JavaFX window's content view --
// the web-renderer counterpart of DrydockTerminalHost.h. Spike contract for
// the web-renderer-in-review-UI feasibility spike; mirroring the terminal
// host's shape keeps the eventual production binding a mechanical port.
//
// Threading contract (identical to DrydockTerminalHost.h): every function
// MUST be called on the AppKit main thread -- which for this project's
// JavaFX setup is the JavaFX Application Thread. AppKit and WebKit are not
// thread-safe; a violation corrupts state instead of raising an error.
//
// Memory contract: create returns an Objective-C retained handle (+1,
// CFBridgingRetain) that destroy releases. All callbacks are registered at
// most once per slot; callbacks are NULLed before the Java side frees the
// corresponding upcall stubs (see destroy).
#ifndef DRYDOCK_WEBVIEW_HOST_H
#define DRYDOCK_WEBVIEW_HOST_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *drydock_webview_host_t;

// Invoked on the AppKit main thread for every message the web page posts via
// window.webkit.messageHandlers.drydock.postMessage(<string>). body_utf8 is
// owned by the shim and valid only for the duration of the call; the Java
// side must copy anything it keeps. The Java upcall stub frees its arena
// only after destroy NULLs this callback, so a queued message during teardown
// finds NULL here (no-op) rather than a freed pointer.
typedef void (*drydock_webview_message_cb)(void *userdata,
                                           const char *body_utf8,
                                           size_t body_len);

// Creates a WKWebView (inside a flipped container view, top-left origin
// matching JavaFX's coordinate space) and attaches it, hidden, as a subview
// of the given parent NSView (the JavaFX Glass content view, see
// JavaFxNativeView). Returns NULL (with an NSLog) on failure.
//
// The WKWebView is created with a WKScriptMessageHandler named "drydock"
// and a navigation/UIDelegate pair that logs navigation failures and blocks
// pop-ups (createWebViewWithConfiguration returns nil).
drydock_webview_host_t drydock_webview_host_create(void *parent_nsview);

// Sets the container view's frame in the parent content view's coordinate
// space (top-left origin; the container is flipped). Also re-asserts topmost
// z-order for the same reason drydock_terminal_host_set_frame does: JavaFX
// owns the parent and can bury the overlay on any repaint.
void drydock_webview_host_set_frame(drydock_webview_host_t host,
                                    double x, double y,
                                    double width, double height);

// Shows/hides the container; visible also re-asserts topmost z-order (same
// reasoning as set_frame).
void drydock_webview_host_set_visible(drydock_webview_host_t host, bool visible);

// Makes the WKWebView the window's first responder (focused) or hands key
// routing back to the JavaFX content view (unfocused) -- same contract as
// drydock_terminal_host_set_focused: nil would dead-end keystrokes at the
// window and break JavaFX text controls.
void drydock_webview_host_set_focused(drydock_webview_host_t host, bool focused);

// Navigates the web view to url_utf8 (absolute). A file:// URL is loaded via
// loadFileURL:allowingReadAccessToURL: with read access granted to the file's
// enclosing directory (the spike fixture lives in a temp dir); any other
// scheme goes through loadRequest. Invalid URLs are logged and ignored.
void drydock_webview_host_load_url(drydock_webview_host_t host,
                                   const char *url_utf8);

// Evaluates window.drydock && window.drydock.receive(<json_utf8>) in the
// page -- the Java->JS bridge direction. json_utf8 must be a complete JSON
// value; it is embedded as a JS expression (JSON is a JS literal except for
// U+2028/U+2029, which are escaped here). Failures are logged, never thrown
// across the FFM boundary.
void drydock_webview_host_post_message(drydock_webview_host_t host,
                                       const char *json_utf8);

// Registers the JS->Java message callback (register-once per host; a second
// registration is refused because the upcall stub leaks otherwise -- mirrors
// the terminal host's register-once contract).
void drydock_webview_host_set_message_callback(drydock_webview_host_t host,
                                               drydock_webview_message_cb callback,
                                               void *userdata);

// Detaches and releases the web view. NULLs the message callback BEFORE
// removing the script message handler so no in-flight message can reach a
// soon-to-be-freed upcall stub.
void drydock_webview_host_destroy(drydock_webview_host_t host);

#ifdef __cplusplus
}
#endif

#endif // DRYDOCK_WEBVIEW_HOST_H
