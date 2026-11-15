package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker's slug index under the dual registry: a checkout both
 * registries know (legacy install id, shared id) is ONE claimant of its
 * slug, so the canonical {@code <slug>:<id>} reference is offered and a
 * cross-era reference validates; two genuinely different checkouts sharing
 * a slug still both fall back to the bare id.
 */
class TaskPrerequisitePickerIndexTest {

    private static TaskSnapshot snapshotOf(RepositoryId legacy, RepositoryId shared, TaskDocument... docs) {
        Map<TaskId, TaskDocument> byId = new java.util.LinkedHashMap<>();
        for (TaskDocument document : docs) {
            byId.put(document.id(), document);
        }
        List<RepositoryId> repositories = legacy.equals(shared) ? List.of(shared) : List.of(legacy, shared);
        return new TaskSnapshot(List.of(docs), repositories, byId, Map.of(), List.of(), List.of());
    }

    private static TaskDocument task(RepositoryId repository, String id, String... after) {
        return new TaskDocument(TaskId.of(id), repository, "Task " + id, null, null,
                java.util.Optional.empty(), List.of(after), List.of(), "", "");
    }

    @Test
    void aTwinCheckoutClaimsOneSlugSoReferencesQualify() {
        RepositoryId legacy = RepositoryId.newId();
        RepositoryId shared = RepositoryId.newId();
        TaskDocument inLegacy = task(legacy, "legacy-task-aaaa");
        TaskDocument inShared = task(shared, "shared-task-bbbb", "checkout:legacy-task-aaaa");
        TaskSnapshot snapshot = snapshotOf(legacy, shared, inLegacy, inShared);
        Function<RepositoryId, String> slugs = repository -> "checkout";
        Function<RepositoryId, RepositoryId> canonical = repository -> repository.equals(legacy) ? shared : repository;

        TaskPrerequisitePicker.Index index = TaskPrerequisitePicker.Index.of(snapshot, slugs, canonical);

        assertEquals("checkout:legacy-task-aaaa", index.canonicalReference(inLegacy),
                "the twin town contributes one slug claim, so the qualified form stays legal");
        assertEquals(List.of(), index.refusals(List.of("checkout:legacy-task-aaaa")),
                "a cross-era qualified reference validates against the twin identity");
    }

    @Test
    void twoGenuineCheckoutsSharingASlugBothFallBackToTheBareId() {
        RepositoryId one = RepositoryId.newId();
        RepositoryId two = RepositoryId.newId();
        TaskDocument inOne = task(one, "one-task-aaaa");
        TaskDocument inTwo = task(two, "two-task-bbbb");
        TaskSnapshot snapshot = new TaskSnapshot(List.of(inOne, inTwo), List.of(one, two),
                Map.of(inOne.id(), inOne, inTwo.id(), inTwo), Map.of(), List.of(), List.of());
        Function<RepositoryId, String> slugs = repository -> "checkout";

        TaskPrerequisitePicker.Index index = TaskPrerequisitePicker.Index.of(snapshot, slugs, Function.identity());

        assertEquals("one-task-aaaa", index.canonicalReference(inOne),
                "two claimants, neither may qualify");
        assertTrue(index.refusals(List.of("checkout:one-task-aaaa")).get(0).contains("ambiguous"),
                "the qualified form refuses with the ambiguity reason");
    }
}
