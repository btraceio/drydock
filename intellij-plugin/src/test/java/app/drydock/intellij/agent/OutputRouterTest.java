package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving an agent's output from one console to another: the new console gets
 * the history and then the live stream, the old one gets nothing more, and a
 * move in the middle of the stream neither loses nor doubles a line.
 */
class OutputRouterTest {

    /** A console stand-in that records what it was given. */
    private static final class RecordingSink implements OutputRouter.Sink {
        private final StringBuilder text = new StringBuilder();

        @Override
        public synchronized void print(String chunk) {
            text.append(chunk);
        }

        synchronized String text() {
            return text.toString();
        }
    }

    @Test
    void aNewSinkGetsTheHistoryThenTheLiveOutput() {
        OutputRouter router = new OutputRouter(new ReplayBuffer());
        router.onText("x\n");
        RecordingSink sink = new RecordingSink();

        router.attach(sink, () -> { });
        router.onText("y\n");

        assertEquals("x\ny\n", sink.text());
    }

    @Test
    void theOldSinkGetsNothingAfterTheSwitch() {
        OutputRouter router = new OutputRouter(new ReplayBuffer());
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();

        router.attach(first, () -> { });
        router.onText("1\n");
        Optional<OutputRouter.Sink> previous = router.attach(second, () -> { });
        router.onText("2\n");

        assertEquals(Optional.of(first), previous);
        assertEquals("1\n", first.text());
        assertEquals("1\n2\n", second.text());
    }

    @Test
    void theRedrawHookRunsOnceAfterTheReplay() {
        OutputRouter router = new OutputRouter(new ReplayBuffer());
        router.onText("history\n");
        RecordingSink sink = new RecordingSink();
        AtomicInteger calls = new AtomicInteger();
        List<String> seenAtHook = new ArrayList<>();

        router.attach(sink, () -> {
            calls.incrementAndGet();
            seenAtHook.add(sink.text());
        });

        assertEquals(1, calls.get());
        assertEquals(List.of("history\n"), seenAtHook);
    }

    @Test
    void detachedOutputIsStillBuffered() {
        OutputRouter router = new OutputRouter(new ReplayBuffer());
        RecordingSink first = new RecordingSink();
        router.attach(first, () -> { });

        router.detach();
        router.onText("z\n");
        RecordingSink second = new RecordingSink();
        router.attach(second, () -> { });

        assertEquals("", first.text());
        assertTrue(second.text().endsWith("z\n"));
    }

    @Test
    void aMoveMidStreamLosesAndDoublesNothing() throws InterruptedException {
        OutputRouter router = new OutputRouter(new ReplayBuffer());
        int lines = 3000;
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= lines; i++) {
                router.onText(String.format("LINE %06d%n", i).replace(System.lineSeparator(), "\n"));
                if (i % 100 == 0) {
                    Thread.onSpinWait();
                }
            }
        });
        RecordingSink last = new RecordingSink();
        router.attach(last, () -> { });
        producer.start();
        Random random = new Random(42);
        for (int move = 0; move < 5; move++) {
            Thread.sleep(random.nextInt(3));
            last = new RecordingSink();
            router.attach(last, () -> { });
        }
        producer.join();

        String[] received = last.text().split("\n");
        assertEquals(lines, received.length);
        for (int i = 0; i < lines; i++) {
            assertEquals(String.format("LINE %06d", i + 1), received[i]);
        }
    }
}
