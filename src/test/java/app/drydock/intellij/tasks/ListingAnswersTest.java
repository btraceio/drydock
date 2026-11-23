package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ListingAnswers}: a gather never waits, and a completion rebuilds only on a new answer. */
class ListingAnswersTest {

    private static final Path ROOT = Path.of("/work/drydock");
    private static final Path OTHER = Path.of("/work/jafar");

    private final AtomicInteger rebuilds = new AtomicInteger();
    private final ListingAnswers answers = new ListingAnswers(rebuilds::incrementAndGet);

    @Test
    void aDoneListingAnswersAtOnce() {
        assertEquals(Optional.of("listed"),
                answers.now(ROOT, "prs", CompletableFuture.completedFuture("listed"), "failed"));
        assertEquals(0, rebuilds.get());
    }

    @Test
    void aRunningFirstListingHasNoAnswerAndItsCompletionRebuildsOnce() {
        CompletableFuture<String> running = new CompletableFuture<>();

        assertEquals(Optional.empty(), answers.now(ROOT, "prs", running, "failed"));
        // A second pass over the same running listing must not add a second rebuild.
        assertEquals(Optional.empty(), answers.now(ROOT, "prs", running, "failed"));
        running.complete("listed");

        assertEquals(1, rebuilds.get());
        assertEquals(Optional.of("listed"), answers.now(ROOT, "prs", running, "failed"));
    }

    @Test
    void aRefetchServesThePreviousAnswerAndRebuildsOnlyWhenTheAnswerChanged() {
        answers.now(ROOT, "prs", CompletableFuture.completedFuture("old"), "failed");
        CompletableFuture<String> same = new CompletableFuture<>();

        assertEquals(Optional.of("old"), answers.now(ROOT, "prs", same, "failed"));
        same.complete("old");
        assertEquals(0, rebuilds.get(), "the same answer again, e.g. gh failing the same way, requests nothing");

        CompletableFuture<String> changed = new CompletableFuture<>();
        assertEquals(Optional.of("old"), answers.now(ROOT, "prs", changed, "failed"));
        changed.complete("new");
        assertEquals(1, rebuilds.get());
    }

    @Test
    void anExceptionalListingAnswersWithTheFailedValue() {
        CompletableFuture<String> broken = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        assertEquals(Optional.of("failed"), answers.now(ROOT, "prs", broken, "failed"));

        CompletableFuture<String> later = new CompletableFuture<>();
        answers.now(ROOT, "issues", later, "failed");
        later.completeExceptionally(new IllegalStateException("boom"));
        assertEquals(1, rebuilds.get());
        assertEquals(Optional.of("failed"), answers.now(ROOT, "issues", later, "failed"));
    }

    @Test
    void forgettingARootShowsItsNextRunningListingAsLoadingAndKeepsOtherRoots() {
        answers.now(ROOT, "prs", CompletableFuture.completedFuture("mine"), "failed");
        answers.now(OTHER, "prs", CompletableFuture.completedFuture("theirs"), "failed");

        answers.forget(ROOT);

        assertEquals(Optional.empty(), answers.now(ROOT, "prs", new CompletableFuture<>(), "failed"));
        assertEquals(Optional.of("theirs"), answers.now(OTHER, "prs", new CompletableFuture<>(), "failed"));
    }
}
