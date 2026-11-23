package app.drydock.intellij.tasks;

import app.drydock.sdlc.SelectedWorkflow.SourceDescriptor;
import app.drydock.sdlc.SelectedWorkflow.SourceKind;

import java.util.Objects;

/**
 * Pure text for the invocation's workflow-provenance indicator. Never renders
 * credentials, absolute paths or remote coordinates: the project is shown by its
 * leaf name only and a source path only when it is a plain repository-relative one.
 */
public final class WorkflowSourceLabel {

    public enum Provenance { DEFAULT, PROJECT, DEFAULT_ONCE, NOT_REGISTERED }

    private static final int MAX = 64;

    private WorkflowSourceLabel() {
    }

    /** Label for a not-yet-edited source; {@code source} is used only for PROJECT. */
    public static String of(Provenance provenance, String project, SourceDescriptor source) {
        Objects.requireNonNull(provenance, "provenance");
        return switch (provenance) {
            case DEFAULT -> "Drydock default";
            case DEFAULT_ONCE -> "Drydock default (this time only)";
            case NOT_REGISTERED -> "Drydock default (project not registered)";
            case PROJECT -> "Project " + leaf(project) + ": " + where(Objects.requireNonNull(source, "source"));
        };
    }

    /** Label once the user changed the generated text; names what it was based on. */
    public static String edited(Provenance base, String project, SourceDescriptor source) {
        return "Edited for this invocation; based on " + of(base, project, source);
    }

    private static String where(SourceDescriptor source) {
        if (source.kind() == SourceKind.INLINE) {
            return "inline text";
        }
        String path = source.relativeSource().orElse("");
        boolean suspicious = path.contains("://") || path.indexOf('@') >= 0;
        return suspicious ? leaf(path) : clean(path);
    }

    private static String leaf(String name) {
        String s = name == null ? "" : name;
        int cut = Math.max(Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\')), s.lastIndexOf(':'));
        String leaf = clean(s.substring(cut + 1));
        return leaf.isEmpty() ? "(unnamed)" : leaf;
    }

    private static String clean(String s) {
        String t = s.replaceAll("\\p{Cntrl}", "").strip();
        return t.length() > MAX ? t.substring(0, MAX) : t;
    }
}
