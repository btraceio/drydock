// Implementation of the minimal AppKit host shim declared in
// CpmTerminalHost.h. See that header and docs/native-integration.md for the
// contract and rationale.
#import <Cocoa/Cocoa.h>
#import "CpmTerminalHost.h"

// A small NSView subclass that does the minimum needed to be a usable host:
//   - accepts first responder / key events, and forwards them verbatim to a
//     C callback (see CpmTerminalHostKeyForwardingView.keyCallback below);
//   - is otherwise a completely plain, un-opinionated NSView. It performs no
//     drawing of its own; libghostty (or nothing at all) is responsible for
//     everything the view displays, via cpm_terminal_host_content_view().
@interface CpmTerminalHostKeyForwardingView : NSView
@property(nonatomic, assign) cpm_terminal_host_key_event_cb keyCallback;
@property(nonatomic, assign) void *keyCallbackUserdata;
@property(nonatomic, assign) cpm_terminal_host_scroll_event_cb scrollCallback;
@property(nonatomic, assign) void *scrollCallbackUserdata;
@property(nonatomic, assign) cpm_terminal_host_mouse_pos_event_cb mousePosCallback;
@property(nonatomic, assign) void *mousePosCallbackUserdata;
@property(nonatomic, assign) cpm_terminal_host_mouse_button_event_cb mouseButtonCallback;
@property(nonatomic, assign) void *mouseButtonCallbackUserdata;
@property(nonatomic, strong) NSTrackingArea *cpmTrackingArea;
/// Local NSEvent monitor installed while this view exists (see
/// cpm_terminal_host_create). JavaFX's Glass layer intercepts some key
/// events (notably arrow keys, which it treats as focus-traversal) in its
/// NSWindow/NSApplication event handling BEFORE they would reach this
/// view's keyDown: via the normal first-responder dispatch -- verified
/// empirically: with this view as firstResponder, letter keys arrive at
/// keyDown: but arrow keys never do. A local monitor runs before that
/// dispatch, so it sees every key event regardless of what Glass does
/// afterward; when this view is the intended target we forward the event
/// to libghostty ourselves and swallow it (returning nil also stops
/// JavaFX from scrolling/moving its own focus in response to terminal
/// keystrokes).
@property(nonatomic, strong) id keyMonitor;
@end

@implementation CpmTerminalHostKeyForwardingView

- (BOOL)acceptsFirstResponder {
    return YES;
}

- (BOOL)isFlipped {
    // Top-left origin, matching JavaFX's coordinate space, so
    // cpm_terminal_host_set_frame's (x, y) can be passed through unmodified
    // by callers instead of every caller having to flip against the
    // parent's height.
    return YES;
}

- (void)forwardKeyEvent:(NSEvent *)event down:(BOOL)down {
    if (self.keyCallback == NULL) {
        return;
    }
    NSString *chars = [event characters];
    const char *utf8 = chars != nil ? [chars UTF8String] : NULL;
    size_t len = utf8 != NULL ? strlen(utf8) : 0;
    // See CpmTerminalHost.h's Javadoc-style comment on
    // cpm_terminal_host_key_event_cb for why this is needed (Kitty keyboard
    // protocol key encoding).
    NSString *unshiftedChars = [event charactersIgnoringModifiers];
    const char *unshiftedUtf8 = unshiftedChars != nil ? [unshiftedChars UTF8String] : NULL;
    size_t unshiftedLen = unshiftedUtf8 != NULL ? strlen(unshiftedUtf8) : 0;
    self.keyCallback(self.keyCallbackUserdata,
                      (uint16_t)[event keyCode],
                      (uint32_t)[event modifierFlags],
                      down ? 1 : 0,
                      utf8,
                      len,
                      unshiftedUtf8,
                      unshiftedLen);
}

- (void)keyDown:(NSEvent *)event {
    [self forwardKeyEvent:event down:YES];
}

- (void)keyUp:(NSEvent *)event {
    [self forwardKeyEvent:event down:NO];
}

// Route ALL mouse-derived events (scrollWheel included) to this view even
// when the renderer (libghostty) has installed its own subview/layer tree
// inside it: AppKit dispatches scroll/click events to the deepest view
// under the cursor, which would be ghostty's internal view -- a view that
// silently ignores them -- and NOT this one. Key events never noticed
// (they follow the first responder, which IS this view); scrollWheel did.
// Returning self for any point inside collapses the whole subtree into a
// single mouse target; no child view needs AppKit mouse events (all
// terminal input goes through the ghostty C API).
- (NSView *)hitTest:(NSPoint)point {
    NSView *result = [super hitTest:point];
    return result != nil ? self : nil;
}

// mouseMoved: events require a tracking area; activeAlways so position
// reporting works even while the JavaFX window is not key.
- (void)updateTrackingAreas {
    [super updateTrackingAreas];
    if (self.cpmTrackingArea != nil) {
        [self removeTrackingArea:self.cpmTrackingArea];
    }
    self.cpmTrackingArea = [[NSTrackingArea alloc]
        initWithRect:NSZeroRect
             options:(NSTrackingMouseMoved | NSTrackingActiveAlways | NSTrackingInVisibleRect)
               owner:self
            userInfo:nil];
    [self addTrackingArea:self.cpmTrackingArea];
}

// Reports the event's position in this view's coordinate space (isFlipped
// => already top-left origin, matching Ghostty's convention).
- (void)forwardMousePos:(NSEvent *)event {
    if (self.mousePosCallback == NULL) {
        return;
    }
    NSPoint pos = [self convertPoint:event.locationInWindow fromView:nil];
    self.mousePosCallback(self.mousePosCallbackUserdata, pos.x, pos.y,
                          (uint32_t)event.modifierFlags);
}

- (void)mouseMoved:(NSEvent *)event {
    [self forwardMousePos:event];
}

// Button events (text selection, mouse-reporting TUIs). Position first so
// the press/release lands at the right cell (same reasoning as scrolls);
// deliberately no [super mouseDown:] etc. -- libghostty owns clicks inside
// the terminal, nothing behind this view should also react to them.
- (void)forwardMouseButton:(NSEvent *)event state:(int)state button:(int)button {
    [self forwardMousePos:event];
    if (self.mouseButtonCallback == NULL) {
        return;
    }
    self.mouseButtonCallback(self.mouseButtonCallbackUserdata, state, button,
                             (uint32_t)event.modifierFlags);
}

- (void)mouseDown:(NSEvent *)event {
    [self forwardMouseButton:event state:1 button:1];
}

- (void)mouseUp:(NSEvent *)event {
    [self forwardMouseButton:event state:0 button:1];
}

- (void)rightMouseDown:(NSEvent *)event {
    [self forwardMouseButton:event state:1 button:2];
}

- (void)rightMouseUp:(NSEvent *)event {
    [self forwardMouseButton:event state:0 button:2];
}

- (void)otherMouseDown:(NSEvent *)event {
    [self forwardMouseButton:event state:1 button:3];
}

- (void)otherMouseUp:(NSEvent *)event {
    [self forwardMouseButton:event state:0 button:3];
}

// Drags do not fire mouseMoved (tracking areas only cover unpressed
// movement), so selection extension needs explicit position forwarding.
- (void)mouseDragged:(NSEvent *)event {
    [self forwardMousePos:event];
}

- (void)rightMouseDragged:(NSEvent *)event {
    [self forwardMousePos:event];
}

- (void)otherMouseDragged:(NSEvent *)event {
    [self forwardMousePos:event];
}

- (void)scrollWheel:(NSEvent *)event {
    if (getenv("CPM_DIAG_SCROLL_LOG") != NULL) {
        NSLog(@"cpm scrollWheel: dy=%f precise=%d cb=%p", event.scrollingDeltaY,
              (int)event.hasPreciseScrollingDeltas, (void *)self.scrollCallback);
    }
    // Position first: mouse-reporting TUIs hit-test the wheel event against
    // the last reported position (see the header's comment).
    [self forwardMousePos:event];
    if (self.scrollCallback == NULL) {
        return;
    }
    // Mirrors Ghostty's own macOS SurfaceView.scrollWheel: raw scrolling
    // deltas, 2x'd for high-precision devices, momentum phase packed into
    // ghostty's ScrollMods bit layout (see the header's comment).
    double x = event.scrollingDeltaX;
    double y = event.scrollingDeltaY;
    bool precision = event.hasPreciseScrollingDeltas;
    if (precision) {
        x *= 2;
        y *= 2;
    }
    uint8_t momentum = 0;
    switch (event.momentumPhase) {
        case NSEventPhaseBegan: momentum = 1; break;
        case NSEventPhaseStationary: momentum = 2; break;
        case NSEventPhaseChanged: momentum = 3; break;
        case NSEventPhaseEnded: momentum = 4; break;
        case NSEventPhaseCancelled: momentum = 5; break;
        case NSEventPhaseMayBegin: momentum = 6; break;
        default: momentum = 0; break;
    }
    uint8_t mods = (uint8_t)((precision ? 1 : 0) | (momentum << 1));
    self.scrollCallback(self.scrollCallbackUserdata, x, y, mods);
}

- (void)flagsChanged:(NSEvent *)event {
    // Modifier-only changes (Shift/Ctrl/Option/Command pressed or
    // released alone). Forward as a "key down" with empty characters; the
    // Java-side translator decides what (if anything) to do with it.
    [self forwardKeyEvent:event down:YES];
}

@end

// Enforces the header's "all functions MUST be called on the AppKit main
// thread" contract (AppKit is not thread-safe); a debug-build violation
// aborts loudly here instead of corrupting AppKit state silently.
#define CPM_ASSERT_MAIN_THREAD(fn) \
    NSCAssert([NSThread isMainThread], @fn " must be called on the AppKit main thread")

cpm_terminal_host_t cpm_terminal_host_create(void *parent_nsview) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_create");
    if (parent_nsview == NULL) {
        NSLog(@"cpm_terminal_host_create: parent_nsview is NULL");
        return NULL;
    }
    NSView *parent = (__bridge NSView *)parent_nsview;
    if (![parent isKindOfClass:[NSView class]]) {
        NSLog(@"cpm_terminal_host_create: parent_nsview is not an NSView");
        return NULL;
    }

    CpmTerminalHostKeyForwardingView *view =
        [[CpmTerminalHostKeyForwardingView alloc] initWithFrame:NSZeroRect];
    view.hidden = YES;
    [parent addSubview:view];

    // See the keyMonitor property's comment for why the normal
    // first-responder keyDown: path is not sufficient here. Command-modified
    // events are deliberately left alone: they must keep flowing to the
    // menu bar / key-equivalent machinery (Cmd+Q, Cmd+W, ...) even while
    // the terminal is focused.
    __weak CpmTerminalHostKeyForwardingView *weakView = view;
    view.keyMonitor = [NSEvent
        addLocalMonitorForEventsMatchingMask:(NSEventMaskKeyDown | NSEventMaskKeyUp)
                                     handler:^NSEvent *(NSEvent *event) {
        CpmTerminalHostKeyForwardingView *strongView = weakView;
        if (strongView == nil || strongView.window == nil) {
            return event;
        }
        if (event.window != strongView.window ||
            strongView.window.firstResponder != strongView) {
            return event;
        }
        if ((event.modifierFlags & NSEventModifierFlagCommand) != 0) {
            return event;
        }
        [strongView forwardKeyEvent:event down:(event.type == NSEventTypeKeyDown)];
        return nil;
    }];

    // Retain the view for the lifetime of the opaque handle; released in
    // cpm_terminal_host_destroy. CFBridgingRetain gives us a +1 retain that
    // balances the CFBridgingRelease in destroy.
    return (cpm_terminal_host_t)CFBridgingRetain(view);
}

void cpm_terminal_host_set_frame(cpm_terminal_host_t host,
                                  double x,
                                  double y,
                                  double width,
                                  double height) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_set_frame");
    if (host == NULL) {
        return;
    }
    NSView *view = (__bridge NSView *)host;
    [view setFrame:NSMakeRect(x, y, width, height)];
    if (!view.hidden && view.superview != nil) {
        // See cpm_terminal_host_set_visible's identical comment: re-assert
        // topmost z-order on every geometry update too (called far more
        // often than set_visible -- every resize/tab-switch/layout pass),
        // since that is where JavaFX's own re-rendering is most likely to
        // have buried this overlay view again since the last time it drew.
        [view.superview addSubview:view];
    }
}

void *cpm_terminal_host_content_view(cpm_terminal_host_t host) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_content_view");
    // `host` already *is* an (Objective-C retained) NSView*, so no bridging
    // is needed here. Callers must not release/free this pointer; its
    // lifetime is owned by the host and ends at cpm_terminal_host_destroy.
    return host;
}

void cpm_terminal_host_set_visible(cpm_terminal_host_t host, bool visible) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_set_visible");
    if (host == NULL) {
        return;
    }
    NSView *view = (__bridge NSView *)host;
    view.hidden = !visible;
    if (visible && view.superview != nil) {
        // JavaFX's own Glass/Prism rendering owns the parent content view
        // and can re-render its full backing layer on its own animation
        // pulse (scene changes elsewhere in the window -- e.g. the sidebar,
        // a TabPane selection, anything animating). Re-adding this
        // already-attached view to the same superview moves it back to the
        // end of the subviews array (topmost z-order in AppKit) without
        // otherwise changing anything, guarding against JavaFX burying this
        // overlay behind a subsequent repaint of its own view.
        [view.superview addSubview:view];
    }
}

void cpm_terminal_host_set_focused(cpm_terminal_host_t host, bool focused) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_set_focused");
    if (host == NULL) {
        return;
    }
    NSView *view = (__bridge NSView *)host;
    NSWindow *window = [view window];
    if (window == nil) {
        return;
    }
    if (focused) {
        [window makeFirstResponder:view];
    } else if ([window firstResponder] == view) {
        // Hand key routing back to JavaFX: its Glass NSView is the window's
        // contentView, and it must be the first responder for JavaFX
        // controls (e.g. the inline tab-rename TextField) to receive key
        // events again. makeFirstResponder:nil would leave the WINDOW as
        // responder, where keystrokes dead-end -- observed as "cannot type
        // into the rename field while a terminal session is open".
        [window makeFirstResponder:[window contentView]];
    }
}

void cpm_terminal_host_destroy(cpm_terminal_host_t host) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_destroy");
    if (host == NULL) {
        return;
    }
    CpmTerminalHostKeyForwardingView *view = (__bridge CpmTerminalHostKeyForwardingView *)host;
    if (view.keyMonitor != nil) {
        [NSEvent removeMonitor:view.keyMonitor];
        view.keyMonitor = nil;
    }
    // NULL every callback and drop the tracking area BEFORE detaching the
    // view: the Java side frees the upcall stubs right after this returns,
    // and a straggler AppKit event (queued mouseMoved, tracking-area
    // update, ...) delivered during teardown must find NULL callbacks, not
    // soon-to-be-freed function pointers.
    view.keyCallback = NULL;
    view.keyCallbackUserdata = NULL;
    view.scrollCallback = NULL;
    view.scrollCallbackUserdata = NULL;
    view.mousePosCallback = NULL;
    view.mousePosCallbackUserdata = NULL;
    view.mouseButtonCallback = NULL;
    view.mouseButtonCallbackUserdata = NULL;
    if (view.cpmTrackingArea != nil) {
        [view removeTrackingArea:view.cpmTrackingArea];
        view.cpmTrackingArea = nil;
    }
    [view removeFromSuperview];
    // Balances the CFBridgingRetain in cpm_terminal_host_create.
    CFBridgingRelease(host);
}

void cpm_terminal_host_set_key_event_callback(cpm_terminal_host_t host,
                                               cpm_terminal_host_key_event_cb callback,
                                               void *userdata) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_set_key_event_callback");
    if (host == NULL) {
        return;
    }
    CpmTerminalHostKeyForwardingView *view = (__bridge CpmTerminalHostKeyForwardingView *)host;
    view.keyCallback = callback;
    view.keyCallbackUserdata = userdata;
}

void cpm_terminal_host_set_scroll_event_callback(cpm_terminal_host_t host,
                                                  cpm_terminal_host_scroll_event_cb callback,
                                                  void *userdata) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_set_scroll_event_callback");
    if (host == NULL) {
        return;
    }
    CpmTerminalHostKeyForwardingView *view = (__bridge CpmTerminalHostKeyForwardingView *)host;
    view.scrollCallback = callback;
    view.scrollCallbackUserdata = userdata;
}

void cpm_terminal_host_set_mouse_pos_event_callback(cpm_terminal_host_t host,
                                                     cpm_terminal_host_mouse_pos_event_cb callback,
                                                     void *userdata) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_set_mouse_pos_event_callback");
    if (host == NULL) {
        return;
    }
    CpmTerminalHostKeyForwardingView *view = (__bridge CpmTerminalHostKeyForwardingView *)host;
    view.mousePosCallback = callback;
    view.mousePosCallbackUserdata = userdata;
}

void cpm_terminal_host_set_mouse_button_event_callback(cpm_terminal_host_t host,
                                                        cpm_terminal_host_mouse_button_event_cb callback,
                                                        void *userdata) {
    CPM_ASSERT_MAIN_THREAD("cpm_terminal_host_set_mouse_button_event_callback");
    if (host == NULL) {
        return;
    }
    CpmTerminalHostKeyForwardingView *view = (__bridge CpmTerminalHostKeyForwardingView *)host;
    view.mouseButtonCallback = callback;
    view.mouseButtonCallbackUserdata = userdata;
}
