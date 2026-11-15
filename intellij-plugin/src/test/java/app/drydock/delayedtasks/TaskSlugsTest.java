package app.drydock.delayedtasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.projects.SharedProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dual-registry resolution contract: shared wins for a checkout both
 * registries know, one identity per checkout for slug purposes, install-only
 * checkouts keep their own name, and genuinely different checkouts sharing a
 * slug are both reported (callers refuse them).
 */
class TaskSlugsTest {

    private static final Instant T = Instant.EPOCH;

    private static SharedProject shared(Path root, String name) {
        return SharedProject.local(RepositoryId.newId(), root, name, T);
    }

    private static Repository install(Path root, String name) {
        return new Repository(RepositoryId.newId(), root, name, T, T, RepositorySettings.DEFAULT);
    }

    @Test
    void sharedNameWinsForATwinnedCheckout(@TempDir Path dir) {
        Path checkout = dir.resolve("checkout");
        SharedProject shared = shared(checkout, "Java Profiler");
        Repository install = install(checkout, "old-name");
        TaskSlugs slugs = TaskSlugs.of(List.of(shared), List.of(install));
        assertEquals(shared.id(), slugs.canonicalId(install.id()), "the install id resolves to the shared id");
        assertEquals(shared.id(), slugs.canonicalId(shared.id()), "the shared id is its own identity");
        assertEquals("Java Profiler", slugs.displayName(install.id()), "the shared name wins for the twin");
        assertEquals("java-profiler", slugs.slug(install.id()));
        assertEquals("java-profiler", slugs.slug(shared.id()));
        assertEquals(List.of(new TaskSlugs.Entry(shared.id(), "java-profiler")), slugs.entries(),
                "one identity per checkout: the twin contributes exactly one entry");
    }

    @Test
    void installOnlyCheckoutKeepsItsOwnName(@TempDir Path dir) {
        Repository install = install(dir.resolve("only"), "Install Only");
        TaskSlugs slugs = TaskSlugs.of(List.of(), List.of(install));
        assertEquals(install.id(), slugs.canonicalId(install.id()));
        assertEquals("install-only", slugs.slug(install.id()));
        assertEquals(List.of(new TaskSlugs.Entry(install.id(), "install-only")), slugs.entries());
    }

    @Test
    void unknownIdIsItsOwnIdentityAndHasNoName(@TempDir Path dir) {
        TaskSlugs slugs = TaskSlugs.of(List.of(), List.of());
        RepositoryId unknown = RepositoryId.newId();
        assertEquals(unknown, slugs.canonicalId(unknown));
        assertNull(slugs.slug(unknown));
        assertTrue(slugs.entries().isEmpty());
    }

    @Test
    void twoCheckoutsSharingANameBothSurfaceTheirEntry(@TempDir Path dir) {
        SharedProject shared = shared(dir.resolve("one"), "same-name");
        Repository install = install(dir.resolve("two"), "same-name");
        TaskSlugs slugs = TaskSlugs.of(List.of(shared), List.of(install));
        assertEquals(
                List.of(new TaskSlugs.Entry(shared.id(), "same-name"),
                        new TaskSlugs.Entry(install.id(), "same-name")),
                slugs.entries(),
                "both entries surface; the caller refuses the slug rather than picking a winner");
    }

    @Test
    void anUnsluggableSharedNameHidesTheTwinRatherThanFallingBackToTheInstallName(@TempDir Path dir) {
        Path checkout = dir.resolve("checkout");
        SharedProject shared = shared(checkout, "---");
        Repository install = install(checkout, "good-name");
        TaskSlugs slugs = TaskSlugs.of(List.of(shared), List.of(install));
        assertNull(slugs.slug(shared.id()), "an unsluggable shared name names nothing");
        assertNull(slugs.slug(install.id()), "the twin inherits the shared name, even unsluggable");
        assertTrue(slugs.entries().isEmpty());
    }

    @Test
    void idsListsBothRegistriesForDisplayMaps(@TempDir Path dir) {
        SharedProject shared = shared(dir.resolve("one"), "one");
        Repository install = install(dir.resolve("two"), "two");
        TaskSlugs slugs = TaskSlugs.of(List.of(shared), List.of(install));
        assertEquals(List.of(shared.id(), install.id()), slugs.ids());
        assertEquals("one", slugs.displayName(shared.id()));
        assertEquals("two", slugs.displayName(install.id()));
    }

    @Test
    void slugProjectionMatchesTheEstablishedRules() {
        assertEquals("lower-case-1", TaskSlugs.slugOf("Lower CASE 1"));
        assertEquals("inner-hyphens", TaskSlugs.slugOf("inner--hyphens"));
        assertEquals("a", TaskSlugs.slugOf("a"));
        assertNull(TaskSlugs.slugOf(""));
        assertNull(TaskSlugs.slugOf("---"));
        assertNull(TaskSlugs.slugOf(null));
        assertEquals("a".repeat(60), TaskSlugs.slugOf("a".repeat(200)), "capped at 60 characters");
        assertEquals("trailing", TaskSlugs.slugOf("trailing---"), "trailing separators are dropped");
    }
}
