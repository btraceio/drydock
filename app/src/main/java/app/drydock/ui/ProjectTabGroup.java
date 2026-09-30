package app.drydock.ui;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Auto-grouping for open session tabs by project (repository).
 *
 * <p>Two halves. Badge-slot allocation (instance state): every repository
 * with at least one open tab holds the lowest free palette slot, so projects
 * are visually distinct whenever at most {@value #PALETTE_SIZE} are open. A
 * slot is freed only when its repository's last tab closes; beyond
 * {@value #PALETTE_SIZE} simultaneous projects the allocation degrades to a
 * hash of the repository id (collisions then allowed) rather than throwing.
 * Insert policy (static): a new tab lands immediately after the last
 * existing tab of the same repository, so a project's tabs stay physically
 * contiguous.</p>
 */
final class ProjectTabGroup {

    /**
     * Must match the {@code .project-badge-N} classes in {@code app.css} and
     * the {@code -drydock-project-N} tokens in both theme files.
     */
    private static final int PALETTE_SIZE = 8;

    /** One repository's badge slot; {@code tabs} counts the open tabs holding it. */
    private static final class Slot {
        final int index;
        int tabs;

        Slot(int index) {
            this.index = index;
        }
    }

    private final Map<RepositoryId, Slot> slotsByRepository = new HashMap<>();

    /**
     * Returns the repository's badge style, joining the slot it already holds
     * or taking the lowest free one. Called once per tab creation, on the FX
     * thread; every call must be paired with exactly one
     * {@link #releaseBadgeStyle} on that tab's removal.
     */
    String acquireBadgeStyle(Repository repository) {
        Slot slot = slotsByRepository.get(repository.id());
        if (slot == null) {
            slot = new Slot(firstFreeIndex(repository.id()));
            slotsByRepository.put(repository.id(), slot);
        }
        slot.tabs++;
        return badgeStyleForIndex(slot.index);
    }

    /**
     * Drops one tab's claim on the repository's slot; the slot frees when the
     * repository's last tab closes. An id that was never acquired is a no-op,
     * so a stray release cannot corrupt another repository's count.
     */
    void releaseBadgeStyle(Repository repository) {
        Slot slot = slotsByRepository.get(repository.id());
        if (slot == null) {
            return;
        }
        slot.tabs--;
        if (slot.tabs <= 0) {
            slotsByRepository.remove(repository.id());
        }
    }

    private int firstFreeIndex(RepositoryId id) {
        boolean[] used = new boolean[PALETTE_SIZE];
        for (Slot slot : slotsByRepository.values()) {
            used[slot.index] = true;
        }
        for (int i = 0; i < PALETTE_SIZE; i++) {
            if (!used[i]) {
                return i;
            }
        }
        // More projects than palette slots: degrade to a stable pick instead
        // of throwing; collisions with an allocated slot are accepted.
        return Math.floorMod(id.value().hashCode(), PALETTE_SIZE);
    }

    private static String badgeStyleForIndex(int index) {
        return "project-badge-" + index;
    }

    /**
     * Where a new tab should land to keep its repository's tabs together.
     *
     * @param items       current tab list, in order
     * @param repository  the repository of the tab being inserted
     * @param resolver    returns the repository associated with an item
     * @param <T>         the type of the tab handle (usually {@link javafx.scene.control.Tab})
     * @return the index at which to insert; this is immediately after the
     *         last existing tab for the same repository, or at the end when
     *         there is no repository or no existing tab for it
     */
    static <T> int insertionIndexFor(List<T> items, Optional<Repository> repository,
                                     Function<T, Optional<Repository>> resolver) {
        Optional<RepositoryId> targetId = projectIdOf(repository);
        if (targetId.isEmpty()) {
            return items.size();
        }
        int lastSameProject = -1;
        for (int i = 0; i < items.size(); i++) {
            Optional<RepositoryId> id = projectIdOf(resolver.apply(items.get(i)));
            if (id.equals(targetId)) {
                lastSameProject = i;
            }
        }
        return lastSameProject >= 0 ? lastSameProject + 1 : items.size();
    }

    private static Optional<RepositoryId> projectIdOf(Optional<Repository> repository) {
        return repository.map(Repository::id);
    }
}