package app.drydock.review.tour;

import java.util.Objects;

/** The agent's claim about one location a step affects (provenance CLAIMED). */
public record ImpactNote(String file, int line, String text) {

    public ImpactNote {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(text, "text");
    }
}
