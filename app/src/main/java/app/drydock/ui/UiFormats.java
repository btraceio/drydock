package app.drydock.ui;

import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatus;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Small shared presentation helpers used across the UI packages (sidebar,
 * review, explorer): relative "time ago" text, branch-name text, the
 * A/D/M change-marker style class, breadcrumb segment nodes, and the
 * mnemonic-free wrapper for controls showing external text. Public
 * only because {@code app.drydock.ui.review} and {@code app.drydock.ui.explorer}
 * share them; nothing outside the UI layer should call these.
 */
public final class UiFormats {

    /** Wrapping width for tooltips: wide enough for paths, narrow enough to scan. */
    public static final int TOOLTIP_LINE_WIDTH = 72;

    private UiFormats() {
    }

    /**
     * Word-wraps {@code value} to {@code width} columns. Embedded newlines
     * are intentional structure, not overflow, and are preserved. Words
     * break on spaces; a token wider than the line (a path, typically — no
     * spaces) breaks at path separators, falling back to a hard break.
     */
    public static String wrap(String value, int width) {
        List<String> out = new ArrayList<>();
        for (String line : value.split("\n", -1)) {
            if (line.length() <= width) {
                out.add(line);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : line.split(" ")) {
                for (String piece : breakLongToken(word, width)) {
                    if (current.length() == 0) {
                        current.append(piece);
                    } else if (current.length() + 1 + piece.length() <= width) {
                        current.append(' ').append(piece);
                    } else {
                        out.add(current.toString());
                        current.setLength(0);
                        current.append(piece);
                    }
                }
            }
            out.add(current.toString());
        }
        return String.join("\n", out);
    }

    /** A token wider than the line breaks at path separators (keeping the slash); a token with none hard-breaks at the width. */
    private static List<String> breakLongToken(String token, int width) {
        if (token.length() <= width) {
            return List.of(token);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < token.length()) {
            int end = Math.min(start + width, token.length());
            if (end < token.length()) {
                int slash = token.lastIndexOf('/', end - 1);
                if (slash > start) {
                    end = slash + 1;
                }
            }
            pieces.add(token.substring(start, end));
            start = end;
        }
        return pieces;
    }

    /**
     * One tooltip field line: {@code label: value}, with continuation lines
     * padded to the label column so a long value reads as one indented field
     * instead of spilling into unlabeled lines. (The pad is approximate in a
     * proportional font; it is still clearly better than column 0.)
     */
    public static String labeledLine(String label, String value, int width) {
        String prefix = label + ": ";
        int valueWidth = Math.max(1, width - prefix.length());
        String[] lines = wrap(value, valueWidth).split("\n", -1);
        String pad = " ".repeat(prefix.length());
        StringBuilder out = new StringBuilder(prefix).append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            out.append('\n').append(pad).append(lines[i]);
        }
        return out.toString();
    }

    /**
     * Replaces the user-home prefix of a local absolute path with {@code ~}
     * so a tooltip's directory line stays short. A path outside (or without)
     * the home prefix — notably {@code host:/remote/path} — is unchanged.
     */
    public static String abbreviateHome(String pathText) {
        String home = System.getProperty("user.home", "");
        if (home.isBlank() || home.equals("/")) {
            return pathText;
        }
        if (pathText.equals(home)) {
            return "~";
        }
        if (pathText.startsWith(home + "/")) {
            return "~" + pathText.substring(home.length());
        }
        return pathText;
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

    /** Wall-clock time of day, e.g. {@code 16:40}, in the local time zone. */
    public static String timeOfDay(Instant instant) {
        return java.time.LocalTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Rough remaining time until a future instant, e.g. {@code ~5h 0m}; {@code expired} if past. */
    public static String timeUntil(Instant instant) {
        long seconds = java.time.Duration.between(Instant.now(), instant).getSeconds();
        if (seconds <= 0) {
            return "expired";
        }
        if (seconds < 60) {
            return "<1m";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return "~" + (hours > 0 ? hours + "h " : "") + minutes + "m";
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

    /** Compact upper-bound currency used by resume-cost badges. */
    public static String maximumUsd(double usd) {
        return usd >= 10 ? "≤$%.0f".formatted(usd) : "≤$%.2f".formatted(usd);
    }

    /** Human-readable token count for resume-cost tooltips. */
    public static String tokenCount(long tokens) {
        return tokens >= 1_000 ? "%,d".formatted(tokens) : Long.toString(tokens);
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

    /**
     * Shows the control's text exactly as given. A JavaFX Button (like any
     * Labeled except Label) parses a mnemonic from its text, which swallows an
     * underscore ("Integer.MAX_VALUE" renders as "Integer.MAXVALUE"); text from
     * the agent, from code or from file paths must never be read that way.
     */
    public static <T extends Labeled> T literal(T control) {
        control.setMnemonicParsing(false);
        return control;
    }
}
