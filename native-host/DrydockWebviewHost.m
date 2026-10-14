// Implementation of the minimal WKWebView embedding shim declared in
// DrydockWebviewHost.h. See that header for the contract; this file adds
// only implementation notes.
#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import "DrydockWebviewHost.h"

// Flipped container view (top-left origin, matching JavaFX's coordinate
// space, same as DrydockTerminalHostKeyForwardingView) holding the WKWebView.
// The container exists because isFlipped cannot be overridden on WKWebView
// itself without a category hack, and because it gives the destroy path a
// stable "host view" to detach, mirroring the terminal host's handle shape.
//
// Deliberately NO key/scroll/mouse forwarding and NO hitTest collapse: unlike
// libghostty, web content handles its own input through AppKit's normal
// first-responder dispatch. Whether JavaFX's Glass layer interferes with that
// dispatch is precisely one of the questions this spike must answer, so the
// shim adds no event machinery of its own.
@interface DrydockWebviewContainerView : NSView
@property(nonatomic, strong) WKWebView *webView;
@end

@implementation DrydockWebviewContainerView

- (BOOL)isFlipped {
    return YES;
}

- (void)setFrame:(NSRect)frame {
    [super setFrame:frame];
    // Keep the web view filling the container; the container's own frame is
    // driven by drydock_webview_host_set_frame.
    if (self.webView != nil) {
        self.webView.frame = self.bounds;
    }
}

@end

@interface DrydockWebviewHostDelegate
    : NSObject <WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate>
@property(nonatomic, assign) drydock_webview_message_cb messageCallback;
@property(nonatomic, assign) void *messageCallbackUserdata;
@end

@implementation DrydockWebviewHostDelegate

// JS -> Java: the page calls window.webkit.messageHandlers.drydock.postMessage(...).
- (void)userContentController:(WKUserContentController * __unused)userContentController
      didReceiveScriptMessage:(WKScriptMessage * __unused)message {
    if (self.messageCallback == NULL) {
        return;
    }
    NSString *body = nil;
    if ([message.body isKindOfClass:[NSString class]]) {
        body = (NSString *)message.body;
    } else if ([message.body isKindOfClass:[NSDictionary class]]) {
        // Arrays/dictionaries arrive as NSArray/NSDictionary; serialize so the
        // C contract stays a single UTF-8 string.
        @try {
            NSData *data = [NSJSONSerialization dataWithJSONObject:message.body
                                                           options:0
                                                             error:nil];
            if (data != nil) {
                body = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
            }
        } @catch (NSException *exception) {
            NSLog(@"drydock_webview: failed to serialize message body: %@",
                  exception.reason);
        }
    }
    if (body == nil) {
        return;
    }
    const char *utf8 = [body UTF8String];
    size_t len = utf8 != NULL ? strlen(utf8) : 0;
    // An exception escaping into WebKit's call stack is fatal to the process;
    // swallow and log, mirroring the upcall handler rule on the Java side.
    @try {
        self.messageCallback(self.messageCallbackUserdata, utf8, len);
    } @catch (NSException *exception) {
        NSLog(@"drydock_webview: message callback raised: %@", exception.reason);
    }
}

- (void)webView:(WKWebView * __unused)webView
    didFailProvisionalNavigation:(WKNavigation * __unused)navigation
                       withError:(NSError *)error {
    NSLog(@"drydock_webview: provisional navigation failed: %@",
          error.localizedDescription);
}

- (void)webView:(WKWebView * __unused)webView
        didFailNavigation:(WKNavigation * __unused)navigation
                withError:(NSError *)error {
    NSLog(@"drydock_webview: navigation failed: %@", error.localizedDescription);
}

// Block pop-ups: a review-embedded renderer has no sane target for
// window.open. Logged, not silent.
- (WKWebView *)webView:(WKWebView * __unused)webView
    createWebViewWithConfiguration:(WKWebViewConfiguration * __unused)configuration
               forNavigationAction:(WKNavigationAction * __unused)navigationAction
                    windowFeatures:(WKWindowFeatures * __unused)windowFeatures {
    NSLog(@"drydock_webview: blocked pop-up navigation to %@",
          navigationAction.request.URL.absoluteString);
    return nil;
}

@end

// Enforces the header's "all functions MUST be called on the AppKit main
// thread" contract; same pattern as DrydockTerminalHost.m.
#define DRYDOCK_ASSERT_MAIN_THREAD(fn) \
    NSCAssert([NSThread isMainThread], @fn " must be called on the AppKit main thread")

static void DrydockReassertTopmost(NSView *view) {
    // JavaFX's Glass/Prism rendering owns the parent content view and can
    // bury this overlay behind its own repaint (see the identical comment on
    // drydock_terminal_host_set_visible); re-adding an already-attached view
    // moves it back to topmost z-order.
    if (view.superview != nil) {
        [view.superview addSubview:view];
    }
}

drydock_webview_host_t drydock_webview_host_create(void *parent_nsview) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_create");
    if (parent_nsview == NULL) {
        NSLog(@"drydock_webview_host_create: parent_nsview is NULL");
        return NULL;
    }
    NSView *parent = (__bridge NSView *)parent_nsview;
    if (![parent isKindOfClass:[NSView class]]) {
        NSLog(@"drydock_webview_host_create: parent_nsview is not an NSView");
        return NULL;
    }

    DrydockWebviewHostDelegate *delegate = [[DrydockWebviewHostDelegate alloc] init];

    WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
    // JS -> Java bridge: the page posts to window.webkit.messageHandlers.drydock.
    [configuration.userContentController addScriptMessageHandler:delegate
                                                            name:@"drydock"];

    WKWebView *webView =
        [[WKWebView alloc] initWithFrame:NSZeroRect configuration:configuration];
    webView.navigationDelegate = delegate;
    webView.UIDelegate = delegate;

    DrydockWebviewContainerView *container =
        [[DrydockWebviewContainerView alloc] initWithFrame:NSZeroRect];
    container.webView = webView;
    container.hidden = YES;
    [container addSubview:webView];
    [parent addSubview:container];

    // The container carries the delegate (which owns the callbacks); retain
    // both for the handle's lifetime (released in destroy).
    return (drydock_webview_host_t)CFBridgingRetain(container);
}

void drydock_webview_host_set_frame(drydock_webview_host_t host,
                                    double x, double y,
                                    double width, double height) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_set_frame");
    if (host == NULL) {
        return;
    }
    NSView *view = (__bridge NSView *)host;
    [view setFrame:NSMakeRect(x, y, width, height)];
    if (!view.hidden) {
        DrydockReassertTopmost(view);
    }
}

void drydock_webview_host_set_visible(drydock_webview_host_t host, bool visible) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_set_visible");
    if (host == NULL) {
        return;
    }
    NSView *view = (__bridge NSView *)host;
    view.hidden = !visible;
    if (visible) {
        DrydockReassertTopmost(view);
    }
}

void drydock_webview_host_set_focused(drydock_webview_host_t host, bool focused) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_set_focused");
    if (host == NULL) {
        return;
    }
    DrydockWebviewContainerView *view = (__bridge DrydockWebviewContainerView *)host;
    NSWindow *window = view.window;
    if (window == nil) {
        return;
    }
    if (focused) {
        [window makeFirstResponder:view.webView];
    } else if ([window firstResponder] == view.webView
               || [window firstResponder] == view) {
        // Hand key routing back to JavaFX (see drydock_terminal_host_set_focused:
        // makeFirstResponder:nil dead-ends keystrokes at the window).
        [window makeFirstResponder:[window contentView]];
    }
}

void drydock_webview_host_load_url(drydock_webview_host_t host,
                                   const char *url_utf8) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_load_url");
    if (host == NULL || url_utf8 == NULL) {
        return;
    }
    DrydockWebviewContainerView *view = (__bridge DrydockWebviewContainerView *)host;
    NSString *urlString = [NSString stringWithUTF8String:url_utf8];
    NSURL *url = [NSURL URLWithString:urlString];
    if (url == nil) {
        NSLog(@"drydock_webview_host_load_url: not a valid URL: %@", urlString);
        return;
    }
    if ([url.scheme.lowercaseString isEqualToString:@"file"]) {
        // Local fixture pages: grant read access to the enclosing directory so
        // same-dir assets resolve (spike fixture is a single temp file, but
        // loadFileURL without read access blocks even the main resource on
        // some macOS versions).
        NSURL *readAccess = [url URLByDeletingLastPathComponent];
        [view.webView loadFileURL:url allowingReadAccessToURL:readAccess];
    } else {
        [view.webView loadRequest:[NSURLRequest requestWithURL:url]];
    }
}

void drydock_webview_host_post_message(drydock_webview_host_t host,
                                       const char *json_utf8) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_post_message");
    if (host == NULL || json_utf8 == NULL) {
        return;
    }
    DrydockWebviewContainerView *view = (__bridge DrydockWebviewContainerView *)host;
    NSString *json = [NSString stringWithUTF8String:json_utf8];
    // JSON is a JS expression literal except U+2028/U+2029 (valid JSON string
    // content, invalid in pre-ES2019 JS string literals); escape them.
    json = [json stringByReplacingOccurrencesOfString:@"\u2028" withString:@"\\u2028"];
    json = [json stringByReplacingOccurrencesOfString:@"\u2029" withString:@"\\u2029"];
    NSString *script =
        [NSString stringWithFormat:
            @"try { window.drydock && window.drydock.receive(%@); } "
            @"catch (e) { console.error('drydock receive failed: ' + e); }",
            json];
    [view.webView evaluateJavaScript:script
                   completionHandler:^(id __unused result, NSError *error) {
        if (error != nil) {
            NSLog(@"drydock_webview_host_post_message: evaluate failed: %@",
                  error.localizedDescription);
        }
    }];
}

void drydock_webview_host_set_message_callback(drydock_webview_host_t host,
                                               drydock_webview_message_cb callback,
                                               void *userdata) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_set_message_callback");
    if (host == NULL) {
        return;
    }
    DrydockWebviewContainerView *view = (__bridge DrydockWebviewContainerView *)host;
    DrydockWebviewHostDelegate *delegate =
        (DrydockWebviewHostDelegate *)view.webView.navigationDelegate;
    if (delegate == nil || ![delegate isKindOfClass:[DrydockWebviewHostDelegate class]]) {
        NSLog(@"drydock_webview_host_set_message_callback: delegate missing");
        return;
    }
    // Register-once: a second registration would strand the first upcall stub.
    NSCAssert(delegate.messageCallback == NULL,
              @"drydock_webview message callback already registered");
    delegate.messageCallback = callback;
    delegate.messageCallbackUserdata = userdata;
}

void drydock_webview_host_destroy(drydock_webview_host_t host) {
    DRYDOCK_ASSERT_MAIN_THREAD("drydock_webview_host_destroy");
    if (host == NULL) {
        return;
    }
    DrydockWebviewContainerView *view = (__bridge DrydockWebviewContainerView *)host;
    DrydockWebviewHostDelegate *delegate =
        (DrydockWebviewHostDelegate *)view.webView.navigationDelegate;
    // NULL the callback BEFORE removing the script message handler: a queued
    // message must find NULL, not a soon-to-be-freed upcall stub (same
    // teardown ordering as drydock_terminal_host_destroy).
    if (delegate != nil && [delegate isKindOfClass:[DrydockWebviewHostDelegate class]]) {
        delegate.messageCallback = NULL;
        delegate.messageCallbackUserdata = NULL;
        [view.webView.configuration.userContentController
            removeScriptMessageHandlerForName:@"drydock"];
    }
    [view.webView removeFromSuperview];
    [view removeFromSuperview];
    // Balances the CFBridgingRetain in create.
    CFBridgingRelease(host);
}
