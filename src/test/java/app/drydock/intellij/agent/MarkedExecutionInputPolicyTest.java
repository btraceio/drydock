package app.drydock.intellij.agent;

import com.jediterm.terminal.TtyConnector;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The display-only guard on a marked terminal's connector: replies pass, human input does not. */
class MarkedExecutionInputPolicyTest {

    private final List<String> written = new ArrayList<>();
    private final List<String> otherCalls = new ArrayList<>();

    private TtyConnector recording() {
        return (TtyConnector) Proxy.newProxyInstance(TtyConnector.class.getClassLoader(),
                new Class<?>[]{TtyConnector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("write")) {
                        written.add(args[0] instanceof byte[] b ? new String(b, StandardCharsets.UTF_8)
                                : (String) args[0]);
                        return null;
                    }
                    otherCalls.add(method.getName());
                    Class<?> type = method.getReturnType();
                    return type == boolean.class ? Boolean.TRUE : type == int.class ? Integer.valueOf(0) : null;
                });
    }

    @Test
    void keystrokesPastesAndImperativeLinesAreDroppedInBothWriteForms() throws Exception {
        MarkedExecutionInputPolicy policy = new MarkedExecutionInputPolicy();
        TtyConnector guarded = policy.guard(recording());

        guarded.write("hello\r");
        guarded.write("x".getBytes(StandardCharsets.UTF_8));
        guarded.write("\u001b[200~pasted\u001b[201~\r");
        guarded.write("\u001b[A");   // an arrow key is not a protocol reply

        assertEquals(List.of(), written);
    }

    @Test
    void terminalProtocolRepliesPassThrough() throws Exception {
        MarkedExecutionInputPolicy policy = new MarkedExecutionInputPolicy();
        TtyConnector guarded = policy.guard(recording());

        guarded.write("\u001b[12;40R");
        guarded.write("\u001b[?62;c".getBytes(StandardCharsets.UTF_8));
        guarded.write("\u001b[I\u001b[O");
        guarded.write("\u001b]11;rgb:0000/0000/0000\u0007");

        assertEquals(4, written.size());
    }

    @Test
    void aReplyGluedToTypedTextIsNotAReply() {
        assertFalse(MarkedExecutionInputPolicy.isProtocolReply("\u001b[1;1Rhello"));
        assertFalse(MarkedExecutionInputPolicy.isProtocolReply(""));
        assertTrue(MarkedExecutionInputPolicy.isProtocolReply("\u001b[1;1R\u001b[I"));
    }

    @Test
    void readResizeAndLivenessGoStraightThrough() throws Exception {
        TtyConnector guarded = new MarkedExecutionInputPolicy().guard(recording());

        guarded.isConnected();
        guarded.read(new char[4], 0, 4);
        guarded.close();

        assertTrue(otherCalls.containsAll(List.of("isConnected", "read", "close")), otherCalls.toString());
    }

    @Test
    void theGuardRefusesWritesThroughEachReboundConnector() throws Exception {
        MarkedExecutionInputPolicy policy = new MarkedExecutionInputPolicy();
        TtyConnector first = policy.guard(recording());
        TtyConnector rebound = policy.guard(recording());

        first.write("a");
        rebound.write("b");

        assertEquals(List.of(), written);
    }
}
