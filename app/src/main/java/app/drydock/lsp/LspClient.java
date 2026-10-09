package app.drydock.lsp;

import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A hand-rolled LSP/JSON-RPC client over injected byte streams (spec
 * {@code docs/superpowers/specs/2026-10-08-lsp-tier3-usage-resolution.md}
 * section 4) — deliberately not LSP4J.
 *
 * <p>Constraints that shape this class:</p>
 * <ul>
 * <li><b>Framing is byte-exact.</b> {@code Content-Length} counts UTF-8
 * <em>bytes</em>, not characters; the reader consumes exactly the advertised
 * byte count (a zero-length frame is a keepalive and carries no message) and
 * the writer serializes first, then frames with the serialized form's byte
 * length. Serialization reuses the repository's own
 * {@link JsonWriter}/{@link JsonParser} — no JSON dependency is introduced.</li>
 * <li><b>Exactly one reader loop</b>, on a virtual thread started by the
 * constructor and guaranteed to end on {@link #close()} (bounded join).</li>
 * <li><b>One terminal completion path per pending request.</b> The pending
 * map's {@code remove} is the single arbiter: a response, a timeout, a
 * close, or an EOF can terminate a request, but exactly one of them wins.</li>
 * <li><b>Every server-to-client request is answered</b> — an unanswered one
 * hangs the server. {@code workspace/configuration} returns the injected
 * settings block (one entry per requested item), {@code
 * client/registerCapability} and {@code window/workDoneProgress/create}
 * return an empty (null) result, and anything else is rejected with JSON-RPC
 * error {@code -32601}.</li>
 * <li><b>A timeout cancels before it gives up.</b> On expiry the client logs,
 * writes a {@code $/cancelRequest} notification carrying the original id, and
 * only then completes the caller's future exceptionally — never the other way
 * around, so the server never keeps working on a request the caller already
 * abandoned.</li>
 * <li><b>A null or empty successful result is a successful value</b>, not a
 * failure; a JSON-RPC error response completes only <em>that</em> request
 * exceptionally (logged) and leaves every other pending request alone.</li>
 * </ul>
 *
 * <p>The class is FX-free and safe for callers on arbitrary service
 * executors: all writes are serialized under one lock, request ids are
 * monotonic, and no caller-side method blocks on the reader. The injected
 * {@link ScheduledExecutorService} (used only for request timeouts) remains
 * owned by the caller and is never shut down by this class; the convenience
 * constructor creates and owns its own and releases it on close.</p>
 */
public final class LspClient implements AutoCloseable {

    private static final Logger LOG = System.getLogger(LspClient.class.getName());

    /** Spec section 4: per-request timeout before the tier falls back. */
    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000;

    /** Upper bound on one frame body; a larger advertised length means a corrupt stream, not a message. */
    static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    /** Upper bound on one header block; stray output without a CRLFCRLF terminator ends the transport. */
    static final int MAX_HEADER_BYTES = 64 * 1024;

    /** JSON-RPC "method not found" — the answer to every server request this client does not implement. */
    private static final int METHOD_NOT_FOUND = -32601;

    private static final byte[] HEADER_TERMINATOR = {'\r', '\n', '\r', '\n'};

    private static final long READER_JOIN_MILLIS = 2000;

    private final InputStream input;
    private final OutputStream output;
    private final JsonObject settings;
    private final Listener listener;
    private final ScheduledExecutorService timeoutScheduler;
    private final boolean ownsScheduler;
    private final long requestTimeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final Thread readerThread;

    /**
     * Production constructor: creates and owns a single-thread timeout
     * scheduler (released on {@link #close()}) with the default 10 s request
     * timeout.
     */
    public LspClient(InputStream input, OutputStream output, JsonObject settings, Listener listener) {
        this(input, output, settings, listener,
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory()),
                DEFAULT_REQUEST_TIMEOUT_MILLIS, true);
    }

    /**
     * Test/owner-friendly constructor. {@code timeoutScheduler} is used only
     * to schedule request timeouts and stays owned by the caller — this class
     * cancels the tasks it schedules but never shuts the executor down.
     */
    public LspClient(InputStream input, OutputStream output, JsonObject settings, Listener listener,
                     ScheduledExecutorService timeoutScheduler, long requestTimeoutMillis) {
        this(input, output, settings, listener, timeoutScheduler, requestTimeoutMillis, false);
    }

    private LspClient(InputStream input, OutputStream output, JsonObject settings, Listener listener,
                      ScheduledExecutorService timeoutScheduler, long requestTimeoutMillis, boolean ownsScheduler) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.listener = listener;
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.ownsScheduler = ownsScheduler;
        if (requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        }
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.readerThread = Thread.ofVirtual().name("lsp-client-reader").start(this::runReader);
    }

    /** Receives server notifications (messages without an id). */
    public interface Listener {
        void onNotification(String method, JsonValue params);
    }

    /**
     * A JSON-RPC error response: carries the server's error code so callers
     * can log it. Completes exactly one request's future.
     */
    public static final class RpcException extends RuntimeException {
        private final long code;

        RpcException(long code, String message) {
            super(message);
            this.code = code;
        }

        public long code() {
            return code;
        }
    }

    /** The transport is gone: the server's stream ended (EOF) or the client was closed. */
    public static final class TransportException extends RuntimeException {
        TransportException(String reason) {
            super(reason);
        }
    }

    /**
     * Sends a request and returns its eventual answer. The id is allocated
     * monotonically; the future completes with the response's result (a null
     * or empty result is a successful {@link JsonNull}/empty value), fails
     * with {@link RpcException} on a JSON-RPC error response, with
     * {@link TimeoutException} after {@code requestTimeoutMillis} (a
     * {@code $/cancelRequest} having been sent first), or with
     * {@link TransportException} on EOF/close. Never blocks.
     */
    public CompletableFuture<JsonValue> request(String method, JsonValue params) {
        Objects.requireNonNull(method, "method");
        Pending request = new Pending(method);
        long id = nextId.getAndIncrement();
        if (closed.get()) {
            return CompletableFuture.failedFuture(new TransportException("client is closed"));
        }
        // Registered before the frame is written, so a response racing the
        // write is never dropped; the pending map's remove() is the single
        // arbiter of this request's terminal completion.
        pending.put(id, request);
        try {
            writeFrame(requestMessage(id, method, params));
        } catch (IOException e) {
            if (pending.remove(id, request)) {
                request.future.completeExceptionally(
                        new TransportException("failed writing request '" + method + "': " + e));
            }
            return request.future;
        }
        try {
            request.timeoutTask = timeoutScheduler.schedule(
                    () -> onTimeout(id, method), requestTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (pending.remove(id, request)) {
                request.future.completeExceptionally(
                        new TransportException("timeout scheduler rejected request '" + method + "': " + e));
            }
        }
        return request.future;
    }

    /**
     * Sends a notification (no id, no answer expected). Throws
     * {@link IllegalStateException} on a closed client and {@link IOException}
     * on a write failure — the caller of a one-way message can see the
     * failure directly.
     */
    public void notification(String method, JsonValue params) throws IOException {
        Objects.requireNonNull(method, "method");
        if (closed.get()) {
            throw new IllegalStateException("client is closed");
        }
        writeFrame(notificationMessage(method, params));
    }

    /**
     * Idempotent release: fails every pending request with
     * {@link TransportException}, closes both streams (which unblocks the
     * reader), shuts the owned scheduler down, and joins the reader thread
     * for at most 2 s. Also runs (just the stream/scheduler release) when the
     * reader already terminated itself on EOF — the release must not depend
     * on who set the closed flag first. Never throws.
     *
     * <p>Constraint for the process wiring above this class: the reader ends
     * on its own once the underlying stream is at EOF, so the protocol-level
     * {@code shutdown}/{@code exit} exchange belongs <em>before</em> this
     * close — closing a live child's stdout stream can block on some stream
     * implementations until the child exits.</p>
     */
    @Override
    public void close() {
        closed.set(true);
        failAllPending(new TransportException("client closed"));
        if (!released.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(input, "input");
        closeQuietly(output, "output");
        if (ownsScheduler) {
            timeoutScheduler.shutdownNow();
        }
        joinReader();
    }

    /** The reader thread, exposed for tests to prove it is virtual and ends on close. */
    Thread readerThread() {
        return readerThread;
    }

    // ---------------------------------------------------------------- reader

    private void runReader() {
        try {
            while (!closed.get()) {
                String headerBlock = readHeaderBlock();
                if (headerBlock == null) {
                    terminate("server stream ended (EOF)", Level.INFO);
                    return;
                }
                int contentLength = parseContentLength(headerBlock);
                byte[] body = readFully(contentLength);
                if (contentLength > 0) {
                    dispatch(body);
                }
            }
        } catch (IOException | RuntimeException e) {
            terminate(closed.get() ? "client closed: " + e : "stream error: " + e,
                    closed.get() ? Level.DEBUG : Level.WARNING);
        }
    }

    /**
     * Reads single bytes until the first {@code \r\n\r\n}, returning the raw
     * header block (terminator included) or {@code null} on EOF. A partial
     * header at EOF is logged — the stream is dead either way.
     */
    private String readHeaderBlock() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < HEADER_TERMINATOR.length) {
            int b = input.read();
            if (b < 0) {
                if (buffer.size() > 0) {
                    LOG.log(Level.WARNING, "LSP stream ended mid-header: {0} byte(s) read",
                            buffer.size());
                }
                return null;
            }
            if (buffer.size() >= MAX_HEADER_BYTES) { // fatal: runReader terminates the transport
                throw new IOException("LSP header block exceeds " + MAX_HEADER_BYTES + " bytes");
            }
            buffer.write(b);
            if (b == HEADER_TERMINATOR[matched]) {
                matched++;
            } else {
                matched = (b == HEADER_TERMINATOR[0]) ? 1 : 0;
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseContentLength(String headerBlock) throws IOException {
        for (String line : headerBlock.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                String value = line.substring(colon + 1).trim();
                int length;
                try {
                    length = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IOException("malformed Content-Length header: " + line.trim());
                }
                // Checked before any allocation: an out-of-range frame is a fatal transport error
                // (runReader terminates and fails every pending request), never an OOM or a skip.
                if (length < 0 || length > MAX_CONTENT_LENGTH) {
                    throw new IOException("Content-Length " + length + " outside 0.." + MAX_CONTENT_LENGTH);
                }
                return length;
            }
        }
        throw new IOException("frame without a Content-Length header: " + headerBlock.trim());
    }

    /** Reads exactly {@code length} bytes; a body split across stream chunks is reassembled. */
    private byte[] readFully(int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) {
                throw new EOFException("stream ended after " + offset + " of " + length + " body bytes");
            }
            offset += read;
        }
        return body;
    }

    // -------------------------------------------------------------- dispatch

    private void dispatch(byte[] body) {
        JsonValue message;
        try {
            message = JsonParser.parse(new String(body, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            // Framing stays aligned (exactly the advertised bytes were
            // consumed), so a bad body is skipped, not fatal.
            LOG.log(Level.WARNING, "Unparseable LSP frame body ({0} bytes): {1}", body.length, e.getMessage());
            return;
        }
        if (!(message instanceof JsonObject object)) {
            LOG.log(Level.WARNING, "LSP frame body is not a JSON object; skipping");
            return;
        }
        JsonValue id = object.get("id");
        JsonValue method = object.get("method");
        if (id != null && method instanceof JsonString methodName) {
            answerServerRequest(id, methodName.value(), object.get("params"));
        } else if (id != null) {
            completePending(id, object);
        } else if (method instanceof JsonString methodName) {
            deliver(methodName.value(), object.get("params"));
        } else {
            LOG.log(Level.WARNING, "Unroutable LSP message (no id, method, or result); skipping");
        }
    }

    private void completePending(JsonValue idValue, JsonObject response) {
        long id;
        try {
            id = ((JsonNumber) idValue).asLong();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Response with non-numeric id {0}; dropping", idValue);
            return;
        }
        Pending request = pending.remove(id);
        if (request == null) {
            // Already terminated by timeout/close, or never ours.
            LOG.log(Level.DEBUG, "Unsolicited or late LSP response for id {0}", id);
            return;
        }
        if (request.timeoutTask != null) {
            request.timeoutTask.cancel(false);
        }
        JsonValue error = response.get("error");
        if (error instanceof JsonObject errorObject) {
            long code = errorObject.get("code") instanceof JsonNumber n ? n.asLong() : 0;
            String text = errorObject.get("message") instanceof JsonString s ? s.value() : "";
            LOG.log(Level.WARNING, "LSP request ''{0}'' (id {1}) failed with error {2}: {3}",
                    request.method, id, code, text);
            request.future.completeExceptionally(new RpcException(code, text));
        } else {
            JsonValue result = response.get("result");
            request.future.complete(result == null ? JsonNull.INSTANCE : result);
        }
    }

    private void answerServerRequest(JsonValue id, String method, JsonValue params) {
        JsonObject response;
        switch (method) {
            case "workspace/configuration" -> response = resultMessage(id, configurationResult(params));
            case "client/registerCapability", "window/workDoneProgress/create" ->
                    response = resultMessage(id, JsonNull.INSTANCE);
            default -> {
                LOG.log(Level.DEBUG, "Rejecting server request ''{0}'' with -32601", method);
                response = errorMessage(id, METHOD_NOT_FOUND, "Method not found: " + method);
            }
        }
        try {
            writeFrame(response);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed answering server request ''{0}'': {1}", method, e.toString());
        }
    }

    /**
     * One injected settings entry per requested item — an unanswered
     * {@code workspace/configuration} is the server hang this class exists
     * to prevent, and the array shape is what the protocol promises.
     */
    private JsonValue configurationResult(JsonValue params) {
        int items = 1;
        if (params instanceof JsonObject object && object.get("items") instanceof JsonArray array) {
            items = Math.max(1, array.elements().size());
        }
        List<JsonValue> values = new ArrayList<>(items);
        for (int i = 0; i < items; i++) {
            values.add(settings);
        }
        return new JsonArray(values);
    }

    private void deliver(String method, JsonValue params) {
        if (listener == null) {
            return;
        }
        try {
            listener.onNotification(method, params == null ? JsonNull.INSTANCE : params);
        } catch (RuntimeException e) {
            // A listener failure must never kill the reader loop.
            LOG.log(Level.WARNING, "LSP notification listener threw for ''{0}'': {1}", method, e.toString());
        }
    }

    // ---------------------------------------------------------------- timeout

    private void onTimeout(long id, String method) {
        Pending request = pending.remove(id);
        if (request == null) {
            return;
        }
        LOG.log(Level.WARNING,
                "LSP request ''{0}'' (id {1}) timed out after {2} ms; sending $/cancelRequest",
                method, id, requestTimeoutMillis);
        try {
            // Cancellation first, completion second: the server must learn the
            // request is dead before the caller's fallback is exposed.
            writeFrame(cancelRequestMessage(id));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed sending $/cancelRequest for id {0}: {1}", id, e.toString());
        }
        request.future.completeExceptionally(
                new TimeoutException("LSP request '" + method + "' timed out after "
                        + requestTimeoutMillis + " ms"));
    }

    private void terminate(String reason, Level level) {
        closed.compareAndSet(false, true);
        LOG.log(level, "LSP client reader ended: {0}", reason);
        failAllPending(new TransportException(reason));
    }

    private void failAllPending(TransportException failure) {
        for (Map.Entry<Long, Pending> entry : pending.entrySet()) {
            Pending request = entry.getValue();
            if (pending.remove(entry.getKey(), request)) {
                if (request.timeoutTask != null) {
                    request.timeoutTask.cancel(false);
                }
                request.future.completeExceptionally(failure);
            }
        }
    }

    private void joinReader() {
        try {
            readerThread.join(READER_JOIN_MILLIS);
            if (readerThread.isAlive()) {
                LOG.log(Level.WARNING, "LSP reader thread did not end within {0} ms of close",
                        READER_JOIN_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ wire

    /**
     * Frames as {@code Content-Length: <UTF-8 byte count>\r\n\r\n<body>} —
     * the count is the serialized body's byte length, which differs from its
     * character count for any multi-byte content. All writers (request,
     * notification, server-request answers, cancellation) go through this
     * single locked path.
     */
    private void writeFrame(JsonValue message) throws IOException {
        byte[] body = JsonWriter.write(message).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            output.write(header);
            output.write(body);
            output.flush();
        }
    }

    private static JsonObject base() {
        return new JsonObject(new LinkedHashMap<>())
                .put("jsonrpc", new JsonString("2.0"));
    }

    private static JsonObject requestMessage(long id, String method, JsonValue params) {
        JsonObject message = base()
                .put("id", JsonNumber.of(id))
                .put("method", new JsonString(method));
        return params == null ? message : message.put("params", params);
    }

    private static JsonObject notificationMessage(String method, JsonValue params) {
        JsonObject message = base().put("method", new JsonString(method));
        return params == null ? message : message.put("params", params);
    }

    private static JsonObject resultMessage(JsonValue id, JsonValue result) {
        return base().put("id", id).put("result", result);
    }

    private static JsonObject errorMessage(JsonValue id, long code, String text) {
        return base().put("id", id).put("error", new JsonObject(new LinkedHashMap<>())
                .put("code", JsonNumber.of(code))
                .put("message", new JsonString(text)));
    }

    private static JsonObject cancelRequestMessage(long id) {
        return base()
                .put("method", new JsonString("$/cancelRequest"))
                .put("params", new JsonObject(new LinkedHashMap<>())
                        .put("id", JsonNumber.of(id)));
    }

    private static void closeQuietly(Closeable stream, String what) {
        try {
            stream.close();
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Failed closing LSP client {0}", what, e);
        }
    }

    /** A pending client-issued request; owns its future and its timeout task. */
    private static final class Pending {
        final String method;
        final CompletableFuture<JsonValue> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeoutTask;

        Pending(String method) {
            this.method = method;
        }
    }
}
