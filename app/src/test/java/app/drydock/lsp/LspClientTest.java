package app.drydock.lsp;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec section 9 client coverage, over injected streams only — no jdt.ls
 * anywhere: byte-exact framing goldens (a multibyte body whose
 * {@code Content-Length} must count bytes), chunk-split reading,
 * {@code \r\n\r\n} handling, a zero-length keepalive frame, piped fake-server
 * round-trips for id correlation, notification dispatch and the mandatory
 * server-request answers (an unanswered server request is the hang these
 * tests exist to prevent), error/EOF/timeout paths — with the 10 s request
 * timeout shortened so no test sleeps it.
 */
@Timeout(value = 20)
class LspClientTest {

    // ------------------------------------------------------------- fixtures

    private static JsonString str(String value) {
        return new JsonString(value);
    }

    private static JsonNumber num(long value) {
        return JsonNumber.of(value);
    }

    private static JsonBoolean bool(boolean value) {
        return new JsonBoolean(value);
    }

    /** Insertion-ordered object from alternating key/value pairs. */
    private static JsonObject obj(Object... members) {
        LinkedHashMap<String, JsonValue> map = new LinkedHashMap<>();
        for (int i = 0; i < members.length; i += 2) {
            map.put((String) members[i], (JsonValue) members[i + 1]);
        }
        return new JsonObject(map);
    }

    /** The settings block T3 will inject: settings.java.import.gradle.enabled. */
    private static JsonObject settings() {
        return obj("java", obj("import", obj("gradle", obj("enabled", bool(true)))));
    }

    private static byte[] frame(String compactJson) {
        byte[] body = compactJson.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] raw(String frameBytes) {
        return frameBytes.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------- stream stand-ins

    /** Blocks until closed, then reports EOF — a server that says nothing. */
    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() {
            awaitClosed();
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            awaitClosed();
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private void awaitClosed() {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Serves at most {@code chunkSize} bytes per read, splitting every body. */
    private static final class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final int chunkSize;
        private int position;

        ChunkedInputStream(byte[] data, int chunkSize) {
            this.data = data;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int read = Math.min(Math.min(length, chunkSize), data.length - position);
            System.arraycopy(data, position, buffer, offset, read);
            position += read;
            return read;
        }
    }

    /** Records every byte written so a snapshot can prove write/completion order. */
    private static final class RecordingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ByteArrayOutputStream recorded = new ByteArrayOutputStream();

        RecordingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            recorded.write(b);
            delegate.write(b);
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) throws IOException {
            recorded.write(buffer, offset, length);
            delegate.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        synchronized byte[] snapshot() {
            return recorded.toByteArray();
        }
    }

    private record Notification(String method, JsonValue params) {
    }

    private static final class RecordingListener implements LspClient.Listener {
        final List<Notification> received = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch;

        RecordingListener(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override
        public void onNotification(String method, JsonValue params) {
            received.add(new Notification(method, params));
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "listener saw only " + received.size() + " notifications: " + received);
        }
    }

    /**
     * A fake server: the test writes frames into {@code toClient} and reads
     * the client's frames from {@code fromClient} (recorded byte-for-byte).
     */
    private static final class Server implements AutoCloseable {
        final PipedOutputStream toClient = new PipedOutputStream();
        final RecordingOutputStream clientOutput;
        final PipedInputStream fromClient = new PipedInputStream(1 << 16);
        final PipedInputStream clientInput;
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final LspClient client;

        Server(LspClient.Listener listener, long timeoutMillis) throws IOException {
            clientInput = new PipedInputStream(toClient, 1 << 16);
            clientOutput = new RecordingOutputStream(new PipedOutputStream(fromClient));
            client = new LspClient(clientInput, clientOutput, settings(), listener,
                    scheduler, timeoutMillis);
        }

        void send(String compactJson) throws IOException {
            toClient.write(frame(compactJson));
            toClient.flush();
        }

        void sendRaw(String rawFrame) throws IOException {
            toClient.write(raw(rawFrame));
            toClient.flush();
        }

        /** Reads one full frame from the client, returning its JSON body text. */
        String readFrameBody() throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = fromClient.read();
                if (b < 0) {
                    throw new EOFException("client stream ended before a complete frame header");
                }
                header.write(b);
                if (b == "\r\n\r\n".getBytes(StandardCharsets.UTF_8)[matched]) {
                    matched++;
                } else {
                    matched = (b == '\r') ? 1 : 0;
                }
            }
            int length = contentLength(header.toString(StandardCharsets.UTF_8.name()));
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = fromClient.read(body, offset, length - offset);
                if (read < 0) {
                    throw new EOFException("client stream ended mid-body");
                }
                offset += read;
            }
            return new String(body, StandardCharsets.UTF_8);
        }

        static int contentLength(String headerText) {
            int at = headerText.indexOf("Content-Length:");
            int cursor = headerText.indexOf(':', at) + 1;
            while (headerText.charAt(cursor) == ' ') {
                cursor++;
            }
            int end = cursor;
            while (end < headerText.length() && Character.isDigit(headerText.charAt(end))) {
                end++;
            }
            return Integer.parseInt(headerText.substring(cursor, end));
        }

        @Override
        public void close() throws IOException {
            toClient.close();
            client.close();
            scheduler.shutdownNow();
        }
    }

    /** Splits recorded bytes back into frame bodies. */
    private static List<String> frameBodies(byte[] recorded) {
        List<String> bodies = new ArrayList<>();
        int position = 0;
        byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        while (position < recorded.length) {
            int at = indexOf(recorded, position, terminator);
            assertTrue(at >= 0, "recorded client output is not frame-aligned");
            String headerText = new String(recorded, position, at - position, StandardCharsets.UTF_8);
            int length = Server.contentLength(headerText);
            int bodyStart = at + terminator.length;
            bodies.add(new String(recorded, bodyStart, length, StandardCharsets.UTF_8));
            position = bodyStart + length;
        }
        return bodies;
    }

    private static int indexOf(byte[] haystack, int from, byte[] needle) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------ writer goldens

    @Test
    void goldenNotificationFrameCountsUtf8BytesNotChars() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LspClient client = new LspClient(new BlockingInputStream(), output, settings(), null)) {
            client.notification("fake/method", obj("text", str("héllo→世界")));
        }
        String body = "{\n"
                + "  \"jsonrpc\": \"2.0\",\n"
                + "  \"method\": \"fake/method\",\n"
                + "  \"params\": {\n"
                + "    \"text\": \"héllo→世界\"\n"
                + "  }\n"
                + "}\n";
        // The multibyte body pins byte counting: 4 characters contribute 2-3
        // UTF-8 bytes each, so a correct header must differ from the char count.
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        assertTrue(bodyBytes.length > body.length(),
                "golden body must be multibyte for the byte/char distinction to bite");
        String expected = "Content-Length: " + bodyBytes.length + "\r\n\r\n" + body;
        assertEquals(expected, output.toString(StandardCharsets.UTF_8.name()));
    }

    @Test
    void goldenRequestFramesUseMonotonicNumericIdsAndOmitNullParams() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LspClient client = new LspClient(new BlockingInputStream(), output, settings(), null)) {
            client.request("a", null);
            client.request("b", obj("k", bool(true)));
        }
        String firstBody = "{\n  \"jsonrpc\": \"2.0\",\n  \"id\": 1,\n  \"method\": \"a\"\n}\n";
        String secondBody = "{\n  \"jsonrpc\": \"2.0\",\n  \"id\": 2,\n  \"method\": \"b\",\n"
                + "  \"params\": {\n    \"k\": true\n  }\n}\n";
        String expected = "Content-Length: " + firstBody.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n" + firstBody
                + "Content-Length: " + secondBody.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n" + secondBody;
        assertEquals(expected, output.toString(StandardCharsets.UTF_8.name()));
    }

    // -------------------------------------------------------------- reader

    @Test
    void readerReassemblesBodiesSplitAcrossChunkedReads() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.writeBytes(frame("{\"jsonrpc\":\"2.0\",\"method\":\"language/status\","
                + "\"params\":{\"type\":\"Started\"}}"));
        raw.writeBytes(frame("{\"jsonrpc\":\"2.0\",\"method\":\"$/progress\","
                + "\"params\":{\"value\":{\"kind\":\"begin\"}}}"));
        RecordingListener listener = new RecordingListener(2);
        try (LspClient ignored = new LspClient(new ChunkedInputStream(raw.toByteArray(), 3),
                new ByteArrayOutputStream(), settings(), listener)) {
            listener.await();
        }
        assertEquals(2, listener.received.size());
        assertEquals("language/status", listener.received.get(0).method());
        JsonObject first = (JsonObject) listener.received.get(0).params();
        assertEquals("Started", ((JsonString) first.get("type")).value());
        assertEquals("$/progress", listener.received.get(1).method());
    }

    @Test
    void readerSkipsNonContentLengthHeadersBeforeTheFirstCrlfCrlf() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"language/status\","
                + "\"params\":{\"type\":\"ServiceReady\"}}";
        String rawFrame = "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8"
                + "\r\n\r\n" + body;
        RecordingListener listener = new RecordingListener(1);
        try (LspClient ignored = new LspClient(
                new ByteArrayInputStream(raw(rawFrame)), new ByteArrayOutputStream(),
                settings(), listener)) {
            listener.await();
        }
        assertEquals("language/status", listener.received.get(0).method());
        JsonObject params = (JsonObject) listener.received.get(0).params();
        assertEquals("ServiceReady", ((JsonString) params.get("type")).value());
    }

    @Test
    void zeroLengthFrameIsSkippedAsKeepalive() throws Exception {
        String raw = "Content-Length: 0\r\n\r\n"
                + new String(frame("{\"jsonrpc\":\"2.0\",\"method\":\"language/status\","
                        + "\"params\":{}}"), StandardCharsets.UTF_8);
        RecordingListener listener = new RecordingListener(1);
        try (LspClient ignored = new LspClient(
                new ByteArrayInputStream(raw(raw)), new ByteArrayOutputStream(),
                settings(), listener)) {
            listener.await();
        }
        assertEquals(1, listener.received.size(), "the keepalive must dispatch nothing");
        assertEquals("language/status", listener.received.get(0).method());
    }

    // -------------------------------------------- fake-server round-trips

    @Test
    void responsesCorrelateByIdEvenWhenTheyArriveOutOfOrder() throws Exception {
        try (Server server = new Server(null, 10_000)) {
            CompletableFuture<JsonValue> first = server.client.request("one", null);
            CompletableFuture<JsonValue> second = server.client.request("two", null);
            server.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"second\"}");
            server.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"first\"}");
            assertEquals(str("second"), second.get(5, TimeUnit.SECONDS));
            assertEquals(str("first"), first.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void serverNotificationsDispatchToTheInjectedListener() throws Exception {
        RecordingListener listener = new RecordingListener(1);
        try (Server server = new Server(listener, 10_000)) {
            server.send("{\"jsonrpc\":\"2.0\",\"method\":\"language/status\","
                    + "\"params\":{\"type\":\"ServiceReady\"}}");
            listener.await();
        }
        assertEquals("language/status", listener.received.get(0).method());
        JsonObject params = (JsonObject) listener.received.get(0).params();
        assertEquals("ServiceReady", ((JsonString) params.get("type")).value());
    }

    @Test
    void workspaceConfigurationIsAnsweredWithTheInjectedSettingsPerItem() throws Exception {
        try (Server server = new Server(null, 10_000)) {
            server.send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"workspace/configuration\","
                    + "\"params\":{\"items\":[{\"section\":\"java\"},{\"section\":\"java\"}]}}");
            JsonObject response = assertInstanceOf(JsonObject.class,
                    JsonParser.parse(server.readFrameBody()));
            assertEquals(num(7), response.get("id"));
            assertFalse(response.has("error"));
            assertEquals(JsonArray.of(List.of(settings(), settings())), response.get("result"));
        }
    }

    @Test
    void registerCapabilityAndProgressCreateAreAnsweredWithAnEmptyResult() throws Exception {
        try (Server server = new Server(null, 10_000)) {
            server.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"client/registerCapability\","
                    + "\"params\":{}}");
            server.send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"window/workDoneProgress/create\","
                    + "\"params\":{\"token\":\"t\"}}");
            JsonObject register = assertInstanceOf(JsonObject.class,
                    JsonParser.parse(server.readFrameBody()));
            JsonObject progress = assertInstanceOf(JsonObject.class,
                    JsonParser.parse(server.readFrameBody()));
            assertEquals(num(3), register.get("id"));
            assertInstanceOf(JsonNull.class, register.get("result"));
            assertFalse(register.has("error"));
            assertEquals(num(4), progress.get("id"));
            assertInstanceOf(JsonNull.class, progress.get("result"));
            assertFalse(progress.has("error"));
        }
    }

    @Test
    void unknownServerRequestsAreRejectedWithMethodNotFound() throws Exception {
        try (Server server = new Server(null, 10_000)) {
            server.send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"workspace/unknownThing\","
                    + "\"params\":{}}");
            JsonObject response = assertInstanceOf(JsonObject.class,
                    JsonParser.parse(server.readFrameBody()));
            assertEquals(num(9), response.get("id"));
            JsonObject error = (JsonObject) response.get("error");
            assertEquals(num(-32601), error.get("code"));
            assertTrue(((JsonString) error.get("message")).value().contains("workspace/unknownThing"));
        }
    }

    // ------------------------------------------------ error / EOF / timeout

    @Test
    void anErrorResponseFailsOnlyItsOwnRequest() throws Exception {
        try (Server server = new Server(null, 10_000)) {
            CompletableFuture<JsonValue> failing = server.client.request("willFail", null);
            CompletableFuture<JsonValue> passing = server.client.request("willSucceed", null);
            server.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                    + "{\"code\":-32603,\"message\":\"boom\"}}");
            server.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"ok\"}");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> failing.get(5, TimeUnit.SECONDS));
            LspClient.RpcException rpc = assertInstanceOf(LspClient.RpcException.class,
                    failure.getCause());
            assertEquals(-32603, rpc.code());
            assertEquals("boom", rpc.getMessage());
            assertEquals(str("ok"), passing.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nullAndEmptySuccessfulResultsAreSuccessfulValues() throws Exception {
        try (Server server = new Server(null, 10_000)) {
            CompletableFuture<JsonValue> nullResult = server.client.request("n", null);
            CompletableFuture<JsonValue> emptyResult = server.client.request("e", null);
            server.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
            server.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[]}");
            assertInstanceOf(JsonNull.class, nullResult.get(5, TimeUnit.SECONDS));
            assertInstanceOf(JsonArray.class, emptyResult.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void eofFailsEveryInFlightRequest() throws Exception {
        try (Server server = new Server(null, 10_000)) {
            CompletableFuture<JsonValue> inFlight = server.client.request("hangs", null);
            server.toClient.close();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> inFlight.get(5, TimeUnit.SECONDS));
            assertInstanceOf(LspClient.TransportException.class, failure.getCause());
        }
    }

    @Test
    void timeoutSendsCancelRequestBeforeTheFutureCompletes() throws Exception {
        try (Server server = new Server(null, 50)) {
            CompletableFuture<JsonValue> slow = server.client.request("slow/query", null);
            AtomicBoolean cancelSeenBeforeCompletion = new AtomicBoolean(false);
            slow.whenComplete((value, thrown) -> cancelSeenBeforeCompletion.set(
                    new String(server.clientOutput.snapshot(), StandardCharsets.UTF_8)
                            .contains("$/cancelRequest")));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> slow.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(cancelSeenBeforeCompletion.get(),
                    "$/cancelRequest must be on the wire before the future completes");
            List<String> bodies = frameBodies(server.clientOutput.snapshot());
            assertEquals(2, bodies.size(), "expected the request and its cancellation");
            JsonObject requestFrame = (JsonObject) JsonParser.parse(bodies.get(0));
            assertEquals("slow/query", ((JsonString) requestFrame.get("method")).value());
            JsonObject cancel = (JsonObject) JsonParser.parse(bodies.get(1));
            assertEquals("$/cancelRequest", ((JsonString) cancel.get("method")).value());
            assertEquals(num(1), ((JsonObject) cancel.get("params")).get("id"));
        }
    }

    @Test
    void closeIsIdempotentFailsPendingAndEndsTheVirtualReader() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LspClient client = new LspClient(input, output, settings(), null)) {
            assertTrue(client.readerThread().isVirtual(),
                    "the reader loop must be a virtual thread");
            CompletableFuture<JsonValue> inFlight = client.request("pending", null);
            client.close();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> inFlight.get(5, TimeUnit.SECONDS));
            assertInstanceOf(LspClient.TransportException.class, failure.getCause());
            client.close();
            int bytesAtClose = output.size();
            CompletableFuture<JsonValue> after = client.request("late", null);
            assertTrue(after.isCompletedExceptionally());
            assertEquals(bytesAtClose, output.size(),
                    "a closed client must not write anything more");
            client.readerThread().join(2000);
            assertFalse(client.readerThread().isAlive(),
                    "the reader thread must end on close");
        }
    }
}
