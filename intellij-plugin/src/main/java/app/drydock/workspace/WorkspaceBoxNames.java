package app.drydock.workspace;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The pure naming rules for workspace boxes: a deterministic default name
 * derived from the repository (one box per user and repo, per the
 * workspace-persistent-sessions investigation), generation names for
 * re-created boxes, and the validity guard every box name must pass before
 * it reaches the workspaces CLI. No method here runs a process or touches
 * I/O — callers pass the parsed {@code workspaces list} name set in
 * themselves, so the naming rules stay testable without a CLI.
 */
public final class WorkspaceBoxNames {

    private static final String PREFIX = "drydock-";

    /**
     * The box-name guard: lower-case alphanumeric first character, then
     * lower-case alphanumeric or dashes, at most 63 characters in total
     * (ASM-11). A name that passes this never needs option escaping when it
     * is handed to the workspaces CLI.
     */
    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private WorkspaceBoxNames() {
    }

    /**
     * Whether {@code name} is a box name the workspaces CLI accepts and a
     * binding may persist (INV-6: the name is validated, never
     * option-escaped). {@code null} is not a valid name.
     */
    public static boolean validate(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /**
     * The deterministic default box name for a repository (AC-15
     * determinism): {@code drydock-} plus the lower-cased repository name
     * with every run of characters outside {@code [a-z0-9]} collapsed to a
     * single dash, and leading and trailing dashes of that repository part
     * trimmed away. The locale is fixed to {@link Locale#ROOT} so the same
     * repository always yields the same name on any machine.
     *
     * <p>Examples: {@code Profiling.RT-AI/Plugins} → {@code
     * drydock-profiling-rt-ai-plugins}; {@code Café} → {@code
     * drydock-caf}; {@code .hidden.dir} → {@code
     * drydock-hidden-dir}. A degenerate repository name that trims to
     * nothing leaves the bare {@code drydock-} prefix, which is itself a
     * valid name.</p>
     */
    public static String defaultName(String repo) {
        Objects.requireNonNull(repo, "repo");
        String sanitized = repo.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return PREFIX + sanitized;
    }

    /**
     * The name for a re-created box: the first of {@code <base>-2},
     * {@code <base>-3}, … that is absent from {@code existingNames}, the
     * names the caller parsed from {@code workspaces list}. Re-created boxes
     * never reuse a name the repo mapping has pointed at (INV-25), so a
     * stopped or gone box is always left in place under its old name.
     *
     * @throws IllegalArgumentException if {@code base} fails {@link #validate}
     */
    public static String nextGeneration(String base, Set<String> existingNames) {
        if (!validate(base)) {
            throw new IllegalArgumentException("not a valid box name: " + base);
        }
        Objects.requireNonNull(existingNames, "existingNames");
        for (int generation = 2; ; generation++) {
            String candidate = base + "-" + generation;
            if (!existingNames.contains(candidate)) {
                return candidate;
            }
        }
    }
}
