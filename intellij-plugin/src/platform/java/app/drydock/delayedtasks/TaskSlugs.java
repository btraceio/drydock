package app.drydock.delayedtasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.projects.SharedProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The one dual-registry resolution of a task repository's display name and
 * slug (P2). Tasks are stored under the shared registry's id (the registry
 * the standalone app and this IDE both read and write), but the install's own
 * catalog remains the only record for checkouts the shared list has never
 * carried -- so every display-name lookup that used to read the install
 * catalog alone resolved to nothing for shared-only projects, and a
 * cross-era task store (legacy tasks stamped with install ids, current ones
 * with shared ids) could never name one checkout with one slug.
 *
 * <p>The rules, decided once here for every caller:</p>
 * <ul>
 *   <li><strong>Shared wins.</strong> When both registries know a checkout,
 *   the shared registry's display name is the slug's source. The install's
 *   name is used only for checkouts the shared list does not carry.</li>
 *   <li><strong>One identity per checkout.</strong> A checkout both registries
 *   know is ONE identity for slug purposes: the install id resolves to the
 *   shared id (root equality; both registries store canonical roots). Both
 *   stores' tasks then share one slug, and {@code <slug>:<id>} references
 *   resolve across the era boundary. This mirrors the create-time remap
 *   ({@link SharedProjectResolver}) and the mirror flows.</li>
 *   <li><strong>Ambiguity still refuses.</strong> Two genuinely different
 *   checkouts whose names slug to the same string both lose their slug:
 *   neither is advertised, neither resolves, and callers that refuse on an
 *   ambiguous slug keep doing so. One checkout twinned in both registries is
 *   one entry, never an ambiguity.</li>
 * </ul>
 *
 * <p>Construction is cheap (two small lists) and touches no I/O; instances
 * are built per call site from the caller's read of both registries, so the
 * answer is always as fresh as the caller's.</p>
 */
public final class TaskSlugs {

    /** One resolvable identity: the canonical id and the slug of its display name. */
    public record Entry(RepositoryId id, String slug) {
        public Entry {
            if (id == null) {
                throw new NullPointerException("id");
            }
            if (slug == null || slug.isBlank()) {
                throw new IllegalArgumentException("an entry carries a resolvable slug");
            }
        }
    }

    /** stored id -> canonical identity (shared id when root-twinned, else the id itself). */
    private final Map<RepositoryId, RepositoryId> canonical;
    /** stored id -> the display name to use (shared name wins). */
    private final Map<RepositoryId, String> displayName;
    /** every stored id, shared entries first, then install-only ones, in registry order. */
    private final List<RepositoryId> ids;
    private final List<SharedProject> shared;
    private final List<Repository> install;

    private TaskSlugs(List<SharedProject> shared, List<Repository> install) {
        this.shared = shared;
        this.install = install;
        Map<java.nio.file.Path, SharedProject> sharedByRoot = new LinkedHashMap<>();
        for (SharedProject project : shared) {
            sharedByRoot.putIfAbsent(project.root(), project);
        }
        Map<RepositoryId, RepositoryId> canonical = new LinkedHashMap<>();
        Map<RepositoryId, String> names = new LinkedHashMap<>();
        List<RepositoryId> all = new ArrayList<>();
        for (SharedProject project : shared) {
            canonical.put(project.id(), project.id());
            names.put(project.id(), project.displayName());
            all.add(project.id());
        }
        for (Repository repository : install) {
            SharedProject twin = sharedByRoot.get(repository.root());
            if (twin != null) {
                canonical.put(repository.id(), twin.id());
                names.put(repository.id(), twin.displayName());
            } else {
                canonical.put(repository.id(), repository.id());
                names.put(repository.id(), repository.displayName());
            }
            all.add(repository.id());
        }
        this.canonical = Map.copyOf(canonical);
        this.displayName = Map.copyOf(names);
        this.ids = List.copyOf(all);
    }

    /** Built over the two registries as the caller read them; pass empty lists for the missing one. */
    public static TaskSlugs of(List<SharedProject> shared, List<Repository> install) {
        return new TaskSlugs(List.copyOf(shared), List.copyOf(install));
    }

    /** Every id stored in either registry; the dual of {@link #entries()}. */
    public List<RepositoryId> ids() {
        return ids;
    }

    /**
     * One entry per checkout — the shared id for a twinned checkout, the install id
     * for an install-only one — carrying the slug of the winning display name.
     * Entries whose name has no slug characters are omitted; two entries may
     * share a slug (two checkouts, same name) and callers decide about
     * ambiguity.
     */
    public List<Entry> entries() {
        Set<RepositoryId> seen = new LinkedHashSet<>();
        List<Entry> out = new ArrayList<>();
        for (SharedProject project : shared) {
            add(project.id(), project.displayName(), seen, out);
        }
        for (Repository repository : install) {
            RepositoryId identity = canonical.get(repository.id());
            if (!identity.equals(repository.id())) {
                continue; // twinned into a shared entry already emitted
            }
            add(repository.id(), repository.displayName(), seen, out);
        }
        return List.copyOf(out);
    }

    private static void add(RepositoryId id, String name, Set<RepositoryId> seen, List<Entry> out) {
        String slug = slugOf(name);
        if (slug != null && seen.add(id)) {
            out.add(new Entry(id, slug));
        }
    }

    /** The canonical identity of a stored id: the shared id when one names the same checkout, else the id itself. */
    public RepositoryId canonicalId(RepositoryId id) {
        return canonical.getOrDefault(id, id);
    }

    /** The display name to use for a stored id (shared wins on a twin), or null for an id neither registry knows. */
    public String displayName(RepositoryId id) {
        return displayName.get(id);
    }

    /** The P2 slug of a stored id, or null when the id is unknown or its name has no slug characters. */
    public String slug(RepositoryId id) {
        return slugOf(displayName.get(id));
    }

    /** The slug function for the task core's {@code Function<RepositoryId, String>} parameters. */
    public Function<RepositoryId, String> slugger() {
        return this::slug;
    }

    /** The canonicalizing function for {@link app.drydock.tasks.TaskGraph#of(
     * app.drydock.tasks.TaskSnapshot, Function, Function)}. */
    public Function<RepositoryId, RepositoryId> canonicalizer() {
        return this::canonicalId;
    }

    /** The display-name slug (P2): lowercase letters, digits and inner hyphens, capped at 60. */
    public static String slugOf(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(61);
        for (int i = 0; i < name.length() && out.length() < 60; i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == '-') {
            out.setLength(length - 1);
        }
        return out.isEmpty() ? null : out.toString();
    }
}
