package app.drydock.intellij.review;

import com.intellij.ide.util.PropertiesComponent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The checkouts opened for review with no agent (a pull request's
 * Open in Review Workbench): they count as covered for the Review tab
 * (Q8) although no session runs there, so their workbench loads.
 *
 * <p>Kept in the application's properties as the window's base path,
 * lexically normalized, as {@code TaskWindows} keeps its task windows: the
 * path the window was opened on is its base path, so no real-path lookup
 * (file I/O) is needed to match it. An IDE quit keeps the entry (the window
 * is restored and its workbench still loads), an ordinary close of the
 * window forgets it, and a later registration forgets an entry whose
 * directory is gone. Reads are in memory only.</p>
 */
public final class ReviewCheckouts {

    private static final String KEY = "drydock.reviewCheckouts";

    private ReviewCheckouts() {
    }

    /**
     * Registers {@code checkout}, the path its window is opened on, and
     * forgets the entries whose directory is gone: an entry kept across a
     * quit whose worktree was removed meanwhile. Checks the disk: off the EDT.
     */
    public static void register(Path checkout) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        List<String> current = values(properties);
        List<String> updated = with(existing(current, path -> Files.isDirectory(Path.of(path))), normalized(checkout));
        if (!updated.equals(current)) {
            properties.setValues(KEY, updated.toArray(String[]::new));
        }
    }

    /** Forgets {@code checkout} (a window's base path), when it was registered. Any thread. */
    public static void forget(Path checkout) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        List<String> current = values(properties);
        List<String> updated = without(current, normalized(checkout));
        if (!updated.equals(current)) {
            // An empty list, not unsetValue: the list value setValues stores is not cleared by it.
            properties.setValues(KEY, updated.toArray(String[]::new));
        }
    }

    /** Whether {@code checkout} (a window's base path) was registered. In memory; any thread. */
    static boolean covers(Path checkout) {
        return values(PropertiesComponent.getInstance()).contains(normalized(checkout));
    }

    /** The stored form (pure): absolute and lexically normalized, never resolved on disk. */
    static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    static List<String> with(List<String> current, String path) {
        Set<String> result = new LinkedHashSet<>(current);
        result.add(path);
        return List.copyOf(result);
    }

    /** The entries {@code exists} keeps (pure over it). */
    static List<String> existing(List<String> current, Predicate<String> exists) {
        return current.stream().filter(exists).toList();
    }

    static List<String> without(List<String> current, String path) {
        return current.stream().filter(entry -> !entry.equals(path)).toList();
    }

    private static List<String> values(PropertiesComponent properties) {
        String[] values = properties.getValues(KEY);
        return values == null ? List.of() : List.copyOf(Arrays.asList(values));
    }
}
