package app.drydock.ui;

import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatus;
import javafx.scene.Node;
import javafx.scene.control.Label;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Small shared presentation helpers used across the UI packages (sidebar,
 * review, explorer): relative "time ago" text, branch-name text, the
 * A/D/M change-marker style class, and breadcrumb segment nodes. Public
 * only because {@code app.drydock.ui.review} and {@code app.drydock.ui.explorer}
 * share them; nothing outside the UI layer should call these.
 */
public final class UiFormats {

    private UiFormats() {
    }

    /** Relative "time ago" for session meta lines (design: {@code branch · 2h ago}). */
    public static String relativeTime(Instant instant) {
        long seconds = Math.max(0, java.time.Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) {
            return "now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        }
        return (seconds / 86400) + "d ago";
    }

    /** Display text for a checkout's branch state: the branch name, or {@code detached@<short-oid>}. */
    public static String branchText(GitStatus status) {
        if (status.branch() instanceof GitBranchState.OnBranch onBranch) {
            return onBranch.name();
        }
        if (status.branch() instanceof GitBranchState.Detached detached) {
            String oid = detached.commitOid();
            return "detached@" + (oid.length() > 7 ? oid.substring(0, 7) : oid);
        }
        return "(unknown)";
    }

    /** Style class for a changed-file's A/D/M marker (Review file list + Finish panel summary). */
    public static String markerStyleClass(String kind) {
        return switch (kind) {
            case "A" -> "marker-added";
            case "D" -> "marker-deleted";
            default -> "marker-modified";
        };
    }

    /**
     * The path-segment nodes of a breadcrumb ({@code seg › seg › …}), for
     * the Review diff header and the Explorer file viewer. Callers add any
     * trailing chrome (read-only chip, gutter toggle) themselves.
     */
    public static List<Node> breadcrumbSegments(Path path) {
        return breadcrumbSegments(path, Integer.MAX_VALUE);
    }

    /**
     * As {@link #breadcrumbSegments(Path)}, keeping at most {@code
     * maxSegments} trailing components and standing in for what it dropped
     * with a leading {@code …}. Elides from the LEFT: the file name is the
     * one segment a reader is never helped by losing.
     */
    public static List<Node> breadcrumbSegments(Path path, int maxSegments) {
        List<Path> segments = new ArrayList<>();
        path.forEach(segments::add);
        boolean elided = segments.size() > maxSegments;
        List<Path> kept = elided
                ? segments.subList(segments.size() - maxSegments, segments.size())
                : segments;

        List<Node> nodes = new ArrayList<>();
        if (elided) {
            nodes.add(segment("…"));
        }
        for (Path part : kept) {
            if (!nodes.isEmpty()) {
                Label sep = new Label("›");
                sep.getStyleClass().add("breadcrumb-separator");
                nodes.add(sep);
            }
            nodes.add(segment(part.toString()));
        }
        return nodes;
    }

    private static Label segment(String text) {
        Label part = new Label(text);
        part.getStyleClass().add("breadcrumb-segment");
        return part;
    }
}
