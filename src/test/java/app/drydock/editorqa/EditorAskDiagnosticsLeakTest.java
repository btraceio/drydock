package app.drydock.editorqa;

import app.drydock.editorqa.EditorAskPorts.LaunchOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskRecord.TextRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INV-13/AC-11: synthetic secrets and code never reach plugin diagnostics, notices or the store. */
class EditorAskDiagnosticsLeakTest {

    private static final String CODE = "LEAKCODE_selected_9f3a";
    private static final String QUESTION = "LEAKQUESTION_why_77b1";
    private static final String CREDENTIAL = "LEAKTOKEN_c0ffee";
    private static final String ENDPOINT = "http://127.0.0.1:59999/LEAKENDPOINT";

    @TempDir
    Path tmp;

    private final List<String> logged = new CopyOnWriteArrayList<>();

    private Handler capture() {
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                StringBuilder sb = new StringBuilder(String.valueOf(r.getMessage()));
                if (r.getParameters() != null) {
                    for (Object p : r.getParameters()) {
                        sb.append(' ').append(p);
                    }
                }
                for (Throwable t = r.getThrown(); t != null; t = t.getCause()) {
                    sb.append(' ').append(t);
                }
                logged.add(sb.toString());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger("").addHandler(h);
        return h;
    }

    @Test
    void syntheticSecretsStayOutOfLogsNoticesAndStore() throws Exception {
        EditorAskCoordinatorTest f = new EditorAskCoordinatorTest();
        f.tmp = tmp;
        List<String> visible = new ArrayList<>();
        Handler h = capture();
        try {
            AtomicInteger injected = new AtomicInteger();
            f.launcher.onLaunch = r -> {
                injected.incrementAndGet();
                throw new IllegalStateException("launch exploded token=" + CREDENTIAL + " at " + ENDPOINT,
                        new RuntimeException("cause " + CREDENTIAL + " " + ENDPOINT));
            };
            f.repo.failSave.set(false);
            f.start(null);
            Path root = f.checkout("repo");
            Result first = f.coordinator.submit(new EditorAskPorts.Submission(root.toString(),
                    EditorAskCoordinatorTest.candidate(root), "src/A.java", new TextRange(0, 4, 0, 0, 0, 4),
                    CODE, QUESTION, null)).get(10, TimeUnit.SECONDS);
            assertTrue(first.accepted());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (injected.get() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(injected.get() >= 1, "the credential-bearing failure was driven through the launcher seam");
            f.launcher.onLaunch = r -> {
                injected.incrementAndGet();
                return new LaunchResult(LaunchOutcome.FAILED, -1, "agent did not start");
            };
            f.coordinator.submit(EditorAskCoordinatorTest.ask(root, QUESTION)).get(10, TimeUnit.SECONDS);
            Thread.sleep(300);
            f.store.flushPendingSaves();
            visible.addAll(f.events.notices);
            visible.addAll(f.events.errors);
        } finally {
            Logger.getLogger("").removeHandler(h);
            f.coordinator.close();
        }
        for (String text : logged) {
            assertNoLeak("log", text);
        }
        for (String text : visible) {
            assertNoLeak("notice", text);
        }
        try (Stream<Path> files = Files.walk(tmp)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String body = readQuietly(p);
                assertFalse(body.contains(CREDENTIAL) || body.contains("LEAKENDPOINT"), "store holds no credential/endpoint: " + p);
            }
        }
    }

    private static void assertNoLeak(String where, String text) {
        for (String secret : List.of(CODE, QUESTION, CREDENTIAL, "LEAKENDPOINT")) {
            assertFalse(text.contains(secret), where + " leaked " + secret + ": " + text);
        }
    }

    private static String readQuietly(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
