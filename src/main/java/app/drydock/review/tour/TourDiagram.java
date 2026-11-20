package app.drydock.review.tour;

import java.util.List;
import java.util.Objects;

/**
 * A monospace diagram on a tour step (spec §3): a caption and stages that
 * are revealed in reading order -- the drawing builds up as the reader
 * goes, the same segmenting the PREDICT reveal applies to code.
 *
 * <p>Deliberately text, not graphics: the agent draws with box-drawing
 * characters and spaces in the panel's existing monospace font, no
 * renderer, no WebView, no animation. A stage <em>continues</em> the
 * drawing above it (the stages are concatenated top to bottom); the panel
 * reveals one per click, so a diagram can hold back the punchline the
 * way a withheld narrative does. Validation lives in {@link TourValidator}
 * -- spacing must survive the font, which is why tabs are rejected:
 * a tab stops wherever the renderer's font says, and the drawing that
 * aligned in the agent's head arrives crooked.</p>
 */
public record TourDiagram(String caption, List<String> stages) {

    public TourDiagram {
        Objects.requireNonNull(caption, "caption");
        Objects.requireNonNull(stages, "stages");
        stages = List.copyOf(stages);
    }

    /** The first {@code revealed} stages, joined as one drawing. */
    public String visibleText(int revealed) {
        int bounded = Math.clamp(revealed, 0, stages.size());
        return String.join("\n", stages.subList(0, bounded));
    }
}
