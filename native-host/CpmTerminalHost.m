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

- (void)flagsChanged:(NSEvent *)event {
    // Modifier-only changes (Shift/Ctrl/Option/Command pressed or
    // released alone). Forward as a "key down" with empty characters; the
    // Java-side translator decides what (if anything) to do with it.
    [self forwardKeyEvent:event down:YES];
}

@end

cpm_terminal_host_t cpm_terminal_host_create(void *parent_nsview) {
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
    // `host` already *is* an (Objective-C retained) NSView*, so no bridging
    // is needed here. Callers must not release/free this pointer; its
    // lifetime is owned by the host and ends at cpm_terminal_host_destroy.
    return host;
}

void cpm_terminal_host_set_visible(cpm_terminal_host_t host, bool visible) {
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
        // Give focus back to the window itself; there is no single
        // "previous" responder to restore to in this minimal shim, and the
        // plan explicitly says this shim must not know about application
        // state (so it cannot remember what had focus before).
        [window makeFirstResponder:nil];
    }
}

void cpm_terminal_host_destroy(cpm_terminal_host_t host) {
    if (host == NULL) {
        return;
    }
    CpmTerminalHostKeyForwardingView *view = (__bridge CpmTerminalHostKeyForwardingView *)host;
    if (view.keyMonitor != nil) {
        [NSEvent removeMonitor:view.keyMonitor];
        view.keyMonitor = nil;
    }
    [view removeFromSuperview];
    // Balances the CFBridgingRetain in cpm_terminal_host_create.
    CFBridgingRelease(host);
}

void cpm_terminal_host_set_key_event_callback(cpm_terminal_host_t host,
                                               cpm_terminal_host_key_event_cb callback,
                                               void *userdata) {
    if (host == NULL) {
        return;
    }
    CpmTerminalHostKeyForwardingView *view = (__bridge CpmTerminalHostKeyForwardingView *)host;
    view.keyCallback = callback;
    view.keyCallbackUserdata = userdata;
}
