package app.drydock.intellij.tasks;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the tree shows for each remote listing (GitHub pull requests, GitHub
 * issues, Jira groups) without a gather ever waiting for one.
 *
 * <p>A listing that is done answers at once. A listing still running answers
 * with the last answer shown for the same key, or with none, which the model
 * renders as a loading row. When a running listing completes with an answer
 * other than the one shown, {@code changed} runs once, the panel's rebuild
 * request. The same answer requests nothing, so a {@code gh} that fails the
 * same way on every retry cannot drive a rebuild loop.</p>
 */
final class ListingAnswers {

    /** A listing's identity: its project root, for per-root forgetting, and the source's own key. */
    private record Key(Path root, Object listing) { }

    private final ConcurrentHashMap<Key, Object> shown = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> watched = ConcurrentHashMap.newKeySet();
    private final Runnable changed;

    ListingAnswers(Runnable changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    /**
     * {@code listing}'s answer if it is done ({@code failed} if it completed
     * exceptionally), else the last answer shown for {@code key} under
     * {@code root}, empty when there is none yet. Never blocks.
     */
    <T> Optional<T> now(Path root, Object key, CompletableFuture<T> listing, T failed) {
        Key identity = new Key(root, key);
        if (listing.isDone()) {
            T answer = listing.isCompletedExceptionally() ? failed : listing.join();
            shown.put(identity, answer);
            return Optional.of(answer);
        }
        if (watched.add(listing)) {
            listing.whenComplete((answer, failure) -> {
                watched.remove(listing);
                Object completed = failure == null ? answer : failed;
                if (!completed.equals(shown.get(identity))) {
                    changed.run();
                }
            });
        }
        @SuppressWarnings("unchecked")
        T previous = (T) shown.get(identity);
        return Optional.ofNullable(previous);
    }

    /** Forgets {@code root}'s shown answers: its next running listing renders as loading. */
    void forget(Path root) {
        shown.keySet().removeIf(key -> key.root().equals(root));
    }

    /** Forgets every shown answer. */
    void forgetAll() {
        shown.clear();
    }
}
