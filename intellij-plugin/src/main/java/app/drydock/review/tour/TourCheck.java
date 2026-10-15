package app.drydock.review.tour;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One check that gates a tour step.
 *
 * <p>PREDICT and TRACE carry two to four choices and a 0-based answer index;
 * they are graded locally. RISK carries neither: the reviewer's free-text
 * answer is judged by the agent. A top-level check carries at least one
 * alternate, offered after a wrong answer; alternates carry none.</p>
 */
public record TourCheck(String id, Kind kind, String prompt, List<Choice> choices, OptionalInt answer,
                        String explanation, List<TourCheck> alternates) {

    public enum Kind {
        PREDICT("predict"),
        TRACE("trace"),
        RISK("risk");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<Kind> fromWire(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.wireName.equals(normalized)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** One answer option; {@code at} names the real call site a TRACE choice stands for. */
    public record Choice(String text, Optional<Location> at) {
        public Choice {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(at, "at");
        }
    }

    public record Location(String file, int line) {
        public Location {
            Objects.requireNonNull(file, "file");
        }
    }

    public TourCheck {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(explanation, "explanation");
        choices = List.copyOf(choices);
        alternates = List.copyOf(alternates);
    }

    /** Whether this check is graded locally against its answer key. */
    public boolean gradedLocally() {
        return kind != Kind.RISK;
    }

    /** How many versions this check has: itself plus its alternates. */
    public int versions() {
        return 1 + alternates.size();
    }

    /** Version {@code attempt}: 0 is this check, {@code k} is alternate {@code k - 1}. */
    public TourCheck version(int attempt) {
        if (attempt <= 0) {
            return this;
        }
        return alternates.get(Math.min(attempt, alternates.size()) - 1);
    }
}
