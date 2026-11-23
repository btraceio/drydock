package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the bundled bridge through ProcessRunner (30 s bound) against loopback fake MCP servers. */
class RemoteBridgeProtocolTest {

    private static final Path BRIDGE = Path.of("src/main/resources/drydock/remote/drydock_mcp_bridge.py")
            .toAbsolutePath();

    @TempDir
    Path tmp;

    private final List<HttpServer> servers = new ArrayList<>();

    /** A fake server recording each POST as "token|nonce|body"; handler decides the reply. */
    private final class Fake {
        final HttpServer http;
        final List<String> seen = new CopyOnWriteArrayList<>();

        Fake(BiConsumer<HttpExchange, String> handler) throws IOException {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.createContext("/mcp", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                seen.add(ex.getRequestHeaders().getFirst("X-Drydock-Session-Token") + "|"
                        + ex.getRequestHeaders().getFirst("X-Drydock-Launch-Nonce") + "|" + body);
                handler.accept(ex, body);
            });
            http.setExecutor(Executors.newCachedThreadPool());
            http.start();
            servers.add(http);
        }

        int port() {
            return http.getAddress().getPort();
        }
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    private static void reply(HttpExchange ex, int status, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, status == 204 ? -1 : b.length);
            if (status != 204) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Echo: result {"echo": method} with the request id, 204 for notifications. */
    private static void echo(HttpExchange ex, String body) {
        if (!body.contains("\"id\"")) {
            reply(ex, 204, "");
            return;
        }
        String id = body.replaceAll(".*\"id\":(\"[^\"]*\"|[0-9]+).*", "$1");
        reply(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"ok\":true}}");
    }

    private static String manifestJson(int port, String token, int generation) {
        return "{\"version\":1,\"installationId\":\"inst\",\"managedSessionId\":\"sess\","
                + "\"agentSessionId\":\"agent\",\"launchNonce\":\"nonce-1\",\"epoch\":\"e1\","
                + "\"generation\":" + generation + ",\"bridgeProtocol\":1,\"port\":" + port
                + ",\"token\":\"" + token + "\"}";
    }

    private Path ns() throws IOException {
        Path ns = Files.createDirectory(tmp.resolve("ns" + System.nanoTime()),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return ns;
    }

    /** Atomic publication like the helper: private temp file, then rename over connection.json. */
    private static Path publish(Path ns, String json, String mode) throws IOException {
        Path tmpFile = Files.createTempFile(ns, ".pending", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)));
        Files.writeString(tmpFile, json);
        return Files.move(tmpFile, ns.resolve("connection.json"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** A running bridge fed through a FIFO; stdout goes to a file so the test can await each reply. */
    private final class Session implements AutoCloseable {
        final Path out = tmp.resolve("out-" + System.nanoTime());
        final RandomAccessFile in;
        final CompletableFuture<ProcessResult> done;

        Session(Path manifest, String... extra) throws Exception {
            Path fifo = tmp.resolve("fifo-" + System.nanoTime());
            assertEquals(0, ProcessRunner.run(List.of("mkfifo", fifo.toString()),
                    new ProcessRunner.Options(null, Duration.ofSeconds(10), true, Map.of())).exitCode());
            List<String> cmd = new ArrayList<>(List.of("/bin/sh", "-c",
                    "exec python3 \"$0\" \"$1\" $4 < \"$2\" > \"$3\"", BRIDGE.toString(), manifest.toString(),
                    fifo.toString(), out.toString(), String.join(" ", extra)));
            done = CompletableFuture.supplyAsync(() -> {
                try {
                    return ProcessRunner.run(cmd, new ProcessRunner.Options(null, Duration.ofSeconds(30), true,
                            Map.of()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            in = new RandomAccessFile(fifo.toFile(), "rw");
        }

        List<String> send(String line, int totalLines) throws Exception {
            in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                List<String> lines = Files.exists(out) ? Files.readAllLines(out) : List.of();
                if (lines.size() >= totalLines) {
                    return lines;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("no reply for line: " + line);
        }

        ProcessResult finish() throws Exception {
            in.close();
            return done.get();
        }

        @Override
        public void close() throws Exception {
            if (!done.isDone()) {
                in.close();
            }
        }
    }

    private static String req(int id, String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}";
    }

    @Test
    void statusAndTransportFailuresAreNeverRetried() throws Exception {
        Fake f = new Fake((ex, body) -> {
            if (body.contains("unauth")) {
                reply(ex, 401, "{}");
            } else if (body.contains("eof")) {
                ex.close();
            } else if (body.contains("slow")) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                echo(ex, body);
            } else {
                echo(ex, body);
            }
        });
        Path manifest = publish(ns(), manifestJson(f.port(), "tok-S", 1), "rw-------");
        try (Session s = new Session(manifest, "--timeouts", "2", "1")) {
            s.send(req(1, "initialize"), 1);
            assertTrue(s.send("{\"jsonrpc\":\"2.0\",\"method\":\"note\"}\n" + req(2, "unauth"), 2).get(1)
                    .contains("\"id\":2") && Files.readString(s.out).contains("-32001"));
            assertTrue(s.send(req(3, "eof"), 3).get(2).contains("-32002"));
            assertTrue(s.send(req(4, "slow"), 4).get(3).contains("-32002"));
            assertTrue(s.send(req(5, "tools/list"), 5).get(4).contains("result"));
            ProcessResult r = s.finish();
            assertFalse(Files.readString(s.out).contains("tok-S"));
            assertFalse(r.stderr().contains("tok-S"));
            for (String m : new String[] {"unauth", "eof", "slow"}) {
                assertEquals(1, f.seen.stream().filter(x -> x.contains(m)).count(), m + " replayed");
            }
            assertEquals(1, f.seen.stream().filter(x -> x.contains("\"note\"") || x.contains("\"method\":\"note\""))
                    .count());
        }
    }

    @Test
    void unsafeOrStaleManifestsAreRejectedBeforeAnyRequest() throws Exception {
        Fake f = new Fake(RemoteBridgeProtocolTest::echo);
        Path ns = ns();
        Path manifest = publish(ns, manifestJson(f.port(), "tok-1", 5), "rw-r-----");
        try (Session s = new Session(manifest)) {
            assertTrue(s.send(req(1, "initialize"), 1).get(0).contains("-32001"), "group-readable");
            publish(ns, manifestJson(f.port(), "tok-1", 5), "rw-------");
            assertTrue(s.send(req(2, "initialize"), 2).get(1).contains("result"));
            publish(ns, manifestJson(f.port(), "tok-1", 4), "rw-------");
            assertTrue(s.send(req(3, "tools/list"), 3).get(2).contains("-32001"), "generation rollback");
            publish(ns, "{" + "\"x\":\"" + "y".repeat(70_000) + "\"}", "rw-------");
            assertTrue(s.send(req(4, "tools/list"), 4).get(3).contains("-32001"), "oversize manifest");
            publish(ns, manifestJson(f.port(), "tok-1", 6).replace("nonce-1", "other"), "rw-------");
            assertTrue(s.send(req(5, "tools/list"), 5).get(4).contains("-32001"), "launch identity changed");
            Files.delete(ns.resolve("connection.json"));
            assertTrue(s.send(req(6, "tools/list"), 6).get(5).contains("-32001"), "missing manifest");
            s.finish();
            assertEquals(1, f.seen.size(), "only the valid request reached the server");
        }
    }

    @Test
    void refreshedPortAndTokenAreUsedWithoutRestartAndInternalInitIsNotLeaked() throws Exception {
        Fake a = new Fake(RemoteBridgeProtocolTest::echo);
        Fake b = new Fake(RemoteBridgeProtocolTest::echo);
        Path ns = ns();
        Path manifest = publish(ns, manifestJson(a.port(), "tok-A", 1), "rw-------");
        try (Session s = new Session(manifest)) {
            s.send(req(1, "initialize"), 1);
            s.send(req(2, "tools/list"), 2);
            assertEquals(2, a.seen.size(), "same generation: no internal initialize");
            publish(ns, manifestJson(b.port(), "tok-B", 2), "rw-------");  // rename over the old file
            List<String> lines = s.send(req(3, "tools/call"), 3);
            s.finish();
            assertEquals(3, lines.size(), "internal initialize response must not reach the caller");
            assertFalse(Files.readString(s.out).contains("drydock-bridge-init"));
            assertTrue(lines.get(2).contains("\"id\":3") && lines.get(2).contains("result"));
            assertEquals(2, b.seen.size());
            assertTrue(b.seen.get(0).startsWith("tok-B|nonce-1|") && b.seen.get(0).contains("drydock-bridge-init-1")
                    && b.seen.get(0).contains("\"initialize\""), b.seen.get(0));
            assertTrue(b.seen.get(1).contains("tools/call"));
            assertEquals(2, a.seen.size(), "old server untouched after refresh");
        }
    }

    @Test
    void responsesFromASupersededOrVanishedManifestAreDiscardedAsOutcomeUnknown() throws Exception {
        Path ns = ns();
        AtomicReference<Runnable> onSwap =
                new AtomicReference<>(() -> { });
        Fake f = new Fake((ex, body) -> {
            if (body.contains("swap")) {
                onSwap.get().run();
            }
            echo(ex, body);
        });
        Path manifest = publish(ns, manifestJson(f.port(), "tok-1", 1), "rw-------");
        try (Session s = new Session(manifest)) {
            s.send(req(1, "initialize"), 1);
            onSwap.set(() -> {
                try {
                    publish(ns, manifestJson(f.port(), "tok-1", 2), "rw-------");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(s.send(req(2, "swap"), 2).get(1).contains("-32002"), "superseded generation");
            onSwap.set(() -> { });
            assertTrue(s.send(req(3, "tools/list"), 3).get(2).contains("result"));
            onSwap.set(() -> {
                try {
                    Files.delete(ns.resolve("connection.json"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(s.send(req(4, "swap"), 4).get(3).contains("-32002"), "manifest gone");
            s.finish();
            assertEquals(1, f.seen.stream().filter(x -> x.contains("\"id\":2,")).count(), "swap replayed");
            assertEquals(1, f.seen.stream().filter(x -> x.contains("\"id\":4,")).count(), "swap replayed");
            assertEquals(1, f.seen.stream().filter(x -> x.contains("drydock-bridge-init-1")).count());
        }
    }
}
