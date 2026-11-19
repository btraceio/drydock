package app.drydock.sdlc;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of path selection: what a later reader may capture. Entry
 * paths are canonical root-relative {@code /} paths (links in selected
 * parents are present as {@link LinkEntry}); no file bytes are held here.
 * {@code logicalToCanonical} maps each selected logical path to its canonical
 * location.
 */
public record WorkflowSelectionPlan(List<Entry> entries, Map<String, String> logicalToCanonical) {

    public WorkflowSelectionPlan {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        logicalToCanonical = Map.copyOf(Objects.requireNonNull(logicalToCanonical, "logicalToCanonical"));
    }

    public sealed interface Entry permits DirectoryEntry, FileEntry, LinkEntry {
        String path();
    }

    public record DirectoryEntry(String path) implements Entry {
    }

    public record FileEntry(String path, boolean executable) implements Entry {
    }

    /** {@code target} is relative to the link's parent and contained in the bundle. */
    public record LinkEntry(String path, String target) implements Entry {
    }

    public long count(Class<? extends Entry> kind) {
        return entries.stream().filter(kind::isInstance).count();
    }
}
