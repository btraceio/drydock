package app.drydock.intellij.agent;

import com.jediterm.terminal.TtyConnector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Makes the terminal of a marked (editor question-and-answer) execution
 * display-only for free-form input, at the connector the registry binds to
 * the console rather than at a Swing key listener: whatever reaches the
 * connector — a keystroke, a paste, another plugin writing to the widget's
 * connector — passes this policy or is dropped.
 *
 * <p>Output, resize and the terminal's own protocol replies (cursor-position
 * and device reports, focus events, OSC/DCS answers) are preserved: a TUI
 * that asked the terminal a question must still get its answer. Anything
 * else is a human line and is dropped, never queued. The connector stays
 * interactive for ordinary rows because the registry never wraps theirs.</p>
 *
 * <p>One policy per marked session, shared by every rebinding (move,
 * release, rebuild), so the display-only guard remains in force as the
 * console is rebuilt. A trust/auth/permission prompt gets nothing pasted or
 * synthesized here.</p>
 */
final class MarkedExecutionInputPolicy {

    /** CSI replies: cursor/status reports (R, n), device attributes (c), window reports (t), focus (I, O). */
    private static final Pattern CSI_REPLY = Pattern.compile(
            "\u001b\\[(?:[?>=]?[0-9;:]*[Rcnt]|[IO])");
    /** OSC reply, terminated by BEL or ST; bounded body. */
    private static final Pattern OSC_REPLY = Pattern.compile(
            "\u001b\\][0-9]{1,4};[\\x20-\\x7e]{0,512}(?:\u0007|\u001b\\\\)");
    /** DCS reply (e.g. XTGETTCAP/DECRQSS), terminated by ST; bounded body. */
    private static final Pattern DCS_REPLY = Pattern.compile(
            "\u001bP[0-9>?!$+;a-zA-Z=]{0,512}\u001b\\\\");

    /** Whether {@code text} is purely terminal protocol replies (one or more), never free-form input. */
    static boolean isProtocolReply(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int at = 0;
        while (at < text.length()) {
            int end = replyEnd(text, at);
            if (end < 0) {
                return false;
            }
            at = end;
        }
        return true;
    }

    private static int replyEnd(String text, int from) {
        for (Pattern pattern : new Pattern[]{CSI_REPLY, OSC_REPLY, DCS_REPLY}) {
            var matcher = pattern.matcher(text).region(from, text.length());
            if (matcher.lookingAt()) {
                return matcher.end();
            }
        }
        return -1;
    }

    /** Whether a write of {@code text} may reach the process; refuses free-form input. */
    boolean admit(String text) {
        return isProtocolReply(text);
    }

    /**
     * {@code delegate} with free-form {@code write} calls dropped. Every
     * other call (read, resize, liveness, close) goes straight through.
     */
    TtyConnector guard(TtyConnector delegate) {
        Objects.requireNonNull(delegate, "delegate");
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("write") && args != null && args.length == 1) {
                String text = args[0] instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                        : args[0] instanceof String s ? s : null;
                if (text != null && !admit(text)) {
                    return null;
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (TtyConnector) Proxy.newProxyInstance(TtyConnector.class.getClassLoader(),
                new Class<?>[]{TtyConnector.class}, handler);
    }
}
