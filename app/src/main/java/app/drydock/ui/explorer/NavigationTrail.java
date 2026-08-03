package app.drydock.ui.explorer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Explorer's trail of waypoints (Explorer delta, part 1): every real
 * navigation -- a file opened from the rail, a peek promoted with {@code ⏎},
 * the Review tab's {@code ⤢} -- appends one, and {@code ⌘[} / {@code ⌘]}
 * walk it.
 *
 * <p><strong>Browser semantics, deliberately.</strong> Navigating from the
 * middle of the trail truncates the forward branch, and re-opening the file
 * already at the cursor is not a navigation at all -- it only updates that
 * waypoint's remembered scroll line. Both are what a reader expects from
 * back/forward, and both are the difference between a trail that reads as a
 * path and one that reads as a log.</p>
 *
 * <p>The trail is bounded ({@link #CAPACITY} waypoints, oldest evicted
 * first) because it is per-session and persisted; pinned waypoints are
 * exempt, which is the whole point of the pin. A trail of nothing but pins
 * stops evicting rather than dropping a pin.</p>
 *
 * <p>Pure model, no JavaFX: the eviction/truncation arithmetic is exactly
 * the part that regresses silently, and it is unit-tested rather than
 * eyeballed.</p>
 */
public final class NavigationTrail {

    /** Waypoint cap; pinned waypoints do not count against eviction. */
    public static final int CAPACITY = 20;

    /**
     * One place the reader has been: a file, the label its chip shows, the
     * line to restore on return, and whether the user pinned it against
     * eviction.
     */
    public record Waypoint(Path file, String label, int line, boolean pinned) {
        public Waypoint {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(label, "label");
            if (line < 1) {
                line = 1;
            }
        }

        Waypoint withLine(int newLine) {
            return new Waypoint(file, label, newLine, pinned);
        }

        Waypoint withPinned(boolean newPinned) {
            return new Waypoint(file, label, line, newPinned);
        }
    }

    private final List<Waypoint> waypoints = new ArrayList<>();

    /** Index of the current waypoint, or -1 while the trail is empty. */
    private int cursor = -1;

    /** The trail as chips render it, oldest first. */
    public List<Waypoint> waypoints() {
        return List.copyOf(waypoints);
    }

    public int cursor() {
        return cursor;
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    public Optional<Waypoint> current() {
        return cursor < 0 ? Optional.empty() : Optional.of(waypoints.get(cursor));
    }

    public boolean canGoBack() {
        return cursor > 0;
    }

    public boolean canGoForward() {
        return cursor >= 0 && cursor < waypoints.size() - 1;
    }

    /**
     * Appends a waypoint for a real navigation and makes it current.
     *
     * <p>Re-opening the file the cursor is already on is a no-op except for
     * the remembered line: without that, clicking the same rail row twice
     * would grow a trail of identical chips, and returning along it would
     * step through the same file over and over.</p>
     *
     * @return true when the trail actually gained a waypoint
     */
    public boolean push(Path file, String label, int line) {
        Objects.requireNonNull(file, "file");
        if (cursor >= 0 && waypoints.get(cursor).file().equals(file)) {
            waypoints.set(cursor, waypoints.get(cursor).withLine(line));
            return false;
        }
        // Browser semantics: navigating from mid-trail drops what was ahead.
        while (waypoints.size() > cursor + 1) {
            waypoints.remove(waypoints.size() - 1);
        }
        waypoints.add(new Waypoint(file, label, line, false));
        cursor = waypoints.size() - 1;
        evict();
        return true;
    }

    /** Remembers where the reader had scrolled the current waypoint's file to. */
    public void rememberLine(int line) {
        if (cursor >= 0) {
            waypoints.set(cursor, waypoints.get(cursor).withLine(line));
        }
    }

    public Optional<Waypoint> back() {
        return canGoBack() ? goTo(cursor - 1) : Optional.empty();
    }

    public Optional<Waypoint> forward() {
        return canGoForward() ? goTo(cursor + 1) : Optional.empty();
    }

    /** Moves the cursor to {@code index}; out-of-range indices are ignored. */
    public Optional<Waypoint> goTo(int index) {
        if (index < 0 || index >= waypoints.size()) {
            return Optional.empty();
        }
        cursor = index;
        return Optional.of(waypoints.get(index));
    }

    /** 📌 on the current waypoint. Returns its new pinned state. */
    public boolean togglePin() {
        if (cursor < 0) {
            return false;
        }
        Waypoint flipped = waypoints.get(cursor).withPinned(!waypoints.get(cursor).pinned());
        waypoints.set(cursor, flipped);
        return flipped.pinned();
    }

    /** Replaces the whole trail (restore from persistence). */
    public void restore(List<Waypoint> restored, int restoredCursor) {
        waypoints.clear();
        waypoints.addAll(restored);
        cursor = waypoints.isEmpty() ? -1 : Math.max(0, Math.min(restoredCursor, waypoints.size() - 1));
        evict();
    }

    /**
     * Drops the oldest unpinned waypoints until the trail fits {@link
     * #CAPACITY}. A trail whose every waypoint is pinned simply stops
     * evicting: the pin is a promise, and silently breaking the oldest one
     * would make it worthless exactly when the reader is deep enough to need
     * it.
     */
    private void evict() {
        while (waypoints.size() > CAPACITY) {
            int victim = -1;
            for (int i = 0; i < waypoints.size(); i++) {
                // Never the waypoint the reader is standing on: it is
                // usually the one just pushed, and evicting it would make a
                // navigation into a no-op the moment the trail filled up.
                if (!waypoints.get(i).pinned() && i != cursor) {
                    victim = i;
                    break;
                }
            }
            if (victim < 0) {
                return;
            }
            waypoints.remove(victim);
            if (victim <= cursor) {
                cursor--;
            }
        }
        if (cursor < 0 && !waypoints.isEmpty()) {
            cursor = 0;
        }
    }
}
